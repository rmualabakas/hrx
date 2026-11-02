package cd.mura.hrx.bean.eval;

import cd.mura.hrx.dao.eval.NatureObjectifDAO;
import cd.mura.hrx.entity.eval.NatureObjectif;
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
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Paramétrage des rubriques de la fiche de fixation des objectifs (groupes et natures), par exercice. */
@Named
@ViewScoped
@Getter
@Setter
public class NatureObjectifAdminView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private NatureObjectifDAO dao;

    private List<NatureObjectif> natures = new ArrayList<>();
    private int exerciceApercu = Year.now().getValue();
    private NatureObjectif edit = new NatureObjectif();

    @PostConstruct
    public void init() { charger(); }

    public void charger() { natures = dao.findAll(); }

    public List<String> getGroupes() {
        List<String> g = new ArrayList<>();
        for (NatureObjectif n : natures) if (!g.contains(n.getGroupe())) g.add(n.getGroupe());
        return g;
    }
    public List<NatureObjectif> naturesDu(String groupe) { return natures.stream().filter(n -> n.getGroupe().equals(groupe)).toList(); }
    public int ordreGroupe(String groupe) { return natures.stream().filter(n -> n.getGroupe().equals(groupe)).mapToInt(NatureObjectif::getOrdreGroupe).min().orElse(1); }
    public long nbObjectifs(NatureObjectif n) { return dao.countObjectifs(n.getId()); }
    public boolean applicable(NatureObjectif n) { return n.applicable(exerciceApercu); }
    public long getNbActives() { return natures.stream().filter(n -> n.applicable(exerciceApercu)).count(); }

    public boolean isEditActif() { return edit.getStatut() == 1; }
    public void setEditActif(boolean v) { edit.setStatut(v ? 1 : 0); }

    public List<String> completerGroupe(String q) {
        String f = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return getGroupes().stream().filter(g -> f.isEmpty() || g.toLowerCase(Locale.ROOT).contains(f)).toList();
    }

    public void nouveau(String groupe) {
        edit = new NatureObjectif();
        if (groupe != null && !groupe.isBlank()) {
            edit.setGroupe(groupe);
            edit.setOrdreGroupe(ordreGroupe(groupe));
            edit.setOrdre(naturesDu(groupe).size() + 1);
        } else {
            edit.setOrdreGroupe(getGroupes().size() + 1);
        }
    }

    public void modifier(NatureObjectif n) { edit = dao.find(n.getId()); if (edit == null) edit = n; }

    public void enregistrer() {
        authorization.requireAnyPrivilege("NAT_EDIT", "MDL_EDIT");
        if (edit.getGroupe() == null || edit.getGroupe().isBlank() || edit.getLibelle() == null || edit.getLibelle().isBlank()) {
            error("Champs obligatoires", "Le groupe et le libellé de la rubrique sont requis."); return;
        }
        if (edit.getCode() == null || edit.getCode().isBlank()) edit.setCode(genererCode(edit.getLibelle()));
        edit.setCode(edit.getCode().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_"));
        if (dao.codeExiste(edit.getCode(), edit.getId())) { error("Code déjà utilisé", "Une rubrique porte déjà le code " + edit.getCode() + "."); return; }
        if (edit.getExerciceDebut() != null && edit.getExerciceFin() != null && edit.getExerciceFin() < edit.getExerciceDebut()) {
            error("Plage incohérente", "L'exercice de fin doit être postérieur ou égal à l'exercice de début."); return;
        }
        edit.setGroupe(edit.getGroupe().trim()); edit.setLibelle(edit.getLibelle().trim());
        // aligner l'ordre du groupe sur les rubriques déjà présentes dans ce groupe
        natures.stream().filter(n -> n.getId() != edit.getId() && n.getGroupe().equalsIgnoreCase(edit.getGroupe())).findFirst().ifPresent(n -> edit.setOrdreGroupe(n.getOrdreGroupe()));
        boolean creation = edit.getId() == 0;
        edit = dao.save(edit);
        auditService.log(creation ? "CREATE" : "UPDATE", "Objectifs", "NatureObjectif", edit.getId(), (creation ? "Création" : "Modification") + " d'une rubrique d'objectifs", edit.getGroupe() + " / " + edit.getLibelle(), true);
        info(creation ? "Rubrique créée" : "Rubrique mise à jour", edit.getLibelle());
        org.primefaces.PrimeFaces.current().ajax().addCallbackParam("ok", true);
        charger();
    }

    public void basculer(NatureObjectif n) {
        authorization.requireAnyPrivilege("NAT_EDIT", "MDL_EDIT");
        NatureObjectif x = dao.find(n.getId());
        x.setStatut(x.getStatut() == 1 ? 0 : 1);
        dao.save(x);
        auditService.updated("Objectifs", "NatureObjectif", x.getId(), x.getStatut() == 1 ? "Rubrique activée" : "Rubrique désactivée", x.getLibelle());
        charger();
    }

    public void supprimer(NatureObjectif n) {
        authorization.requireAnyPrivilege("NAT_EDIT", "MDL_EDIT");
        if (dao.countObjectifs(n.getId()) > 0) { error("Suppression impossible", "Des objectifs utilisent cette rubrique : désactivez-la ou bornez-la à un exercice de fin."); return; }
        dao.remove(n.getId());
        auditService.deleted("Objectifs", "NatureObjectif", n.getId(), "Suppression d'une rubrique d'objectifs", n.getLibelle());
        info("Rubrique supprimée", n.getLibelle());
        charger();
    }

    public void monter(NatureObjectif n) { decaler(n, -1); }
    public void descendre(NatureObjectif n) { decaler(n, +1); }

    private void decaler(NatureObjectif n, int sens) {
        List<NatureObjectif> grp = new ArrayList<>(naturesDu(n.getGroupe()));
        int i = grp.indexOf(n), j = i + sens;
        if (i < 0 || j < 0 || j >= grp.size()) return;
        NatureObjectif a = dao.find(grp.get(i).getId()), b = dao.find(grp.get(j).getId());
        int tmp = a.getOrdre(); a.setOrdre(b.getOrdre() == tmp ? tmp + sens : b.getOrdre()); b.setOrdre(tmp);
        dao.save(a); dao.save(b);
        charger();
    }

    public void monterGroupe(String g) { decalerGroupe(g, -1); }
    public void descendreGroupe(String g) { decalerGroupe(g, +1); }

    private void decalerGroupe(String g, int sens) {
        List<String> gs = getGroupes();
        int i = gs.indexOf(g), j = i + sens;
        if (i < 0 || j < 0 || j >= gs.size()) return;
        int oa = ordreGroupe(gs.get(i)), ob = ordreGroupe(gs.get(j));
        if (oa == ob) ob = oa + sens;
        for (NatureObjectif n : natures) {
            NatureObjectif x = dao.find(n.getId());
            if (x.getGroupe().equals(gs.get(i))) { x.setOrdreGroupe(ob); dao.save(x); }
            else if (x.getGroupe().equals(gs.get(j))) { x.setOrdreGroupe(oa); dao.save(x); }
        }
        charger();
    }

    private static String genererCode(String libelle) {
        String base = java.text.Normalizer.normalize(libelle, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceFirst("^[A-Za-z]\\.\\s*", "").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return base.length() > 40 ? base.substring(0, 40) : base;
    }

    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
}
