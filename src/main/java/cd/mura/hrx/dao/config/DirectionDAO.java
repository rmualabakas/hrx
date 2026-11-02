package cd.mura.hrx.dao.config;

import cd.mura.hrx.entity.config.Direction;
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
public class DirectionDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    protected EntityManager entityManager;
    protected Query query;

    public Direction persist(Direction direction){

        Direction response=new Direction();
        try {
            entityManager.persist(direction);
            entityManager.flush();
            entityManager.refresh(direction);
            response= direction;
        } catch (Exception x) {

            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }

        return response;
    }

    public int merge(Direction direction) {
        int data = 0;
        try {
            entityManager.merge(direction);
            entityManager.flush();

            data = 1;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return data;
    }

    public Direction find(int id) {
        if (id <= 0) {
            return new Direction();
        }

        Direction direction = entityManager.find(Direction.class, id);
        return direction != null ? direction : new Direction();
    }

    public List<Direction> findAll() {

        List<Direction> data =new ArrayList<>();

        try{
            query = entityManager.createQuery("select U from Direction U order by U.nom") ;
            data=query.getResultList();

        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
        return data;
    }

    /** Directions dont l'utilisateur est le directeur désigné. */
    public List<Direction> findDirigeesPar(int userId) {
        try {
            return entityManager.createQuery("select d from Direction d where d.dirigePar = :u and d.statut = 1 order by d.nom", Direction.class)
                    .setParameter("u", userId).getResultList();
        } catch (Exception e) { java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e); return new ArrayList<>(); }
    }

    public List<Direction> findAllActif() {

        List<Direction> data =new ArrayList<>();

        try{
            query = entityManager.createQuery("select U from Direction U where  U.statut=?1 order by U.nom") ;
            query.setParameter(1,1);
            data=query.getResultList();

        } catch (Exception e) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
        return data;
    }

    public int remove(Direction direction) {

        int data = 0;
        try {
            Direction Direction1 = entityManager.merge(direction);
            entityManager.remove(Direction1);
            data = 1;
        } catch (Exception x) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", x);
        }
        return data;
    }

}
