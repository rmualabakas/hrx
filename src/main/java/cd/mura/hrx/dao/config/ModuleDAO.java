package cd.mura.hrx.dao.config;

import cd.mura.hrx.entity.config.ModuleP;
import cd.mura.hrx.entity.config.Profil;
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
public class ModuleDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    protected EntityManager entityManager;
    protected Query query;

    public int persist(ModuleP moduleP) {

        int response = 0;

        try {
            entityManager.persist(moduleP);
            entityManager.flush();
            response = 1;
        } catch (Exception x) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }

        return response;
    }

    public ModuleP find(int id) {

        ModuleP data = new ModuleP();
        try {

            query = entityManager.createQuery("select V from ModuleP V where V.id=?1");
            query.setParameter(1, id);
            data = (ModuleP) query.getSingleResult();

        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return data;
    }

    public List<ModuleP> findAllActive() {

        List<ModuleP> modulePS = new ArrayList<>();

        try {
            query = entityManager.createQuery("select V from ModuleP V where V.statut=?1 order by V.nom");
            query.setParameter(1, 1);
            modulePS = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return modulePS;
    }

    public List<ModuleP> findAllActiveWithMenus() {
        return entityManager.createQuery(
                        "select distinct mo from ModuleP mo " +
                                "join mo.menus m " +
                                "where mo.statut = 1 and m.statut = 1 " +
                                "order by mo.nom asc",
                        ModuleP.class)
                .getResultList();
    }

    public List<ModuleP> findAll() {

        List<ModuleP> modulePS = new ArrayList<>();

        try {
            query = entityManager.createQuery("select V from ModuleP V order by V.nom asc ");
            modulePS = query.getResultList();

        } catch (Exception e) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }

        return modulePS;
    }

    public int merge(ModuleP moduleP) {
        int data = 0;
        try {
            entityManager.merge(moduleP);
            entityManager.flush();

            data = 1;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return data;
    }

    public int remove(ModuleP moduleP) {

        int data = 0;
        try {
            ModuleP moduleP1 = entityManager.merge(moduleP);
            entityManager.remove(moduleP1);
            data = 1;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return data;
    }

    public List<ModuleP> findModulActifByProfil(Profil profil) {
        return entityManager.createQuery(
                        "select distinct mo from ModuleP mo " +
                                "join mo.menus m " +
                                "join m.profils p " +
                                "where mo.statut = 1 and m.statut = 1 and p = :profil " +
                                "order by mo.nom asc",
                        ModuleP.class)
                .setParameter("profil", profil)
                .getResultList();
    }

}
