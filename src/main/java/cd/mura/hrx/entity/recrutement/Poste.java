package cd.mura.hrx.entity.recrutement;

import cd.mura.hrx.entity.config.Direction;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Poste à pourvoir : ses critères de sélection servent à classer les CV reçus. */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "rec_poste", indexes = @Index(name = "idx_rec_poste_statut", columnList = "statut"))
public class Poste implements Serializable {

    public static final String OUVERT = "OUVERT", CLOS = "CLOS", POURVU = "POURVU", ARCHIVE = "ARCHIVE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @Version private long version;

    @Column(nullable = false, length = 200) private String intitule;
    @Column(length = 50) private String reference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "direction_id")
    private Direction direction;

    /** Contexte complémentaire libre (avant la fiche structurée, c'était la seule description). */
    @Column(columnDefinition = "TEXT") private String description;

    // ---- Fiche de poste au modèle BGFIBank (offre d'emploi)
    @Column(name = "nombre_postes") private Integer nombrePostes = 1;
    @Column(name = "lieu_affectation", length = 200) private String lieuAffectation;
    /** Supérieur hiérarchique direct (« rattaché au Chef d'Agence »). */
    @Column(length = 200) private String rattachement;
    /** Date limite de dépôt : le lendemain, les candidatures sont closes automatiquement. */
    @Column(name = "date_limite") @Temporal(TemporalType.DATE) private Date dateLimite;
    @Column(columnDefinition = "TEXT") private String missions;
    @Column(columnDefinition = "TEXT") private String activites;
    @Column(name = "profil_recherche", columnDefinition = "TEXT") private String profilRecherche;
    @Column(columnDefinition = "TEXT") private String savoir;
    @Column(name = "savoir_faire", columnDefinition = "TEXT") private String savoirFaire;
    @Column(name = "savoir_etre", columnDefinition = "TEXT") private String savoirEtre;
    @Column(columnDefinition = "TEXT") private String comportements;
    @Column(name = "dossier_candidature", columnDefinition = "TEXT") private String dossierCandidature;
    @Column(columnDefinition = "TEXT") private String contact;
    @Column(nullable = false, length = 20) private String statut = OUVERT;

    /** Score à partir duquel un CV est jugé bon (vert) ou moyen (orange). */
    @Column(name = "seuil_bon", nullable = false) private int seuilBon = 75;
    @Column(name = "seuil_moyen", nullable = false) private int seuilMoyen = 50;

    @OneToMany(mappedBy = "poste", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("ordre ASC, id ASC")
    private List<PosteCritere> criteres = new ArrayList<>();

    @Column(name = "cree_par") private int creePar;
    @Column(name = "date_creation") @Temporal(TemporalType.TIMESTAMP) private Date dateCreation = new Date();
    @Column(name = "date_maj") @Temporal(TemporalType.TIMESTAMP) private Date dateMaj;

    public boolean isOuvert() { return OUVERT.equals(statut); }
    public boolean isClos() { return CLOS.equals(statut); }
    public boolean isArchive() { return ARCHIVE.equals(statut); }
    public String getStatutLabel() { return POURVU.equals(statut) ? "Pourvu" : ARCHIVE.equals(statut) ? "Archivé" : CLOS.equals(statut) ? "Candidatures closes" : "Ouvert"; }

    /** La date limite est dépassée (les candidatures sont acceptées jusqu'au jour inclus). */
    public boolean isEchu() {
        return dateLimite != null && new java.sql.Date(dateLimite.getTime()).toLocalDate().isBefore(java.time.LocalDate.now());
    }

    /** Contexte du poste transmis à l'IA : seules les données visibles dans le formulaire. */
    public String getFicheTexte() {
        StringBuilder sb = new StringBuilder();
        ligne(sb, "Lieu d'affectation", lieuAffectation);
        ligne(sb, "Rattachement hiérarchique", rattachement);
        return sb.toString().trim();
    }
    private static void ligne(StringBuilder sb, String titre, String v) { if (v != null && !v.isBlank()) sb.append(titre).append(" : ").append(v.trim()).append('\n'); }
    private static void bloc(StringBuilder sb, String titre, String v) { if (v != null && !v.isBlank()) sb.append('\n').append(titre).append(" :\n").append(v.trim()).append('\n'); }

    /** Éléments d'une rubrique saisie à raison d'un élément par ligne (puces retirées). */
    public static java.util.List<String> elements(String v) {
        if (v == null || v.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(v.split("\\r?\\n")).map(l -> l.replaceFirst("^\\s*([-•*·▪]|\\d+[.)])\\s*", "").trim()).filter(l -> !l.isEmpty()).toList();
    }
}
