package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.bean.config.PrivilegeBean;
import cd.mura.hrx.dao.config.DirectionDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.dao.eval.CampagneDAO;
import cd.mura.hrx.dao.eval.EvaluationDAO;
import cd.mura.hrx.dao.eval.ObjectifDAO;
import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.Campagne;
import cd.mura.hrx.entity.eval.Evaluation;
import cd.mura.hrx.entity.eval.FicheObjectifs;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Tableau de bord */
@Named
@ViewScoped
@Getter
@Setter
public class TableauBordView implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Inject private LoginBean loginBean;
    @Inject private PrivilegeBean privilegeBean;
    @EJB private UserDAO userDAO;
    @EJB private DirectionDAO directionDAO;
    @EJB private CampagneDAO campagneDAO;
    @EJB private EvaluationDAO evaluationDAO;
    @EJB private ObjectifDAO objectifDAO;
    @EJB private cd.mura.hrx.service.SuiviObjectifsService suiviObjectifs;
    @EJB private cd.mura.hrx.service.AlertesService alertesService;
    @EJB private cd.mura.hrx.service.EvalChaudService evalChaud;
    /** Fixation des objectifs de l'année en cours (DCHT). */
    private cd.mura.hrx.service.SuiviObjectifsService.Agregat objectifsAnnee;

    /** Ligne de la répartition par direction (vue DCHT). */
    public record LigneDirection(String nom, String abrege, long effectif, long initiees, long soumises, long evaluees, long validees, long retard) {
        public int taux() { return effectif == 0 ? 0 : (int) Math.round(100.0 * validees / effectif); }
        public int pct(long v) { return effectif == 0 ? 0 : (int) Math.round(100.0 * v / effectif); }
        public long nonInitiees() { return Math.max(0, effectif - initiees); }
    }

    /** Action attendue de l'utilisateur connecté. */
    public record Tache(String icone, String titre, String detail, String lien, String niveau) { }

    /** Répartition des niveaux d'atteinte sur les fiches évaluées/validées. */
    public record Niveau(String code, String libelle, long nb, int pct, String classe) { }

    private Users moi;
    private Campagne campagne;
    private int exercice;
    private boolean dcht, manager, directeur;

    // --- mon espace
    private FicheObjectifs mesObjectifs;
    private Evaluation maFiche;

    // --- mon équipe
    private final List<Users> equipe = new ArrayList<>();
    private final Map<Integer, Integer> niveaux = new HashMap<>();
    private final Map<Integer, Evaluation> fichesEquipe = new HashMap<>();
    private final Map<Integer, FicheObjectifs> objectifsEquipe = new HashMap<>();

    // --- direction(s) dirigée(s)
    private List<Direction> directionsDirigees = new ArrayList<>();
    private List<Evaluation> fichesDirection = new ArrayList<>();

    // --- pilotage DCHT
    private List<Users> evaluables = new ArrayList<>();
    private List<Evaluation> toutes = new ArrayList<>();
    private List<LigneDirection> parDirection = new ArrayList<>();
    private List<Niveau> niveauxAtteinte = new ArrayList<>();
    private List<Tache> taches = new ArrayList<>();
    /** Alertes graduées ouvertes pour l'utilisateur (rappels, relances, niveau critique). */
    private List<cd.mura.hrx.entity.eval.Relance> alertes = new ArrayList<>();
    /** DCHT : nombre d'alertes critiques ouvertes dans toute la banque. */
    private long nbAlertesCritiques;
    public boolean isAlerteCritique() { return alertes.stream().anyMatch(cd.mura.hrx.entity.eval.Relance::isCritique); }

    @PostConstruct
    public void init() {
        moi = loginBean.getUsers();
        campagne = campagneDAO.findOuverte();
        exercice = campagne != null ? campagne.getExercice() : Year.now().getValue();
        dcht = (moi.getProfil() != null && moi.getProfil().getId() == 1)
                || privilegeBean.hasPrivilege("EVAL_DCHT") || privilegeBean.hasPrivilege("CAMP_VIEW") || privilegeBean.hasPrivilege("CAMP_CREATE");

        chargerMonEspace();
        chargerEquipe();
        chargerDirection();
        if (dcht) { chargerPilotage(); objectifsAnnee = suiviObjectifs.total(suiviObjectifs.etat(Year.now().getValue(), null)); }
        construireTaches();
        try {
            alertes = alertesService.ouvertesPour(moi.getId());
            if (dcht) nbAlertesCritiques = alertesService.nbCritiques();
        } catch (Exception e) { alertes = new ArrayList<>(); }
    }

    // chargements

    private void chargerMonEspace() {
        mesObjectifs = objectifDAO.findFiche(moi.getId(), exercice);
        maFiche = campagne == null ? null : evaluationDAO.findByCampagneEtSalarie(campagne.getId(), moi.getId());
    }

    private void chargerEquipe() {
        equipe.clear(); niveaux.clear(); fichesEquipe.clear(); objectifsEquipe.clear();
        collecter(moi.getId(), 1, new HashSet<>());
        manager = !equipe.isEmpty();
        if (!manager) return;
        for (FicheObjectifs f : objectifDAO.findFiches(exercice, equipe.stream().map(Users::getId).toList()))
            objectifsEquipe.put(f.getSalarie().getId(), f);
        if (campagne != null) {
            for (Evaluation e : evaluationDAO.findByCampagne(campagne.getId()))
                if (niveaux.containsKey(e.getSalarie().getId())) fichesEquipe.put(e.getSalarie().getId(), e);
        }
    }

    private void collecter(int n1, int niveau, Set<Integer> vus) {
        if (!vus.add(n1)) return;
        for (Users u : userDAO.findBySuperieur(n1)) {
            equipe.add(u);
            niveaux.put(u.getId(), niveau);
            collecter(u.getId(), niveau + 1, vus);
        }
    }

    private void chargerDirection() {
        directionsDirigees = directionDAO.findDirigeesPar(moi.getId());
        directeur = !directionsDirigees.isEmpty();
        fichesDirection = new ArrayList<>();
        if (!directeur || campagne == null) return;
        Set<Integer> ids = directionsDirigees.stream().map(Direction::getId).collect(Collectors.toSet());
        Map<Integer, Integer> dirParUser = new HashMap<>();
        for (Users u : userDAO.findActifsAvecDirection()) if (u.getDirection() != null) dirParUser.put(u.getId(), u.getDirection().getId());
        for (Evaluation e : evaluationDAO.findByCampagne(campagne.getId())) {
            Integer d = dirParUser.get(e.getSalarie().getId());
            if (d != null && ids.contains(d)) fichesDirection.add(e);
        }
    }

    private void chargerPilotage() {
        List<Users> actifs = userDAO.findActifsAvecDirection();
        evaluables = actifs.stream().filter(u -> u.getEvaluable() == 1).toList();
        toutes = campagne == null ? List.of() : evaluationDAO.findByCampagne(campagne.getId());
        Map<Integer, Users> parId = new HashMap<>();
        for (Users u : actifs) parId.put(u.getId(), u);

        // Répartition par direction : effectif évaluable et avancement des fiches
        Map<String, long[]> agg = new LinkedHashMap<>();   // [effectif, initiées, soumises, évaluées, validées, retard]
        Map<String, String> abreges = new HashMap<>();
        for (Users u : evaluables) {
            String k = cleDirection(u);
            abreges.putIfAbsent(k, u.getDirection() == null ? "—" : s(u.getDirection().getAbrege()));
            agg.computeIfAbsent(k, x -> new long[6])[0]++;
        }
        for (Evaluation e : toutes) {
            Users u = parId.getOrDefault(e.getSalarie().getId(), e.getSalarie());
            String k = cleDirection(u);
            abreges.putIfAbsent(k, u.getDirection() == null ? "—" : s(u.getDirection().getAbrege()));
            long[] a = agg.computeIfAbsent(k, x -> new long[6]);
            a[1]++;
            switch (e.getStatut()) {
                case Evaluation.SOUMISE -> a[2]++;
                case Evaluation.EVALUEE -> a[3]++;
                case Evaluation.VALIDEE -> a[4]++;
                default -> { }
            }
            if (e.isEnRetard()) a[5]++;
        }
        parDirection = agg.entrySet().stream()
                .map(en -> new LigneDirection(en.getKey(), abreges.getOrDefault(en.getKey(), ""), en.getValue()[0], en.getValue()[1], en.getValue()[2], en.getValue()[3], en.getValue()[4], en.getValue()[5]))
                .sorted(Comparator.comparingInt(LigneDirection::taux).reversed().thenComparing(LigneDirection::nom))
                .toList();

        // Niveaux d'atteinte (fiches évaluées par le N+1 ou validées)
        List<Evaluation> notees = toutes.stream().filter(e -> e.getNiveauAtteinte() != null && (Evaluation.EVALUEE.equals(e.getStatut()) || Evaluation.VALIDEE.equals(e.getStatut()))).toList();
        String[][] codes = {{"ATTEINTS", "hx-att--4"}, {"MOYENNEMENT", "hx-att--3"}, {"FAIBLEMENT", "hx-att--2"}, {"NON_ATTEINTS", "hx-att--1"}};
        niveauxAtteinte = new ArrayList<>();
        for (String[] c : codes) {
            long nb = notees.stream().filter(e -> c[0].equals(e.getNiveauAtteinte())).count();
            niveauxAtteinte.add(new Niveau(c[0], Evaluation.niveauAtteinteLabel(c[0]).replace("Objectifs ", "").substring(0, 1).toUpperCase(Locale.ROOT) + Evaluation.niveauAtteinteLabel(c[0]).replace("Objectifs ", "").substring(1), nb, notees.isEmpty() ? 0 : (int) Math.round(100.0 * nb / notees.size()), c[1]));
        }
    }

    private String cleDirection(Users u) {
        try { return u.getDirection() == null ? "Sans direction" : s(u.getDirection().getNom()); } catch (Exception ex) { return "Sans direction"; }
    }

    // à faire

    private void construireTaches() {
        taches = new ArrayList<>();
        String ctx = "";
        // --- moi
        if (moi.getEvaluable() == 1) {
            if (mesObjectifs == null || mesObjectifs.getNbValides() == 0)
                taches.add(new Tache("pi-flag", "Vos objectifs " + exercice + " ne sont pas encore fixés",
                        "Vous pouvez en proposer : votre N+1 les validera avant toute évaluation.", "/ui/mesEvaluations", "warn"));
            else if (mesObjectifs.getNbProposes() > 0)
                taches.add(new Tache("pi-hourglass", pluriel(mesObjectifs.getNbProposes(), "proposition d'objectif", "propositions d'objectifs") + " en attente de validation",
                        "Votre hiérarchie doit les valider ou les refuser.", "/ui/mesEvaluations", "info"));
            else if (mesObjectifs.getNbContestations() > 0)
                taches.add(new Tache("pi-flag", pluriel(mesObjectifs.getNbContestations(), "demande de révision d'objectif", "demandes de révision d'objectifs") + " en attente de réponse",
                        "Votre N+1 doit réviser ou maintenir ces objectifs avant la signature.", "/ui/objectifs?salarie=" + moi.getId() + "&exercice=" + exercice, "info"));
            else if (!mesObjectifs.isSignee() && !mesObjectifs.isSigneeSalarie())
                taches.add(new Tache("pi-pen-to-square", "Signer votre fiche d'objectifs " + exercice,
                        pluriel(mesObjectifs.getNbValides(), "objectif validé", "objectifs validés") + (mesObjectifs.isSigneeN1() ? " · déjà signée par votre N+1 : il ne manque que votre signature." : " · votre signature et celle de votre N+1 figent les objectifs."),
                        "/ui/objectifs?salarie=" + moi.getId() + "&exercice=" + exercice, "primary"));
            else if (!mesObjectifs.isSignee())
                taches.add(new Tache("pi-hourglass", "Fiche d'objectifs signée, en attente de votre N+1",
                        "Elle sera figée dès que votre N+1 l'aura signée à son tour.", "/ui/objectifs?salarie=" + moi.getId() + "&exercice=" + exercice, "info"));
            if (maFiche != null) {
                switch (maFiche.getStatut()) {
                    case Evaluation.INITIEE -> taches.add(new Tache("pi-pencil", "Renseigner votre auto-évaluation",
                            echeanceTexte(), "/ui/fiche?id=" + maFiche.getId(), maFiche.isEnRetard() ? "danger" : "primary"));
                    case Evaluation.RETOURNEE -> taches.add(new Tache("pi-replay", "Votre fiche vous a été retournée",
                            "Corrigez les points signalés par votre N+1 puis soumettez-la à nouveau.", "/ui/fiche?id=" + maFiche.getId(), "danger"));
                    case Evaluation.EVALUEE -> taches.add(new Tache("pi-comment", "Donner votre avis sur l'évaluation du N+1",
                            "Renseignez vos souhaits et commentaires dans la partie V de la fiche.", "/ui/fiche?id=" + maFiche.getId(), "info"));
                    default -> { }
                }
            }
        }
        // --- évaluations de formation (tout salarié, intégré ou non à la campagne)
        try {
            for (var e : evalChaud.mesQuestionnaires(moi.getId()).entrySet()) {
                cd.mura.hrx.entity.eval.EvalChaud c = e.getKey();
                if (Boolean.TRUE.equals(e.getValue()) || !c.isRepondable()) continue;
                String fin = "";
                if (c.getDateFin() != null) {
                    long j = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), c.getDateFin());
                    fin = " · à rendre au plus tard le " + c.getDateFin().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                            + (j == 0 ? " (aujourd'hui)" : j == 1 ? " (demain)" : " (J-" + j + ")");
                }
                long j = c.getDateFin() == null ? 99 : java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), c.getDateFin());
                taches.add(new Tache(c.isFroid() ? "pi-chart-line" : "pi-graduation-cap",
                        (c.isFroid() ? "Évaluation à froid à remplir : " : "Évaluation de formation à remplir : ") + c.getIntitule(),
                        (c.isFroid() ? "Efficacité de la formation sur votre poste, environ 10 minutes" : "Votre avis sur la formation suivie, environ 5 minutes") + fin,
                        "/ui/questionnaire?id=" + c.getId(), j <= 2 ? "danger" : "primary"));
            }
            long avisFroid = evalChaud.avisEquipe(moi.getId()).stream().filter(r -> !r.isAvisDonne()).count();
            if (avisFroid > 0) taches.add(new Tache("pi-comments", pluriel(avisFroid, "évaluation à froid", "évaluations à froid") + " de vos collaborateurs en attente de votre avis",
                    "Donnez votre appréciation de l'efficacité de la formation pour chacun.", "/ui/mesEvaluations?onglet=2", "warn"));
        } catch (RuntimeException ex) {
            java.util.logging.Logger.getLogger(TableauBordView.class.getName()).warning("HRX tableau de bord : évaluations de formation : " + ex.getMessage());
        }
        // --- manager
        if (manager) {
            long aTraiter = getEquipeATraiter();
            if (aTraiter > 0) taches.add(new Tache("pi-inbox", pluriel(aTraiter, "auto-évaluation reçue", "auto-évaluations reçues") + " à évaluer",
                    "Vos collaborateurs directs attendent votre évaluation.", "/ui/equipe", "primary"));
            long props = getEquipePropositions();
            if (props > 0) taches.add(new Tache("pi-check-square", pluriel(props, "proposition d'objectif", "propositions d'objectifs") + " à valider",
                    "Objectifs saisis par vos collaborateurs, à valider ou refuser.", "/ui/equipe", "info"));
            // contestations d'objectifs à traiter (révision ou maintien motivé)
            List<FicheObjectifs> contestees = equipe.stream().filter(u -> niveaux.get(u.getId()) == 1)
                    .map(u -> objectifsEquipe.get(u.getId())).filter(f -> f != null && f.getNbContestations() > 0).toList();
            if (!contestees.isEmpty()) {
                long nb = contestees.stream().mapToLong(FicheObjectifs::getNbContestations).sum();
                FicheObjectifs f0 = contestees.get(0);
                taches.add(new Tache("pi-flag-fill", pluriel(nb, "demande de révision d'objectif", "demandes de révision d'objectifs") + (contestees.size() == 1 ? " de " + f0.getSalarie().getNomComplet() : " de vos collaborateurs"),
                        "Révisez-les ou maintenez-les en motivant votre réponse : la fiche ne peut être signée qu'ensuite.",
                        contestees.size() == 1 ? "/ui/objectifs?salarie=" + f0.getSalarie().getId() + "&exercice=" + exercice : "/ui/equipe", "danger"));
            }
            // fiches d'objectifs de mes collaborateurs directs prêtes à être signées par moi (N+1)
            List<FicheObjectifs> aSigner = equipe.stream().filter(u -> niveaux.get(u.getId()) == 1)
                    .map(u -> objectifsEquipe.get(u.getId()))
                    .filter(f -> f != null && !f.isSignee() && !f.isSigneeN1() && f.getNbValides() > 0 && f.getNbProposes() == 0 && f.getNbContestations() == 0).toList();
            if (aSigner.size() == 1) {
                FicheObjectifs f = aSigner.get(0);
                taches.add(new Tache("pi-pen-to-square", "Signer la fiche d'objectifs de " + f.getSalarie().getNomComplet(),
                        f.isSigneeSalarie() ? "Le collaborateur a déjà signé : il ne manque que votre signature." : pluriel(f.getNbValides(), "objectif validé", "objectifs validés") + ", prêts à être signés.",
                        "/ui/objectifs?salarie=" + f.getSalarie().getId() + "&exercice=" + exercice, "primary"));
            } else if (aSigner.size() > 1) {
                long dejaSignees = aSigner.stream().filter(FicheObjectifs::isSigneeSalarie).count();
                taches.add(new Tache("pi-pen-to-square", pluriel(aSigner.size(), "fiche d'objectifs", "fiches d'objectifs") + " à signer",
                        dejaSignees > 0 ? dejaSignees + " déjà signée(s) par le collaborateur, en attente de votre signature." : "Objectifs validés de vos collaborateurs, prêts à être signés.",
                        "/ui/equipe", "primary"));
            }
            long sansObj = getEquipeSansObjectifs();
            if (sansObj > 0) taches.add(new Tache("pi-flag", "Fixer les objectifs de " + pluriel(sansObj, "collaborateur", "collaborateurs"),
                    "Sans objectifs validés, un collaborateur ne peut pas intégrer la population évaluable.", "/ui/equipe", "warn"));
            if (campagne != null) {
                long aInitier = getEquipeAInitier();
                if (aInitier > 0) taches.add(new Tache("pi-user-plus", pluriel(aInitier, "collaborateur", "collaborateurs") + " à intégrer à la campagne",
                        "Constituez votre population évaluable pour « " + campagne.getLibelle() + " ».", "/ui/equipe", "warn"));
            }
        }
        // --- directeur
        if (directeur) {
            long avis = getDirectionAvisAttendus();
            if (avis > 0) taches.add(new Tache("pi-briefcase", pluriel(avis, "fiche transmise", "fiches transmises") + " en attente de votre avis",
                    "Évaluations de votre direction transmises par les N+1.", "/ui/evaluations", "primary"));
        }
        if (dcht && objectifsAnnee != null && objectifsAnnee.getRestants() > 0)
            taches.add(new Tache("pi-flag", pluriel(objectifsAnnee.getRestants(), "fiche d'objectifs non signée", "fiches d'objectifs non signées") + " pour " + Year.now().getValue(),
                    objectifsAnnee.getSignees() + " signée(s) sur " + objectifsAnnee.getEffectif() + (objectifsAnnee.getRetard() > 0 ? ", " + objectifsAnnee.getRetard() + " en retard" : "") + ".", "/ui/suiviObjectifs", objectifsAnnee.getRetard() > 0 ? "danger" : "warn"));
        // --- DCHT
        if (dcht) {
            if (campagne == null) {
                if (privilegeBean.hasPrivilege("CAMP_CREATE") || (moi.getProfil() != null && moi.getProfil().getId() == 1))
                    taches.add(new Tache("pi-megaphone", "Aucune campagne ouverte", "Lancez la campagne de mi-parcours ou de fin d'année pour démarrer les évaluations.", "/ui/campagnes", "info"));
            } else {
                long aValider = toutes.stream().filter(e -> Evaluation.EVALUEE.equals(e.getStatut())).count();
                if (aValider > 0) taches.add(new Tache("pi-verified", pluriel(aValider, "fiche évaluée", "fiches évaluées") + " à valider par la DCHT",
                        "Clôturez le circuit : validation finale des fiches transmises.", "/ui/evaluations", "primary"));
                long rest = getRestantes();
                if (rest > 0) taches.add(new Tache("pi-users", pluriel(rest, "évaluation attendue", "évaluations attendues") + " non initiée" + (rest > 1 ? "s" : ""),
                        "Sur " + getAttendues() + " attendues, " + getNbInitiees() + " fiches ont été initiées par les N+1.", "/ui/evaluations", campagne.isEnRetard() ? "danger" : "warn"));
                if (campagne.isEnRetard() && getNbRetard() > 0)
                    taches.add(new Tache("pi-exclamation-triangle", pluriel(getNbRetard(), "fiche", "fiches") + " non finalisée" + (getNbRetard() > 1 ? "s" : "") + " après l'échéance",
                            "Échéance dépassée le " + campagne.getDateFin().format(DF) + ".", "/ui/evaluations", "danger"));
            }
        }
    }

    private String echeanceTexte() {
        if (campagne == null || campagne.getDateFin() == null) return "";
        long j = getJoursRestants();
        return j < 0 ? "Échéance dépassée depuis " + pluriel(-j, "jour", "jours") + "." : j == 0 ? "Dernier jour aujourd'hui." : "Il vous reste " + pluriel(j, "jour", "jours") + " (échéance le " + campagne.getDateFin().format(DF) + ").";
    }

    // indicateurs : campagne

    public boolean isCampagneOuverte() { return campagne != null; }
    public long getJoursRestants() { return campagne == null || campagne.getDateFin() == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), campagne.getDateFin()); }
    public int getAvancementCalendrier() {
        if (campagne == null || campagne.getDateDebut() == null || campagne.getDateFin() == null) return 0;
        long total = java.time.temporal.ChronoUnit.DAYS.between(campagne.getDateDebut(), campagne.getDateFin());
        long passe = java.time.temporal.ChronoUnit.DAYS.between(campagne.getDateDebut(), LocalDate.now());
        return total <= 0 ? 100 : (int) Math.max(0, Math.min(100, Math.round(100.0 * passe / total)));
    }

    // indicateurs : DCHT

    public long getNbEvaluables() { return evaluables.size(); }
    public long getNbInitiees() { return toutes.size(); }
    public long getNbSoumises() { return toutes.stream().filter(e -> !Evaluation.INITIEE.equals(e.getStatut()) && !Evaluation.RETOURNEE.equals(e.getStatut())).count(); }
    public long getNbEnAttenteN1() { return toutes.stream().filter(e -> Evaluation.SOUMISE.equals(e.getStatut())).count(); }
    public long getNbEvaluees() { return toutes.stream().filter(e -> Evaluation.EVALUEE.equals(e.getStatut())).count(); }
    public long getNbValidees() { return toutes.stream().filter(Evaluation::isFinalisee).count(); }
    public long getNbRetournees() { return toutes.stream().filter(e -> Evaluation.RETOURNEE.equals(e.getStatut())).count(); }
    public long getNbAutoEval() { return toutes.stream().filter(e -> Evaluation.INITIEE.equals(e.getStatut()) || Evaluation.RETOURNEE.equals(e.getStatut())).count(); }
    public long getNbRetard() { return toutes.stream().filter(Evaluation::isEnRetard).count(); }
    public long getNbHorsDelai() { return toutes.stream().filter(e -> e.getDateSoumission() != null && !e.isSoumiseATemps()).count(); }
    public long getNbATemps() { return toutes.stream().filter(Evaluation::isSoumiseATemps).count(); }
    /** Nombre attendu saisi, ou nombre de fiches ouvertes s'il est plus grand. */
    public long getAttendues() { return campagne == null ? 0 : Math.max(campagne.getEffectifAttendu(), toutes.size()); }
    public long getRestantes() { return Math.max(0, getAttendues() - getNbInitiees()); }
    /** Taux de réalisation : validées / attendues (sinon / initiées). */
    public int getTauxRealisation() { long base = getAttendues() > 0 ? getAttendues() : getNbInitiees(); return base == 0 ? 0 : (int) Math.round(100.0 * getNbValidees() / base); }
    public int getTauxIntegration() { return getAttendues() == 0 ? 0 : (int) Math.min(100, Math.round(100.0 * getNbInitiees() / getAttendues())); }
    public int getTauxSoumission() { return getNbInitiees() == 0 ? 0 : (int) Math.round(100.0 * getNbSoumises() / getNbInitiees()); }
    public int pctAttendu(long v) { long base = getAttendues() > 0 ? getAttendues() : getNbInitiees(); return base == 0 ? 0 : (int) Math.min(100, Math.round(100.0 * v / base)); }
    public double getNoteMoyenne() {
        return toutes.stream().filter(e -> e.getNoteGlobale() != null && (Evaluation.EVALUEE.equals(e.getStatut()) || Evaluation.VALIDEE.equals(e.getStatut())))
                .mapToDouble(Evaluation::getNoteGlobale).average().orElse(0);
    }
    public long getNbNotees() { return toutes.stream().filter(e -> e.getNoteGlobale() != null && (Evaluation.EVALUEE.equals(e.getStatut()) || Evaluation.VALIDEE.equals(e.getStatut()))).count(); }
    public long getNbDirectionsConcernees() { return parDirection.size(); }

    // indicateurs : équipe

    public long getEquipeTaille() { return equipe.size(); }
    public long getEquipeDirects() { return equipe.stream().filter(u -> niveaux.getOrDefault(u.getId(), 1) == 1).count(); }
    public long getEquipeEvaluables() { return equipe.stream().filter(u -> u.getEvaluable() == 1).count(); }
    public long getEquipeSansObjectifs() { return equipe.stream().filter(u -> u.getEvaluable() == 1 && niveaux.get(u.getId()) == 1 && !(objectifsEquipe.containsKey(u.getId()) && objectifsEquipe.get(u.getId()).getNbValides() > 0)).count(); }
    public long getEquipePropositions() { return equipe.stream().filter(u -> niveaux.get(u.getId()) == 1).mapToLong(u -> objectifsEquipe.containsKey(u.getId()) ? objectifsEquipe.get(u.getId()).getNbProposes() : 0).sum(); }
    public long getEquipeInitiees() { return fichesEquipe.size(); }
    public long getEquipeAInitier() { return campagne == null ? 0 : equipe.stream().filter(u -> u.getEvaluable() == 1 && niveaux.get(u.getId()) == 1 && !fichesEquipe.containsKey(u.getId())).count(); }
    public long getEquipeATraiter() { return fichesEquipe.values().stream().filter(e -> Evaluation.SOUMISE.equals(e.getStatut()) && e.getEvaluateur() != null && e.getEvaluateur().getId() == moi.getId()).count(); }
    public long getEquipeEnAutoEval() { return fichesEquipe.values().stream().filter(e -> Evaluation.INITIEE.equals(e.getStatut()) || Evaluation.RETOURNEE.equals(e.getStatut())).count(); }
    public long getEquipeEvaluees() { return fichesEquipe.values().stream().filter(e -> Evaluation.EVALUEE.equals(e.getStatut())).count(); }
    public long getEquipeFinalisees() { return fichesEquipe.values().stream().filter(Evaluation::isFinalisee).count(); }
    public long getEquipeRetard() { return campagne == null || !campagne.isEnRetard() ? 0 : equipe.stream().filter(u -> u.getEvaluable() == 1 && (!fichesEquipe.containsKey(u.getId()) || !fichesEquipe.get(u.getId()).isFinalisee())).count(); }
    public int getEquipeTaux() { long ev = getEquipeEvaluables(); return ev == 0 ? 0 : (int) Math.round(100.0 * getEquipeFinalisees() / ev); }
    public int equipePct(long v) { long ev = getEquipeEvaluables(); return ev == 0 ? 0 : (int) Math.min(100, Math.round(100.0 * v / ev)); }
    /** Fiches directes nécessitant une action du N+1 connecté, les plus urgentes d'abord. */
    public List<Evaluation> getEquipeFichesATraiter() {
        return fichesEquipe.values().stream().filter(e -> Evaluation.SOUMISE.equals(e.getStatut()) && e.getEvaluateur() != null && e.getEvaluateur().getId() == moi.getId())
                .sorted(Comparator.comparing(Evaluation::getDateSoumission, Comparator.nullsLast(Comparator.<Date>naturalOrder()))).limit(6).toList();
    }

    // indicateurs : direction

    public String getDirectionsLibelle() { return directionsDirigees.stream().map(Direction::getNom).collect(Collectors.joining(", ")); }
    public long getDirectionFiches() { return fichesDirection.size(); }
    public long getDirectionAvisAttendus() { return fichesDirection.stream().filter(e -> Evaluation.EVALUEE.equals(e.getStatut()) && e.getDateAvisDirecteur() == null).count(); }
    public long getDirectionValidees() { return fichesDirection.stream().filter(Evaluation::isFinalisee).count(); }
    public long getDirectionEffectif() {
        Set<Integer> ids = directionsDirigees.stream().map(Direction::getId).collect(Collectors.toSet());
        return userDAO.findActifsAvecDirection().stream().filter(u -> u.getEvaluable() == 1 && u.getDirection() != null && ids.contains(u.getDirection().getId())).count();
    }
    public int getDirectionTaux() { long ef = getDirectionEffectif(); return ef == 0 ? 0 : (int) Math.round(100.0 * getDirectionValidees() / ef); }
    public List<Evaluation> getDirectionFichesAViser() {
        return fichesDirection.stream().filter(e -> Evaluation.EVALUEE.equals(e.getStatut()) && e.getDateAvisDirecteur() == null)
                .sorted(Comparator.comparing(Evaluation::getDateEvaluation, Comparator.nullsLast(Comparator.<Date>naturalOrder()))).limit(6).toList();
    }

    // mon espace

    public boolean isMoiEvaluable() { return moi.getEvaluable() == 1; }
    public long getMesObjectifsValides() { return mesObjectifs == null ? 0 : mesObjectifs.getNbValides(); }
    public long getMesObjectifsProposes() { return mesObjectifs == null ? 0 : mesObjectifs.getNbProposes(); }
    public boolean isMesObjectifsSignes() { return mesObjectifs != null && mesObjectifs.isSignee(); }
    /** Objectifs validés, sans proposition en attente, et pas encore signés par moi. */
    public boolean isMesObjectifsASigner() { return mesObjectifs != null && !mesObjectifs.isSignee() && !mesObjectifs.isSigneeSalarie() && mesObjectifs.getNbValides() > 0 && mesObjectifs.getNbProposes() == 0 && mesObjectifs.getNbContestations() == 0; }
    public String getMaFicheEtat() {
        if (!isMoiEvaluable()) return "Compte non évaluable";
        if (campagne == null) return "Aucune campagne ouverte";
        if (maFiche == null) return "Pas encore intégré à la campagne";
        return maFiche.getStatutLabel();
    }
    public String getMaFicheClasse() {
        if (maFiche == null) return "hx-badge--draft";
        return switch (maFiche.getStatut()) {
            case Evaluation.VALIDEE -> "hx-badge--done";
            case Evaluation.SOUMISE -> "hx-badge--pending";
            case Evaluation.EVALUEE -> "hx-badge--info";
            case Evaluation.RETOURNEE -> "hx-badge--late";
            default -> maFiche.isEnRetard() ? "hx-badge--late" : "hx-badge--draft";
        };
    }
    /** Étape 1..5 du circuit pour ma fiche : objectifs, auto-éval, évaluation N+1, avis, validation. */
    public int getMaFicheEtape() {
        if (maFiche == null) return getMesObjectifsValides() > 0 ? 1 : 0;
        return switch (maFiche.getStatut()) {
            case Evaluation.SOUMISE -> 3;
            case Evaluation.EVALUEE -> 4;
            case Evaluation.VALIDEE -> 5;
            default -> 2;
        };
    }
    public String getMaNiveauLabel() { return maFiche == null ? "" : Evaluation.niveauAtteinteLabel(maFiche.getNiveauAtteinte()); }
    public Double getMaNote() { return maFiche == null ? null : maFiche.getNoteGlobale(); }
    public boolean isMaNoteVisible() { return maFiche != null && maFiche.getNoteGlobale() != null && (Evaluation.EVALUEE.equals(maFiche.getStatut()) || Evaluation.VALIDEE.equals(maFiche.getStatut())); }

    // utilitaires

    public String initiales(Users u) {
        String p = s(u.getPrenom()), n = s(u.getNom());
        return ((p.isEmpty() ? "" : p.substring(0, 1)) + (n.isEmpty() ? "" : n.substring(0, 1))).toUpperCase(Locale.ROOT);
    }
    public String depuis(Date d) {
        if (d == null) return "";
        long j = java.time.temporal.ChronoUnit.DAYS.between(d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate(), LocalDate.now());
        return j <= 0 ? "aujourd'hui" : j == 1 ? "hier" : "il y a " + j + " jours";
    }
    public String getSalutation() {
        int h = java.time.LocalTime.now().getHour();
        return h < 18 ? "Bonjour" : "Bonsoir";
    }
    public String getDateDuJour() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH));
    }
    private static String pluriel(long n, String sing, String plur) { return n + " " + (n > 1 ? plur : sing); }
    private static String s(String v) { return v == null ? "" : v; }
}
