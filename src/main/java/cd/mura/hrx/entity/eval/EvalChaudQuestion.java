package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Question du questionnaire d'une évaluation de formation (à chaud ou à froid). */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "eval_chaud_question")
public class EvalChaudQuestion implements Serializable {

    /**
     * NOTE : échelle de 1 à 5 (bornes libellées) ; OUI_NON (précision facultative si oui) ; TEXTE : réponse libre ;
     * LISTE : plusieurs points (ex. compétences acquises) ; PLAN : actions à mettre en place par échéance (fiche MAF 063, Q.11) ;
     * MULTI : cases à cocher parmi des choix, « autre » facultatif ; OBJECTIFS : objectifs de la formation à cocher (atteints) ;
     * NIVEAUX : niveau d'acquisition de chaque point cité à la question « liste » précédente (fiche MAF 064, Q.3).
     */
    public static final String NOTE = "NOTE", OUI_NON = "OUI_NON", TEXTE = "TEXTE", LISTE = "LISTE", PLAN = "PLAN",
            MULTI = "MULTI", OBJECTIFS = "OBJECTIFS", NIVEAUX = "NIVEAUX";
    /** Préfixe d'un choix « autre » dans une réponse à choix multiples. */
    public static final String AUTRE = "AUTRE";
    /** Niveaux d'acquisition proposés par défaut (fiche MAF 064, Q.3). */
    public static final String NIVEAUX_ACQUISITION = "Pas du tout acquis\nEn cours d'acquisition\nPartiellement acquis\nPresque acquis\nEntièrement acquis";
    /** Échéances du plan d'actions (codes stockés, libellés du modèle MAF 063). */
    public static final String[][] HORIZONS = {{"H0", "Dans l'immédiat"}, {"H1", "Après 1 mois"}, {"H3", "Après 3 mois"}, {"H6", "Après 6 mois"}};

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "eval_chaud_id", nullable = false)
    private EvalChaud evalChaud;

    @Column(nullable = false, length = 300) private String libelle;
    @Column(nullable = false, length = 10) private String type = NOTE;
    @Column(nullable = false) private boolean obligatoire = true;
    @Column(nullable = false) private int ordre;

    /** Libellés des bornes de l'échelle (1 et 5), ex. « Très insatisfait » / « Très satisfait ». */
    @Column(name = "libelle_min", length = 120) private String libelleMin;
    @Column(name = "libelle_max", length = 120) private String libelleMax;
    /** Échelle centrée : 3 est l'idéal (ex. rythme « très lent » à « très rapide ») ; exclue de la satisfaction moyenne. */
    @Column(name = "echelle_centree") private Boolean echelleCentree;
    /** Oui / non : libellé de la précision demandée si la réponse est oui (vide = aucune précision). */
    @Column(name = "precision_oui", length = 150) private String precisionOui;

    /** Choix (MULTI), libellés des 5 niveaux (NOTE, NIVEAUX) : un par ligne. */
    @Column(columnDefinition = "TEXT") private String options;
    /** Choix multiples : ajoute « Autre (veuillez préciser) ». */
    @Column(name = "avec_autre") private Boolean avecAutre;
    /** Affichée seulement si la question oui / non précédente a reçu « non » (ex. « Si vous ne l'utilisez pas… »). */
    @Column(name = "si_non") private Boolean siNon;
    /** Liste : nombre maximal de points (vide = sans limite). */
    @Column(name = "max_lignes") private Integer maxLignes;

    public boolean isMulti() { return MULTI.equals(type); }
    public boolean isObjectifs() { return OBJECTIFS.equals(type); }
    public boolean isNiveaux() { return NIVEAUX.equals(type); }
    public boolean isAutre() { return isMulti() && Boolean.TRUE.equals(avecAutre); }
    public void setAutre(boolean v) { avecAutre = v; }
    public boolean isConditionnelle() { return Boolean.TRUE.equals(siNon); }
    public void setConditionnelle(boolean v) { siNon = v; }
    public List<String> getOptionsListe() {
        List<String> l = new ArrayList<>();
        String o = options == null || options.isBlank() ? (isNiveaux() ? NIVEAUX_ACQUISITION : "") : options;
        for (String x : o.split("\\R")) if (!x.isBlank()) l.add(x.trim());
        return l;
    }
    /** Note libellée niveau par niveau (5 libellés renseignés). */
    public boolean isLibellee() { return isNote() && getOptionsListe().size() == 5; }
    /** Libellé d'un niveau (1 à 5) : celui des options, sinon les bornes. */
    public String niveau(int k) {
        List<String> l = getOptionsListe();
        if ((isNote() || isNiveaux()) && l.size() == 5 && k >= 1 && k <= 5) return l.get(k - 1);
        return k == 1 ? getBorneMin() : k == 5 ? getBorneMax() : String.valueOf(k);
    }
    public int getMaxPoints() { return maxLignes == null || maxLignes <= 0 ? 15 : maxLignes; }

    public boolean isNote() { return NOTE.equals(type); }
    public boolean isOuiNon() { return OUI_NON.equals(type); }
    public boolean isTexte() { return TEXTE.equals(type); }
    public boolean isListe() { return LISTE.equals(type); }
    public boolean isPlan() { return PLAN.equals(type); }
    /** Réponse rédigée (texte, liste de points, plan d'actions). */
    public boolean isRedigee() { return isTexte() || isListe() || isPlan() || isMulti() || isObjectifs() || isNiveaux(); }
    public boolean isCentree() { return isNote() && Boolean.TRUE.equals(echelleCentree); }
    public void setCentree(boolean v) { echelleCentree = v; }
    /** Note comptée dans la satisfaction moyenne. */
    public boolean isSatisfaction() { return isNote() && !isCentree(); }
    public boolean isAvecPrecision() { return isOuiNon() && precisionOui != null && !precisionOui.isBlank(); }
    public String getBorneMin() { return libelleMin == null || libelleMin.isBlank() ? (isCentree() ? "Trop faible" : "Pas du tout satisfait") : libelleMin; }
    public String getBorneMax() { return libelleMax == null || libelleMax.isBlank() ? (isCentree() ? "Trop élevé" : "Très satisfait") : libelleMax; }

    public static String typeLabel(String t) {
        return switch (t == null ? "" : t) {
            case OUI_NON -> "Oui / non"; case TEXTE -> "Réponse libre"; case LISTE -> "Liste de points"; case PLAN -> "Plan d'actions";
            case MULTI -> "Choix multiples"; case OBJECTIFS -> "Objectifs atteints"; case NIVEAUX -> "Niveau d'acquisition"; default -> "Note de 1 à 5";
        };
    }
    public static String horizonLabel(String code) {
        for (String[] h : HORIZONS) if (h[0].equals(code)) return h[1];
        return code == null ? "" : code;
    }
    public static int horizonIndex(String code) {
        for (int i = 0; i < HORIZONS.length; i++) if (HORIZONS[i][0].equals(code)) return i;
        return -1;
    }
}
