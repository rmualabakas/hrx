package cd.mura.hrx.service.recrutement;

import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.recrutement.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Postes, critères et analyses de CV (opérations en base, chacune dans sa transaction). */
@Stateless
public class RecrutementService {

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    static final ObjectMapper JSON = new ObjectMapper();

    /** Avis sur un critère, conservé en JSON dans l'analyse. */
    public record Detail(int critereId, String resultat, String justification) implements Serializable { }

    // ------------------------------------------------------------------ postes

    public List<Poste> postes(boolean archives) {
        return em.createQuery("select p from Poste p left join fetch p.direction where " + (archives ? "p.statut = :a" : "p.statut <> :a")
                + " order by p.dateCreation desc, p.id desc", Poste.class).setParameter("a", Poste.ARCHIVE).getResultList();
    }

    public Poste poste(int id) {
        List<Poste> l = em.createQuery("select distinct p from Poste p left join fetch p.criteres where p.id = :id", Poste.class).setParameter("id", id).getResultList();
        if (l.isEmpty()) return null;
        Poste p = l.get(0);
        if (p.getDirection() != null) p.getDirection().getNom();
        return p;
    }

    /** Par poste : [CV, analysés, présélectionnés ou convoqués, meilleur score]. */
    public Map<Integer, long[]> compteurs() {
        Map<Integer, long[]> m = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select a.poste.id, count(a), sum(case when a.statut = 'TERMINEE' then 1 else 0 end),
                       sum(case when a.decision in ('PRESELECTIONNE','ENTRETIEN') then 1 else 0 end), max(a.score)
                from CvAnalyse a group by a.poste.id""", Object[].class).getResultList())
            m.put((Integer) r[0], new long[]{n(r[1]), n(r[2]), n(r[3]), r[4] == null ? -1 : n(r[4])});
        return m;
    }
    private static long n(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    public Poste enregistrer(Poste p, Integer directionId, Users auteur) {
        if (p.getIntitule() == null || p.getIntitule().isBlank()) throw new IllegalArgumentException("L'intitulé du poste est obligatoire.");
        if (p.getSeuilMoyen() >= p.getSeuilBon()) throw new IllegalArgumentException("Le seuil « moyen » doit être inférieur au seuil « bon ».");
        p.getCriteres().removeIf(c -> !c.isCalcule() && (c.getLibelle() == null || c.getLibelle().isBlank()));
        int i = 0;
        for (PosteCritere c : p.getCriteres()) {
            c.setPoste(p); c.setOrdre(i++);
            c.setPonderation(Math.max(1, Math.min(5, c.getPonderation())));
            if (c.getLibelle() != null) c.setLibelle(c.getLibelle().trim());
            if (PosteCritere.DIPLOME.equals(c.getType())) { c.setValeurMax(null); if (c.getValeurMin() == null) throw new IllegalArgumentException("Choisissez le niveau d'études minimum."); }
            if ((PosteCritere.AGE.equals(c.getType()) || PosteCritere.EXPERIENCE.equals(c.getType())) && c.getValeurMin() == null && c.getValeurMax() == null)
                throw new IllegalArgumentException("Indiquez une borne minimum ou maximum pour le critère « " + c.getTypeLabel() + " ».");
            if (c.getValeurMin() != null && c.getValeurMax() != null && c.getValeurMin() > c.getValeurMax())
                throw new IllegalArgumentException("Critère « " + c.getTypeLabel() + " » : le minimum dépasse le maximum.");
        }
        if (p.getCriteres().isEmpty()) throw new IllegalArgumentException("Définissez au moins un critère de sélection.");
        p.setDirection(directionId == null ? null : em.getReference(Direction.class, directionId));
        p.setDateMaj(new Date());
        if (p.getId() == 0) { p.setCreePar(auteur == null ? 0 : auteur.getId()); em.persist(p); em.flush(); return p; }
        Poste m = em.merge(p);
        em.flush();
        return m;
    }

    public void changerStatut(int posteId, String statut) {
        Poste p = em.find(Poste.class, posteId);
        if (p != null) { p.setStatut(statut); p.setDateMaj(new Date()); }
    }

    public void retirerDateLimite(int posteId) {
        Poste p = em.find(Poste.class, posteId);
        if (p != null) { p.setDateLimite(null); p.setDateMaj(new Date()); }
    }

    /** Ferme les candidatures des postes ouverts dont la date limite est passée ; renvoie les postes clos. */
    public List<String> cloturerEchus() {
        List<Poste> l = em.createQuery("select p from Poste p where p.statut = :o and p.dateLimite < :j", Poste.class)
                .setParameter("o", Poste.OUVERT).setParameter("j", java.sql.Date.valueOf(java.time.LocalDate.now())).getResultList();
        for (Poste p : l) { p.setStatut(Poste.CLOS); p.setDateMaj(new Date()); }
        return l.stream().map(Poste::getIntitule).toList();
    }

    public void supprimerPoste(int posteId) {
        em.createQuery("delete from CvAnalyse a where a.poste.id = :p").setParameter("p", posteId).executeUpdate();
        Poste p = em.find(Poste.class, posteId);
        if (p != null) em.remove(p);
    }

    // ------------------------------------------------------------------ candidatures

    /** Ajoute des CV à un poste (sans doublon). Renvoie le nombre de CV ajoutés. */
    public int ajouterCvs(int posteId, Collection<Integer> cvIds) {
        Poste p = em.find(Poste.class, posteId);
        Set<Integer> deja = new HashSet<>(em.createQuery("select a.cv.id from CvAnalyse a where a.poste.id = :p", Integer.class).setParameter("p", posteId).getResultList());
        int n = 0;
        for (Integer id : new LinkedHashSet<>(cvIds)) {
            if (id == null || deja.contains(id)) continue;
            CvAnalyse a = new CvAnalyse();
            a.setPoste(p); a.setCv(em.getReference(Cv.class, id));
            em.persist(a); n++;
        }
        return n;
    }

    public List<CvAnalyse> analyses(int posteId) {
        return em.createQuery("select a from CvAnalyse a join fetch a.cv where a.poste.id = :p", CvAnalyse.class).setParameter("p", posteId).getResultList();
    }

    public void retirer(int analyseId) {
        CvAnalyse a = em.find(CvAnalyse.class, analyseId);
        if (a != null) em.remove(a);
    }

    public void decider(int analyseId, String decision, String commentaire) {
        CvAnalyse a = em.find(CvAnalyse.class, analyseId);
        if (a == null) return;
        a.setDecision(decision == null ? CvAnalyse.A_TRAITER : decision);
        a.setCommentaire(commentaire == null || commentaire.isBlank() ? null : commentaire.trim());
    }

    /** Remet en file les analyses à faire. tout = true : relance aussi celles déjà terminées (critères modifiés). */
    public int planifier(int posteId, boolean tout) {
        return em.createQuery("update CvAnalyse a set a.statut = 'EN_ATTENTE', a.erreur = null where a.poste.id = :p and "
                + (tout ? "a.statut <> 'EN_COURS'" : "a.statut in ('EN_ATTENTE','ERREUR')")).setParameter("p", posteId).executeUpdate();
    }
    public int planifierUne(int analyseId) {
        return em.createQuery("update CvAnalyse a set a.statut = 'EN_ATTENTE', a.erreur = null where a.id = :i and a.statut <> 'EN_COURS'").setParameter("i", analyseId).executeUpdate();
    }
    /** Analyses restées « en cours » après un arrêt du serveur. */
    public void debloquer(int posteId) {
        em.createQuery("update CvAnalyse a set a.statut = 'EN_ATTENTE' where a.poste.id = :p and a.statut = 'EN_COURS'").setParameter("p", posteId).executeUpdate();
    }

    /** Au démarrage du serveur, aucune analyse ne tourne : les « en cours » restés en base sont remis en attente. */
    public int debloquerTout() {
        return em.createQuery("update CvAnalyse a set a.statut = 'EN_ATTENTE' where a.statut = 'EN_COURS'").executeUpdate();
    }

    public List<Integer> enAttente(int posteId) {
        return em.createQuery("select a.id from CvAnalyse a where a.poste.id = :p and a.statut = 'EN_ATTENTE' order by a.id", Integer.class).setParameter("p", posteId).getResultList();
    }

    /** Réserve une analyse pour traitement ; faux si un autre traitement l'a déjà prise. */
    public boolean reserver(int analyseId) {
        return em.createQuery("update CvAnalyse a set a.statut = 'EN_COURS' where a.id = :i and a.statut = 'EN_ATTENTE'").setParameter("i", analyseId).executeUpdate() == 1;
    }

    /** Données nécessaires au traitement d'une analyse, lues hors transaction longue. */
    public record Travail(int analyseId, int cvId, Cv cv, Poste poste) { }

    public Travail travail(int analyseId) {
        CvAnalyse a = em.find(CvAnalyse.class, analyseId);
        if (a == null) return null;
        Cv cv = a.getCv(); cv.getNomFichier();
        Poste p = poste(a.getPoste().getId());
        p.getCriteres().size();
        return new Travail(analyseId, cv.getId(), cv, p);
    }

    public void echec(int analyseId, String message) {
        CvAnalyse a = em.find(CvAnalyse.class, analyseId);
        if (a == null) return;
        a.setStatut(CvAnalyse.ERREUR); a.setErreur(message); a.setDateAnalyse(new Date());
    }

    public void resultat(int analyseId, int score, boolean elimine, List<Detail> details, String synthese) {
        CvAnalyse a = em.find(CvAnalyse.class, analyseId);
        if (a == null) return;
        try { a.setDetailJson(JSON.writeValueAsString(details)); } catch (Exception e) { throw new IllegalStateException(e); }
        a.setScore(score); a.setElimine(elimine); a.setSynthese(synthese); a.setErreur(null);
        a.setStatut(CvAnalyse.TERMINEE); a.setDateAnalyse(new Date());
    }

    public static List<Detail> details(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return JSON.readValue(json, new TypeReference<List<Detail>>() { }); } catch (Exception e) { return List.of(); }
    }

    // ------------------------------------------------------------------ lecture des CV

    /** Lecture en cours (les lectures d'un même CV sont sérialisées par le moteur). */
    public void marquerLecture(int cvId) {
        em.createQuery("update Cv c set c.statutLecture = 'EN_COURS' where c.id = :i").setParameter("i", cvId).executeUpdate();
    }
    public void echecLecture(int cvId, String message) {
        Cv c = em.find(Cv.class, cvId);
        if (c != null) { c.setStatutLecture(Cv.ERREUR); c.setErreurLecture(message); }
    }
    public void relire(int cvId) {
        Cv c = em.find(Cv.class, cvId);
        if (c != null && !Cv.EN_COURS.equals(c.getStatutLecture())) c.setStatutLecture(Cv.A_LIRE);
    }

    /** Enregistre le profil lu par Claude et en reprend les champs utiles au tri. */
    public Cv profil(int cvId, ClaudeCvService.ProfilCv p, String modele) {
        Cv c = em.find(Cv.class, cvId);
        if (c == null) return null;
        try { c.setProfilJson(JSON.writeValueAsString(p)); } catch (Exception e) { throw new IllegalStateException(e); }
        c.setCandidatNom(tronque(p.nomComplet(), 200)); c.setEmail(tronque(p.email(), 200)); c.setTelephone(tronque(p.telephone(), 60));
        c.setLocalisation(tronque(p.localisation(), 200)); c.setTitreProfil(tronque(p.titre(), 300)); c.setDiplome(tronque(p.diplomePrincipal(), 300));
        LocalDate naissance = null;
        try { if (p.dateNaissance() != null && !p.dateNaissance().isBlank()) naissance = LocalDate.parse(p.dateNaissance().trim()); } catch (DateTimeParseException ignored) { }
        if (naissance != null && (naissance.isAfter(LocalDate.now().minusYears(14)) || naissance.isBefore(LocalDate.now().minusYears(90)))) naissance = null;
        c.setDateNaissance(naissance);
        c.setAge(naissance != null ? Integer.valueOf(Period.between(naissance, LocalDate.now()).getYears()) : p.ageDeclare() > 0 ? Integer.valueOf(p.ageDeclare()) : null);
        c.setExperienceAnnees(p.experienceAnnees() < 0 ? null : p.experienceAnnees());
        c.setNiveauEtudes(p.niveauEtudes() <= 0 ? null : Math.min(7, p.niveauEtudes()));
        c.setModeleIa(modele); c.setDateLecture(new Date());
        c.setStatutLecture(Cv.LU); c.setErreurLecture(null);
        return c;
    }

    private static String tronque(String s, int n) { if (s == null || s.isBlank()) return null; String t = s.trim(); return t.length() > n ? t.substring(0, n) : t; }
}
