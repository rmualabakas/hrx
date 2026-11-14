package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/** Critère apprécié dans une évaluation de fin de période d'essai (copié du modèle de fiche à l'ouverture). */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "evaluation_essai_critere")
public class EvaluationEssaiCritere implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_essai_id", nullable = false)
    private EvaluationEssai evaluation;

    @Column(nullable = false, length = 255) private String libelle;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(length = 30) private String famille;
    /** Niveau retenu par le N+1 : TB, B, P, I, PC (fiche MAF 070) ; A, EA, NA, NM sur l'ancienne grille. */
    @Column(length = 2) private String niveau;
    /** Axes d'amélioration (critères) ou commentaire (objectifs). */
    @Column(columnDefinition = "TEXT") private String commentaire;

    /** Objectifs (I. Rappel des objectifs) : activité, indicateur, objectif (O) et réalisé (R) par trimestre. */
    @Column(length = 255) private String activite;
    @Column(columnDefinition = "TEXT") private String indicateur;
    @Column(length = 60) private String o1;
    @Column(length = 60) private String o2;
    @Column(length = 60) private String o3;
    @Column(length = 60) private String o4;
    @Column(length = 60) private String r1;
    @Column(length = 60) private String r2;
    @Column(length = 60) private String r3;
    @Column(length = 60) private String r4;
    @Column(nullable = false) private int ordre;

    /** Famille des objectifs fixés repris de la fiche d'objectifs du collaborateur. */
    public static final String OBJECTIF = "OBJECTIF";
    public boolean isObjectif() { return OBJECTIF.equals(famille); }

    public String getFamilleLabel() { return familleLabel(famille); }
    public static String familleLabel(String famille) {
        if (famille == null) return "";
        return switch (famille) {
            case OBJECTIF -> "Objectif fixé";
            case "SAVOIR" -> "Savoir (connaissances théoriques)";
            case "SAVOIR_ETRE" -> "Savoir-être (attitudes et comportements)";
            case "MANAGERIAL" -> "Compétences managériales";
            case "VALEURS_GROUPE" -> "Respect des valeurs du Groupe";
            case "DISCIPLINE" -> "Déontologie et règlement intérieur";
            default -> "Savoir-faire (connaissances techniques)";
        };
    }
}
