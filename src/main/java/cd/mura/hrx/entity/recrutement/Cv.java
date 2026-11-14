package cd.mura.hrx.entity.recrutement;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;

/**
 * CV de la CVthèque : le fichier d'origine (stocké sur disque) et le profil structuré qu'en a extrait l'IA.
 * Le profil est réutilisé pour tous les postes : un CV n'est lu qu'une fois.
 */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "rec_cv", indexes = {
        @Index(name = "idx_rec_cv_sha", columnList = "sha256", unique = true),
        @Index(name = "idx_rec_cv_dossier", columnList = "dossier_id")})
public class Cv implements Serializable {

    public static final String A_LIRE = "A_LIRE", EN_COURS = "EN_COURS", LU = "LU", ERREUR = "ERREUR";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @Column(name = "nom_fichier", nullable = false, length = 255) private String nomFichier;
    @Column(name = "type_mime", length = 120) private String typeMime;
    @Column(nullable = false) private long taille;
    @Column(nullable = false, length = 64) private String sha256;
    @Column(name = "chemin", nullable = false, length = 500) private String chemin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id")
    private CvDossier dossier;

    @Column(nullable = false) private boolean archive;
    @Column(length = 300) private String etiquettes;
    @Column(columnDefinition = "TEXT") private String notes;

    @Column(name = "importe_par") private int importePar;
    @Column(name = "date_import") @Temporal(TemporalType.TIMESTAMP) private Date dateImport = new Date();

    // lecture par l'IA
    @Column(name = "statut_lecture", nullable = false, length = 10) private String statutLecture = A_LIRE;
    @Column(name = "erreur_lecture", columnDefinition = "TEXT") private String erreurLecture;
    @Column(name = "date_lecture") @Temporal(TemporalType.TIMESTAMP) private Date dateLecture;
    @Column(name = "modele_ia", length = 60) private String modeleIa;
    @Column(name = "profil_json", columnDefinition = "MEDIUMTEXT") private String profilJson;

    // champs du profil repris pour la recherche et l'affichage
    @Column(name = "candidat_nom", length = 200) private String candidatNom;
    @Column(length = 200) private String email;
    @Column(length = 60) private String telephone;
    @Column(length = 200) private String localisation;
    @Column(name = "titre_profil", length = 300) private String titreProfil;
    @Column(name = "date_naissance") private LocalDate dateNaissance;
    private Integer age;
    @Column(name = "experience_annees") private Double experienceAnnees;
    @Column(name = "niveau_etudes") private Integer niveauEtudes;
    @Column(name = "diplome", length = 300) private String diplome;

    public boolean isLu() { return LU.equals(statutLecture); }
    public boolean isEnErreur() { return ERREUR.equals(statutLecture); }
    public String getNomAffiche() { return candidatNom == null || candidatNom.isBlank() ? nomFichier : candidatNom; }
    public String getExtension() { int i = nomFichier == null ? -1 : nomFichier.lastIndexOf('.'); return i < 0 ? "" : nomFichier.substring(i + 1).toLowerCase(); }
    public String getTailleLisible() { return taille < 1024 * 1024 ? Math.max(1, taille / 1024) + " Ko" : String.format("%.1f Mo", taille / 1048576.0); }
    public String getStatutLectureLabel() {
        return switch (statutLecture) { case LU -> "Lu"; case EN_COURS -> "Lecture en cours"; case ERREUR -> "Erreur de lecture"; default -> "Non lu"; };
    }
}
