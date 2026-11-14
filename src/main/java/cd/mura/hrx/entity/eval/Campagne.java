package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;

/** Campagne d'évaluation : mi-parcours (6 mois) ou fin d'année (12 mois) d'un exercice. */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "campagne", indexes = @Index(name = "idx_campagne_statut", columnList = "statut"))
public class Campagne implements Serializable {

    public static final String BROUILLON = "BROUILLON", OUVERTE = "OUVERTE", CLOTUREE = "CLOTUREE", ARCHIVEE = "ARCHIVEE";
    public static final String MI_PARCOURS = "MI_PARCOURS", FIN_ANNEE = "FIN_ANNEE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @Version
    private long version;

    @Column(nullable = false, length = 150)
    private String libelle;

    /** Exercice évalué (année). */
    @Column(nullable = false)
    private int exercice;

    @Column(nullable = false, length = 20)
    private String type = MI_PARCOURS;

    @Column(name = "date_debut")
    private LocalDate dateDebut;

    /** Échéance de remise des fiches : au-delà, les fiches non finalisées sont en retard. */
    @Column(name = "date_fin")
    private LocalDate dateFin;

    @Column(nullable = false, length = 20)
    private String statut = BROUILLON;

    /** Nombre d'évaluations attendues sur la campagne (population évaluable arrêtée par la DCHT) ; base du taux de réalisation. */
    @Column(name = "effectif_attendu", nullable = false)
    private int effectifAttendu = 0;

    /** Inclure l'évaluation des compétences et des valeurs du Groupe (sections II et III de la méthodologie) ; la fiche mi-parcours BGFI ne les comporte pas. */
    @Column(name = "evaluer_competences", nullable = false)
    private boolean evaluerCompetences = false;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Fiche des autres collaborateurs (non directeurs). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modele_defaut_id")
    private ModeleFiche modeleParDefaut;

    /** Fiche des directeurs (dirigeants d'au moins une direction) ; à défaut, le modèle des autres collaborateurs. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modele_encadrants_id")
    private ModeleFiche modeleEncadrants;

    @Column(name = "cree_par") private int creePar;
    @Column(name = "date_creation") @Temporal(TemporalType.TIMESTAMP) private Date dateCreation = new Date();
    @Column(name = "date_maj") @Temporal(TemporalType.TIMESTAMP) private Date dateMaj;
    @Column(name = "date_lancement") @Temporal(TemporalType.TIMESTAMP) private Date dateLancement;
    @Column(name = "date_cloture") @Temporal(TemporalType.TIMESTAMP) private Date dateCloture;

    public boolean isOuverte() { return OUVERTE.equals(statut); }
    public boolean isEnRetard() { return isOuverte() && dateFin != null && LocalDate.now().isAfter(dateFin); }
    public String getTypeLabel() { return FIN_ANNEE.equals(type) ? "Fin d'année" : "Mi-parcours"; }
}
