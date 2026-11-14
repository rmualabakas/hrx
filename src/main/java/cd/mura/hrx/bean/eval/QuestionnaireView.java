package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.entity.eval.EvalChaud;
import cd.mura.hrx.entity.eval.EvalChaudQuestion;
import cd.mura.hrx.entity.eval.EvalChaudReponse;
import cd.mura.hrx.entity.eval.EvalChaudValeur;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.EvalChaudService;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.*;

/** Questionnaire d'une évaluation de formation : réponse du participant (à chaud ou à froid) et, à froid, avis de son supérieur hiérarchique. */
@Named
@ViewScoped
@Getter
@Setter
public class QuestionnaireView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private AuditService auditService;
    @EJB private EvalChaudService service;

    @Inject private cd.mura.hrx.service.FicheChaudExportService exportService;
    @EJB private cd.mura.hrx.service.NotificationService notifications;

    /** Ligne saisie d'une liste ou d'un plan d'actions (échéance utilisée pour le plan seulement). */
    @Getter @Setter
    public static class Ligne implements Serializable {
        private String texte;
        private String horizon = "H0";
        public Ligne() { }
        public Ligne(String horizon, String texte) { this.horizon = horizon; this.texte = texte; }
    }

    /** Niveau d'acquisition d'un point cité (fiche à froid, Q.3). */
    @Getter @Setter
    public static class Niveau implements Serializable {
        private String point;
        private Integer niveau;
        public Niveau() { }
        public Niveau(String point, Integer niveau) { this.point = point; this.niveau = niveau; }
    }

    private Integer id;
    /** Réponse à consulter en tant qu'évaluateur (N+1), à froid. */
    private Integer rep;
    /** Le lecteur est le supérieur hiérarchique du collaborateur (avis à froid). */
    private boolean evaluateur;
    /** Choix cochés (choix multiples, objectifs) et précision « autre », par question. */
    private Map<Integer, List<String>> choixSel = new HashMap<>();
    private Map<Integer, String> autres = new HashMap<>();
    /** Niveaux d'acquisition saisis, par question puis par point. */
    private Map<Integer, Map<String, Niveau>> niveaux = new HashMap<>();
    private Integer avisNote;
    private String avisCommentaire;
    private EvalChaud questionnaire;
    private EvalChaudReponse reponse;
    private boolean participant;
    private Map<Integer, Integer> notes = new HashMap<>();
    private Map<Integer, String> textes = new HashMap<>();
    /** Lignes des questions « liste » et « plan », par question. */
    private Map<Integer, List<Ligne>> lignes = new HashMap<>();

    public void charger() {
        questionnaire = null; reponse = null; participant = false; evaluateur = false;
        int moi = loginBean.getUsers().getId();
        EvalChaud c;
        if (rep != null) {
            // lecture par le supérieur hiérarchique : la réponse à froid de son collaborateur
            EvalChaudReponse r = service.reponse(rep);
            if (r == null || !EvalChaudService.estEvaluateur(r, loginBean.getUsers())) return;
            c = service.charger(r.getEvalChaud().getId());
            if (c == null || !c.isFroid()) return;
            id = c.getId();
            questionnaire = c; reponse = r; evaluateur = true;
        } else {
            if (id == null) return;
            c = service.charger(id);
            if (c == null || c.isBrouillon() || !service.estParticipant(id, moi)) return;
            questionnaire = c;
            participant = true;
            reponse = service.reponseDe(id, moi);
            if (reponse != null) reponse = service.reponse(reponse.getId());
        }
        notes = new HashMap<>(); textes = new HashMap<>(); lignes = new HashMap<>();
        choixSel = new HashMap<>(); autres = new HashMap<>(); niveaux = new HashMap<>();
        avisNote = reponse == null ? null : reponse.getAvisNote();
        avisCommentaire = reponse == null ? null : reponse.getAvisCommentaire();
        if (reponse != null) for (EvalChaudValeur v : reponse.getValeurs()) {
            Integer qid = v.getQuestion().getId();
            if (v.getNote() != null) notes.put(qid, v.getNote());
            if (v.getTexte() != null) textes.put(qid, v.getTexte());
        }
        for (EvalChaudQuestion q : c.getQuestions()) {
            if (q.isMulti() || q.isObjectifs()) {
                List<String> sel = new ArrayList<>();
                for (String x : EvalChaudService.lignes(textes.get(q.getId()))) {
                    String[] ch = EvalChaudService.choix(x);
                    if (x.startsWith(EvalChaudQuestion.AUTRE + "\t")) { sel.add(EvalChaudQuestion.AUTRE); autres.put(q.getId(), ch[1]); } else sel.add(x);
                }
                choixSel.put(q.getId(), sel);
            }
            if (q.isNiveaux()) {
                Map<String, Niveau> m = new LinkedHashMap<>();
                for (String x : EvalChaudService.lignes(textes.get(q.getId()))) {
                    String[] a = EvalChaudService.niveauLigne(x);
                    m.put(a[0], new Niveau(a[0], a[1].matches("[1-5]") ? Integer.valueOf(a[1]) : null));
                }
                niveaux.put(q.getId(), m);
            }
            if (!q.isListe() && !q.isPlan()) continue;
            List<Ligne> l = new ArrayList<>();
            for (String x : EvalChaudService.lignes(textes.get(q.getId()))) {
                if (q.isPlan()) { String[] a = EvalChaudService.action(x); l.add(new Ligne(a[0], a[1])); } else l.add(new Ligne(null, x));
            }
            if (reponse == null) while (l.size() < Math.min(3, q.getMaxPoints())) l.add(new Ligne(q.isPlan() ? "H0" : null, null));
            lignes.put(q.getId(), l);
        }
    }

    public boolean isSaisie() { return participant && questionnaire != null && reponse == null && questionnaire.isRepondable(); }
    public boolean isFroid() { return questionnaire != null && questionnaire.isFroid(); }
    /** Le supérieur hiérarchique peut encore donner son avis. */
    public boolean isAvisSaisie() { return evaluateur && reponse != null && !reponse.isAvisDonne(); }
    /** Collaborateur concerné par la fiche : le lecteur, ou le collaborateur évalué quand le N+1 consulte. */
    public cd.mura.hrx.entity.config.Users getCollaborateur() { return evaluateur && reponse != null ? reponse.getParticipant() : loginBean.getUsers(); }
    public cd.mura.hrx.entity.config.Users getSuperieur() { cd.mura.hrx.entity.config.Users u = getCollaborateur(); return u == null ? null : u.getSuperieurHierarchique(); }
    public List<String[]> getBareme() {
        List<String[]> l = new ArrayList<>();
        for (int k = 1; k <= 5; k++) l.add(new String[]{String.valueOf(k), EvalChaudReponse.AVIS_LIBELLES[k], EvalChaudReponse.AVIS_TAUX[k] + " %", k >= 4 ? "Efficace" : "Pas efficace"});
        return l;
    }

    /** Question conditionnelle : affichée seulement si la question oui / non qui la précède a reçu « non ». */
    public boolean visible(EvalChaudQuestion q) {
        if (!q.isConditionnelle()) return true;
        Integer dernier = null;
        for (EvalChaudQuestion x : questionnaire.getQuestions()) {
            if (x.getId() == q.getId()) break;
            if (x.isOuiNon()) dernier = notes.get(x.getId());
        }
        return Integer.valueOf(0).equals(dernier);
    }
    /** Question « liste » dont une question « niveaux » reprend les points (la plus proche avant elle). */
    private EvalChaudQuestion source(EvalChaudQuestion q) {
        EvalChaudQuestion s = null;
        for (EvalChaudQuestion x : questionnaire.getQuestions()) { if (x.getId() == q.getId()) break; if (x.isListe()) s = x; }
        return s;
    }
    public String sourceNum(EvalChaudQuestion q) { EvalChaudQuestion s = source(q); return s == null ? "" : "Q." + (questionnaire.getQuestions().indexOf(s) + 1); }
    /** Points à qualifier : ceux saisis à la question « liste » source, chacun avec son niveau. */
    public List<Niveau> niveauxDe(EvalChaudQuestion q) {
        Map<String, Niveau> m = niveaux.computeIfAbsent(q.getId(), k -> new LinkedHashMap<>());
        EvalChaudQuestion src = source(q);
        if (src == null) return new ArrayList<>(m.values());
        if (!isSaisie()) return new ArrayList<>(m.values());
        List<Niveau> l = new ArrayList<>();
        for (Ligne x : lignesDe(src)) {
            if (x.getTexte() == null || x.getTexte().isBlank()) continue;
            String p = x.getTexte().replaceAll("[\\t\\r\\n]+", " ").trim();
            l.add(m.computeIfAbsent(p, k -> new Niveau(k, null)));
        }
        return l;
    }
    public List<Integer> getCinq() { return List.of(1, 2, 3, 4, 5); }
    public List<String> optionsDe(EvalChaudQuestion q) { return q.isObjectifs() ? questionnaire.getObjectifsListe() : q.getOptionsListe(); }
    /** Cases proposées : [valeur, libellé], « autre » en dernier. */
    public List<String[]> choixDe(EvalChaudQuestion q) {
        List<String[]> l = new ArrayList<>();
        for (String o : optionsDe(q)) l.add(new String[]{o, o});
        if (q.isAutre()) l.add(new String[]{EvalChaudQuestion.AUTRE, "Autre (veuillez préciser)"});
        return l;
    }
    /** Les cases cochées : la case à cocher multiple peut écrire un tableau dans la map, on le ramène à une liste. */
    @SuppressWarnings("unchecked")
    public List<String> selDe(EvalChaudQuestion q) {
        Object v = ((Map<Integer, Object>) (Map<?, ?>) choixSel).get(q.getId());
        List<String> l;
        if (v instanceof List<?> x) l = (List<String>) x;
        else if (v instanceof Object[] a) { l = new ArrayList<>(); for (Object o : a) if (o != null) l.add(o.toString()); }
        else l = new ArrayList<>();
        choixSel.put(q.getId(), l);
        return l;
    }
    public void setSel(EvalChaudQuestion q, List<String> v) { choixSel.put(q.getId(), v == null ? new ArrayList<>() : new ArrayList<>(v)); }
    public boolean autreVisible(EvalChaudQuestion q) { return q.isAutre() && selDe(q).contains(EvalChaudQuestion.AUTRE); }
    public boolean coche(EvalChaudQuestion q, String o) { return selDe(q).contains(o); }
    public String libelleNiveau(EvalChaudQuestion q, Integer k) { return k == null ? "—" : q.niveau(k); }
    public String typeLabel(EvalChaudQuestion q) { return EvalChaudQuestion.typeLabel(q.getType()); }
    public List<String[]> getHorizons() { return Arrays.asList(EvalChaudQuestion.HORIZONS); }
    public String horizonLabel(String code) { return EvalChaudQuestion.horizonLabel(code); }
    public List<Ligne> lignesDe(EvalChaudQuestion q) { return lignes.computeIfAbsent(q.getId(), k -> new ArrayList<>()); }
    public boolean peutAjouter(EvalChaudQuestion q) { return isSaisie() && lignesDe(q).size() < q.getMaxPoints(); }
    public void ajouterLigne(EvalChaudQuestion q) { if (isSaisie() && lignesDe(q).size() < q.getMaxPoints()) lignesDe(q).add(new Ligne(q.isPlan() ? "H0" : null, null)); }
    public void retirerLigne(EvalChaudQuestion q, int index) { List<Ligne> l = lignesDe(q); if (isSaisie() && index >= 0 && index < l.size() && l.size() > 1) l.remove(index); }
    /** Le « oui » appelle une précision. */
    public boolean precisionVisible(EvalChaudQuestion q) { return q.isAvecPrecision() && Integer.valueOf(1).equals(notes.get(q.getId())); }

    /** Une question est répondue si sa note, son texte ou au moins une ligne est renseigné. */
    public boolean repondue(EvalChaudQuestion q) {
        if (!visible(q)) return true;
        if (q.isMulti() || q.isObjectifs()) return !selDe(q).isEmpty() || (q.isObjectifs() && questionnaire.getObjectifsListe().isEmpty());
        if (q.isNiveaux()) { List<Niveau> l = niveauxDe(q); return l.isEmpty() || l.stream().allMatch(x -> x.getNiveau() != null); }
        if (q.isListe() || q.isPlan()) return lignesDe(q).stream().anyMatch(x -> x.getTexte() != null && !x.getTexte().isBlank());
        if (q.isTexte()) { String t = textes.get(q.getId()); return t != null && !t.isBlank(); }
        return notes.get(q.getId()) != null;
    }
    public long getNbRepondues() { return questionnaire == null ? 0 : questionnaire.getQuestions().stream().filter(this::repondue).count(); }
    public int getPctRepondues() { return questionnaire == null || questionnaire.getQuestions().isEmpty() ? 0 : (int) Math.round(100.0 * getNbRepondues() / questionnaire.getQuestions().size()); }
    public java.time.LocalDate getAujourdhui() { return java.time.LocalDate.now(); }

    /** Les listes et plans sont transmis au service sous forme de lignes (« échéance tabulation action » pour le plan). */
    private Map<Integer, String> textesComplets() {
        Map<Integer, String> m = new HashMap<>(textes);
        for (EvalChaudQuestion q : questionnaire.getQuestions()) {
            if (q.isMulti() || q.isObjectifs()) {
                List<String> l = new ArrayList<>();
                for (String x : selDe(q)) {
                    if (EvalChaudQuestion.AUTRE.equals(x)) { String a = autres.get(q.getId()); if (a != null && !a.isBlank()) l.add(EvalChaudQuestion.AUTRE + "\t" + a.replaceAll("[\\t\\r\\n]+", " ").trim()); }
                    else l.add(x);
                }
                m.put(q.getId(), l.isEmpty() ? null : String.join("\n", l));
                continue;
            }
            if (q.isNiveaux()) {
                List<String> l = new ArrayList<>();
                for (Niveau x : niveauxDe(q)) if (x.getNiveau() != null) l.add(x.getPoint() + "\t" + x.getNiveau());
                m.put(q.getId(), l.isEmpty() ? null : String.join("\n", l));
                continue;
            }
            if (!q.isListe() && !q.isPlan()) continue;
            List<String> l = new ArrayList<>();
            for (Ligne x : lignesDe(q)) {
                if (x.getTexte() == null || x.getTexte().isBlank()) continue;
                String t = x.getTexte().replaceAll("[\\t\\r\\n]+", " ").trim();
                l.add(q.isPlan() ? (x.getHorizon() == null ? "H0" : x.getHorizon()) + "\t" + t : t);
            }
            m.put(q.getId(), l.isEmpty() ? null : String.join("\n", l));
        }
        return m;
    }

    public void envoyer() {
        if (!isSaisie()) return;
        try {
            service.repondre(id, loginBean.getUsers(), notes, textesComplets());
            auditService.created("Formations", "EvalChaudReponse", id, "Réponse à l'évaluation " + (isFroid() ? "à froid" : "à chaud"), questionnaire.getIntitule());
            String suite = "Votre avis a bien été enregistré. Vous pouvez télécharger votre fiche.";
            if (isFroid()) {
                cd.mura.hrx.entity.config.Users n1 = getSuperieur();
                EvalChaudReponse r = service.reponseDe(id, loginBean.getUsers().getId());
                if (n1 != null && r != null && n1.getEmail() != null && !n1.getEmail().isBlank()) {
                    try { notifications.avisFroidAttendu(questionnaire, loginBean.getUsers(), n1, r.getId()); } catch (Exception ex) { /* l'envoi ne bloque pas la réponse */ }
                }
                suite = n1 == null ? "Votre fiche est enregistrée. Aucun supérieur hiérarchique n'est renseigné : la DCHT complétera l'avis."
                        : "Votre fiche est transmise à " + n1.getNomComplet() + " pour son avis sur l'efficacité de la formation.";
            }
            cd.mura.hrx.utils.Dialogue.succes("Merci pour votre réponse", suite);
            charger();
        } catch (Exception ex) {
            cd.mura.hrx.utils.Dialogue.erreur("Réponse non enregistrée", PeriodeEssaiView.cause(ex));
        }
    }

    /** Avis général du supérieur hiérarchique sur l'efficacité de la formation (vaut signature). */
    public void donnerAvis() {
        if (!isAvisSaisie()) return;
        try {
            service.donnerAvis(reponse.getId(), loginBean.getUsers(), avisNote, avisCommentaire);
            auditService.updated("Formations", "EvalChaudReponse", reponse.getId(), "Avis du supérieur sur l'évaluation à froid",
                    questionnaire.getIntitule() + " : " + reponse.getParticipant().getNomComplet() + " - " + avisNote + "/5");
            cd.mura.hrx.utils.Dialogue.succes("Avis enregistré et signé",
                    "L'évaluation à froid de " + reponse.getParticipant().getNomComplet() + " est complète.");
            charger();
        } catch (Exception ex) {
            cd.mura.hrx.utils.Dialogue.erreur("Avis non enregistré", PeriodeEssaiView.cause(ex));
        }
    }

    /** Fiche MAF 063 / MAF 064 du participant (remplie s'il a répondu, vierge sinon). */
    public org.primefaces.model.StreamedContent getFichePdf() {
        if (questionnaire == null) return null;
        byte[] data = exportService.pdf(questionnaire, getCollaborateur(), reponse);
        String n = java.text.Normalizer.normalize(questionnaire.getIntitule(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]+", "_");
        String pre = isFroid() ? "MAF064_Evaluation_a_froid_" + java.text.Normalizer.normalize(getCollaborateur().getNomComplet(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]+", "_") + "_" : "MAF063_Evaluation_a_chaud_";
        return org.primefaces.model.DefaultStreamedContent.builder().name(pre + n + ".pdf").contentType("application/pdf")
                .stream(() -> new java.io.ByteArrayInputStream(data)).build();
    }
}
