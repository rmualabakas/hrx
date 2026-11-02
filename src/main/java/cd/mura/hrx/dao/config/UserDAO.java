package cd.mura.hrx.dao.config;

import cd.mura.hrx.entity.config.Users;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Data
@Stateless
public class UserDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    protected EntityManager entityManager;
    protected Query query;
    private static final Logger log = LoggerFactory.getLogger(UserDAO.class);

    public Users persist(Users user) {
        try {
            entityManager.persist(user);
            entityManager.flush();
            return user;
        } catch (Exception e) {
            log.error("Error persisting user", e);
            throw new PersistenceException("Could not persist user", e);
        }
    }

    /**
     * Recherche un utilisateur par email OU login
     * ⚡ CORRECTION: Utilise getResultList() au lieu de getSingleResult()
     * pour éviter NoResultException lors de l'importation LDAP
     *
     * @param emailOrLogin Email ou login à rechercher
     * @return L'utilisateur trouvé, ou NULL si aucun utilisateur ne correspond
     */
    public Users findUserByLoginEmail(String emailOrLogin) {
        try {
            query = entityManager.createQuery(
                    "SELECT u FROM Users u WHERE u.email = :param OR u.login = :param"
            );
            query.setParameter("param", emailOrLogin);

            // ⚡ UTILISER getResultList() au lieu de getSingleResult()
            // pour éviter NoResultException
            List<Users> results = query.getResultList();

            // Retourner le premier résultat ou null
            return results.isEmpty() ? null : results.get(0);

        } catch (Exception e) {
            log.error("Erreur lors de la recherche de l'utilisateur par email/login: {}", emailOrLogin, e);
            return null;
        }
    }

    public int merge(Users user) {
        int data = 0;
        try {
            entityManager.merge(user);
            entityManager.flush();

            data = 1;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return data;
    }

    public Users findUserById(int id) {

        List<Users> results = entityManager
                .createQuery("SELECT u FROM Users u WHERE u.id = :id", Users.class)
                .setParameter("id", id)
                .getResultList();

        return results.isEmpty() ? null : results.get(0);
    }

    public Users findAccountById(int id) {
        List<Users> results = entityManager.createQuery("""
                        select distinct u from Users u
                        left join fetch u.profil
                        left join fetch u.direction
                        left join fetch u.superieurHierarchique
                        where u.id = :id
                        """, Users.class)
                .setParameter("id", id)
                .getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    // Evite un SELECT complet, rend un proxy MANAGÉ
    public Users getReferenceById(int id) {
        return entityManager.getReference(Users.class, id);
    }

    public List<Users> findAll() {

        List<Users> data = new ArrayList<>();

        try {
            query = entityManager.createQuery("""
                    select distinct U from Users U
                    left join fetch U.superieurHierarchique
                    where U.editable=?1
                    order by U.nom,U.prenom
                    """);
            query.setParameter(1, 1);
            data = query.getResultList();

        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
        return data;
    }

    /** Comptes actifs avec direction et N+1 chargés (tableau de bord, reporting). */
    public List<Users> findActifsAvecDirection() {
        try {
            return entityManager.createQuery("""
                    select distinct U from Users U left join fetch U.direction left join fetch U.superieurHierarchique
                    where U.statut = 1 order by U.nom, U.prenom
                    """, Users.class).getResultList();
        } catch (Exception e) { java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e); return new ArrayList<>(); }
    }

    public List<Users> findAllActif() {

        List data = new ArrayList<>();

        try {
            query = entityManager.createQuery("""
                    select distinct U from Users U
                    left join fetch U.superieurHierarchique
                    where U.editable=?1 and U.statut=?2
                    order by U.nom,U.prenom
                    """);
            query.setParameter(1, 1);
            query.setParameter(2, 1);
            data = query.getResultList();

        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
        return data;
    }

    /** Comptes pouvant être proposés par une mention */
    /** Collaborateurs actifs dont l'utilisateur est le N+1 direct. */
    public List<Users> findBySuperieur(int superieurId) {
        try {
            return entityManager.createQuery("""
                    select distinct U from Users U left join fetch U.direction left join fetch U.profil left join fetch U.superieurHierarchique
                    where U.superieurHierarchique.id = :n1 and U.statut = 1
                    order by U.nom, U.prenom
                    """, Users.class).setParameter("n1", superieurId).getResultList();
        } catch (Exception e) { java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e); return new ArrayList<>(); }
    }

    public List<Users> findAllMentionable() {
        return entityManager.createQuery("""
                        select distinct u from Users u
                        where u.statut = 1
                        order by u.nom, u.prenom, u.login
                        """, Users.class)
                .getResultList();
    }

    /** Utilisateurs actifs habilités à piloter le module Réclamations */
    public List<Users> findActiveByPrivilegeCodes(Collection<String> privilegeCodes) {
        if (privilegeCodes == null || privilegeCodes.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery("""
                        select distinct u from Users u
                        join u.profil p
                        left join p.privileges priv
                        where u.statut = 1
                          and u.editable = 1
                          and (p.id = 1 or (priv.statut = 1 and priv.code in :codes))
                        order by u.nom, u.prenom
                        """, Users.class)
                .setParameter("codes", privilegeCodes)
                .getResultList();
    }

    public int remove(Users user) {

        int data = 0;
        try {
            Users user1 = entityManager.merge(user);
            entityManager.remove(user1);
            data = 1;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return data;
    }

}
