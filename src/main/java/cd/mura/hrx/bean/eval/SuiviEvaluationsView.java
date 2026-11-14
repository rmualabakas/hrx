package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.bean.config.PrivilegeBean;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.dao.eval.CampagneDAO;
import cd.mura.hrx.dao.eval.EvaluationDAO;
import cd.mura.hrx.dao.eval.ObjectifDAO;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.Campagne;
import cd.mura.hrx.entity.eval.Evaluation;
import cd.mura.hrx.entity.eval.FicheObjectifs;
import cd.mura.hrx.entity.eval.ObjectifEvaluation;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.EvaluationService;
import cd.mura.hrx.service.NotificationService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;
import org.primefaces.PrimeFaces;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;

/**
 * Suivi des évaluations (DCHT) : population évaluable d'une campagne ligne par ligne, avancement, anomalies,
 * contrôles de complétude, relances par e-mail et validation finale.
 */
@Named
@ViewScoped
@Getter
@Setter
public class SuiviEvaluationsView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private PrivilegeBean privilegeBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private UserDAO userDAO;
    @EJB private CampagneDAO campagneDAO;
    @EJB private EvaluationDAO evaluationDAO;
    @EJB private ObjectifDAO objectifDAO;
    @EJB private EvaluationService service;
    @EJB private NotificationService notifications;

    /** Une ligne = un collaborateur évaluable (avec ou sans fiche) ou une fiche d'un compte devenu non évaluable. */
    @Getter
    public static class Ligne implements Serializable {
        private final Users salarie;
        private final Evaluation fiche;
        private final FicheObjectifs objectifs;
        private final List<String> anomalies = new ArrayList<>();
        private int completudeSalarie = -1, completudeN1 = -1;   // -1 = sans objet
        private String etape;

        Ligne(Users salarie, Evaluation fiche, FicheObjectifs objectifs) { this.salarie = salarie; this.fiche = fiche; this.objectifs = objectifs; }
        public int getId() { return salarie.getId(); }
        public boolean isIntegree() { return fiche != null; }
        public String getStatut() { return fiche == null ? "NON_INITIEE" : fiche.getStatut(); }
        public String getStatutLabel() { return fiche == null ? "Non intégrée" : fiche.getStatutLabel(); }
        public String getStatutClasse() {
            if (fiche == null) return "hx-badge--draft";
            return switch (fiche.getStatut()) {
                case Evaluation.VALIDEE -> "hx-badge--done";
                case Evaluation.SOUMISE -> "hx-badge--pending";
                case Evaluation.EVALUEE -> "hx-badge--info";
                case Evaluation.RETOURNEE -> "hx-badge--late";
                default -> "hx-badge--draft";
            };
        }
        public String getDirectionNom() { try { return salarie.getDirection() == null ? "—" : salarie.getDirection().getAbrege() != null && !salarie.getDirection().getAbrege().isBlank() ? salarie.getDirection().getAbrege() : salarie.getDirection().getNom(); } catch (Exception ex) { return "—"; } }
        public String getDirectionComplete() { try { return salarie.getDirection() == null ? "Sans direction" : salarie.getDirection().getNom(); } catch (Exception ex) { return "Sans direction"; } }
        public Users getN1() { return fiche != null && fiche.getEvaluateur() != null ? fiche.getEvaluateur() : salarie.getSuperieurHierarchique(); }
        public String getN1Nom() { Users n = getN1(); return n == null ? "—" : n.getNomComplet(); }
        public long getNbObjectifs() { return objectifs == null ? 0 : objectifs.getNbValides(); }
        public boolean isAnomalie() { return !anomalies.isEmpty(); }
        public boolean isRetard() { return fiche != null ? fiche.isEnRetard() : false; }
        public Double getNote() { return fiche == null ? null : fiche.getNoteGlobale(); }
        public String getAnomaliesTexte() { return String.join(" · ", anomalies); }
        /** Destinataire naturel d'une relance selon l'étape. */
        public Users getCibleRelance() {
            if (fiche == null) return salarie.getSuperieurHierarchique();
            return switch (fiche.getStatut()) {
                case Evaluation.SOUMISE -> fiche.getEvaluateur();
                case Evaluation.EVALUEE -> fiche.getDirecteur() != null ? fiche.getDirecteur() : null;
                case Evaluation.VALIDEE -> null;
                default -> salarie;
            };
        }
        public String getCibleRelanceRole() {
            if (fiche == null) return "N+1 (intégration à la campagne)";
            return switch (fiche.getStatut()) {
                case Evaluation.SOUMISE -> "N+1 (évaluation à réaliser)";
                case Evaluation.EVALUEE -> "Directeur (avis à donner)";
                case Evaluation.VALIDEE -> "—";
                default -> "Collaborateur (auto-évaluation)";
            };
        }
        public boolean isRelancable() { return getCibleRelance() != null && (fiche == null || !fiche.isFinalisee()); }
        void setCompletude(int s, int n1) { completudeSalarie = s; completudeN1 = n1; }
    }

    private Users moi;
    private boolean dcht;
    private List<Campagne> campagnes = new ArrayList<>();
    private Integer campagneId;
    private Campagne campagne;
    private final List<Ligne> lignes = new ArrayList<>();

    // filtres
    private String onglet = "tous";          // tous | anomalies | retard | aValider | nonInitiees
    private String filtreStatut = "";
    private String filtreDirection = "";
    private String recherche = "";

    // relance
    private Ligne cible;
    private String relanceObjet, relanceMessage;
    private List<Ligne> selection = new ArrayList<>();

    @PostConstruct
    public void init() {
        moi = loginBean.getUsers();
        dcht = (moi.getProfil() != null && moi.getProfil().getId() == 1) || privilegeBean.hasPrivilege("EVAL_DCHT");
        campagnes = campagneDAO.findAll();
        String p = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap().get("campagne");
        if (p != null && !p.isBlank()) { try { campagneId = Integer.valueOf(p); } catch (NumberFormatException ignored) { } }
        if (campagneId == null) {
            // Pas de ternaire int/null : il serait déballé en int et lèverait un NPE quand il n'y a aucune campagne.
            Campagne o = campagneDAO.findOuverte();
            if (o != null) campagneId = o.getId();
            else if (!campagnes.isEmpty()) campagneId = campagnes.get(0).getId();
        }
        charger();
    }

    public void charger() {
        lignes.clear();
        campagne = campagneId == null ? null : campagneDAO.find(campagneId);
        if (campagne == null) return;
        List<Users> actifs = userDAO.findActifsAvecDirection();
        Map<Integer, Users> parId = new HashMap<>();
        for (Users u : actifs) parId.put(u.getId(), u);
        Map<Integer, Evaluation> fiches = new HashMap<>();
        for (Evaluation e : evaluationDAO.findByCampagneAvecObjectifs(campagne.getId())) fiches.put(e.getSalarie().getId(), e);
        Map<Integer, FicheObjectifs> obj = new HashMap<>();
        List<Integer> ids = new ArrayList<>(parId.keySet()); ids.addAll(fiches.keySet());
        for (FicheObjectifs f : objectifDAO.findFiches(campagne.getExercice(), ids.stream().distinct().toList())) obj.put(f.getSalarie().getId(), f);

        Set<Integer> vus = new HashSet<>();
        for (Users u : actifs) {
            if (u.getEvaluable() != 1 && !fiches.containsKey(u.getId())) continue;
            vus.add(u.getId());
            lignes.add(analyser(new Ligne(u, fiches.get(u.getId()), obj.get(u.getId()))));
        }
        for (Evaluation e : fiches.values()) if (!vus.contains(e.getSalarie().getId())) lignes.add(analyser(new Ligne(e.getSalarie(), e, obj.get(e.getSalarie().getId()))));
        lignes.sort(Comparator.comparing((Ligne l) -> l.getSalarie().getNom() == null ? "" : l.getSalarie().getNom()).thenComparing(l -> l.getSalarie().getPrenom() == null ? "" : l.getSalarie().getPrenom()));
    }

    /** Contrôles d'anomalies et de complétude d'une ligne. */
    private Ligne analyser(Ligne l) {
        Users u = l.getSalarie();
        Evaluation e = l.getFiche();
        if (u.getSuperieurHierarchique() == null) l.anomalies.add("Aucun N+1 renseigné");
        if (l.getNbObjectifs() == 0) l.anomalies.add("Aucun objectif validé pour " + campagne.getExercice());
        if (u.getEvaluable() != 1 && e != null) l.anomalies.add("Compte non évaluable mais fiche initiée");
        if (e == null) {
            if (campagne.isOuverte() && u.getEvaluable() == 1) l.anomalies.add(campagne.isEnRetard() ? "Non intégrée après l'échéance" : "Non encore intégrée à la campagne");
            l.etape = "—";
            return l;
        }
        if (e.getEvaluateur() != null && u.getSuperieurHierarchique() != null && e.getEvaluateur().getId() != u.getSuperieurHierarchique().getId())
            l.anomalies.add("Évaluateur différent du N+1 actuel");
        if (e.getDateSoumission() != null && !e.isSoumiseATemps()) l.anomalies.add("Auto-évaluation hors délai");
        if (e.getDateEvaluation() != null && !e.isEvalueeATemps()) l.anomalies.add("Évaluation N+1 hors délai");
        if (Evaluation.RETOURNEE.equals(e.getStatut())) l.anomalies.add("Retournée pour correction");
        if (e.isEnRetard()) l.anomalies.add("Non finalisée après l'échéance");
        List<ObjectifEvaluation> lo = e.getObjectifs() == null ? List.of() : e.getObjectifs();
        if (lo.isEmpty()) l.anomalies.add("Fiche sans ligne d'objectif");
        else {
            long okS = lo.stream().filter(ObjectifEvaluation::isAutoEvalComplete).count();
            long okN = lo.stream().filter(ObjectifEvaluation::isEvalN1Complete).count();
            int cs = (int) Math.round(100.0 * okS / lo.size()), cn = (int) Math.round(100.0 * okN / lo.size());
            boolean apresSoumission = !Evaluation.INITIEE.equals(e.getStatut()) && !Evaluation.RETOURNEE.equals(e.getStatut());
            boolean apresEval = Evaluation.EVALUEE.equals(e.getStatut()) || Evaluation.VALIDEE.equals(e.getStatut());
            l.setCompletude(cs, apresSoumission ? cn : -1);
            if (apresSoumission && cs < 100) l.anomalies.add("Auto-évaluation incomplète (" + cs + " %)");
            if (apresEval && cn < 100) l.anomalies.add("Évaluation N+1 incomplète (" + cn + " %)");
            if (apresEval && (e.getNiveauAtteinte() == null || e.getNiveauAtteinte().isBlank())) l.anomalies.add("Niveau d'atteinte non coché");
            if (apresEval && (e.getCommentaireEvaluateur() == null || e.getCommentaireEvaluateur().isBlank())) l.anomalies.add("Appréciation générale absente");
            if (apresEval && e.getNoteGlobale() == null) l.anomalies.add("Note non calculée");
        }
        l.etape = switch (e.getStatut()) {
            case Evaluation.SOUMISE -> "Chez le N+1";
            case Evaluation.EVALUEE -> e.getDateAvisDirecteur() == null ? "Avis directeur / DCHT" : "Validation DCHT";
            case Evaluation.VALIDEE -> "Terminée";
            case Evaluation.RETOURNEE -> "Chez le collaborateur (correction)";
            default -> "Chez le collaborateur";
        };
        return l;
    }

    // lecture filtrée

    public List<Ligne> getLignesFiltrees() {
        String q = recherche == null ? "" : recherche.trim().toLowerCase(Locale.ROOT);
        return lignes.stream().filter(l -> {
            if (!q.isEmpty()) {
                String h = (s(l.getSalarie().getNomComplet()) + " " + s(l.getSalarie().getMatricule()) + " " + s(l.getSalarie().getFonction()) + " " + l.getN1Nom() + " " + l.getDirectionComplete()).toLowerCase(Locale.ROOT);
                if (!h.contains(q)) return false;
            }
            if (!s(filtreStatut).isEmpty() && !filtreStatut.equals(l.getStatut())) return false;
            if (!s(filtreDirection).isEmpty() && !filtreDirection.equals(l.getDirectionComplete())) return false;
            return switch (s(onglet)) {
                case "anomalies" -> l.isAnomalie();
                case "retard" -> l.isRetard() || (l.getFiche() == null && campagne != null && campagne.isEnRetard() && l.getSalarie().getEvaluable() == 1);
                case "aValider" -> Evaluation.EVALUEE.equals(l.getStatut());
                case "nonInitiees" -> l.getFiche() == null;
                default -> true;
            };
        }).toList();
    }

    public List<String> getDirections() { return lignes.stream().map(Ligne::getDirectionComplete).distinct().sorted().toList(); }
    public List<Campagne> getCampagnes() { return campagnes; }

    public long getNbTotal() { return lignes.size(); }
    public long getNbIntegrees() { return lignes.stream().filter(Ligne::isIntegree).count(); }
    public long getNbNonIntegrees() { return lignes.stream().filter(l -> !l.isIntegree() && l.getSalarie().getEvaluable() == 1).count(); }
    public long getNbAutoEval() { return lignes.stream().filter(l -> Evaluation.INITIEE.equals(l.getStatut()) || Evaluation.RETOURNEE.equals(l.getStatut())).count(); }
    public long getNbChezN1() { return lignes.stream().filter(l -> Evaluation.SOUMISE.equals(l.getStatut())).count(); }
    public long getNbAValider() { return lignes.stream().filter(l -> Evaluation.EVALUEE.equals(l.getStatut())).count(); }
    public long getNbValidees() { return lignes.stream().filter(l -> Evaluation.VALIDEE.equals(l.getStatut())).count(); }
    public long getNbAnomalies() { return lignes.stream().filter(Ligne::isAnomalie).count(); }
    public long getNbRetard() { return lignes.stream().filter(l -> l.isRetard() || (l.getFiche() == null && campagne != null && campagne.isEnRetard() && l.getSalarie().getEvaluable() == 1)).count(); }
    public long getNbHorsDelai() { return lignes.stream().filter(l -> l.getFiche() != null && l.getFiche().getDateSoumission() != null && !l.getFiche().isSoumiseATemps()).count(); }
    public int getTaux() { long b = getBase(); return b == 0 ? 0 : (int) Math.round(100.0 * getNbValidees() / b); }
    public int pct(long v) { long b = getBase(); return b == 0 ? 0 : (int) Math.round(100.0 * v / b); }
    /** Nombre attendu saisi, ou nombre de fiches ouvertes s'il est plus grand. */
    public long getBase() { return campagne == null ? 0 : Math.max(campagne.getEffectifAttendu(), getNbIntegrees()); }
    public long getRestantes() { return campagne == null ? 0 : Math.max(0, campagne.getEffectifAttendu() - getNbIntegrees()); }

    // actions

    public void changerCampagne() { onglet = "tous"; filtreStatut = ""; filtreDirection = ""; charger(); }

    public boolean isPeutValider() { return dcht; }
    /** Validable depuis le suivi : statut « évaluée » et avis du directeur rendu (si la direction en a un). */
    public boolean validable(Ligne l) { return dcht && l.getFiche() != null && Evaluation.EVALUEE.equals(l.getFiche().getStatut()) && !service.avisDirecteurEnAttente(l.getFiche()); }
    public boolean isPeutRelancer() { return dcht || privilegeBean.hasPrivilege("EVAL_ANOM"); }

    public void valider(Ligne l) {
        authorization.requirePrivilege("EVAL_DCHT");
        if (!dcht || l.getFiche() == null || !Evaluation.EVALUEE.equals(l.getFiche().getStatut())) return;
        Evaluation e = service.charger(l.getFiche().getId());
        if (service.avisDirecteurEnAttente(e)) { cd.mura.hrx.utils.Dialogue.alerte("Avis du directeur attendu", "La fiche de " + e.getSalarie().getNomComplet() + " ne peut être validée qu'après l'avis du directeur de sa direction."); return; }
        e = service.valider(e, e.getCommentaireDcht()); e = service.charger(e.getId());
        auditService.updated("Évaluations", "Evaluation", e.getId(), "Fiche validée par la DCHT (suivi)", e.getSalarie().getNomComplet() + " : " + campagne.getLibelle());
        try { notifications.ficheValidee(e); } catch (Exception ex) { }
        charger();
        cd.mura.hrx.utils.Dialogue.succes("Fiche validée", e.getSalarie().getNomComplet() + " - évaluation définitive.");
    }

    public void preparerRelance(Ligne l) {
        cible = l;
        Users dest = l.getCibleRelance();
        String prenom = dest == null ? "" : s(dest.getPrenom());
        String ech = campagne.getDateFin() == null ? "" : " L'échéance de la campagne est fixée au " + campagne.getDateFin().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".";
        if (l.getFiche() == null) {
            relanceObjet = "Collaborateur à intégrer à la campagne " + campagne.getLibelle();
            relanceMessage = "Bonjour " + prenom + ",\n\n" + l.getSalarie().getNomComplet() + " fait partie de votre équipe et n'a pas encore de fiche d'évaluation pour la campagne « " + campagne.getLibelle() + " ». Merci de constituer votre population évaluable depuis Mon équipe" + (l.getNbObjectifs() == 0 ? ", après avoir fixé et validé ses objectifs" : "") + "." + ech;
        } else switch (l.getFiche().getStatut()) {
            case Evaluation.SOUMISE -> { relanceObjet = "Évaluation à réaliser : " + l.getSalarie().getNomComplet(); relanceMessage = "Bonjour " + prenom + ",\n\nL'auto-évaluation de " + l.getSalarie().getNomComplet() + " vous a été soumise. Merci de faire son évaluation et de la transmettre au directeur." + ech; }
            case Evaluation.EVALUEE -> { relanceObjet = "Avis du directeur attendu : " + l.getSalarie().getNomComplet(); relanceMessage = "Bonjour " + prenom + ",\n\nL'évaluation de " + l.getSalarie().getNomComplet() + " attend votre avis. Merci de le donner pour que la DCHT puisse valider la fiche." + ech; }
            default -> { relanceObjet = "Auto-évaluation à renseigner : " + campagne.getLibelle(); relanceMessage = "Bonjour " + prenom + ",\n\nVotre fiche d'auto-évaluation pour la campagne « " + campagne.getLibelle() + " » est toujours en attente" + (Evaluation.RETOURNEE.equals(l.getFiche().getStatut()) ? " de correction" : "") + ". Merci de la compléter et de la soumettre à votre N+1." + ech; }
        }
    }

    public void envoyerRelance() {
        authorization.requireAnyPrivilege("EVAL_DCHT", "EVAL_ANOM");
        if (!isPeutRelancer() || cible == null) return;
        if (s(relanceMessage).isBlank()) { error("Message requis", "Rédigez le message de relance."); return; }
        if (!relancer(cible)) { error("Relance impossible", "Aucun destinataire avec adresse e-mail pour cette ligne."); return; }
        info("Relance envoyée", "À " + cible.getCibleRelance().getNomComplet() + " (" + cible.getCibleRelanceRole() + ").");
        PrimeFaces.current().ajax().addCallbackParam("ok", true);
    }

    private boolean relancer(Ligne l) {
        Users dest = l.getCibleRelance();
        if (dest == null || s(dest.getEmail()).isBlank()) return false;
        if (l.getFiche() == null) notifications.relanceIntegration(dest, l.getSalarie(), campagne, relanceMessage, moi);
        else notifications.relance(l.getFiche(), dest, relanceObjet, relanceMessage, moi);
        auditService.log("NOTIFICATION", "Évaluations", "Evaluation", l.getFiche() == null ? null : l.getFiche().getId(), "Relance DCHT", "Destinataire : " + dest.getNomComplet() + " (" + l.getCibleRelanceRole() + ")\nCollaborateur : " + l.getSalarie().getNomComplet() + "\nObjet : " + relanceObjet, true);
        return true;
    }

    /** Relance groupée : toutes les lignes en retard ou en anomalie bloquante de l'onglet courant, avec le message standard de chaque étape. */
    public void relancerRetardataires() {
        authorization.requireAnyPrivilege("EVAL_DCHT", "EVAL_ANOM");
        if (!isPeutRelancer()) return;
        int ok = 0, ko = 0;
        for (Ligne l : getLignesFiltrees()) {
            if (!l.isRelancable()) continue;
            if (!(l.isRetard() || l.getFiche() == null || l.isAnomalie())) continue;
            preparerRelance(l);
            if (relancer(l)) ok++; else ko++;
        }
        cible = null;
        info(ok + " relance" + (ok > 1 ? "s" : "") + " envoyée" + (ok > 1 ? "s" : ""), ko == 0 ? "" : ko + " ligne" + (ko > 1 ? "s" : "") + " sans destinataire joignable.");
    }

    public String initiales(Users u) {
        String p = s(u.getPrenom()), n = s(u.getNom());
        return ((p.isEmpty() ? "" : p.substring(0, 1)) + (n.isEmpty() ? "" : n.substring(0, 1))).toUpperCase(Locale.ROOT);
    }
    public String depuis(Date d) {
        if (d == null) return "";
        long j = java.time.temporal.ChronoUnit.DAYS.between(d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate(), LocalDate.now());
        return j <= 0 ? "aujourd'hui" : j == 1 ? "hier" : "il y a " + j + " j";
    }
    public String getNomFichierExport() { return campagne == null ? "suivi-evaluations" : "suivi-evaluations-" + campagne.getLibelle().replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(Locale.ROOT); }

    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
    private static String s(String v) { return v == null ? "" : v; }
}
