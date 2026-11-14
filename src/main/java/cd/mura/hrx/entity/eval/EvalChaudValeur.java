package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/** Réponse à une question : note (1-5), oui/non (1/0) ou texte. */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "eval_chaud_valeur")
public class EvalChaudValeur implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reponse_id", nullable = false)
    private EvalChaudReponse reponse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private EvalChaudQuestion question;

    private Integer note;
    @Column(columnDefinition = "TEXT") private String texte;
}
