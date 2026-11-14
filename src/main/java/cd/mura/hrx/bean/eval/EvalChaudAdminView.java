package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.dao.config.DirectionDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.EvalChaud;
import cd.mura.hrx.entity.eval.EvalChaudQuestion;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.EvalChaudExportService;
import cd.mura.hrx.service.EvalChaudService;
import cd.mura.hrx.service.NotificationService;
import jakarta.annotation.PostConstruct;
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
import java.io.Serializable;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;

/** Évaluations à chaud des formations : liste, préparation (population et questionnaire), lancement et résultats. */
@Named
@ViewScoped
@Getter
@Setter
public class EvalChaudAdminView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private EvalChaudService service;
    @EJB private NotificationService notifications;
    @EJB private UserDAO userDAO;
    @EJB private DirectionDAO directionDAO;
    @Inject private EvalChaudExportService exportService;
    @Inject private cd.mura.hrx.service.recrutement.ClaudeCvService claude;
    @EJB private cd.mura.hrx.dao.config.AppDAO appDAO;
    @Inject private cd.mura.hrx.service.FicheChaudExportService ficheService;

    /** liste, edition ou resultats. */
    private String mode = "liste";
    private List<EvalChaud> liste = new ArrayList<>();
    private Map<Integer, long[]> compteurs = new HashMap<>();

    // édition
    private EvalChaud edit;
    private List<Integer> participantIds = new ArrayList<>();
    private Integer directionAjout;
    private boolean questionsFigees;
    private List<Users> collaborateurs = new ArrayList<>();
    private Map<Integer, Users> parId = new HashMap<>();
    private List<Direction> directions = new ArrayList<>();

    // résultats
    private EvalChaud courant;
    private List<EvalChaudService.Stat> stats = new ArrayList<>();
    private Double satisfaction;
    private Set<Integer> repondants = new HashSet<>();
    private List<Users> participantsCourant = new ArrayList<>();

    @PostConstruct
    public void init() {
        // Onglet, filtre de statut et affichage repris de l'adresse : un rechargement garde la même vue.
        Map<String, String> p = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap();
        if (p.get("type") != null) setNature(p.get("type"));
        String st = p.get("statut");
        if (st != null && List.of("tous", "ouvertes", "brouillons", "cloturees").contains(st)) statutFiltre = st;
        if (p.get("vue") != null) setAffichage(p.get("vue"));
        charger();
    }

    // affichage de la liste
    /** Compartiment affiché : chaud ou froid. */
    private String nature = "chaud";
    private String affichage = "grille";
    /** À froid : évaluation à chaud reprise (formation et participants). */
    private Integer sourceChoisie;
    private List<EvalChaud> sources = new ArrayList<>();
    // suivi à froid
    private List<cd.mura.hrx.entity.eval.EvalChaudReponse> reponsesFroid = new ArrayList<>();
    private EvalChaudService.SyntheseAvis syntheseAvis;

    public String getNature() { return nature; }
    public void setNature(String n) { nature = "froid".equals(n) ? "froid" : "chaud"; }
    public boolean isFroid() { return "froid".equals(nature); }
    private boolean deNature(EvalChaud c) { return isFroid() == c.isFroid(); }
    public long nbNature(String n) { boolean f = "froid".equals(n); return liste.stream().filter(c -> c.isFroid() == f).count(); }
    public long nbOuvertesNature(String n) { boolean f = "froid".equals(n); return liste.stream().filter(c -> c.isFroid() == f && c.isOuverte()).count(); }
    public Integer getSourceChoisie() { return sourceChoisie; }
    public void setSourceChoisie(Integer v) { sourceChoisie = v; }
    public List<EvalChaud> getSources() { return sources; }
    public List<cd.mura.hrx.entity.eval.EvalChaudReponse> getReponsesFroid() { return reponsesFroid; }
    public EvalChaudService.SyntheseAvis getSyntheseAvis() { return syntheseAvis; }
    public long getNbAvisAttente() { return reponsesFroid.stream().filter(r -> !r.isAvisDonne()).count(); }
    private String filtre;
    private String statutFiltre = "tous";

    public String getAffichage() { return affichage; }
    public void setAffichage(String a) { affichage = "liste".equals(a) ? "liste" : "grille"; }
    public String getFiltre() { return filtre; }
    public void setFiltre(String f) { filtre = f; }
    public String getStatutFiltre() { return statutFiltre; }
    public void setStatutFiltre(String s) { statutFiltre = s == null ? "tous" : s; }

    private static boolean deStatut(EvalChaud c, String s) {
        return switch (s) {
            case "ouvertes" -> c.isOuverte();
            case "brouillons" -> c.isBrouillon();
            case "cloturees" -> c.isCloturee();
            default -> true;
        };
    }
    public long nbStatut(String s) { return liste.stream().filter(this::deNature).filter(c -> deStatut(c, s)).count(); }

    /** Liste filtrée par statut et recherche (intitulé, organisme, formateur, lieu). */
    public List<EvalChaud> getListeFiltree() {
        String f = filtre == null ? "" : sansAccents(filtre.trim());
        return liste.stream().filter(this::deNature).filter(c -> deStatut(c, statutFiltre))
                .filter(c -> f.isEmpty() || sansAccents(String.join(" ", java.util.Objects.toString(c.getIntitule(), ""),
                        java.util.Objects.toString(c.getOrganisme(), ""), java.util.Objects.toString(c.getFormateur(), ""),
                        java.util.Objects.toString(c.getLieu(), ""))).contains(f))
                .toList();
    }
    private static String sansAccents(String v) {
        return java.text.Normalizer.normalize(v, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    }

    public void charger() {
        liste = service.lister();
        compteurs = service.compteurs();
    }

    private boolean isRoot() { return loginBean.getUsers().getProfil() != null && loginBean.getUsers().getProfil().getId() == 1; }
    private void exiger() { if (!isRoot()) authorization.requirePrivilege("FORM_CHAUD"); }

    // lecture
    public long nbParticipants(EvalChaud c) { long[] v = compteurs.get(c.getId()); return v == null ? 0 : v[0]; }
    public long nbReponses(EvalChaud c) { long[] v = compteurs.get(c.getId()); return v == null ? 0 : v[1]; }
    public int tauxReponse(EvalChaud c) { long p = nbParticipants(c); return p == 0 ? 0 : (int) Math.min(100, Math.round(100.0 * nbReponses(c) / p)); }
    // état de progression d'une évaluation : 1 préparation, 2 collecte des réponses, 3 clôturée
    public int etape(EvalChaud c) { return c.isBrouillon() ? 1 : c.isOuverte() ? 2 : 3; }
    public String classeEtape(EvalChaud c, int n) { int e = etape(c); return n < e || (n == 3 && e == 3) ? "is-done" : n == e ? (c.isEchue() ? "is-current is-late" : "is-current") : ""; }
    /** Part écoulée de la période de réponse (0 à 100). */
    public int avancementDelai(EvalChaud c) {
        if (c.isCloturee()) return 100;
        if (c.isBrouillon() || c.getDateDebut() == null || c.getDateFin() == null) return 0;
        long total = java.time.temporal.ChronoUnit.DAYS.between(c.getDateDebut(), c.getDateFin()) + 1;
        long ecoule = java.time.temporal.ChronoUnit.DAYS.between(c.getDateDebut(), LocalDate.now()) + 1;
        return total <= 0 ? 100 : (int) Math.max(0, Math.min(100, Math.round(100.0 * ecoule / total)));
    }
    public String libelleDelai(EvalChaud c) {
        LocalDate auj = LocalDate.now();
        if (c.isBrouillon()) {
            if (nbParticipants(c) == 0) return "À préparer : aucun participant défini";
            return "Prête à lancer · " + nbParticipants(c) + " participant(s)";
        }
        if (c.isCloturee()) return c.getDateCloture() == null ? "Collecte terminée" : "Collecte terminée le " + new java.text.SimpleDateFormat("dd/MM/yyyy").format(c.getDateCloture());
        if (c.getDateFin() == null) return "Collecte en cours";
        long j = java.time.temporal.ChronoUnit.DAYS.between(auj, c.getDateFin());
        if (j < 0) return "Échéance dépassée de " + (-j) + " jour(s) : pensez à clôturer";
        if (c.getDateDebut() != null && auj.isBefore(c.getDateDebut())) return "Ouverture le " + c.getDateDebut().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        return j == 0 ? "Dernier jour de réponse" : "J-" + j + " avant clôture";
    }
    public long nbAttente(EvalChaud c) { return Math.max(0, nbParticipants(c) - nbReponses(c)); }

    public String typeLabel(String t) { return EvalChaudQuestion.typeLabel(t); }
    public List<Users> getParticipantsChoisis() {
        return participantIds.stream().map(parId::get).filter(Objects::nonNull).sorted(Comparator.comparing(Users::getNomComplet)).toList();
    }
    public String libelleCollaborateur(Users u) {
        String d = u.getDirection() == null ? "" : " · " + (u.getDirection().getAbrege() == null || u.getDirection().getAbrege().isBlank() ? u.getDirection().getNom() : u.getDirection().getAbrege());
        return u.getNomComplet() + (u.getMatricule() == null ? "" : " (" + u.getMatricule() + ")") + d;
    }
    public boolean repondu(Users u) { return repondants.contains(u.getId()); }
    public String classeNote(Double m) { return m == null ? "" : m >= 4 ? "hx-att--4" : m >= 3 ? "hx-att--3" : m >= 2 ? "hx-att--2" : "hx-att--1"; }

    // préparation

    private void preparerReferentiels() {
        collaborateurs = userDAO.findActifsAvecDirection();
        parId = new HashMap<>();
        for (Users u : collaborateurs) parId.put(u.getId(), u);
        directions = directionDAO.findAllActif();
    }

    public void nouvelle() {
        exiger();
        preparerReferentiels();
        edit = new EvalChaud();
        if (isFroid()) edit.setNature(EvalChaud.FROID);
        edit.setDateDebut(LocalDate.now());
        edit.setDateFin(LocalDate.now().plusDays(isFroid() ? 14 : 7));
        edit.setConsignes(isFroid() ? EvalChaudService.INTRO_MAF064 : EvalChaudService.INTRO_MAF063);
        sourceChoisie = null;
        sources = isFroid() ? service.sourcesFroid() : new ArrayList<>();
        edit.getQuestions().addAll(EvalChaudService.questionsModele(edit));
        participantIds = new ArrayList<>();
        questionsFigees = false;
        mode = "edition";
    }

    public void modifier(EvalChaud c) {
        exiger();
        preparerReferentiels();
        edit = service.charger(c.getId());
        if (edit == null || edit.isCloturee()) { error("Modification impossible", "Cette évaluation est clôturée."); return; }
        participantIds = new ArrayList<>(edit.getParticipants().stream().map(Users::getId).toList());
        nature = edit.isFroid() ? "froid" : "chaud";
        sourceChoisie = edit.getSourceId();
        sources = edit.isFroid() ? service.sourcesFroid() : new ArrayList<>();
        for (Users u : edit.getParticipants()) parId.putIfAbsent(u.getId(), u);
        questionsFigees = service.nbReponses(edit.getId()) > 0;
        mode = "edition";
    }

    public void ajouterQuestion() {
        EvalChaudQuestion q = new EvalChaudQuestion();
        q.setType(EvalChaudQuestion.NOTE); q.setOrdre(edit.getQuestions().size()); q.setEvalChaud(edit);
        edit.getQuestions().add(q);
    }
    /** Remplace le questionnaire par le modèle officiel MAF 063 ou MAF 064 (tant qu'aucune réponse n'est arrivée). */
    public void rechargerModele() {
        if (questionsFigees || edit == null) return;
        edit.getQuestions().clear();
        edit.getQuestions().addAll(EvalChaudService.questionsModele(edit));
        info("Modèle " + getModele() + " appliqué", edit.getQuestions().size() + " questions du modèle officiel. Enregistrez pour conserver.");
    }
    public String getModele() { return edit != null && edit.isFroid() || edit == null && isFroid() ? "MAF 064" : "MAF 063"; }

    /** À froid : reprend la formation et les participants d'une évaluation à chaud. */
    public void reprendreSource() {
        if (edit == null || sourceChoisie == null) return;
        EvalChaud src = service.charger(sourceChoisie);
        if (src == null) return;
        edit.setSourceId(src.getId());
        edit.setIntitule(src.getIntitule()); edit.setOrganisme(src.getOrganisme()); edit.setFormateur(src.getFormateur()); edit.setLieu(src.getLieu());
        edit.setDateFormationDebut(src.getDateFormationDebut()); edit.setDateFormationFin(src.getDateFormationFin());
        Set<Integer> ids = new LinkedHashSet<>(participantIds);
        for (Users u : src.getParticipants()) { ids.add(u.getId()); parId.putIfAbsent(u.getId(), u); }
        participantIds = new ArrayList<>(ids);
        info("Formation reprise", src.getIntitule() + " · " + src.getParticipants().size() + " participant(s) repris de l'évaluation à chaud.");
    }
    public String libelleSource(EvalChaud c) {
        String d = c.getDateFormationDebut() == null ? "" : " · " + c.getDateFormationDebut().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        return c.getIntitule() + d + (c.getOrganisme() == null || c.getOrganisme().isBlank() ? "" : " · " + c.getOrganisme());
    }
    /** Nombre de mois écoulés depuis la fin de la formation (repère pour une évaluation à froid). */
    public String recul(EvalChaud c) {
        LocalDate f = c.getDateFormationFin() != null ? c.getDateFormationFin() : c.getDateFormationDebut();
        if (f == null) return "";
        long m = java.time.temporal.ChronoUnit.MONTHS.between(f, c.getDateDebut() != null ? c.getDateDebut() : LocalDate.now());
        return m <= 0 ? "moins d'un mois après la formation" : m + " mois après la formation";
    }
    public List<String[]> getHorizons() { return Arrays.asList(EvalChaudQuestion.HORIZONS); }

    public void retirerQuestion(int index) { if (!questionsFigees && index >= 0 && index < edit.getQuestions().size()) edit.getQuestions().remove(index); }
    public void monterQuestion(int index) {
        if (questionsFigees || index <= 0 || index >= edit.getQuestions().size()) return;
        Collections.swap(edit.getQuestions(), index, index - 1);
    }
    public void descendreQuestion(int index) {
        if (questionsFigees || index < 0 || index >= edit.getQuestions().size() - 1) return;
        Collections.swap(edit.getQuestions(), index, index + 1);
    }

    public void ajouterDirection() {
        if (directionAjout == null) return;
        int avant = participantIds.size();
        Set<Integer> s = new LinkedHashSet<>(participantIds);
        for (Users u : collaborateurs) if (u.getDirection() != null && u.getDirection().getId() == directionAjout) s.add(u.getId());
        participantIds = new ArrayList<>(s);
        info("Direction ajoutée", (participantIds.size() - avant) + " collaborateur(s) ajouté(s) à la population.");
        directionAjout = null;
    }
    public void retirerParticipant(Users u) { participantIds.remove(Integer.valueOf(u.getId())); }
    public void viderParticipants() { participantIds = new ArrayList<>(); }

    public void enregistrer() {
        exiger();
        try {
            boolean nouveau = edit.getId() == 0;
            EvalChaud c = service.enregistrer(edit, participantIds, loginBean.getUsers());
            if (nouveau) auditService.created("Formations", "EvalChaud", c.getId(), (c.isFroid() ? "Création d'une évaluation à froid" : "Création d'une évaluation à chaud"), c.getIntitule());
            else auditService.updated("Formations", "EvalChaud", c.getId(), (c.isFroid() ? "Modification d'une évaluation à froid" : "Modification d'une évaluation à chaud"), c.getIntitule() + " : " + participantIds.size() + " participant(s)");
            info("Évaluation enregistrée", c.getIntitule() + " · " + participantIds.size() + " participant(s)" + (c.isBrouillon() ? ". Lancez-la pour prévenir les participants." : "."));
            mode = "liste";
            edit = null;
            charger();
        } catch (Exception ex) { error("Enregistrement impossible", PeriodeEssaiView.cause(ex)); }
    }

    public void annuler() { mode = "liste"; edit = null; charger(); }

    public void lancer(EvalChaud c) {
        exiger();
        try {
            List<Users> dest = service.lancer(c.getId());
            int n = 0;
            EvalChaud lance = service.charger(c.getId());
            for (Users u : dest) {
                if (u.getEmail() == null || u.getEmail().isBlank()) continue;
                try { notifications.evalChaudOuverte(lance, u, false); n++; } catch (Exception ex) { /* un échec d'envoi ne bloque pas */ }
            }
            auditService.updated("Formations", "EvalChaud", c.getId(), "Lancement de l'évaluation à chaud", c.getIntitule() + " : " + n + " e-mail(s)");
            info("Questionnaire ouvert", n + " participant(s) prévenu(s) par e-mail.");
            charger();
        } catch (Exception ex) { error("Lancement impossible", PeriodeEssaiView.cause(ex)); }
    }

    public void relancer(EvalChaud c) {
        exiger();
        try {
            EvalChaud x = service.charger(c.getId());
            if (x == null || !x.isRepondable()) { error("Relance impossible", "Le questionnaire n'est pas ouvert aux réponses."); return; }
            int n = 0;
            for (Users u : service.nonRepondants(c.getId())) {
                if (u.getEmail() == null || u.getEmail().isBlank()) continue;
                try { notifications.evalChaudOuverte(x, u, true); n++; } catch (Exception ex) { }
            }
            auditService.updated("Formations", "EvalChaud", c.getId(), "Relance des non-répondants", c.getIntitule() + " : " + n + " e-mail(s)");
            info("Relance envoyée", n + " participant(s) sans réponse relancé(s).");
        } catch (Exception ex) { error("Relance impossible", PeriodeEssaiView.cause(ex)); }
    }

    public void cloturer(EvalChaud c) {
        exiger();
        try {
            service.cloturer(c.getId());
            auditService.updated("Formations", "EvalChaud", c.getId(), "Clôture de l'évaluation à chaud", c.getIntitule());
            info("Évaluation clôturée", "Plus aucune réponse n'est acceptée.");
            charger();
            if ("resultats".equals(mode)) resultats(c);
        } catch (Exception ex) { error("Clôture impossible", PeriodeEssaiView.cause(ex)); }
    }

    public void rouvrir(EvalChaud c) {
        exiger();
        try {
            service.rouvrir(c.getId());
            auditService.updated("Formations", "EvalChaud", c.getId(), "Réouverture de l'évaluation à chaud", c.getIntitule());
            info("Évaluation rouverte", "Les participants peuvent de nouveau répondre.");
            charger();
        } catch (Exception ex) { error("Réouverture impossible", PeriodeEssaiView.cause(ex)); }
    }

    public void supprimer(EvalChaud c) {
        exiger();
        try {
            service.supprimer(c.getId());
            auditService.deleted("Formations", "EvalChaud", c.getId(), "Suppression de l'évaluation à chaud", c.getIntitule());
            info("Évaluation supprimée", c.getIntitule());
            charger();
        } catch (Exception ex) { error("Suppression impossible", PeriodeEssaiView.cause(ex)); }
    }

    public void resultats(EvalChaud c) {
        exiger();
        courant = service.charger(c.getId());
        if (courant == null) return;
        stats = service.resultats(c.getId());
        satisfaction = EvalChaudService.satisfaction(stats);
        repondants = service.repondants(c.getId());
        participantsCourant = courant.getParticipants().stream().sorted(Comparator.comparing(Users::getNomComplet)).toList();
        reponsesFroid = courant.isFroid() ? service.reponsesFroid(c.getId()) : new ArrayList<>();
        syntheseAvis = courant.isFroid() ? EvalChaudService.syntheseAvis(reponsesFroid) : null;
        mode = "resultats";
    }
    public int getTauxCourant() { return participantsCourant.isEmpty() ? 0 : (int) Math.round(100.0 * repondants.size() / participantsCourant.size()); }


    // rapport de dépouillement

    private EvalChaudService.Depouillement depouillementCourant() {
        exiger();
        if (courant == null) throw new IllegalStateException("Aucune évaluation sélectionnée");
        return service.depouillement(courant.getId());
    }
    private String nomFichier(String ext) {
        String n = Normalizer.normalize(courant.getIntitule(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(Locale.ROOT);
        return "depouillement-" + n + "." + ext;
    }
    // analyse de Claude, gardée tant que les réponses et avis n'ont pas changé
    private String cleAnalyse;
    private cd.mura.hrx.service.recrutement.ClaudeCvService.AnalyseFormation analyseIA;
    private String analyseIndispo;

    /** Analyse rédigée par Claude ; en cas d'impossibilité, la raison est gardée dans analyseIndispo. */
    private void preparerAnalyse(EvalChaudService.Depouillement d) {
        List<cd.mura.hrx.entity.eval.EvalChaudReponse> avis = d.evalChaud().isFroid() ? service.reponsesFroid(d.evalChaud().getId()) : List.of();
        String cle = d.evalChaud().getId() + "/" + d.nbRepondants() + "/" + avis.stream().filter(cd.mura.hrx.entity.eval.EvalChaudReponse::isAvisDonne)
                .map(r -> r.getId() + ":" + r.getAvisNote()).reduce("", String::concat);
        if (cle.equals(cleAnalyse)) return;
        analyseIA = null; analyseIndispo = null;
        cd.mura.hrx.entity.config.App app = appDAO.findFirst();
        if (d.nbRepondants() == 0) analyseIndispo = "Aucune réponse reçue : pas d'analyse à produire.";
        else if (app == null || !Boolean.TRUE.equals(app.getIaActive())) analyseIndispo = "Analyse par Claude non incluse : l'IA est désactivée (Paramètres > IA).";
        else try {
            analyseIA = claude.analyserFormation(app, EvalChaudExportService.donneesAnalyse(d, avis));
        } catch (RuntimeException e) {
            java.util.logging.Logger.getLogger(EvalChaudAdminView.class.getName()).warning("HRX analyse IA du dépouillement " + d.evalChaud().getId() + " : " + e.getMessage());
            analyseIndispo = "Analyse par Claude indisponible : " + e.getMessage();
        }
        // une erreur n'est pas mise en cache : le prochain export retentera
        cleAnalyse = analyseIA != null || d.nbRepondants() == 0 ? cle : null;
    }

    public StreamedContent getExportExcel() {
        EvalChaudService.Depouillement d = depouillementCourant();
        preparerAnalyse(d);
        byte[] data = exportService.excel(d, analyseIA, analyseIndispo);
        auditService.log("EXPORT", "Formations", "EvalChaud", courant.getId(), "Export Excel du dépouillement", courant.getIntitule(), true);
        return DefaultStreamedContent.builder().name(nomFichier("xlsx")).contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").stream(() -> new ByteArrayInputStream(data)).build();
    }
    public StreamedContent getExportPdf() {
        EvalChaudService.Depouillement d = depouillementCourant();
        preparerAnalyse(d);
        byte[] data = exportService.pdf(d, analyseIA, analyseIndispo);
        auditService.log("EXPORT", "Formations", "EvalChaud", courant.getId(), "Export PDF du dépouillement", courant.getIntitule(), true);
        return DefaultStreamedContent.builder().name(nomFichier("pdf")).contentType("application/pdf").stream(() -> new ByteArrayInputStream(data)).build();
    }

    /** Fiche vierge (MAF 063 ou MAF 064) de l'évaluation affichée, pour une collecte sur papier. */
    public StreamedContent getFicheVierge() {
        exiger();
        if (courant == null) return null;
        byte[] data = ficheService.pdf(service.charger(courant.getId()), null, null);
        String ref = courant.isFroid() ? "MAF064" : "MAF063";
        return DefaultStreamedContent.builder().name(ref + "-fiche-vierge-" + nomFichier("pdf").replaceFirst("^depouillement-", "")).contentType("application/pdf").stream(() -> new ByteArrayInputStream(data)).build();
    }

    /** Fiche MAF 064 remplie d'un collaborateur (évaluation à froid). */
    public StreamedContent ficheCollaborateur(cd.mura.hrx.entity.eval.EvalChaudReponse r) {
        exiger();
        cd.mura.hrx.entity.eval.EvalChaudReponse x = service.reponse(r.getId());
        byte[] data = ficheService.pdf(service.charger(courant.getId()), x.getParticipant(), x);
        String n = Normalizer.normalize(x.getParticipant().getNomComplet(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(Locale.ROOT);
        auditService.log("EXPORT", "Formations", "EvalChaudReponse", r.getId(), "Fiche d'évaluation à froid", courant.getIntitule() + " : " + x.getParticipant().getNomComplet(), true);
        return DefaultStreamedContent.builder().name("MAF064-" + n + "-" + nomFichier("pdf").replaceFirst("^depouillement-", "")).contentType("application/pdf").stream(() -> new ByteArrayInputStream(data)).build();
    }

    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
}
