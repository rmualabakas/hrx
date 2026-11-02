package cd.mura.hrx.dao.config;

import cd.mura.hrx.entity.config.GroupeUtilisateur;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
@Stateless
public class GroupeUtilisateurDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    protected EntityManager entityManager;

    protected Query query;

    public GroupeUtilisateur persist(GroupeUtilisateur groupe) {
        try {
            entityManager.persist(groupe);
            entityManager.flush();
            entityManager.refresh(groupe);
            return groupe;
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
            return new GroupeUtilisateur();
        }
    }

    public int merge(GroupeUtilisateur groupe) {
        try {
            entityManager.merge(groupe);
            entityManager.flush();
            return 1;
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
            return 0;
        }
    }

    public GroupeUtilisateur find(long id) {
        try {
            query = entityManager.createQuery("""
                    select distinct G from GroupeUtilisateur G
                    left join fetch G.membres
                    where G.id = ?1
                    """);
            query.setParameter(1, id);
            return (GroupeUtilisateur) query.getSingleResult();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
            return null;
        }
    }

    public GroupeUtilisateur getReferenceById(long id) {
        return entityManager.getReference(GroupeUtilisateur.class, id);
    }

    public List<GroupeUtilisateur> findAll() {
        List<GroupeUtilisateur> data = new ArrayList<>();
        try {
            query = entityManager.createQuery("""
                    select distinct G from GroupeUtilisateur G
                    left join fetch G.membres
                    order by G.nom asc
                    """);
            data = query.getResultList();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
        return data;
    }

    public List<GroupeUtilisateur> findActifs() {
        List<GroupeUtilisateur> data = new ArrayList<>();
        try {
            query = entityManager.createQuery("""
                    select distinct G from GroupeUtilisateur G
                    left join fetch G.membres
                    where G.statut = 1
                    order by G.nom asc
                    """);
            data = query.getResultList();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
        return data;
    }

    public List<GroupeUtilisateur> findByNom(String nom) {
        List<GroupeUtilisateur> data = new ArrayList<>();
        try {
            query = entityManager.createQuery("""
                    select G from GroupeUtilisateur G
                    where lower(G.nom) = ?1
                    order by G.id desc
                    """);
            query.setParameter(1, nom == null ? "" : nom.trim().toLowerCase());
            data = query.getResultList();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
        return data;
    }

    public int remove(GroupeUtilisateur groupe) {
        try {
            GroupeUtilisateur managed = entityManager.merge(groupe);
            entityManager.remove(managed);
            entityManager.flush();
            return 1;
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
            return 0;
        }
    }
}
