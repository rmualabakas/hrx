package cd.mura.hrx.dao.config;

import cd.mura.hrx.entity.config.Menu;
import cd.mura.hrx.entity.config.ModuleP;
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
public class MenuDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    protected EntityManager entityManager;
    protected Query query;
    @Transactional
    public int persist(Menu menu) {

        int response = 0;

        try {
            entityManager.persist(menu);
            entityManager.flush();
            response = 1;
        } catch (Exception x) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }

        return response;
    }

    @Transactional
    public Menu find(int id) {

        Menu data = new Menu();
        try {

            query = entityManager.createQuery("select m from Menu m where m.id=?1");
            query.setParameter(1, id);
            data = (Menu) query.getSingleResult();

        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return data;
    }

@Transactional
    public List<Menu> findAllActiveById(int id) {

        List<Menu> menus = new ArrayList<>();

        try {
            query = entityManager.createQuery("select m from Menu m where m.statut=?1 and m.id=?2 order by m.nom asc ");
            query.setParameter(1, 1);
            query.setParameter(2,id);
            menus = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return menus;
    }
    @Transactional
    public List<Menu> findAllByParent(int id) {

        List<Menu> menus = new ArrayList<>();

        try {
            query = entityManager.createQuery("select m from Menu m where m.parentId=?1 order by m.nom asc ");
            query.setParameter(1, id);
            menus = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return menus;
    }

    @Transactional
    public int merge(Menu menu) {
        int data = 0;
        try {
            entityManager.merge(menu);
            entityManager.flush();

            data = 1;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return data;
    }
    @Transactional
    public int remove(Menu menu) {

        int data = 0;
        try {
            Menu menu1 = entityManager.merge(menu);
            entityManager.remove(menu1);
            data = 1;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return data;
    }
    @Transactional
    public List<Menu> findMenuActifByParent(int parentId) {
        List<Menu> menus = new ArrayList<>();

        try {
            query = entityManager.createQuery("select m from Menu m where m.parentId=?1 and m.statut=?2 order by m.nom asc ");
            query.setParameter(1, parentId);
            query.setParameter(2, 1);
            menus = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return menus;
    }
    @Transactional
    public List<Menu> findAllByModule(ModuleP moduleP) {

        List<Menu> menus = new ArrayList<>();

        try {
            query = entityManager.createQuery("select m from Menu m where m.moduleP.id=?1  order by m.nom asc ");
            query.setParameter(1, moduleP.getId());
            menus = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return menus;

    }

    @Transactional
    public List<Menu> findAllActifByModule(ModuleP moduleP) {
        return entityManager.createQuery(
                        "select distinct m from Menu m " +
                                "where m.moduleP = :module and m.statut = 1 " +
                                "order by m.parentId asc, m.nom asc",
                        Menu.class)
                .setParameter("module", moduleP)
                .getResultList();
    }

    /** Tous les écrans actifs, réservé aux traitements du profil racine. */
    @Transactional
    public List<Menu> findAllActif() {
        return entityManager.createQuery(
                        "select distinct m from Menu m " +
                                "left join fetch m.moduleP " +
                                "where m.statut = 1 and m.moduleP.statut = 1 " +
                                "order by m.moduleP.nom asc, m.parentId asc, m.nom asc",
                        Menu.class)
                .getResultList();
    }

    /** Catalogue actif d'un profil avec le module chargé pour la recherche globale. */
    @Transactional
    public List<Menu> findAllActifByProfilForSearch(Profil profil) {
        return entityManager.createQuery(
                        "select distinct m from Menu m " +
                                "join m.profils p " +
                                "left join fetch m.moduleP " +
                                "where p = :profil and m.statut = 1 and m.moduleP.statut = 1 " +
                                "order by m.moduleP.nom asc, m.parentId asc, m.nom asc",
                        Menu.class)
                .setParameter("profil", profil)
                .getResultList();
    }

    @Transactional
    public Menu findMenuById(int id) {

        Menu menu = new Menu();

        try {
            query = entityManager.createQuery("select m from Menu m where m.id=?1  ");
            query.setParameter(1, id);
            menu = (Menu) query.getSingleResult();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return menu;
    }
    @Transactional
    public List<Menu> findAllByProfil(Profil profil) {

        List<Menu> menus = new ArrayList<>();

        try {
            query = entityManager.createQuery("SELECT m FROM Menu m JOIN m.profils p WHERE p.id = ?1 AND m.statut = ?2 ORDER BY m.moduleP.nom ASC");
            query.setParameter(1, profil.getId());
            query.setParameter(2,1);
            menus = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return menus;
    }
    @Transactional
    public List<Menu> findAllActiveByProfil(Profil profil) {

        List<Menu> menus = new ArrayList<>();

        try {
            query = entityManager.createQuery("SELECT m FROM Menu m JOIN m.profils p WHERE p.id = ?1 ORDER BY m.moduleP.nom ASC");
            query.setParameter(1, profil.getId());
            menus = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return menus;
    }

    @Transactional
    public List<Menu> findAllActifByProfilAndModule(Profil profil, ModuleP module) {
        return entityManager.createQuery(
                        "select distinct m from Menu m " +
                                "join m.profils p " +
                                "where m.statut = 1 and m.moduleP = :module and p = :profil " +
                                "order by m.parentId asc, m.nom asc",
                        Menu.class)
                .setParameter("module", module)
                .setParameter("profil", profil)
                .getResultList();
    }

    @Transactional
    public List<Menu> findMenuProfilDisponible(Profil profil) {

        List<Menu> menus = new ArrayList<>();

        try {
            query = entityManager.createQuery("Select m from Menu m, Profil p where p.id = ?1 and p not member of m.profils order by m.moduleP.nom asc");
            query.setParameter(1, profil.getId());
            menus = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return menus;

    }
    @Transactional
    public int affectMenuToProfile(Menu menu1, Profil profil) {

        int result = 0;

        try {
            query = entityManager.createNativeQuery("insert into profil_menu(menu_id,profil_id) values(?1,?2)");
            query.setParameter(1, menu1.getId());
            query.setParameter(2, profil.getId());
            query.executeUpdate();

            result = 1;
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return result;

    }
    @Transactional
    public boolean retirerMenuToProfile(Profil profil, Menu menu1) {

        boolean result = false;

        try {
            query = entityManager.createNativeQuery("delete from profil_menu where menu_id=?1 and profil_id=?2 ");
            query.setParameter(1, menu1.getId());
            query.setParameter(2, profil.getId());
            query.executeUpdate();

            result = true;
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return result;
    }
    @Transactional
    public List<Menu> findAllActiveByParentProfil(Menu menu1, Profil profil) {

        List<Menu> menus = new ArrayList<>();

        try {
            query = entityManager.createQuery("select m FROM Menu m JOIN m.profils p WHERE p.id =?1 and m.parentId = ?2 and m.statut=?3");
            query.setParameter(1, profil.getId());
            query.setParameter(2, menu1.getId());
            query.setParameter(3, 1);
            menus = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return menus;
    }

}
