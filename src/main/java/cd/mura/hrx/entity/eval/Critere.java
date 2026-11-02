package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/** Critère d'un modèle de fiche (compétence métier, comportement, management…). */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "critere")
public class Critere implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "modele_id", nullable = false)
    private ModeleFiche modele;

    @Column(nullable = false, length = 200)
    private String libelle;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Rubrique de la fiche MAF 159 : SAVOIR_FAIRE, SAVOIR_ETRE, MANAGERIAL (section II) ; VALEURS_GROUPE, DISCIPLINE (section III). */
    @Column(nullable = false, length = 30)
    private String famille = "SAVOIR_FAIRE";

    /** Réservé au personnel d'encadrement (compétences managériales). */
    @Column(name = "reserve_managers", nullable = false)
    private boolean reserveManagers = false;

    public String getFamilleLabel() {
        return switch (famille) {
            case "SAVOIR_ETRE" -> "Savoir-être";
            case "MANAGERIAL" -> "Compétences managériales";
            case "VALEURS_GROUPE" -> "Valeurs du Groupe";
            case "DISCIPLINE" -> "Respect de la discipline";
            default -> "Savoir-faire";
        };
    }
    /** Section de la fiche : II (compétences) ou III (adhésion aux valeurs). */
    public String getSection() { return "VALEURS_GROUPE".equals(famille) || "DISCIPLINE".equals(famille) ? "III" : "II"; }

    /** Poids relatif du critère (somme libre, normalisée au calcul). */
    @Column(nullable = false)
    private int poids = 1;

    @Column(nullable = false)
    private int ordre = 0;
}
