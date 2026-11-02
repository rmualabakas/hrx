package cd.mura.hrx.service;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;

/** Suppressions « profondes » du référentiel d'habilitations, dans une seule transaction */
@Stateless
public class HabilitationAdminService {

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    /** Nombre de sous-menus directs d'un menu. */
    public long countChildren(int menuId) {
        return em.createQuery("select count(m) from Menu m where m.parentId = :id", Long.class)
                .setParameter("id", menuId).getSingleResult();
    }

    /** Nombre de profils auxquels le menu est affecté. */
    public long countProfilsForMenu(int menuId) {
        Number n = (Number) em.createNativeQuery("select count(*) from profil_menu where menu_id = ?1")
                .setParameter(1, menuId).getSingleResult();
        return n.longValue();
    }

    public void deletePrivilege(int privilegeId) {
        em.createNativeQuery("delete from profil_privilege where privilege_id = ?1").setParameter(1, privilegeId).executeUpdate();
        em.createNativeQuery("delete from privilege where id = ?1").setParameter(1, privilegeId).executeUpdate();
    }

    /** Supprime un menu, ses privilèges et toutes leurs affectations aux profils. Refuse s'il a des sous-menus. */
    public void deleteMenu(int menuId) {
        if (countChildren(menuId) > 0) {
            throw new IllegalStateException("Ce menu possède des sous-menus : supprimez-les d'abord.");
        }
        em.createNativeQuery("delete from profil_privilege where privilege_id in (select id from privilege where menu_id = ?1)")
                .setParameter(1, menuId).executeUpdate();
        em.createNativeQuery("delete from privilege where menu_id = ?1").setParameter(1, menuId).executeUpdate();
        em.createNativeQuery("delete from profil_menu where menu_id = ?1").setParameter(1, menuId).executeUpdate();
        em.createNativeQuery("delete from menu where id = ?1").setParameter(1, menuId).executeUpdate();
    }

    /** Supprime un module et l'intégralité de son arborescence (sous-menus compris). */
    public void deleteModule(int moduleId) {
        @SuppressWarnings("unchecked")
        List<Number> ids = em.createNativeQuery("select id from menu where module_id = ?1 order by parent_id desc, id desc")
                .setParameter(1, moduleId).getResultList();
        // parent_id desc : les sous-menus (parent_id > 0) partent avant leurs parents.
        for (Number id : ids) {
            int menuId = id.intValue();
            em.createNativeQuery("delete from profil_privilege where privilege_id in (select id from privilege where menu_id = ?1)")
                    .setParameter(1, menuId).executeUpdate();
            em.createNativeQuery("delete from privilege where menu_id = ?1").setParameter(1, menuId).executeUpdate();
            em.createNativeQuery("delete from profil_menu where menu_id = ?1").setParameter(1, menuId).executeUpdate();
            em.createNativeQuery("delete from menu where id = ?1").setParameter(1, menuId).executeUpdate();
        }
        em.createNativeQuery("delete from module where id = ?1").setParameter(1, moduleId).executeUpdate();
    }

    // Habilitations d'un profil

    @SuppressWarnings("unchecked")
    public java.util.Set<Integer> menuIdsForProfil(int profilId) {
        List<Number> rows = em.createNativeQuery("select menu_id from profil_menu where profil_id = ?1").setParameter(1, profilId).getResultList();
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (Number n : rows) ids.add(n.intValue());
        return ids;
    }

    @SuppressWarnings("unchecked")
    public java.util.Set<Integer> privilegeIdsForProfil(int profilId) {
        List<Number> rows = em.createNativeQuery("select privilege_id from profil_privilege where profil_id = ?1").setParameter(1, profilId).getResultList();
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (Number n : rows) ids.add(n.intValue());
        return ids;
    }

    /** Donne un menu à un profil ; un sous-menu entraîne son parent (sinon il serait invisible dans la navigation). */
    public void grantMenu(int profilId, int menuId) {
        em.createNativeQuery("insert into profil_menu (menu_id, profil_id) select ?1, ?2 where not exists (select 1 from profil_menu where menu_id = ?1 and profil_id = ?2)")
                .setParameter(1, menuId).setParameter(2, profilId).executeUpdate();
        Number parent = (Number) em.createNativeQuery("select parent_id from menu where id = ?1").setParameter(1, menuId).getSingleResult();
        if (parent != null && parent.intValue() > 0) grantMenu(profilId, parent.intValue());
    }

    /** Retire un menu, ses privilèges et ses sous-menus au profil. */
    public void revokeMenu(int profilId, int menuId) {
        em.createNativeQuery("delete from profil_privilege where profil_id = ?1 and privilege_id in (select id from privilege where menu_id = ?2)")
                .setParameter(1, profilId).setParameter(2, menuId).executeUpdate();
        em.createNativeQuery("delete from profil_menu where profil_id = ?1 and menu_id = ?2").setParameter(1, profilId).setParameter(2, menuId).executeUpdate();
        @SuppressWarnings("unchecked")
        List<Number> children = em.createNativeQuery("select id from menu where parent_id = ?1").setParameter(1, menuId).getResultList();
        for (Number c : children) revokeMenu(profilId, c.intValue());
    }

    /** Donne un privilège (et le menu porteur) à un profil. */
    public void grantPrivilege(int profilId, int privilegeId) {
        em.createNativeQuery("insert into profil_privilege (privilege_id, profil_id) select ?1, ?2 where not exists (select 1 from profil_privilege where privilege_id = ?1 and profil_id = ?2)")
                .setParameter(1, privilegeId).setParameter(2, profilId).executeUpdate();
        Number menuId = (Number) em.createNativeQuery("select menu_id from privilege where id = ?1").setParameter(1, privilegeId).getSingleResult();
        if (menuId != null) grantMenu(profilId, menuId.intValue());
    }

    public void revokePrivilege(int profilId, int privilegeId) {
        em.createNativeQuery("delete from profil_privilege where profil_id = ?1 and privilege_id = ?2").setParameter(1, profilId).setParameter(2, privilegeId).executeUpdate();
    }

    /** Tous les privilèges actifs d'un menu, en une fois. */
    public void grantAllPrivileges(int profilId, int menuId) {
        grantMenu(profilId, menuId);
        em.createNativeQuery("""
                insert into profil_privilege (privilege_id, profil_id)
                select p.id, ?1 from privilege p where p.menu_id = ?2 and p.statut = 1
                  and not exists (select 1 from profil_privilege pp where pp.privilege_id = p.id and pp.profil_id = ?1)
                """).setParameter(1, profilId).setParameter(2, menuId).executeUpdate();
    }

    /** Tout un module (menus + privilèges) au profil. */
    public void grantModule(int profilId, int moduleId) {
        @SuppressWarnings("unchecked")
        List<Number> menus = em.createNativeQuery("select id from menu where module_id = ?1 and statut = 1").setParameter(1, moduleId).getResultList();
        for (Number m : menus) grantAllPrivileges(profilId, m.intValue());
    }

    public void revokeModule(int profilId, int moduleId) {
        @SuppressWarnings("unchecked")
        List<Number> menus = em.createNativeQuery("select id from menu where module_id = ?1").setParameter(1, moduleId).getResultList();
        for (Number m : menus) revokeMenu(profilId, m.intValue());
    }

    /** Copie l'intégralité des habilitations d'un profil vers un autre (en plus de l'existant). */
    public void copyHabilitations(int fromProfilId, int toProfilId) {
        em.createNativeQuery("insert into profil_menu (menu_id, profil_id) select pm.menu_id, ?2 from profil_menu pm where pm.profil_id = ?1 and not exists (select 1 from profil_menu x where x.menu_id = pm.menu_id and x.profil_id = ?2)")
                .setParameter(1, fromProfilId).setParameter(2, toProfilId).executeUpdate();
        em.createNativeQuery("insert into profil_privilege (privilege_id, profil_id) select pp.privilege_id, ?2 from profil_privilege pp where pp.profil_id = ?1 and not exists (select 1 from profil_privilege x where x.privilege_id = pp.privilege_id and x.profil_id = ?2)")
                .setParameter(1, fromProfilId).setParameter(2, toProfilId).executeUpdate();
    }

    public long countUsersForProfil(int profilId) {
        return em.createQuery("select count(u) from Users u where u.profil.id = :id", Long.class).setParameter("id", profilId).getSingleResult();
    }

    /** Supprime un profil sans utilisateur, avec ses affectations. */
    public void deleteProfil(int profilId) {
        if (countUsersForProfil(profilId) > 0) throw new IllegalStateException("Des utilisateurs sont encore rattachés à ce profil.");
        em.createNativeQuery("delete from profil_privilege where profil_id = ?1").setParameter(1, profilId).executeUpdate();
        em.createNativeQuery("delete from profil_menu where profil_id = ?1").setParameter(1, profilId).executeUpdate();
        em.createNativeQuery("delete from profil where id = ?1").setParameter(1, profilId).executeUpdate();
    }
}
