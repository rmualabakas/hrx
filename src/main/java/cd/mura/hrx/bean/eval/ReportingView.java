package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.bean.config.PrivilegeBean;
import cd.mura.hrx.dao.config.DirectionDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.dao.eval.CampagneDAO;
import cd.mura.hrx.dao.eval.EvaluationDAO;
import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.BesoinFormation;
import cd.mura.hrx.entity.eval.Campagne;
import cd.mura.hrx.entity.eval.Evaluation;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.ReportingExportService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

/** Reporting */
@Named
@ViewScoped
@Getter
@Setter
public class ReportingView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private PrivilegeBean privilegeBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private CampagneDAO campagneDAO;
    @EJB private EvaluationDAO evaluationDAO;
    @EJB private UserDAO userDAO;
    @EJB private DirectionDAO directionDAO;
    @Inject private ReportingExportService exportService;
    @EJB private cd.mura.hrx.dao.config.AppDAO appDAO;

    // structures

    /** Agrégat d'une direction (ou du total). */
    @Getter
    public static class Agregat implements Serializable {
        private final String nom, abrege;
        long effectif, initiees, soumises, evaluees, validees, aTemps, horsDelai, retard;
        long atteints, moyennement, faiblement, nonAtteints;
        /** Évalués dont le taux de réalisation des objectifs atteint le seuil paramétré. */
        long atteintSeuil;
        double sommeNotes; long nbNotes;
        Agregat(String nom, String abrege) { this.nom = nom; this.abrege = abrege; }
        /** Part des évaluables ayant atteint le seuil (base : effectif évaluable). */
        public int getPctAtteinteSeuil() { return effectif == 0 ? 0 : (int) Math.min(100, Math.round(100.0 * atteintSeuil / effectif)); }
        public long getAtteintSeuil() { return atteintSeuil; }
        public int getTaux() { return effectif == 0 ? 0 : (int) Math.min(100, Math.round(100.0 * validees / effectif)); }
        public int getTauxIntegration() { return effectif == 0 ? 0 : (int) Math.min(100, Math.round(100.0 * initiees / effectif)); }
        public Double getNoteMoyenne() { return nbNotes == 0 ? null : Math.round(sommeNotes / nbNotes * 10) / 10.0; }
        public int getPctATemps() { long b = aTemps + horsDelai; return b == 0 ? 0 : (int) Math.round(100.0 * aTemps / b); }
        public long getNotees() { return atteints + moyennement + faiblement + nonAtteints; }
        public long getNbDatees() { return aTemps + horsDelai; }
        public int pct(long v) { long n = getNotees(); return n == 0 ? 0 : (int) Math.round(100.0 * v / n); }
        public long getEffectif() { return effectif; } public long getInitiees() { return initiees; } public long getSoumises() { return soumises; }
        public long getEvaluees() { return evaluees; } public long getValidees() { return validees; } public long getATemps() { return aTemps; }
        public long getHorsDelai() { return horsDelai; } public long getRetard() { return retard; }
        public long getAtteints() { return atteints; } public long getMoyennement() { return moyennement; } public long getFaiblement() { return faiblement; } public long getNonAtteints() { return nonAtteints; }
    }

    /** Besoin de formation consolidé (même intitulé, toutes fiches confondues). */
    @Getter
    public static class Formation implements Serializable {
        private final String intitule;
        long demandes, haute, moyenne, basse, parEvaluateur, parEvalue, parDirecteur;
        final Set<String> directions = new TreeSet<>();
        final List<String> collaborateurs = new ArrayList<>();
        Formation(String intitule) { this.intitule = intitule; }
        public long getDemandes() { return demandes; } public long getHaute() { return haute; } public long getMoyenne() { return moyenne; } public long getBasse() { return basse; }
        public long getParEvaluateur() { return parEvaluateur; } public long getParEvalue() { return parEvalue; } public long getParDirecteur() { return parDirecteur; }
        public String getDirectionsTexte() { return String.join(", ", directions); }
        private static void ajout(List<String> l, long n, String un, String plusieurs) { if (n > 0) l.add(n + " " + (n > 1 ? plusieurs : un)); }
        /** Répartition des priorités en clair, seulement si plusieurs priorités coexistent (sinon le badge suffit). */
        public String getDetailPriorites() {
            List<String> l = new ArrayList<>();
            ajout(l, haute, "haute", "hautes"); ajout(l, moyenne, "moyenne", "moyennes"); ajout(l, basse, "basse", "basses");
            return l.size() > 1 ? String.join(" · ", l) : "";
        }
        /** Qui a exprimé le besoin, en clair, sans les zéros. */
        public String getDetailOrigines() {
            List<String> l = new ArrayList<>();
            if (parEvalue > 0) l.add("Collaborateur" + (demandes > 1 ? " (" + parEvalue + ")" : ""));
            if (parEvaluateur > 0) l.add("N+1" + (demandes > 1 ? " (" + parEvaluateur + ")" : ""));
            if (parDirecteur > 0) l.add("Directeur" + (demandes > 1 ? " (" + parDirecteur + ")" : ""));
            return String.join(" · ", l);
        }
        public String getCollaborateursTexte() { return String.join(", ", collaborateurs); }
        public String getPrioriteDominante() { return haute >= moyenne && haute >= basse && haute > 0 ? "HAUTE" : moyenne >= basse ? "MOYENNE" : "BASSE"; }
    }

    /** Ligne simple libellé / effectif / pourcentage. */
    public record Repartition(String code, String libelle, long nb, int pct, String classe) implements Serializable { }

    // état

    private List<Campagne> campagnes = new ArrayList<>();
    private Integer campagneId;
    private Campagne campagne;
    private boolean perimetreRestreint;
    private String perimetreLibelle;
    private List<Agregat> directions = new ArrayList<>();
    private Agregat total;
    private List<Repartition> appreciations = new ArrayList<>();
    private List<Repartition> niveaux = new ArrayList<>();
    private List<Repartition> souhaits = new ArrayList<>();
    private List<Formation> formations = new ArrayList<>();
    private List<Evaluation> fiches = new ArrayList<>();
    private long nbBesoins;
    private String tri = "taux";
    /** Seuil d'atteinte des objectifs (Paramètres > Évaluations, 85 % par défaut). */
    private int seuil = 85;
    /** Recrues encore en période d'essai au début de la campagne : non éligibles, hors effectif. */
    private long nbEnEssai;

    @PostConstruct
    public void init() {
        campagnes = campagneDAO.findAll().stream().filter(c -> !Campagne.BROUILLON.equals(c.getStatut())).toList();
        String p = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap().get("campagne");
        if (p != null) try { campagneId = Integer.valueOf(p); } catch (NumberFormatException ignored) { }
        if (campagneId == null) {
            // Attention : un ternaire int/null serait déballé en int et lèverait un NPE quand il n'y a aucune campagne.
            Campagne o = campagneDAO.findOuverte();
            if (o != null) campagneId = o.getId();
            else if (!campagnes.isEmpty()) campagneId = campagnes.get(0).getId();
        }
        calculer();
    }

    private boolean isRoot() { return loginBean.getUsers().getProfil() != null && loginBean.getUsers().getProfil().getId() == 1; }
    public boolean isPeutExporter() { return isRoot() || privilegeBean.hasPrivilege("RPT_EXPORT"); }

    public void changerCampagne() { calculer(); }

    public void calculer() {
        directions = new ArrayList<>(); appreciations = new ArrayList<>(); niveaux = new ArrayList<>(); souhaits = new ArrayList<>();
        formations = new ArrayList<>(); fiches = new ArrayList<>(); nbBesoins = 0;
        total = new Agregat("Total", "");
        nbEnEssai = 0;
        campagne = campagneId == null ? null : campagneDAO.find(campagneId);
        if (campagne == null) return;
        cd.mura.hrx.entity.config.App param = appDAO.findFirst();
        seuil = param == null ? 85 : param.getSeuilAtteinteEffectif();
        int moisEssai = param == null ? 6 : param.getDureeEssai();
        java.time.LocalDate refEssai = cd.mura.hrx.service.PeriodeEssaiService.reference(campagne);

        // Périmètre : toute la banque pour la DCHT, sinon les directions dirigées
        Set<Integer> dirsAutorisees = null;
        perimetreRestreint = !(isRoot() || privilegeBean.hasPrivilege("RPT_VIEW") || privilegeBean.hasPrivilege("EVAL_DCHT"));
        if (perimetreRestreint) {
            List<Direction> dd = directionDAO.findDirigeesPar(loginBean.getUsers().getId());
            dirsAutorisees = dd.stream().map(Direction::getId).collect(Collectors.toSet());
            perimetreLibelle = dd.isEmpty() ? "Aucune direction" : dd.stream().map(Direction::getNom).collect(Collectors.joining(", "));
        } else perimetreLibelle = "Toutes les directions";

        Map<Integer, Users> parId = new HashMap<>();
        for (Users u : userDAO.findActifsAvecDirection()) parId.put(u.getId(), u);
        Map<String, Agregat> agg = new LinkedHashMap<>();
        final Set<Integer> autorisees = dirsAutorisees;

        for (Users u : parId.values()) {
            if (u.getEvaluable() != 1 || !dansPerimetre(u, autorisees)) continue;
            if (cd.mura.hrx.service.PeriodeEssaiService.enEssai(u, refEssai, moisEssai)) { nbEnEssai++; continue; }
            agregat(agg, u).effectif++; total.effectif++;
        }
        Map<String, Formation> form = new LinkedHashMap<>();
        Map<String, Long> app = new LinkedHashMap<>(), sh = new LinkedHashMap<>();
        for (Evaluation e : evaluationDAO.findByCampagneAvecFormations(campagne.getId())) {
            Users u = parId.getOrDefault(e.getSalarie().getId(), e.getSalarie());
            if (!dansPerimetre(u, autorisees)) continue;
            fiches.add(e);
            Agregat a = agregat(agg, u);
            for (Agregat x : List.of(a, total)) compter(x, e);
            boolean notee = e.getNoteGlobale() != null && (Evaluation.EVALUEE.equals(e.getStatut()) || Evaluation.VALIDEE.equals(e.getStatut()));
            if (notee) app.merge(e.getAppreciation() == null ? Evaluation.appreciationPour(e.getNoteGlobale()) : e.getAppreciation(), 1L, Long::sum);
            for (String so : e.getSouhaits()) sh.merge(so, 1L, Long::sum);
            for (BesoinFormation b : e.getBesoinsFormation()) {
                if (b.getIntitule() == null || b.getIntitule().isBlank()) continue;
                nbBesoins++;
                String cle = cle(b.getIntitule());
                Formation f = form.computeIfAbsent(cle, k -> new Formation(b.getIntitule().trim()));
                f.demandes++;
                switch (s(b.getPriorite())) { case "HAUTE" -> f.haute++; case "BASSE" -> f.basse++; default -> f.moyenne++; }
                switch (s(b.getOrigine())) { case "EVALUE" -> f.parEvalue++; case "DIRECTEUR" -> f.parDirecteur++; default -> f.parEvaluateur++; }
                f.directions.add(a.getAbrege() == null || a.getAbrege().isBlank() ? a.getNom() : a.getAbrege());
                if (!f.collaborateurs.contains(e.getSalarie().getNomComplet())) f.collaborateurs.add(e.getSalarie().getNomComplet());
            }
        }
        directions = new ArrayList<>(agg.values());
        trier();

        long notees = total.getNotees();
        String[][] nv = {{"ATTEINTS", "Atteints", "hx-att--4"}, {"MOYENNEMENT", "Moyennement atteints", "hx-att--3"}, {"FAIBLEMENT", "Faiblement atteints", "hx-att--2"}, {"NON_ATTEINTS", "Non atteints", "hx-att--1"}};
        long[] vals = {total.atteints, total.moyennement, total.faiblement, total.nonAtteints};
        for (int i = 0; i < nv.length; i++) niveaux.add(new Repartition(nv[i][0], nv[i][1], vals[i], notees == 0 ? 0 : (int) Math.round(100.0 * vals[i] / notees), nv[i][2]));

        long nApp = app.values().stream().mapToLong(Long::longValue).sum();
        String[][] ap = {{"Excellent", "20", "hx-att--4"}, {"Bon", "17 – 19", "hx-att--4"}, {"Moyennement bon", "15 – 16", "hx-att--3"}, {"Insuffisant", "10 – 14", "hx-att--2"}, {"Mauvais", "< 10", "hx-att--1"}};
        for (String[] a : ap) { long v = app.getOrDefault(a[0], 0L); appreciations.add(new Repartition(a[1], a[0], v, nApp == 0 ? 0 : (int) Math.round(100.0 * v / nApp), a[2])); }

        long nSh = sh.values().stream().mapToLong(Long::longValue).sum();
        for (String c : List.of("BIEN_POSTE", "ACCOMPAGNEMENT", "CHANGER_POSTE", "AUTRES")) {
            long v = sh.getOrDefault(c, 0L);
            souhaits.add(new Repartition(c, Evaluation.souhaitLabel(c), v, nSh == 0 ? 0 : (int) Math.round(100.0 * v / nSh), "hx-att--3"));
        }
        formations = form.values().stream()
                .sorted(Comparator.comparingLong(Formation::getDemandes).reversed().thenComparing(f -> -f.haute).thenComparing(Formation::getIntitule))
                .toList();
    }

    private void compter(Agregat a, Evaluation e) {
        a.initiees++;
        switch (e.getStatut()) {
            case Evaluation.SOUMISE -> a.soumises++;
            case Evaluation.EVALUEE -> a.evaluees++;
            case Evaluation.VALIDEE -> a.validees++;
            default -> { }
        }
        if (e.getDateSoumission() != null) { if (e.isSoumiseATemps()) a.aTemps++; else a.horsDelai++; }
        if (e.isEnRetard()) a.retard++;
        boolean notee = Evaluation.EVALUEE.equals(e.getStatut()) || Evaluation.VALIDEE.equals(e.getStatut());
        if (notee && e.getNoteGlobale() != null) { a.sommeNotes += e.getNoteGlobale(); a.nbNotes++; }
        if (notee && e.getScoreObjectifs() != null && e.getScoreObjectifs() >= seuil) a.atteintSeuil++;
        if (notee && e.getNiveauAtteinte() != null) switch (e.getNiveauAtteinte()) {
            case "ATTEINTS" -> a.atteints++;
            case "MOYENNEMENT" -> a.moyennement++;
            case "FAIBLEMENT" -> a.faiblement++;
            case "NON_ATTEINTS" -> a.nonAtteints++;
            default -> { }
        }
    }

    public void trier() {
        Comparator<Agregat> c = switch (s(tri)) {
            case "note" -> Comparator.comparing((Agregat a) -> a.getNoteMoyenne() == null ? -1 : a.getNoteMoyenne()).reversed();
            case "effectif" -> Comparator.comparingLong(Agregat::getEffectif).reversed();
            case "nom" -> Comparator.comparing(Agregat::getNom);
            default -> Comparator.comparingInt(Agregat::getTaux).reversed();
        };
        directions.sort(c.thenComparing(Agregat::getNom));
    }

    private static boolean dansPerimetre(Users u, Set<Integer> autorisees) {
        if (autorisees == null) return true;
        try { return u.getDirection() != null && autorisees.contains(u.getDirection().getId()); } catch (Exception ex) { return false; }
    }
    private static Agregat agregat(Map<String, Agregat> agg, Users u) {
        String nom, ab;
        try { nom = u.getDirection() == null ? "Sans direction" : s(u.getDirection().getNom()); ab = u.getDirection() == null ? "" : s(u.getDirection().getAbrege()); }
        catch (Exception ex) { nom = "Sans direction"; ab = ""; }
        final String a = ab;
        return agg.computeIfAbsent(nom, k -> new Agregat(k, a));
    }
    private static String cle(String v) {
        return Normalizer.normalize(v.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("\\s+", " ");
    }

    // lecture

    public boolean isDonnees() { return campagne != null && (total.effectif > 0 || !fiches.isEmpty()); }
    public long getNbNotees() { return total.getNotees() == 0 ? total.nbNotes : total.getNotees(); }
    public int getMaxFormation() { return formations.isEmpty() ? 1 : (int) formations.get(0).getDemandes(); }
    public int pctFormation(Formation f) { return (int) Math.round(100.0 * f.getDemandes() / Math.max(1, getMaxFormation())); }
    public String prioriteLabel(String p) { return switch (s(p)) { case "HAUTE" -> "Haute"; case "BASSE" -> "Basse"; default -> "Moyenne"; }; }
    public String prioriteClasse(String p) { return switch (s(p)) { case "HAUTE" -> "hx-badge--late"; case "BASSE" -> "hx-badge--draft"; default -> "hx-badge--pending"; }; }
    public long getAttendues() { if (campagne == null) return 0; long saisi = campagne.getEffectifAttendu() > 0 && !perimetreRestreint ? campagne.getEffectifAttendu() : total.effectif; return Math.max(saisi, total.initiees); }
    /** Part des évaluables (base du taux de réalisation) dont le taux d'atteinte des objectifs est ≥ au seuil. */
    public int getPctAtteinteSeuil() { long b = getAttendues(); return b == 0 ? 0 : (int) Math.min(100, Math.round(100.0 * total.atteintSeuil / b)); }
    public int getTauxGlobal() { long b = getAttendues(); return b == 0 ? 0 : (int) Math.min(100, Math.round(100.0 * total.validees / b)); }

    // exports

    private String nomFichier(String ext) {
        String n = campagne == null ? "reporting" : "reporting-" + Normalizer.normalize(campagne.getLibelle(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(Locale.ROOT);
        return n + "." + ext;
    }
    private ReportingExportService.Donnees donnees() {
        return new ReportingExportService.Donnees(campagne, perimetreLibelle, getAttendues(), getTauxGlobal(), total, directions, niveaux, appreciations, souhaits, formations, fiches, parIdDirection());
    }
    private Map<Integer, String> parIdDirection() {
        Map<Integer, String> m = new HashMap<>();
        for (Users u : userDAO.findActifsAvecDirection()) {
            try { m.put(u.getId(), u.getDirection() == null ? "Sans direction" : u.getDirection().getNom()); } catch (Exception ex) { m.put(u.getId(), "Sans direction"); }
        }
        return m;
    }
    public StreamedContent getExportExcel() {
        authorization.requirePrivilege("RPT_EXPORT");
        if (!isPeutExporter() || campagne == null) return null;
        byte[] data = exportService.excel(donnees());
        auditService.log("EXPORT", "Reporting", "Campagne", campagne.getId(), "Export Excel du reporting", campagne.getLibelle() + " : " + perimetreLibelle, true);
        return DefaultStreamedContent.builder().name(nomFichier("xlsx")).contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").stream(() -> new ByteArrayInputStream(data)).build();
    }
    public StreamedContent getExportPdf() {
        authorization.requirePrivilege("RPT_EXPORT");
        if (!isPeutExporter() || campagne == null) return null;
        byte[] data = exportService.pdf(donnees());
        auditService.log("EXPORT", "Reporting", "Campagne", campagne.getId(), "Export PDF du reporting", campagne.getLibelle() + " : " + perimetreLibelle, true);
        return DefaultStreamedContent.builder().name(nomFichier("pdf")).contentType("application/pdf").stream(() -> new ByteArrayInputStream(data)).build();
    }

    private static String s(String v) { return v == null ? "" : v; }
}
