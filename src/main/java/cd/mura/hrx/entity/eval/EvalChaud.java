package cd.mura.hrx.entity.eval;

import cd.mura.hrx.entity.config.Users;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;

/**
 * Évaluation d'une formation : petite campagne (dates d'ouverture et de clôture) adressée aux participants,
 * avec son questionnaire. À chaud (MAF 063, satisfaction en fin de formation, anonyme) ou à froid
 * (MAF 064, efficacité et impact quelques mois après, complétée par l'avis du supérieur hiérarchique).
 */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "eval_chaud", indexes = @Index(name = "idx_eval_chaud_statut", columnList = "statut"))
public class EvalChaud implements Serializable {

    public static final String BROUILLON = "BROUILLON", OUVERTE = "OUVERTE", CLOTUREE = "CLOTUREE";
    public static final String CHAUD = "CHAUD", FROID = "FROID";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @Version
    private long version;

    /** Intitulé de la formation évaluée. */
    @Column(nullable = false, length = 200) private String intitule;
    @Column(length = 150) private String organisme;
    @Column(length = 150) private String formateur;
    @Column(name = "date_formation_debut") private LocalDate dateFormationDebut;
    @Column(name = "date_formation_fin") private LocalDate dateFormationFin;

    /** Période de réponse au questionnaire. */
    @Column(name = "date_debut") private LocalDate dateDebut;
    @Column(name = "date_fin") private LocalDate dateFin;

    @Column(nullable = false, length = 20) private String statut = BROUILLON;
    @Column(columnDefinition = "TEXT") private String consignes;
    /** CHAUD (défaut, valeur nulle des évaluations existantes) ou FROID. */
    @Column(length = 10) private String nature;
    /** Objectifs de la formation, un par ligne (rappelés en Q.1 de la fiche à froid). */
    @Column(columnDefinition = "TEXT") private String objectifs;
    /** Évaluation à chaud dont l'évaluation à froid est la suite (facultatif). */
    @Column(name = "source_id") private Integer sourceId;
    /** Lieu de la formation (facultatif). */
    @Column(length = 150) private String lieu;

    /** Population : collaborateurs ayant suivi la formation. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "eval_chaud_participant", joinColumns = @JoinColumn(name = "eval_chaud_id"), inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<Users> participants = new LinkedHashSet<>();

    @OneToMany(mappedBy = "evalChaud", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("ordre asc, id asc")
    private List<EvalChaudQuestion> questions = new ArrayList<>();

    @Column(name = "cree_par") private int creePar;
    @Column(name = "date_creation") @Temporal(TemporalType.TIMESTAMP) private Date dateCreation = new Date();
    @Column(name = "date_maj") @Temporal(TemporalType.TIMESTAMP) private Date dateMaj;
    @Column(name = "date_lancement") @Temporal(TemporalType.TIMESTAMP) private Date dateLancement;
    @Column(name = "date_cloture") @Temporal(TemporalType.TIMESTAMP) private Date dateCloture;

    public boolean isFroid() { return FROID.equals(nature); }
    public boolean isChaud() { return !isFroid(); }
    public String getNatureLabel() { return isFroid() ? "À froid" : "À chaud"; }
    public List<String> getObjectifsListe() {
        List<String> l = new ArrayList<>();
        if (objectifs != null) for (String x : objectifs.split("\\R")) if (!x.isBlank()) l.add(x.trim());
        return l;
    }

    public boolean isBrouillon() { return BROUILLON.equals(statut); }
    public boolean isOuverte() { return OUVERTE.equals(statut); }
    public boolean isCloturee() { return CLOTUREE.equals(statut); }
    /** Ouverte et dans sa période de réponse. */
    public boolean isRepondable() {
        LocalDate auj = LocalDate.now();
        return isOuverte() && (dateDebut == null || !auj.isBefore(dateDebut)) && (dateFin == null || !auj.isAfter(dateFin));
    }
    public boolean isEchue() { return isOuverte() && dateFin != null && LocalDate.now().isAfter(dateFin); }
    public String getStatutLabel() { return isOuverte() ? (isEchue() ? "Échue" : "Ouverte") : isCloturee() ? "Clôturée" : "Brouillon"; }
}
