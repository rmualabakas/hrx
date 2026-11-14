package cd.mura.hrx.service;

import cd.mura.hrx.entity.config.JourFerie;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Calendrier ouvré : ni samedi, ni dimanche, ni jour férié (ponctuel ou récurrent, cf. {@link JourFerie}).
 * Sert au calcul des délais (ex. 48 h ouvrées pour signer une fiche d'objectifs) et au rythme des relances.
 */
@Stateless
public class CalendrierService {

    private static final Logger LOG = Logger.getLogger(CalendrierService.class.getName());

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    public List<JourFerie> lister() {
        return em.createQuery("select j from JourFerie j order by j.recurrent desc, j.mois, j.jour, j.dateFerie", JourFerie.class).getResultList();
    }

    public List<JourFerie> actifs() {
        return em.createQuery("select j from JourFerie j where j.actif = true", JourFerie.class).getResultList();
    }

    public JourFerie enregistrer(JourFerie j) {
        if (j.getLibelle() == null || j.getLibelle().isBlank()) throw new IllegalArgumentException("Indiquez le nom du jour férié.");
        j.setLibelle(j.getLibelle().trim());
        if (j.isRecurrent()) {
            if (j.getJour() == null || j.getMois() == null) throw new IllegalArgumentException("Indiquez le jour et le mois du férié récurrent.");
            try { LocalDate.of(2024, j.getMois(), j.getJour()); } catch (Exception e) { throw new IllegalArgumentException("Cette date n'existe pas."); }
            j.setDateFerie(null);
        } else {
            if (j.getDateFerie() == null) throw new IllegalArgumentException("Indiquez la date du jour férié.");
            j.setJour(null); j.setMois(null);
        }
        if (j.getId() == 0) { em.persist(j); return j; }
        return em.merge(j);
    }

    public void supprimer(int id) {
        JourFerie j = em.find(JourFerie.class, id);
        if (j != null) em.remove(j);
    }

    public JourFerie basculer(int id) {
        JourFerie j = em.find(JourFerie.class, id);
        if (j != null) j.setActif(!j.isActif());
        return j;
    }

    /** Jours fériés légaux de la RDC, créés une fois si la table est vide (modifiables ensuite par l'administrateur). */
    public void initialiserSiVide() {
        Long n = em.createQuery("select count(j) from JourFerie j", Long.class).getSingleResult();
        if (n != null && n > 0) return;
        Object[][] rdc = {
                {"Nouvel an", 1, 1}, {"Journée des martyrs de l'indépendance", 4, 1}, {"Journée du héros national Laurent-Désiré Kabila", 16, 1},
                {"Journée du héros national Patrice-Émery Lumumba", 17, 1}, {"Journée de Simon Kimbangu et de la conscience africaine", 6, 4},
                {"Fête du travail", 1, 5}, {"Journée de la révolution et des forces armées", 17, 5}, {"Fête de l'indépendance", 30, 6},
                {"Fête des parents", 1, 8}, {"Journée du génocide congolais (Genocost)", 2, 8}, {"Noël", 25, 12}};
        for (Object[] r : rdc) {
            JourFerie j = new JourFerie();
            j.setLibelle((String) r[0]); j.setJour((Integer) r[1]); j.setMois((Integer) r[2]); j.setRecurrent(true); j.setActif(true);
            em.persist(j);
        }
        LOG.info("HRX : jours fériés de la RDC initialisés (" + rdc.length + ")");
    }

    // ------------------------------------------------------------------ calcul ouvré

    public boolean estFerie(LocalDate d) { return estFerie(d, actifs()); }

    public boolean estOuvre(LocalDate d) { return estOuvre(d, actifs()); }

    /** Date atteinte après {@code n} jours ouvrés comptés à partir du lendemain de {@code depart}. */
    public LocalDate ajouterJoursOuvres(LocalDate depart, int n) {
        List<JourFerie> f = actifs();
        LocalDate d = depart;
        int restants = n;
        while (restants > 0) { d = d.plusDays(1); if (estOuvre(d, f)) restants--; }
        return d;
    }

    /** Nombre de jours ouvrés entre deux dates (borne de départ exclue, borne d'arrivée incluse) ; négatif si {@code b} est avant {@code a}. */
    public int joursOuvresEntre(LocalDate a, LocalDate b) {
        if (a == null || b == null || a.equals(b)) return 0;
        List<JourFerie> f = actifs();
        boolean avant = b.isBefore(a);
        LocalDate debut = avant ? b : a, fin = avant ? a : b;
        int n = 0;
        for (LocalDate d = debut.plusDays(1); !d.isAfter(fin); d = d.plusDays(1)) if (estOuvre(d, f)) n++;
        return avant ? -n : n;
    }

    /** Fériés tombant une année donnée, triés par date (affichage). */
    public List<LocalDate> datesFeriees(int annee) {
        List<LocalDate> l = new ArrayList<>();
        for (JourFerie j : actifs()) { LocalDate d = j.dansAnnee(annee); if (d != null) l.add(d); }
        l.sort(null);
        return l;
    }

    public static boolean estFerie(LocalDate d, List<JourFerie> feries) { return feries.stream().anyMatch(j -> j.tombeLe(d)); }

    public static boolean estOuvre(LocalDate d, List<JourFerie> feries) {
        if (d == null) return false;
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !estFerie(d, feries);
    }
}
