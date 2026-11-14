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
    @Inject private cd.mura.hrx.service.recrutement.ClaudeCvService claude;
    @EJB private cd.mura.hrx.dao.config.AppDAO appDAO;

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
        if (!isPonderationLibre() && getPoidsTotal() != 100) { error("Pondération incomplète", "La somme des pondérations doit faire 100 %. Laissez-les toutes à 0 pour donner la même pondération à chaque objectif."); return; }
        if (getNbProposes() > 0 && (n1 || hierarchie || dcht)) { error("Propositions en attente", "Validez ou refusez d'abord les objectifs proposés par le collaborateur."); return; }
        if (getNbContestations() > 0) { cd.mura.hrx.utils.Dialogue.erreur("Contestation en cours", (n1 || hierarchie || dcht)
                ? "Le collaborateur conteste " + getNbContestations() + " objectif(s) : révisez-les ou maintenez-les en motivant votre réponse avant de signer."
                : "Votre N+1 doit d'abord répondre à votre contestation avant que la fiche puisse être signée."); return; }
        Date now = new Date();
        if (moi && !fiche.isSigneeSalarie()) fiche.setDateSignatureSalarie(now);
        if ((n1 || hierarchie || dcht) && !fiche.isSigneeN1()) { fiche.setDateSignatureN1(now); fiche.setSignataireN1(loginBean.getUsers()); }
        if (fiche.isSigneeSalarie() && fiche.isSigneeN1()) fiche.setStatut(FicheObjectifs.SIGNEE);
        fiche = objectifDAO.saveFiche(fiche);
        auditService.updated("Objectifs", "FicheObjectifs", fiche.getId(), fiche.isSignee() ? "Fiche d'objectifs signée par les deux parties" : "Signature de la fiche d'objectifs", salarie.getNomComplet() + " : " + exercice);
        if (fiche.isSignee()) { try { notifications.ficheObjectifsSignee(objectifDAO.findFiche(fiche.getId())); } catch (Exception ex) { } }
        cd.mura.hrx.utils.Dialogue.succes(fiche.isSignee() ? "Fiche signée" : "Signature enregistrée", fiche.isSignee() ? "Les objectifs sont figés pour l'exercice " + exercice + "." : "En attente de la signature de l'autre partie.");
    }

    public void rouvrir() {
        if (fiche == null || !(n1 || hierarchie || dcht)) return;
        if (isVerrouEvaluation()) { error("Réouverture impossible", getMotifVerrou()); return; }
        fiche.setStatut(FicheObjectifs.EN_COURS);
        fiche.setDateSignatureSalarie(null);
        fiche.setDateSignatureN1(null);
        fiche.setSignataireN1(null);
        fiche = objectifDAO.saveFiche(fiche);
        auditService.updated("Objectifs", "FicheObjectifs", fiche.getId(), "Réouverture de la fiche d'objectifs", salarie.getNomComplet() + " : " + exercice);
        cd.mura.hrx.utils.Dialogue.succes("Fiche rouverte", "Les objectifs peuvent de nouveau être modifiés ; les signatures sont à refaire.");
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
        boolean revision = !creation && isPeutFixer() && edit.isContestationEnAttente();
        if (revision) {
            edit.setContestationStatut(Objectif.CONTEST_REVISE); edit.setDateReponseContestation(new Date());
            edit.setContestationReponse(reponseContestation == null || reponseContestation.isBlank() ? "Objectif révisé suite à votre demande." : reponseContestation.trim());
            reponseContestation = null;
        }
        objectifDAO.save(edit);
        if (revision) {
            auditService.updated("Objectifs", "Objectif", edit.getId(), "Objectif révisé suite à contestation", salarie.getNomComplet() + " : " + edit.getLibelle());
            try { notifications.contestationTraitee(objectifDAO.findFiche(fiche.getId()), edit); } catch (Exception ex) { }
        }
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

    // contestation d'un objectif par le collaborateur
    private String motifContestation, commentaireContestation, reponseContestation;
    public String getMotifContestation() { return motifContestation; }
    public void setMotifContestation(String v) { motifContestation = v; }
    public String getCommentaireContestation() { return commentaireContestation; }
    public void setCommentaireContestation(String v) { commentaireContestation = v; }
    public String getReponseContestation() { return reponseContestation; }
    public void setReponseContestation(String v) { reponseContestation = v; }
    public java.util.Map<String, String> getMotifsContestation() { return Objectif.MOTIFS_CONTESTATION; }
    public long getNbContestations() { return objectifs.stream().filter(Objectif::isContestationEnAttente).count(); }

    /** Le collaborateur peut contester un objectif validé tant qu'il n'a pas signé sa fiche. */
    public boolean isPeutContester(Objectif o) {
        return moi && !n1 && fiche != null && !fiche.isSignee() && !fiche.isSigneeSalarie() && !isVerrouEvaluation()
                && o.isValide() && !o.isContestationEnAttente();
    }
    public boolean isPeutRetirerContestation(Objectif o) { return moi && o.isContestationEnAttente() && fiche != null && !fiche.isSignee(); }
    /** La hiérarchie traite la contestation : révision (modification) ou maintien motivé. */
    public boolean isPeutTraiterContestation(Objectif o) { return (n1 || hierarchie || dcht) && !moi && o.isContestationEnAttente() && !isVerrouEvaluation(); }

    public void preparerContestation(Objectif o) { edit = o; motifContestation = null; commentaireContestation = null; }

    public void contester() {
        if (edit == null) return;
        Objectif x = objectifDAO.find(edit.getId());
        if (x == null || !isPeutContester(x)) { cd.mura.hrx.utils.Dialogue.erreur("Contestation impossible", "Cet objectif ne peut plus être contesté (fiche déjà signée par vous ou évaluation en cours)."); return; }
        if (motifContestation == null || !Objectif.MOTIFS_CONTESTATION.containsKey(motifContestation)) { error("Motif requis", "Choisissez la raison de votre demande de révision."); return; }
        if (commentaireContestation == null || commentaireContestation.trim().length() < 10) { error("Explication requise", "Expliquez en quelques mots pourquoi cet objectif doit être revu (10 caractères au moins)."); return; }
        x.setContestationStatut(Objectif.CONTEST_EN_ATTENTE); x.setContestationMotif(motifContestation);
        x.setContestationCommentaire(commentaireContestation.trim()); x.setDateContestation(new Date());
        x.setContestationReponse(null); x.setDateReponseContestation(null);
        objectifDAO.save(x);
        boolean signatureAnnulee = fiche.isSigneeN1();
        if (signatureAnnulee) {   // la fiche revient au N+1 : sa signature est annulée pour qu'il puisse réviser
            fiche.setDateSignatureN1(null); fiche.setSignataireN1(null); fiche.setStatut(FicheObjectifs.EN_COURS);
            fiche = objectifDAO.saveFiche(fiche);
        }
        auditService.updated("Objectifs", "Objectif", x.getId(), "Contestation d'un objectif par le collaborateur",
                salarie.getNomComplet() + " : " + x.getLibelle() + "\nMotif : " + x.getContestationMotifLabel() + "\n" + x.getContestationCommentaire());
        try { notifications.objectifConteste(objectifDAO.findFiche(fiche.getId()), x); } catch (Exception ex) { }
        org.primefaces.PrimeFaces.current().ajax().addCallbackParam("ok", true);
        cd.mura.hrx.utils.Dialogue.succes("Demande de révision envoyée", "Votre N+1 est prévenu par e-mail. Il peut réviser l'objectif ou le maintenir en motivant sa réponse ; la fiche ne pourra être signée qu'ensuite."
                + (signatureAnnulee ? " Sa signature a été annulée pour lui permettre de modifier la fiche." : ""));
        charger();
    }

    public void retirerContestation(Objectif o) {
        Objectif x = objectifDAO.find(o.getId());
        if (x == null || !isPeutRetirerContestation(x)) return;
        x.setContestationStatut(null); x.setContestationMotif(null); x.setContestationCommentaire(null); x.setDateContestation(null);
        objectifDAO.save(x);
        auditService.updated("Objectifs", "Objectif", x.getId(), "Contestation retirée par le collaborateur", salarie.getNomComplet() + " : " + x.getLibelle());
        info("Contestation retirée", x.getLibelle());
        charger();
    }

    /** Révision : ouvre la fenêtre de modification de l'objectif contesté. */
    public void reviser(Objectif o) { modifier(o); reponseContestation = null; }

    public void preparerMaintien(Objectif o) { edit = o; reponseContestation = null; }

    public void maintenir() {
        if (edit == null) return;
        Objectif x = objectifDAO.find(edit.getId());
        if (x == null || !isPeutTraiterContestation(x)) return;
        if (reponseContestation == null || reponseContestation.trim().length() < 10) { error("Justification requise", "Expliquez au collaborateur pourquoi l'objectif est maintenu (10 caractères au moins)."); return; }
        x.setContestationStatut(Objectif.CONTEST_MAINTENU); x.setContestationReponse(reponseContestation.trim()); x.setDateReponseContestation(new Date());
        objectifDAO.save(x);
        auditService.updated("Objectifs", "Objectif", x.getId(), "Objectif maintenu malgré la contestation", salarie.getNomComplet() + " : " + x.getLibelle() + "\nRéponse : " + x.getContestationReponse());
        try { notifications.contestationTraitee(objectifDAO.findFiche(fiche.getId()), x); } catch (Exception ex) { }
        org.primefaces.PrimeFaces.current().ajax().addCallbackParam("ok", true);
        cd.mura.hrx.utils.Dialogue.succes("Objectif maintenu", "Votre réponse est transmise au collaborateur par e-mail. La fiche peut maintenant être signée.");
        reponseContestation = null;
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

    // ------------------------------------------------------------------ import d'une fiche par l'IA

    /** Objectif lu par l'IA, en revue : rien n'est enregistré tant que l'utilisateur n'a pas confirmé. */
    @Getter @Setter
    public static class Brouillon implements Serializable {
        private static final long serialVersionUID = 1L;
        private boolean retenu = true;
        private Integer natureId;
        private String activite, libelle, moyens, indicateur, cible, periodicite = Periodicite.TRIMESTRIEL.name();
        private boolean t1, t2, t3, t4;
        private LocalDate echeance;
        private int poids;
        /** Signalement affiché sur la ligne (doublon, rubrique devinée…). */
        private String alerte;
    }

    private List<Brouillon> brouillons = new ArrayList<>();
    private String missionLue, remarquesImport, fichierImporte, avertissementSalarie;
    private boolean reprendreMission;

    private static final Map<String, String> TYPES_IMPORT = Map.of("pdf", "application/pdf", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "png", "image/png", "webp", "image/webp", "docx", "docx", "txt", "text/plain");

    public boolean isPeutImporter() { return isPeutFixer() || isPeutProposer(); }
    public boolean isIaActive() {
        cd.mura.hrx.entity.config.App a = appDAO.findFirst();
        return a != null && Boolean.TRUE.equals(a.getIaActive());
    }
    public long getNbRetenus() { return brouillons.stream().filter(b -> b.isRetenu() && b.getLibelle() != null && !b.getLibelle().isBlank()).count(); }

    /** Fait lire une fiche d'objectifs (Word, PDF, scan, photo) par l'IA puis ouvre la revue. */
    public void importerFiche(org.primefaces.event.FileUploadEvent e) {
        if (e.getPhaseId() != jakarta.faces.event.PhaseId.INVOKE_APPLICATION) {
            e.setPhaseId(jakarta.faces.event.PhaseId.INVOKE_APPLICATION);
            e.queue();
            return;
        }
        if (!isPeutImporter()) { error("Import impossible", "La fiche n'est pas modifiable."); return; }
        cd.mura.hrx.entity.config.App app = appDAO.findFirst();
        if (app == null || !Boolean.TRUE.equals(app.getIaActive())) { error("IA désactivée", "Activez l'analyse de documents par l'IA dans Paramètres > IA."); return; }
        try {
            String nom = e.getFile().getFileName();
            String ext = nom.contains(".") ? nom.substring(nom.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            String mime = TYPES_IMPORT.get(ext);
            if (mime == null) { error("Format non pris en charge", "Fiche en Word (.docx), PDF (même scanné), image ou texte. Un ancien .doc est à réenregistrer en .docx."); return; }
            byte[] contenu = e.getFile().getContent();
            String texte = "docx".equals(ext) || "txt".equals(ext) ? cd.mura.hrx.service.recrutement.AnalyseMoteur.texte(contenu, ext) : null;
            StringBuilder rub = new StringBuilder();
            for (NatureObjectif n : natures)
                rub.append(n.getCode()).append(" : ").append(n.getGroupe()).append(" - ").append(n.getLibelle())
                   .append(n.getAide() == null || n.getAide().isBlank() ? "" : " (" + n.getAide() + ")").append('\n');
            var lu = claude.lireObjectifs(app, contenu, mime, nom, texte, rub.toString(), exercice, getTrimestreDebut());
            preparerRevue(lu, nom);
            auditService.log("IMPORT", "Objectifs", "FicheObjectifs", fiche == null ? 0 : fiche.getId(), "Lecture d'une fiche d'objectifs par l'IA",
                    salarie.getNomComplet() + " : " + nom + " (" + brouillons.size() + " objectif(s) lu(s))", true);
            if (brouillons.isEmpty()) { error("Aucun objectif trouvé", "L'IA n'a trouvé aucun objectif dans « " + nom + " »."); return; }
            org.primefaces.PrimeFaces.current().ajax().update("frmImportObj:impCorps", "frmImportObj:impPied");
            org.primefaces.PrimeFaces.current().executeScript("PF('dlgImportObj').show()");
        } catch (Exception ex) {
            Throwable c = ex; while (c.getCause() != null && c.getCause() != c) c = c.getCause();
            error("Lecture de la fiche impossible", c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
        }
    }

    private void preparerRevue(cd.mura.hrx.service.recrutement.ClaudeCvService.FicheObjectifsLue lu, String nom) {
        fichierImporte = nom;
        brouillons = new ArrayList<>();
        missionLue = lu.mission() == null || lu.mission().isBlank() ? null : lu.mission().trim();
        reprendreMission = missionLue != null && (fiche == null || fiche.getMission() == null || fiche.getMission().isBlank());
        remarquesImport = lu.remarques() == null || lu.remarques().isBlank() ? null : lu.remarques().trim();
        avertissementSalarie = null;
        if (lu.salarie() != null && !lu.salarie().isBlank() && salarie.getNom() != null
                && !sansAccents(lu.salarie()).contains(sansAccents(salarie.getNom()).trim()))
            avertissementSalarie = "La fiche semble concerner « " + lu.salarie().trim() + " » et non " + salarie.getNomComplet() + ". Vérifiez le document.";
        if (lu.exercice() > 0 && lu.exercice() != exercice)
            avertissementSalarie = (avertissementSalarie == null ? "" : avertissementSalarie + " ") + "La fiche porte sur l'exercice " + lu.exercice() + ", vous êtes sur " + exercice + ".";
        Map<String, Integer> parCode = new HashMap<>();
        for (NatureObjectif n : natures) parCode.put(n.getCode().toUpperCase(Locale.ROOT), n.getId());
        Set<String> existants = new HashSet<>();
        for (Objectif o : objectifs) existants.add(sansAccents(o.getLibelle()).trim());
        int t0 = getTrimestreDebut();
        for (var o : lu.objectifs() == null ? List.<cd.mura.hrx.service.recrutement.ClaudeCvService.ObjectifLu>of() : lu.objectifs()) {
            if (o.libelle() == null || o.libelle().isBlank()) continue;
            Brouillon b = new Brouillon();
            b.setNatureId(o.rubrique() == null ? null : parCode.get(o.rubrique().trim().toUpperCase(Locale.ROOT)));
            if (b.getNatureId() == null) { b.setNatureId(natures.isEmpty() ? null : natures.get(0).getId()); b.setAlerte("Rubrique à vérifier"); }
            b.setActivite(coupe(o.activite(), 200)); b.setLibelle(coupe(o.libelle(), 600)); b.setMoyens(coupe(o.moyens(), 1000));
            recalerIndicateurCible(b, o.indicateur(), o.cible());
            b.setPeriodicite(Periodicite.of(o.periodicite() == null ? "" : o.periodicite().trim().toUpperCase(Locale.ROOT)).name());
            boolean aucun = !o.t1() && !o.t2() && !o.t3() && !o.t4();
            b.setT1(aucun ? t0 <= 1 : o.t1()); b.setT2(aucun ? t0 <= 2 : o.t2()); b.setT3(aucun ? t0 <= 3 : o.t3()); b.setT4(aucun || o.t4());
            if (o.echeance() != null && !o.echeance().isBlank()) try { b.setEcheance(LocalDate.parse(o.echeance().trim())); } catch (Exception ignored) { }
            b.setPoids(isPeutFixer() ? Math.max(0, Math.min(100, o.poids())) : 0);
            if (existants.contains(sansAccents(b.getLibelle()).trim())) { b.setRetenu(false); b.setAlerte("Déjà présent sur la fiche"); }
            brouillons.add(b);
        }
    }

    /**
     * Indicateur = énoncé mesurable complet ; cible = valeur courte, 100 % par défaut.
     * Si l'IA a placé un énoncé dans la cible (ou recopié l'indicateur), il revient dans l'indicateur et la cible repasse à 100 %.
     */
    static void recalerIndicateurCible(Brouillon b, String indicateur, String cible) {
        String ind = indicateur == null ? "" : indicateur.trim(), cib = cible == null ? "" : cible.trim();
        if (!cib.isEmpty() && !cibleCourte(cib)) {
            if (ind.isEmpty() || sansAccents(ind).equals(sansAccents(cib))) ind = cib;
            else if (!sansAccents(ind).contains(sansAccents(cib))) ind = ind + " ; " + cib;
            cib = "";
        }
        if (!ind.isEmpty() && sansAccents(ind).equals(sansAccents(cib))) cib = "";
        b.setIndicateur(coupe(ind, 400));
        b.setCible(cib.isEmpty() ? "100 %" : coupe(cib, 100));
    }

    /** Une cible est une valeur courte (« 100 % », « 0 incident », « ≤ 48 h », « 5 jours ») et non un énoncé. */
    static boolean cibleCourte(String c) {
        String x = c.trim();
        return x.length() <= 25 && x.split("\\s+").length <= 4;
    }

    public void toutRetenir() { boolean tous = brouillons.stream().allMatch(Brouillon::isRetenu); brouillons.forEach(b -> b.setRetenu(!tous)); }

    /** Enregistre les objectifs retenus : fixés (N+1, N+n, DCHT) ou proposés (le collaborateur), comme une saisie manuelle. */
    public void enregistrerImport() {
        if (!isPeutImporter()) { error("Import impossible", "La fiche n'est plus modifiable."); return; }
        List<Brouillon> l = brouillons.stream().filter(b -> b.isRetenu() && b.getLibelle() != null && !b.getLibelle().isBlank()).toList();
        if (l.isEmpty()) { error("Aucun objectif retenu", "Cochez au moins un objectif à enregistrer."); return; }
        for (Brouillon b : l) {
            if (b.getNatureId() == null) { error("Rubrique manquante", "Choisissez la rubrique de « " + b.getLibelle() + " »."); return; }
            if (!b.isT1() && !b.isT2() && !b.isT3() && !b.isT4()) { error("Période manquante", "Cochez au moins un trimestre pour « " + b.getLibelle() + " »."); return; }
        }
        boolean fixe = isPeutFixer();
        FicheObjectifs f = ficheOuCreer();
        int ordre = objectifs.size();
        List<Objectif> proposes = new ArrayList<>();
        for (Brouillon b : l) {
            Objectif o = new Objectif();
            o.setFiche(objectifDAO.refFiche(f.getId()));
            o.setNature(natureObjectifDAO.find(b.getNatureId()));
            o.setActivite(vide(b.getActivite())); o.setLibelle(b.getLibelle().trim()); o.setMoyens(vide(b.getMoyens()));
            o.setIndicateur(vide(b.getIndicateur())); o.setCible(vide(b.getCible())); o.setPeriodicite(b.getPeriodicite());
            o.setT1(b.isT1()); o.setT2(b.isT2()); o.setT3(b.isT3()); o.setT4(b.isT4()); o.setEcheance(b.getEcheance());
            o.setOrdre(++ordre);
            o.setCreePar(loginBean.getUsers().getId());
            if (fixe) { o.setPoids(b.getPoids()); o.setOrigine(Objectif.ORIGINE_N1); o.setStatut(Objectif.VALIDE); o.setValidePar(loginBean.getUsers().getId()); o.setDateValidation(new Date()); }
            else { o.setOrigine(Objectif.ORIGINE_SALARIE); o.setStatut(Objectif.PROPOSE); proposes.add(o); }
            objectifDAO.save(o);
        }
        if (reprendreMission && missionLue != null) {
            fiche = objectifDAO.findFiche(f.getId());
            fiche.setMission(coupe(missionLue, 3000));
            fiche = objectifDAO.saveFiche(fiche);
        }
        auditService.log("CREATE", "Objectifs", "FicheObjectifs", f.getId(), fixe ? "Fixation d'objectifs importés par l'IA" : "Propositions d'objectifs importées par l'IA",
                salarie.getNomComplet() + " : " + l.size() + " objectif(s) depuis « " + fichierImporte + " »", true);
        if (!proposes.isEmpty()) try { notifications.objectifPropose(objectifDAO.findFiche(f.getId()), proposes.get(0), loginBean.getUsers()); } catch (Exception ex) { }
        info(fixe ? l.size() + " objectif(s) fixé(s)" : l.size() + " proposition(s) envoyée(s) à votre hiérarchie",
                fixe ? "Ils sont validés. Vérifiez la pondération puis faites signer la fiche." : "Votre N+1 les validera ou les refusera avec un motif.");
        brouillons = new ArrayList<>();
        org.primefaces.PrimeFaces.current().ajax().addCallbackParam("ok", true);
        charger();
    }

    private static String coupe(String v, int n) { if (v == null || v.isBlank()) return null; v = v.trim(); return v.length() <= n ? v : v.substring(0, n - 1) + "…"; }
    private static String vide(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static String sansAccents(String v) {
        return v == null ? "" : java.text.Normalizer.normalize(v, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
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
