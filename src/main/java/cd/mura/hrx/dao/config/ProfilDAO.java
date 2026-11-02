package cd.mura.hrx.dao.config;

import cd.mura.hrx.entity.config.Profil;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Data
@Stateless
public class ProfilDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    protected EntityManager entityManager;
    protected Query query;

    public Profil persist(Profil profil) {
        Profil response = new Profil();
        try {
            entityManager.persist(profil);
            entityManager.flush();
            response = profil;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return response;
    }

    public Profil find(Profil profil) {
        Profil data = new Profil();
        try {
            query = entityManager.createQuery("select V from Profil V where V.id=?1");
            query.setParameter(1, profil.getId());
            data = (Profil) query.getSingleResult();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
        return data;
    }

    public List<Profil> findAllActive() {
        List<Profil> profils = new ArrayList<>();
        try {
            query = entityManager.createQuery("select V from Profil V where V.statut=?1 order by V.nom asc ");
            query.setParameter(1, 1);
            profils = query.getResultList();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
        return profils;
    }

    public List<Profil> findAll() {
        List<Profil> profils = new ArrayList<>();
        try {
            query = entityManager.createQuery("select V from Profil V order by V.nom asc ");
            profils = query.getResultList();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
        return profils;
    }

    public int remove(Profil profil) {
        int data = 0;
        try {
            Profil profil1 = entityManager.merge(profil);
            entityManager.remove(profil1);
            data = 1;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return data;
    }

    /**
     * Trouve un profil par ID
     */
    public Profil findById(Integer id) {
        try {
            query = entityManager.createQuery(
                    "SELECT p FROM Profil p WHERE p.id = :id"
            );
            query.setParameter("id", id);
            return (Profil) query.getSingleResult();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
            return null;
        }
    }

    /**
     * CRITICAL: Merge avec gestion correcte des relations ManyToMany
     */
    public int merge(Profil profil) {
        try {
            Profil merged = entityManager.merge(profil);
            entityManager.flush();
            entityManager.refresh(merged);
            return 1;
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
            return 0;
        }
    }

    public void updateDefault(int id) {
        try {
            query = entityManager.createQuery("update Profil P set P.profilParDefaut=?1 where P.id <> ?2");
            query.setParameter(1, Boolean.FALSE);
            query.setParameter(2, id);
            query.executeUpdate();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
    }

    public Profil findByDefault(int i) {
        Profil data = null;
        try {
            if (i == 1) {
                query = entityManager.createQuery(
                        "SELECT p FROM Profil p WHERE p.profilParDefaut = :defaut AND p.statut = :statut"
                );
                query.setParameter("defaut", Boolean.TRUE);
                query.setParameter("statut", 1);
                data = (Profil) query.getSingleResult();
            }
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName())
                    .log(java.util.logging.Level.INFO, "Aucun profil par défaut trouvé");
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
        return data;
    }

}
