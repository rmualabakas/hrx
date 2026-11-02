package cd.mura.hrx.dao.config;

import cd.mura.hrx.entity.config.Menu;
import cd.mura.hrx.entity.config.Privilege;
import cd.mura.hrx.entity.config.Profil;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
@Stateless
public class PrivilegeDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    protected EntityManager entityManager;
    protected Query query;

    @Transactional
    public int persist(Privilege privilege) {

        int response = 0;

        try {
            entityManager.persist(privilege);
            entityManager.flush();
            response = 1;
        } catch (Exception x) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }

        return response;
    }

    @Transactional
    public Privilege find(Privilege privilege) {

        Privilege data = new Privilege();
        try {

            query = entityManager.createQuery("select m from Privilege m where m.id=?1");
            query.setParameter(1, privilege.getId());
            data = (Privilege) query.getSingleResult();

        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return data;
    }

    @Transactional
    public int merge(Privilege privilege) {
        int data = 0;
        try {
            entityManager.merge(privilege);
            entityManager.flush();

            data = 1;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return data;
    }
    @Transactional
    public int remove(Privilege privilege) {

        int data = 0;
        try {
            Privilege privilege1 = entityManager.merge(privilege);
            entityManager.remove(privilege1);
            data = 1;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return data;
    }
    @Transactional
    public List<Privilege> findPrivilegeActifByMenu(Menu menu) {
        List<Privilege> privileges = new ArrayList<>();

        try {
            query = entityManager.createQuery("select m from Privilege m where m.menu.id=?1 and m.statut=?2 order by m.nom asc ");
            query.setParameter(1,menu.getId());
            query.setParameter(2,1);
            privileges = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return privileges;
    }
    @Transactional
    public List<Privilege> findAllByMenu(Menu menu ) {

        List<Privilege> privileges = new ArrayList<>();

        try {
            query = entityManager.createQuery("select m from Privilege m where m.menu.id=?1  order by m.nom asc ");
            query.setParameter(1,menu.getId());
            privileges = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return privileges;

    }
    @Transactional
    public Privilege findPrivilegeById(int id) {

       Privilege privilege = new Privilege();

        try {
            query = entityManager.createQuery("select m from Privilege m where m.id=?1  ");
            query.setParameter(1,id);
            privilege = (Privilege) query.getSingleResult();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return privilege;
    }
    @Transactional
    public List<Privilege> findAllByProfil(Profil profil) {

        List<Privilege> data;
        query = entityManager.createQuery("SELECT e FROM Privilege e JOIN e.profils p WHERE p.id = ?1") ;
        query.setParameter(1,profil.getId());
        data = query.getResultList();
        return data;
    }

    @Transactional
    public void addProfilPrivilege(Privilege privilege, Profil profil){

        query = entityManager.createNativeQuery("INSERT INTO `profil_privilege` (`privilege_id`, `profil_id`) VALUES (?1, ?2)") ;
        query.setParameter(1,privilege.getId());
        query.setParameter(2,profil.getId());
        query.executeUpdate();

    }
    @Transactional
    public void removeProfilPrivilege(Privilege privilege, Profil profil){

        query = entityManager.createNativeQuery("delete from `profil_privilege` where profil_id=?1 and privilege_id=?2") ;
        query.setParameter(1,profil.getId());
        query.setParameter(2,privilege.getId());
        query.executeUpdate();
    }

    @Transactional
    public void ensureProfilMenu(Menu menu, Profil profil) {
        if (menu == null || profil == null) return;
        entityManager.createNativeQuery("""
                INSERT INTO profil_menu (menu_id, profil_id)
                SELECT ?1, ?2
                WHERE NOT EXISTS (
                    SELECT 1 FROM profil_menu WHERE menu_id=?1 AND profil_id=?2
                )
                """)
                .setParameter(1, menu.getId())
                .setParameter(2, profil.getId())
                .executeUpdate();
    }

    @Transactional
    public void removeProfilMenuWithoutActivePrivilege(Menu menu, Profil profil) {
        if (menu == null || profil == null) return;
        entityManager.createNativeQuery("""
                DELETE FROM profil_menu
                WHERE menu_id=?1 AND profil_id=?2
                  AND NOT EXISTS (
                    SELECT 1
                    FROM profil_privilege pp
                    JOIN privilege pr ON pr.id=pp.privilege_id
                    WHERE pp.profil_id=?2 AND pr.menu_id=?1 AND pr.statut=1
                  )
                """)
                .setParameter(1, menu.getId())
                .setParameter(2, profil.getId())
                .executeUpdate();
    }

    @Transactional
    public int addAllMenuPrivilegesToProfil(Menu menu, Profil profil) {
        query = entityManager.createNativeQuery("""
                INSERT INTO profil_privilege (privilege_id, profil_id)
                SELECT privilege.id, ?2
                FROM privilege
                WHERE privilege.menu_id = ?1
                  AND NOT EXISTS (
                    SELECT 1
                    FROM profil_privilege
                    WHERE profil_privilege.privilege_id = privilege.id
                      AND profil_privilege.profil_id = ?2
                  )
                """);
        query.setParameter(1, menu.getId());
        query.setParameter(2, profil.getId());
        return query.executeUpdate();
    }

    @Transactional
    public int removeAllMenuPrivilegesFromProfil(Menu menu, Profil profil) {
        query = entityManager.createNativeQuery("""
                DELETE profil_privilege
                FROM profil_privilege
                INNER JOIN privilege ON privilege.id = profil_privilege.privilege_id
                WHERE profil_privilege.profil_id = ?1
                  AND privilege.menu_id = ?2
                """);
        query.setParameter(1, profil.getId());
        query.setParameter(2, menu.getId());
        return query.executeUpdate();
    }
    @Transactional
    public Privilege findPrivilegeByCode(String code) {

        Privilege privilege = new Privilege();

        try {
            query = entityManager.createQuery("select priv from Privilege priv where priv.code=?1 ");
            query.setParameter(1,code);
            privilege = (Privilege) query.getSingleResult();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return privilege;
    }

}
