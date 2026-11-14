package cd.mura.hrx.service.recrutement;

import cd.mura.hrx.entity.recrutement.Cv;
import cd.mura.hrx.entity.recrutement.PosteCritere;
import cd.mura.hrx.service.recrutement.RecrutementService.Detail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calcul du score d'un CV pour un poste. L'âge, l'expérience et le niveau d'études sont vérifiés par calcul
 * (l'expérience exigée dans un domaine compte seulement les années dans ce domaine, estimées par l'IA) ;
 * une information absente du CV ne satisfait pas le critère. Score = somme des pondérations obtenues
 * (OUI = 1, PARTIEL = 0,5, NON = 0) rapportée au total des pondérations.
 */
public final class Notation {

    public static final String OUI = "OUI", PARTIEL = "PARTIEL", NON = "NON";

    public record Resultat(int score, boolean elimine, List<Detail> details) { }

    private Notation() { }

    public static Detail calculer(PosteCritere c, Cv cv) {
        return switch (c.getType()) {
            case PosteCritere.AGE -> cv.getAge() == null
                    ? new Detail(c.getId(), NON, "Ni âge ni date de naissance sur le CV.")
                    : borne(c, cv.getAge(), cv.getAge() + " ans" + (cv.getDateNaissance() != null ? " (né(e) le " + cv.getDateNaissance().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ")" : ""));
            case PosteCritere.EXPERIENCE -> cv.getExperienceAnnees() == null
                    ? new Detail(c.getId(), NON, "Expérience professionnelle impossible à déterminer sur le CV.")
                    : borne(c, cv.getExperienceAnnees(), format(cv.getExperienceAnnees()) + " an(s) d'expérience professionnelle");
            case PosteCritere.DIPLOME -> {
                if (cv.getNiveauEtudes() == null) yield new Detail(c.getId(), NON, "Niveau d'études non indiqué sur le CV.");
                String d = PosteCritere.niveauLabel(cv.getNiveauEtudes()) + (cv.getDiplome() == null ? "" : " : " + cv.getDiplome());
                int min = c.getValeurMin() == null ? 0 : c.getValeurMin();
                yield cv.getNiveauEtudes() >= min ? new Detail(c.getId(), OUI, d)
                        : cv.getNiveauEtudes() == min - 1 ? new Detail(c.getId(), PARTIEL, d + " (un niveau en dessous)")
                        : new Detail(c.getId(), NON, d);
            }
            default -> null;
        };
    }

    private static Detail borne(PosteCritere c, double v, String constat) {
        boolean okMin = c.getValeurMin() == null || v >= c.getValeurMin();
        boolean okMax = c.getValeurMax() == null || v <= c.getValeurMax();
        return new Detail(c.getId(), okMin && okMax ? OUI : NON, constat + (okMin ? "" : ", en dessous du minimum") + (okMax ? "" : ", au-dessus du maximum") + ".");
    }

    private static String format(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d).replace('.', ','); }

    /**
     * Expérience exigée dans un domaine : on compare les années dans ce domaine (estimées par l'IA poste par poste)
     * aux bornes, et non l'expérience totale. Au moins la moitié du minimum : PARTIEL ; en dessous : NON.
     */
    static Detail experienceDomaine(PosteCritere c, Cv cv, Double annees, Detail avis) {
        String total = cv.getExperienceAnnees() == null ? "" : " sur " + format(cv.getExperienceAnnees()) + " an(s) au total";
        String pourquoi = avis == null || avis.justification() == null || avis.justification().isBlank() ? "" : " " + avis.justification().trim();
        if (annees == null) return new Detail(c.getId(), NON, "Expérience dans le domaine impossible à établir sur le CV" + total + "." + pourquoi);
        String constat = format(annees) + " an(s) d'expérience dans le domaine" + total;
        boolean okMin = c.getValeurMin() == null || annees >= c.getValeurMin();
        boolean okMax = c.getValeurMax() == null || annees <= c.getValeurMax();
        if (okMin && okMax) return new Detail(c.getId(), OUI, constat + "." + pourquoi);
        if (!okMin && c.getValeurMin() != null && annees >= c.getValeurMin() / 2.0 && annees > 0)
            return new Detail(c.getId(), PARTIEL, constat + ", en dessous du minimum de " + c.getValeurMin() + " an(s)." + pourquoi);
        return new Detail(c.getId(), NON, constat + (okMin ? ", au-dessus du maximum" : ", en dessous du minimum de " + c.getValeurMin() + " an(s)") + "." + pourquoi);
    }

    /** Assemble les avis (calculés et IA) et calcule le score. */
    public static Resultat noter(List<PosteCritere> criteres, Cv cv, Map<Integer, Detail> avisIa) { return noter(criteres, cv, avisIa, Map.of()); }

    public static Resultat noter(List<PosteCritere> criteres, Cv cv, Map<Integer, Detail> avisIa, Map<Integer, Double> anneesDomaine) {
        List<Detail> details = new ArrayList<>();
        double obtenu = 0, total = 0;
        boolean elimine = false;
        for (PosteCritere c : criteres) {
            Detail d = c.isExperienceDomaine() ? experienceDomaine(c, cv, anneesDomaine.get(c.getId()), avisIa.get(c.getId()))
                    : c.isCalcule() ? calculer(c, cv) : avisIa.get(c.getId());
            if (d == null) d = new Detail(c.getId(), NON, "Critère non apprécié.");
            String r = OUI.equals(d.resultat()) || PARTIEL.equals(d.resultat()) ? d.resultat() : NON;
            if (!r.equals(d.resultat())) d = new Detail(d.critereId(), r, d.justification());
            details.add(d);
            total += c.getPonderation();
            obtenu += c.getPonderation() * (OUI.equals(r) ? 1 : PARTIEL.equals(r) ? .5 : 0);
            if (c.isEliminatoire() && NON.equals(r)) elimine = true;
        }
        return new Resultat(total == 0 ? 0 : (int) Math.round(100 * obtenu / total), elimine, details);
    }
}
