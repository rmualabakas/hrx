package cd.mura.hrx.dao.eval;

import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.FicheObjectifs;
import cd.mura.hrx.entity.eval.Objectif;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Stateless
public class ObjectifDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    public FicheObjectifs findFiche(int id) {
        List<FicheObjectifs> l = em.createQuery("""
                select distinct f from FicheObjectifs f join fetch f.salarie
                left join fetch f.n1 left join fetch f.objectifs where f.id = :id
                """, FicheObjectifs.class).setParameter("id", id).getResultList();
        return l.isEmpty() ? null : l.get(0);
    }

    public FicheObjectifs findFiche(int salarieId, int exercice) {
        List<FicheObjectifs> l = em.createQuery("""
                select distinct f from FicheObjectifs f join fetch f.salarie
                left join fetch f.n1 left join fetch f.objectifs where f.salarie.id = :s and f.exercice = :ex
                """, FicheObjectifs.class).setParameter("s", salarieId).setParameter("ex", exercice).getResultList();
        return l.isEmpty() ? null : l.get(0);
    }

    /** Fiches d'un exercice pour une liste de salariés (vue équipe). */
    public List<FicheObjectifs> findFiches(int exercice, List<Integer> salarieIds) {
        if (salarieIds == null || salarieIds.isEmpty()) return List.of();
        return em.createQuery("""
                select distinct f from FicheObjectifs f join fetch f.salarie left join fetch f.objectifs
                where f.exercice = :ex and f.salarie.id in :ids
                """, FicheObjectifs.class).setParameter("ex", exercice).setParameter("ids", salarieIds).getResultList();
    }

    public List<Integer> exercices(int salarieId) {
        return em.createQuery("select f.exercice from FicheObjectifs f where f.salarie.id = :s order by f.exercice desc", Integer.class).setParameter("s", salarieId).getResultList();
    }

    public FicheObjectifs creerFiche(Users salarie, int exercice, Users n1, String mission) {
        FicheObjectifs f = new FicheObjectifs();
        f.setSalarie(em.getReference(Users.class, salarie.getId()));
        if (n1 != null) f.setN1(em.getReference(Users.class, n1.getId()));
        f.setExercice(exercice);
        f.setMission(mission);
        // Recrue de l'exercice → fixation à la prise de poste, objectifs à compter de l'embauche ; sinon début d'exercice.
        java.time.LocalDate embauche = salarie.getDateEmbauche() == null ? null
                : salarie.getDateEmbauche().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        if (embauche != null && embauche.getYear() == exercice) { f.setMotif(FicheObjectifs.RECRUTEMENT); f.setDateEffet(embauche); }
        else { f.setMotif(FicheObjectifs.DEBUT_EXERCICE); f.setDateEffet(java.time.LocalDate.of(exercice, 1, 1)); }
        f.setDateMaj(new Date());
        em.persist(f); em.flush();
        return f;
    }

    public FicheObjectifs saveFiche(FicheObjectifs f) { f.setDateMaj(new Date()); return em.merge(f); }

    public Objectif save(Objectif o) {
        if (o.getId() == 0) { em.persist(o); em.flush(); return o; }
        return em.merge(o);
    }

    public Objectif find(int id) { return em.find(Objectif.class, id); }

    public void remove(int id) { Objectif o = em.find(Objectif.class, id); if (o != null) em.remove(o); }

    public List<Objectif> objectifs(int ficheId) {
        return em.createQuery("select o from Objectif o where o.fiche.id = :f order by o.ordre, o.id", Objectif.class).setParameter("f", ficheId).getResultList();
    }

    public FicheObjectifs refFiche(int id) { return em.getReference(FicheObjectifs.class, id); }
}
