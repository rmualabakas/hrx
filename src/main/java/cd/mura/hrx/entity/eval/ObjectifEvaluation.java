package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/** Notation d'un objectif de la fiche de fixation lors d'une campagne (mi-parcours ou fin d'année). */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "objectif_evaluation", uniqueConstraints = @UniqueConstraint(columnNames = {"evaluation_id", "objectif_id"}))
public class ObjectifEvaluation implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false)
    private Evaluation evaluation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "objectif_id", nullable = false)
    private Objectif objectif;

    /** Pourcentage attendu à l'échéance de la campagne (100 % par défaut). */
    @Column(nullable = false) private int attendu = 100;

    /** Objectif déclaré non applicable sur la période (NA) par le salarié / retenu NA par le N+1 : exclu du calcul, commentaire obligatoire. */
    @Column(name = "na_salarie", nullable = false) private boolean nonApplicableSalarie;
    @Column(name = "na_n1", nullable = false) private boolean nonApplicableN1;

    /** Taux de réalisation déclaré par le salarié (auto-évaluation), en %. */
    @Column(name = "realisation_salarie") private Integer realisationSalarie;
    @Column(name = "commentaire_salarie", columnDefinition = "TEXT") private String commentaireSalarie;

    /** Taux retenu et note attribuée par le N+1 (grille : 100 % → 20, 85–99 → 17–19, 70–84 → 15–16, 51–69 → 10–14, < 50 → < 10). */
    @Column(name = "realisation_n1") private Integer realisationN1;
    @Column(name = "note_n1") private Double noteN1;
    @Column(name = "commentaire_n1", columnDefinition = "TEXT") private String commentaireN1;

    /** Commentaire exigé du salarié : objectif NA ou non atteint à 100 % de l'attendu. */
    public boolean isCommentaireSalarieRequis() { return nonApplicableSalarie || (realisationSalarie != null && realisationSalarie < attendu); }
    /** Commentaire exigé du N+1 : NA, objectif non atteint à 100 %, ou écart avec l'auto-évaluation. */
    public boolean isCommentaireN1Requis() {
        if (nonApplicableN1) return true;
        if (realisationN1 == null) return false;
        return realisationN1 < attendu || nonApplicableSalarie || (realisationSalarie != null && !realisationSalarie.equals(realisationN1));
    }
    /** Ligne complète côté salarié : NA ou %, et commentaire si requis. */
    public boolean isAutoEvalComplete() {
        if (!nonApplicableSalarie && realisationSalarie == null) return false;
        return !isCommentaireSalarieRequis() || (commentaireSalarie != null && !commentaireSalarie.isBlank());
    }
    /** Ligne complète côté N+1 : NA ou %, et commentaire si requis. */
    public boolean isEvalN1Complete() {
        if (!nonApplicableN1 && realisationN1 == null) return false;
        return !isCommentaireN1Requis() || (commentaireN1 != null && !commentaireN1.isBlank());
    }
    /** Un seul commentaire affiché et exporté : le plus récent l'emporte (N+1 / directeur, sinon salarié). */
    public String getCommentaireRetenu() { return commentaireN1 != null && !commentaireN1.isBlank() ? commentaireN1 : commentaireSalarie; }
    public boolean isCommentaireRetenuN1() { return commentaireN1 != null && !commentaireN1.isBlank(); }
}
