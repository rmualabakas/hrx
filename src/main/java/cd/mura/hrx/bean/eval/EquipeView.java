package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.dao.eval.CampagneDAO;
import cd.mura.hrx.dao.eval.EvaluationDAO;
import cd.mura.hrx.dao.eval.ModeleFicheDAO;
import cd.mura.hrx.dao.eval.ObjectifDAO;
import cd.mura.hrx.entity.eval.FicheObjectifs;
import cd.mura.hrx.entity.config.Users;
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
import java.util.*;

/** Mon équipe : périmètre du N+1, avancement des fiches de la campagne ouverte et fixation des objectifs. */
@Named
@ViewScoped
@Getter
@Setter
public class EquipeView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private UserDAO userDAO;
    @EJB private CampagneDAO campagneDAO;
    @EJB private EvaluationDAO evaluationDAO;
    @EJB private ModeleFicheDAO modeleFicheDAO;
    @EJB private ObjectifDAO objectifDAO;
    @EJB private cd.mura.hrx.service.EvaluationService evaluationService;
    @EJB private cd.mura.hrx.service.NotificationService notifications;
    @EJB private cd.mura.hrx.service.PeriodeEssaiService periodeEssai;
    @EJB private cd.mura.hrx.dao.config.DirectionDAO directionDAO;
    /** Fiches transmises attendant mon visa (N+2) ou mon avis (directeur) : id salarié → libellé de l'action. */
    private Map<Integer, String> actionsHierarchiques = new HashMap<>();

    private Campagne campagne;
    private List<Users> membres = new ArrayList<>();
    private Map<Integer, Evaluation> fiches = new HashMap<>();
    private Map<Integer, FicheObjectifs> fichesObjectifs = new HashMap<>();
    private int exercice = java.time.Year.now().getValue();
    private Map<Integer, Integer> niveaux = new HashMap<>();
    /** Par défaut, tout le périmètre hiérarchique (N-1, N-2, N-3…) est affiché. */
    private boolean inclureIndirects = true;
    private String recherche = "";
    private String filtre = "tous";
    /** Affichage choisi : cartes, liste ou hierarchie. Mémorisé dans un cookie du navigateur. */
    private String vue = "cartes";
    private static final String COOKIE_VUE = "hrx_equipe_vue";
    private static final Set<String> VUES = Set.of("cartes", "liste", "hierarchie");
    /** Constitution de la population évaluable : identifiants cochés dans le dialogue. */
    private List<Integer> selectionIds = new ArrayList<>();
    /** Durée de la période d'essai (Paramètres > Évaluations). */
    private int moisEssai = 6;
    /** Collaborateurs directs arrivés en fin de période d'essai, jamais évalués : évaluation de fin d'essai attendue. */
    private List<cd.mura.hrx.service.PeriodeEssaiService.Echeance> essaisAEvaluer = new ArrayList<>();
    private List<cd.mura.hrx.service.PeriodeEssaiService.Echeance> essaisProchains = new ArrayList<>();

    @PostConstruct
    public void init() {
        Object c = FacesContext.getCurrentInstance().getExternalContext().getRequestCookieMap().get(COOKIE_VUE);
        if (c instanceof jakarta.servlet.http.Cookie ck && VUES.contains(ck.getValue())) vue = ck.getValue();
        charger();
    }

    public void choisirVue(String v) {
        if (!VUES.contains(v)) return;
        vue = v;
        Map<String, Object> props = new HashMap<>();
        props.put("maxAge", 60 * 60 * 24 * 365);
        props.put("path", FacesContext.getCurrentInstance().getExternalContext().getRequestContextPath() + "/");
        FacesContext.getCurrentInstance().getExternalContext().addResponseCookie(COOKIE_VUE, v, props);
    }

    public void charger() {
        Users moi = loginBean.getUsers();
        campagne = campagneDAO.findOuverte();
        membres = new ArrayList<>();
        niveaux.clear();
        collecter(moi.getId(), 1, new HashSet<>());
        fiches.clear();
        exercice = campagne != null ? campagne.getExercice() : java.time.Year.now().getValue();
        moisEssai = periodeEssai.dureeMois();
        List<Users> directs = membres.stream().filter(this::isDirect).toList();
        essaisAEvaluer = periodeEssai.aEvaluer(directs);
        essaisProchains = periodeEssai.prochaines(directs);
        essaisNonClos = evaluationService.essaisNonClos(membres.stream().map(Users::getId).toList());
        fichesObjectifs.clear();
        for (FicheObjectifs f : objectifDAO.findFiches(exercice, membres.stream().map(Users::getId).toList())) fichesObjectifs.put(f.getSalarie().getId(), f);
        if (campagne != null) {
            for (Evaluation e : evaluationDAO.findByEvaluateur(campagne.getId(), moi.getId())) fiches.put(e.getSalarie().getId(), e);
            // fiches de N-2 déjà initiées par leur propre N+1 (lecture seule ici)
            if (inclureIndirects) for (Users u : membres) if (!fiches.containsKey(u.getId())) {
                Evaluation e = evaluationDAO.findByCampagneEtSalarie(campagne.getId(), u.getId());
                if (e != null) fiches.put(u.getId(), e);
            }
        }
        actionsHierarchiques.clear();
        for (Users u : membres) {
            Evaluation f = fiches.get(u.getId());
            if (f == null || !Evaluation.EVALUEE.equals(f.getStatut()) || isDirect(u)) continue;
            Users sal = userDAO.findAccountById(u.getId());
            cd.mura.hrx.entity.config.Direction d = sal == null || sal.getDirection() == null ? null : directionDAO.find(sal.getDirection().getId());
            int dir = d == null ? 0 : d.getDirigePar();
            if (dir == moi.getId()) { if (f.getDateAvisDirecteur() == null) actionsHierarchiques.put(u.getId(), "Donner mon avis"); }
            else if (niveau(u) == 2 && f.getDateVisaN2() == null) actionsHierarchiques.put(u.getId(), "Apposer mon visa");
        }
    }

    private void collecter(int n1, int niveau, Set<Integer> vus) {
        if (!vus.add(n1)) return;
        for (Users u : userDAO.findBySuperieur(n1)) {
            membres.add(u);
            niveaux.put(u.getId(), niveau);
            if (inclureIndirects) collecter(u.getId(), niveau + 1, vus);
        }
    }

    // lecture
    public List<Users> getMembresFiltres() {
        String f = recherche == null ? "" : recherche.trim().toLowerCase(Locale.ROOT);
        return membres.stream().filter(u -> {
            if (!f.isEmpty() && !(s(u.getNomComplet()) + " " + s(u.getFonction()) + " " + s(u.getMatricule())).toLowerCase(Locale.ROOT).contains(f)) return false;
            return switch (filtre) {
                case "aInitier" -> u.getEvaluable() == 1 && !enEssai(u) && fiche(u) == null;
                case "aTraiter" -> fiche(u) != null && Evaluation.SOUMISE.equals(fiche(u).getStatut()) || actionHierarchique(u) != null;
                case "retard" -> enRetard(u);
                case "finalisees" -> fiche(u) != null && fiche(u).isFinalisee();
                default -> true;
            };
        }).toList();
    }

    public Evaluation fiche(Users u) { return fiches.get(u.getId()); }
    /** Action attendue de moi, supérieur indirect, sur la fiche transmise de ce collaborateur (null si aucune). */
    public String actionHierarchique(Users u) { return actionsHierarchiques.get(u.getId()); }
    public int niveau(Users u) { return niveaux.getOrDefault(u.getId(), 1); }
    public boolean isDirect(Users u) { return niveau(u) == 1; }

    public String etat(Users u) {
        if (u.getEvaluable() != 1) return "Non évaluable";
        if (essaiNonClos(u)) return "Éval. d'essai en cours";
        if (enEssai(u)) return "Période d'essai";
        if (campagne == null) return "Hors campagne";
        Evaluation e = fiche(u);
        return e == null ? "Fiche à initier" : e.getStatutLabel();
    }

    public String etatClasse(Users u) {
        if (u.getEvaluable() == 1 && enEssai(u)) return "hx-badge--essai";
        if (u.getEvaluable() != 1 || campagne == null) return "hx-badge--draft";
        Evaluation e = fiche(u);
        if (e == null) return enRetard(u) ? "hx-badge--late" : "hx-badge--draft";
        return switch (e.getStatut()) {
            case Evaluation.VALIDEE -> "hx-badge--done";
            case Evaluation.SOUMISE -> "hx-badge--pending";
            case Evaluation.RETOURNEE -> "hx-badge--late";
            case Evaluation.EVALUEE -> "hx-badge--info";
            default -> enRetard(u) ? "hx-badge--late" : "hx-badge--draft";
        };
    }

    /** Recrue qui n'a pas encore l'ancienneté requise au début de la campagne : non éligible. */
    public boolean enEssai(Users u) {
        return essaiNonClos(u) || cd.mura.hrx.service.PeriodeEssaiService.enEssai(u, cd.mura.hrx.service.PeriodeEssaiService.reference(campagne), moisEssai);
    }
    /** Évaluation de fin d'essai ouverte mais pas encore validée par la DCHT : toujours non éligible. */
    private Set<Integer> essaisNonClos = new HashSet<>();
    public boolean essaiNonClos(Users u) { return essaisNonClos.contains(u.getId()); }
    public String essaiInfo(Users u) {
        if (essaiNonClos(u)) return "Évaluation de fin de période d'essai en cours : non éligible aux campagnes tant qu'elle n'est pas validée par la DCHT";
        java.time.LocalDate f = finEssai(u);
        return f == null ? "" : "Période d'essai jusqu'au " + f.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " : non éligible aux campagnes";
    }
    /** Date de fin de période d'essai affichée sur la carte. */
    public String finEssaiTexte(Users u) {
        java.time.LocalDate f = finEssai(u);
        return f == null ? "" : f.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }
    public java.time.LocalDate finEssai(Users u) { return cd.mura.hrx.service.PeriodeEssaiService.finEssai(u, moisEssai); }

    public boolean enRetard(Users u) {
        if (campagne == null || !campagne.isEnRetard() || u.getEvaluable() != 1 || (enEssai(u) && fiche(u) == null)) return false;
        Evaluation e = fiche(u);
        return e == null || !e.isFinalisee();
    }

    public FicheObjectifs ficheObjectifs(Users u) { return fichesObjectifs.get(u.getId()); }
    public int nbObjectifs(Users u) { FicheObjectifs f = ficheObjectifs(u); return f == null ? 0 : (int) f.getNbValides(); }
    public int nbProposes(Users u) { FicheObjectifs f = ficheObjectifs(u); return f == null ? 0 : (int) f.getNbProposes(); }
    /** Recrue de l'exercice : ses objectifs se fixent à la prise de poste. */
    public boolean recrue(Users u) {
        if (u.getDateEmbauche() == null) return false;
        return u.getDateEmbauche().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().getYear() == exercice;
    }
    /** Fiche non signée alors que la fenêtre de fixation (fin janvier, ou 1 mois après l'embauche) est passée. */
    public boolean fixationEnRetard(Users u) {
        if (u.getEvaluable() != 1 || objectifsSignes(u)) return false;
        java.time.LocalDate effet = recrue(u) ? u.getDateEmbauche().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate() : java.time.LocalDate.of(exercice, 1, 1);
        return java.time.LocalDate.now().isAfter(effet.plusMonths(1));
    }
    public boolean objectifsSignes(Users u) { FicheObjectifs f = ficheObjectifs(u); return f != null && f.isSignee(); }

    /** Étape courante sur 4 : objectifs fixés → auto-évaluation → évaluation N+1 → validation DCHT. */
    public int etape(Users u) {
        Evaluation e = fiche(u);
        if (e == null) return objectifsSignes(u) ? 1 : 0;
        return switch (e.getStatut()) {
            case Evaluation.VALIDEE -> 4;
            case Evaluation.EVALUEE -> 3;
            case Evaluation.SOUMISE, Evaluation.RETOURNEE -> 2;
            default -> objectifsSignes(u) ? 1 : 0;
        };
    }

    public String etapeLabel(Users u) {
        return switch (etape(u)) {
            case 4 -> "Validée DCHT";
            case 3 -> "Évaluée par le N+1";
            case 2 -> "Auto-évaluation soumise";
            case 1 -> "Objectifs signés";
            default -> nbObjectifs(u) > 0 ? "Objectifs en cours" : "Aucun objectif";
        };
    }

    public long getNbSansObjectifs() { return membres.stream().filter(u -> u.getEvaluable() == 1 && isDirect(u) && !objectifsSignes(u)).count(); }
    public long getNbPropositions() { return membres.stream().filter(this::isDirect).mapToInt(this::nbProposes).sum(); }
    public long getNbDirects() { return membres.stream().filter(this::isDirect).count(); }
    public long getNbEvaluables() { return membres.stream().filter(u -> u.getEvaluable() == 1 && !enEssai(u)).count(); }
    /** Collaborateurs directs en période d'essai à la date de la campagne. */
    public List<Users> getEnPeriodeEssai() { return membres.stream().filter(u -> isDirect(u) && u.getEvaluable() == 1 && enEssai(u)).toList(); }
    public long getNbInitiees() { return fiches.size(); }
    public long getNbATraiter() { return fiches.values().stream().filter(e -> Evaluation.SOUMISE.equals(e.getStatut())).count(); }
    public long getNbFinalisees() { return fiches.values().stream().filter(Evaluation::isFinalisee).count(); }
    public long getNbRetard() { return membres.stream().filter(this::enRetard).count(); }
    public int getTauxAvancement() { long ev = getNbEvaluables(); return ev == 0 ? 0 : (int) Math.round(100.0 * getNbFinalisees() / ev); }

    public String initiales(Users u) {
        String p = s(u.getPrenom()), n = s(u.getNom());
        return ((p.isEmpty() ? "" : p.substring(0, 1)) + (n.isEmpty() ? "" : n.substring(0, 1))).toUpperCase(Locale.ROOT);
    }

    // fenêtre « collaborateur non éligible »
    private Users alerteUser;
    private String alerteType, alerteTitre, alerteMessage, alerteLibelle, alerteValeur, alerteDetail, alerteLienLibelle, alerteLien;
    public Users getAlerteUser() { return alerteUser; }
    public String getAlerteType() { return alerteType; }
    public String getAlerteTitre() { return alerteTitre; }
    public String getAlerteMessage() { return alerteMessage; }
    public String getAlerteLibelle() { return alerteLibelle; }
    public String getAlerteValeur() { return alerteValeur; }
    public String getAlerteDetail() { return alerteDetail; }
    public String getAlerteLienLibelle() { return alerteLienLibelle; }
    public String getAlerteLien() { return alerteLien; }

    private void alerter(Users u, String type, String titre, String message, String libelle, String valeur, String detail, String lienLibelle, String lien) {
        alerteUser = u; alerteType = type; alerteTitre = titre; alerteMessage = message;
        alerteLibelle = libelle; alerteValeur = valeur; alerteDetail = detail; alerteLienLibelle = lienLibelle; alerteLien = lien;
        org.primefaces.PrimeFaces.current().ajax().update("dlgAlerte");
        org.primefaces.PrimeFaces.current().executeScript("PF('dlgAlerte').show()");
    }

    // actions
    public void initier(Users u) {
        // le droit d'initier découle du lien hiérarchique (relu en base par le service), pas d'un privilège de profil
        if (!periodeEssai.estN1Direct(loginBean.getUsers(), u)) throw new SecurityException("Seul le N+1 direct peut initier la fiche de ce collaborateur");
        if (campagne == null) { error("Aucune campagne ouverte", "La DCHT doit d'abord lancer une campagne."); return; }
        if (fiche(u) != null) return;
        if (essaiNonClos(u)) {
            cd.mura.hrx.entity.eval.EvaluationEssai ee = periodeEssai.findBySalarie(u.getId());
            alerter(u, "essai", "Évaluation de fin de période d'essai en cours",
                    "Ce collaborateur a une évaluation de fin de période d'essai (ou de CDD) qui n'est pas encore clôturée. Il ne pourra être intégré à la campagne qu'une fois cette évaluation validée par la DCHT et son engagement confirmé.",
                    "Statut de l'évaluation d'essai", ee == null ? "En cours" : ee.getStatutLabel(),
                    ee == null || ee.getDateFinEssai() == null ? "" : "échéance du " + ee.getDateFinEssai().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    "Ouvrir l'évaluation d'essai", ee == null ? "/ui/periodeEssai" : "/ui/periodeEssai?id=" + ee.getId());
            return;
        }
        if (enEssai(u)) {
            java.time.LocalDate f = finEssai(u);
            alerter(u, "essai", "En période d'essai",
                    "Un collaborateur en période d'essai ne peut pas être intégré à une campagne d'évaluation. Sa première évaluation sera celle de fin de période d'essai (fiche MAF 070), à réaliser à l'échéance.",
                    "Fin de la période d'essai", f.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)),
                    "dans " + java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), f) + " jours",
                    "Voir les périodes d'essai", "/ui/periodeEssai");
            return;
        }
        FicheObjectifs fo = ficheObjectifs(u);
        if (!objectifsSignes(u)) {
            FicheObjectifs f0 = ficheObjectifs(u);
            boolean aucun = f0 == null || f0.getNbValides() == 0;
            alerter(u, "objectifs", aucun ? "Aucun objectif fixé" : "Fiche d'objectifs non signée",
                    aucun ? "Un collaborateur ne peut être évalué que sur des objectifs fixés, transmis puis signés par lui et par vous. Fixez ses objectifs et transmettez-lui la fiche."
                          : "Un collaborateur n'est évalué que sur une fiche d'objectifs signée par les deux parties : d'abord par lui, puis par vous. Finalisez la fiche, puis intégrez-le à la campagne.",
                    "Fiche d'objectifs " + exercice, aucun ? "à fixer" : etatFicheObjectifs(u),
                    aucun ? (nbProposes(u) > 0 ? nbProposes(u) + " proposition(s) en attente de votre validation" : "aucun objectif validé")
                          : f0.getNbValides() + " objectif(s) validé(s)" + (f0.isSigneeSalarie() ? " · signée par le collaborateur" : f0.isTransmise() ? " · transmise, en attente de sa signature" : " · pas encore transmise"),
                    aucun ? "Fixer les objectifs" : "Ouvrir la fiche d'objectifs", "/ui/objectifs?salarie=" + u.getId() + "&exercice=" + exercice);
            return;
        }
        ModeleFiche m = modeleFicheDAO.pourCampagne(campagne, u);
        Evaluation e = evaluationService.initier(campagne, u, loginBean.getUsers(), m, fo);
        auditService.created("Évaluations", "Evaluation", e.getId(), "Initiation de la fiche d'évaluation", u.getNomComplet() + " : " + campagne.getLibelle());
        try { notifications.ficheInitiee(evaluationService.charger(e.getId())); } catch (Exception ex) { }
        cd.mura.hrx.utils.Dialogue.succes("Fiche initiée", u.getNomComplet() + " peut renseigner son auto-évaluation" + (m == null ? " (aucun modèle de compétences affecté)" : "") + ".");
        charger();
    }

    /** Collaborateurs directs, évaluables, pas encore intégrés à la campagne ouverte. */
    public List<Users> getCandidats() {
        if (campagne == null) return List.of();
        return membres.stream().filter(u -> isDirect(u) && u.getEvaluable() == 1 && !enEssai(u) && fiche(u) == null).toList();
    }
    /** Éligible à la campagne : au moins un objectif validé pour l'exercice (fiche de fixation en cours ou signée). */
    /** Éligible à la campagne : fiche d'objectifs de l'exercice signée par le collaborateur puis par le N+1. */
    public boolean aObjectifsEnCours(Users u) { return objectifsSignes(u); }
    /** Où en est la fiche d'objectifs (affiché tant qu'elle n'est pas signée par les deux parties). */
    public String etatFicheObjectifs(Users u) {
        FicheObjectifs f = ficheObjectifs(u);
        if (f == null || f.getNbValides() == 0) return "objectifs à fixer";
        if (f.isSignee()) return "fiche signée";
        if (f.getNbContestations() > 0) return "révision demandée";
        if (f.isSigneeSalarie()) return "à signer par vous";
        if (f.isTransmise()) return "chez le collaborateur";
        return f.getNbProposes() > 0 ? "propositions à traiter" : "à transmettre";
    }
    public List<Users> getCandidatsEligibles() { return getCandidats().stream().filter(this::aObjectifsEnCours).toList(); }
    public List<Users> getCandidatsBloques() { return getCandidats().stream().filter(u -> !aObjectifsEnCours(u)).toList(); }
    public void preparerSelection() { selectionIds = new ArrayList<>(getCandidatsEligibles().stream().map(Users::getId).toList()); }
    public void integrerSelection() {
        if (campagne == null) { error("Aucune campagne ouverte", "La DCHT doit d'abord lancer une campagne."); return; }
        int n = 0; List<String> refuses = new ArrayList<>();
        for (Users u : getCandidats()) {
            if (!selectionIds.contains(u.getId())) continue;
            if (!objectifsSignes(u)) { refuses.add(u.getNomComplet()); continue; }
            FicheObjectifs fo = ficheObjectifs(u);
            ModeleFiche m = modeleFicheDAO.pourCampagne(campagne, u);
            Evaluation e = evaluationService.initier(campagne, u, loginBean.getUsers(), m, fo);
            auditService.created("Évaluations", "Evaluation", e.getId(), "Intégration à la campagne", u.getNomComplet() + " : " + campagne.getLibelle());
            try { notifications.ficheInitiee(evaluationService.charger(e.getId())); } catch (Exception ex) { }
            n++;
        }
        if (!refuses.isEmpty()) error("Non intégré(s) : fiche d'objectifs non signée", String.join(", ", refuses) + " - la fiche d'objectifs doit d'abord être signée par le collaborateur puis par vous.");
        if (n == 0) { if (refuses.isEmpty()) error("Aucune sélection", "Cochez au moins un collaborateur."); return; }
        cd.mura.hrx.utils.Dialogue.succes(n + " collaborateur" + (n > 1 ? "s intégrés" : " intégré") + " à la campagne", "Chacun reçoit un e-mail et peut remplir son auto-évaluation depuis « Mes évaluations ».");
        org.primefaces.PrimeFaces.current().ajax().addCallbackParam("ok", true);
        charger();
    }

    private static String s(String v) { return v == null ? "" : v; }
    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
}
