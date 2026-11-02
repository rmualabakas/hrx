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

/**
 * Fiche de fixation des objectifs individuels d'un salarié pour un exercice (MAF 066).
 * Une seule fiche par salarié et par exercice ; les campagnes mi-parcours et fin d'année la notent.
 */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "fiche_objectifs", uniqueConstraints = @UniqueConstraint(name = "uk_fiche_salarie_exercice", columnNames = {"salarie_id", "exercice"}))
public class FicheObjectifs implements Serializable {

    public static final String EN_COURS = "EN_COURS", SIGNEE = "SIGNEE";
    /** Moment de la fixation : en début d'exercice, ou à la prise de poste pour une recrue de l'année. */
    public static final String DEBUT_EXERCICE = "DEBUT_EXERCICE", RECRUTEMENT = "RECRUTEMENT";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @Version
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "salarie_id", nullable = false)
    private Users salarie;

    /** N+1 au moment de la fixation. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "n1_id")
    private Users n1;

    @Column(nullable = false)
    private int exercice;

    /** Mission du poste, reprise en tête de fiche. */
    @Column(columnDefinition = "TEXT")
    private String mission;

    @Column(nullable = false, length = 20)
    private String statut = EN_COURS;

    @Column(nullable = false, length = 20)
    private String motif = DEBUT_EXERCICE;

    /** Date à partir de laquelle les objectifs courent : 1er janvier, ou date d'embauche pour une recrue. */
    @Column(name = "date_effet")
    private java.time.LocalDate dateEffet;

    @OneToMany(mappedBy = "fiche", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("ordre asc, id asc")
    private List<Objectif> objectifs = new ArrayList<>();

    @Column(name = "date_signature_salarie") @Temporal(TemporalType.TIMESTAMP) private Date dateSignatureSalarie;
    @Column(name = "date_signature_n1") @Temporal(TemporalType.TIMESTAMP) private Date dateSignatureN1;
    @Column(name = "date_creation") @Temporal(TemporalType.TIMESTAMP) private Date dateCreation = new Date();
    @Column(name = "date_maj") @Temporal(TemporalType.TIMESTAMP) private Date dateMaj;

    public boolean isSignee() { return SIGNEE.equals(statut); }
    public boolean isRecrutement() { return RECRUTEMENT.equals(motif); }
    public String getMotifLabel() { return isRecrutement() ? "Fixation à la prise de poste" : "Fixation en début d'exercice"; }
    /** Premier trimestre couvert par la fiche (1 à 4). */
    public int getTrimestreDebut() { return dateEffet == null ? 1 : (dateEffet.getMonthValue() - 1) / 3 + 1; }
    public boolean isSigneeSalarie() { return dateSignatureSalarie != null; }
    public boolean isSigneeN1() { return dateSignatureN1 != null; }
    public long getNbValides() { return objectifs.stream().filter(o -> Objectif.VALIDE.equals(o.getStatut())).count(); }
    public long getNbProposes() { return objectifs.stream().filter(o -> Objectif.PROPOSE.equals(o.getStatut())).count(); }
    public int getPoidsTotal() { return objectifs.stream().filter(o -> Objectif.VALIDE.equals(o.getStatut())).mapToInt(Objectif::getPoids).sum(); }
}
