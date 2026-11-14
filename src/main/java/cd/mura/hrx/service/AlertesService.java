package cd.mura.hrx.service;

import cd.mura.hrx.dao.config.DirectionDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.Campagne;
import cd.mura.hrx.entity.eval.Evaluation;
import cd.mura.hrx.entity.eval.EvaluationEssai;
import cd.mura.hrx.entity.eval.Relance;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Alertes graduées aux acteurs des évaluations.
 * <ul>
 *   <li>Avant l'échéance : rappel à J-{@value #RAPPEL_1} et à J-{@value #RAPPEL_2}.</li>
 *   <li>Après l'échéance : relance tous les {@value #INTERVALLE_RELANCE} jours. Le niveau monte avec le nombre de
 *       relances : 1 = relance, 2 = urgent, {@value #SEUIL_CRITIQUE} et plus = critique.</li>
 *   <li>Au niveau critique, le message annonce qu'une fiche de non-conformité sera établie à l'encontre du
 *       responsable ; son supérieur hiérarchique et la DCHT reçoivent une copie.</li>
 * </ul>
 * Un seul e-mail par destinataire et par passage, qui regroupe toutes ses actions en attente.
 */
@Stateless
public class AlertesService {

    private static final Logger LOG = Logger.getLogger(AlertesService.class.getName());

    public static final int RAPPEL_1 = 7, RAPPEL_2 = 2, INTERVALLE_RELANCE = 3, SEUIL_CRITIQUE = 3;
    /** Délai laissé au directeur pour donner son avis sur une évaluation de fin de période d'essai. */
    public static final int DELAI_AVIS_DIRECTEUR_JOURS = 7;

    public static final String T_OBJECTIFS = "OBJECTIFS", T_AUTO_EVAL = "AUTO_EVAL", T_EVAL_N1 = "EVAL_N1", T_AVIS_DIRECTEUR = "AVIS_DIRECTEUR",
            T_ESSAI_N1 = "ESSAI_N1", T_ESSAI_DIRECTEUR = "ESSAI_DIRECTEUR";

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    @EJB private UserDAO userDAO;
    @EJB private DirectionDAO directionDAO;
    @EJB private SuiviObjectifsService suiviObjectifs;
    @EJB private PeriodeEssaiService periodeEssai;
    @EJB private NotificationService notifications;

    /** Action attendue d'un acteur à une date. */
    public record Attendu(String type, int refId, int salarieId, int destinataireId, String libelle, String lien, LocalDate echeance) {
        String cle() { return type + ":" + refId + ":" + destinataireId; }
    }

    // ------------------------------------------------------------------ inventaire des actions attendues

    /** Toutes les actions attendues aujourd'hui, tous acteurs confondus. */
    public List<Attendu> attendus(boolean objectifs) {
        List<Attendu> l = new ArrayList<>();
        try { if (objectifs) l.addAll(objectifs()); } catch (Exception e) { LOG.log(Level.WARNING, "HRX alertes : objectifs", e); }
        try { l.addAll(campagnes()); } catch (Exception e) { LOG.log(Level.WARNING, "HRX alertes : campagnes", e); }
        try { l.addAll(periodesEssai()); } catch (Exception e) { LOG.log(Level.WARNING, "HRX alertes : périodes d'essai", e); }
        return l;
    }

    /** Fixation des objectifs : fiche non signée → N+1, échéance fin janvier (ou un mois après l'arrivée d'une recrue). */
    private List<Attendu> objectifs() {
        int exercice = LocalDate.now().getYear();
        List<Attendu> l = new ArrayList<>();
        for (SuiviObjectifsService.Ligne x : suiviObjectifs.etat(exercice, null)) {
            if (x.isSignee() || x.n1() == null) continue;
            LocalDate effet = LocalDate.of(exercice, 1, 1);
            LocalDate emb = local(x.salarie().getDateEmbauche());
            if (emb != null && emb.getYear() == exercice) effet = emb;
            l.add(new Attendu(T_OBJECTIFS, x.salarie().getId(), x.salarie().getId(), x.n1().getId(),
                    "Fixer et faire signer les objectifs " + exercice + " de " + x.salarie().getNomComplet(),
                    "/ui/objectifs?salarie=" + x.salarie().getId() + "&exercice=" + exercice, effet.plusMonths(1)));
        }
        return l;
    }

    /** Évaluations des campagnes ouvertes : auto-évaluation (salarié), évaluation (N+1), avis (directeur). Échéance = fin de campagne. */
    private List<Attendu> campagnes() {
        List<Evaluation> evals = em.createQuery("""
                select e from Evaluation e join fetch e.campagne join fetch e.salarie left join fetch e.evaluateur
                where e.campagne.statut = :o and e.statut <> :v and e.campagne.dateFin is not null""", Evaluation.class)
                .setParameter("o", Campagne.OUVERTE).setParameter("v", Evaluation.VALIDEE).getResultList();
        List<Attendu> l = new ArrayList<>();
        for (Evaluation e : evals) {
            String lien = "/ui/fiche?id=" + e.getId(), qui = e.getSalarie().getNomComplet(), camp = e.getCampagne().getLibelle();
            LocalDate fin = e.getCampagne().getDateFin();
            switch (e.getStatut()) {
                case Evaluation.INITIEE, Evaluation.RETOURNEE ->
                        l.add(new Attendu(T_AUTO_EVAL, e.getId(), e.getSalarie().getId(), e.getSalarie().getId(), "Renseigner et soumettre votre auto-évaluation (" + camp + ")", lien, fin));
                case Evaluation.SOUMISE -> {
                    if (e.getEvaluateur() != null)
                        l.add(new Attendu(T_EVAL_N1, e.getId(), e.getSalarie().getId(), e.getEvaluateur().getId(), "Évaluer " + qui + " (" + camp + ")", lien, fin));
                }
                case Evaluation.EVALUEE -> {
                    Users dir = e.getDateAvisDirecteur() == null ? directeurDe(e.getSalarie()) : null;
                    if (dir != null)
                        l.add(new Attendu(T_AVIS_DIRECTEUR, e.getId(), e.getSalarie().getId(), dir.getId(), "Donner votre avis sur l'évaluation de " + qui + " (" + camp + ")", lien, fin));
                }
                default -> { }
            }
        }
        return l;
    }

    /** Fins de période d'essai : évaluation à ouvrir et à transmettre (N+1, échéance = fin d'essai), puis avis du directeur. */
    private List<Attendu> periodesEssai() {
        List<Attendu> l = new ArrayList<>();
        List<Users> population = userDAO.findActifsAvecDirection();
        List<PeriodeEssaiService.Echeance> aOuvrir = new ArrayList<>(periodeEssai.aEvaluer(population));
        aOuvrir.addAll(periodeEssai.prochaines(population));
        for (PeriodeEssaiService.Echeance ech : aOuvrir) {
            Users u = ech.salarie(), n1 = u.getSuperieurHierarchique();
            if (n1 == null) continue;
            l.add(new Attendu(T_ESSAI_N1, ech.isFinCdd() ? -u.getId() : u.getId(), u.getId(), n1.getId(),
                    (ech.isFinCdd() ? "Réaliser l'évaluation de fin de CDD de " : "Réaliser l'évaluation de fin de période d'essai de ") + u.getNomComplet(),
                    "/ui/periodeEssai", ech.date()));
        }
        for (EvaluationEssai e : periodeEssai.lister(null)) {
            if (e.isEnCours() && e.getEvaluateur() != null)
                l.add(new Attendu(T_ESSAI_N1, e.isFinCdd() ? -e.getSalarie().getId() : e.getSalarie().getId(), e.getSalarie().getId(), e.getEvaluateur().getId(),
                        "Terminer et transmettre l'évaluation (" + e.getTypeLabel() + ") de " + e.getSalarie().getNomComplet(), "/ui/periodeEssai?id=" + e.getId(),
                        e.getEcheance() == null ? LocalDate.now() : e.getEcheance()));
            else if (e.isTransmise() && e.getDateAvisDirecteur() == null) {
                Users dir = directeurDe(e.getSalarie());
                LocalDate trans = e.getDateTransmission() == null ? LocalDate.now() : local(e.getDateTransmission());
                if (dir != null)
                    l.add(new Attendu(T_ESSAI_DIRECTEUR, e.getId(), e.getSalarie().getId(), dir.getId(),
                            "Donner votre avis sur l'évaluation (" + e.getTypeLabel() + ") de " + e.getSalarie().getNomComplet(), "/ui/periodeEssai?id=" + e.getId(),
                            trans.plusDays(DELAI_AVIS_DIRECTEUR_JOURS)));
            }
        }
        return l;
    }

    private final Map<Integer, Optional<Users>> directeurs = new HashMap<>();
    private Users directeurDe(Users salarie) {
        Users s = userDAO.findAccountById(salarie.getId());
        if (s == null || s.getDirection() == null) return null;
        int dirId = s.getDirection().getId();
        return directeurs.computeIfAbsent(dirId, k -> {
            Direction d = directionDAO.find(k);
            return Optional.ofNullable(d == null || d.getDirigePar() <= 0 ? null : userDAO.findAccountById(d.getDirigePar()));
        }).orElse(null);
    }

    // ------------------------------------------------------------------ passage quotidien

    /**
     * Met à jour le suivi et envoie les alertes du jour. Retourne le nombre d'e-mails envoyés.
     * Les actions qui ne sont plus attendues sont closes ; leur compteur repart de zéro si elles reviennent.
     */
    public int executer(LocalDate jour, boolean objectifs) {
        directeurs.clear();
        List<Attendu> attendus = attendus(objectifs);
        Map<String, Relance> ouvertes = new HashMap<>();
        for (Relance r : em.createQuery("select r from Relance r where r.resolue = false", Relance.class).getResultList()) ouvertes.put(r.getCle(), r);

        Map<Integer, List<Relance>> aEnvoyer = new LinkedHashMap<>();
        Set<String> vues = new HashSet<>();
        for (Attendu a : attendus) {
            if (a.echeance() == null || !vues.add(a.cle())) continue;
            Relance r = ouvertes.get(a.cle());
            boolean nouvelle = r == null;
            if (nouvelle) {
                r = new Relance();
                r.setCle(a.cle()); r.setType(a.type()); r.setRefId(a.refId()); r.setSalarieId(a.salarieId()); r.setDestinataireId(a.destinataireId());
            }
            // libellé renseigné avant l'insertion (colonne non nulle, insertion immédiate en IDENTITY)
            r.setLibelle(tronque(a.libelle() == null || a.libelle().isBlank() ? a.type() : a.libelle(), 300)); r.setLien(tronque(a.lien(), 300)); r.setEcheance(a.echeance());
            if (nouvelle) em.persist(r);
            if (planifier(r, jour)) aEnvoyer.computeIfAbsent(r.getDestinataireId(), k -> new ArrayList<>()).add(r);
        }
        for (Relance r : ouvertes.values()) if (!vues.contains(r.getCle())) { r.setResolue(true); r.setDateResolution(new Date()); }
        em.flush();

        int n = 0;
        Map<Users, List<Relance>> critiques = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Relance>> e : aEnvoyer.entrySet()) {
            Users dest = userDAO.findAccountById(e.getKey());
            if (dest == null || dest.getStatut() != 1) continue;
            List<Relance> l = e.getValue();
            l.sort(Comparator.comparingInt((Relance r) -> -Relance.rang(r.getNiveau())).thenComparing(Relance::getEcheance));
            try { notifications.alertes(dest, l); n++; } catch (Exception ex) { LOG.log(Level.WARNING, "HRX alertes : envoi à " + dest.getLogin(), ex); }
            List<Relance> crit = l.stream().filter(Relance::isCritique).toList();
            if (!crit.isEmpty()) {
                critiques.put(dest, crit);
                Users chef = dest.getSuperieurHierarchique();
                if (chef != null) try { notifications.alertesCritiquesResponsable(chef, dest, crit); n++; } catch (Exception ex) { LOG.log(Level.WARNING, "HRX alertes : copie au supérieur", ex); }
            }
        }
        if (!critiques.isEmpty())
            for (Users rh : notifications.destinatairesDcht())
                try { notifications.alertesCritiquesDcht(rh, critiques); n++; } catch (Exception ex) { LOG.log(Level.WARNING, "HRX alertes : synthèse DCHT", ex); }
        LOG.info("HRX alertes du " + jour + " : " + attendus.size() + " action(s) attendue(s), " + aEnvoyer.size() + " destinataire(s) alerté(s), " + critiques.size() + " en situation critique");
        return n;
    }

    /** Ligne de simulation : ce qui serait envoyé à un destinataire, sans rien écrire ni envoyer. */
    public record Simulation(String destinataire, String destinataireLogin, boolean actif, boolean email, String type, String libelle,
                             LocalDate echeance, String niveau, int nbRelances, boolean envoye) { }

    /**
     * Simule le passage du jour donné : mêmes règles que {@link #executer}, mais sur des copies des suivis ouverts.
     * Rien n'est enregistré et aucun e-mail n'est envoyé. {@code envoye} indique si la ligne partirait ce jour-là.
     */
    public List<Simulation> simuler(LocalDate jour, boolean objectifs) {
        directeurs.clear();
        Map<String, Relance> ouvertes = new HashMap<>();
        for (Relance r : em.createQuery("select r from Relance r where r.resolue = false", Relance.class).getResultList()) ouvertes.put(r.getCle(), r);
        List<Simulation> out = new ArrayList<>();
        Set<String> vues = new HashSet<>();
        Map<Integer, Users> dests = new HashMap<>();
        for (Attendu a : attendus(objectifs)) {
            if (a.echeance() == null || !vues.add(a.cle())) continue;
            Relance o = ouvertes.get(a.cle()), r = new Relance();
            if (o != null) { r.setNbRappels(o.getNbRappels()); r.setNbRelances(o.getNbRelances()); r.setNiveau(o.getNiveau()); r.setDernierEnvoi(o.getDernierEnvoi()); }
            r.setEcheance(a.echeance());
            boolean part = planifier(r, jour);
            Users d = dests.computeIfAbsent(a.destinataireId(), userDAO::findAccountById);
            out.add(new Simulation(d == null ? "#" + a.destinataireId() : d.getNomComplet(), d == null ? "" : d.getLogin(), d != null && d.getStatut() == 1,
                    d != null && d.getEmail() != null && !d.getEmail().isBlank(), a.type(), a.libelle(), a.echeance(), r.getNiveau(), r.getNbRelances(), part));
        }
        return out;
    }

    /** Décide si la ligne doit être envoyée aujourd'hui et met à jour ses compteurs et son niveau. */
    static boolean planifier(Relance r, LocalDate jour) {
        if (jour.equals(r.getDernierEnvoi())) return false;
        long avant = ChronoUnit.DAYS.between(jour, r.getEcheance());
        if (avant > 0) {
            boolean du = (avant <= RAPPEL_1 && r.getNbRappels() == 0) || (avant <= RAPPEL_2 && r.getNbRappels() == 1);
            if (!du) return false;
            r.setNbRappels(r.getNbRappels() + 1);
            r.setNiveau(Relance.RAPPEL);
        } else if (avant == 0) {
            if (r.getNbRappels() >= 3) return false;
            r.setNbRappels(3);
            r.setNiveau(Relance.RAPPEL);
        } else {
            boolean du = r.getNbRelances() == 0 || r.getDernierEnvoi() == null || ChronoUnit.DAYS.between(r.getDernierEnvoi(), jour) >= INTERVALLE_RELANCE;
            if (!du) return false;
            r.setNbRelances(r.getNbRelances() + 1);
            r.setNiveau(niveauPour(r.getNbRelances()));
            if (Relance.CRITIQUE.equals(r.getNiveau()) && r.getDateCritique() == null) r.setDateCritique(jour);
        }
        r.setDernierEnvoi(jour);
        return true;
    }

    public static String niveauPour(int nbRelances) {
        if (nbRelances >= SEUIL_CRITIQUE) return Relance.CRITIQUE;
        if (nbRelances == SEUIL_CRITIQUE - 1) return Relance.URGENT;
        return nbRelances >= 1 ? Relance.RELANCE : Relance.RAPPEL;
    }

    // ------------------------------------------------------------------ lecture (tableau de bord)

    /** Alertes ouvertes d'un acteur, les plus graves d'abord. */
    public List<Relance> ouvertesPour(int destinataireId) {
        List<Relance> l = new ArrayList<>(em.createQuery("select r from Relance r where r.resolue = false and r.destinataireId = :d and r.dernierEnvoi is not null", Relance.class)
                .setParameter("d", destinataireId).getResultList());
        l.sort(Comparator.comparingInt((Relance r) -> -Relance.rang(r.getNiveau())).thenComparing(Relance::getEcheance, Comparator.nullsLast(Comparator.naturalOrder())));
        return l;
    }

    /** Nombre d'alertes critiques ouvertes, toute la banque (tableau de bord DCHT). */
    public long nbCritiques() {
        return em.createQuery("select count(r) from Relance r where r.resolue = false and r.niveau = :c", Long.class).setParameter("c", Relance.CRITIQUE).getSingleResult();
    }

    private static LocalDate local(Date d) {
        if (d == null) return null;
        if (d instanceof java.sql.Date sd) return sd.toLocalDate();
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
    private static String tronque(String s, int n) { return s == null ? null : s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
}
