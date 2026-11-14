package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;

/**
 * Suivi d'une action attendue d'un acteur (fixation des objectifs, auto-évaluation, évaluation du N+1, avis du
 * directeur, évaluation de fin de période d'essai) : rappels avant l'échéance, relances après, et niveau
 * d'alerte qui monte avec le nombre de relances jusqu'à « critique » (fiche de non-conformité annoncée).
 * Une ligne est close (resolue) dès que l'action n'est plus attendue.
 */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "relance_suivi",
        uniqueConstraints = @UniqueConstraint(name = "uk_relance_cle", columnNames = "cle"),
        indexes = {@Index(name = "idx_relance_dest", columnList = "destinataire_id, resolue")})
public class Relance implements Serializable {

    /** Niveaux d'alerte, du plus faible au plus fort. */
    public static final String RAPPEL = "RAPPEL", RELANCE = "RELANCE", URGENT = "URGENT", CRITIQUE = "CRITIQUE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    /** Type, dossier et destinataire : une seule ligne ouverte par action attendue. */
    @Column(nullable = false, length = 120) private String cle;
    @Column(nullable = false, length = 30) private String type;
    @Column(name = "ref_id") private int refId;
    @Column(name = "salarie_id") private int salarieId;
    @Column(name = "destinataire_id", nullable = false) private int destinataireId;
    @Column(nullable = false, length = 300) private String libelle;
    @Column(length = 300) private String lien;
    private LocalDate echeance;

    @Column(name = "nb_rappels", nullable = false) private int nbRappels;
    @Column(name = "nb_relances", nullable = false) private int nbRelances;
    @Column(nullable = false, length = 12) private String niveau = RAPPEL;
    @Column(name = "dernier_envoi") private LocalDate dernierEnvoi;
    /** Date à laquelle le niveau critique a été atteint (fiche de non-conformité annoncée). */
    @Column(name = "date_critique") private LocalDate dateCritique;

    @Column(nullable = false) private boolean resolue;
    @Column(name = "date_resolution") @Temporal(TemporalType.TIMESTAMP) private Date dateResolution;
    @Column(name = "date_creation") @Temporal(TemporalType.TIMESTAMP) private Date dateCreation = new Date();

    public static int rang(String niveau) {
        return switch (niveau == null ? RAPPEL : niveau) { case CRITIQUE -> 3; case URGENT -> 2; case RELANCE -> 1; default -> 0; };
    }
    public static String niveauLabel(String niveau) {
        return switch (niveau == null ? RAPPEL : niveau) {
            case CRITIQUE -> "Critique"; case URGENT -> "Urgent"; case RELANCE -> "Relance"; default -> "Rappel";
        };
    }
    public String getNiveauLabel() { return niveauLabel(niveau); }
    public boolean isCritique() { return CRITIQUE.equals(niveau); }
    public long getJoursRetard() { return echeance == null ? 0 : Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(echeance, LocalDate.now())); }
}
