package cd.mura.hrx.dao.config;

import cd.mura.hrx.entity.config.App;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import java.util.List;

@Stateless
public class AppDAO {

    @PersistenceContext
    private EntityManager em;

    /**
     * Retourne la configuration existante (première ligne), sinon null.
     * Si tu veux forcer "statut=1", tu peux filtrer ici.
     */
    public App findFirst() {
        TypedQuery<App> q = em.createQuery(
                "SELECT a FROM App a ORDER BY a.id ASC",
                App.class
        );
        q.setMaxResults(1);
        List<App> list = q.getResultList();
        return list.isEmpty() ? null : list.get(0);
    }

    public App save(App app) {
        em.persist(app);
        em.flush();
        return app;
    }

    public App update(App app) {
        App merged = em.merge(app);
        em.flush();
        return merged;
    }
}
