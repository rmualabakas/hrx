package cd.mura.hrx.service;

import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.dao.eval.ObjectifDAO;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.FicheObjectifs;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** État de la fixation des objectifs d'un exercice, et rappels aux N+1 et à la DCHT. */
@Stateless
public class SuiviObjectifsService {

    public static final String SANS_OBJECTIF = "SANS_OBJECTIF", EN_COURS = "EN_COURS", A_SIGNER = "A_SIGNER", SIGNEE = "SIGNEE";

    @EJB private UserDAO userDAO;
    @EJB private ObjectifDAO objectifDAO;
    @EJB private NotificationService notifications;

    /** Situation d'un collaborateur évaluable. */
    public record Ligne(Users salarie, Users n1, String direction, String abrege, String statut,
                        long valides, long proposes, boolean signeSalarie, boolean signeN1, boolean enRetard) implements Serializable {
        public boolean isSignee() { return SIGNEE.equals(statut); }
        public String statutLabel() {
            return switch (statut) {
                case SIGNEE -> "Fiche signée";
                case A_SIGNER -> signeSalarie || signeN1 ? "Une signature manquante" : "À signer";
                case EN_COURS -> proposes > 0 ? "Propositions à valider" : "En cours de fixation";
                default -> "Aucun objectif";
            };
        }
        public String statutClasse() {
            return switch (statut) {
                case SIGNEE -> "hx-badge--done";
                case A_SIGNER -> "hx-badge--info";
                case EN_COURS -> "hx-badge--pending";
                default -> enRetard ? "hx-badge--late" : "hx-badge--draft";
            };
        }
    }

    /** Totaux d'une direction (ou d'un périmètre). */
    public static class Agregat implements Serializable {
        private final String nom, abrege;
        long effectif, sansObjectif, enCours, aSigner, signees, retard, propositions;
        public Agregat(String nom, String abrege) { this.nom = nom; this.abrege = abrege; }
        void ajouter(Ligne l) {
            effectif++; propositions += l.proposes();
            switch (l.statut()) { case SIGNEE -> signees++; case A_SIGNER -> aSigner++; case EN_COURS -> enCours++; default -> sansObjectif++; }
            if (l.enRetard()) retard++;
        }
        public String getNom() { return nom; } public String getAbrege() { return abrege; }
        public long getEffectif() { return effectif; } public long getSansObjectif() { return sansObjectif; } public long getEnCours() { return enCours; }
        public long getAttenteSignature() { return aSigner; } public long getSignees() { return signees; } public long getRetard() { return retard; }
        public long getPropositions() { return propositions; } public long getRestants() { return effectif - signees; }
        public int getTaux() { return effectif == 0 ? 0 : (int) Math.round(100.0 * signees / effectif); }
        public int pct(long v) { return effectif == 0 ? 0 : (int) Math.round(100.0 * v / effectif); }
    }

    /** Collaborateurs évaluables actifs de l'exercice ; {@code perimetre} null = toute la banque. */
    public List<Ligne> etat(int exercice, Set<Integer> perimetre) {
        List<Users> users = userDAO.findActifsAvecDirection().stream()
                .filter(u -> u.getEvaluable() == 1 && (perimetre == null || perimetre.contains(u.getId()))).toList();
        Map<Integer, FicheObjectifs> fiches = new HashMap<>();
        if (!users.isEmpty())
            for (FicheObjectifs f : objectifDAO.findFiches(exercice, users.stream().map(Users::getId).toList())) fiches.put(f.getSalarie().getId(), f);
        List<Ligne> out = new ArrayList<>();
        for (Users u : users) {
            FicheObjectifs f = fiches.get(u.getId());
            long valides = f == null ? 0 : f.getNbValides(), proposes = f == null ? 0 : f.getNbProposes();
            String statut;
            if (f != null && f.isSignee()) statut = SIGNEE;
            else if (valides > 0 && proposes == 0) statut = A_SIGNER;
            else if (valides > 0 || proposes > 0) statut = EN_COURS;
            else statut = SANS_OBJECTIF;
            String dir = "Sans direction", ab = "";
            try { if (u.getDirection() != null) { dir = s(u.getDirection().getNom()); ab = s(u.getDirection().getAbrege()); } } catch (Exception ignored) { }
            out.add(new Ligne(u, u.getSuperieurHierarchique(), dir, ab, statut, valides, proposes,
                    f != null && f.isSigneeSalarie(), f != null && f.isSigneeN1(), !SIGNEE.equals(statut) && enRetard(u, exercice)));
        }
        out.sort(Comparator.comparing((Ligne l) -> s(l.salarie().getNom())).thenComparing(l -> s(l.salarie().getPrenom())));
        return out;
    }

    /** Fenêtre de fixation : fin janvier pour l'exercice, ou un mois après l'arrivée pour une recrue. */
    public static boolean enRetard(Users u, int exercice) {
        LocalDate effet = LocalDate.of(exercice, 1, 1);
        if (u.getDateEmbauche() != null) {
            LocalDate emb = u.getDateEmbauche().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (emb.getYear() == exercice) effet = emb;
        }
        return LocalDate.now().isAfter(effet.plusMonths(1));
    }

    public List<Agregat> parDirection(List<Ligne> lignes) {
        Map<String, Agregat> m = new LinkedHashMap<>();
        for (Ligne l : lignes) m.computeIfAbsent(l.direction(), k -> new Agregat(k, l.abrege())).ajouter(l);
        List<Agregat> out = new ArrayList<>(m.values());
        out.sort(Comparator.comparingInt(Agregat::getTaux).thenComparing(Agregat::getNom));
        return out;
    }

    public Agregat total(List<Ligne> lignes) {
        Agregat t = new Agregat("Total", "");
        lignes.forEach(t::ajouter);
        return t;
    }

    /** Fiches non signées regroupées par N+1 direct. */
    public Map<Users, List<Ligne>> aRelancerParN1(List<Ligne> lignes) {
        Map<Integer, Users> chefs = new HashMap<>();
        Map<Integer, List<Ligne>> m = new LinkedHashMap<>();
        for (Ligne l : lignes) {
            if (l.isSignee() || l.n1() == null) continue;
            chefs.putIfAbsent(l.n1().getId(), l.n1());
            m.computeIfAbsent(l.n1().getId(), k -> new ArrayList<>()).add(l);
        }
        Map<Users, List<Ligne>> out = new LinkedHashMap<>();
        m.forEach((id, l) -> out.put(chefs.get(id), l));
        return out;
    }

    /** Envoie un rappel à chaque N+1 concerné et une synthèse par direction à la DCHT. Retourne le nombre d'e-mails. */
    public int envoyerRappels(int exercice) {
        List<Ligne> lignes = etat(exercice, null);
        int n = 0;
        for (Map.Entry<Users, List<Ligne>> e : aRelancerParN1(lignes).entrySet()) {
            Users n1 = userDAO.findAccountById(e.getKey().getId());
            if (n1 == null || n1.getStatut() != 1) continue;
            notifications.rappelObjectifsManager(n1, e.getValue(), exercice); n++;
        }
        Agregat total = total(lignes);
        if (total.getRestants() > 0) {
            List<Agregat> dirs = parDirection(lignes);
            for (Users rh : notifications.destinatairesDcht()) { notifications.rappelObjectifsDcht(rh, dirs, total, exercice); n++; }
        }
        return n;
    }

    /** Rappel ciblé à un N+1 pour ses collaborateurs directs. */
    public boolean relancerN1(int n1Id, int exercice) {
        Users n1 = userDAO.findAccountById(n1Id);
        if (n1 == null) return false;
        List<Ligne> l = aRelancerParN1(etat(exercice, null)).entrySet().stream()
                .filter(e -> e.getKey().getId() == n1Id).map(Map.Entry::getValue).findFirst().orElse(List.of());
        if (l.isEmpty()) return false;
        notifications.rappelObjectifsManager(n1, l, exercice);
        return true;
    }

    private static String s(String v) { return v == null ? "" : v; }
}
