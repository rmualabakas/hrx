package cd.mura.hrx.dao.eval;

import cd.mura.hrx.entity.eval.NatureObjectif;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.Serializable;
import java.util.List;

@Stateless
public class NatureObjectifDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    public NatureObjectif find(int id) { return em.find(NatureObjectif.class, id); }

    public List<NatureObjectif> findAll() {
        return em.createQuery("select n from NatureObjectif n order by n.ordreGroupe, n.groupe, n.ordre, n.id", NatureObjectif.class).getResultList();
    }

    /** Rubriques actives applicables à un exercice, dans l'ordre de la fiche. */
    public List<NatureObjectif> findPourExercice(int exercice) {
        return findAll().stream().filter(n -> n.applicable(exercice)).toList();
    }

    public long countObjectifs(int natureId) {
        return em.createQuery("select count(o) from Objectif o where o.nature.id = :n", Long.class).setParameter("n", natureId).getSingleResult();
    }

    public boolean codeExiste(String code, int saufId) {
        return em.createQuery("select count(n) from NatureObjectif n where upper(n.code) = :c and n.id <> :id", Long.class)
                .setParameter("c", code.toUpperCase()).setParameter("id", saufId).getSingleResult() > 0;
    }

    public NatureObjectif save(NatureObjectif n) {
        if (n.getId() == 0) { em.persist(n); em.flush(); return n; }
        return em.merge(n);
    }

    public void remove(int id) { NatureObjectif n = em.find(NatureObjectif.class, id); if (n != null) em.remove(n); }

    public long count() { return em.createQuery("select count(n) from NatureObjectif n", Long.class).getSingleResult(); }

    /** Rubriques de la fiche MAF 066 en vigueur, créées une seule fois si la table est vide ; modifiables ensuite par la DCHT. */
    public void initialiserSiVide() {
        if (count() > 0) return;
        Object[][] defs = {
            {"ACTIVITES_OPERATIONNELLES", "I. Tronc commun", 1, "A. Activités opérationnelles", "Il s'agit de la mise en œuvre des objectifs spécifiques poursuivis par votre fonction", null, 1},
            {"MAITRISE_RISQUES", "I. Tronc commun", 1, "B. Maîtrise des risques associés", "Il s'agit de la maîtrise des risques générés par vos activités hors plan d'action", null, 2},
            {"PLAN_ACTION", "II. Objectifs liés au management", 2, "A. Mise en œuvre du plan d'action", "Il s'agit des objectifs liés à la réalisation du plan d'action de la fonction", null, 1},
            {"COORDINATION_EQUIPES", "II. Objectifs liés au management", 2, "B. Coordination des équipes", null, "Si pilote de processus ou manager de proximité", 2},
            {"TRANSFERT_COMPETENCES", "II. Objectifs liés au management", 2, "C. Transfert des compétences", null, "Si manager de proximité ou pilote de processus", 3},
            {"TENUE_PROCESSUS", "III. Autres objectifs spécifiques", 3, "A. Tenue d'un processus", null, "Si pilote de processus", 1},
            {"CONDUITE_PROJET", "III. Autres objectifs spécifiques", 3, "B. Conduite d'un projet", null, "Si chef de projet ou membre d'équipe projet", 2},
            {"TRAVAUX_FILIERE", "III. Autres objectifs spécifiques", 3, "C. Participation aux travaux en filière", null, "Si responsable de fonction", 3},
            {"GESTION_BUDGET", "III. Autres objectifs spécifiques", 3, "D. Gestion d'un budget", null, "Si ordonnateur de budget", 4},
        };
        for (Object[] d : defs) {
            NatureObjectif n = new NatureObjectif();
            n.setCode((String) d[0]); n.setGroupe((String) d[1]); n.setOrdreGroupe((Integer) d[2]); n.setLibelle((String) d[3]);
            n.setAide((String) d[4]); n.setCondition((String) d[5]); n.setOrdre((Integer) d[6]);
            em.persist(n);
        }
    }
}
