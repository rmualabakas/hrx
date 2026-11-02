package cd.mura.hrx.service;

import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.Evaluation;
import cd.mura.hrx.entity.eval.FicheObjectifs;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.Year;
import java.util.*;

/**
 * Modifications de l'organisation : N+1, direction et directeur d'un collaborateur.
 * Les fiches encore ouvertes suivent le nouveau N+1 ; celles déjà évaluées, validées ou signées restent telles quelles.
 */
@Stateless
public class OrganisationService {

    private static final List<String> EVAL_OUVERTES = List.of(Evaluation.INITIEE, Evaluation.SOUMISE, Evaluation.RETOURNEE);

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    /** Ce que touche un changement de N+1 pour ce collaborateur. */
    public record Impact(long directs, long branche, long evaluationsOuvertes, long fichesObjectifsOuvertes) implements Serializable { }

    /** Résultat d'un changement appliqué. */
    public record Resultat(boolean n1Change, boolean directionChangee, boolean directeurChange,
                           int evaluationsTransferees, int fichesTransferees, String avant, String apres) implements Serializable { }

    /** Identifiants de tous les collaborateurs situés sous {@code chefId}, à tous les niveaux. */
    public Set<Integer> descendants(int chefId) {
        Map<Integer, List<Integer>> enfants = new HashMap<>();
        for (Object[] r : em.createQuery("select u.id, u.superieurHierarchique.id from Users u where u.statut = 1 and u.superieurHierarchique is not null", Object[].class).getResultList())
            enfants.computeIfAbsent((Integer) r[1], k -> new ArrayList<>()).add((Integer) r[0]);
        Set<Integer> out = new HashSet<>();
        Deque<Integer> pile = new ArrayDeque<>(List.of(chefId));
        while (!pile.isEmpty()) for (Integer e : enfants.getOrDefault(pile.pop(), List.of())) if (out.add(e)) pile.push(e);
        return out;
    }

    public Impact impact(int salarieId) {
        long directs = em.createQuery("select count(u) from Users u where u.statut = 1 and u.superieurHierarchique.id = :id", Long.class)
                .setParameter("id", salarieId).getSingleResult();
        long evals = em.createQuery("select count(e) from Evaluation e where e.salarie.id = :id and e.statut in :st", Long.class)
                .setParameter("id", salarieId).setParameter("st", EVAL_OUVERTES).getSingleResult();
        long fiches = em.createQuery("select count(f) from FicheObjectifs f where f.salarie.id = :id and f.exercice >= :an and f.statut <> :signee", Long.class)
                .setParameter("id", salarieId).setParameter("an", Year.now().getValue()).setParameter("signee", FicheObjectifs.SIGNEE).getSingleResult();
        return new Impact(directs, descendants(salarieId).size(), evals, fiches);
    }

    /**
     * Applique les changements demandés.
     * @param n1Id         nouveau N+1, ou null pour placer le collaborateur en tête de l'organigramme
     * @param directionId  nouvelle direction, ou null
     * @param directeur    vrai s'il dirige sa direction
     * @param transferer   vrai pour confier ses fiches ouvertes au nouveau N+1
     * @throws IllegalArgumentException si le changement créerait une boucle hiérarchique
     */
    public Resultat appliquer(int salarieId, Integer n1Id, Integer directionId, boolean directeur, boolean transferer) {
        Users u = em.find(Users.class, salarieId);
        if (u == null) throw new IllegalArgumentException("Collaborateur introuvable.");
        if (n1Id != null && (n1Id == salarieId || descendants(salarieId).contains(n1Id)))
            throw new IllegalArgumentException("Ce N+1 est déjà rattaché, directement ou non, à " + u.getNomComplet() + " : le changement créerait une boucle.");

        Users ancienN1 = u.getSuperieurHierarchique();
        Direction ancienneDir = u.getDirection();
        Integer ancienN1Id = ancienN1 == null ? null : ancienN1.getId();
        Integer ancienneDirId = ancienneDir == null ? null : ancienneDir.getId();
        String avant = "N+1 : " + (ancienN1 == null ? "aucun" : ancienN1.getNomComplet()) + " · direction : " + (ancienneDir == null ? "aucune" : ancienneDir.getNom());

        Users nouveauN1 = n1Id == null ? null : em.find(Users.class, n1Id);
        Direction nouvelleDir = directionId == null ? null : em.find(Direction.class, directionId);
        boolean n1Change = !Objects.equals(ancienN1Id, n1Id);
        boolean dirChange = !Objects.equals(ancienneDirId, directionId);

        u.setSuperieurHierarchique(nouveauN1);
        u.setDirection(nouvelleDir);
        u.setDateMaj(new Date());

        // directeur : il quitte la tête de son ancienne direction, prend (ou lâche) celle de la nouvelle
        boolean directeurChange = false;
        if (dirChange && ancienneDir != null && ancienneDir.getDirigePar() == salarieId) { ancienneDir.setDirigePar(0); ancienneDir.setDateMaj(LocalDate.now()); directeurChange = true; }
        if (nouvelleDir != null) {
            boolean estDirecteur = nouvelleDir.getDirigePar() == salarieId;
            if (directeur && !estDirecteur) { nouvelleDir.setDirigePar(salarieId); nouvelleDir.setDateMaj(LocalDate.now()); directeurChange = true; }
            else if (!directeur && estDirecteur) { nouvelleDir.setDirigePar(0); nouvelleDir.setDateMaj(LocalDate.now()); directeurChange = true; }
        }

        int evals = 0, fiches = 0;
        if (n1Change && transferer && nouveauN1 != null) {
            evals = em.createQuery("update Evaluation e set e.evaluateur = :n1 where e.salarie.id = :id and e.statut in :st")
                    .setParameter("n1", nouveauN1).setParameter("id", salarieId).setParameter("st", EVAL_OUVERTES).executeUpdate();
            fiches = em.createQuery("update FicheObjectifs f set f.n1 = :n1 where f.salarie.id = :id and f.exercice >= :an and f.statut <> :signee")
                    .setParameter("n1", nouveauN1).setParameter("id", salarieId).setParameter("an", Year.now().getValue())
                    .setParameter("signee", FicheObjectifs.SIGNEE).executeUpdate();
        }
        em.flush();
        String apres = "N+1 : " + (nouveauN1 == null ? "aucun" : nouveauN1.getNomComplet()) + " · direction : " + (nouvelleDir == null ? "aucune" : nouvelleDir.getNom())
                + (directeur && nouvelleDir != null ? " (directeur)" : "");
        return new Resultat(n1Change, dirChange, directeurChange, evals, fiches, avant, apres);
    }
}
