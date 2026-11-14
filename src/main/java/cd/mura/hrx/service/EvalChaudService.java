package cd.mura.hrx.service;

import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.EvalChaud;
import cd.mura.hrx.entity.eval.EvalChaudQuestion;
import cd.mura.hrx.entity.eval.EvalChaudReponse;
import cd.mura.hrx.entity.eval.EvalChaudValeur;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.Serializable;
import java.util.*;

/** Évaluations des formations, à chaud et à froid : préparation, lancement, réponses des participants, avis du N+1 (à froid) et résultats. */
@Stateless
public class EvalChaudService {

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    /** Pied et référence du modèle officiel. */
    public static final String REF_MAF063 = "MAF 063 DCHT 02 / FICHE D'EVALUATION A CHAUD DE LA SATISFACTION DES PARTICIPANTS / Juin 2022";
    public static final String INTRO_MAF063 = "Votre avis compte ! Évaluez notre programme de formation. En répondant à ce questionnaire d'évaluation, vous nous aiderez "
            + "à mieux comprendre vos besoins et à proposer des formations toujours plus adaptées à vos attentes. Le questionnaire est simple et rapide à remplir "
            + "(durée : environ 4 minutes). Nous vous remercions de votre participation et de votre aide dans notre démarche d'amélioration continue.";

    /**
     * Questionnaire officiel MAF 063 DCHT 02 : libellé, type, borne 1, borne 5, échelle centrée, précision si oui, obligatoire.
     * Modifiable avant la première réponse.
     */
    public static final String[][] MODELE_MAF063 = {
            {"Dans quelle mesure êtes-vous satisfait de la formation ?", EvalChaudQuestion.NOTE, "Très insatisfait", "Très satisfait", "", "", "1"},
            {"Dans quelle mesure avez-vous trouvé la formation pertinente par rapport à vos besoins et attentes ?", EvalChaudQuestion.NOTE, "Pas du tout pertinente", "Très pertinente", "", "", "1"},
            {"Quelles nouvelles compétences avez-vous acquises grâce à cette formation ?", EvalChaudQuestion.LISTE, "", "", "", "", "1"},
            {"Aviez-vous été informé des objectifs à atteindre et des compétences à acquérir lors de cette formation ?", EvalChaudQuestion.OUI_NON, "", "", "", "", "1"},
            {"Dans quelle mesure le formateur a-t-il démontré une connaissance approfondie du sujet ?", EvalChaudQuestion.NOTE, "Pas du tout démontré", "Démontré de manière exceptionnelle", "", "", "1"},
            {"Dans quelle mesure êtes-vous satisfait(e) des conditions de la formation (ex. : salle, équipement, matériel) ?", EvalChaudQuestion.NOTE, "Très insatisfait", "Très satisfait", "", "", "1"},
            {"Comment évaluez-vous le rythme de la formation en termes de progression et de temps alloué à chaque sujet ?", EvalChaudQuestion.NOTE, "Très lent", "Très rapide", "1", "", "1"},
            {"À quel point le formateur a-t-il su expliquer les concepts de manière claire et compréhensible ?", EvalChaudQuestion.NOTE, "Pas du tout clair et compréhensible", "Très clair et compréhensible", "", "", "1"},
            {"Dans quelle mesure le formateur a-t-il su répondre à vos préoccupations ?", EvalChaudQuestion.NOTE, "Pas du tout satisfaisante", "Très satisfaisante", "", "", "1"},
            {"Souhaiteriez-vous une suite à cette formation ?", EvalChaudQuestion.OUI_NON, "", "", "", "Si oui, veuillez préciser", "1"},
            {"Quelles actions allez-vous mettre en place au sortir de cette formation ?", EvalChaudQuestion.PLAN, "", "", "", "", "0"}};

    /** Pied, accroche et modèle de la fiche d'évaluation à froid. */
    public static final String REF_MAF064 = "MAF 064 DCHT 05 / FICHE D'EVALUATION A FROID";
    public static final String INTRO_MAF064 = "Dans le cadre de notre évaluation à froid, nous aimerions recueillir votre opinion sur l'efficacité et l'impact "
            + "de cette formation sur votre poste et sur l'entreprise. Vos réponses sont complétées par l'avis de votre supérieur hiérarchique.";
    private static final String MESURE = "Très faiblement\nFaiblement\nModérément\nFortement\nTrès fortement";

    /**
     * Questionnaire officiel MAF 064 DCHT 05 : libellé, type, options (une par ligne), autre, si non, max points, précision si oui, obligatoire.
     * Q.3 (niveaux) porte sur les compétences citées en Q.2 ; Q.5 ne s'affiche que si Q.4 = non.
     */
    public static final String[][] MODELE_MAF064 = {
            {"Rappel des objectifs de la formation : cochez les objectifs que vous estimez atteints", EvalChaudQuestion.OBJECTIFS, "", "", "", "", "", "0"},
            {"Avez-vous pu, à la suite de la formation, acquérir et développer de nouvelles compétences ? (dans la limite de 3 compétences maximum)", EvalChaudQuestion.LISTE, "", "", "", "3", "", "1"},
            {"Pour chaque compétence citée, veuillez préciser votre niveau d'acquisition", EvalChaudQuestion.NIVEAUX, EvalChaudQuestion.NIVEAUX_ACQUISITION, "", "", "", "", "1"},
            {"Utilisez-vous régulièrement ce que vous avez appris en formation ?", EvalChaudQuestion.OUI_NON, "", "", "", "", "", "1"},
            {"Si vous ne l'utilisez pas, pour quelle(s) raison(s) ?", EvalChaudQuestion.MULTI,
                    "Je n'ai pas eu le temps\nJ'ai manqué d'accompagnement\nJe n'ai pas bénéficié du matériel adéquat\nLa formation ne m'a pas donné les compétences nécessaires\nJ'ai eu des difficultés à adapter ce que j'ai appris à mon poste",
                    "1", "1", "", "", "1"},
            {"De manière générale, citez les principales difficultés rencontrées (dans la limite de 3 difficultés maximum)", EvalChaudQuestion.LISTE, "", "", "", "3", "", "0"},
            {"Dans quelle mesure pensez-vous que les compétences acquises à l'issue de la formation ont été appliquées dans les activités quotidiennes ?", EvalChaudQuestion.NOTE, MESURE, "", "", "", "", "1"},
            {"Comment la formation a-t-elle contribué à l'atteinte de vos objectifs individuels ?", EvalChaudQuestion.NOTE, "Pas du tout\nFaiblement\nModérément\nFortement\nTrès fortement", "", "", "", "", "1"},
            {"Quels changements spécifiques avez-vous observés au sein de vos activités après la formation ?", EvalChaudQuestion.MULTI,
                    "Amélioration de la productivité\nRéduction des erreurs ou dysfonctionnements\nMeilleure satisfaction des clients\nMeilleure maîtrise des risques", "1", "", "", "", "1"},
            {"Veuillez fournir un exemple concret ou un livrable en lien avec l'un des changements que vous avez mentionnés ci-dessus", EvalChaudQuestion.TEXTE, "", "", "", "", "", "0"},
            {"Avez-vous identifié des situations spécifiques où vous avez utilisé les compétences acquises lors de la formation pour résoudre des problèmes ou relever des défis ?", EvalChaudQuestion.OUI_NON, "", "", "", "", "Si oui, veuillez préciser", "1"},
            {"Dans quelle mesure la formation a-t-elle contribué à améliorer les performances globales de l'entreprise ?", EvalChaudQuestion.NOTE, MESURE, "", "", "", "", "1"},
            {"Quels indicateurs de performance clés utilisez-vous pour mesurer l'impact de cette formation sur l'entreprise ?", EvalChaudQuestion.MULTI,
                    "Augmentation du chiffre d'affaires / PNB\nRéduction des charges\nAmélioration de la qualité de service à la clientèle\nRéduction des pertes opérationnelles", "1", "", "", "", "1"},
            {"Veuillez fournir un exemple concret ou un livrable en lien avec l'indicateur de performance que vous avez mentionné ci-dessus", EvalChaudQuestion.TEXTE, "", "", "", "", "", "0"},
            {"Pensez-vous qu'il est nécessaire de bénéficier d'une formation complémentaire pour améliorer votre productivité ?", EvalChaudQuestion.OUI_NON, "", "", "", "", "Si oui, veuillez préciser", "1"}};

    /** Questions du modèle officiel (MAF 063 à chaud, MAF 064 à froid), prêtes à être rattachées à une évaluation. */
    public static List<EvalChaudQuestion> questionsModele(EvalChaud c) {
        if (c.isFroid()) {
            List<EvalChaudQuestion> l = new ArrayList<>();
            int i = 0;
            for (String[] m : MODELE_MAF064) {
                EvalChaudQuestion q = new EvalChaudQuestion();
                q.setLibelle(m[0]); q.setType(m[1]);
                q.setOptions(m[2].isEmpty() ? null : m[2]);
                q.setAvecAutre("1".equals(m[3]) ? Boolean.TRUE : null);
                q.setSiNon("1".equals(m[4]) ? Boolean.TRUE : null);
                q.setMaxLignes(m[5].isEmpty() ? null : Integer.valueOf(m[5]));
                q.setPrecisionOui(m[6].isEmpty() ? null : m[6]);
                if (q.isNote()) { List<String> o = q.getOptionsListe(); q.setLibelleMin(o.get(0)); q.setLibelleMax(o.get(4)); }
                q.setObligatoire("1".equals(m[7])); q.setOrdre(i++); q.setEvalChaud(c);
                l.add(q);
            }
            return l;
        }
        List<EvalChaudQuestion> l = new ArrayList<>();
        int i = 0;
        for (String[] m : MODELE_MAF063) {
            EvalChaudQuestion q = new EvalChaudQuestion();
            q.setLibelle(m[0]); q.setType(m[1]);
            q.setLibelleMin(m[2].isEmpty() ? null : m[2]); q.setLibelleMax(m[3].isEmpty() ? null : m[3]);
            q.setEchelleCentree("1".equals(m[4]) ? Boolean.TRUE : null);
            q.setPrecisionOui(m[5].isEmpty() ? null : m[5]);
            q.setObligatoire("1".equals(m[6])); q.setOrdre(i++); q.setEvalChaud(c);
            l.add(q);
        }
        return l;
    }

    /** Lignes d'une réponse « liste » ou « plan » (une par ligne). */
    public static List<String> lignes(String texte) {
        List<String> l = new ArrayList<>();
        if (texte != null) for (String x : texte.split("\\R")) if (!x.isBlank()) l.add(x.trim());
        return l;
    }
    /** Action d'un plan : [code échéance, action]. */
    public static String[] action(String ligne) {
        int t = ligne.indexOf('\t');
        return t < 0 ? new String[]{"", ligne} : new String[]{ligne.substring(0, t), ligne.substring(t + 1)};
    }
    /** Choix d'une réponse à choix multiples : [libellé, précision] (précision renseignée pour « autre »). */
    public static String[] choix(String ligne) {
        String p = EvalChaudQuestion.AUTRE + "\t";
        return ligne.startsWith(p) ? new String[]{"Autre", ligne.substring(p.length())} : new String[]{ligne, ""};
    }
    /** Niveau d'un point : [point, niveau 1 à 5 ou vide]. */
    public static String[] niveauLigne(String ligne) {
        int t = ligne.lastIndexOf('\t');
        return t < 0 ? new String[]{ligne, ""} : new String[]{ligne.substring(0, t), ligne.substring(t + 1)};
    }

    /** Texte lisible d'une réponse (dépouillement, exports). */
    public static String lisible(EvalChaudQuestion q, Integer note, String texte) {
        if (q.isMulti() || q.isObjectifs()) {
            List<String> l = new ArrayList<>();
            for (String x : lignes(texte)) { String[] c = choix(x); l.add(c[1].isEmpty() ? c[0] : c[0] + " : " + c[1]); }
            return String.join(" ; ", l);
        }
        if (q.isNiveaux()) {
            List<String> l = new ArrayList<>();
            for (String x : lignes(texte)) { String[] a = niveauLigne(x); l.add(a[0] + " : " + (a[1].isEmpty() ? "non précisé" : q.niveau(Integer.parseInt(a[1])))); }
            return String.join(" ; ", l);
        }
        if (q.isNote() && note != null && q.isLibellee()) return note + " - " + q.niveau(note);
        if (q.isListe()) return String.join(" ; ", lignes(texte));
        if (q.isPlan()) {
            List<String> l = new ArrayList<>();
            for (String x : lignes(texte)) { String[] a = action(x); l.add(EvalChaudQuestion.horizonLabel(a[0]) + " : " + a[1]); }
            return String.join(" ; ", l);
        }
        if (q.isTexte()) return texte == null ? "" : texte;
        if (q.isOuiNon()) return note == null ? "" : (note == 1 ? "Oui" : "Non") + (texte == null || texte.isBlank() ? "" : " - " + texte);
        return note == null ? "" : String.valueOf(note);
    }

    // lecture

    /** Évaluations à chaud pouvant servir de base à une évaluation à froid (lancées). */
    public List<EvalChaud> sourcesFroid() {
        return em.createQuery("select c from EvalChaud c where (c.nature is null or c.nature <> :f) and c.statut <> :b order by c.dateCreation desc", EvalChaud.class)
                .setParameter("f", EvalChaud.FROID).setParameter("b", EvalChaud.BROUILLON).getResultList();
    }

    public List<EvalChaud> lister() {
        return em.createQuery("select c from EvalChaud c order by c.dateCreation desc, c.id desc", EvalChaud.class).getResultList();
    }

    /** Effectifs par évaluation : [participants, réponses]. */
    public Map<Integer, long[]> compteurs() {
        Map<Integer, long[]> m = new HashMap<>();
        for (Object[] r : em.createQuery("select c.id, count(p) from EvalChaud c join c.participants p group by c.id", Object[].class).getResultList())
            m.computeIfAbsent((Integer) r[0], k -> new long[2])[0] = (Long) r[1];
        for (Object[] r : em.createQuery("select r.evalChaud.id, count(r) from EvalChaudReponse r group by r.evalChaud.id", Object[].class).getResultList())
            m.computeIfAbsent((Integer) r[0], k -> new long[2])[1] = (Long) r[1];
        return m;
    }

    public EvalChaud charger(int id) {
        List<EvalChaud> l = em.createQuery("select distinct c from EvalChaud c left join fetch c.questions where c.id = :id", EvalChaud.class).setParameter("id", id).getResultList();
        if (l.isEmpty()) return null;
        EvalChaud c = l.get(0);
        em.createQuery("select distinct c from EvalChaud c left join fetch c.participants where c.id = :id", EvalChaud.class).setParameter("id", id).getResultList();
        // JPQL strict : pas d'alias sur un fetch join, la direction est initialisée ici dans la transaction
        for (Users u : c.getParticipants()) org.hibernate.Hibernate.initialize(u.getDirection());
        return c;
    }

    public long nbReponses(int id) {
        return em.createQuery("select count(r) from EvalChaudReponse r where r.evalChaud.id = :c", Long.class).setParameter("c", id).getSingleResult();
    }

    public boolean estParticipant(int id, int userId) {
        return em.createQuery("select count(c) from EvalChaud c join c.participants p where c.id = :c and p.id = :u", Long.class)
                .setParameter("c", id).setParameter("u", userId).getSingleResult() > 0;
    }

    public EvalChaudReponse reponseDe(int id, int userId) {
        List<EvalChaudReponse> l = em.createQuery("select distinct r from EvalChaudReponse r left join fetch r.valeurs where r.evalChaud.id = :c and r.participant.id = :u", EvalChaudReponse.class)
                .setParameter("c", id).setParameter("u", userId).getResultList();
        return l.isEmpty() ? null : l.get(0);
    }

    /** Questionnaires adressés à un collaborateur (hors brouillons) ; la valeur indique s'il a répondu. */
    public Map<EvalChaud, Boolean> mesQuestionnaires(int userId) {
        List<EvalChaud> l = em.createQuery("select c from EvalChaud c join c.participants p where p.id = :u and c.statut <> :b order by c.dateDebut desc, c.id desc", EvalChaud.class)
                .setParameter("u", userId).setParameter("b", EvalChaud.BROUILLON).getResultList();
        Set<Integer> repondus = new HashSet<>(em.createQuery("select r.evalChaud.id from EvalChaudReponse r where r.participant.id = :u", Integer.class).setParameter("u", userId).getResultList());
        Map<EvalChaud, Boolean> m = new LinkedHashMap<>();
        for (EvalChaud c : l) m.put(c, repondus.contains(c.getId()));
        return m;
    }

    public List<Users> nonRepondants(int id) {
        return em.createQuery("""
                select p from EvalChaud c join c.participants p where c.id = :c and p.statut = 1
                and not exists (select r from EvalChaudReponse r where r.evalChaud.id = c.id and r.participant.id = p.id)
                order by p.nom, p.prenom""", Users.class).setParameter("c", id).getResultList();
    }

    public Set<Integer> repondants(int id) {
        return new HashSet<>(em.createQuery("select r.participant.id from EvalChaudReponse r where r.evalChaud.id = :c", Integer.class).setParameter("c", id).getResultList());
    }

    // préparation

    public EvalChaud enregistrer(EvalChaud c, Collection<Integer> participantIds, Users auteur) {
        if (c.getIntitule() == null || c.getIntitule().isBlank()) throw new IllegalArgumentException("L'intitulé de la formation est obligatoire");
        if (c.getDateDebut() != null && c.getDateFin() != null && c.getDateFin().isBefore(c.getDateDebut()))
            throw new IllegalArgumentException("La clôture du questionnaire doit suivre son ouverture");
        if (c.isCloturee()) throw new IllegalStateException("Une évaluation clôturée n'est plus modifiable");
        boolean questionsFigees = c.getId() > 0 && nbReponses(c.getId()) > 0;
        if (questionsFigees) {
            // Des réponses existent : le questionnaire reste celui auquel les participants ont répondu.
            c.setQuestions(new ArrayList<>(em.createQuery("select q from EvalChaudQuestion q where q.evalChaud.id = :c order by q.ordre, q.id", EvalChaudQuestion.class)
                    .setParameter("c", c.getId()).getResultList()));
        } else {
            c.getQuestions().removeIf(q -> q.getLibelle() == null || q.getLibelle().isBlank());
            int i = 0;
            for (EvalChaudQuestion q : c.getQuestions()) {
                q.setEvalChaud(c); q.setOrdre(i++); q.setLibelle(q.getLibelle().trim());
                if (!q.isNote()) { q.setLibelleMin(null); q.setLibelleMax(null); q.setEchelleCentree(null); }
                if (!q.isOuiNon() || q.getPrecisionOui() == null || q.getPrecisionOui().isBlank()) q.setPrecisionOui(null);
                if (q.isNote() || q.isMulti() || q.isNiveaux()) {
                    List<String> o = new ArrayList<>();
                    if (q.getOptions() != null) for (String x : q.getOptions().split("\\R")) if (!x.isBlank()) o.add(x.trim());
                    q.setOptions(o.isEmpty() ? null : String.join("\n", o));
                    if (q.isMulti() && o.isEmpty() && !q.isAutre()) throw new IllegalArgumentException("Indiquez les choix proposés : " + q.getLibelle());
                    if (q.isNote() && !o.isEmpty() && o.size() != 5) throw new IllegalArgumentException("Une note libellée attend 5 libellés (un par niveau) : " + q.getLibelle());
                    if (q.isNiveaux() && !o.isEmpty() && o.size() != 5) throw new IllegalArgumentException("Indiquez 5 niveaux d'acquisition : " + q.getLibelle());
                    if (q.isNote() && o.size() == 5) { q.setLibelleMin(o.get(0)); q.setLibelleMax(o.get(4)); }
                } else q.setOptions(null);
                if (!q.isMulti()) q.setAvecAutre(null);
                if (!q.isListe() || q.getMaxLignes() == null || q.getMaxLignes() <= 0) q.setMaxLignes(null);
                if (!Boolean.TRUE.equals(q.getSiNon())) q.setSiNon(null);
            }
        }
        Set<Users> pop = new LinkedHashSet<>();
        for (Integer uid : new LinkedHashSet<>(participantIds)) if (uid != null) pop.add(em.getReference(Users.class, uid));
        c.setParticipants(pop);
        c.setDateMaj(new Date());
        if (c.getId() == 0) {
            c.setCreePar(auteur == null ? 0 : auteur.getId());
            em.persist(c); em.flush();
            return c;
        }
        EvalChaud m = em.merge(c);
        em.flush();
        return m;
    }

    /** Ouvre le questionnaire ; renvoie les participants actifs à prévenir. */
    public List<Users> lancer(int id) {
        EvalChaud c = charger(id);
        if (c == null || !c.isBrouillon()) throw new IllegalStateException("Seule une évaluation en brouillon peut être lancée");
        if (c.getQuestions().isEmpty()) throw new IllegalArgumentException("Le questionnaire est vide");
        if (c.getParticipants().isEmpty()) throw new IllegalArgumentException("Aucun participant : définissez la population formée");
        if (c.getDateFin() == null) throw new IllegalArgumentException("Indiquez la date de clôture du questionnaire");
        if (c.getDateDebut() == null) c.setDateDebut(java.time.LocalDate.now());
        c.setStatut(EvalChaud.OUVERTE); c.setDateLancement(new Date()); c.setDateMaj(new Date());
        return c.getParticipants().stream().filter(u -> u.getStatut() == 1).toList();
    }

    public void cloturer(int id) {
        EvalChaud c = em.find(EvalChaud.class, id);
        if (c == null || !c.isOuverte()) throw new IllegalStateException("Seule une évaluation ouverte peut être clôturée");
        c.setStatut(EvalChaud.CLOTUREE); c.setDateCloture(new Date()); c.setDateMaj(new Date());
    }

    public void rouvrir(int id) {
        EvalChaud c = em.find(EvalChaud.class, id);
        if (c == null || !c.isCloturee()) throw new IllegalStateException("Seule une évaluation clôturée peut être rouverte");
        if (c.getDateFin() != null && c.getDateFin().isBefore(java.time.LocalDate.now())) c.setDateFin(java.time.LocalDate.now().plusDays(7));
        c.setStatut(EvalChaud.OUVERTE); c.setDateCloture(null); c.setDateMaj(new Date());
    }

    public void supprimer(int id) {
        EvalChaud c = em.find(EvalChaud.class, id);
        if (c == null) return;
        if (!c.isBrouillon()) throw new IllegalStateException("Seule une évaluation en brouillon peut être supprimée");
        em.remove(c);
    }

    // réponses

    /** Enregistre la réponse d'un participant (une seule par évaluation). Les clés sont les identifiants de question. */
    public void repondre(int id, Users participant, Map<Integer, Integer> notes, Map<Integer, String> textes) {
        EvalChaud c = charger(id);
        if (c == null) throw new IllegalArgumentException("Questionnaire introuvable");
        if (!c.isRepondable()) throw new IllegalStateException("Le questionnaire n'est pas ouvert aux réponses");
        if (!estParticipant(id, participant.getId())) throw new SecurityException("Vous ne faites pas partie des participants de cette formation");
        if (reponseDe(id, participant.getId()) != null) throw new IllegalStateException("Vous avez déjà répondu à ce questionnaire");
        EvalChaudReponse r = new EvalChaudReponse();
        r.setEvalChaud(c); r.setParticipant(em.getReference(Users.class, participant.getId()));
        List<String> manquantes = new ArrayList<>();
        Integer dernierOuiNon = null;
        String derniereListe = null;
        for (EvalChaudQuestion q : c.getQuestions()) {
            Integer n = q.isRedigee() ? null : notes.get(q.getId());
            String t = textes.get(q.getId());
            t = t == null || t.isBlank() ? null : t.trim();
            boolean visible = !q.isConditionnelle() || Integer.valueOf(0).equals(dernierOuiNon);
            if (q.isOuiNon()) dernierOuiNon = n;
            if (!visible) continue;   // question conditionnelle sans objet : rien n'est conservé
            if (q.isListe()) {
                List<String> l = lignes(t);
                if (l.size() > q.getMaxPoints()) l = l.subList(0, q.getMaxPoints());
                t = l.isEmpty() ? null : String.join("\n", l);
                derniereListe = t;
            }
            if (q.isNiveaux()) {
                // ne garde que les points cités à la question « liste » précédente
                Set<String> cites = new HashSet<>(lignes(derniereListe));
                List<String> l = new ArrayList<>();
                for (String x : lignes(t)) { String[] a = niveauLigne(x); if (cites.contains(a[0]) && a[1].matches("[1-5]")) l.add(a[0] + "\t" + a[1]); }
                if (cites.isEmpty()) { t = null; if (q.isObligatoire()) continue; }
                else if (l.size() < cites.size() && q.isObligatoire()) { manquantes.add(q.getLibelle()); continue; }
                t = l.isEmpty() ? null : String.join("\n", l);
            }
            if (q.isMulti() || q.isObjectifs()) {
                Set<String> permis = new HashSet<>(q.isObjectifs() ? c.getObjectifsListe() : q.getOptionsListe());
                List<String> l = new ArrayList<>();
                for (String x : lignes(t)) {
                    String[] ch = choix(x);
                    if (ch[0].equals("Autre") && x.startsWith(EvalChaudQuestion.AUTRE) && q.isAutre()) { if (!ch[1].isBlank()) l.add(EvalChaudQuestion.AUTRE + "\t" + ch[1].trim()); }
                    else if (permis.contains(x)) l.add(x);
                }
                t = l.isEmpty() ? null : String.join("\n", l);
                if (q.isObjectifs() && permis.isEmpty()) continue;
            }
            if (n != null && (q.isNote() ? n < 1 || n > 5 : n < 0 || n > 1)) n = null;
            if (q.isNote() || (q.isOuiNon() && (n == null || n != 1 || !q.isAvecPrecision()))) t = null;   // précision gardée seulement après un « oui »
            if (q.isObligatoire() && (q.isRedigee() ? t == null : n == null)) { manquantes.add(q.getLibelle()); continue; }
            if (n == null && t == null) continue;
            EvalChaudValeur v = new EvalChaudValeur();
            v.setReponse(r); v.setQuestion(q); v.setNote(n); v.setTexte(t);
            r.getValeurs().add(v);
        }
        if (!manquantes.isEmpty()) throw new IllegalArgumentException("Répondez aux questions obligatoires : " + String.join(" ; ", manquantes));
        em.persist(r);
    }

    // avis du supérieur hiérarchique (à froid)

    public EvalChaudReponse reponse(int reponseId) {
        List<EvalChaudReponse> l = em.createQuery("select distinct r from EvalChaudReponse r left join fetch r.valeurs where r.id = :r", EvalChaudReponse.class)
                .setParameter("r", reponseId).getResultList();
        if (l.isEmpty()) return null;
        EvalChaudReponse r = l.get(0);
        org.hibernate.Hibernate.initialize(r.getParticipant());
        if (r.getParticipant().getSuperieurHierarchique() != null) org.hibernate.Hibernate.initialize(r.getParticipant().getSuperieurHierarchique());
        if (r.getEvaluateur() != null) org.hibernate.Hibernate.initialize(r.getEvaluateur());
        return r;
    }

    /** Évaluateur attendu d'une réponse à froid : le supérieur hiérarchique du collaborateur. */
    public static boolean estEvaluateur(EvalChaudReponse r, Users u) {
        return u != null && r.getParticipant() != null && r.getParticipant().getSuperieurHierarchique() != null
                && r.getParticipant().getSuperieurHierarchique().getId() == u.getId();
    }

    /** Réponses à froid des collaborateurs directs d'un responsable (avis en attente d'abord). */
    public List<EvalChaudReponse> avisEquipe(int responsableId) {
        List<EvalChaudReponse> l = em.createQuery("""
                select r from EvalChaudReponse r join fetch r.evalChaud join fetch r.participant
                where r.evalChaud.nature = :f and r.participant.superieurHierarchique.id = :u order by r.dateReponse desc""", EvalChaudReponse.class)
                .setParameter("f", EvalChaud.FROID).setParameter("u", responsableId).getResultList();
        List<EvalChaudReponse> res = new ArrayList<>(l.stream().filter(r -> !r.isAvisDonne()).toList());
        res.addAll(l.stream().filter(EvalChaudReponse::isAvisDonne).toList());
        return res;
    }

    public void donnerAvis(int reponseId, Users evaluateur, Integer note, String commentaire) {
        EvalChaudReponse r = em.find(EvalChaudReponse.class, reponseId);
        if (r == null || !r.getEvalChaud().isFroid()) throw new IllegalArgumentException("Réponse introuvable");
        if (!estEvaluateur(r, evaluateur)) throw new SecurityException("Seul le supérieur hiérarchique du collaborateur peut donner l'avis");
        if (r.isAvisDonne()) throw new IllegalStateException("L'avis a déjà été donné et signé");
        if (note == null || note < 1 || note > 5) throw new IllegalArgumentException("Indiquez votre avis général sur l'efficacité de la formation (1 à 5)");
        if (commentaire == null || commentaire.isBlank()) throw new IllegalArgumentException("Le commentaire général de l'évaluateur est obligatoire");
        r.setAvisNote(note); r.setAvisCommentaire(commentaire.trim());
        r.setEvaluateur(em.getReference(Users.class, evaluateur.getId())); r.setDateAvis(new Date());
    }

    /** Suivi d'une évaluation à froid : réponses (collaborateur, N+1, avis). */
    public List<EvalChaudReponse> reponsesFroid(int id) {
        List<EvalChaudReponse> l = em.createQuery("select r from EvalChaudReponse r join fetch r.participant where r.evalChaud.id = :c order by r.participant.nom, r.participant.prenom", EvalChaudReponse.class)
                .setParameter("c", id).getResultList();
        for (EvalChaudReponse r : l) {
            if (r.getParticipant().getSuperieurHierarchique() != null) org.hibernate.Hibernate.initialize(r.getParticipant().getSuperieurHierarchique());
            if (r.getEvaluateur() != null) org.hibernate.Hibernate.initialize(r.getEvaluateur());
        }
        return l;
    }

    /** Synthèse des avis à froid : [nb avis, moyenne x10, taux de satisfaction moyen, nb efficaces]. */
    public record SyntheseAvis(long nbReponses, long nbAvis, Double moyenne, Integer tauxSatisfaction, long nbEfficaces) implements Serializable {
        public int getPctEfficace() { return nbAvis == 0 ? 0 : (int) Math.round(100.0 * nbEfficaces / nbAvis); }
        public long getNbAvis() { return nbAvis; }
        public long getNbReponses() { return nbReponses; }
        public Double getMoyenne() { return moyenne; }
        public Integer getTauxSatisfaction() { return tauxSatisfaction; }
        public long getNbEfficaces() { return nbEfficaces; }
        public boolean isEfficace() { return moyenne != null && moyenne >= 4; }
    }
    public static SyntheseAvis syntheseAvis(List<EvalChaudReponse> reps) {
        List<EvalChaudReponse> a = reps.stream().filter(EvalChaudReponse::isAvisDonne).toList();
        if (a.isEmpty()) return new SyntheseAvis(reps.size(), 0, null, null, 0);
        double m = a.stream().mapToInt(EvalChaudReponse::getAvisNote).average().orElse(0);
        int taux = (int) Math.round(a.stream().mapToInt(EvalChaudReponse::getTauxSatisfaction).average().orElse(0));
        return new SyntheseAvis(reps.size(), a.size(), Math.round(m * 10) / 10.0, taux, a.stream().filter(EvalChaudReponse::isEfficace).count());
    }

    // résultats

    /** Synthèse d'une question : moyenne et répartition des notes, oui/non, réponses libres, choix cochés. */
    public record Stat(EvalChaudQuestion question, long nb, Double moyenne, long[] repartition, long oui, long non, List<String> textes, List<Object[]> choix) implements Serializable {
        /** Choix cochés : [libellé, nombre, % des répondants]. */
        public List<Object[]> getChoix() { return choix; }
        public int pct(long v) { return nb == 0 ? 0 : (int) Math.round(100.0 * v / nb); }
        public int getPctOui() { long b = oui + non; return b == 0 ? 0 : (int) Math.round(100.0 * oui / b); }
        public int getPctMoyenne() { return moyenne == null ? 0 : (int) Math.round(moyenne / 5 * 100); }
        public long getNb() { return nb; }
        public Double getMoyenne() { return moyenne; }
        public long getOui() { return oui; }
        public long getNon() { return non; }
        public List<String> getTextes() { return textes; }
        public EvalChaudQuestion getQuestion() { return question; }
        public List<long[]> getNiveaux() { List<long[]> l = new ArrayList<>(); for (int i = 5; i >= 1; i--) l.add(new long[]{i, repartition[i]}); return l; }
        /** Échelle centrée : part des notes 3 (jugé adapté). */
        public int getPctAdapte() { return nb == 0 ? 0 : pct(repartition[3]); }
        /** Échelle centrée : part des notes 1-2 (côté borne basse) et 4-5 (côté borne haute). */
        public int getPctBas() { return nb == 0 ? 0 : pct(repartition[1] + repartition[2]); }
        public int getPctHaut() { return nb == 0 ? 0 : pct(repartition[4] + repartition[5]); }
        /** Plan d'actions : nombre d'actions par échéance (dans l'ordre des échéances du modèle). */
        public List<Object[]> getHorizons() {
            List<Object[]> l = new ArrayList<>(); long max = 1;
            for (int i = 0; i < EvalChaudQuestion.HORIZONS.length; i++) max = Math.max(max, repartition[i]);
            for (int i = 0; i < EvalChaudQuestion.HORIZONS.length; i++) l.add(new Object[]{EvalChaudQuestion.HORIZONS[i][1], repartition[i], (int) Math.round(100.0 * repartition[i] / max)});
            return l;
        }
    }

    public List<Stat> resultats(int id) {
        EvalChaud c = charger(id);
        if (c == null) return List.of();
        List<EvalChaudValeur> vals = em.createQuery("select v from EvalChaudValeur v join fetch v.question where v.reponse.evalChaud.id = :c order by v.reponse.dateReponse", EvalChaudValeur.class)
                .setParameter("c", id).getResultList();
        Map<Integer, List<EvalChaudValeur>> parQ = new HashMap<>();
        for (EvalChaudValeur v : vals) parQ.computeIfAbsent(v.getQuestion().getId(), k -> new ArrayList<>()).add(v);
        List<Stat> res = new ArrayList<>();
        for (EvalChaudQuestion q : c.getQuestions()) {
            List<EvalChaudValeur> l = parQ.getOrDefault(q.getId(), List.of());
            long[] rep = new long[6]; long oui = 0, non = 0, nb = 0; double somme = 0;
            List<String> textes = new ArrayList<>();
            long redigees = 0;
            for (EvalChaudValeur v : l) {
                if (v.getTexte() != null) {
                    redigees++;
                    if (q.isListe()) textes.addAll(lignes(v.getTexte()));
                    else if (q.isMulti() || q.isObjectifs()) { for (String x : lignes(v.getTexte())) { String[] ch = choix(x); if (!ch[1].isEmpty()) textes.add(ch[1]); } }
                    else if (q.isNiveaux()) for (String x : lignes(v.getTexte())) {
                        String[] a = niveauLigne(x);
                        if (a[1].matches("[1-5]")) { rep[Integer.parseInt(a[1])]++; textes.add(a[0] + " : " + q.niveau(Integer.parseInt(a[1]))); }
                    }
                    else if (q.isPlan()) for (String x : lignes(v.getTexte())) {
                        String[] a = action(x); int h = EvalChaudQuestion.horizonIndex(a[0]);
                        if (h >= 0) rep[h]++;
                        textes.add(EvalChaudQuestion.horizonLabel(a[0]) + " : " + a[1]);
                    }
                    else textes.add(v.getTexte());
                }
                if (v.getNote() == null) continue;
                nb++;
                if (q.isNote()) { rep[v.getNote()]++; somme += v.getNote(); }
                else if (q.isOuiNon()) { if (v.getNote() == 1) oui++; else non++; }
            }
            if (q.isRedigee()) nb = redigees;
            Double moy = q.isNote() && nb > 0 ? Math.round(somme / nb * 10) / 10.0 : null;
            res.add(new Stat(q, nb, moy, rep, oui, non, textes, choixStat(c, q, l, rep)));
        }
        return res;
    }

    /** Décompte des choix cochés (choix multiples, objectifs) ou des niveaux d'acquisition. */
    private static List<Object[]> choixStat(EvalChaud c, EvalChaudQuestion q, List<EvalChaudValeur> l, long[] rep) {
        List<Object[]> res = new ArrayList<>();
        if (q.isNiveaux()) {
            long tot = 0; for (int k = 1; k <= 5; k++) tot += rep[k];
            for (int k = 1; k <= 5; k++) res.add(new Object[]{q.niveau(k), rep[k], tot == 0 ? 0 : (int) Math.round(100.0 * rep[k] / tot)});
            return res;
        }
        if (!q.isMulti() && !q.isObjectifs()) return res;
        Map<String, Long> n = new LinkedHashMap<>();
        for (String o : q.isObjectifs() ? c.getObjectifsListe() : q.getOptionsListe()) n.put(o, 0L);
        if (q.isAutre()) n.put("Autre", 0L);
        long repondants = 0;
        for (EvalChaudValeur v : l) {
            if (v.getTexte() == null) continue;
            repondants++;
            for (String x : lignes(v.getTexte())) n.merge(choix(x)[0], 1L, Long::sum);
        }
        for (Map.Entry<String, Long> e : n.entrySet()) res.add(new Object[]{e.getKey(), e.getValue(), repondants == 0 ? 0 : (int) Math.round(100.0 * e.getValue() / repondants)});
        return res;
    }

    /** Satisfaction globale : moyenne des questions notées (sur 5). */
    public static Double satisfaction(List<Stat> stats) {
        double s = 0; long n = 0;
        for (Stat x : stats) if (x.moyenne() != null && x.question().isSatisfaction()) { s += x.moyenne() * x.nb(); n += x.nb(); }
        return n == 0 ? null : Math.round(s / n * 10) / 10.0;
    }

    // dépouillement (rapport analytique)

    /** En dessous de ce nombre de répondants, la satisfaction d'une direction n'est pas publiée (anonymat). */
    public static final int SEUIL_ANONYMAT = 3;

    public record QuestionAnalyse(Stat stat, Double mediane, Double ecartType, int pctFavorable, int pctNeutre, int pctDefavorable) implements Serializable { }
    public record DirectionAnalyse(String direction, long participants, long repondants, Double satisfaction, boolean masquee) implements Serializable {
        public int taux() { return participants == 0 ? 0 : (int) Math.round(100.0 * repondants / participants); }
    }
    public record Participant(String matricule, String nom, String direction, boolean repondu) implements Serializable { }
    public record Depouillement(EvalChaud evalChaud, long nbParticipants, long nbRepondants, Double satisfaction, Integer pctSatisfaits,
                                Integer pctRecommandation, List<QuestionAnalyse> questions, List<DirectionAnalyse> directions,
                                List<Participant> participants, List<Map<Integer, String>> brut) implements Serializable {
        public int taux() { return nbParticipants == 0 ? 0 : (int) Math.min(100, Math.round(100.0 * nbRepondants / nbParticipants)); }
        /** Questions notées classées de la mieux à la moins bien notée. */
        public List<QuestionAnalyse> classement() {
            return questions.stream().filter(q -> q.stat().moyenne() != null && q.stat().question().isSatisfaction())
                    .sorted(Comparator.comparing((QuestionAnalyse q) -> q.stat().moyenne()).reversed()).toList();
        }
    }

    public Depouillement depouillement(int id) {
        EvalChaud c = charger(id);
        if (c == null) return null;
        List<Stat> stats = resultats(id);
        List<EvalChaudReponse> reps = em.createQuery("select distinct r from EvalChaudReponse r left join fetch r.valeurs where r.evalChaud.id = :c", EvalChaudReponse.class)
                .setParameter("c", id).getResultList();
        Map<Integer, String> dirParReponse = new HashMap<>();
        for (Object[] o : em.createQuery("select r.id, d.nom from EvalChaudReponse r join r.participant p left join p.direction d where r.evalChaud.id = :c", Object[].class)
                .setParameter("c", id).getResultList())
            dirParReponse.put((Integer) o[0], o[1] == null ? "Sans direction" : (String) o[1]);
        Set<Integer> repondus = repondants(id);

        // notes par question
        Map<Integer, List<Integer>> notesParQ = new HashMap<>();
        for (EvalChaudReponse r : reps) for (EvalChaudValeur v : r.getValeurs())
            if (v.getNote() != null && v.getQuestion().isNote()) notesParQ.computeIfAbsent(v.getQuestion().getId(), k -> new ArrayList<>()).add(v.getNote());
        List<QuestionAnalyse> qa = new ArrayList<>();
        long favorables = 0, notees = 0, oui = 0, ouiNon = 0;
        for (Stat st : stats) {
            List<Integer> n = notesParQ.getOrDefault(st.question().getId(), List.of());
            Double med = null, et = null; int fav = 0, neu = 0, def = 0;
            if (!n.isEmpty()) {
                List<Integer> t = new ArrayList<>(n); Collections.sort(t);
                med = t.size() % 2 == 1 ? t.get(t.size() / 2) : (t.get(t.size() / 2 - 1) + t.get(t.size() / 2)) / 2.0;
                double m = t.stream().mapToInt(Integer::intValue).average().orElse(0);
                et = Math.round(Math.sqrt(t.stream().mapToDouble(x -> (x - m) * (x - m)).sum() / t.size()) * 100) / 100.0;
                if (st.question().isCentree()) {
                    // échelle centrée : favorable = 3 (adapté), défavorable = extrêmes 1 et 5
                    long f = t.stream().filter(x -> x == 3).count(), d = t.stream().filter(x -> x == 1 || x == 5).count();
                    fav = (int) Math.round(100.0 * f / t.size()); def = (int) Math.round(100.0 * d / t.size()); neu = 100 - fav - def;
                } else {
                    long f = t.stream().filter(x -> x >= 4).count(), d = t.stream().filter(x -> x <= 2).count();
                    fav = (int) Math.round(100.0 * f / t.size()); def = (int) Math.round(100.0 * d / t.size()); neu = 100 - fav - def;
                    favorables += f; notees += t.size();
                }
            }
            if (st.question().isOuiNon()) { oui += st.oui(); ouiNon += st.oui() + st.non(); }
            qa.add(new QuestionAnalyse(st, med, et, fav, neu, def));
        }

        // par direction
        Map<String, long[]> pop = new TreeMap<>();
        List<Participant> parts = new ArrayList<>();
        for (Users u : c.getParticipants()) {
            String dir = u.getDirection() == null ? "Sans direction" : u.getDirection().getNom();
            boolean rep = repondus.contains(u.getId());
            long[] a = pop.computeIfAbsent(dir, k -> new long[2]); a[0]++; if (rep) a[1]++;
            parts.add(new Participant(u.getMatricule(), u.getNomComplet(), dir, rep));
        }
        parts.sort(Comparator.comparing(Participant::direction).thenComparing(Participant::nom));
        Map<String, double[]> notesDir = new HashMap<>();
        for (EvalChaudReponse r : reps) {
            double[] a = notesDir.computeIfAbsent(dirParReponse.getOrDefault(r.getId(), "Sans direction"), k -> new double[2]);
            for (EvalChaudValeur v : r.getValeurs()) if (v.getNote() != null && v.getQuestion().isSatisfaction()) { a[0] += v.getNote(); a[1]++; }
        }
        List<DirectionAnalyse> dirs = new ArrayList<>();
        for (Map.Entry<String, long[]> e : pop.entrySet()) {
            double[] a = notesDir.get(e.getKey());
            boolean masque = e.getValue()[1] < SEUIL_ANONYMAT;
            Double sat = masque || a == null || a[1] == 0 ? null : Math.round(a[0] / a[1] * 10) / 10.0;
            dirs.add(new DirectionAnalyse(e.getKey(), e.getValue()[0], e.getValue()[1], sat, masque && e.getValue()[1] > 0));
        }

        // réponses brutes anonymisées : ordre mélangé pour ne pas trahir l'ordre d'arrivée
        List<Map<Integer, String>> brut = new ArrayList<>();
        for (EvalChaudReponse r : reps) {
            Map<Integer, String> m = new HashMap<>();
            for (EvalChaudValeur v : r.getValeurs()) {
                EvalChaudQuestion q = v.getQuestion();
                m.put(q.getId(), lisible(q, v.getNote(), v.getTexte()));
            }
            brut.add(m);
        }
        Collections.shuffle(brut);

        return new Depouillement(c, c.getParticipants().size(), repondus.size(), satisfaction(stats),
                notees == 0 ? null : (int) Math.round(100.0 * favorables / notees), ouiNon == 0 ? null : (int) Math.round(100.0 * oui / ouiNon),
                qa, dirs, parts, brut);
    }
}
