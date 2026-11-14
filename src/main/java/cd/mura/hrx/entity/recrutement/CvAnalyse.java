package cd.mura.hrx.entity.recrutement;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;

/** Résultat de la confrontation d'un CV aux critères d'un poste, et décision du recruteur. */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "rec_analyse", uniqueConstraints = @UniqueConstraint(name = "uk_rec_analyse", columnNames = {"poste_id", "cv_id"}))
public class CvAnalyse implements Serializable {

    public static final String EN_ATTENTE = "EN_ATTENTE", EN_COURS = "EN_COURS", TERMINEE = "TERMINEE", ERREUR = "ERREUR";
    public static final String A_TRAITER = "A_TRAITER", PRESELECTIONNE = "PRESELECTIONNE", ENTRETIEN = "ENTRETIEN", ECARTE = "ECARTE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poste_id", nullable = false)
    private Poste poste;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_id", nullable = false)
    private Cv cv;

    @Column(nullable = false, length = 12) private String statut = EN_ATTENTE;
    private Integer score;
    @Column(nullable = false) private boolean elimine;
    /** Détail par critère : [{critereId, resultat OUI|PARTIEL|NON, justification}]. */
    @Column(name = "detail_json", columnDefinition = "MEDIUMTEXT") private String detailJson;
    @Column(columnDefinition = "TEXT") private String synthese;
    @Column(columnDefinition = "TEXT") private String erreur;
    @Column(name = "date_analyse") @Temporal(TemporalType.TIMESTAMP) private Date dateAnalyse;

    @Column(nullable = false, length = 16) private String decision = A_TRAITER;
    @Column(columnDefinition = "TEXT") private String commentaire;
    @Column(name = "date_ajout") @Temporal(TemporalType.TIMESTAMP) private Date dateAjout = new Date();

    public boolean isTerminee() { return TERMINEE.equals(statut); }
    public boolean isEnErreur() { return ERREUR.equals(statut); }
    public boolean isEnCours() { return EN_COURS.equals(statut) || EN_ATTENTE.equals(statut); }

    public static String decisionLabel(String d) {
        return switch (d == null ? "" : d) {
            case PRESELECTIONNE -> "Présélectionné"; case ENTRETIEN -> "Convoqué en entretien"; case ECARTE -> "Écarté"; default -> "À traiter";
        };
    }
}
