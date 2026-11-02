package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/** Travaux réalisés en dehors des objectifs assignés (section facultative de la fiche MAF 159). */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "travail_hors_objectif")
public class TravailHorsObjectif implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Transient @EqualsAndHashCode.Include
    private final String cle = java.util.UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_id", nullable = false)
    private Evaluation evaluation;

    /** Activité / type de travaux (ex. « Missions professionnelles »). */
    @Column(length = 200) private String activite;
    @Column(nullable = false, length = 600) private String libelle;
    @Column(length = 400) private String indicateur;
    @Column(nullable = false) private int attendu = 100;
    @Column private Integer realise;
    @Column(columnDefinition = "TEXT") private String commentaire;
    /** SALARIE ou N1 : qui a déclaré le travail. */
    @Column(nullable = false, length = 10) private String origine = "SALARIE";
    @Column(nullable = false) private int ordre = 0;
}
