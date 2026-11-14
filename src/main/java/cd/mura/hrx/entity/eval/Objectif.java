package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;

/**
 * Objectif individuel d'une fiche de fixation (MAF 066) : nature, activité, moyens, indicateur,
 * période d'atteinte (T1–T4), périodicité de mesure. Fixé par le N+1, ou proposé par le salarié puis validé par le N+1.
 */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "objectif", indexes = @Index(name = "idx_objectif_fiche", columnList = "fiche_id"))
public class Objectif implements Serializable {

    public static final String VALIDE = "VALIDE", PROPOSE = "PROPOSE", REFUSE = "REFUSE";
    public static final String ORIGINE_N1 = "N1", ORIGINE_SALARIE = "SALARIE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    /** Clé d'identité côté vue (les objectifs non encore persistés ont tous id = 0). */
    @Transient
    @EqualsAndHashCode.Include
    private final String cle = java.util.UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fiche_id", nullable = false)
    private FicheObjectifs fiche;

    /** Rubrique de la fiche (paramétrable). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "nature_id", nullable = false)
    @org.hibernate.annotations.NotFound(action = org.hibernate.annotations.NotFoundAction.IGNORE)
    private NatureObjectif nature;

    /** Activité ou axe auquel se rattache l'objectif (ex. « Gestion du parc informatique »). */
    @Column(length = 200)
    private String activite;

    @Column(nullable = false, length = 600)
    private String libelle;

    /** Moyens mis à disposition (procédures, outils, budget…). */
    @Column(columnDefinition = "TEXT")
    private String moyens;

    /** Indicateur de mesure (ex. « Nombre d'incidents traités dans les délais / total »). */
    @Column(length = 400)
    private String indicateur;

    /** Cible attendue (ex. « 100 % », « ≤ 48 h »). */
    @Column(length = 100)
    private String cible;

    /** Code de {@link Periodicite}. */
    @Column(nullable = false, length = 20)
    private String periodicite = Periodicite.TRIMESTRIEL.name();

    /** Période d'atteinte : trimestres concernés. */
    @Column(nullable = false) private boolean t1 = true;
    @Column(nullable = false) private boolean t2 = true;
    @Column(nullable = false) private boolean t3 = true;
    @Column(nullable = false) private boolean t4 = true;

    @Column(name = "date_echeance") private LocalDate echeance;

    /** Pondération en % dans la note « objectifs » (0 = pondération égale entre objectifs validés). */
    @Column(nullable = false)
    private int poids = 0;

    @Column(nullable = false, length = 10)
    private String origine = ORIGINE_N1;

    @Column(nullable = false, length = 10)
    private String statut = VALIDE;

    @Column(name = "motif_refus", length = 500)
    private String motifRefus;

    @Column(nullable = false)
    private int ordre = 0;

    // contestation par le collaborateur d'un objectif fixé par sa hiérarchie
    public static final String CONTEST_EN_ATTENTE = "EN_ATTENTE", CONTEST_REVISE = "REVISE", CONTEST_MAINTENU = "MAINTENU";
    /** Motifs de contestation proposés au collaborateur (code → libellé). */
    public static final java.util.Map<String, String> MOTIFS_CONTESTATION = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>() {{
        put("CIBLE", "Cible non atteignable");
        put("MOYENS", "Moyens ou ressources insuffisants");
        put("PERIMETRE", "Hors de mon périmètre ou de ma fonction");
        put("INDICATEUR", "Indicateur non mesurable ou ambigu");
        put("DELAI", "Délai ou période irréaliste");
        put("DEPENDANCE", "Dépend de facteurs hors de mon contrôle");
        put("PONDERATION", "Pondération inadaptée");
        put("AUTRE", "Autre motif");
    }});

    @Column(name = "contestation_statut", length = 12) private String contestationStatut;
    @Column(name = "contestation_motif", length = 20) private String contestationMotif;
    @Column(name = "contestation_commentaire", columnDefinition = "TEXT") private String contestationCommentaire;
    @Column(name = "date_contestation") @Temporal(TemporalType.TIMESTAMP) private Date dateContestation;
    @Column(name = "contestation_reponse", columnDefinition = "TEXT") private String contestationReponse;
    @Column(name = "date_reponse_contestation") @Temporal(TemporalType.TIMESTAMP) private Date dateReponseContestation;

    @Column(name = "cree_par") private int creePar;
    @Column(name = "date_creation") @Temporal(TemporalType.TIMESTAMP) private Date dateCreation = new Date();
    @Column(name = "valide_par") private Integer validePar;
    @Column(name = "date_validation") @Temporal(TemporalType.TIMESTAMP) private Date dateValidation;

    public Periodicite getPeriodiciteEnum() { return Periodicite.of(periodicite); }
    public boolean isValide() { return VALIDE.equals(statut); }
    public boolean isPropose() { return PROPOSE.equals(statut); }
    public boolean isParSalarie() { return ORIGINE_SALARIE.equals(origine); }
    /**
     * Pourcentage attendu déduit de la cible : « 85 », « 85 % », « 85% » → 85.
     * Une cible qui n'est pas un pourcentage (ex. « 5 rapports ») ou vide donne 100 %.
     */
    public int getCiblePourcentage() {
        if (cible == null) return 100;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d{1,3})(?:[.,]\\d+)?\\s*%?\\s*$").matcher(cible);
        if (!m.matches()) return 100;
        int v = Integer.parseInt(m.group(1));
        return v >= 1 && v <= 100 ? v : 100;
    }
    public boolean isContestationEnAttente() { return CONTEST_EN_ATTENTE.equals(contestationStatut); }
    public boolean isContestationRevise() { return CONTEST_REVISE.equals(contestationStatut); }
    public boolean isContestationMaintenue() { return CONTEST_MAINTENU.equals(contestationStatut); }
    public String getContestationMotifLabel() { return contestationMotif == null ? "" : MOTIFS_CONTESTATION.getOrDefault(contestationMotif, contestationMotif); }
    public String getPeriodeLabel() {
        StringBuilder b = new StringBuilder();
        if (t1) b.append("T1 "); if (t2) b.append("T2 "); if (t3) b.append("T3 "); if (t4) b.append("T4 ");
        return b.toString().trim();
    }
}
