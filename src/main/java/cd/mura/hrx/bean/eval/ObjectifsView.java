package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.bean.config.PrivilegeBean;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.dao.eval.ObjectifDAO;
import cd.mura.hrx.dao.eval.NatureObjectifDAO;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.FicheObjectifs;
import cd.mura.hrx.entity.eval.NatureObjectif;
import cd.mura.hrx.entity.eval.Objectif;
import cd.mura.hrx.entity.eval.Periodicite;
import cd.mura.hrx.service.AuditService;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.Year;
import java.util.*;

/**
 * Fiche de fixation des objectifs individuels (MAF 066) d'un salarié pour un exercice.
 * Le N+1 fixe et valide ; le salarié propose ; les deux signent.
 */
@Named
@ViewScoped
@Getter
@Setter
public class ObjectifsView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private PrivilegeBean privilegeBean;
    @Inject private AuditService auditService;
    @EJB private UserDAO userDAO;
    @EJB private ObjectifDAO objectifDAO;
    @EJB private cd.mura.hrx.dao.eval.EvaluationDAO evaluationDAO;
    /** Fiche d'évaluation de l'exercice qui fige les objectifs (null = aucune). */
    private cd.mura.hrx.entity.eval.Evaluation evaluationBloquante;
    @EJB private NatureObjectifDAO natureObjectifDAO;
    @EJB private cd.mura.hrx.service.NotificationService notifications;
    @Inject private cd.mura.hrx.service.FicheObjectifsExportService exportService;

    /** Paramètres de vue. */
    private Integer salarieId;
    private Integer exercice;

    private Users salarie;
    private FicheObjectifs fiche;
    private List<Objectif> objectifs = new ArrayList<>();
    private List<Integer> exercices = new ArrayList<>();
    private List<NatureObjectif> natures = new ArrayList<>();
    private Integer editNatureId;
    private boolean moi, n1, hierarchie, dcht;

    /** Objectif en édition dans le dialogue. */
    private Objectif edit = new Objectif();
    private String motifRefus;

    // chargement
    private boolean parametreInvalide;
    public String getSalarieParam() { return salarieId == null ? null : String.valueOf(salarieId); }
    public void setSalarieParam(String v) { Integer x = entier(v); if (v != null && !v.isBlank() && x == null) parametreInvalide = true; salarieId = x; }
    public String getExerciceParam() { return exercice == null ? null : String.valueOf(exercice); }
    public void setExerciceParam(String v) { Integer x = entier(v); if (v != null && !v.isBlank() && (x == null || x < 2000 || x > 2100)) { parametreInvalide = true; x = null; } exercice = x; }
    private static Integer entier(String v) { return v != null && v.trim().matches("\\d{1,9}") ? Integer.valueOf(v.trim()) : null; }

    public void charger() {
        Users courant = loginBean.getUsers();
        if (courant == null) return;
        if (parametreInvalide) { redirect("/ui/mesEvaluations"); return; }
        if (salarieId == null || salarieId <= 0) salarieId = courant.getId();
        if (exercice == null || exercice <= 0) exercice = Year.now().getValue();

        salarie = userDAO.findAccountById(salarieId);
        if (salarie == null) { redirect("/ui/equipe"); return; }

        moi = salarie.getId() == courant.getId();
        n1 = salarie.getSuperieurHierarchique() != null && salarie.getSuperieurHierarchique().getId() == courant.getId();
        hierarchie = n1 || estDansMaLigne(salarie, courant.getId());
        dcht = privilegeBean.hasPrivilege("EVAL_DCHT") || privilegeBean.hasPrivilege("CAMP_CREATE");
        if (!moi && !hierarchie && !dcht) { redirect("/ui/home"); return; }

        fiche = objectifDAO.findFiche(salarie.getId(), exercice);
        objectifs = fiche == null ? new ArrayList<>() : objectifDAO.objectifs(fiche.getId());
        natures = natureObjectifDAO.findPourExercice(exercice);
        // rubriques désactivées depuis mais encore portées par des objectifs existants : on les garde visibles
        for (Objectif o : objectifs) if (o.getNature() != null && natures.stream().noneMatch(n -> n.getId() == o.getNature().getId())) natures.add(o.getNature());
        // Une évaluation de l'exercice fige les objectifs : ils ont été recopiés dans la fiche d'évaluation.
        // Seule une évaluation de mi-parcours validée rouvre la possibilité d'une révision.
        evaluationBloquante = null;
        for (cd.mura.hrx.entity.eval.Evaluation ev : evaluationDAO.findBySalarie(salarie.getId())) {
            if (ev.getCampagne() == null || ev.getCampagne().getExercice() != exercice) continue;
            boolean revisionPermise = cd.mura.hrx.entity.eval.Campagne.MI_PARCOURS.equals(ev.getCampagne().getType()) && ev.isFinalisee();
            if (!revisionPermise) { evaluationBloquante = ev; break; }
        }
        exercices = objectifDAO.exercices(salarie.getId());
        int annee = Year.now().getValue();
        for (int a : new int[]{annee + 1, annee}) if (!exercices.contains(a)) exercices.add(a);
        exercices.sort(Comparator.reverseOrder());
    }

    private boolean estDansMaLigne(Users u, int chefId) {
        Set<Integer> vus = new HashSet<>();
        Users cur = u.getSuperieurHierarchique();
        while (cur != null && vus.add(cur.getId())) {
            if (cur.getId() == chefId) return true;
            cur = userDAO.findAccountById(cur.getId()).getSuperieurHierarchique();
        }
        return false;
    }

    public void changerExercice() { fiche = null; charger(); }

    // droits
    /** Le N+1 (ou la DCHT) pilote la fiche : fixe, valide, refuse, signe côté N+1. */
    /** Une signature posée (salarié ou N+1) fige le contenu : toute modification passe par « Rouvrir la fiche ». */
    public boolean isFigee() { return fiche != null && (fiche.isSignee() || fiche.isSigneeSalarie() || fiche.isSigneeN1()); }
    /** Une fiche d'évaluation de l'exercice existe : les objectifs ne peuvent plus bouger, même après réouverture. */
    public boolean isVerrouEvaluation() { return evaluationBloquante != null; }
    public String getMotifVerrou() {
        if (evaluationBloquante == null) return "";
        return "Ces objectifs sont repris dans la fiche d'évaluation « " + evaluationBloquante.getCampagne().getLibelle() + " » ("
                + evaluationBloquante.getStatutLabel().toLowerCase(java.util.Locale.ROOT) + "). Ils ne sont plus modifiables pour l'exercice " + exercice + ".";
    }
    private boolean ouverte() { return !isFigee() && !isVerrouEvaluation(); }
    /** Le N+1 (ou la DCHT) pilote la fiche : fixe, valide, refuse, signe côté N+1. */
    public boolean isPeutFixer() { return (n1 || hierarchie || dcht) && ouverte(); }
    /** Le salarié propose des objectifs tant que la fiche n'est ni signée ni reprise dans une évaluation. */
    public boolean isPeutProposer() { return moi && ouverte(); }
    public boolean isPeutModifier(Objectif o) {
        if (!ouverte()) return false;
        if (n1 || hierarchie || dcht) return true;
        // le salarié peut créer et mettre à jour ses objectifs : toute modification repasse en proposition à valider
        return moi && !Objectif.REFUSE.equals(o.getStatut());
    }
    public boolean isPeutSupprimer(Objectif o) {
        if (!ouverte()) return false;
        if (n1 || hierarchie || dcht) return true;
        return moi && o.isParSalarie() && o.isPropose();
    }
    public boolean isPeutRouvrir() { return isFigee() && !isVerrouEvaluation() && (n1 || dcht); }
    public boolean isLectureSeule() { return !isPeutFixer() && !isPeutProposer(); }

    // lecture
    public List<String> getGroupes() {
        List<String> g = new ArrayList<>();
        for (NatureObjectif n : natures) if (!g.contains(n.getGroupe())) g.add(n.getGroupe());
        return g;
    }
    public List<NatureObjectif> naturesDu(String groupe) { return natures.stream().filter(n -> n.getGroupe().equals(groupe)).toList(); }
    public Periodicite[] getPeriodicites() { return Periodicite.values(); }
    public List<Objectif> objectifsDe(NatureObjectif n) { return objectifs.stream().filter(o -> o.getNature() != null && o.getNature().getId() == n.getId()).toList(); }
    public boolean groupeVide(String groupe) { return naturesDu(groupe).stream().allMatch(n -> objectifsDe(n).isEmpty()); }
    public long getNbValides() { return objectifs.stream().filter(Objectif::isValide).count(); }
    public long getNbProposes() { return objectifs.stream().filter(Objectif::isPropose).count(); }
    public int getPoidsTotal() { return objectifs.stream().filter(Objectif::isValide).mapToInt(Objectif::getPoids).sum(); }
    public boolean isPonderationLibre() { return objectifs.stream().filter(Objectif::isValide).allMatch(o -> o.getPoids() == 0); }

    public String getAnciennete() {
        if (salarie == null || salarie.getDateEmbauche() == null) return "—";
        LocalDate d = salarie.getDateEmbauche().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        Period p = Period.between(d, LocalDate.now());
        String a = p.getYears() > 0 ? p.getYears() + " an" + (p.getYears() > 1 ? "s" : "") : "";
        String m = p.getMonths() > 0 ? p.getMonths() + " mois" : "";
        return (a + (a.isEmpty() || m.isEmpty() ? "" : " et ") + m).isEmpty() ? "moins d'un mois" : a + (a.isEmpty() || m.isEmpty() ? "" : " et ") + m;
    }

    /** Contexte de fixation affiché en tête de fiche, même avant la création de la fiche. */
    public boolean isRecrueExercice() {
        if (salarie == null || salarie.getDateEmbauche() == null || exercice == null) return false;
        return salarie.getDateEmbauche().toInstant().atZone(ZoneId.systemDefault()).toLocalDate().getYear() == exercice;
    }
    public String getContexteFixation() {
        if (fiche != null) return fiche.getMotifLabel();
        return isRecrueExercice() ? "Fixation à la prise de poste" : "Fixation en début d'exercice";
    }
    public LocalDate getDateEffet() {
        if (fiche != null && fiche.getDateEffet() != null) return fiche.getDateEffet();
        if (isRecrueExercice()) return salarie.getDateEmbauche().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return exercice == null ? null : LocalDate.of(exercice, 1, 1);
    }
    public int getTrimestreDebut() { LocalDate d = getDateEffet(); return d == null ? 1 : (d.getMonthValue() - 1) / 3 + 1; }
    /** La fiche de l'exercice est en retard si l'on a dépassé la fenêtre de fixation (fin janvier, ou 1 mois après l'embauche). */
    public boolean isFixationEnRetard() {
        if (fiche != null && fiche.isSignee()) return false;
        LocalDate d = getDateEffet();
        return d != null && LocalDate.now().isAfter(d.plusMonths(1));
    }

    public String initiales() {
        if (salarie == null) return "";
        String p = s(salarie.getPrenom()), n = s(salarie.getNom());
        return ((p.isEmpty() ? "" : p.substring(0, 1)) + (n.isEmpty() ? "" : n.substring(0, 1))).toUpperCase(Locale.ROOT);
    }

    // fiche
    private FicheObjectifs ficheOuCreer() {
        if (fiche == null) {
            fiche = objectifDAO.creerFiche(salarie, exercice, salarie.getSuperieurHierarchique(), null);
            auditService.created("Objectifs", "FicheObjectifs", fiche.getId(), "Ouverture de la fiche de fixation", salarie.getNomComplet() + " : " + exercice);
        }
        return fiche;
    }

    public void enregistrerMission() {
        if (!isPeutFixer()) return;
        ficheOuCreer();
        fiche = objectifDAO.saveFiche(fiche);
        info("Mission enregistrée", "");
    }

    public void signer() {
        if (fiche == null) { error("Fiche vide", "Ajoutez au moins un objectif avant de signer."); return; }
        if (getNbValides() == 0) { error("Aucun objectif validé", "La fiche doit contenir au moins un objectif validé."); return; }
        if (!isPonderationLibre() && getPoidsTotal() != 100) { error("Pondération incomplète", "La somme des poids doit faire 100 %. Laissez-les tous à 0 pour donner le même poids à chaque objectif."); return; }
        if (getNbProposes() > 0 && (n1 || hierarchie || dcht)) { error("Propositions en attente", "Validez ou refusez d'abord les objectifs proposés par le collaborateur."); return; }
        Date now = new Date();
        if (moi && !fiche.isSigneeSalarie()) fiche.setDateSignatureSalarie(now);
        if ((n1 || hierarchie || dcht) && !fiche.isSigneeN1()) fiche.setDateSignatureN1(now);
        if (fiche.isSigneeSalarie() && fiche.isSigneeN1()) fiche.setStatut(FicheObjectifs.SIGNEE);
        fiche = objectifDAO.saveFiche(fiche);
        auditService.updated("Objectifs", "FicheObjectifs", fiche.getId(), fiche.isSignee() ? "Fiche d'objectifs signée par les deux parties" : "Signature de la fiche d'objectifs", salarie.getNomComplet() + " : " + exercice);
        if (fiche.isSignee()) { try { notifications.ficheObjectifsSignee(objectifDAO.findFiche(fiche.getId())); } catch (Exception ex) { } }
        info(fiche.isSignee() ? "Fiche signée" : "Signature enregistrée", fiche.isSignee() ? "Les objectifs sont figés pour l'exercice " + exercice + "." : "En attente de la signature de l'autre partie.");
    }

    public void rouvrir() {
        if (fiche == null || !(n1 || hierarchie || dcht)) return;
        if (isVerrouEvaluation()) { error("Réouverture impossible", getMotifVerrou()); return; }
        fiche.setStatut(FicheObjectifs.EN_COURS);
        fiche.setDateSignatureSalarie(null);
        fiche.setDateSignatureN1(null);
        fiche = objectifDAO.saveFiche(fiche);
        auditService.updated("Objectifs", "FicheObjectifs", fiche.getId(), "Réouverture de la fiche d'objectifs", salarie.getNomComplet() + " : " + exercice);
        info("Fiche rouverte", "Les objectifs peuvent de nouveau être modifiés ; les signatures sont à refaire.");
    }

    // objectifs
    public void nouveau(Integer natureId) {
        edit = new Objectif();
        editNatureId = natureId != null ? natureId : (natures.isEmpty() ? null : natures.get(0).getId());
        edit.setOrdre(objectifs.size() + 1);
        edit.setCible("100 %");
        int t0 = getTrimestreDebut();
        edit.setT1(t0 <= 1); edit.setT2(t0 <= 2); edit.setT3(t0 <= 3);
        if (moi && !n1 && !dcht) { edit.setOrigine(Objectif.ORIGINE_SALARIE); edit.setStatut(Objectif.PROPOSE); }
    }

    public void modifier(Objectif o) { edit = objectifDAO.find(o.getId()); if (edit == null) edit = o; editNatureId = edit.getNature() == null ? null : edit.getNature().getId(); }

    public void enregistrer() {
        if (edit.getLibelle() == null || edit.getLibelle().isBlank()) { error("Objectif incomplet", "Formulez l'objectif."); return; }
        NatureObjectif nat = editNatureId == null ? null : natureObjectifDAO.find(editNatureId);
        if (nat == null) { error("Rubrique manquante", "Choisissez la rubrique de l'objectif."); return; }
        edit.setNature(nat);
        if (!edit.isT1() && !edit.isT2() && !edit.isT3() && !edit.isT4()) { error("Période manquante", "Cochez au moins un trimestre d'atteinte."); return; }
        boolean creation = edit.getId() == 0;
        if (creation) {
            if (!isPeutFixer() && !isPeutProposer()) return;
            edit.setFiche(objectifDAO.refFiche(ficheOuCreer().getId()));
            edit.setCreePar(loginBean.getUsers().getId());
            if (isPeutFixer()) { edit.setOrigine(Objectif.ORIGINE_N1); edit.setStatut(Objectif.VALIDE); edit.setValidePar(loginBean.getUsers().getId()); edit.setDateValidation(new Date()); }
            else { edit.setOrigine(Objectif.ORIGINE_SALARIE); edit.setStatut(Objectif.PROPOSE); }
        } else if (!isPeutModifier(edit)) { error("Modification refusée", "Cet objectif ne peut plus être modifié."); return; }
        else if (!isPeutFixer()) {
            // mise à jour par le salarié lui-même : l'objectif redevient une proposition soumise à la hiérarchie
            edit.setStatut(Objectif.PROPOSE); edit.setValidePar(null); edit.setDateValidation(null); edit.setMotifRefus(null);
        }
        edit.setLibelle(edit.getLibelle().trim());
        objectifDAO.save(edit);
        auditService.log(creation ? "CREATE" : "UPDATE", "Objectifs", "Objectif", edit.getId(),
                creation ? (edit.isPropose() ? "Proposition d'objectif" : "Fixation d'un objectif") : "Modification d'un objectif",
                salarie.getNomComplet() + " : " + nat.getLibelle() + " : " + edit.getLibelle(), true);
        info(creation ? (edit.isPropose() ? "Proposition envoyée à votre hiérarchie" : "Objectif fixé") : (edit.isPropose() && !isPeutFixer() ? "Modification soumise à validation" : "Objectif mis à jour"), edit.getLibelle());
        if (edit.isPropose() && !isPeutFixer()) { try { notifications.objectifPropose(objectifDAO.findFiche(fiche.getId()), edit, loginBean.getUsers()); } catch (Exception ex) { } }
        charger();
    }

    public void valider(Objectif o) {
        if (!isPeutFixer()) return;
        Objectif x = objectifDAO.find(o.getId());
        x.setStatut(Objectif.VALIDE); x.setValidePar(loginBean.getUsers().getId()); x.setDateValidation(new Date()); x.setMotifRefus(null);
        objectifDAO.save(x);
        auditService.updated("Objectifs", "Objectif", x.getId(), "Validation d'un objectif proposé", salarie.getNomComplet() + " : " + x.getLibelle());
        try { notifications.objectifTraite(objectifDAO.findFiche(fiche.getId()), x, true); } catch (Exception ex) { }
        info("Objectif validé", x.getLibelle());
        charger();
    }

    public void preparerRefus(Objectif o) { edit = o; motifRefus = null; }

    public void refuser() {
        if (!isPeutFixer() || edit == null) return;
        Objectif x = objectifDAO.find(edit.getId());
        x.setStatut(Objectif.REFUSE); x.setMotifRefus(motifRefus); x.setValidePar(loginBean.getUsers().getId()); x.setDateValidation(new Date());
        objectifDAO.save(x);
        auditService.updated("Objectifs", "Objectif", x.getId(), "Refus d'un objectif proposé", salarie.getNomComplet() + " : " + x.getLibelle());
        try { notifications.objectifTraite(objectifDAO.findFiche(fiche.getId()), x, false); } catch (Exception ex) { }
        info("Proposition refusée", x.getLibelle());
        charger();
    }

    public void supprimer(Objectif o) {
        if (!isPeutSupprimer(o)) { error("Suppression refusée", "Cet objectif ne peut pas être supprimé."); return; }
        objectifDAO.remove(o.getId());
        auditService.deleted("Objectifs", "Objectif", o.getId(), "Suppression d'un objectif", salarie.getNomComplet() + " : " + o.getLibelle());
        info("Objectif supprimé", o.getLibelle());
        charger();
    }

    // exports
    public org.primefaces.model.StreamedContent getExportDocx() {
        if (fiche == null) return null;
        byte[] data = exportService.docx(fiche, salarie, objectifs, natures);
        auditService.log("EXPORT", "Objectifs", "FicheObjectifs", fiche.getId(), "Export Word de la fiche d'objectifs", salarie.getNomComplet() + " : " + exercice, true);
        return org.primefaces.model.DefaultStreamedContent.builder().name(nomFichier("docx"))
                .contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .stream(() -> new java.io.ByteArrayInputStream(data)).build();
    }
    public org.primefaces.model.StreamedContent getExportPdf() {
        if (fiche == null) return null;
        byte[] data = exportService.pdf(fiche, salarie, objectifs, natures);
        auditService.log("EXPORT", "Objectifs", "FicheObjectifs", fiche.getId(), "Export PDF de la fiche d'objectifs", salarie.getNomComplet() + " : " + exercice, true);
        return org.primefaces.model.DefaultStreamedContent.builder().name(nomFichier("pdf")).contentType("application/pdf")
                .stream(() -> new java.io.ByteArrayInputStream(data)).build();
    }
    private String nomFichier(String ext) {
        String n = java.text.Normalizer.normalize(s(salarie.getNom()) + "_" + s(salarie.getPrenom()), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9_]+", "_");
        return "MAF066_Fixation_Objectifs_" + exercice + "_" + n + "." + ext;
    }

    // utilitaires
    private static String s(String v) { return v == null ? "" : v; }
    private void redirect(String url) {
        try { FacesContext ctx = FacesContext.getCurrentInstance(); ctx.getExternalContext().redirect(ctx.getExternalContext().getRequestContextPath() + url); ctx.responseComplete(); }
        catch (IOException ignored) { }
    }
    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
}
