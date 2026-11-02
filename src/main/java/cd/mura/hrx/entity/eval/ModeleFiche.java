package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Modèle de fiche d'évaluation, affecté selon la catégorie du salarié (cadre, maîtrise…). */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "modele_fiche")
public class ModeleFiche implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @Column(nullable = false, length = 30, unique = true)
    private String code;

    @Column(nullable = false, length = 150)
    private String libelle;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Catégorie ciblée (CADRE_SUP, CADRE, MAITRISE, EMPLOYE) ; null = tous. */
    @Column(name = "categorie_cible", length = 30)
    private String categorieCible;

    /** Note maximale de l'échelle (ex. 5). */
    @Column(name = "note_max", nullable = false)
    private int noteMax = 20;

    /** Pondération des objectifs dans la note globale, en % ; le reste va aux critères. */
    @Column(name = "poids_objectifs", nullable = false)
    private int poidsObjectifs = 60;

    @Column(nullable = false)
    private int statut = 1;

    @OneToMany(mappedBy = "modele", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("ordre asc, id asc")
    private List<Critere> criteres = new ArrayList<>();

    @Column(name = "cree_par") private int creePar;
    @Column(name = "date_creation") @Temporal(TemporalType.TIMESTAMP) private Date dateCreation = new Date();
    @Column(name = "date_maj") @Temporal(TemporalType.TIMESTAMP) private Date dateMaj;

    public int getPoidsCriteres() { return 100 - poidsObjectifs; }
}
