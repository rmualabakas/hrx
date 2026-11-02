package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.dao.eval.CampagneDAO;
import cd.mura.hrx.dao.eval.EvaluationDAO;
import cd.mura.hrx.dao.eval.ModeleFicheDAO;
import cd.mura.hrx.entity.eval.Campagne;
import cd.mura.hrx.entity.eval.Evaluation;
import cd.mura.hrx.entity.eval.ModeleFiche;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.Year;
import java.util.*;

/** Campagnes d'évaluation (DCHT) : création, paramétrage, lancement, clôture, archivage et avancement. */
@Named
@ViewScoped
@Getter
@Setter
public class CampagneAdminView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private CampagneDAO campagneDAO;
    @EJB private EvaluationDAO evaluationDAO;
    @EJB private ModeleFicheDAO modeleFicheDAO;
    @EJB private cd.mura.hrx.dao.config.UserDAO userDAO;
    private long evaluablesActifs;

    private List<Campagne> campagnes = new ArrayList<>();
    private List<ModeleFiche> modeles = new ArrayList<>();
    private Map<Integer, long[]> stats = new HashMap<>(); // [initiées, soumises, évaluées, validées]
    private Campagne edit = new Campagne();
    private Integer editModeleId;

    @PostConstruct
    public void init() { charger(); }

    public void charger() {
        campagnes = campagneDAO.findAll();
        evaluablesActifs = userDAO.findAllActif().stream().filter(u -> u.getEvaluable() == 1).count();
        modeles = modeleFicheDAO.findActifs();
        stats.clear();
        for (Campagne c : campagnes) {
            long[] s = new long[4];
            for (Evaluation e : evaluationDAO.findByCampagne(c.getId())) {
                s[0]++;
                if (Evaluation.SOUMISE.equals(e.getStatut())) s[1]++;
                if (Evaluation.EVALUEE.equals(e.getStatut())) s[2]++;
                if (Evaluation.VALIDEE.equals(e.getStatut())) s[3]++;
            }
            stats.put(c.getId(), s);
        }
    }

    public long stat(Campagne c, int i) { long[] s = stats.get(c.getId()); return s == null ? 0 : s[i]; }
    /** Taux de réalisation : fiches validées rapportées au nombre attendu saisi par la DCHT (sinon aux fiches initiées). */
    /** Base de calcul : le nombre attendu saisi, ou le nombre de fiches réellement ouvertes s'il est plus grand. */
    public long base(Campagne c) { return Math.max(c.getEffectifAttendu(), stat(c, 0)); }
    /** Fiches ouvertes au-delà du nombre attendu saisi (0 si aucun dépassement). */
    public long depassement(Campagne c) { return Math.max(0, stat(c, 0) - c.getEffectifAttendu()); }
    public int taux(Campagne c) { long b = base(c); return b == 0 ? 0 : (int) Math.round(100.0 * stat(c, 3) / b); }
    public int tauxIntegration(Campagne c) { long b = base(c); return b == 0 ? 0 : (int) Math.round(100.0 * stat(c, 0) / b); }
    public int pct(Campagne c, long v) { long b = base(c); return b == 0 ? 0 : (int) Math.round(100.0 * v / b); }
    public long restantes(Campagne c) { return Math.max(0, c.getEffectifAttendu() - stat(c, 0)); }
    public boolean isOuverteExistante() { return campagnes.stream().anyMatch(Campagne::isOuverte); }
    public List<Integer> getExercices() { int y = Year.now().getValue(); return List.of(y + 1, y, y - 1); }

    public void nouvelle() {
        edit = new Campagne();
        int y = Year.now().getValue();
        boolean mp = LocalDate.now().getMonthValue() <= 8;
        edit.setExercice(y); edit.setType(mp ? Campagne.MI_PARCOURS : Campagne.FIN_ANNEE);
        edit.setLibelle((mp ? "Mi-parcours " : "Fin d'année ") + y);
        edit.setEvaluerCompetences(!mp);
        edit.setDateDebut(mp ? LocalDate.of(y, 6, 1) : LocalDate.of(y, 11, 15));
        edit.setDateFin(mp ? LocalDate.of(y, 7, 31) : LocalDate.of(y + 1, 1, 31));
        editModeleId = modeles.isEmpty() ? null : modeles.get(0).getId();
        edit.setEffectifAttendu((int) evaluablesActifs);
    }
    public void modifier(Campagne c) { edit = c; editModeleId = c.getModeleParDefaut() == null ? null : c.getModeleParDefaut().getId(); }

    public void enregistrer() {
        authorization.requirePrivilege("CAMP_CREATE");
        if (edit.getLibelle() == null || edit.getLibelle().isBlank()) { error("Libellé requis", ""); return; }
        if (edit.getDateDebut() != null && edit.getDateFin() != null && edit.getDateFin().isBefore(edit.getDateDebut())) { error("Dates incohérentes", "L'échéance doit suivre la date de début."); return; }
        if (edit.getEffectifAttendu() <= 0) { error("Nombre d'évaluations attendues requis", "Indiquez combien de collaborateurs doivent être évalués sur cette campagne (" + evaluablesActifs + " comptes évaluables actifs aujourd'hui)."); return; }
        if (edit.getId() > 0 && edit.getEffectifAttendu() < stat(edit, 0)) { error("Nombre attendu trop bas", stat(edit, 0) + " fiches sont déjà ouvertes sur cette campagne. Le nombre attendu ne peut pas être inférieur."); return; }
        edit.setModeleParDefaut(editModeleId == null ? null : modeleFicheDAO.find(editModeleId));
        boolean creation = edit.getId() == 0;
        if (creation) edit.setCreePar(loginBean.getUsers().getId());
        edit.setDateMaj(new Date());
        edit = campagneDAO.save(edit);
        auditService.log(creation ? "CREATE" : "UPDATE", "Évaluations", "Campagne", edit.getId(), (creation ? "Création" : "Modification") + " d'une campagne", edit.getLibelle(), true);
        info(creation ? "Campagne créée" : "Campagne mise à jour", edit.getLibelle());
        org.primefaces.PrimeFaces.current().ajax().addCallbackParam("ok", true);
        charger();
    }

    public void lancer(Campagne c) {
        authorization.requirePrivilege("CAMP_LAUNCH");
        if (isOuverteExistante()) { error("Campagne déjà ouverte", "Clôturez la campagne en cours avant d'en lancer une autre."); return; }
        c.setStatut(Campagne.OUVERTE); c.setDateLancement(new Date()); c.setDateMaj(new Date());
        campagneDAO.save(c);
        auditService.updated("Évaluations", "Campagne", c.getId(), "Lancement de la campagne", c.getLibelle());
        info("Campagne lancée", "Les N+1 peuvent initier les fiches d'évaluation de leur équipe.");
        charger();
    }
    /** Aligne le nombre attendu sur le nombre de fiches réellement ouvertes. */
    public void ajusterAttendu(Campagne c) {
        authorization.requirePrivilege("CAMP_CREATE");
        long ouvertes = stat(c, 0);
        if (ouvertes <= c.getEffectifAttendu()) return;
        int avant = c.getEffectifAttendu();
        c.setEffectifAttendu((int) ouvertes); c.setDateMaj(new Date());
        campagneDAO.save(c);
        auditService.updated("Évaluations", "Campagne", c.getId(), "Nombre d'évaluations attendues ajusté", c.getLibelle() + " : " + avant + " → " + ouvertes);
        info("Nombre attendu mis à jour", ouvertes + " évaluations attendues pour " + c.getLibelle() + ".");
        charger();
    }

    public void cloturer(Campagne c) {
        authorization.requirePrivilege("CAMP_LAUNCH");
        c.setStatut(Campagne.CLOTUREE); c.setDateCloture(new Date()); c.setDateMaj(new Date());
        campagneDAO.save(c);
        auditService.updated("Évaluations", "Campagne", c.getId(), "Clôture de la campagne", c.getLibelle());
        info("Campagne clôturée", "Plus aucune fiche ne peut être initiée.");
        charger();
    }
    public void archiver(Campagne c) {
        authorization.requirePrivilege("CAMP_LAUNCH");
        c.setStatut(Campagne.ARCHIVEE); c.setDateMaj(new Date());
        campagneDAO.save(c);
        auditService.updated("Évaluations", "Campagne", c.getId(), "Archivage de la campagne", c.getLibelle());
        charger();
    }
    public void supprimer(Campagne c) {
        authorization.requirePrivilege("CAMP_CREATE");
        if (stat(c, 0) > 0) { error("Suppression impossible", "Des fiches existent pour cette campagne : archivez-la."); return; }
        campagneDAO.remove(c);
        auditService.deleted("Évaluations", "Campagne", c.getId(), "Suppression d'une campagne", c.getLibelle());
        charger();
    }

    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
}
