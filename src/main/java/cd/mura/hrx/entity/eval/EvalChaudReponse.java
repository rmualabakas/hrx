package cd.mura.hrx.entity.eval;

import cd.mura.hrx.entity.config.Users;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Réponse d'un participant au questionnaire d'une évaluation de formation ; à froid, complétée par l'avis du supérieur hiérarchique. */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "eval_chaud_reponse", uniqueConstraints = @UniqueConstraint(name = "uk_eval_chaud_rep", columnNames = {"eval_chaud_id", "participant_id"}))
public class EvalChaudReponse implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "eval_chaud_id", nullable = false)
    private EvalChaud evalChaud;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Users participant;

    @Column(name = "date_reponse") @Temporal(TemporalType.TIMESTAMP) private Date dateReponse = new Date();

    /** À froid : avis général de l'évaluateur (N+1) sur l'efficacité de la formation, de 1 à 5. */
    @Column(name = "avis_note") private Integer avisNote;
    @Column(name = "avis_commentaire", columnDefinition = "TEXT") private String avisCommentaire;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evaluateur_id")
    private Users evaluateur;
    @Column(name = "date_avis") @Temporal(TemporalType.TIMESTAMP) private Date dateAvis;

    /** Barème MAF 064 : libellé, taux de satisfaction et efficacité par note. */
    public static final String[] AVIS_LIBELLES = {"", "Très insatisfait", "Insatisfait", "Moyennement satisfait", "Satisfait", "Très satisfait"};
    public static final int[] AVIS_TAUX = {0, 20, 40, 60, 85, 100};
    public boolean isAvisDonne() { return avisNote != null; }
    public String getAvisLibelle() { return avisNote == null ? "" : AVIS_LIBELLES[avisNote]; }
    public Integer getTauxSatisfaction() { return avisNote == null ? null : AVIS_TAUX[avisNote]; }
    public boolean isEfficace() { return avisNote != null && avisNote >= 4; }
    public String getEfficaciteLabel() { return avisNote == null ? "" : isEfficace() ? "Efficace" : "Pas efficace"; }

    @OneToMany(mappedBy = "reponse", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<EvalChaudValeur> valeurs = new ArrayList<>();
}
