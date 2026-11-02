package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/** Besoin de formation identifié lors de l'évaluation. */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "besoin_formation")
public class BesoinFormation implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false)
    private Evaluation evaluation;

    @Column(nullable = false, length = 200)
    private String intitule;

    /** HAUTE, MOYENNE, BASSE */
    @Column(nullable = false, length = 10)
    private String priorite = "MOYENNE";

    @Column(columnDefinition = "TEXT")
    private String justification;

    /** Origine de la demande : EVALUATEUR (N+1), EVALUE (salarié), DIRECTEUR. */
    @Column(nullable = false, length = 12)
    private String origine = "EVALUATEUR";

    public String getOrigineLabel() {
        return switch (origine) { case "EVALUE" -> "Évalué"; case "DIRECTEUR" -> "Directeur"; default -> "Évaluateur"; };
    }
}
