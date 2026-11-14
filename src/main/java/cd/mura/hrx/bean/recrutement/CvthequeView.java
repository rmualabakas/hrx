package cd.mura.hrx.bean.recrutement;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.entity.config.App;
import cd.mura.hrx.entity.recrutement.Cv;
import cd.mura.hrx.entity.recrutement.CvAnalyse;
import cd.mura.hrx.entity.recrutement.CvDossier;
import cd.mura.hrx.entity.recrutement.Poste;
import cd.mura.hrx.entity.recrutement.PosteCritere;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.recrutement.*;
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

import java.io.Serializable;
import java.util.*;

/** CVthèque : explorateur de dossiers et de CV, réutilisable pour tous les postes. */
@Named
@ViewScoped
@Getter
@Setter
public class CvthequeView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private CvthequeService cvtheque;
    @EJB private RecrutementService recrutement;
    @EJB private AnalyseMoteur moteur;
    @EJB private AppDAO appDAO;

    /** Dossier affiché (null : racine). */
    private Integer dossierId;
    private List<CvDossier> dossiers = new ArrayList<>();
    private List<Noeud> arbre = new ArrayList<>();
    private Map<Integer, Long> compteParDossier = new HashMap<>();
    private List<Cv> cvs = new ArrayList<>();
    private String recherche;
    private boolean archives;
    private String affichage = "grille";
    private Map<Integer, Boolean> coches = new HashMap<>();

    // dialogues
    private String nomDossier;
    /** Dossier visé par « Renommer » et dossier parent d'une création (menu contextuel ou dossier affiché). */
    private Integer dossierEdite;
    private Integer parentCreation;
    /** Élément visé par le menu contextuel : « cv:12 », « dossier:5 », « racine: » ou « fond: ». */
    private String ctxCible;
    private Integer dossierCible;
    private Integer posteCible;
    private List<Poste> postes = new ArrayList<>();
    private Cv detail;
    private List<CvAnalyse> analysesDetail = new ArrayList<>();
    private String nomFichierEdit;
    private String etiquettesEdit;
    private String notesEdit;

    /** Dossier dans l'arborescence, avec sa profondeur. */
    public record Noeud(CvDossier dossier, int profondeur, long nb, boolean ouvert) implements Serializable { }

    @PostConstruct
    public void init() {
        exiger();
        charger();
    }

    private boolean isRoot() { return loginBean.getUsers().getProfil() != null && loginBean.getUsers().getProfil().getId() == 1; }
    private void exiger() { if (!isRoot()) authorization.requirePrivilege("CVTHEQUE"); }

    public void charger() {
        dossiers = cvtheque.dossiers();
        compteParDossier = cvtheque.compteParDossier();
        if (dossierId != null && dossiers.stream().noneMatch(d -> d.getId() == dossierId)) dossierId = null;
        construireArbre();
        cvs = cvtheque.lister(dossierId, recherche, archives);
        coches.keySet().retainAll(cvs.stream().map(Cv::getId).toList());
    }

    private void construireArbre() {
        Map<Integer, List<CvDossier>> enfants = new HashMap<>();
        for (CvDossier d : dossiers) enfants.computeIfAbsent(d.getParent() == null ? 0 : d.getParent().getId(), k -> new ArrayList<>()).add(d);
        Set<Integer> chemin = new HashSet<>();
        for (CvDossier d = courant(); d != null; d = d.getParent()) chemin.add(d.getId());
        List<Noeud> l = new ArrayList<>();
        parcourir(0, 0, enfants, chemin, l);
        arbre = l;
    }
    private void parcourir(int parent, int prof, Map<Integer, List<CvDossier>> enfants, Set<Integer> chemin, List<Noeud> l) {
        for (CvDossier d : enfants.getOrDefault(parent, List.of())) {
            boolean ouvert = chemin.contains(d.getId());
            l.add(new Noeud(d, prof, compteParDossier.getOrDefault(d.getId(), 0L), ouvert));
            if (ouvert) parcourir(d.getId(), prof + 1, enfants, chemin, l);
        }
    }

    public CvDossier courant() { return dossierId == null ? null : dossiers.stream().filter(d -> d.getId() == dossierId).findFirst().orElse(null); }

    /** Fil d'Ariane du dossier affiché. */
    public List<CvDossier> getFil() {
        LinkedList<CvDossier> l = new LinkedList<>();
        for (CvDossier d = courant(); d != null; d = d.getParent()) l.addFirst(d);
        return l;
    }
    public List<CvDossier> getSousDossiers() {
        if (recherche != null && !recherche.isBlank()) return List.of();
        return dossiers.stream().filter(d -> dossierId == null ? d.getParent() == null : d.getParent() != null && d.getParent().getId() == dossierId).toList();
    }
    public long nbDans(CvDossier d) { return compteParDossier.getOrDefault(d.getId(), 0L); }
    public long getNbRacine() { return compteParDossier.getOrDefault(null, 0L); }
    public String chemin(CvDossier d) {
        if (d == null) return "Racine";
        StringBuilder sb = new StringBuilder(d.getNom());
        for (CvDossier p = d.getParent(); p != null; p = p.getParent()) sb.insert(0, p.getNom() + " / ");
        return sb.toString();
    }

    public void ouvrir(Integer id) { dossierId = id; recherche = null; coches.clear(); charger(); }
    public void chercher() { coches.clear(); charger(); }
    public void basculerArchives() { archives = !archives; coches.clear(); charger(); }

    // ------------------------------------------------------------------ sélection

    public List<Integer> getSelection() { return coches.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList(); }
    public int getNbSelection() { return getSelection().size(); }
    public void toutCocher() { boolean tous = getNbSelection() < cvs.size(); coches.clear(); if (tous) for (Cv c : cvs) coches.put(c.getId(), true); }

    // ------------------------------------------------------------------ dossiers

    public void preparerDossier(boolean renommer) {
        if (renommer) { dossierEdite = dossierId; nomDossier = courant() != null ? courant().getNom() : null; }
        else { parentCreation = dossierId; nomDossier = null; }
    }
    public CvDossier dossierPar(Integer id) { return id == null ? null : dossiers.stream().filter(d -> d.getId() == id).findFirst().orElse(null); }
    public CvDossier getParentCreationDossier() { return dossierPar(parentCreation); }

    public void creerDossier() {
        exiger();
        try {
            CvDossier d = cvtheque.creerDossier(nomDossier, parentCreation, loginBean.getUsers().getId());
            info("Dossier créé", chemin(dossierPar(parentCreation)) + " / " + d.getNom());
            nomDossier = null;
            ouvrir(d.getId());
            PrimeFaces.current().ajax().addCallbackParam("ok", true);
        } catch (Exception ex) { error("Création impossible", RecrutementView.cause(ex)); }
    }

    public void renommerDossier() {
        exiger();
        try {
            cvtheque.renommerDossier(dossierEdite, nomDossier);
            info("Dossier renommé", nomDossier);
            charger();
            PrimeFaces.current().ajax().addCallbackParam("ok", true);
        } catch (Exception ex) { error("Renommage impossible", RecrutementView.cause(ex)); }
    }

    public void supprimerDossier() { supprimerDossier(dossierId); }

    /** Supprime un dossier vide ; si l'on se trouvait dedans (ou dans un de ses sous-dossiers), remonte à son parent. */
    public void supprimerDossier(Integer id) {
        exiger();
        try {
            CvDossier d = dossierPar(id);
            Integer parent = d == null || d.getParent() == null ? null : d.getParent().getId();
            boolean dedans = false;
            for (CvDossier x = courant(); x != null; x = x.getParent()) if (Objects.equals(x.getId(), id)) { dedans = true; break; }
            cvtheque.supprimerDossier(id);
            info("Dossier supprimé", d == null ? "" : d.getNom());
            if (dedans) ouvrir(parent); else charger();
        } catch (Exception ex) { error("Suppression impossible", RecrutementView.cause(ex)); }
    }

    // ------------------------------------------------------------------ menu contextuel

    private String ctxType() { return ctxCible == null || !ctxCible.contains(":") ? "" : ctxCible.substring(0, ctxCible.indexOf(':')); }
    private Integer ctxId() {
        try { String v = ctxCible.substring(ctxCible.indexOf(':') + 1); return v.isBlank() ? null : Integer.valueOf(v); } catch (Exception e) { return null; }
    }
    /** Clic droit sur un CV non coché : l'action ne porte que sur lui ; sur un CV coché, sur toute la sélection. */
    private void ctxSelection() {
        Integer id = ctxId();
        if (id != null && !Boolean.TRUE.equals(coches.get(id))) { coches.clear(); coches.put(id, true); }
    }
    private void dialogue(String widget) { PrimeFaces.current().ajax().addCallbackParam("dlg", widget); }

    /** Exécute l'entrée choisie dans le menu contextuel (paramètre « act ») sur l'élément visé. */
    public void contexte() {
        String act = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap().getOrDefault("act", "");
        Integer id = ctxId();
        switch (ctxType()) {
            case "dossier", "racine" -> {
                switch (act) {
                    case "ouvrir" -> ouvrir(id);
                    case "nouveau" -> { parentCreation = id; nomDossier = null; dialogue("dlgDos"); }
                    case "renommer" -> { CvDossier d = dossierPar(id); if (d != null) { dossierEdite = id; nomDossier = d.getNom(); dialogue("dlgRen"); } }
                    default -> { }
                }
            }
            case "cv" -> {
                Cv cv = cvs.stream().filter(c -> Objects.equals(c.getId(), id)).findFirst().orElse(null);
                if (cv == null) return;
                switch (act) {
                    case "details" -> { voir(cv); dialogue("sbDet"); }
                    case "lire" -> { ctxSelection(); lireSelection(false); }
                    case "relire" -> { ctxSelection(); lireSelection(true); }
                    case "poste" -> { ctxSelection(); preparerPoste(); dialogue("dlgPst"); }
                    case "deplacer" -> { ctxSelection(); dossierCible = cv.getDossier() == null ? null : cv.getDossier().getId(); dialogue("dlgDep"); }
                    case "archiver" -> { ctxSelection(); archiver(); }
                    default -> { }
                }
            }
            default -> {
                switch (act) {
                    case "nouveau" -> { parentCreation = dossierId; nomDossier = null; dialogue("dlgDos"); }
                    case "tout" -> toutCocher();
                    default -> { }
                }
            }
        }
    }
    public void ctxSupprimerCv() { if ("cv".equals(ctxType())) { ctxSelection(); supprimer(); } }
    public void ctxSupprimerDossier() { if ("dossier".equals(ctxType()) && ctxId() != null) supprimerDossier(ctxId()); }

    // ------------------------------------------------------------------ fichiers

    public void recevoir(FileUploadEvent e) {
        exiger();
        try {
            Cv c = cvtheque.importer(e.getFile().getContent(), e.getFile().getFileName(), dossierId, loginBean.getUsers().getId());
            if (dossierId != null && (c.getDossier() == null || c.getDossier().getId() != dossierId))
                info("Déjà dans la CVthèque", "« " + e.getFile().getFileName() + " » existe déjà dans " + chemin(c.getDossier()) + " : il n'a pas été dupliqué.");
            charger();
        } catch (Exception ex) { error("Import impossible", RecrutementView.cause(ex)); }
    }

    public void deplacer() {
        exiger();
        List<Integer> sel = getSelection();
        cvtheque.deplacer(sel, dossierCible);
        info(sel.size() + " CV déplacé(s)", "vers " + chemin(dossierCible == null ? null : dossiers.stream().filter(d -> d.getId() == dossierCible).findFirst().orElse(null)));
        coches.clear(); charger();
        PrimeFaces.current().ajax().addCallbackParam("ok", true);
    }

    public void archiver() {
        exiger();
        List<Integer> sel = getSelection();
        cvtheque.archiver(sel, !archives);
        info(sel.size() + " CV " + (archives ? "désarchivé(s)" : "archivé(s)"), archives ? "Ils réapparaissent dans leurs dossiers." : "Retrouvez-les avec le bouton « Archives ».");
        coches.clear(); charger();
    }

    public void supprimer() {
        exiger();
        List<Integer> sel = getSelection();
        int n = cvtheque.supprimer(sel);
        auditService.deleted("Recrutement", "Cv", sel.toString(), "Suppression définitive de CV de la CVthèque", n + " CV");
        info(n + " CV supprimé(s) définitivement", "Fichiers et analyses associées effacés.");
        if (detail != null && sel.contains(detail.getId())) detail = null;
        coches.clear(); charger();
    }

    public void preparerPoste() { postes = recrutement.postes(false).stream().filter(Poste::isOuvert).toList(); posteCible = null; }
    public void ajouterAuPoste() {
        exiger();
        if (posteCible == null) { error("Choisissez un poste", ""); return; }
        int n = recrutement.ajouterCvs(posteCible, getSelection());
        info(n + " CV ajouté(s) au poste", "Ouvrez le poste dans « Présélection des CV » pour lancer l'analyse.");
        coches.clear();
        PrimeFaces.current().ajax().addCallbackParam("ok", true);
    }

    /** Fait lire par l'IA les CV cochés qui ne le sont pas encore (ou tous, pour une relecture). */
    public void lireSelection(boolean forcer) {
        exiger();
        App app = appDAO.findFirst();
        if (app == null || !Boolean.TRUE.equals(app.getIaActive())) { error("IA désactivée", "Activez l'analyse de documents par l'IA dans Paramètres > IA."); return; }
        List<Integer> ids = cvs.stream().filter(c -> getSelection().contains(c.getId()) && (forcer || !c.isLu())).map(Cv::getId).toList();
        if (ids.isEmpty()) { info("Rien à lire", "Les CV cochés ont déjà été lus. Utilisez « Relire » pour forcer une nouvelle lecture."); return; }
        moteur.lireCvs(new ArrayList<>(ids));
        info("Lecture lancée", ids.size() + " CV en cours de lecture par l'IA ; actualisez dans quelques instants.");
        coches.clear();
    }

    // ------------------------------------------------------------------ détail

    public void voir(Cv c) {
        detail = cvtheque.cv(c.getId());
        analysesDetail = cvtheque.analysesDe(c.getId());
        nomFichierEdit = detail.getNomFichier();
        etiquettesEdit = detail.getEtiquettes();
        notesEdit = detail.getNotes();
    }
    public void fermerDetail() { detail = null; }

    /** Relance la lecture IA du CV affiché (utile après un échec de lecture). */
    public void relireDetail() {
        exiger();
        if (detail == null) return;
        App app = appDAO.findFirst();
        if (app == null || !Boolean.TRUE.equals(app.getIaActive())) { error("IA désactivée", "Activez l'analyse de documents par l'IA dans Paramètres > IA."); return; }
        moteur.lireCvs(new ArrayList<>(List.of(detail.getId())));
        info("Lecture relancée", "Le CV est en cours de lecture par l'IA ; actualisez dans quelques instants.");
    }

    public void enregistrerDetail() {
        exiger();
        cvtheque.renommer(detail.getId(), nomFichierEdit);
        cvtheque.annoter(detail.getId(), etiquettesEdit, notesEdit);
        info("CV mis à jour", nomFichierEdit);
        voir(detail);
        charger();
    }

    public ClaudeCvService.ProfilCv getProfilDetail() { return profil(detail); }

    public ClaudeCvService.ProfilCv profil(Cv cv) {
        if (cv == null || cv.getProfilJson() == null) return null;
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(cv.getProfilJson(), ClaudeCvService.ProfilCv.class); } catch (Exception e) { return null; }
    }
    public String niveauLabel(Integer n) { return PosteCritere.niveauLabel(n); }
    public String decisionLabel(String d) { return CvAnalyse.decisionLabel(d); }
    public String icone(Cv c) {
        return switch (c.getExtension()) { case "pdf" -> "pi-file-pdf"; case "docx" -> "pi-file-word"; case "txt" -> "pi-file"; default -> "pi-image"; };
    }
    public String experienceLabel(Cv cv) {
        if (cv.getExperienceAnnees() == null) return "—";
        double d = cv.getExperienceAnnees();
        return (d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d).replace('.', ',')) + " an" + (d >= 2 ? "s" : "");
    }

    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
}
