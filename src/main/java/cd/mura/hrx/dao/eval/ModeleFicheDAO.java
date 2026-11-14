package cd.mura.hrx.dao.eval;

import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.Campagne;
import cd.mura.hrx.entity.eval.Critere;
import cd.mura.hrx.entity.eval.ModeleFiche;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.Serializable;
import java.util.List;

@Stateless
public class ModeleFicheDAO implements Serializable {

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    public ModeleFiche find(int id) { return em.find(ModeleFiche.class, id); }

    public ModeleFiche findAvecCriteres(int id) {
        List<ModeleFiche> l = em.createQuery("select distinct m from ModeleFiche m left join fetch m.criteres where m.id = :id", ModeleFiche.class).setParameter("id", id).getResultList();
        return l.isEmpty() ? null : l.get(0);
    }

    public List<ModeleFiche> findAll() {
        return em.createQuery("select m from ModeleFiche m order by m.statut desc, m.libelle", ModeleFiche.class).getResultList();
    }

    public List<ModeleFiche> findActifs() {
        return em.createQuery("select m from ModeleFiche m where m.statut = 1 order by m.libelle", ModeleFiche.class).getResultList();
    }

    /** Modèle actif ciblant la catégorie, sinon null. */
    public ModeleFiche findPourCategorie(String categorie) {
        if (categorie == null || categorie.isBlank()) return null;
        List<ModeleFiche> l = em.createQuery("select m from ModeleFiche m where m.statut = 1 and m.categorieCible = :c order by m.id", ModeleFiche.class)
                .setParameter("c", categorie).setMaxResults(1).getResultList();
        return l.isEmpty() ? null : l.get(0);
    }

    /** Directeur (dirige une direction) ou manager (N+1 d'au moins un collaborateur actif). */
    /** Directeur : collaborateur désigné comme dirigeant d'au moins une direction (qu'il ait ou non des N-1). */
    public boolean estDirecteur(int userId) {
        return em.createQuery("select count(d) from Direction d where d.dirigePar = :id", Long.class).setParameter("id", userId).getSingleResult() > 0;
    }

    /**
     * Fiche à appliquer à un collaborateur dans une campagne : fiche des directeurs s'il dirige une direction,
     * sinon fiche des autres collaborateurs ; à défaut, le modèle global de sa catégorie, puis le premier modèle actif.
     */
    public ModeleFiche pourCampagne(Campagne c, Users u) {
        ModeleFiche m = null;
        if (c != null) {
            if (c.getModeleEncadrants() != null && u != null && estDirecteur(u.getId())) m = find(c.getModeleEncadrants().getId());
            if (m == null && c.getModeleParDefaut() != null) m = find(c.getModeleParDefaut().getId());
        }
        if (m == null && u != null) m = findPourCategorie(u.getCategorie());
        if (m == null) { List<ModeleFiche> actifs = findActifs(); if (!actifs.isEmpty()) m = actifs.get(0); }
        return m;
    }

    public ModeleFiche save(ModeleFiche m) {
        if (m.getId() == 0) { em.persist(m); em.flush(); return m; }
        return em.merge(m);
    }

    /** Nombre de fiches d'évaluation déjà établies avec ce modèle. */
    public long nbEvaluations(int modeleId) {
        return em.createQuery("select count(e) from Evaluation e where e.modele.id = :m", Long.class).setParameter("m", modeleId).getSingleResult();
    }

    /** Nombre de campagnes qui utilisent ce modèle (par défaut ou pour une catégorie). */
    public long nbCampagnes(int modeleId) {
        return em.createQuery("select count(c) from Campagne c where c.modeleParDefaut.id = :m or c.modeleEncadrants.id = :m", Long.class).setParameter("m", modeleId).getSingleResult();
    }

    public boolean codeExiste(String code, int saufId) {
        return em.createQuery("select count(m) from ModeleFiche m where upper(m.code) = :c and m.id <> :id", Long.class)
                .setParameter("c", code.toUpperCase()).setParameter("id", saufId).getSingleResult() > 0;
    }

    public long count() { return em.createQuery("select count(m) from ModeleFiche m", Long.class).getSingleResult(); }

    /** Modèle standard MAF 159 (compétences, valeurs du Groupe, discipline), créé une fois si aucun modèle n'existe ; modifiable ensuite. */
    public void initialiserSiVide() {
        if (count() > 0) return;
        ModeleFiche m = new ModeleFiche();
        m.setCode("MAF159_STD"); m.setLibelle("Fiche d'évaluation individuelle - modèle standard (MAF 159)");
        m.setDescription("Modèle par défaut : évaluation des objectifs, des compétences (savoir-faire, savoir-être, managériales) et de l'adhésion aux valeurs du Groupe.");
        m.setNoteMax(20); m.setPoidsObjectifs(100); m.setStatut(1);
        Object[][] defs = {
            {"SAVOIR_FAIRE", "Savoir appliquer les procédures", false}, {"SAVOIR_FAIRE", "Savoir respecter les horaires de travail", false},
            {"SAVOIR_ETRE", "Être rigoureux", false}, {"SAVOIR_ETRE", "Être discipliné", false},
            {"MANAGERIAL", "Contrôler", true}, {"MANAGERIAL", "Déléguer", true}, {"MANAGERIAL", "Décider", true},
            {"MANAGERIAL", "Motiver", true}, {"MANAGERIAL", "Former", true}, {"MANAGERIAL", "Communiquer", true},
            {"VALEURS_GROUPE", "Transparence", false}, {"VALEURS_GROUPE", "Intégrité", false}, {"VALEURS_GROUPE", "Travail", false},
            {"VALEURS_GROUPE", "Responsabilité", false}, {"VALEURS_GROUPE", "Esprit d'équipe", false},
            {"DISCIPLINE", "Savoir se conformer au Règlement intérieur", false}, {"DISCIPLINE", "Savoir se conformer au Code de déontologie", false},
        };
        int ordre = 1;
        for (Object[] d : defs) {
            Critere c = new Critere();
            c.setModele(m); c.setFamille((String) d[0]); c.setLibelle((String) d[1]); c.setReserveManagers((Boolean) d[2]); c.setOrdre(ordre++);
            m.getCriteres().add(c);
        }
        em.persist(m);
    }

    public void remove(ModeleFiche m) { ModeleFiche x = em.find(ModeleFiche.class, m.getId()); if (x != null) em.remove(x); }
}
