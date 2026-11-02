package cd.mura.hrx.dao.eval;

import cd.mura.hrx.entity.eval.Campagne;
import cd.mura.hrx.entity.eval.Evaluation;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.Serializable;
import java.util.List;

@Stateless
public class EvaluationDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    public Evaluation find(int id) { return em.find(Evaluation.class, id); }

    /** Fiche complète (objectifs, critères, besoins, campagne, salarié, évaluateur, modèle). */
    public Evaluation findComplete(int id) {
        List<Evaluation> l = em.createQuery("""
                select distinct e from Evaluation e
                join fetch e.campagne join fetch e.salarie left join fetch e.evaluateur left join fetch e.modele
                left join fetch e.ficheObjectifs
                where e.id = :id
                """, Evaluation.class).setParameter("id", id).getResultList();
        if (l.isEmpty()) return null;
        Evaluation e = l.get(0);
        e.getCriteres().size(); e.getBesoinsFormation().size();
        return e;
    }

    public Evaluation findByCampagneEtSalarie(int campagneId, int salarieId) {
        List<Evaluation> l = em.createQuery("""
                select distinct e from Evaluation e left join fetch e.ficheObjectifs
                where e.campagne.id = :c and e.salarie.id = :s
                """, Evaluation.class).setParameter("c", campagneId).setParameter("s", salarieId).getResultList();
        return l.isEmpty() ? null : l.get(0);
    }

    /** Fiches dont l'utilisateur est l'évaluateur pour une campagne. */
    public List<Evaluation> findByEvaluateur(int campagneId, int evaluateurId) {
        return em.createQuery("""
                select distinct e from Evaluation e join fetch e.salarie left join fetch e.ficheObjectifs
                where e.campagne.id = :c and e.evaluateur.id = :n1
                order by e.salarie.nom, e.salarie.prenom
                """, Evaluation.class).setParameter("c", campagneId).setParameter("n1", evaluateurId).getResultList();
    }

    public List<Evaluation> findBySalarie(int salarieId) {
        return em.createQuery("""
                select distinct e from Evaluation e join fetch e.campagne left join fetch e.evaluateur
                where e.salarie.id = :s order by e.campagne.exercice desc, e.campagne.dateDebut desc
                """, Evaluation.class).setParameter("s", salarieId).getResultList();
    }

    public List<Evaluation> findByCampagne(int campagneId) {
        return em.createQuery("""
                select distinct e from Evaluation e join fetch e.salarie left join fetch e.evaluateur
                where e.campagne.id = :c order by e.salarie.nom, e.salarie.prenom
                """, Evaluation.class).setParameter("c", campagneId).getResultList();
    }

    /** Fiches d'une campagne avec les lignes d'objectifs chargées (contrôles de complétude). */
    public List<Evaluation> findByCampagneAvecObjectifs(int campagneId) {
        return em.createQuery("""
                select distinct e from Evaluation e join fetch e.salarie left join fetch e.evaluateur left join fetch e.objectifs
                where e.campagne.id = :c order by e.salarie.nom, e.salarie.prenom
                """, Evaluation.class).setParameter("c", campagneId).getResultList();
    }

    /** Fiches d'une campagne avec les besoins de formation chargés (reporting). */
    public List<Evaluation> findByCampagneAvecFormations(int campagneId) {
        return em.createQuery("""
                select distinct e from Evaluation e join fetch e.salarie left join fetch e.evaluateur left join fetch e.besoinsFormation
                where e.campagne.id = :c order by e.salarie.nom, e.salarie.prenom
                """, Evaluation.class).setParameter("c", campagneId).getResultList();
    }

    public long countByCampagne(int campagneId) {
        return em.createQuery("select count(e) from Evaluation e where e.campagne.id = :c", Long.class).setParameter("c", campagneId).getSingleResult();
    }

    public Evaluation save(Evaluation e) {
        if (e.getId() == 0) { em.persist(e); em.flush(); return e; }
        return em.merge(e);
    }

    public Campagne refCampagne(int id) { return em.getReference(Campagne.class, id); }
}
