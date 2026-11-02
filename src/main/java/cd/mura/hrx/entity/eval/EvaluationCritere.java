package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/** Notation d'un critère du modèle pour une fiche donnée. */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "evaluation_critere", uniqueConstraints = @UniqueConstraint(columnNames = {"evaluation_id", "critere_id"}))
public class EvaluationCritere implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false)
    private Evaluation evaluation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "critere_id", nullable = false)
    private Critere critere;

    /** Niveaux : A = acquis, EA = en cours d'acquisition, NA = non acquis, NM = non mesuré. */
    public static final String A = "A", EA = "EA", NA = "NA", NM = "NM";

    /** Auto-positionnement du salarié. */
    @Column(name = "auto_niveau", length = 2) private String autoNiveau;
    /** Niveau retenu par le N+1. */
    @Column(length = 2) private String niveau;
    @Column(name = "axes_amelioration", columnDefinition = "TEXT") private String axesAmelioration;
    @Column(columnDefinition = "TEXT") private String commentaire;

    public static String niveauLabel(String n) {
        if (n == null) return "";
        return switch (n) { case "A" -> "Acquis"; case "EA" -> "En cours d'acquisition"; case "NA" -> "Non acquis"; case "NM" -> "Non mesuré"; default -> n; };
    }
}
