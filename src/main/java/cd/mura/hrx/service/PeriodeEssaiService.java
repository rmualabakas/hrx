package cd.mura.hrx.service;

import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.dao.config.DirectionDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.dao.eval.ModeleFicheDAO;
import cd.mura.hrx.dao.eval.ObjectifDAO;
import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.eval.FicheObjectifs;
import cd.mura.hrx.entity.eval.Objectif;
import cd.mura.hrx.entity.config.App;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.Campagne;
import cd.mura.hrx.entity.eval.Critere;
import cd.mura.hrx.entity.eval.EvaluationEssai;
import cd.mura.hrx.entity.eval.EvaluationEssaiCritere;
import cd.mura.hrx.entity.eval.ModeleFiche;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Période d'essai des recrues : éligibilité aux campagnes, détection des fins de période d'essai
 * et cycle de l'évaluation de fin de période d'essai.
 * La durée (en mois) vient de Paramètres &gt; Évaluations.
 */
@Stateless
public class PeriodeEssaiService {

    /** Les fins de période d'essai plus anciennes ne sont plus signalées (recrues antérieures à la mise en service). */
    public static final int FENETRE_DETECTION_MOIS = 12;
    /** Les fins de période d'essai à venir dans ce délai sont annoncées. */
    public static final int PREAVIS_JOURS = 30;

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    @EJB private AppDAO appDAO;
    @EJB private ModeleFicheDAO modeleFicheDAO;
    @EJB private ObjectifDAO objectifDAO;
    @EJB private DirectionDAO directionDAO;
    @EJB private UserDAO userDAO;
    @EJB private cd.mura.hrx.dao.config.PrivilegeDAO privilegeDAO;

    // règles

    /** Les échéances de CDD à venir dans ce délai sont annoncées (la décision de renouvellement se prépare avant le terme). */
    public static final int PREAVIS_CDD_JOURS = 45;

    /** Grille II de la fiche MAF 070 : famille, sous-groupe, critère. */
    public static final String[][] GRILLE_MAF070 = {
            {"SAVOIR", null, "Acquis de la formation initiale"},
            {"SAVOIR", null, "Acquis de la formation continue"},
            {"SAVOIR", null, "Connaissance des procédures liées à son activité"},
            {"SAVOIR", null, "Connaissance du processus lié à son activité"},
            {"SAVOIR", "Connaissance des logiciels bureautiques / banque", "Word"},
            {"SAVOIR", "Connaissance des logiciels bureautiques / banque", "Excel"},
            {"SAVOIR", "Connaissance des logiciels bureautiques / banque", "PowerPoint"},
            {"SAVOIR", "Connaissance des logiciels bureautiques / banque", "Deltabank"},
            {"SAVOIR_FAIRE", null, "Organisation du travail"},
            {"SAVOIR_FAIRE", null, "Application des procédures"},
            {"SAVOIR_FAIRE", null, "Fiabilité des tâches exécutées"},
            {"SAVOIR_FAIRE", null, "Fiabilité des contrôles réalisés (autocontrôle)"},
            {"SAVOIR_FAIRE", "Reporting", "Fiabilité des informations"},
            {"SAVOIR_FAIRE", "Reporting", "Respect des délais"},
            {"SAVOIR_ETRE", null, "Autonomie"},
            {"SAVOIR_ETRE", null, "Initiative"},
            {"SAVOIR_ETRE", null, "Rigueur"},
            {"SAVOIR_ETRE", null, "Disponibilité"},
            {"SAVOIR_ETRE", null, "Courtoisie"},
            {"SAVOIR_ETRE", null, "Ponctualité"},
            {"SAVOIR_ETRE", null, "Travail d'équipe"},
            {"SAVOIR_ETRE", null, "Assiduité"},
            {"SAVOIR_ETRE", null, "Présentation (habillement, coiffure)"},
            {"VALEURS_GROUPE", null, "Travail"},
            {"VALEURS_GROUPE", null, "Intégrité"},
            {"VALEURS_GROUPE", null, "Tolérance"},
            {"VALEURS_GROUPE", null, "Responsabilité"},
            {"VALEURS_GROUPE", null, "Esprit d'équipe"},
            {"VALEURS_GROUPE", null, "Discipline"},
            {"DISCIPLINE", null, "Respect du Code de Déontologie"},
            {"DISCIPLINE", null, "Respect du Règlement Intérieur"},
    };

    /** Échéance qui appelle une évaluation : fin de période d'essai (CDD ou CDI) ou fin de CDD. */
    public record Echeance(Users salarie, String type, LocalDate date) implements java.io.Serializable {
        public String getTypeLabel() { return EvaluationEssai.typeLabel(type); }
        public boolean isFinCdd() { return EvaluationEssai.FIN_CDD.equals(type); }
        public long getJoursDepuis() { return java.time.temporal.ChronoUnit.DAYS.between(date, LocalDate.now()); }
        public long getJoursAvant() { return java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date); }
        public Users getSalarie() { return salarie; }
        public LocalDate getDate() { return date; }
        public String getType() { return type; }
    }

    public int dureeMois() {
        App a = appDAO.findFirst();
        return a == null ? 6 : a.getDureeEssai();
    }

    public static LocalDate local(Date d) {
        if (d == null) return null;
        if (d instanceof java.sql.Date sd) return sd.toLocalDate();
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /** Début du contrat en cours : date saisie, sinon date d'embauche. */
    public static LocalDate debutContrat(Users u) {
        if (u == null) return null;
        LocalDate d = local(u.getDateDebutContrat());
        return d != null ? d : local(u.getDateEmbauche());
    }

    /**
     * Fin de période d'essai du collaborateur : date fixée au contrat, sinon début du contrat + durée paramétrée ;
     * null si le contrat est sans période d'essai.
     */
    public static LocalDate finEssai(Users u, int mois) {
        if (u == null || u.isSansPeriodeEssai()) return null;
        LocalDate f = local(u.getDateFinEssai());
        if (f != null) return f;
        LocalDate e = debutContrat(u);
        return e == null ? null : e.plusMonths(mois);
    }

    /** Vrai si la recrue n'a pas encore atteint la fin de sa période d'essai à la date donnée. */
    public static boolean enEssai(Users u, LocalDate ref, int mois) {
        LocalDate fin = finEssai(u, mois);
        return fin != null && (ref == null ? LocalDate.now() : ref).isBefore(fin);
    }

    /** Date de référence d'une campagne pour l'éligibilité : son début, sinon aujourd'hui. */
    public static LocalDate reference(Campagne c) {
        return c != null && c.getDateDebut() != null ? c.getDateDebut() : LocalDate.now();
    }

    // détection

    private static boolean candidat(Users u) { return u != null && u.getStatut() == 1 && u.getEvaluable() == 1; }

    /** Échéances du collaborateur, évaluées ou non. */
    private static List<Echeance> echeances(Users u, int mois) {
        List<Echeance> l = new ArrayList<>();
        LocalDate fe = finEssai(u, mois);
        if (fe != null) l.add(new Echeance(u, u.isCdd() ? EvaluationEssai.FIN_ESSAI_CDD : EvaluationEssai.FIN_ESSAI_CDI, fe));
        LocalDate fc = u.isCdd() ? local(u.getDateFinContrat()) : null;
        if (fc != null) l.add(new Echeance(u, EvaluationEssai.FIN_CDD, fc));
        return l;
    }

    /**
     * Échéances déjà couvertes : une période d'essai est évaluée une fois (ou le collaborateur a déjà une évaluation
     * de campagne) ; chaque échéance de CDD a sa propre évaluation.
     */
    private Set<String> dejaCouvertes() {
        Set<String> s = new HashSet<>();
        for (Integer id : em.createQuery("select distinct e.salarie.id from Evaluation e", Integer.class).getResultList()) s.add("E" + id);
        for (Object[] r : em.createQuery("select ee.salarie.id, ee.typeEvaluation, ee.dateFinContrat from EvaluationEssai ee", Object[].class).getResultList()) {
            if (EvaluationEssai.FIN_CDD.equals(r[1])) s.add("C" + r[0] + "@" + r[2]);
            else s.add("E" + r[0]);
        }
        return s;
    }
    private static boolean couverte(Set<String> deja, Echeance e) {
        return e.isFinCdd() ? deja.contains("C" + e.salarie().getId() + "@" + e.date()) : deja.contains("E" + e.salarie().getId());
    }

    /** Collaborateurs en cours de période d'essai ou de CDD dont l'échéance est encore lointaine (au-delà du préavis). */
    public List<Echeance> enCours(Collection<Users> population) {
        int mois = dureeMois();
        LocalDate auj = LocalDate.now();
        Set<String> deja = dejaCouvertes();
        List<Echeance> l = new ArrayList<>();
        for (Users u : population) {
            if (!candidat(u)) continue;
            for (Echeance e : echeances(u, mois))
                if (!couverte(deja, e) && e.date().isAfter(auj.plusDays(e.isFinCdd() ? PREAVIS_CDD_JOURS : PREAVIS_JOURS))) l.add(e);
        }
        l.sort(Comparator.comparing(Echeance::date));
        return l;
    }

    private List<Echeance> detecter(Collection<Users> population, boolean atteintes) {
        int mois = dureeMois();
        LocalDate auj = LocalDate.now(), limite = auj.minusMonths(FENETRE_DETECTION_MOIS);
        Set<String> deja = dejaCouvertes();
        List<Echeance> l = new ArrayList<>();
        for (Users u : population) {
            if (!candidat(u)) continue;
            for (Echeance e : echeances(u, mois)) {
                if (couverte(deja, e)) continue;
                boolean ok = atteintes ? !e.date().isAfter(auj) && !e.date().isBefore(limite)
                        : e.date().isAfter(auj) && !e.date().isAfter(auj.plusDays(e.isFinCdd() ? PREAVIS_CDD_JOURS : PREAVIS_JOURS));
                if (ok) l.add(e);
            }
        }
        l.sort(Comparator.comparing(Echeance::date));
        return l;
    }

    /** Échéances atteintes (fin d'essai ou fin de CDD) sans évaluation : une évaluation est attendue. */
    public List<Echeance> aEvaluer(Collection<Users> population) { return detecter(population, true); }

    /** Échéances à venir : fins d'essai sous {@value #PREAVIS_JOURS} jours, fins de CDD sous {@value #PREAVIS_CDD_JOURS} jours. */
    public List<Echeance> prochaines(Collection<Users> population) { return detecter(population, false); }

    // lecture

    public EvaluationEssai charger(int id) {
        List<EvaluationEssai> l = em.createQuery("""
                select distinct e from EvaluationEssai e join fetch e.salarie
                left join fetch e.evaluateur left join fetch e.campagne left join fetch e.criteres where e.id = :id""", EvaluationEssai.class)
                .setParameter("id", id).getResultList();
        if (l.isEmpty()) return null;
        initDirections(l);
        return l.get(0);
    }

    /** Évaluation la plus récente du collaborateur (essai ou CDD), ou null. */
    public EvaluationEssai findBySalarie(int salarieId) {
        List<EvaluationEssai> l = em.createQuery("select e from EvaluationEssai e left join fetch e.evaluateur where e.salarie.id = :s order by e.id desc", EvaluationEssai.class)
                .setParameter("s", salarieId).setMaxResults(1).getResultList();
        return l.isEmpty() ? null : l.get(0);
    }

    /** Toutes les évaluations (evaluateurId null) ou celles d'un évaluateur. */
    public List<EvaluationEssai> lister(Integer evaluateurId) {
        String q = "select e from EvaluationEssai e join fetch e.salarie left join fetch e.evaluateur left join fetch e.campagne"
                + (evaluateurId == null ? "" : " where e.evaluateur.id = :ev") + " order by e.dateCreation desc, e.id desc";
        var query = em.createQuery(q, EvaluationEssai.class);
        if (evaluateurId != null) query.setParameter("ev", evaluateurId);
        List<EvaluationEssai> l = query.getResultList();
        initDirections(l);
        return l;
    }

    /** Évaluations des collaborateurs des directions dirigées par cet utilisateur (avis du directeur). */
    public List<EvaluationEssai> listerPourDirecteur(int directeurId) {
        List<Integer> dirs = directionDAO.findDirigeesPar(directeurId).stream().map(Direction::getId).toList();
        if (dirs.isEmpty()) return new ArrayList<>();
        List<EvaluationEssai> l = em.createQuery("select e from EvaluationEssai e join fetch e.salarie left join fetch e.evaluateur left join fetch e.campagne where e.salarie.direction.id in :d order by e.dateCreation desc, e.id desc", EvaluationEssai.class)
                .setParameter("d", dirs).getResultList();
        initDirections(l);
        return l;
    }

    /** Directeur de la direction du collaborateur (celui qui donne son avis), ou null si la direction n'a pas de directeur. */
    public Users directeurAttendu(EvaluationEssai e) {
        Users s = e == null ? null : userDAO.findAccountById(e.getSalarie().getId());
        Direction d = s == null || s.getDirection() == null ? null : directionDAO.find(s.getDirection().getId());
        return d == null || d.getDirigePar() <= 0 ? null : userDAO.findAccountById(d.getDirigePar());
    }

    /** N+2 : supérieur hiérarchique de l'évaluateur, ou null. */
    public Users n2Attendu(EvaluationEssai e) {
        if (e == null || e.getEvaluateur() == null) return null;
        Users n1 = userDAO.findAccountById(e.getEvaluateur().getId());
        return n1 == null ? null : n1.getSuperieurHierarchique();
    }

    /** Direction du salarié chargée dans la transaction (le JPQL strict refuse les fetch join aliasés). */
    private static void initDirections(List<EvaluationEssai> l) {
        for (EvaluationEssai e : l) if (e.getSalarie() != null) org.hibernate.Hibernate.initialize(e.getSalarie().getDirection());
    }

    // contrôles d'accès (appliqués ici, côté serveur, quel que soit l'écran appelant)

    private static final int ROOT_PROFIL_ID = 1;

    /** DCHT : profil root ou privilège EVAL_DCHT / ESSAI_DCHT actif sur le profil, relu en base. */
    public boolean estDcht(Users u) {
        Users x = u == null ? null : em.find(Users.class, u.getId());
        if (x == null || x.getProfil() == null) return false;
        if (x.getProfil().getId() == ROOT_PROFIL_ID) return true;
        for (cd.mura.hrx.entity.config.Privilege p : privilegeDAO.findAllByProfil(x.getProfil()))
            if (p != null && p.getStatut() == 1 && ("EVAL_DCHT".equals(p.getCode()) || "ESSAI_DCHT".equals(p.getCode()))) return true;
        return false;
    }

    /** N+1 direct du collaborateur (relu en base). */
    public boolean estN1Direct(Users acteur, Users salarie) {
        if (acteur == null || salarie == null) return false;
        Users s = em.find(Users.class, salarie.getId());
        return s != null && s.getSuperieurHierarchique() != null && s.getSuperieurHierarchique().getId() == acteur.getId() && s.getId() != acteur.getId();
    }

    /** Supérieur de l'évaluateur, à n'importe quel niveau au-dessus (N+2 et plus). */
    public boolean estAuDessusEvaluateur(Users acteur, EvaluationEssai e) {
        if (acteur == null || e == null || e.getEvaluateur() == null) return false;
        Users cur = em.find(Users.class, e.getEvaluateur().getId());
        cur = cur == null ? null : cur.getSuperieurHierarchique();
        Set<Integer> vus = new HashSet<>();
        while (cur != null && vus.add(cur.getId())) {
            if (cur.getId() == acteur.getId()) return true;
            cur = cur.getSuperieurHierarchique();
        }
        return false;
    }

    /**
     * Date à partir de laquelle l'évaluation peut être ouverte : fin de la période d'essai (elle doit être atteinte),
     * ou {@value #PREAVIS_CDD_JOURS} jours avant le terme d'un CDD (la décision de renouvellement se prépare avant).
     */
    public static LocalDate ouvrableLe(Echeance e) {
        return e.isFinCdd() ? e.date().minusDays(PREAVIS_CDD_JOURS) : e.date();
    }
    public static boolean ouvrable(Echeance e) { return !LocalDate.now().isBefore(ouvrableLe(e)); }

    private static void refuser(String m) { throw new SecurityException(m); }

    /** Relit l'évaluation en base : les contrôles ne se fient jamais à l'objet transmis par l'écran. */
    private EvaluationEssai enBase(EvaluationEssai e) {
        EvaluationEssai x = e == null ? null : em.find(EvaluationEssai.class, e.getId());
        if (x == null) throw new IllegalArgumentException("Évaluation introuvable");
        return x;
    }
    private void exigerEvaluateur(EvaluationEssai e, Users acteur) {
        EvaluationEssai x = enBase(e);
        if (acteur == null || x.getEvaluateur() == null || x.getEvaluateur().getId() != acteur.getId() || x.getSalarie().getId() == acteur.getId())
            refuser("Seul l'évaluateur désigné (N+1 direct du collaborateur) peut renseigner cette évaluation");
    }
    private void exigerDcht(EvaluationEssai e, Users acteur) {
        EvaluationEssai x = enBase(e);
        if (!estDcht(acteur)) refuser("Action réservée à la DCHT");
        if (x.getSalarie().getId() == acteur.getId()) refuser("Vous ne pouvez pas statuer sur votre propre évaluation");
        if (x.getEvaluateur() != null && x.getEvaluateur().getId() == acteur.getId())
            refuser("L'évaluateur ne peut pas valider sa propre évaluation : un autre membre de la DCHT doit statuer");
    }

    // cycle

    /** Ouvre l'évaluation (fiche MAF 070) pour l'échéance donnée : grille d'intégration et objectifs fixés. */
    public EvaluationEssai ouvrir(Users salarie, String type, Users auteur) {
        if (salarie == null || salarie.getId() <= 0) throw new IllegalArgumentException("Collaborateur requis");
        Users s = em.find(Users.class, salarie.getId());
        int mois = dureeMois();
        Echeance ech = echeances(s, mois).stream().filter(x -> x.type().equals(type)).findFirst().orElse(null);
        if (ech == null) throw new IllegalStateException(EvaluationEssai.FIN_CDD.equals(type) ? "Aucune échéance de CDD n'est renseignée pour ce collaborateur" : "Ce collaborateur n'a pas de période d'essai");
        if (auteur == null || !estN1Direct(auteur, s))
            refuser("Seul le N+1 direct du collaborateur peut ouvrir son évaluation de fin de période d'essai ou de CDD");
        if (!ouvrable(ech))
            throw new IllegalStateException(ech.isFinCdd()
                    ? "L'évaluation de fin de CDD peut être ouverte à partir du " + ouvrableLe(ech).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " (" + PREAVIS_CDD_JOURS + " jours avant le terme)"
                    : "La période d'essai se termine le " + ech.date().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " : l'évaluation ne peut être ouverte qu'à cette date. Si la fin d'essai a été avancée, corrigez d'abord la date sur la fiche du collaborateur.");
        if (couverte(dejaCouvertes(), ech)) throw new IllegalStateException("Une évaluation existe déjà pour cette échéance (" + ech.getTypeLabel() + ")");
        if (!em.createQuery("select e.id from EvaluationEssai e where e.salarie.id = :s and e.statut <> :v", Integer.class)
                .setParameter("s", s.getId()).setParameter("v", EvaluationEssai.VALIDEE).getResultList().isEmpty())
            throw new IllegalStateException("Une évaluation est déjà en cours pour ce collaborateur : terminez-la avant d'en ouvrir une autre");
        EvaluationEssai e = new EvaluationEssai();
        e.setSalarie(s);
        e.setEvaluateur(s.getSuperieurHierarchique());
        e.setTypeEvaluation(type);
        e.setDureeMois(mois);
        figerContrat(e, s, mois);
        e.setCreePar(auteur == null ? 0 : auteur.getId());
        e.setDateMaj(new Date());
        appliquerGrille(e);
        e.setDateEntretien(LocalDate.now());
        ajouterObjectifs(e);
        em.persist(e);
        em.flush();
        return e;
    }

    /** Recopie le contrat du collaborateur (en-tête et section III de la fiche). */
    private static void figerContrat(EvaluationEssai e, Users s, int mois) {
        e.setDateEmbauche(local(s.getDateEmbauche()));
        e.setDateDebutContrat(debutContrat(s));
        e.setDateFinEssai(finEssai(s, mois));
        e.setDateFinContrat(s.isCdd() ? local(s.getDateFinContrat()) : null);
        e.setDateLimiteContrat(s.isCdd() ? local(s.getDateLimiteContrat()) : null);
        e.setNbProrogations(s.isCdd() ? s.getNbProrogations() : null);
    }

    /** Remplace les critères (hors objectifs) par la grille II de la fiche MAF 070. */
    private static void appliquerGrille(EvaluationEssai e) {
        e.getCriteres().removeIf(c -> !c.isObjectif());
        int ordre = 0;
        for (String[] g : GRILLE_MAF070) e.getCriteres().add(critere(e, g[2], g[1], g[0], ordre++));
        e.setGrille(EvaluationEssai.GRILLE_MAF070);
    }

    /**
     * Objectifs validés de la fiche d'objectifs du collaborateur (exercice de l'échéance évaluée, sinon de l'embauche),
     * repris dans « Rappel des objectifs » : activité, objectif, indicateur et objectif (O) par trimestre.
     * Les objectifs déjà repris ne sont pas dupliqués.
     */
    private int ajouterObjectifs(EvaluationEssai e) {
        FicheObjectifs f = null;
        LocalDate[] refs = e.isFinCdd() ? new LocalDate[]{e.getDateFinContrat(), e.getDateDebutContrat(), LocalDate.now()}
                : new LocalDate[]{e.getDateEmbauche(), e.getDateFinEssai(), LocalDate.now()};
        for (LocalDate ref : refs) {
            if (ref == null) continue;
            f = objectifDAO.findFiche(e.getSalarie().getId(), ref.getYear());
            if (f != null && f.getNbValides() > 0) break;
        }
        if (f == null) return 0;
        Set<String> deja = new HashSet<>();
        int ordre = 1000;
        for (EvaluationEssaiCritere c : e.getCriteres()) { if (c.isObjectif()) { deja.add(c.getLibelle()); ordre = Math.max(ordre, c.getOrdre() + 1); } }
        int n = 0;
        for (Objectif o : f.getObjectifs()) {
            if (!o.isValide() || o.getLibelle() == null || o.getLibelle().isBlank()) continue;
            String lib = court(o.getLibelle(), 255);
            if (!deja.add(lib)) continue;
            EvaluationEssaiCritere c = critere(e, lib, null, EvaluationEssaiCritere.OBJECTIF, ordre++);
            c.setActivite(o.getActivite() == null || o.getActivite().isBlank() ? null : court(o.getActivite(), 255));
            c.setIndicateur(o.getIndicateur());
            String cible = o.getCible() == null || o.getCible().isBlank() ? "✓" : court(o.getCible(), 60);
            c.setO1(o.isT1() ? cible : null); c.setO2(o.isT2() ? cible : null); c.setO3(o.isT3() ? cible : null); c.setO4(o.isT4() ? cible : null);
            e.getCriteres().add(c);
            n++;
        }
        return n;
    }

    private static String court(String v, int max) { return v.length() > max ? v.substring(0, max - 1) + "…" : v; }

    /**
     * Mise à jour d'une évaluation en cours : passage à la fiche MAF 070 des évaluations ouvertes sur l'ancienne grille
     * (tant qu'aucun critère n'est apprécié) et reprise des objectifs fixés depuis l'ouverture.
     * Retourne vrai si l'évaluation a changé.
     */
    public boolean synchroniser(int id) {
        EvaluationEssai e = em.find(EvaluationEssai.class, id);
        if (e == null || !e.isEnCours()) return false;
        boolean change = false;
        if (!e.isMaf070() && e.getNbNotes() == 0) {
            Users s = e.getSalarie();
            if (e.getTypeEvaluation() == null) e.setTypeEvaluation(s.isCdd() ? EvaluationEssai.FIN_ESSAI_CDD : EvaluationEssai.FIN_ESSAI_CDI);
            figerContrat(e, s, e.getDureeMois() > 0 ? e.getDureeMois() : dureeMois());
            e.getCriteres().removeIf(EvaluationEssaiCritere::isObjectif);
            appliquerGrille(e);
            e.setDecision(null);
            change = true;
        }
        if (ajouterObjectifs(e) > 0) change = true;
        if (change) e.setDateMaj(new Date());
        return change;
    }

    private static EvaluationEssaiCritere critere(EvaluationEssai e, String libelle, String description, String famille, int ordre) {
        EvaluationEssaiCritere c = new EvaluationEssaiCritere();
        c.setEvaluation(e); c.setLibelle(libelle); c.setDescription(description); c.setFamille(famille); c.setOrdre(ordre);
        return c;
    }

    public EvaluationEssai enregistrer(EvaluationEssai e, Integer campagneId, Users acteur) {
        exigerEvaluateur(e, acteur);
        requireState(enBase(e), EvaluationEssai.EN_COURS);
        return sauver(e, campagneId);
    }

    private EvaluationEssai sauver(EvaluationEssai e, Integer campagneId) {
        e.setCampagne(campagneId == null ? null : em.getReference(Campagne.class, campagneId));
        if (!EvaluationEssai.PROLONGER.equals(e.getDecision())) e.setProlongationMois(null);
        if (!EvaluationEssai.RENOUVELER.equals(e.getDecision())) e.setNouvelleEcheance(null);
        e.setDateMaj(new Date());
        return em.merge(e);
    }

    public EvaluationEssai transmettre(EvaluationEssai e, Integer campagneId, Users acteur) {
        exigerEvaluateur(e, acteur);
        requireState(enBase(e), EvaluationEssai.EN_COURS);
        if (e.getNbNotesCompetences() < e.getCriteresCompetences().size())
            throw new IllegalArgumentException("Notez chaque critère de l'intégration (TB, B, P, I ou PC) avant de transmettre : " + (e.getCriteresCompetences().size() - e.getNbNotesCompetences()) + " restant(s)");
        if (e.isMaf070() && (e.getCommentaireEvaluateur() == null || e.getCommentaireEvaluateur().isBlank()))
            throw new IllegalArgumentException("Rédigez l'appréciation globale avant de transmettre");
        if (e.getDecision() == null || e.getDecision().isBlank() || (e.isMaf070() && !e.getDecisionsPossibles().contains(e.getDecision())))
            throw new IllegalArgumentException("Indiquez la suite à donner au contrat de travail");
        if (EvaluationEssai.PROLONGER.equals(e.getDecision()) && (e.getProlongationMois() == null || e.getProlongationMois() < 1))
            throw new IllegalArgumentException("Indiquez la durée de la prolongation");
        boolean motifRequis = e.isFinCdd() || EvaluationEssai.FIN_CONTRAT.equals(e.getDecision());
        if (motifRequis && (e.getMotifDecision() == null || e.getMotifDecision().isBlank()))
            throw new IllegalArgumentException("Précisez le motif de la décision");
        if (EvaluationEssai.RENOUVELER.equals(e.getDecision())) {
            if (e.getNouvelleEcheance() == null) throw new IllegalArgumentException("Indiquez la nouvelle échéance proposée pour le CDD");
            if (e.getDateFinContrat() != null && !e.getNouvelleEcheance().isAfter(e.getDateFinContrat()))
                throw new IllegalArgumentException("La nouvelle échéance doit être postérieure à l'échéance actuelle du CDD");
            if (e.getDateLimiteContrat() != null && e.getNouvelleEcheance().isAfter(e.getDateLimiteContrat()))
                throw new IllegalArgumentException("La nouvelle échéance dépasse la date limite légale du contrat (" + e.getDateLimiteContrat().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ")");
        }
        e.setStatut(EvaluationEssai.TRANSMISE); e.setDateTransmission(new Date()); e.setMotifRetour(null);
        e.setVisaN2(null); e.setN2(null); e.setDateVisaN2(null);
        e.setAvisDirecteur(null); e.setDirecteur(null); e.setDateAvisDirecteur(null);
        if (e.getDateEntretien() == null) e.setDateEntretien(LocalDate.now());
        return sauver(e, campagneId);
    }

    /** Visa facultatif du N+2. */
    public EvaluationEssai viser(EvaluationEssai e, Users n2, String visa) {
        requireState(enBase(e), EvaluationEssai.TRANSMISE);
        EvaluationEssai x = enBase(e);
        if (n2 == null || x.getSalarie().getId() == n2.getId() || !estAuDessusEvaluateur(n2, x))
            refuser("Seul un supérieur hiérarchique de l'évaluateur (N+2 ou au-dessus) peut viser cette évaluation");
        if (e.getDateVisaN2() != null) throw new IllegalStateException("Le visa du N+2 est déjà enregistré");
        e.setN2(em.getReference(Users.class, n2.getId())); e.setVisaN2(visa); e.setDateVisaN2(new Date()); e.setDateMaj(new Date());
        return em.merge(e);
    }

    /** Avis du directeur : requis avant la validation DCHT. */
    public EvaluationEssai avisDirecteur(EvaluationEssai e, Users directeur, String avis) {
        requireState(enBase(e), EvaluationEssai.TRANSMISE);
        EvaluationEssai x = enBase(e);
        Users attendu = directeurAttendu(x);
        boolean ok = directeur != null && x.getSalarie().getId() != directeur.getId()
                && (attendu != null ? attendu.getId() == directeur.getId() : estDcht(directeur));
        if (!ok) refuser(attendu != null ? "Seul le directeur de la direction (" + attendu.getNomComplet() + ") peut donner cet avis"
                : "Aucun directeur n'est désigné pour cette direction : l'avis est saisi par la DCHT");
        if (e.getDateAvisDirecteur() != null) throw new IllegalStateException("L'avis du directeur est déjà enregistré");
        if (avis == null || avis.isBlank()) throw new IllegalArgumentException("Saisissez l'avis du directeur");
        e.setDirecteur(em.getReference(Users.class, directeur.getId())); e.setAvisDirecteur(avis); e.setDateAvisDirecteur(new Date()); e.setDateMaj(new Date());
        return em.merge(e);
    }

    public EvaluationEssai valider(EvaluationEssai e, Users acteur) {
        exigerDcht(e, acteur);
        requireState(enBase(e), EvaluationEssai.TRANSMISE);
        if (e.getDateAvisDirecteur() == null) throw new IllegalStateException("L'avis du directeur est attendu avant la validation");
        e.setStatut(EvaluationEssai.VALIDEE); e.setDateValidation(new Date()); e.setDateMaj(new Date());
        EvaluationEssai v = em.merge(e);
        appliquerAuContrat(v);
        return v;
    }

    /**
     * Report de la décision validée sur le contrat du collaborateur : passage en CDI, ou renouvellement du CDD
     * (nouvelle période, prorogation comptée). Retourne la description du changement, ou null.
     */
    private String appliquerAuContrat(EvaluationEssai e) {
        Users s = em.find(Users.class, e.getSalarie().getId());
        if (s == null || e.getDecision() == null) return null;
        switch (e.getDecision()) {
            case EvaluationEssai.CONFIRMER_CDI -> {
                if (!s.isCdd() && s.getTypeContrat() != null) return null;
                if (s.isCdd() && e.isFinCdd() && e.getDateFinContrat() != null) s.setDateDebutContrat(java.sql.Date.valueOf(e.getDateFinContrat().plusDays(1)));
                s.setTypeContrat(Users.CDI); s.setDateFinContrat(null); s.setDateLimiteContrat(null); s.setNbProrogations(0);
                return "passage en CDI";
            }
            case EvaluationEssai.RENOUVELER -> {
                if (!s.isCdd() || e.getNouvelleEcheance() == null || e.getDateFinContrat() == null) return null;
                s.setDateDebutContrat(java.sql.Date.valueOf(e.getDateFinContrat().plusDays(1)));
                s.setDateFinContrat(java.sql.Date.valueOf(e.getNouvelleEcheance()));
                s.setNbProrogations(s.getNbProrogations() + 1);
                return "CDD renouvelé jusqu'au " + e.getNouvelleEcheance().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            }
            default -> { return null; }
        }
    }

    public EvaluationEssai retourner(EvaluationEssai e, String motif, Users acteur) {
        exigerDcht(e, acteur);
        requireState(enBase(e), EvaluationEssai.TRANSMISE);
        if (motif == null || motif.isBlank()) throw new IllegalArgumentException("Le motif de retour est requis");
        e.setStatut(EvaluationEssai.EN_COURS); e.setMotifRetour(motif); e.setDateTransmission(null); e.setDateMaj(new Date());
        return em.merge(e);
    }

    /** Commentaire du collaborateur, seul champ qu'il peut modifier (jusqu'à la validation). */
    public void commenter(int id, String texte, Users acteur) {
        EvaluationEssai e = em.find(EvaluationEssai.class, id);
        if (e == null) throw new IllegalArgumentException("Évaluation introuvable");
        if (acteur == null || e.getSalarie().getId() != acteur.getId()) refuser("Seul le collaborateur évalué peut commenter son évaluation");
        if (e.isValidee()) throw new IllegalStateException("L'évaluation est validée");
        if (e.getDateSignatureSalarie() != null) throw new IllegalStateException("Votre commentaire est signé : il n'est plus modifiable");
        e.setCommentaireSalarie(texte); e.setDateMaj(new Date());
    }

    /** Signature du collaborateur (prise de connaissance), avec son commentaire éventuel. */
    public void signerSalarie(int id, String texte, Users acteur) {
        EvaluationEssai e = em.find(EvaluationEssai.class, id);
        if (e == null) throw new IllegalArgumentException("Évaluation introuvable");
        if (acteur == null || e.getSalarie().getId() != acteur.getId()) refuser("Seul le collaborateur évalué peut signer son évaluation");
        if (e.isEnCours()) throw new IllegalStateException("Le collaborateur signe une fois l'évaluation transmise par son N+1");
        if (e.getDateSignatureSalarie() != null) throw new IllegalStateException("Vous avez déjà signé cette évaluation");
        e.setCommentaireSalarie(texte); e.setDateSignatureSalarie(new Date()); e.setDateMaj(new Date());
    }

    public void supprimer(int id, Users acteur) {
        EvaluationEssai e = em.find(EvaluationEssai.class, id);
        if (e == null) return;
        if (!estDcht(acteur)) refuser("Suppression réservée à la DCHT");
        if (e.getSalarie().getId() == acteur.getId()) refuser("Vous ne pouvez pas supprimer votre propre évaluation");
        if (e.isValidee()) throw new IllegalStateException("Une évaluation validée ne peut pas être supprimée");
        em.remove(e);
    }

    private static void requireState(EvaluationEssai e, String... etats) {
        if (e == null || e.getId() <= 0) throw new IllegalArgumentException("Évaluation requise");
        for (String s : etats) if (s.equals(e.getStatut())) return;
        throw new IllegalStateException("Action impossible au statut « " + e.getStatutLabel() + " »");
    }
}
