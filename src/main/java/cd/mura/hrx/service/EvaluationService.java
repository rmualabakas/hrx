package cd.mura.hrx.service;

import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.*;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.Date;
import java.util.List;

/** Cycle de vie d'une fiche d'évaluation MAF 159 : initiation, auto-évaluation, évaluation N+1, visa N+2, avis directeur, validation DCHT. */
@Stateless
public class EvaluationService {

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    @EJB private UserDAO userDAO;

    /** Crée la fiche d'un salarié pour une campagne : lignes d'objectifs depuis la fiche de fixation validée, critères depuis le modèle. */
    public Evaluation initier(Campagne campagne, Users salarie, Users evaluateur, ModeleFiche modele, FicheObjectifs ficheObjectifs) {
        if (campagne == null || !Campagne.OUVERTE.equals(campagne.getStatut())) {
            throw new IllegalStateException("La campagne doit être ouverte pour initier une évaluation");
        }
        if (salarie == null || salarie.getId() <= 0) {
            throw new IllegalArgumentException("Collaborateur requis");
        }
        // contrôles relus en base : seul le N+1 direct initie, et jamais pendant une évaluation d'essai non clôturée
        Users s = em.find(Users.class, salarie.getId());
        if (evaluateur == null || s == null || s.getSuperieurHierarchique() == null
                || s.getSuperieurHierarchique().getId() != evaluateur.getId() || s.getId() == evaluateur.getId()) {
            throw new SecurityException("Seul le N+1 direct du collaborateur peut initier sa fiche d'évaluation");
        }
        if (ficheObjectifs == null || !em.find(cd.mura.hrx.entity.eval.FicheObjectifs.class, ficheObjectifs.getId()).isSignee()) {
            throw new IllegalStateException("La fiche d'objectifs doit être signée par le collaborateur puis par le N+1 avant l'évaluation");
        }
        if (essaiNonClos(s.getId())) {
            throw new IllegalStateException("L'évaluation de fin de période d'essai de ce collaborateur n'est pas encore validée par la DCHT");
        }
        Evaluation e = new Evaluation();
        e.setCampagne(em.getReference(Campagne.class, campagne.getId()));
        e.setSalarie(em.getReference(Users.class, salarie.getId()));
        if (evaluateur != null) e.setEvaluateur(em.getReference(Users.class, evaluateur.getId()));
        if (modele != null) e.setModele(em.getReference(ModeleFiche.class, modele.getId()));
        if (ficheObjectifs != null) e.setFicheObjectifs(em.getReference(FicheObjectifs.class, ficheObjectifs.getId()));
        e.setDateMaj(new Date());
        em.persist(e);
        if (ficheObjectifs != null) {
            List<Objectif> objs = em.createQuery("select o from Objectif o where o.fiche.id = :f and o.statut = :s order by o.ordre, o.id", Objectif.class)
                    .setParameter("f", ficheObjectifs.getId()).setParameter("s", Objectif.VALIDE).getResultList();
            for (Objectif o : objs) {
                ObjectifEvaluation oe = new ObjectifEvaluation();
                oe.setEvaluation(e); oe.setObjectif(o); oe.setAttendu(100);
                em.persist(oe); e.getObjectifs().add(oe);
            }
        }
        if (modele != null && campagne.isEvaluerCompetences()) {
            boolean manager = !userDAO.findBySuperieur(salarie.getId()).isEmpty();
            List<Critere> crits = em.createQuery("select c from Critere c where c.modele.id = :m order by c.ordre, c.id", Critere.class).setParameter("m", modele.getId()).getResultList();
            for (Critere c : crits) {
                if (c.isReserveManagers() && !manager) continue;
                EvaluationCritere ec = new EvaluationCritere();
                ec.setEvaluation(e); ec.setCritere(c);
                em.persist(ec); e.getCriteres().add(ec);
            }
        }
        em.flush();
        return e;
    }

    public Evaluation charger(int id) {
        Evaluation e = em.find(Evaluation.class, id);
        if (e == null) return null;
        e.getCampagne().getLibelle(); e.getSalarie().getNom();
        if (e.getEvaluateur() != null) e.getEvaluateur().getNom();
        if (e.getModele() != null) e.getModele().getLibelle();
        if (e.getFicheObjectifs() != null) e.getFicheObjectifs().getMission();
        if (e.getN2() != null) e.getN2().getNom();
        if (e.getDirecteur() != null) e.getDirecteur().getNom();
        e.getObjectifs().forEach(o -> { o.getObjectif().getLibelle(); if (o.getObjectif().getNature() != null) o.getObjectif().getNature().getLibelle(); });
        e.getCriteres().forEach(c -> c.getCritere().getLibelle());
        e.getBesoinsFormation().size(); e.getTravauxHorsObjectifs().size();
        Users s = e.getSalarie();
        if (s.getDirection() != null) s.getDirection().getNom();
        if (s.getSuperieurHierarchique() != null) s.getSuperieurHierarchique().getNom();
        return e;
    }

    public Evaluation enregistrer(Evaluation e) {
        if (e == null || e.getId() <= 0) throw new IllegalArgumentException("Évaluation requise");
        if (Evaluation.VALIDEE.equals(e.getStatut())) {
            throw new IllegalStateException("Une évaluation validée est définitive");
        }
        return mergeEvaluation(e);
    }

    private Evaluation mergeEvaluation(Evaluation e) {
        e.setDateMaj(new Date());
        Evaluation m = em.merge(e);
        em.flush();
        return m;
    }

    /** Le salarié soumet son auto-évaluation. */
    public Evaluation soumettre(Evaluation e) {
        requireState(e, Evaluation.INITIEE, Evaluation.RETOURNEE);
        e.setStatut(Evaluation.SOUMISE); e.setDateSoumission(new Date()); e.setMotifRetour(null);
        return mergeEvaluation(e);
    }

    /** Le N+1 clôt son évaluation : calcul du score objectifs → note /20 → appréciation. */
    public Evaluation evaluer(Evaluation e) {
        requireState(e, Evaluation.SOUMISE, Evaluation.INITIEE, Evaluation.RETOURNEE);
        calculer(e);
        if (e.getNiveauAtteinte() == null && e.getScoreObjectifs() != null) e.setNiveauAtteinte(Evaluation.niveauAtteintePour(e.getScoreObjectifs()));
        e.setStatut(Evaluation.EVALUEE); e.setDateEvaluation(new Date()); e.setDateTransmissionDirecteur(new Date());
        if (e.getDateEntretien() == null) e.setDateEntretien(java.time.LocalDate.now());   // date d'évaluation par défaut : jour de la transmission
        return mergeEvaluation(e);
    }

    /** Le N+1 rouvre son évaluation (tant que la DCHT n'a pas validé) pour la rééditer ou la corriger. */
    public Evaluation rouvrirParN1(Evaluation e) {
        requireState(e, Evaluation.EVALUEE);
        e.setStatut(Evaluation.SOUMISE); e.setDateEvaluation(null); e.setDateTransmissionDirecteur(null);
        return mergeEvaluation(e);
    }

    public void calculer(Evaluation e) {
        double sommePoids = 0, somme = 0; int n = 0;
        boolean pondere = e.getObjectifs().stream().anyMatch(o -> o.getObjectif().getPoids() > 0);
        for (ObjectifEvaluation o : e.getObjectifs()) {
            if (o.isNonApplicableN1() || o.getRealisationN1() == null) continue;
            double taux = o.getAttendu() <= 0 ? o.getRealisationN1() : Math.min(100.0, 100.0 * o.getRealisationN1() / o.getAttendu());
            double p = pondere ? o.getObjectif().getPoids() : 1;
            somme += taux * p; sommePoids += p; n++;
        }
        if (n == 0) { e.setScoreObjectifs(null); e.setNoteGlobale(null); e.setAppreciation(null); return; }
        double pct = sommePoids == 0 ? 0 : somme / sommePoids;
        e.setScoreObjectifs(Math.round(pct * 10) / 10.0);
        double note = Math.round(Evaluation.noteSur20(pct) * 10) / 10.0;
        e.setNoteGlobale(note);
        e.setAppreciation(Evaluation.appreciationPour(note));
    }

    public Evaluation viser(Evaluation e, Users n2, String visa) {
        requireState(e, Evaluation.EVALUEE);
        if (e.getDateVisaN2() != null) throw new IllegalStateException("Le visa N+2 est déjà enregistré");
        e.setN2(em.getReference(Users.class, n2.getId())); e.setVisaN2(visa); e.setDateVisaN2(new Date());
        return mergeEvaluation(e);
    }

    public Evaluation avisDirecteur(Evaluation e, Users directeur, String avis) {
        requireState(e, Evaluation.EVALUEE);
        if (e.getDateAvisDirecteur() != null) throw new IllegalStateException("L'avis du directeur est déjà enregistré");
        int dir = directeurDe(e);
        if (dir > 0 && (directeur == null || directeur.getId() != dir))
            throw new SecurityException("Seul le directeur de la direction du collaborateur peut donner cet avis");
        e.setDirecteur(em.getReference(Users.class, directeur.getId())); e.setAvisDirecteur(avis); e.setDateAvisDirecteur(new Date());
        return mergeEvaluation(e);
    }

    public Evaluation valider(Evaluation e, String commentaireDcht) {
        requireState(e, Evaluation.EVALUEE);
        Evaluation enBase = em.find(Evaluation.class, e.getId());
        if (directeurDe(e) > 0 && (enBase == null || enBase.getDateAvisDirecteur() == null))
            throw new IllegalStateException("La fiche ne peut être validée qu'après l'avis du directeur");
        e.setStatut(Evaluation.VALIDEE); e.setDateValidation(new Date()); e.setCommentaireDcht(commentaireDcht);
        return mergeEvaluation(e);
    }

    /** Retour pour correction : vers le salarié (auto-évaluation) ou vers le N+1 selon l'étape. */
    public Evaluation retourner(Evaluation e, String motif, boolean versSalarie) {
        requireState(e, Evaluation.INITIEE, Evaluation.SOUMISE, Evaluation.EVALUEE, Evaluation.RETOURNEE);
        if (motif == null || motif.isBlank()) throw new IllegalArgumentException("Le motif de retour est requis");
        e.setStatut(versSalarie ? Evaluation.RETOURNEE : Evaluation.SOUMISE); e.setMotifRetour(motif);
        if (!versSalarie) { e.setDateEvaluation(null); }
        return mergeEvaluation(e);
    }

    public <T> T fusionner(T x) {
        requireAnnexeType(x);
        return em.merge(x);
    }
    public <T> T persister(T x) {
        requireAnnexeType(x);
        em.persist(x); em.flush(); return x;
    }
    public void supprimer(Class<?> type, int id) {
        if (type != TravailHorsObjectif.class && type != BesoinFormation.class) {
            throw new SecurityException("Type de suppression non autorisé");
        }
        Object x = em.find(type, id);
        if (x != null) em.remove(x);
    }

    private void requireAnnexeType(Object value) {
        if (!(value instanceof TravailHorsObjectif) && !(value instanceof BesoinFormation)) {
            throw new SecurityException("Type d'annexe non autorisé");
        }
    }

    private void requireState(Evaluation evaluation, String... allowedStates) {
        if (evaluation == null || evaluation.getId() <= 0) throw new IllegalArgumentException("Évaluation requise");
        for (String state : allowedStates) {
            if (state.equals(evaluation.getStatut())) return;
        }
        throw new IllegalStateException("Transition impossible depuis le statut " + evaluation.getStatut());
    }

    /** Évaluation de fin d'essai / de CDD ouverte et pas encore validée par la DCHT. */
    public boolean essaiNonClos(int salarieId) {
        return em.createQuery("select count(e) from EvaluationEssai e where e.salarie.id = :s and e.statut <> :v", Long.class)
                .setParameter("s", salarieId).setParameter("v", cd.mura.hrx.entity.eval.EvaluationEssai.VALIDEE).getSingleResult() > 0;
    }

    /** Parmi ces collaborateurs, ceux dont l'évaluation d'essai n'est pas clôturée. */
    public java.util.Set<Integer> essaisNonClos(java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return new java.util.HashSet<>();
        return new java.util.HashSet<>(em.createQuery("select distinct e.salarie.id from EvaluationEssai e where e.salarie.id in :ids and e.statut <> :v", Integer.class)
                .setParameter("ids", ids).setParameter("v", cd.mura.hrx.entity.eval.EvaluationEssai.VALIDEE).getResultList());
    }

    /** Un directeur est attendu et n'a pas encore donné son avis : la DCHT ne peut pas valider. */
    public boolean avisDirecteurEnAttente(Evaluation e) {
        if (e == null || directeurDe(e) <= 0) return false;
        Evaluation x = em.find(Evaluation.class, e.getId());
        return x == null || x.getDateAvisDirecteur() == null;
    }

    /** Id du directeur de la direction du salarié (relu en base), 0 si aucun. */
    private int directeurDe(Evaluation e) {
        Users s = e == null || e.getSalarie() == null ? null : em.find(Users.class, e.getSalarie().getId());
        return s == null || s.getDirection() == null ? 0 : s.getDirection().getDirigePar();
    }
}
