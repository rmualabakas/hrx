package cd.mura.hrx.bean.recrutement;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.dao.config.DirectionDAO;
import cd.mura.hrx.entity.config.App;
import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.recrutement.*;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.recrutement.*;
import cd.mura.hrx.service.recrutement.RecrutementService.Detail;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;
import org.primefaces.PrimeFaces;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.*;

/** Présélection des CV : postes et critères, dépôt des CV, analyse par l'IA, classement et décisions. */
@Named
@ViewScoped
@Getter
@Setter
public class RecrutementView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @Inject private RecrutementExportService exportService;
    @Inject private ClaudeCvService claude;
    @EJB private RecrutementService service;
    @EJB private CvthequeService cvtheque;
    @EJB private AnalyseMoteur moteur;
    @EJB private DirectionDAO directionDAO;
    @EJB private AppDAO appDAO;

    /** liste, edition ou poste. */
    private String mode = "liste";
    private boolean voirArchives;
    private List<Poste> postes = new ArrayList<>();
    private Map<Integer, long[]> compteurs = new HashMap<>();

    // édition
    private Poste edit;
    private Integer directionId;
    private List<Direction> directions = new ArrayList<>();

    // poste affiché
    private Poste courant;
    private List<Ligne> lignes = new ArrayList<>();
    private String filtre = "tous";
    /** Vue du classement : "tableau" ou "graphique". */
    private String vue = "tableau";
    private Ligne selection;
    private Integer apercuId;

    // ajout depuis la CVthèque
    private String rechercheCvtheque;
    private Integer dossierCvtheque;
    private List<Cv> resultatsCvtheque = new ArrayList<>();
    private List<Integer> choixCvtheque = new ArrayList<>();
    private List<CvDossier> dossiers = new ArrayList<>();

    /** Une candidature dans le classement. */
    @Getter @Setter
    public static class Ligne implements Serializable {
        private int id;
        private int rang;
        private Cv cv;
        private String statut;
        private Integer score;
        private boolean elimine;
        private String synthese;
        private String erreur;
        private String decision;
        private String commentaire;
        private Map<Integer, Detail> details = new HashMap<>();

        public boolean isTerminee() { return CvAnalyse.TERMINEE.equals(statut); }
        public boolean isEnCours() { return CvAnalyse.EN_COURS.equals(statut) || CvAnalyse.EN_ATTENTE.equals(statut); }
        public boolean isEnErreur() { return CvAnalyse.ERREUR.equals(statut); }
        public String getDecisionLabel() { return CvAnalyse.decisionLabel(decision); }
    }

    @PostConstruct
    public void init() {
        exiger();
        String p = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap().get("poste");
        if (p != null && p.matches("\\d{1,9}")) { Poste x = service.poste(Integer.parseInt(p)); if (x != null) { ouvrir(x); return; } }
        charger();
    }

    private boolean isRoot() { return loginBean.getUsers().getProfil() != null && loginBean.getUsers().getProfil().getId() == 1; }
    private void exiger() { if (!isRoot()) authorization.requirePrivilege("RECRUT"); }

    public void charger() {
        postes = service.postes(voirArchives);
        compteurs = service.compteurs();
    }
    public void basculerArchives() { voirArchives = !voirArchives; pagePostes = 0; charger(); }

    // ---- liste des postes : grille ou liste, recherche, pagination
    private String affichagePostes = "grille";
    private String filtrePostes;
    private int pagePostes;

    public int getTaillePagePostes() { return "grille".equals(affichagePostes) ? 9 : 10; }
    public List<Poste> getPostesFiltres() {
        if (filtrePostes == null || filtrePostes.isBlank()) return postes;
        String f = sansAccents(filtrePostes.trim());
        return postes.stream().filter(p -> sansAccents(String.join(" ", Objects.toString(p.getIntitule(), ""), Objects.toString(p.getReference(), ""),
                p.getDirection() == null ? "" : Objects.toString(p.getDirection().getNom(), ""), Objects.toString(p.getLieuAffectation(), ""))).contains(f)).toList();
    }
    public int getNbPagesPostes() { return Math.max(1, (getPostesFiltres().size() + getTaillePagePostes() - 1) / getTaillePagePostes()); }
    public List<Poste> getPostesPage() {
        List<Poste> l = getPostesFiltres();
        pagePostes = Math.max(0, Math.min(pagePostes, getNbPagesPostes() - 1));
        int de = pagePostes * getTaillePagePostes();
        return l.subList(Math.min(de, l.size()), Math.min(de + getTaillePagePostes(), l.size()));
    }
    public int getFinPagePostes() { return Math.min((pagePostes + 1) * getTaillePagePostes(), getPostesFiltres().size()); }
    public List<Integer> getPagesPostes() { List<Integer> l = new ArrayList<>(); for (int i = 0; i < getNbPagesPostes(); i++) l.add(i); return l; }
    public void allerPagePostes(int p) { pagePostes = Math.max(0, Math.min(p, getNbPagesPostes() - 1)); }
    public void premierePage() { pagePostes = 0; }
    public void filtrerPostes() { pagePostes = 0; }
    private static String sansAccents(String v) {
        return java.text.Normalizer.normalize(v, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    public long nb(Poste p, int i) { long[] v = compteurs.get(p.getId()); return v == null ? (i == 3 ? -1 : 0) : v[i]; }

    // ------------------------------------------------------------------ édition du poste

    public void nouveau() {
        exiger();
        directions = directionDAO.findAllActif();
        edit = new Poste();
        directionId = null;
        ajouterCritere(PosteCritere.DIPLOME);
        ajouterCritere(PosteCritere.EXPERIENCE);
        mode = "edition";
    }

    public void modifier() {
        exiger();
        directions = directionDAO.findAllActif();
        edit = service.poste(courant.getId());
        directionId = edit.getDirection() == null ? null : edit.getDirection().getId();
        mode = "edition";
    }

    public void ajouterCritere(String type) {
        PosteCritere c = new PosteCritere();
        c.setType(type); c.setPoste(edit); c.setOrdre(edit.getCriteres().size());
        if (PosteCritere.DIPLOME.equals(type)) c.setValeurMin(4);
        if (PosteCritere.EXPERIENCE.equals(type)) c.setValeurMin(3);
        if (PosteCritere.AGE.equals(type)) c.setValeurMax(35);
        if (PosteCritere.EXPERIENCE.equals(type) || PosteCritere.DIPLOME.equals(type)) c.setPonderation(2);
        edit.getCriteres().add(c);
    }
    public void retirerCritere(int index) { if (index >= 0 && index < edit.getCriteres().size()) edit.getCriteres().remove(index); }
    public void monterCritere(int index) { if (index > 0 && index < edit.getCriteres().size()) Collections.swap(edit.getCriteres(), index, index - 1); }
    public List<String> getTypesCritere() { return PosteCritere.TYPES; }
    public String typeLabel(String t) { return PosteCritere.typeLabel(t); }
    public String[] getNiveaux() { return PosteCritere.NIVEAUX; }
    public String niveauLabel(Integer n) { return PosteCritere.niveauLabel(n); }

    // ------------------------------------------------------------------ import d'une fiche de poste (modèle BGFIBank)

    private static final Map<String, String> TYPES_FICHE = Map.of("pdf", "application/pdf", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "png", "image/png", "webp", "image/webp", "docx", "docx", "txt", "text/plain");

    /** Fait lire la fiche de poste par l'IA : les rubriques sont remplies et, pour un nouveau poste, les critères proposés. */
    public void importerFiche(FileUploadEvent e) {
        // PrimeFaces diffuse l'envoi de fichier avant la mise à jour du modèle : les champs du formulaire (vides)
        // écraseraient alors l'en-tête rempli par l'IA. On rejoue donc l'événement en phase « Invoke Application ».
        if (e.getPhaseId() != jakarta.faces.event.PhaseId.INVOKE_APPLICATION) {
            e.setPhaseId(jakarta.faces.event.PhaseId.INVOKE_APPLICATION);
            e.queue();
            return;
        }
        exiger();
        if (edit == null || !iaPrete()) return;
        try {
            String nom = e.getFile().getFileName();
            String ext = nom.contains(".") ? nom.substring(nom.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            String mime = TYPES_FICHE.get(ext);
            if (mime == null) { error("Format non pris en charge", "Fiche de poste en PDF (même scanné), Word (.docx), image ou texte."); return; }
            byte[] contenu = e.getFile().getContent();
            String texte = "docx".equals(ext) || "txt".equals(ext) ? AnalyseMoteur.texte(contenu, ext) : null;
            ClaudeCvService.FichePoste f = claude.lireFiche(appDAO.findFirst(), contenu, mime, nom, texte);
            appliquer(f);
            auditService.updated("Recrutement", "Poste", edit.getId(), "Import d'une fiche de poste par l'IA", nom);
        } catch (Exception ex) { error("Lecture de la fiche impossible", cause(ex)); }
    }

    private void appliquer(ClaudeCvService.FichePoste f) {
        if (vide(edit.getIntitule())) edit.setIntitule(coupe(f.intitule(), 200));
        if (vide(edit.getReference())) edit.setReference(coupe(f.reference(), 50));
        if (f.nombrePostes() > 0) edit.setNombrePostes(f.nombrePostes());
        if (!vide(f.lieuAffectation())) edit.setLieuAffectation(coupe(f.lieuAffectation(), 200));
        if (!vide(f.rattachement())) edit.setRattachement(coupe(f.rattachement(), 200));
        if (!vide(f.dateLimite())) try { edit.setDateLimite(java.sql.Date.valueOf(java.time.LocalDate.parse(f.dateLimite().trim()))); } catch (Exception ignored) { }

        // Les critères ne sont remplacés que sur un nouveau poste : sur un poste existant, ils portent déjà des analyses.
        boolean remplacer = edit.getId() == 0 && f.criteres() != null && !f.criteres().isEmpty();
        if (remplacer) {
            edit.getCriteres().clear();
            for (ClaudeCvService.CritereSuggere s : f.criteres()) {
                String type = s.type() == null ? "" : s.type().trim().toUpperCase(Locale.ROOT);
                if (!PosteCritere.TYPES.contains(type) || PosteCritere.AGE.equals(type)) continue;   // jamais de critère d'âge proposé
                PosteCritere c = new PosteCritere();
                c.setType(type); c.setPoste(edit); c.setOrdre(edit.getCriteres().size());
                c.setLibelle(vide(s.libelle()) ? null : coupe(s.libelle().trim(), 400));
                if (PosteCritere.DIPLOME.equals(type)) c.setValeurMin(s.valeurMin() >= 2 && s.valeurMin() <= 7 ? s.valeurMin() : 4);
                else if (PosteCritere.EXPERIENCE.equals(type)) { if (s.valeurMin() < 0) continue; c.setValeurMin(s.valeurMin()); }
                else if (vide(s.libelle())) continue;
                c.setPonderation(Math.max(1, Math.min(5, s.ponderation())));
                c.setEliminatoire(s.eliminatoire() && !PosteCritere.LOCALISATION.equals(type));
                edit.getCriteres().add(c);
            }
        }
        info("Fiche de poste importée", remplacer
                ? edit.getCriteres().size() + " critère(s) proposé(s) par l'IA : vérifiez l'en-tête, les pondérations et les critères éliminatoires avant d'enregistrer."
                : "En-tête mis à jour. Les critères existants sont conservés.");
    }
    private static boolean vide(String v) { return v == null || v.isBlank(); }
    private static String coupe(String v, int max) { if (v == null) return null; v = v.trim(); return v.length() > max ? v.substring(0, max) : v; }
    public List<String> elements(String v) { return Poste.elements(v); }

    public void enregistrer() {
        exiger();
        try {
            boolean nouveau = edit.getId() == 0;
            boolean avaitAnalyses = !nouveau && !service.analyses(edit.getId()).isEmpty();
            Poste p = service.enregistrer(edit, directionId, loginBean.getUsers());
            if (nouveau) auditService.created("Recrutement", "Poste", p.getId(), "Création d'un poste", p.getIntitule());
            else auditService.updated("Recrutement", "Poste", p.getId(), "Modification d'un poste et de ses critères", p.getIntitule());
            info("Poste enregistré", nouveau ? "Déposez maintenant les CV reçus, puis lancez l'analyse."
                    : avaitAnalyses ? "Les critères ont changé : utilisez « Tout réanalyser » pour mettre à jour les scores." : p.getIntitule());
            edit = null;
            ouvrir(service.poste(p.getId()));
        } catch (Exception ex) { error("Enregistrement impossible", cause(ex)); }
    }

    public void annulerEdition() {
        edit = null;
        if (courant != null) ouvrir(service.poste(courant.getId())); else { mode = "liste"; charger(); }
    }

    public void changerStatut(String statut) {
        exiger();
        service.changerStatut(courant.getId(), statut);
        if (Poste.OUVERT.equals(statut) && courant.isEchu()) {
            service.retirerDateLimite(courant.getId());
            info("Date limite retirée", "Elle était dépassée : fixez-en une nouvelle dans « Fiche et critères » si besoin.");
        }
        auditService.updated("Recrutement", "Poste", courant.getId(), "Changement de statut du poste", courant.getIntitule() + " : " + statut);
        courant = service.poste(courant.getId());
        info("Poste « " + courant.getStatutLabel().toLowerCase() + " »", courant.getIntitule());
    }

    public void supprimerPoste() {
        exiger();
        int id = courant.getId(); String nom = courant.getIntitule();
        service.supprimerPoste(id);
        auditService.deleted("Recrutement", "Poste", id, "Suppression d'un poste et de ses analyses", nom);
        info("Poste supprimé", nom + ". Les CV restent dans la CVthèque.");
        retourListe();
    }

    public void retourListe() { courant = null; selection = null; decisionInitiale = null; mode = "liste"; charger(); }

    // ------------------------------------------------------------------ poste et classement

    public void ouvrir(Poste p) {
        exiger();
        courant = service.poste(p.getId());
        mode = "poste";
        filtre = "tous";
        rafraichir();
        if (isTraitementActif()) demarrerSuivi();
    }

    /** Recharge le classement (appelé aussi par le rafraîchissement automatique pendant l'analyse). */
    public void rafraichir() {
        if (courant == null) return;
        List<Ligne> l = new ArrayList<>();
        for (CvAnalyse a : service.analyses(courant.getId())) {
            Ligne x = new Ligne();
            x.setId(a.getId()); x.setCv(a.getCv()); x.setStatut(a.getStatut()); x.setScore(a.getScore()); x.setElimine(a.isElimine());
            x.setSynthese(a.getSynthese()); x.setErreur(a.getErreur()); x.setDecision(a.getDecision()); x.setCommentaire(a.getCommentaire());
            for (Detail d : RecrutementService.details(a.getDetailJson())) x.getDetails().put(d.critereId(), d);
            l.add(x);
        }
        // classement : score décroissant, les candidats écartés par un critère éliminatoire ensuite, puis les analyses à venir
        l.sort(Comparator.comparingInt((Ligne x) -> x.isTerminee() ? (x.isElimine() ? 1 : 0) : 2)
                .thenComparing(x -> x.getScore() == null ? -1 : x.getScore(), Comparator.reverseOrder())
                .thenComparing(x -> x.getCv().getNomAffiche(), String.CASE_INSENSITIVE_ORDER));
        int r = 0;
        for (Ligne x : l) x.setRang(x.isTerminee() ? ++r : 0);
        lignes = l;
        if (selection != null) selection = l.stream().filter(x -> x.getId() == selection.getId()).findFirst().orElse(null);
    }

    public List<Ligne> getLignesFiltrees() {
        return switch (filtre) {
            case "bons" -> lignes.stream().filter(x -> "bon".equals(classe(x))).toList();
            case "moyens" -> lignes.stream().filter(x -> "moyen".equals(classe(x))).toList();
            case "faibles" -> lignes.stream().filter(x -> "faible".equals(classe(x))).toList();
            case "ecartes" -> lignes.stream().filter(x -> "elimine".equals(classe(x))).toList();
            case "retenus" -> lignes.stream().filter(x -> CvAnalyse.PRESELECTIONNE.equals(x.getDecision()) || CvAnalyse.ENTRETIEN.equals(x.getDecision())).toList();
            case "erreurs" -> getLignesErreur();
            default -> lignes;
        };
    }

    /** bon (vert), moyen (orange), faible (rouge), elimine (gris), attente, erreur. */
    public String classe(Ligne x) {
        if (x.isEnErreur()) return "erreur";
        if (!x.isTerminee() || x.getScore() == null) return "attente";
        if (x.isElimine()) return "elimine";
        if (courant == null) return "attente";
        return x.getScore() >= courant.getSeuilBon() ? "bon" : x.getScore() >= courant.getSeuilMoyen() ? "moyen" : "faible";
    }
    public long compte(String c) { return lignes.stream().filter(x -> c.equals(classe(x))).count(); }
    public long getNbAnalysees() { return lignes.stream().filter(Ligne::isTerminee).count(); }
    public long getNbEnCours() { return lignes.stream().filter(Ligne::isEnCours).count(); }
    public long getNbErreurs() { return lignes.stream().filter(Ligne::isEnErreur).count(); }
    public int getAvancement() { return lignes.isEmpty() ? 0 : (int) Math.round(100.0 * (getNbAnalysees() + getNbErreurs()) / lignes.size()); }
    /**
     * Une analyse tourne si le moteur l'indique ou si des CV sont « en cours » en base (au démarrage du serveur,
     * les « en cours » orphelins sont remis en attente : un « en cours » signifie donc un traitement réel).
     */
    public boolean isTraitementActif() { return courant != null && (AnalyseMoteur.actif(courant.getId()) || getNbTraitement() > 0); }

    /** Rafraîchissement automatique pendant l'analyse ; s'arrête après quelques passages sans traitement actif. */
    private boolean suivi;
    private int passagesInactifs;

    // ---- avancement détaillé de l'analyse
    public long getNbTraitement() { return lignes.stream().filter(x -> CvAnalyse.EN_COURS.equals(x.getStatut())).count(); }
    public long getNbAttente() { return lignes.stream().filter(x -> CvAnalyse.EN_ATTENTE.equals(x.getStatut())).count(); }
    public long getNbFaits() { return getNbAnalysees() + getNbErreurs(); }
    /** CV en cours de lecture ou d'appréciation par l'IA, pour l'afficher. */
    public List<String> getNomsEnTraitement() {
        return lignes.stream().filter(x -> CvAnalyse.EN_COURS.equals(x.getStatut()))
                .map(x -> x.getCv().getNomAffiche()).limit(8).toList();
    }
    public String getTempsEcoule() {
        long d = courant == null ? 0 : AnalyseMoteur.debut(courant.getId());
        return d == 0 ? "" : duree((System.currentTimeMillis() - d) / 1000);
    }
    /** Fin estimée d'après le rythme observé depuis le lancement (vide tant qu'aucun CV n'est terminé). */
    public String getTempsRestant() {
        if (courant == null) return "";
        long d = AnalyseMoteur.debut(courant.getId()), n = AnalyseMoteur.traites(courant.getId());
        long reste = getNbAttente() + getNbTraitement();
        if (d == 0 || n == 0 || reste == 0) return "";
        double parCv = (System.currentTimeMillis() - d) / 1000.0 / n;
        return duree(Math.round(parCv * reste));
    }
    private static String duree(long s) {
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min" + (s % 60 >= 10 ? " " + (s % 60) + " s" : "");
        return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }
    public int pct(long n) { return lignes.isEmpty() ? 0 : (int) Math.round(100.0 * n / lignes.size()); }
    /**
     * Appelé toutes les 3 s par le navigateur pendant l'analyse. Compteurs et avancement sont toujours mis à jour ;
     * le classement seulement si aucun menu ou fenêtre n'est ouvert côté navigateur (paramètre « overlay »).
     * Quand l'analyse est finie, le paramètre de retour « fini » arrête le suivi côté navigateur.
     */
    public void suivre() {
        rafraichir();
        PrimeFaces pf = PrimeFaces.current();
        String overlay = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap().get("overlay");
        if (!"true".equals(overlay)) pf.ajax().update("frmRec:classementCorps", "frmRec:pagerHaut");
        if (isTraitementActif()) passagesInactifs = 0; else passagesInactifs++;
        if (passagesInactifs >= 3 || (!isTraitementActif() && getNbEnCours() == 0)) {
            suivi = false;
            pf.ajax().addCallbackParam("fini", true);
            pf.ajax().update("frmRec:classementCorps", "frmRec:pagerHaut", "frmRec:msgRec");
            if (getNbEnCours() == 0 && !lignes.isEmpty())
                if (getNbErreurs() == 0) info("Analyse terminée", getNbAnalysees() + " CV analysé(s) : le classement est à jour.");
                else warn("Analyse terminée avec " + getNbErreurs() + " exception(s)", getNbAnalysees() + " CV analysé(s) et classé(s). "
                        + getNbErreurs() + " CV n'ont pas pu être analysés : la cause et le bouton « Réanalyser » sont dans l'encadré au-dessus du classement.");
        }
    }
    private void demarrerSuivi() {
        suivi = true; passagesInactifs = 0;
        if (FacesContext.getCurrentInstance() != null && PrimeFaces.current().isAjaxRequest())
            PrimeFaces.current().executeScript("if (window.hxSuivi) hxSuivi.start();");
    }

    public Detail detail(Ligne x, PosteCritere c) { return x.getDetails().get(c.getId()); }
    public String icone(Detail d) { return d == null ? "pi-minus" : "OUI".equals(d.resultat()) ? "pi-check" : "PARTIEL".equals(d.resultat()) ? "pi-circle" : "pi-times"; }
    public String classeAvis(Detail d) { return d == null ? "is-none" : "OUI".equals(d.resultat()) ? "is-ok" : "PARTIEL".equals(d.resultat()) ? "is-mid" : "is-ko"; }
    public String resultatLabel(Detail d) { return d == null ? "Non évalué" : "OUI".equals(d.resultat()) ? "Satisfait" : "PARTIEL".equals(d.resultat()) ? "Partiellement" : "Non satisfait"; }

    // ------------------------------------------------------------------ dépôt des CV

    public void recevoir(FileUploadEvent e) {
        exiger();
        try {
            CvDossier d = cvtheque.dossierPoste(courant.getIntitule(), loginBean.getUsers().getId());
            Cv cv = cvtheque.importer(e.getFile().getContent(), e.getFile().getFileName(), d.getId(), loginBean.getUsers().getId());
            int n = service.ajouterCvs(courant.getId(), List.of(cv.getId()));
            if (n == 0) info("Déjà présent", "« " + e.getFile().getFileName() + " » fait déjà partie des candidatures de ce poste.");
            rafraichir();
        } catch (Exception ex) { error("Dépôt impossible", cause(ex)); }
    }

    public void ouvrirCvtheque() {
        dossiers = cvtheque.dossiers();
        rechercheCvtheque = null; dossierCvtheque = null; choixCvtheque = new ArrayList<>();
        chercherCvtheque();
    }
    /** Coche tous les CV affichés (selon la recherche et le dossier), ou les décoche s'ils le sont déjà tous. */
    public void toutChoisirCvtheque() {
        List<Integer> visibles = resultatsCvtheque.stream().map(Cv::getId).toList();
        List<Integer> l = new ArrayList<>(choixCvtheque == null ? List.of() : choixCvtheque);
        if (isToutChoisiCvtheque()) l.removeAll(visibles);
        else for (Integer id : visibles) if (!l.contains(id)) l.add(id);
        choixCvtheque = l;
    }
    public boolean isToutChoisiCvtheque() {
        return !resultatsCvtheque.isEmpty() && choixCvtheque != null
                && resultatsCvtheque.stream().allMatch(c -> choixCvtheque.contains(c.getId()));
    }
    public int getNbChoixCvtheque() { return choixCvtheque == null ? 0 : choixCvtheque.size(); }

    public void chercherCvtheque() {
        Set<Integer> deja = new HashSet<>();
        for (Ligne l : lignes) deja.add(l.getCv().getId());
        resultatsCvtheque = cvtheque.lister(dossierCvtheque, rechercheCvtheque, false, true).stream().filter(c -> !deja.contains(c.getId())).limit(300).toList();
    }
    public String cheminDossier(CvDossier d) {
        if (d == null) return "Racine";
        StringBuilder sb = new StringBuilder(d.getNom());
        for (CvDossier p = d.getParent(); p != null; p = p.getParent()) sb.insert(0, p.getNom() + " / ");
        return sb.toString();
    }
    public void ajouterDepuisCvtheque() {
        exiger();
        int n = service.ajouterCvs(courant.getId(), choixCvtheque);
        info(n + " CV ajouté(s)", "Lancez l'analyse pour les classer.");
        rafraichir();
        org.primefaces.PrimeFaces.current().ajax().addCallbackParam("ok", true);
    }

    public void retirer(Ligne x) {
        exiger();
        service.retirer(x.getId());
        info("Candidature retirée", x.getCv().getNomAffiche() + " reste dans la CVthèque.");
        rafraichir();
    }

    // ------------------------------------------------------------------ analyse

    private boolean iaPrete() {
        App app = appDAO.findFirst();
        if (app == null || !Boolean.TRUE.equals(app.getIaActive())) { error("IA désactivée", "Activez l'analyse de documents par l'IA dans Paramètres > IA."); return false; }
        if ((app.getIaCleApi() == null || app.getIaCleApi().isBlank()) && (System.getenv("ANTHROPIC_API_KEY") == null || System.getenv("ANTHROPIC_API_KEY").isBlank())) {
            error("Clé d'API manquante", "Saisissez la clé d'API Claude dans Paramètres > IA."); return false;
        }
        return true;
    }

    public void lancer() { lancer(false); }
    public void reanalyserTout() { lancer(true); }

    private void lancer(boolean tout) {
        exiger();
        if (!iaPrete()) return;
        if (AnalyseMoteur.actif(courant.getId())) { info("Analyse déjà en cours", "Le classement se met à jour automatiquement."); return; }
        service.debloquer(courant.getId());
        int n = service.planifier(courant.getId(), tout);
        if (n == 0) { info("Rien à analyser", "Tous les CV de ce poste sont déjà analysés. « Tout réanalyser » recalcule les scores."); return; }
        moteur.lancer(courant.getId());
        demarrerSuivi();
        auditService.log("ANALYSE", "Recrutement", "Poste", courant.getId(), "Analyse des CV par l'IA", courant.getIntitule() + " : " + n + " CV", true);
        info("Analyse lancée", n + " CV en cours d'analyse. Le classement se met à jour au fil de l'eau.");
        rafraichir();
    }

    /** Candidatures dont l'analyse a échoué. */
    public List<Ligne> getLignesErreur() { return lignes.stream().filter(Ligne::isEnErreur).toList(); }

    /** Réanalyse uniquement les CV en erreur (en relisant le CV quand c'est sa lecture qui a échoué). */
    public void reanalyserErreurs() {
        exiger();
        if (!iaPrete()) return;
        List<Ligne> l = getLignesErreur();
        if (l.isEmpty()) { info("Aucune exception", "Tous les CV de ce poste ont été analysés."); return; }
        if (AnalyseMoteur.actif(courant.getId())) { info("Analyse déjà en cours", "Relancez les exceptions une fois l'analyse en cours terminée."); return; }
        for (Ligne x : l) {
            service.planifierUne(x.getId());
            if (x.getCv().isEnErreur()) service.relire(x.getCv().getId());
        }
        moteur.lancer(courant.getId());
        demarrerSuivi();
        auditService.log("ANALYSE", "Recrutement", "Poste", courant.getId(), "Réanalyse des CV en erreur", courant.getIntitule() + " : " + l.size() + " CV", true);
        info("Réanalyse lancée", l.size() + " CV en erreur relancé(s). Les autres résultats ne sont pas touchés.");
        rafraichir();
    }

    /** Titre court de l'exception, compréhensible par un recruteur. */
    public String causeErreur(Ligne x) {
        String e = erreurBrute(x);
        if (e.contains("tronquée")) return "CV trop long ou trop dense pour l'IA";
        if (e.contains("refusé")) return "L'IA a refusé de traiter ce document";
        if (e.contains("texte lisible") || e.contains("vide")) return "Aucun texte lisible dans le fichier";
        if (e.contains("Clé d'API") || e.contains("clé d'API") || e.contains("accès à ce modèle") || e.contains("Modèle Claude inconnu")) return "Configuration de l'IA à vérifier";
        if (e.contains("Limite de débit")) return "Trop de demandes envoyées à l'IA en même temps";
        if (e.contains("surchargée")) return "Service d'IA momentanément surchargé";
        if (e.contains("trop volumineux")) return "Fichier trop volumineux";
        if (e.toLowerCase(Locale.ROOT).contains("timeout") || e.contains("timed out")) return "L'IA a mis trop de temps à répondre";
        return "Analyse interrompue par une erreur";
    }

    /** Ce que le recruteur peut faire pour cette exception. */
    public String conseilErreur(Ligne x) {
        String e = erreurBrute(x);
        if (e.contains("tronquée")) return "Le CV a été relu avec une réponse plus longue sans succès. Réanalysez-le ; si l'échec persiste, ouvrez-le et appréciez-le vous-même, ou déposez une version plus courte (sans annexes ni diplômes scannés).";
        if (e.contains("refusé")) return "Vérifiez que le fichier est bien un CV, puis réanalysez-le.";
        if (e.contains("texte lisible") || e.contains("vide")) return "Le fichier semble vide ou protégé. Déposez-le à nouveau en PDF ou en photo lisible.";
        if (e.contains("Clé d'API") || e.contains("clé d'API") || e.contains("accès à ce modèle") || e.contains("Modèle Claude inconnu")) return "Corrigez la clé ou le modèle dans Paramètres > IA, puis réanalysez.";
        if (e.contains("Limite de débit") || e.contains("surchargée")) return "Ce n'est pas lié au CV : réanalysez dans quelques minutes.";
        if (e.contains("trop volumineux")) return "Réduisez la taille du fichier (moins de 30 Mo, sans pages inutiles) et déposez-le à nouveau.";
        return "Réanalysez ce CV ; si l'échec persiste, ouvrez-le pour l'apprécier vous-même.";
    }

    /** Étape qui a échoué : lecture du CV ou appréciation des critères. */
    public String etapeErreur(Ligne x) { return x.getCv().isEnErreur() ? "Lecture du CV" : "Appréciation des critères"; }

    private static String erreurBrute(Ligne x) {
        String e = x.getErreur();
        if ((e == null || e.isBlank()) && x.getCv().getErreurLecture() != null) e = x.getCv().getErreurLecture();
        return e == null ? "" : e;
    }

    public void reanalyser(Ligne x) {
        exiger();
        if (!iaPrete()) return;
        service.planifierUne(x.getId());
        if (x.isEnErreur() && x.getCv().isEnErreur()) service.relire(x.getCv().getId());
        moteur.lancer(courant.getId());
        demarrerSuivi();
        rafraichir();
    }

    /** Relit le CV (profil extrait à nouveau) puis le réanalyse. */
    public void relireCv(Ligne x) {
        exiger();
        if (!iaPrete()) return;
        service.relire(x.getCv().getId());
        service.planifierUne(x.getId());
        moteur.lancer(courant.getId());
        demarrerSuivi();
        info("Relecture lancée", x.getCv().getNomFichier());
        rafraichir();
    }

    // ------------------------------------------------------------------ décisions

    public void decider(Ligne x) {
        exiger();
        service.decider(x.getId(), x.getDecision(), x.getCommentaire());
        auditService.updated("Recrutement", "CvAnalyse", x.getId(), "Décision sur une candidature", courant.getIntitule() + " : " + x.getCv().getNomAffiche() + " → " + x.getDecisionLabel());
    }
    public void enregistrerSelection() {
        if (selection == null) return;
        decider(selection);
        decisionInitiale = selection.getDecision();
        info("Décision enregistrée", selection.getCv().getNomAffiche() + " : " + selection.getDecisionLabel());
        rafraichir();
    }
    public List<String> getDecisions() { return List.of(CvAnalyse.A_TRAITER, CvAnalyse.PRESELECTIONNE, CvAnalyse.ENTRETIEN, CvAnalyse.ECARTE); }
    public String decisionLabel(String d) { return CvAnalyse.decisionLabel(d); }

    /** Décision portée par la ligne à l'ouverture du détail, rétablie si on ferme sans enregistrer. */
    private String decisionInitiale;

    /** Ouvre le détail ; une candidature « à traiter » reçoit d'emblée l'action suggérée (modifiable, enregistrée seulement sur demande). */
    public void choisir(Ligne x) {
        selection = x;
        decisionInitiale = x.getDecision();
        String s = suggestion(x);
        if (s != null && CvAnalyse.A_TRAITER.equals(x.getDecision())) x.setDecision(s);
    }
    public void annulerSelection() {
        if (selection != null && decisionInitiale != null) selection.setDecision(decisionInitiale);
        decisionInitiale = null;
    }

    /** Action probable d'après le résultat de l'analyse : bon → présélectionné, faible ou critère éliminatoire → écarté, moyen → à examiner. */
    public String suggestion(Ligne x) {
        return switch (classe(x)) {
            case "bon" -> CvAnalyse.PRESELECTIONNE;
            case "faible", "elimine" -> CvAnalyse.ECARTE;
            case "moyen" -> CvAnalyse.A_TRAITER;
            default -> null;
        };
    }
    public String suggestionLabel(Ligne x) {
        String s = suggestion(x);
        return s == null ? "" : CvAnalyse.A_TRAITER.equals(s) ? "À examiner" : CvAnalyse.decisionLabel(s);
    }
    public String suggestionMotif(Ligne x) {
        return switch (classe(x)) {
            case "bon" -> "score ≥ " + courant.getSeuilBon() + " %";
            case "moyen" -> "score entre " + courant.getSeuilMoyen() + " et " + courant.getSeuilBon() + " % : profil à examiner";
            case "faible" -> "score < " + courant.getSeuilMoyen() + " %";
            case "elimine" -> "critère éliminatoire non rempli";
            default -> "";
        };
    }
    public void apercu(Ligne x) { apercuId = x.getCv().getId(); selection = x; }

    public String getVue() { return vue; }
    public void setVue(String vue) { this.vue = "graphique".equals(vue) ? "graphique" : "tableau"; }

    /** Emploi occupé : en cours (fin « present ») ou, à défaut, le plus récent. */
    public record EmploiActuel(String poste, String employeur, String debut, boolean enCours) {
        /** EL 5 (WildFly 30) ne lit pas les composants de record : accesseur de style bean. */
        public boolean isEnCours() { return enCours; }
        public String getLibelle() {
            String p = poste == null || poste.isBlank() ? "Poste non précisé" : poste.trim();
            return employeur == null || employeur.isBlank() ? p : p + " · " + employeur.trim();
        }
        public String getDepuis() { return debut == null || debut.isBlank() ? "" : (enCours ? "depuis " : "début ") + debut.trim(); }
    }
    private final Map<String, Optional<EmploiActuel>> emplois = new HashMap<>();

    public EmploiActuel emploiActuel(Cv cv) {
        if (cv == null || cv.getProfilJson() == null) return null;
        String cle = cv.getId() + ":" + cv.getProfilJson().hashCode();
        return emplois.computeIfAbsent(cle, k -> Optional.ofNullable(calculerEmploi(profil(cv)))).orElse(null);
    }
    private static EmploiActuel calculerEmploi(ClaudeCvService.ProfilCv p) {
        if (p == null || p.experiences() == null || p.experiences().isEmpty()) return null;
        List<ClaudeCvService.Experience> pro = p.experiences().stream().filter(e -> e != null && !e.stage()).toList();
        if (pro.isEmpty()) pro = p.experiences().stream().filter(Objects::nonNull).toList();
        if (pro.isEmpty()) return null;
        ClaudeCvService.Experience e = pro.stream().filter(x -> enCours(x.fin())).findFirst()
                .orElse(pro.stream().max(Comparator.comparing((ClaudeCvService.Experience x) -> x.fin() == null ? "" : x.fin())
                        .thenComparing(x -> x.debut() == null ? "" : x.debut())).orElse(pro.get(0)));
        return new EmploiActuel(e.poste(), e.employeur(), e.debut(), enCours(e.fin()));
    }
    private static boolean enCours(String fin) {
        if (fin == null) return false;
        String f = fin.trim().toLowerCase(Locale.ROOT);
        return f.equals("present") || f.equals("présent") || f.contains("cours") || f.contains("aujourd") || f.equals("now") || f.equals("current");
    }

    /** Candidats analysés pour le graphique (même filtre que le tableau). */
    public List<Ligne> getLignesGraphique() { return getLignesRecherche().stream().filter(x -> x.isTerminee() && x.getScore() != null).toList(); }

    // ---- classement : recherche sur tous les candidats (toutes pages), pagination, nombre par page (0 = tout)
    private String rechercheCand;
    private int pageCand;
    private int tailleCand = 25;

    /** Candidats du filtre courant correspondant à la recherche, toutes pages confondues. */
    public List<Ligne> getLignesRecherche() {
        List<Ligne> l = getLignesFiltrees();
        if (rechercheCand == null || rechercheCand.isBlank()) return l;
        String[] mots = sansAccents(rechercheCand.trim()).split("\\s+");
        return l.stream().filter(x -> {
            String t = sansAccents(texteRecherche(x));
            for (String m : mots) if (!t.contains(m)) return false;
            return true;
        }).toList();
    }
    private String texteRecherche(Ligne x) {
        Cv c = x.getCv();
        StringBuilder sb = new StringBuilder();
        for (String v : new String[]{c.getNomAffiche(), c.getCandidatNom(), c.getTitreProfil(), c.getNomFichier(), c.getEmail(), c.getTelephone(),
                c.getLocalisation(), c.getDiplome(), x.getSynthese(), x.getDecisionLabel(), x.getCommentaire()})
            if (v != null) sb.append(v).append(' ');
        EmploiActuel e = emploiActuel(c);
        if (e != null) sb.append(e.getLibelle()).append(' ');
        ClaudeCvService.ProfilCv p = profil(c);
        if (p != null && p.competences() != null) sb.append(String.join(" ", p.competences()));
        return sb.toString();
    }
    public int getNbPagesCand() { int n = getLignesRecherche().size(); return tailleCand <= 0 ? 1 : Math.max(1, (n + tailleCand - 1) / tailleCand); }
    public List<Ligne> getLignesPage() {
        List<Ligne> l = getLignesRecherche();
        if (tailleCand <= 0) return l;
        pageCand = Math.max(0, Math.min(pageCand, getNbPagesCand() - 1));
        int de = pageCand * tailleCand;
        return l.subList(Math.min(de, l.size()), Math.min(de + tailleCand, l.size()));
    }
    public int getDebutPageCand() { return getLignesRecherche().isEmpty() ? 0 : (tailleCand <= 0 ? 1 : pageCand * tailleCand + 1); }
    public int getFinPageCand() { int n = getLignesRecherche().size(); return tailleCand <= 0 ? n : Math.min((pageCand + 1) * tailleCand, n); }
    /** Numéros de page à afficher : première, dernière, et deux de part et d'autre de la page courante ; -1 = « … ». */
    public List<Integer> getPagesCand() {
        int n = getNbPagesCand(); List<Integer> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i == 0 || i == n - 1 || Math.abs(i - pageCand) <= 2) l.add(i);
            else if (!l.isEmpty() && l.get(l.size() - 1) != -1) l.add(-1);
        }
        return l;
    }
    public void allerPageCand(int p) { pageCand = Math.max(0, Math.min(p, getNbPagesCand() - 1)); }
    public void premierePageCand() { pageCand = 0; }

    /** Profil extrait (pour le détail). */
    public ClaudeCvService.ProfilCv profil(Cv cv) {
        if (cv == null || cv.getProfilJson() == null) return null;
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(cv.getProfilJson(), ClaudeCvService.ProfilCv.class); } catch (Exception e) { return null; }
    }
    public String experienceLabel(Cv cv) {
        if (cv.getExperienceAnnees() == null) return "—";
        double d = cv.getExperienceAnnees();
        return (d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d).replace('.', ',')) + " an" + (d >= 2 ? "s" : "");
    }

    // ------------------------------------------------------------------ export

    public StreamedContent getExportExcel() {
        exiger();
        byte[] data = exportService.excel(courant, lignes, this);
        auditService.log("EXPORT", "Recrutement", "Poste", courant.getId(), "Export Excel du classement", courant.getIntitule(), true);
        String n = Normalizer.normalize(courant.getIntitule(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(Locale.ROOT);
        return DefaultStreamedContent.builder().name("preselection-" + n + ".xlsx").contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").stream(() -> new ByteArrayInputStream(data)).build();
    }

    // ------------------------------------------------------------------ utilitaires

    static String cause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void warn(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_WARN, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
}
