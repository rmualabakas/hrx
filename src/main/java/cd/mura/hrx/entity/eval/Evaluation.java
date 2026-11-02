package cd.mura.hrx.entity.eval;

import cd.mura.hrx.entity.config.Users;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Fiche d'évaluation d'un salarié pour une campagne.
 * Circuit : INITIEE → (salarié) SOUMISE → (N+1) EVALUEE → (DCHT) VALIDEE ; RETOURNEE renvoie au N+1.
 */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "evaluation",
        uniqueConstraints = @UniqueConstraint(name = "uk_eval_campagne_salarie", columnNames = {"campagne_id", "salarie_id"}),
        indexes = {@Index(name = "idx_eval_evaluateur", columnList = "evaluateur_id"), @Index(name = "idx_eval_statut", columnList = "statut")})
public class Evaluation implements Serializable {

    public static final String INITIEE = "INITIEE", SOUMISE = "SOUMISE", EVALUEE = "EVALUEE",
            RETOURNEE = "RETOURNEE", VALIDEE = "VALIDEE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    /** Empêche deux acteurs d'écraser silencieusement leurs modifications. */
    @Version
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campagne_id", nullable = false)
    private Campagne campagne;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "salarie_id", nullable = false)
    private Users salarie;

    /** N+1 au moment de l'initiation (figé : un changement d'organigramme ne réécrit pas l'historique). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evaluateur_id")
    private Users evaluateur;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modele_id")
    private ModeleFiche modele;

    @Column(nullable = false, length = 20)
    private String statut = INITIEE;

    /** Fiche de fixation des objectifs de l'exercice, notée par cette évaluation. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fiche_objectifs_id")
    private FicheObjectifs ficheObjectifs;

    @OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ObjectifEvaluation> objectifs = new ArrayList<>();

    @OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<EvaluationCritere> criteres = new ArrayList<>();

    @OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BesoinFormation> besoinsFormation = new ArrayList<>();

    @OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("ordre asc, id asc")
    private List<TravailHorsObjectif> travauxHorsObjectifs = new ArrayList<>();

    /** Scores calculés (sur noteMax du modèle), nuls tant que le N+1 n'a pas noté. */
    @Column(name = "score_objectifs") private Double scoreObjectifs;
    @Column(name = "score_criteres") private Double scoreCriteres;
    @Column(name = "note_globale") private Double noteGlobale;
    @Column(length = 40) private String appreciation;

    @Column(name = "commentaire_salarie", columnDefinition = "TEXT") private String commentaireSalarie;
    @Column(name = "commentaire_evaluateur", columnDefinition = "TEXT") private String commentaireEvaluateur;
    @Column(name = "commentaire_dcht", columnDefinition = "TEXT") private String commentaireDcht;
    /** Case cochée par le N+1 : ATTEINTS, MOYENNEMENT, FAIBLEMENT, NON_ATTEINTS. */
    @Column(name = "niveau_atteinte", length = 20) private String niveauAtteinte;
    /** Case cochée par le salarié : BIEN_POSTE, ACCOMPAGNEMENT, CHANGER_POSTE, AUTRES. */
    @Column(name = "souhait_salarie", length = 20) private String souhaitSalarie;
    /** Avis du directeur : demandes à valider, autres propositions. */
    @Column(name = "demandes_a_valider", columnDefinition = "TEXT") private String demandesAValider;
    @Column(name = "autres_propositions", columnDefinition = "TEXT") private String autresPropositions;
    @Column(name = "date_transmission_directeur") @Temporal(TemporalType.TIMESTAMP) private Date dateTransmissionDirecteur;

    /** Propositions et recommandations du N+1 (mutation, formation, avancement…). */
    @Column(columnDefinition = "TEXT") private String propositions;
    /** Visa du supérieur hiérarchique intermédiaire (N+2). */
    @Column(name = "visa_n2", columnDefinition = "TEXT") private String visaN2;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "n2_id") private Users n2;
    @Column(name = "date_visa_n2") @Temporal(TemporalType.TIMESTAMP) private Date dateVisaN2;
    /** Avis du directeur. */
    @Column(name = "avis_directeur", columnDefinition = "TEXT") private String avisDirecteur;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "directeur_id") private Users directeur;
    @Column(name = "date_avis_directeur") @Temporal(TemporalType.TIMESTAMP) private Date dateAvisDirecteur;
    /** Date de l'entretien d'évaluation. */
    @Column(name = "date_entretien") private LocalDate dateEntretien;
    @Column(name = "motif_retour", columnDefinition = "TEXT") private String motifRetour;

    @Column(name = "date_initiation") @Temporal(TemporalType.TIMESTAMP) private Date dateInitiation = new Date();
    @Column(name = "date_soumission") @Temporal(TemporalType.TIMESTAMP) private Date dateSoumission;
    @Column(name = "date_evaluation") @Temporal(TemporalType.TIMESTAMP) private Date dateEvaluation;
    @Column(name = "date_validation") @Temporal(TemporalType.TIMESTAMP) private Date dateValidation;
    @Column(name = "date_maj") @Temporal(TemporalType.TIMESTAMP) private Date dateMaj;

    /** Date de l'évaluation affichée et exportée : date d'entretien saisie, sinon date de transmission par le N+1. */
    public LocalDate getDateEvaluationRetenue() {
        if (dateEntretien != null) return dateEntretien;
        return dateEvaluation == null ? null : dateEvaluation.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
    }
    public boolean isFinalisee() { return VALIDEE.equals(statut); }
    private boolean avant(Date d) {
        if (d == null || campagne == null || campagne.getDateFin() == null) return true;
        return !d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().isAfter(campagne.getDateFin());
    }
    /** Auto-évaluation soumise avant l'échéance de la campagne. */
    public boolean isSoumiseATemps() { return dateSoumission != null && avant(dateSoumission); }
    /** Évaluation du N+1 transmise avant l'échéance. */
    public boolean isEvalueeATemps() { return dateEvaluation != null && avant(dateEvaluation); }
    /** Fiche validée avant l'échéance. */
    public boolean isValideeATemps() { return dateValidation != null && avant(dateValidation); }
    /** Jours restants avant l'échéance (négatif = dépassée). */
    public long getJoursRestants() { return campagne == null || campagne.getDateFin() == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), campagne.getDateFin()); }
    public boolean isAvecCompetences() { return criteres != null && !criteres.isEmpty(); }
    public static String niveauAtteinteLabel(String v) {
        if (v == null) return "";
        return switch (v) { case "ATTEINTS" -> "Objectifs atteints"; case "MOYENNEMENT" -> "Objectifs moyennement atteints"; case "FAIBLEMENT" -> "Objectifs faiblement atteints"; case "NON_ATTEINTS" -> "Objectifs non atteints"; default -> v; };
    }
    public static String souhaitLabel(String v) {
        if (v == null) return "";
        return switch (v) { case "BIEN_POSTE" -> "Bien dans son poste / poursuivre"; case "ACCOMPAGNEMENT" -> "Besoin d'accompagnement"; case "CHANGER_POSTE" -> "Souhaite changer de poste"; case "AUTRES" -> "Autres souhaits"; default -> v; };
    }
    /** Niveau d'atteinte déduit du taux de réalisation, proposé au N+1. */
    public static String niveauAtteintePour(double pct) { return pct >= 85 ? "ATTEINTS" : pct >= 70 ? "MOYENNEMENT" : pct >= 51 ? "FAIBLEMENT" : "NON_ATTEINTS"; }
    public boolean isEnRetard() {
        if (isFinalisee() || campagne == null || campagne.getDateFin() == null) return false;
        return LocalDate.now().isAfter(campagne.getDateFin());
    }
    public String getStatutLabel() {
        return switch (statut) {
            case SOUMISE -> "Auto-évaluation soumise";
            case EVALUEE -> "Évaluée par le N+1";
            case RETOURNEE -> "Retournée pour correction";
            case VALIDEE -> "Validée";
            default -> "Auto-évaluation à renseigner";
        };
    }
    /** Appréciation selon la grille BGFI (note /20). */
    public static String appreciationPour(double note) {
        if (note >= 20) return "Excellent";
        if (note >= 17) return "Bon";
        if (note >= 15) return "Moyennement bon";
        if (note >= 10) return "Insuffisant";
        return "Mauvais";
    }
    /** Conversion du taux de réalisation (%) en note /20 suivant la grille : 100 → 20 ; 85–99 → 17–19 ; 70–84 → 15–16 ; 51–69 → 10–14 ; < 50 → < 10. */
    public static double noteSur20(double pct) {
        pct = Math.max(0, Math.min(100, pct));
        if (pct >= 100) return 20;
        if (pct >= 85) return 17 + (pct - 85) / 14 * 2;
        if (pct >= 70) return 15 + (pct - 70) / 14;
        if (pct >= 51) return 10 + (pct - 51) / 18 * 4;
        return pct / 50 * 9.9;
    }
}
