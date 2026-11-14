package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.dao.eval.CampagneDAO;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.Campagne;
import cd.mura.hrx.entity.eval.EvaluationCritere;
import cd.mura.hrx.entity.eval.EvaluationEssai;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.NotificationService;
import cd.mura.hrx.service.PeriodeEssaiService;
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
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Évaluations de fin de période d'essai : détection des recrues à évaluer, liste et fiche.
 * DCHT : tout le périmètre ; N+1 : ses collaborateurs directs ; N+2 : visa facultatif ; directeur : avis sur les
 * collaborateurs de sa direction ; collaborateur : sa propre évaluation.
 */
@Named
@ViewScoped
@Getter
@Setter
public class PeriodeEssaiView implements Serializable {

    private static final long serialVersionUID = 1L;
    public static final List<String> NIVEAUX = List.of(EvaluationCritere.A, EvaluationCritere.EA, EvaluationCritere.NA, EvaluationCritere.NM);
    /** Échelle de la fiche MAF 070. */
    public static final List<String> NIVEAUX_MAF070 = List.of("TB", "B", "P", "I", "PC");

    @Inject private LoginBean loginBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private PeriodeEssaiService service;
    @EJB private NotificationService notifications;
    @EJB private UserDAO userDAO;
    @EJB private CampagneDAO campagneDAO;
    @Inject private cd.mura.hrx.service.FicheEssaiExportService exportService;

    /** Paramètre d'URL : fiche affichée. */
    private Integer id;
    private EvaluationEssai courant;
    private Integer campagneId;
    private String motifRetour;
    private List<Campagne> campagnes = new ArrayList<>();

    private int moisEssai = 6;
    private List<PeriodeEssaiService.Echeance> recruesAEvaluer = new ArrayList<>();
    private List<PeriodeEssaiService.Echeance> prochaines = new ArrayList<>();
    /** En période d'essai ou en CDD, échéance encore lointaine. */
    private List<PeriodeEssaiService.Echeance> enCours = new ArrayList<>();
    private List<EvaluationEssai> evaluations = new ArrayList<>();
    private String filtre = "tous";
    /** Directeur et N+2 attendus pour la fiche affichée. */
    private Users directeurAttendu, n2Attendu;

    public void charger() {
        moisEssai = service.dureeMois();
        if (id != null) { chargerFiche(); return; }
        Users moi = moi();
        List<Users> population;
        if (isDcht()) {
            population = userDAO.findActifsAvecDirection();
            evaluations = service.lister(null);
        } else {
            population = userDAO.findBySuperieur(moi.getId());
            evaluations = new ArrayList<>(service.lister(moi.getId()));
            for (EvaluationEssai d : service.listerPourDirecteur(moi.getId()))
                if (evaluations.stream().noneMatch(x -> x.getId() == d.getId())) evaluations.add(d);
            EvaluationEssai mienne = service.findBySalarie(moi.getId());
            if (mienne != null && evaluations.stream().noneMatch(e -> e.getId() == mienne.getId())) evaluations.add(0, service.charger(mienne.getId()));
        }
        recruesAEvaluer = service.aEvaluer(population);
        prochaines = service.prochaines(population);
        enCours = service.enCours(population);
    }

    private void chargerFiche() {
        courant = service.charger(id);
        directeurAttendu = courant == null ? null : service.directeurAttendu(courant);
        n2Attendu = courant == null ? null : service.n2Attendu(courant);
        if (courant == null || !peutVoir(courant)) {
            courant = null;
            error("Évaluation inaccessible", "Cette évaluation de fin de période d'essai n'existe pas ou ne vous concerne pas.");
            return;
        }
        if (courant.isEnCours() && isPeutEditer()) {
            if (service.synchroniser(courant.getId())) courant = service.charger(id);
            if (courant.getDateEntretien() == null) courant.setDateEntretien(LocalDate.now());
        }
        if (isRevisionDirecteur() && realisesInitiaux.isEmpty()) photographierRealises();
        campagneId = courant.getCampagne() == null ? null : courant.getCampagne().getId();
        campagnes = campagneDAO.findAll().stream().filter(c -> !Campagne.ARCHIVEE.equals(c.getStatut()) || (campagneId != null && c.getId() == campagneId)).toList();
    }

    // droits

    private Users moi() { return loginBean.getUsers(); }
    private boolean isRoot() { return moi().getProfil() != null && moi().getProfil().getId() == 1; }
    public boolean isDcht() { return isRoot() || authorization.hasPrivilege("EVAL_DCHT") || authorization.hasPrivilege("ESSAI_DCHT"); }
    private boolean estEvaluateur(EvaluationEssai e) { return e.getEvaluateur() != null && e.getEvaluateur().getId() == moi().getId(); }
    public boolean isSalarie() { return courant != null && courant.getSalarie().getId() == moi().getId(); }
    /** Directeur de la direction du collaborateur. */
    public boolean isDirecteur() { return directeurAttendu != null && directeurAttendu.getId() == moi().getId(); }
    /** Supérieur de l'évaluateur, à n'importe quel niveau (le N+2 et au-dessus). */
    public boolean isN2() {
        if (courant == null || courant.getEvaluateur() == null || estEvaluateur(courant) || isSalarie()) return false;
        Set<Integer> vus = new HashSet<>();
        Users cur = userDAO.findAccountById(courant.getEvaluateur().getId());
        cur = cur == null ? null : cur.getSuperieurHierarchique();
        while (cur != null && vus.add(cur.getId())) {
            if (cur.getId() == moi().getId()) return true;
            Users c = userDAO.findAccountById(cur.getId());
            cur = c == null ? null : c.getSuperieurHierarchique();
        }
        return false;
    }
    private boolean peutVoir(EvaluationEssai e) {
        if (isDcht() || estEvaluateur(e) || e.getSalarie().getId() == moi().getId()) return true;
        if (isDirecteur() || isN2()) return true;
        Users s = userDAO.findUserById(e.getSalarie().getId());
        return s != null && s.getSuperieurHierarchique() != null && s.getSuperieurHierarchique().getId() == moi().getId();
    }
    /** Saisie de l'évaluation : l'évaluateur désigné seulement (la DCHT consulte et statue, elle ne remplit pas à sa place). */
    public boolean isPeutEditer() { return courant != null && courant.isEnCours() && !isSalarie() && estEvaluateur(courant); }
    /** Validation : la DCHT, ni le collaborateur ni l'évaluateur (séparation des rôles). */
    public boolean isPeutValider() { return courant != null && courant.isTransmise() && isDcht() && !isSalarie() && !estEvaluateur(courant); }
    /** Visa du N+2 (facultatif) : tant que la DCHT n'a pas validé. */
    public boolean isSaisieN2() { return courant != null && courant.isTransmise() && courant.getDateVisaN2() == null && isN2() && !isSalarie(); }
    /** Avis du directeur ; la DCHT peut le saisir pour lui (directeur absent ou direction sans directeur). */
    public boolean isSaisieDirecteur() { return courant != null && courant.isTransmise() && courant.getDateAvisDirecteur() == null && (isDirecteur() || (directeurAttendu == null && isDcht())) && !isSalarie(); }
    /** Le circuit passe par un directeur désigné. */
    public boolean isAvecDirecteur() { return directeurAttendu != null; }
    /** Le directeur (ou la DCHT si la direction n'a pas de directeur) peut corriger les réalisés du N+1 tant qu'il n'a pas rendu son avis. */
    public boolean isRevisionDirecteur() { return isSaisieDirecteur(); }
    /** Colonnes « R » éditables : par l'évaluateur pendant la saisie, ou par le directeur en révision. */
    public boolean isSaisieRealise() { return isPeutEditer() || isRevisionDirecteur(); }

    /** Valeurs du N+1 au chargement (réalisés, notation, commentaires), pour tracer les corrections du directeur. */
    private java.util.Map<Integer, String[]> realisesInitiaux = new java.util.HashMap<>();
    private void photographierRealises() {
        realisesInitiaux.clear();
        if (courant == null) return;
        for (cd.mura.hrx.entity.eval.EvaluationEssaiCritere k : courant.getCriteres())
            realisesInitiaux.put(k.getId(), new String[]{k.getR1(), k.getR2(), k.getR3(), k.getR4(), k.getNiveau(), k.getCommentaire()});
    }
    private static String v(String x) { return x == null ? "" : x.trim(); }
    private java.util.List<String> correctionsRealises() {
        java.util.List<String> l = new java.util.ArrayList<>();
        if (courant == null) return l;
        for (cd.mura.hrx.entity.eval.EvaluationEssaiCritere k : courant.getCriteres()) {
            String[] av = realisesInitiaux.get(k.getId());
            if (av == null) continue;
            String[] ap = {k.getR1(), k.getR2(), k.getR3(), k.getR4(), k.getNiveau(), k.getCommentaire()};
            for (int t = 0; t < 6; t++) {
                String a = v(av[t]), b = v(ap[t]);
                if (a.equals(b)) continue;
                if (t < 4) l.add("« " + k.getLibelle() + " », réalisé T" + (t + 1) + " : " + (a.isEmpty() ? "vide" : cd.mura.hrx.entity.eval.EvaluationEssaiCritere.pourcent(a))
                        + " → " + (b.isEmpty() ? "vide" : cd.mura.hrx.entity.eval.EvaluationEssaiCritere.pourcent(b)));
                else if (t == 4) l.add("« " + k.getLibelle() + " », notation : " + (a.isEmpty() ? "—" : a) + " → " + (b.isEmpty() ? "—" : b));
                else l.add("« " + k.getLibelle() + " », " + (k.isObjectif() ? "commentaire" : "axes d'amélioration") + " : « " + (a.isEmpty() ? "vide" : a) + " » → « " + (b.isEmpty() ? "vide" : b) + " »");
            }
        }
        return l;
    }

    /** Brouillon du directeur : enregistre ses corrections des réalisés sans rendre l'avis. */
    public void enregistrerCorrections() {
        if (!isRevisionDirecteur()) return;
        try {
            java.util.List<String> c = correctionsRealises();
            service.corrigerRealises(courant, moi());
            if (!c.isEmpty()) auditService.updated("Évaluations", "EvaluationEssai", courant.getId(), "Évaluation du N+1 corrigée par le directeur (" + c.size() + ")",
                    courant.getSalarie().getNomComplet() + "\n" + String.join("\n", c));
            chargerFiche(); photographierRealises();
            cd.mura.hrx.utils.Dialogue.succes("Corrections enregistrées", c.isEmpty() ? "Aucune modification par rapport à l'évaluation du N+1." : c.size() + " correction(s) enregistrée(s). Vous pouvez maintenant rendre votre avis.");
        } catch (Exception ex) { error("Enregistrement impossible", cause(ex)); }
    }
    /** Le collaborateur commente et signe une fois l'évaluation transmise par son N+1. */
    public boolean isPeutCommenter() { return isSalarie() && !courant.isEnCours() && courant.getDateSignatureSalarie() == null; }
    /** Ouverture : le N+1 direct seulement, une fois l'échéance ouvrable (fin d'essai atteinte, ou 45 j avant la fin d'un CDD). */
    public boolean peutOuvrir(Users u) {
        return u != null && u.getId() != moi().getId() && u.getSuperieurHierarchique() != null && u.getSuperieurHierarchique().getId() == moi().getId();
    }
    public boolean peutOuvrir(PeriodeEssaiService.Echeance e) { return e != null && peutOuvrir(e.salarie()) && PeriodeEssaiService.ouvrable(e); }
    public LocalDate ouvrableLe(PeriodeEssaiService.Echeance e) { return PeriodeEssaiService.ouvrableLe(e); }

    // lecture

    public LocalDate embauche(Users u) { return PeriodeEssaiService.local(u.getDateEmbauche()); }
    public LocalDate debutContrat(Users u) { return PeriodeEssaiService.debutContrat(u); }
    public List<String> getNiveaux() { return courant != null && !courant.isMaf070() ? NIVEAUX : NIVEAUX_MAF070; }
    public String niveauLabel(String n) {
        if (n == null) return "";
        return switch (n) {
            case "TB" -> "Très bien"; case "B" -> "Bien"; case "P" -> "Passable"; case "I" -> "Insuffisant"; case "PC" -> "Pas concerné";
            default -> EvaluationCritere.niveauLabel(n);
        };
    }
    public String familleLabel(String f) { return cd.mura.hrx.entity.eval.EvaluationEssaiCritere.familleLabel(f); }
    public String decisionLabel(String d) { return EvaluationEssai.decisionLabel(d); }
    public long nb(String statut) { return evaluations.stream().filter(e -> statut.equals(e.getStatut())).count(); }
    public List<EvaluationEssai> getEvaluationsFiltrees() {
        return "tous".equals(filtre) ? evaluations : evaluations.stream().filter(e -> filtre.equals(e.getStatut())).toList();
    }

    // export (fiche MAF 070)

    /** La fiche exportée est rechargée pour refléter l'état enregistré, pas une saisie en cours. */
    public org.primefaces.model.StreamedContent getExportPdf() {
        if (courant == null) return null;
        byte[] data = exportService.pdf(service.charger(courant.getId()));
        auditService.log("EXPORT", "Évaluations", "EvaluationEssai", courant.getId(), "Export PDF de la fiche MAF 070", courant.getSalarie().getNomComplet(), true);
        return org.primefaces.model.DefaultStreamedContent.builder().name(nomFichier("pdf")).contentType("application/pdf")
                .stream(() -> new java.io.ByteArrayInputStream(data)).build();
    }
    public org.primefaces.model.StreamedContent getExportDocx() {
        if (courant == null) return null;
        byte[] data = exportService.docx(service.charger(courant.getId()));
        auditService.log("EXPORT", "Évaluations", "EvaluationEssai", courant.getId(), "Export Word de la fiche MAF 070", courant.getSalarie().getNomComplet(), true);
        return org.primefaces.model.DefaultStreamedContent.builder().name(nomFichier("docx"))
                .contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .stream(() -> new java.io.ByteArrayInputStream(data)).build();
    }
    private String nomFichier(String ext) {
        Users u = courant.getSalarie();
        String n = java.text.Normalizer.normalize((u.getNom() == null ? "" : u.getNom()) + "_" + (u.getPrenom() == null ? "" : u.getPrenom()), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9_]+", "_");
        String type = courant.isFinCdd() ? "Fin_CDD" : "Fin_Essai";
        return "MAF070_Evaluation_" + type + "_" + n + "." + ext;
    }

    // actions

    public void ouvrir(Users u, String type) {
        if (!peutOuvrir(u)) { error("Action non autorisée", "Seul le N+1 direct du collaborateur peut ouvrir cette évaluation."); return; }
        try {
            EvaluationEssai e = service.ouvrir(u, type, moi());
            auditService.created("Évaluations", "EvaluationEssai", e.getId(), "Ouverture de l'évaluation (" + e.getTypeLabel() + ")", u.getNomComplet());
            try { notifications.essaiOuverte(service.charger(e.getId())); } catch (Exception ex) { /* l'envoi ne bloque pas */ }
            FacesContext.getCurrentInstance().getExternalContext().getFlash().setKeepMessages(true);
            info("Évaluation ouverte", "Renseignez les réalisations, notez chaque critère, rédigez l'appréciation globale et la suite à donner au contrat, puis transmettez.");
            redirect(e.getId());
        } catch (Exception ex) { error("Ouverture impossible", cause(ex)); }
    }

    public void enregistrer() {
        if (!isPeutEditer()) return;
        try {
            service.enregistrer(courant, campagneId, moi());
            info("Évaluation enregistrée", "Vous pourrez la transmettre à la DCHT une fois terminée.");
            chargerFiche();
        } catch (Exception ex) { error("Enregistrement impossible", cause(ex)); chargerFiche(); }
    }

    public void transmettre() {
        if (!isPeutEditer()) return;
        try {
            service.transmettre(courant, campagneId, moi());
            chargerFiche();
            auditService.updated("Évaluations", "EvaluationEssai", courant.getId(), "Transmission à la DCHT", courant.getSalarie().getNomComplet() + " : " + courant.getDecisionLabel());
            try { notifications.essaiTransmise(courant, n2Attendu, directeurAttendu); } catch (Exception ex) { }
            cd.mura.hrx.utils.Dialogue.succes("Évaluation transmise", directeurAttendu != null ? directeurAttendu.getNomComplet() + " (directeur) est prévenu pour donner son avis ; le N+2 peut apposer son visa." : "La DCHT est prévenue par e-mail.");
        } catch (Exception ex) { error("Transmission impossible", cause(ex)); }
    }

    public void viser() {
        if (!isSaisieN2()) return;
        try {
            service.viser(courant, moi(), courant.getVisaN2());
            chargerFiche();
            auditService.updated("Évaluations", "EvaluationEssai", courant.getId(), "Visa du N+2", courant.getSalarie().getNomComplet());
            cd.mura.hrx.utils.Dialogue.succes("Visa enregistré", "Merci : votre visa figure sur l'évaluation.");
        } catch (Exception ex) { error("Visa impossible", cause(ex)); chargerFiche(); }
    }

    public void donnerAvis() {
        if (!isSaisieDirecteur()) return;
        try {
            java.util.List<String> corrections = correctionsRealises();
            service.avisDirecteur(courant, moi(), courant.getAvisDirecteur());
            chargerFiche();
            auditService.updated("Évaluations", "EvaluationEssai", courant.getId(), "Avis du directeur", courant.getSalarie().getNomComplet());
            if (!corrections.isEmpty()) auditService.updated("Évaluations", "EvaluationEssai", courant.getId(), "Évaluation du N+1 corrigée par le directeur (" + corrections.size() + ")",
                    courant.getSalarie().getNomComplet() + "\n" + String.join("\n", corrections));
            realisesInitiaux.clear();
            try { notifications.essaiAvisDirecteur(courant); } catch (Exception ex) { }
            cd.mura.hrx.utils.Dialogue.succes("Avis enregistré", "La DCHT est prévenue : l'évaluation est prête pour validation.");
        } catch (Exception ex) { error("Avis impossible", cause(ex)); }
    }

    public void valider() {
        if (!isPeutValider()) return;
        try {
            service.valider(courant, moi());
            chargerFiche();
            auditService.updated("Évaluations", "EvaluationEssai", courant.getId(), "Validation DCHT", courant.getSalarie().getNomComplet() + " : " + courant.getDecisionLabel());
            try { notifications.essaiValidee(courant); } catch (Exception ex) { }
            String contrat = switch (courant.getDecision() == null ? "" : courant.getDecision()) {
                case EvaluationEssai.CONFIRMER_CDI -> " Le contrat du collaborateur passe en CDI dans sa fiche.";
                case EvaluationEssai.RENOUVELER -> courant.getNouvelleEcheance() == null ? "" : " Le CDD est renouvelé dans sa fiche jusqu'au " + courant.getNouvelleEcheance().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".";
                case EvaluationEssai.FIN_CONTRAT -> " Pensez à désactiver le compte à la date de sortie.";
                default -> "";
            };
            cd.mura.hrx.utils.Dialogue.succes("Évaluation validée", "Le collaborateur et son N+1 sont prévenus." + contrat);
        } catch (Exception ex) { error("Validation impossible", cause(ex)); chargerFiche(); }
    }

    public void retourner() {
        if (!isPeutValider()) return;
        try {
            service.retourner(courant, motifRetour, moi());
            chargerFiche();
            auditService.updated("Évaluations", "EvaluationEssai", courant.getId(), "Retour au N+1", motifRetour);
            try { notifications.essaiRetournee(courant); } catch (Exception ex) { }
            motifRetour = null;
            cd.mura.hrx.utils.Dialogue.succes("Évaluation retournée", "Le N+1 est prévenu par e-mail.");
            org.primefaces.PrimeFaces.current().ajax().addCallbackParam("ok", true);
        } catch (Exception ex) { error("Retour impossible", cause(ex)); }
    }

    public void commenter() {
        if (!isPeutCommenter()) return;
        try {
            service.commenter(courant.getId(), courant.getCommentaireSalarie(), moi());
            cd.mura.hrx.utils.Dialogue.succes("Commentaire enregistré", "Il est visible par votre N+1 et la DCHT.");
            chargerFiche();
        } catch (Exception ex) { error("Enregistrement impossible", cause(ex)); }
    }

    /** Signature du collaborateur : prise de connaissance de l'évaluation, avec son commentaire. */
    public void signer() {
        if (!isPeutCommenter()) return;
        try {
            service.signerSalarie(courant.getId(), courant.getCommentaireSalarie(), moi());
            auditService.updated("Évaluations", "EvaluationEssai", courant.getId(), "Signature du collaborateur", courant.getSalarie().getNomComplet());
            cd.mura.hrx.utils.Dialogue.succes("Évaluation signée", "Votre signature et votre commentaire figurent sur l'évaluation.");
            chargerFiche();
        } catch (Exception ex) { error("Signature impossible", cause(ex)); }
    }

    public void supprimer() {
        if (courant == null || !isDcht() || isSalarie() || courant.isValidee()) return;
        try {
            int sid = courant.getId(); String nom = courant.getSalarie().getNomComplet();
            service.supprimer(sid, moi());
            auditService.deleted("Évaluations", "EvaluationEssai", sid, "Suppression de l'évaluation de fin de période d'essai", nom);
            FacesContext.getCurrentInstance().getExternalContext().getFlash().setKeepMessages(true);
            info("Évaluation supprimée", nom + " réapparaîtra parmi les évaluations à réaliser si son échéance est toujours dans la période de détection.");
            FacesContext.getCurrentInstance().getExternalContext().redirect(FacesContext.getCurrentInstance().getExternalContext().getRequestContextPath() + "/ui/periodeEssai");
        } catch (Exception ex) { error("Suppression impossible", cause(ex)); }
    }

    private void redirect(int evalId) throws java.io.IOException {
        FacesContext.getCurrentInstance().getExternalContext().redirect(FacesContext.getCurrentInstance().getExternalContext().getRequestContextPath() + "/ui/periodeEssai?id=" + evalId);
    }

    /** Message lisible : les exceptions métier arrivent enveloppées par le conteneur EJB. */
    static String cause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        if (c instanceof jakarta.persistence.OptimisticLockException || c.getClass().getSimpleName().contains("StaleObject"))
            return "Quelqu'un d'autre a modifié cette évaluation entre-temps. Rechargez la page.";
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
}
