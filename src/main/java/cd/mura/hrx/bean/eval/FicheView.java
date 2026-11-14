package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.bean.config.PrivilegeBean;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.eval.*;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.EvaluationService;
import cd.mura.hrx.service.FicheEvaluationExportService;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;

/**
 * Fiche d'évaluation individuelle (MAF 159) : auto-évaluation du salarié, évaluation et appréciation du N+1,
 * visa du N+2, avis du directeur, validation DCHT.
 */
@Named
@ViewScoped
@Getter
@Setter
public class FicheView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private PrivilegeBean privilegeBean;
    @Inject private AuditService auditService;
    @Inject private FicheEvaluationExportService exportService;
    @EJB private EvaluationService service;
    @EJB private cd.mura.hrx.service.NotificationService notifications;
    @EJB private UserDAO userDAO;
    @EJB private cd.mura.hrx.dao.config.DirectionDAO directionDAO;
    /** Directeur désigné de la direction du collaborateur (null si la direction n'en a pas). */
    private Users directeurAttendu;
    private String directionSalarie;

    private Integer id;
    private Evaluation e;
    private boolean moi, n1, n2, directeur, dcht;
    private String motif;

    /** Lignes en édition (dialogues). */
    private TravailHorsObjectif travailEdit = new TravailHorsObjectif();
    private BesoinFormation formationEdit = new BesoinFormation();

    // chargement & droits
    /** Paramètre d'URL brut : tout ce qui n'est pas un entier positif est rejeté (id=2', id=abc, id=-1…). */
    public String getIdParam() { return id == null ? null : String.valueOf(id); }
    public void setIdParam(String v) { id = v != null && v.trim().matches("\\d{1,9}") ? Integer.valueOf(v.trim()) : -1; }

    /** Valeurs N+1 au chargement, pour détailler dans le journal ce que le directeur a corrigé. */
    private final Map<String, String> instantaneN1 = new HashMap<>();
    private static String valN1(ObjectifEvaluation o) { return o.isNonApplicableN1() ? "NA" : o.getRealisationN1() == null ? "—" : o.getRealisationN1() + " %"; }
    private void photographierN1() {
        instantaneN1.clear();
        for (ObjectifEvaluation o : e.getObjectifs()) instantaneN1.put("O" + o.getId(), valN1(o));
        for (EvaluationCritere c : e.getCriteres()) instantaneN1.put("C" + c.getId(), c.getNiveau() == null ? "—" : c.getNiveau());
    }
    private List<String> correctionsDirecteur() {
        List<String> l = new ArrayList<>();
        for (ObjectifEvaluation o : e.getObjectifs()) {
            String avant = instantaneN1.get("O" + o.getId()), apres = valN1(o);
            if (avant != null && !avant.equals(apres)) l.add("Objectif « " + court(o.getObjectif().getLibelle()) + " » : " + avant + " → " + apres);
        }
        for (EvaluationCritere c : e.getCriteres()) {
            String avant = instantaneN1.get("C" + c.getId()), apres = c.getNiveau() == null ? "—" : c.getNiveau();
            if (avant != null && !avant.equals(apres)) l.add("Compétence « " + court(c.getCritere().getLibelle()) + " » : " + avant + " → " + apres);
        }
        return l;
    }
    private static String court(String v) { return v == null ? "" : v.length() > 70 ? v.substring(0, 70) + "…" : v; }

    public void charger() {
        Users me = loginBean.getUsers();
        if (id == null || id <= 0 || me == null) { redirect("/ui/mesEvaluations"); return; }
        e = service.charger(id);
        if (e == null) { redirect("/ui/mesEvaluations"); return; }
        Users s = e.getSalarie();
        moi = s.getId() == me.getId();
        n1 = e.getEvaluateur() != null && e.getEvaluateur().getId() == me.getId();
        Users chefN1 = e.getEvaluateur() == null ? null : userDAO.findAccountById(e.getEvaluateur().getId()).getSuperieurHierarchique();
        n2 = !n1 && (chefN1 != null && chefN1.getId() == me.getId() || estAuDessus(e.getEvaluateur(), me.getId()));
        // Directeur : relu en base (compte complet du salarié + direction fraîche), sans dépendre d'un proxy paresseux.
        Users sal = userDAO.findAccountById(s.getId());
        Direction dir = sal == null || sal.getDirection() == null ? null : directionDAO.find(sal.getDirection().getId());
        directionSalarie = dir == null || dir.getId() == 0 ? null : dir.getNom();
        directeurAttendu = dir == null || dir.getDirigePar() <= 0 ? null : userDAO.findAccountById(dir.getDirigePar());
        directeur = directeurAttendu != null && directeurAttendu.getId() == me.getId();
        java.util.logging.Logger.getLogger(FicheView.class.getName()).info("HRX fiche " + e.getId() + " - rôles de " + me.getLogin() + " : moi=" + (s.getId() == me.getId())
                + " n1=" + (e.getEvaluateur() != null && e.getEvaluateur().getId() == me.getId()) + " directeur=" + directeur
                + " (direction salarié=" + directionSalarie + ", dirigée par=" + (directeurAttendu == null ? "personne" : directeurAttendu.getLogin()) + ")");
        dcht = privilegeBean.hasPrivilege("EVAL_DCHT") || privilegeBean.hasPrivilege("EVAL_ANOM");
        if (!moi && !n1 && !n2 && !directeur && !dcht) { redirect("/ui/home"); return; }
        if (isRevisionDirecteur()) photographierN1();
        // Première revue du N+1 : la colonne N+1 est pré-remplie avec l'auto-évaluation, à confirmer ou corriger.
        if (isSaisieN1() && isStatut(Evaluation.SOUMISE) && e.getObjectifs().stream().allMatch(o -> o.getRealisationN1() == null && !o.isNonApplicableN1() && (o.getCommentaireN1() == null || o.getCommentaireN1().isBlank()))) {
            boolean copie = false;
            for (ObjectifEvaluation o : e.getObjectifs()) if (o.getRealisationSalarie() != null || o.isNonApplicableSalarie()) { reprendre(o); copie = true; }
            for (EvaluationCritere c : e.getCriteres()) if (c.getNiveau() == null && c.getAutoNiveau() != null) { c.setNiveau(c.getAutoNiveau()); copie = true; }
            if (copie) e = service.enregistrer(e); e = service.charger(e.getId());
        }
    }

    /** Reprend pour une ligne les valeurs du collaborateur (%, NA, commentaire) : le N+1 confirme ou modifie ensuite. */
    public void reprendre(ObjectifEvaluation o) {
        o.setNonApplicableN1(o.isNonApplicableSalarie());
        o.setRealisationN1(o.isNonApplicableSalarie() ? null : o.getRealisationSalarie());
        o.setCommentaireN1(o.getCommentaireSalarie());
    }
    public void confirmer(ObjectifEvaluation o) { reprendre(o); }
    public void confirmerTout() { for (ObjectifEvaluation o : e.getObjectifs()) reprendre(o); for (EvaluationCritere c : e.getCriteres()) if (c.getAutoNiveau() != null) c.setNiveau(c.getAutoNiveau()); info("Auto-évaluation reprise", "Les valeurs du collaborateur ont été recopiées dans votre colonne. Corrigez ce qui ne va pas, puis transmettez."); }
    /** Le N+1 a repris à l'identique la valeur du collaborateur. */
    public boolean isConfirme(ObjectifEvaluation o) {
        if (o.isNonApplicableN1() || o.isNonApplicableSalarie()) return o.isNonApplicableN1() == o.isNonApplicableSalarie();
        return o.getRealisationN1() != null && o.getRealisationN1().equals(o.getRealisationSalarie());
    }
    public boolean isEcart(ObjectifEvaluation o) { return (o.getRealisationN1() != null || o.isNonApplicableN1()) && !isConfirme(o) && (o.getRealisationSalarie() != null || o.isNonApplicableSalarie()); }
    public long getNbEcarts() { return e.getObjectifs().stream().filter(this::isEcart).count(); }

    private boolean estAuDessus(Users u, int chefId) {
        if (u == null) return false;
        Set<Integer> vus = new HashSet<>();
        Users cur = userDAO.findAccountById(u.getId()).getSuperieurHierarchique();
        while (cur != null && vus.add(cur.getId())) {
            if (cur.getId() == chefId) return true;
            cur = userDAO.findAccountById(cur.getId()).getSuperieurHierarchique();
        }
        return false;
    }

    public boolean isStatut(String s) { return e != null && s.equals(e.getStatut()); }
    /** Le salarié renseigne son auto-évaluation tant qu'elle n'est pas soumise (ou après retour). */
    public boolean isSaisieSalarie() { return moi && (isStatut(Evaluation.INITIEE) || isStatut(Evaluation.RETOURNEE)); }
    /** Le salarié peut commenter (« commentaires et propositions du salarié ») jusqu'à validation. */
    public boolean isCommentaireSalarie() { return moi && !isStatut(Evaluation.VALIDEE); }
    /** Le N+1 évalue après soumission ; il peut aussi évaluer une fiche non soumise (collaborateur absent). */
    public boolean isSaisieN1() { return (n1 || dcht) && (isStatut(Evaluation.SOUMISE) || isStatut(Evaluation.INITIEE) || isStatut(Evaluation.RETOURNEE)); }
    public boolean isSaisieN2() { return (n2 || dcht) && isStatut(Evaluation.EVALUEE) && e.getDateVisaN2() == null; }
    /** Avis du directeur : le directeur de la direction ; la DCHT ne le remplace que si la direction n'a pas de directeur. */
    public boolean isSaisieDirecteur() { return (directeur || dcht && directeurAttendu == null) && isStatut(Evaluation.EVALUEE) && e.getDateAvisDirecteur() == null; }
    /** Le directeur désigné peut réviser l'évaluation du N+1 (pourcentages, NA, commentaires, compétences) tant qu'il n'a pas rendu son avis. */
    public boolean isRevisionDirecteur() { return directeur && isStatut(Evaluation.EVALUEE) && e.getDateAvisDirecteur() == null; }
    /** Colonne « N+1 » éditable : par le N+1 pendant l'évaluation, ou par le directeur en révision. */
    public boolean isRevisionN1() { return isSaisieN1() || isRevisionDirecteur(); }
    /** La DCHT ne clôture qu'après l'avis du directeur (sauf direction sans directeur). */
    public boolean isValidationDcht() { return dcht && isStatut(Evaluation.EVALUEE) && (directeurAttendu == null || e.getDateAvisDirecteur() != null); }
    /** DCHT en attente de l'avis du directeur avant de pouvoir valider. */
    public boolean isAttenteDirecteurDcht() { return dcht && !directeur && isStatut(Evaluation.EVALUEE) && directeurAttendu != null && e.getDateAvisDirecteur() == null; }
    public boolean isLectureSeule() { return !isSaisieSalarie() && !isRevisionN1() && !isSaisieN2() && !isSaisieDirecteur() && !isCommentaireSalarie() && !isPeutRouvrirN1() && !isValidationDcht(); }

    // lecture
    public List<String> getFamillesII() { return familles("II"); }
    public List<String> getFamillesIII() { return familles("III"); }
    private List<String> familles(String section) {
        List<String> f = new ArrayList<>();
        for (EvaluationCritere c : e.getCriteres()) if (c.getCritere().getSection().equals(section) && !f.contains(c.getCritere().getFamille())) f.add(c.getCritere().getFamille());
        return f;
    }
    public List<EvaluationCritere> criteresDe(String famille) { return e.getCriteres().stream().filter(c -> c.getCritere().getFamille().equals(famille)).toList(); }
    public String familleLabel(String f) { return e.getCriteres().stream().filter(c -> c.getCritere().getFamille().equals(f)).findFirst().map(c -> c.getCritere().getFamilleLabel()).orElse(f); }
    public String niveauLabel(String n) { return EvaluationCritere.niveauLabel(n); }
    public List<String> getOrigines() { return List.of("EVALUATEUR", "EVALUE", "DIRECTEUR"); }
    public String origineLabel(String o) { return "EVALUE".equals(o) ? "Évalué" : "DIRECTEUR".equals(o) ? "Directeur" : "Évaluateur"; }
    public List<String> getNiveaux() { return List.of("A", "EA", "NA", "NM"); }
    public List<String> getNiveauxIII() { return List.of("A", "EA", "NA"); }

    /** Objectifs groupés par activité (ordre de la fiche de fixation). */
    public List<String> getActivites() {
        List<String> a = new ArrayList<>();
        for (ObjectifEvaluation o : e.getObjectifs()) { String k = s(o.getObjectif().getActivite()); if (!a.contains(k)) a.add(k); }
        return a;
    }
    public List<ObjectifEvaluation> objectifsDe(String activite) { return e.getObjectifs().stream().filter(o -> s(o.getObjectif().getActivite()).equals(activite)).toList(); }
    public List<BesoinFormation> formationsDe(String origine) { return e.getBesoinsFormation().stream().filter(b -> origine.equals(b.getOrigine())).toList(); }
    public boolean peutAjouterFormation(String origine) {
        return switch (origine) { case "EVALUE" -> isSaisieSalarie() || isCommentaireSalarie() && !isStatut(Evaluation.VALIDEE); case "DIRECTEUR" -> isSaisieDirecteur(); default -> isSaisieN1(); };
    }

    public Double getScoreApercu() {
        Evaluation tmp = new Evaluation(); tmp.setObjectifs(e.getObjectifs());
        service.calculer(tmp);
        return tmp.getScoreObjectifs();
    }
    public Double getNoteApercu() { Double p = getScoreApercu(); return p == null ? null : Math.round(Evaluation.noteSur20(p) * 10) / 10.0; }
    public String getAppreciationApercu() { Double n = getNoteApercu(); return n == null ? "" : Evaluation.appreciationPour(n); }
    public long getNbObjectifsNotes() { return e.getObjectifs().stream().filter(o -> o.isNonApplicableN1() || o.getRealisationN1() != null).count(); }
    public long getNbAutoEval() { return e.getObjectifs().stream().filter(o -> o.isNonApplicableSalarie() || o.getRealisationSalarie() != null).count(); }
    public long getNbNonApplicables() { return e.getObjectifs().stream().filter(ObjectifEvaluation::isNonApplicableN1).count(); }
    /** Bascule NA côté salarié : vide le pourcentage. */
    public void basculerNaSalarie(ObjectifEvaluation o) { if (o.isNonApplicableSalarie()) o.setRealisationSalarie(null); }
    public void basculerNaN1(ObjectifEvaluation o) { if (o.isNonApplicableN1()) o.setRealisationN1(null); }

    public String getAnciennete() {
        Users u = e.getSalarie();
        if (u.getDateEmbauche() == null) return "—";
        var d = u.getDateEmbauche().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        var p = java.time.Period.between(d, java.time.LocalDate.now());
        String a = p.getYears() > 0 ? p.getYears() + " an" + (p.getYears() > 1 ? "s" : "") : "", m = p.getMonths() > 0 ? p.getMonths() + " mois" : "";
        String r = a + (a.isEmpty() || m.isEmpty() ? "" : " et ") + m;
        return r.isEmpty() ? "moins d'un mois" : r;
    }
    public String initiales() {
        Users u = e.getSalarie(); String p = s(u.getPrenom()), n = s(u.getNom());
        return ((p.isEmpty() ? "" : p.substring(0, 1)) + (n.isEmpty() ? "" : n.substring(0, 1))).toUpperCase(Locale.ROOT);
    }

    // actions
    public void enregistrer() {
        if (!peutModifierFiche()) throw new SecurityException("Modification de la fiche non autorisée");
        e = service.enregistrer(e); e = service.charger(e.getId());
        info("Fiche enregistrée", "Vos saisies sont conservées en brouillon.");
    }

    public void soumettre() {
        if (!isSaisieSalarie()) return;
        List<String> manquants = e.getObjectifs().stream().filter(o -> !o.isAutoEvalComplete()).map(o -> o.getObjectif().getLibelle()).toList();
        if (!manquants.isEmpty()) { error("Auto-évaluation incomplète", "Renseignez un % (ou « Non applicable ») sur chaque objectif, avec un commentaire s'il n'est pas atteint à 100 % ou s'il est NA. À compléter : " + String.join(" ; ", manquants.stream().map(l -> l.length() > 60 ? l.substring(0, 60) + "…" : l).toList())); return; }
        e = service.soumettre(e); e = service.charger(e.getId());
        auditService.updated("Évaluations", "Evaluation", e.getId(), "Auto-évaluation soumise au N+1", e.getSalarie().getNomComplet() + " : " + e.getCampagne().getLibelle());
        try { notifications.autoEvaluationSoumise(e); } catch (Exception ex) { /* la notification ne doit jamais bloquer le circuit */ }
        cd.mura.hrx.utils.Dialogue.succes("Auto-évaluation soumise", "Vous allez recevoir un accusé de réception par e-mail. Votre N+1 est prévenu.");
    }

    public void evaluer() {
        if (!isSaisieN1()) return;
        List<String> manquants = e.getObjectifs().stream().filter(o -> !o.isEvalN1Complete()).map(o -> o.getObjectif().getLibelle()).toList();
        if (!manquants.isEmpty()) { error("Évaluation incomplète", "Renseignez un % (ou « Non applicable ») sur chaque objectif, avec un commentaire s'il n'est pas à 100 %, s'il est NA ou si vous n'êtes pas d'accord avec le collaborateur. À compléter : " + String.join(" ; ", manquants.stream().map(l -> l.length() > 60 ? l.substring(0, 60) + "…" : l).toList())); return; }
        boolean critOk = e.getCriteres().stream().allMatch(c -> c.getNiveau() != null && !c.getNiveau().isBlank());
        if (!critOk) { error("Compétences non notées", "Positionnez chaque compétence et chaque valeur (A, EA, NA)."); return; }
        if (s(e.getCommentaireEvaluateur()).isBlank()) { error("Appréciation manquante", "Rédigez l'appréciation générale du supérieur hiérarchique direct."); return; }
        e = service.evaluer(e); e = service.charger(e.getId());
        auditService.updated("Évaluations", "Evaluation", e.getId(), "Évaluation du N+1 transmise", e.getSalarie().getNomComplet() + " - note " + e.getNoteGlobale() + "/20 (" + e.getAppreciation() + ")");
        try {
            Users chefN1 = e.getEvaluateur() == null ? null : userDAO.findAccountById(e.getEvaluateur().getId()).getSuperieurHierarchique();
            Users dir = e.getSalarie().getDirection() == null || e.getSalarie().getDirection().getDirigePar() <= 0 ? null : userDAO.findAccountById(e.getSalarie().getDirection().getDirigePar());
            if (dir != null && chefN1 != null && dir.getId() == chefN1.getId()) chefN1 = null;
            notifications.evaluationTransmise(e, chefN1, dir);
        } catch (Exception ex) { /* notification best-effort */ }
        cd.mura.hrx.utils.Dialogue.succes("Évaluation transmise", "Note " + e.getNoteGlobale() + "/20 : " + e.getAppreciation() + ". Le collaborateur, le N+2, le directeur et la DCHT sont notifiés par e-mail.");
    }

    public void retournerAuSalarie() {
        if (!isSaisieN1() || s(motif).isBlank()) { error("Motif requis", "Indiquez au collaborateur ce qu'il doit corriger."); return; }
        String m = motif;
        e = service.retourner(e, motif, true); e = service.charger(e.getId()); motif = null;
        auditService.updated("Évaluations", "Evaluation", e.getId(), "Fiche retournée au salarié", e.getSalarie().getNomComplet());
        try { notifications.ficheRetournee(e, true, m); } catch (Exception ex) { }
        cd.mura.hrx.utils.Dialogue.succes("Fiche retournée", "Le collaborateur peut corriger son auto-évaluation.");
    }

    public void viser() {
        if (!isSaisieN2()) return;
        e = service.viser(e, loginBean.getUsers(), e.getVisaN2()); e = service.charger(e.getId());
        auditService.updated("Évaluations", "Evaluation", e.getId(), "Visa du N+2", e.getSalarie().getNomComplet());
        cd.mura.hrx.utils.Dialogue.succes("Visa enregistré", "");
    }

    public void donnerAvis() {
        if (!isSaisieDirecteur()) return;
        List<String> corrections = List.of();
        if (isRevisionDirecteur()) {
            List<String> manquants = e.getObjectifs().stream().filter(o -> !o.isEvalN1Complete()).map(o -> court(o.getObjectif().getLibelle())).toList();
            if (!manquants.isEmpty()) { error("Évaluation incomplète", "Renseignez un % (ou « Non applicable ») sur chaque objectif, avec un commentaire s'il n'est pas à 100 %, s'il est NA ou s'il diffère de l'auto-évaluation. À compléter : " + String.join(" ; ", manquants)); return; }
            if (!e.getCriteres().stream().allMatch(c -> c.getNiveau() != null && !c.getNiveau().isBlank())) { error("Compétences non notées", "Positionnez chaque compétence et chaque valeur (A, EA, NA)."); return; }
            corrections = correctionsDirecteur();
            if (!corrections.isEmpty()) service.calculer(e);   // la note /20 suit les pourcentages révisés
        }
        e = service.avisDirecteur(e, loginBean.getUsers(), e.getAvisDirecteur()); e = service.charger(e.getId());
        auditService.updated("Évaluations", "Evaluation", e.getId(), "Avis du directeur", e.getSalarie().getNomComplet());
        if (!corrections.isEmpty())
            auditService.updated("Évaluations", "Evaluation", e.getId(), "Évaluation révisée par le directeur (" + corrections.size() + " correction" + (corrections.size() > 1 ? "s" : "") + ")",
                    e.getSalarie().getNomComplet() + " - nouvelle note " + e.getNoteGlobale() + "/20 (" + e.getAppreciation() + ")\n" + String.join("\n", corrections));
        cd.mura.hrx.utils.Dialogue.succes("Avis enregistré", corrections.isEmpty() ? "" : corrections.size() + " correction(s) apportée(s) à l'évaluation du N+1 ; note recalculée : " + e.getNoteGlobale() + "/20.");
    }

    /** Brouillon du directeur : enregistre ses corrections sans rendre l'avis. */
    public void enregistrerRevision() {
        if (!isRevisionDirecteur()) return;
        List<String> corrections = correctionsDirecteur();
        e = service.enregistrer(e); e = service.charger(e.getId());
        if (!corrections.isEmpty())
            auditService.updated("Évaluations", "Evaluation", e.getId(), "Corrections du directeur (brouillon)", e.getSalarie().getNomComplet() + "\n" + String.join("\n", corrections));
        photographierN1();
        info("Corrections enregistrées", "Elles seront prises en compte (note recalculée) lorsque vous rendrez votre avis.");
    }

    public void valider() {
        if (!isValidationDcht()) return;
        e = service.valider(e, e.getCommentaireDcht()); e = service.charger(e.getId());
        auditService.updated("Évaluations", "Evaluation", e.getId(), "Fiche validée par la DCHT", e.getSalarie().getNomComplet() + " : " + e.getCampagne().getLibelle());
        try { notifications.ficheValidee(e); } catch (Exception ex) { }
        cd.mura.hrx.utils.Dialogue.succes("Fiche validée", "L'évaluation est définitive et archivée.");
    }

    public void retournerAuN1() {
        if (!isValidationDcht() || s(motif).isBlank()) { error("Motif requis", "Précisez au N+1 l'anomalie à corriger."); return; }
        String m = motif;
        e = service.retourner(e, motif, false); e = service.charger(e.getId()); motif = null;
        auditService.updated("Évaluations", "Evaluation", e.getId(), "Fiche retournée au N+1 par la DCHT", e.getSalarie().getNomComplet());
        try { notifications.ficheRetournee(e, false, m); } catch (Exception ex) { }
        cd.mura.hrx.utils.Dialogue.succes("Fiche retournée au N+1", "");
    }

    /** Le N+1 reprend la main sur une fiche déjà transmise (avant validation DCHT). */
    public boolean isPeutRouvrirN1() { return (n1 || dcht) && isStatut(Evaluation.EVALUEE); }
    public void rouvrirN1() {
        if (!isPeutRouvrirN1()) return;
        e = service.rouvrirParN1(e); e = service.charger(e.getId());
        auditService.updated("Évaluations", "Evaluation", e.getId(), "Réouverture de l'évaluation par le N+1", e.getSalarie().getNomComplet());
        cd.mura.hrx.utils.Dialogue.succes("Évaluation rouverte", "Vous pouvez corriger puis transmettre de nouveau.");
    }
    public List<String> getNiveauxAtteinte() { return List.of("ATTEINTS", "MOYENNEMENT", "FAIBLEMENT", "NON_ATTEINTS"); }
    public List<String> getSouhaits() { return List.of("BIEN_POSTE", "ACCOMPAGNEMENT", "CHANGER_POSTE", "AUTRES"); }
    public String niveauAtteinteLabel(String v) { return Evaluation.niveauAtteinteLabel(v); }
    public String souhaitLabel(String v) { return Evaluation.souhaitLabel(v); }
    /** Message affiché quand un souhait coché vient d'en décocher un autre, incompatible. */
    private String souhaitExclu;
    public String getSouhaitExclu() { return souhaitExclu; }
    public List<String> getSouhaitsChoisis() { return e == null ? new ArrayList<>() : e.getSouhaits(); }
    /**
     * Souhaits cochés. « Bien dans son poste » et « Souhaite changer de poste » s'excluent :
     * la case cochée en dernier l'emporte et l'autre est décochée.
     */
    public void setSouhaitsChoisis(List<String> l) {
        souhaitExclu = null;
        if (e == null) return;
        List<String> avant = e.getSouhaits();
        List<String> apres = new ArrayList<>(l == null ? List.of() : l);
        for (Map.Entry<String, String> x : Evaluation.SOUHAITS_EXCLUSIFS.entrySet()) {
            if (apres.contains(x.getKey()) && apres.contains(x.getValue())) {
                String garde = avant.contains(x.getKey()) ? x.getValue() : x.getKey();
                String retire = garde.equals(x.getKey()) ? x.getValue() : x.getKey();
                apres.remove(retire);
                souhaitExclu = "« " + Evaluation.souhaitLabel(retire) + " » a été décoché : il n'est pas compatible avec « " + Evaluation.souhaitLabel(garde) + " ».";
            }
        }
        e.setSouhaits(apres);
    }
    public String getNiveauAtteinteSuggere() { Double p = getScoreApercu(); return p == null ? null : Evaluation.niveauAtteintePour(p); }

    public void retourner() { if (isValidationDcht()) retournerAuN1(); else retournerAuSalarie(); }

    // travaux hors objectifs
    public void nouveauTravail() { travailEdit = new TravailHorsObjectif(); travailEdit.setOrigine(isSaisieN1() && !moi ? "N1" : "SALARIE"); travailEdit.setOrdre(e.getTravauxHorsObjectifs().size() + 1); }
    public void modifierTravail(TravailHorsObjectif t) { travailEdit = t; }
    /* Les ajouts annexes (travaux, formations) sont enregistrés seuls, sans recharger la fiche :
       la saisie en cours du formulaire principal, non encore enregistrée, reste intacte. */
    public void enregistrerTravail() {
        if (!(isSaisieSalarie() || isSaisieN1())) throw new SecurityException("Modification des travaux non autorisée");
        if (s(travailEdit.getLibelle()).isBlank()) { error("Libellé requis", "Décrivez le travail réalisé."); return; }
        if (travailEdit.getId() == 0) {
            travailEdit.setEvaluation(e); service.persister(travailEdit);
            e.getTravauxHorsObjectifs().add(travailEdit);
        } else {
            TravailHorsObjectif m = service.fusionner(travailEdit);
            e.getTravauxHorsObjectifs().replaceAll(t -> t.getId() == m.getId() ? m : t);
        }
        info("Travail enregistré", travailEdit.getLibelle());
    }
    public void supprimerTravail(TravailHorsObjectif t) {
        if (!(isSaisieSalarie() || isSaisieN1()) || !appartientTravaux(t)) {
            throw new SecurityException("Suppression du travail non autorisée");
        }
        service.supprimer(TravailHorsObjectif.class, t.getId());
        e.getTravauxHorsObjectifs().removeIf(x -> x.getId() == t.getId());
    }

    // formations
    public void nouvelleFormation(String origine) { formationEdit = new BesoinFormation(); formationEdit.setOrigine(origine); }
    public void modifierFormation(BesoinFormation b) { formationEdit = b; }
    public void enregistrerFormation() {
        if (!peutAjouterFormation(s(formationEdit.getOrigine()))) {
            throw new SecurityException("Modification des formations non autorisée");
        }
        if (s(formationEdit.getIntitule()).isBlank()) { error("Intitulé requis", "Indiquez la formation demandée."); return; }
        if (!getOrigines().contains(s(formationEdit.getOrigine()))) formationEdit.setOrigine("EVALUATEUR");
        if (s(formationEdit.getPriorite()).isBlank()) formationEdit.setPriorite("MOYENNE");
        if (formationEdit.getId() == 0) {
            formationEdit.setEvaluation(e); service.persister(formationEdit);
            e.getBesoinsFormation().add(formationEdit);
        } else {
            BesoinFormation m = service.fusionner(formationEdit);
            e.getBesoinsFormation().replaceAll(b -> b.getId() == m.getId() ? m : b);
        }
        info("Souhait de formation enregistré", formationEdit.getIntitule());
    }
    public void supprimerFormation(BesoinFormation b) {
        if (b == null || !peutAjouterFormation(s(b.getOrigine())) || !appartientFormations(b)) {
            throw new SecurityException("Suppression de la formation non autorisée");
        }
        service.supprimer(BesoinFormation.class, b.getId());
        e.getBesoinsFormation().removeIf(x -> x.getId() == b.getId());
    }

    private boolean peutModifierFiche() {
        return e != null && (isSaisieSalarie() || isCommentaireSalarie() || isRevisionN1()
                || isSaisieN2() || isSaisieDirecteur() || isValidationDcht());
    }

    private boolean appartientTravaux(TravailHorsObjectif travail) {
        return travail != null && e != null && e.getTravauxHorsObjectifs().stream()
                .anyMatch(x -> x.getId() == travail.getId());
    }

    private boolean appartientFormations(BesoinFormation formation) {
        return formation != null && e != null && e.getBesoinsFormation().stream()
                .anyMatch(x -> x.getId() == formation.getId());
    }

    // exports
    public StreamedContent getExportDocx() {
        byte[] data = exportService.docx(e);
        auditService.log("EXPORT", "Évaluations", "Evaluation", e.getId(), "Export Word de la fiche d'évaluation", e.getSalarie().getNomComplet(), true);
        return DefaultStreamedContent.builder().name(nomFichier("docx")).contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document").stream(() -> new ByteArrayInputStream(data)).build();
    }
    public StreamedContent getExportPdf() {
        byte[] data = exportService.pdf(e);
        auditService.log("EXPORT", "Évaluations", "Evaluation", e.getId(), "Export PDF de la fiche d'évaluation", e.getSalarie().getNomComplet(), true);
        return DefaultStreamedContent.builder().name(nomFichier("pdf")).contentType("application/pdf").stream(() -> new ByteArrayInputStream(data)).build();
    }
    private String nomFichier(String ext) {
        String n = java.text.Normalizer.normalize(s(e.getSalarie().getNom()) + "_" + s(e.getSalarie().getPrenom()), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9_]+", "_");
        return "MAF159_Evaluation_" + (Campagne.FIN_ANNEE.equals(e.getCampagne().getType()) ? "FA" : "MP") + "_" + e.getCampagne().getExercice() + "_" + n + "." + ext;
    }

    private static String s(String v) { return v == null ? "" : v; }
    private void redirect(String url) {
        try { FacesContext ctx = FacesContext.getCurrentInstance(); ctx.getExternalContext().redirect(ctx.getExternalContext().getRequestContextPath() + url); ctx.responseComplete(); } catch (IOException ignored) { }
    }
    /** Messages de la fiche : fenêtre stylée (repli automatique sur un message classique hors ajax). */
    private void info(String t, String d) { cd.mura.hrx.utils.Dialogue.succes(t, d); }
    private void error(String t, String d) { cd.mura.hrx.utils.Dialogue.erreur(t, d); }
}
