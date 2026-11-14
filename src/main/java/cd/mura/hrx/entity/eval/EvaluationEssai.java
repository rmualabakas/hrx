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
 * Évaluation individuelle du personnel en CDD ou en période d'essai (fiche MAF 070 DRH 02) : fin de période d'essai
 * (CDD ou CDI) ou fin de CDD. Distincte des campagnes, elle peut y être rattachée sans obligation.
 * Contenu : I. rappel des objectifs (O/R par trimestre), II. intégration (savoir, savoir-faire, savoir-être, valeurs,
 * déontologie : TB, B, P, I, PC + axes d'amélioration) et appréciation globale, III. suite à donner au contrat.
 * Circuit : EN_COURS (N+1) → TRANSMISE : visa du N+2 (facultatif) et avis du directeur, puis validation DCHT → VALIDEE.
 * Un retour DCHT la repasse EN_COURS.
 */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "evaluation_essai",
        indexes = {@Index(name = "idx_eval_essai_salarie", columnList = "salarie_id"), @Index(name = "idx_eval_essai_evaluateur", columnList = "evaluateur_id"), @Index(name = "idx_eval_essai_statut", columnList = "statut")})
public class EvaluationEssai implements Serializable {

    public static final String EN_COURS = "EN_COURS", TRANSMISE = "TRANSMISE", VALIDEE = "VALIDEE";
    /** Décisions anciennes (avant la fiche MAF 070), conservées pour l'affichage. */
    public static final String CONFIRMER = "CONFIRMER", PROLONGER = "PROLONGER", NE_PAS_CONFIRMER = "NE_PAS_CONFIRMER";
    /** III. Suite à donner au contrat de travail (fiche MAF 070). */
    public static final String CONFIRMER_CDD = "CONFIRMER_CDD", CONFIRMER_CDI = "CONFIRMER_CDI", RENOUVELER = "RENOUVELER", FIN_CONTRAT = "FIN_CONTRAT";
    /** Type de contrat évalué (cases de l'en-tête de la fiche). */
    public static final String FIN_ESSAI_CDD = "FIN_ESSAI_CDD", FIN_CDD = "FIN_CDD", FIN_ESSAI_CDI = "FIN_ESSAI_CDI";
    /** Grille d'intégration de la fiche MAF 070 (échelle TB, B, P, I, PC). */
    public static final String GRILLE_MAF070 = "MAF070";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @Version
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "salarie_id", nullable = false)
    private Users salarie;

    /** N+1 au moment de l'ouverture (figé). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evaluateur_id")
    private Users evaluateur;

    /** Rattachement facultatif à une campagne. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campagne_id")
    private Campagne campagne;

    /** FIN_ESSAI_CDD, FIN_CDD ou FIN_ESSAI_CDI ; null = ancienne évaluation de fin d'essai (CDI). */
    @Column(name = "type_evaluation", length = 20) private String typeEvaluation;
    /** Grille utilisée ; null = ancienne grille (A, EA, NA, NM). */
    @Column(length = 10) private String grille;

    @Column(name = "date_embauche") private LocalDate dateEmbauche;
    /** Contrat au moment de l'ouverture (figé) : début, échéance du CDD, date limite légale, prorogations. */
    @Column(name = "date_debut_contrat") private LocalDate dateDebutContrat;
    @Column(name = "date_fin_contrat") private LocalDate dateFinContrat;
    @Column(name = "date_limite_contrat") private LocalDate dateLimiteContrat;
    @Column(name = "nb_prorogations") private Integer nbProrogations;
    @Column(name = "date_fin_essai") private LocalDate dateFinEssai;
    /** Durée de la période d'essai retenue à l'ouverture (paramètre en vigueur à ce moment). */
    @Column(name = "duree_mois", nullable = false) private int dureeMois;

    @Column(nullable = false, length = 20)
    private String statut = EN_COURS;

    @OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("ordre asc, id asc")
    private List<EvaluationEssaiCritere> criteres = new ArrayList<>();

    @Column(name = "points_forts", columnDefinition = "TEXT") private String pointsForts;
    @Column(name = "axes_amelioration", columnDefinition = "TEXT") private String axesAmelioration;
    @Column(name = "commentaire_evaluateur", columnDefinition = "TEXT") private String commentaireEvaluateur;
    @Column(name = "commentaire_salarie", columnDefinition = "TEXT") private String commentaireSalarie;
    @Column(name = "commentaire_dcht", columnDefinition = "TEXT") private String commentaireDcht;
    @Column(name = "motif_retour", columnDefinition = "TEXT") private String motifRetour;
    /** Proposition du N+1 : CONFIRMER, PROLONGER, NE_PAS_CONFIRMER. */
    @Column(length = 20) private String decision;
    @Column(name = "prolongation_mois") private Integer prolongationMois;
    /** Motif de la décision (renouvellement ou fin de contrat). */
    @Column(name = "motif_decision", columnDefinition = "TEXT") private String motifDecision;
    /** Échéance proposée en cas de renouvellement du CDD. */
    @Column(name = "nouvelle_echeance") private LocalDate nouvelleEcheance;
    /** Signature du collaborateur (prise de connaissance de l'évaluation). */
    @Column(name = "date_signature_salarie") @Temporal(TemporalType.TIMESTAMP) private Date dateSignatureSalarie;
    @Column(name = "date_entretien") private LocalDate dateEntretien;

    /** Visa du N+2 : facultatif, n'empêche pas la suite du circuit. */
    @Column(name = "visa_n2", columnDefinition = "TEXT") private String visaN2;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "n2_id") private Users n2;
    @Column(name = "date_visa_n2") @Temporal(TemporalType.TIMESTAMP) private Date dateVisaN2;
    /** Avis du directeur de la direction du collaborateur : requis avant la validation DCHT. */
    @Column(name = "avis_directeur", columnDefinition = "TEXT") private String avisDirecteur;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "directeur_id") private Users directeur;
    @Column(name = "date_avis_directeur") @Temporal(TemporalType.TIMESTAMP) private Date dateAvisDirecteur;

    @Column(name = "cree_par") private int creePar;
    @Column(name = "date_creation") @Temporal(TemporalType.TIMESTAMP) private Date dateCreation = new Date();
    @Column(name = "date_transmission") @Temporal(TemporalType.TIMESTAMP) private Date dateTransmission;
    @Column(name = "date_validation") @Temporal(TemporalType.TIMESTAMP) private Date dateValidation;
    @Column(name = "date_maj") @Temporal(TemporalType.TIMESTAMP) private Date dateMaj;

    public boolean isEnCours() { return EN_COURS.equals(statut); }
    public boolean isTransmise() { return TRANSMISE.equals(statut); }
    public boolean isValidee() { return VALIDEE.equals(statut); }

    public String getStatutLabel() {
        return switch (statut) {
            case TRANSMISE -> dateAvisDirecteur == null ? "Avis du directeur attendu" : "Transmise à la DCHT";
            case VALIDEE -> "Validée";
            default -> "En cours (N+1)";
        };
    }
    public String getStatutClasse() {
        return switch (statut) {
            case TRANSMISE -> "hx-badge--pending";
            case VALIDEE -> "hx-badge--done";
            default -> motifRetour != null && !motifRetour.isBlank() ? "hx-badge--late" : "hx-badge--info";
        };
    }
    public static String decisionLabel(String d) {
        if (d == null) return "";
        return switch (d) {
            case CONFIRMER_CDD -> "Confirmation en CDD";
            case CONFIRMER_CDI -> "Confirmation en CDI";
            case RENOUVELER -> "Renouvellement du CDD";
            case FIN_CONTRAT -> "Fin de contrat";
            case CONFIRMER -> "Confirmer dans le poste";
            case PROLONGER -> "Prolonger la période d'essai";
            case NE_PAS_CONFIRMER -> "Ne pas confirmer";
            default -> d;
        };
    }
    public static String typeLabel(String t) {
        return switch (t == null ? FIN_ESSAI_CDI : t) {
            case FIN_ESSAI_CDD -> "CDD - fin de période d'essai";
            case FIN_CDD -> "CDD - fin de contrat";
            default -> "CDI - fin de période d'essai";
        };
    }
    public String getTypeLabel() { return typeLabel(typeEvaluation); }
    public boolean isFinCdd() { return FIN_CDD.equals(typeEvaluation); }
    public boolean isMaf070() { return GRILLE_MAF070.equals(grille); }
    /** Date qui déclenche l'évaluation : échéance du CDD ou fin de période d'essai. */
    public LocalDate getEcheance() { return isFinCdd() ? dateFinContrat : dateFinEssai; }
    /** Décisions proposées selon le type de contrat (section III de la fiche). */
    public List<String> getDecisionsPossibles() {
        if (isFinCdd()) return List.of(RENOUVELER, FIN_CONTRAT);
        if (FIN_ESSAI_CDD.equals(typeEvaluation)) return List.of(CONFIRMER_CDD, CONFIRMER_CDI, FIN_CONTRAT);
        return List.of(CONFIRMER_CDI, FIN_CONTRAT);
    }
    public String getDecisionLabel() { return decisionLabel(decision); }
    /** Vrai si la ligne ouvre une nouvelle famille de critères (en-tête de groupe à l'affichage). */
    public boolean debutFamille(EvaluationEssaiCritere k) {
        List<EvaluationEssaiCritere> l = k.isObjectif() ? getCriteresObjectifs() : getCriteresCompetences();
        int i = l.indexOf(k);
        if (i <= 0) return true;
        EvaluationEssaiCritere p = l.get(i - 1);
        return k.isObjectif() ? !java.util.Objects.equals(p.getActivite(), k.getActivite()) : !java.util.Objects.equals(p.getFamille(), k.getFamille());
    }
    /** Vrai si la ligne ouvre un sous-groupe (ex. logiciels, reporting). */
    public boolean debutGroupe(EvaluationEssaiCritere k) {
        if (k.getDescription() == null || k.getDescription().isBlank() || k.isObjectif()) return false;
        List<EvaluationEssaiCritere> l = getCriteresCompetences();
        int i = l.indexOf(k);
        return i <= 0 || debutFamille(k) || !k.getDescription().equals(l.get(i - 1).getDescription());
    }
    public long getNbNotes() { return criteres.stream().filter(c -> c.getNiveau() != null && !c.getNiveau().isBlank()).count(); }
    /** Objectifs fixés au collaborateur (repris de sa fiche d'objectifs). */
    public List<EvaluationEssaiCritere> getCriteresObjectifs() { return criteres.stream().filter(EvaluationEssaiCritere::isObjectif).toList(); }
    /** Critères de comportement et de compétence du modèle de fiche. */
    public List<EvaluationEssaiCritere> getCriteresCompetences() { return criteres.stream().filter(c -> !c.isObjectif()).toList(); }
    public long getNbNotesObjectifs() { return getCriteresObjectifs().stream().filter(c -> c.getNiveau() != null && !c.getNiveau().isBlank()).count(); }
    public long getNbNotesCompetences() { return getCriteresCompetences().stream().filter(c -> c.getNiveau() != null && !c.getNiveau().isBlank()).count(); }
}
