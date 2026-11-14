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
    @EJB private cd.mura.hrx.dao.eval.ObjectifDAO objectifDAO;
    @EJB private cd.mura.hrx.service.NotificationService notifications;
    @EJB private cd.mura.hrx.service.PeriodeEssaiService periodeEssai;
    private long evaluablesActifs;

    private List<Campagne> campagnes = new ArrayList<>();
    private List<ModeleFiche> modeles = new ArrayList<>();
    private Map<Integer, long[]> stats = new HashMap<>(); // [initiées, soumises, évaluées, validées]
    private Campagne edit = new Campagne();
    private Integer editModeleId;
    /** Catégories couvertes par chaque fiche dans le formulaire (id du modèle → codes catégorie) ; une fiche peut en couvrir plusieurs. */
    /** Fiche des directeurs dans le formulaire. */
    private Integer editModeleEncadrantsId;

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
        editModeleEncadrantsId = editModeleId;
    }
    /** Une campagne reste modifiable tant qu'elle n'est pas archivée. */
    public boolean modifiable(Campagne c) { return c != null && !Campagne.ARCHIVEE.equals(c.getStatut()); }
    /** Type et exercice figés dès qu'une fiche d'évaluation existe sur la campagne. */
    public boolean isCadreFige() { return edit != null && edit.getId() > 0 && stat(edit, 0) > 0; }

    public void modifier(Campagne c) {
        edit = campagneDAO.find(c.getId());
        if (edit == null) { error("Campagne introuvable", ""); edit = new Campagne(); return; }
        editModeleId = edit.getModeleParDefaut() == null ? null : edit.getModeleParDefaut().getId();
        editModeleEncadrantsId = edit.getModeleEncadrants() == null ? null : edit.getModeleEncadrants().getId();
    }

    /** Résumé des fiches retenues, pour la carte de la campagne. */
    public List<String[]> fichesDe(Campagne c) {
        String autres = c.getModeleParDefaut() == null ? "Selon la catégorie" : c.getModeleParDefaut().getLibelle();
        String enc = c.getModeleEncadrants() == null ? autres : c.getModeleEncadrants().getLibelle();
        return List.of(new String[]{"Directeurs", enc}, new String[]{"Autres collaborateurs", autres});
    }

    public void enregistrer() {
        authorization.requirePrivilege("CAMP_CREATE");
        if (edit.getLibelle() == null || edit.getLibelle().isBlank()) { error("Libellé requis", ""); return; }
        if (edit.getDateDebut() != null && edit.getDateFin() != null && edit.getDateFin().isBefore(edit.getDateDebut())) { error("Dates incohérentes", "L'échéance doit suivre la date de début."); return; }
        if (edit.getEffectifAttendu() <= 0) { error("Nombre d'évaluations attendues requis", "Indiquez combien de collaborateurs doivent être évalués sur cette campagne (" + evaluablesActifs + " comptes évaluables actifs aujourd'hui)."); return; }
        if (edit.getId() > 0 && edit.getEffectifAttendu() < stat(edit, 0)) { error("Nombre attendu trop bas", stat(edit, 0) + " fiches sont déjà ouvertes sur cette campagne. Le nombre attendu ne peut pas être inférieur."); return; }
        edit.setModeleParDefaut(editModeleId == null ? null : modeleFicheDAO.find(editModeleId));
        edit.setModeleEncadrants(editModeleEncadrantsId == null ? null : modeleFicheDAO.find(editModeleEncadrantsId));
        boolean creation = edit.getId() == 0;
        if (!creation) {
            Campagne enBase = campagneDAO.find(edit.getId());
            if (enBase == null || !modifiable(enBase)) { error("Modification impossible", "Une campagne archivée ne peut plus être modifiée."); return; }
            if (stat(edit, 0) > 0 && (enBase.getExercice() != edit.getExercice() || !enBase.getType().equals(edit.getType()))) {
                error("Type et exercice figés", "Des fiches d'évaluation existent déjà sur cette campagne : son type et son exercice ne peuvent plus changer."); return;
            }
            edit.setStatut(enBase.getStatut());   // le statut ne se change que par lancer / clôturer / archiver
        }
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
        int[] envoi = annoncerOuverture(c);
        cd.mura.hrx.utils.Dialogue.succes("Campagne lancée", "Les N+1 peuvent initier les fiches d'évaluation de leur équipe. E-mail d'annonce envoyé à " + (envoi[0] + envoi[1]) + " collaborateur(s), dont " + envoi[1] + " sans objectif validé."
                + (envoi[2] > 0 ? " " + envoi[2] + " recrue(s) en période d'essai non concernée(s)." : ""));
        charger();
    }
    /** Annonce l'ouverture à chaque collaborateur actif hors période d'essai ; retourne {avec objectifs, sans objectif, en période d'essai}. */
    private int[] annoncerOuverture(Campagne c) {
        Map<Integer, Long> nb = objectifDAO.nbObjectifsValides(c.getExercice());
        int[] n = new int[3];
        int mois = periodeEssai.dureeMois();
        java.time.LocalDate ref = cd.mura.hrx.service.PeriodeEssaiService.reference(c);
        for (cd.mura.hrx.entity.config.Users u : userDAO.findAllActif()) {
            if (u.getEmail() == null || u.getEmail().isBlank()) continue;
            if (cd.mura.hrx.service.PeriodeEssaiService.enEssai(u, ref, mois)) { n[2]++; continue; }   // pas éligible : première évaluation = fin de période d'essai
            long k = nb.getOrDefault(u.getId(), 0L);
            try { notifications.campagneOuverte(c, u, k); n[k > 0 ? 0 : 1]++; } catch (Exception ex) { /* un échec d'envoi ne bloque pas le lancement */ }
        }
        auditService.updated("Évaluations", "Campagne", c.getId(), "Annonce de l'ouverture par e-mail", n[0] + " avec objectifs, " + n[1] + " sans objectif, " + n[2] + " en période d'essai (non concernés)");
        return n;
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
        cd.mura.hrx.utils.Dialogue.succes("Campagne clôturée", "Plus aucune fiche ne peut être initiée.");
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
