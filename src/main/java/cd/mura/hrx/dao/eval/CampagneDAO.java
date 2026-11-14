package cd.mura.hrx.dao.eval;

import cd.mura.hrx.entity.eval.Campagne;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.Serializable;
import java.util.List;

@Stateless
public class CampagneDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    public Campagne find(int id) {
        List<Campagne> l = em.createQuery("select c from Campagne c left join fetch c.modeleParDefaut left join fetch c.modeleEncadrants where c.id = :id", Campagne.class).setParameter("id", id).getResultList();
        return l.isEmpty() ? null : l.get(0);
    }

    public List<Campagne> findAll() {
        return em.createQuery("select c from Campagne c left join fetch c.modeleParDefaut left join fetch c.modeleEncadrants order by c.exercice desc, c.dateDebut desc, c.id desc", Campagne.class).getResultList();
    }

    /** Campagne ouverte la plus récente (une seule à la fois en pratique). */
    public Campagne findOuverte() {
        List<Campagne> l = em.createQuery("select c from Campagne c left join fetch c.modeleParDefaut left join fetch c.modeleEncadrants where c.statut = :s order by c.dateDebut desc, c.id desc", Campagne.class)
                .setParameter("s", Campagne.OUVERTE).setMaxResults(1).getResultList();
        return l.isEmpty() ? null : l.get(0);
    }

    public Campagne save(Campagne c) {
        if (c.getId() == 0) { em.persist(c); em.flush(); return c; }
        return em.merge(c);
    }

    public void remove(Campagne c) { Campagne m = em.find(Campagne.class, c.getId()); if (m != null) em.remove(m); }
}
