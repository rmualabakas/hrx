package cd.mura.hrx.entity.recrutement;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

/**
 * Critère de sélection d'un poste. L'âge, l'expérience et le niveau d'études sont évalués par calcul
 * à partir du profil extrait du CV ; les autres types sont appréciés par l'IA.
 */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "rec_critere")
public class PosteCritere implements Serializable {

    public static final String AGE = "AGE", EXPERIENCE = "EXPERIENCE", DIPLOME = "DIPLOME", DOMAINE = "DOMAINE",
            COMPETENCE = "COMPETENCE", LANGUE = "LANGUE", CERTIFICATION = "CERTIFICATION", LOCALISATION = "LOCALISATION", AUTRE = "AUTRE";
    public static final List<String> TYPES = List.of(AGE, EXPERIENCE, DIPLOME, DOMAINE, COMPETENCE, LANGUE, CERTIFICATION, LOCALISATION, AUTRE);

    /** Niveaux d'études : la valeur est le rang comparé au niveau le plus élevé du candidat. */
    public static final String[] NIVEAUX = {"Non précisé", "Secondaire (sans bac)", "Baccalauréat", "Bac+2 (BTS, DUT, graduat)",
            "Bac+3 (licence)", "Bac+4 (maîtrise)", "Bac+5 (master, ingénieur)", "Doctorat"};

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poste_id", nullable = false)
    private Poste poste;

    @Column(nullable = false) private int ordre;
    @Column(nullable = false, length = 20) private String type = COMPETENCE;
    /** Énoncé du critère (obligatoire pour les critères appréciés par l'IA). */
    @Column(length = 400) private String libelle;
    @Column(name = "valeur_min") private Integer valeurMin;
    @Column(name = "valeur_max") private Integer valeurMax;
    /** Pondération de 1 à 5 dans le score. */
    @Column(nullable = false) private int ponderation = 1;
    /** Non satisfait : le candidat est écarté, quel que soit son score. */
    @Column(nullable = false) private boolean eliminatoire;

    public boolean isCalcule() { return AGE.equals(type) || EXPERIENCE.equals(type) || DIPLOME.equals(type); }

    /** Expérience exigée dans un domaine précis (précision renseignée) : seules les années dans ce domaine comptent, estimées par l'IA. */
    public boolean isExperienceDomaine() { return EXPERIENCE.equals(type) && libelle != null && !libelle.isBlank(); }
    /** Le critère passe par l'IA : critères qualitatifs et expérience dans un domaine. */
    public boolean isApprecieIa() { return !isCalcule() || isExperienceDomaine(); }
    public String getModeLabel() {
        return isExperienceDomaine() ? "années dans le domaine estimées par l'IA"
                : DOMAINE.equals(type) ? "diplôme, formations et expérience appréciés par l'IA"
                : isCalcule() ? "calculé" : "apprécié par l'IA";
    }

    public static String typeLabel(String t) {
        return switch (t == null ? "" : t) {
            case AGE -> "Âge"; case EXPERIENCE -> "Expérience (années)"; case DIPLOME -> "Niveau d'études";
            case DOMAINE -> "Domaine d'études"; case COMPETENCE -> "Compétence"; case LANGUE -> "Langue";
            case CERTIFICATION -> "Certification"; case LOCALISATION -> "Localisation"; default -> "Autre exigence";
        };
    }
    public String getTypeLabel() { return typeLabel(type); }

    public static String niveauLabel(Integer n) { return n == null || n < 0 || n >= NIVEAUX.length ? NIVEAUX[0] : NIVEAUX[n]; }

    /** Libellé lisible du critère, y compris pour les critères calculés. */
    public String getResume() {
        String base = switch (type == null ? "" : type) {
            case AGE -> "Âge" + borne(" ans");
            case EXPERIENCE -> isExperienceDomaine() ? "Expérience" + borne(" an(s)") + " dans : " + libelle.trim() + " (ou domaine similaire)" : "Expérience" + borne(" an(s)");
            case DIPLOME -> "Niveau minimum : " + niveauLabel(valeurMin);
            default -> libelle == null ? getTypeLabel() : libelle;
        };
        return isCalcule() && !isExperienceDomaine() && libelle != null && !libelle.isBlank() ? base + " (" + libelle + ")" : base;
    }
    private String borne(String unite) {
        if (valeurMin != null && valeurMax != null) return " de " + valeurMin + " à " + valeurMax + unite;
        if (valeurMin != null) return " d'au moins " + valeurMin + unite;
        if (valeurMax != null) return " de " + valeurMax + unite + " au plus";
        return "";
    }
}
