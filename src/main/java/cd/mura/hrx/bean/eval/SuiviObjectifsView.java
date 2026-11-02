package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.bean.config.PrivilegeBean;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.SuiviObjectifsService;
import cd.mura.hrx.service.SuiviObjectifsService.Agregat;
import cd.mura.hrx.service.SuiviObjectifsService.Ligne;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.Year;
import java.util.*;

/** Suivi de la fixation des objectifs : toute la banque pour la DCHT, son périmètre pour un manager. */
@Named
@ViewScoped
@Getter
@Setter
public class SuiviObjectifsView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private PrivilegeBean privilegeBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private UserDAO userDAO;
    @EJB private SuiviObjectifsService service;

    private int exercice = Year.now().getValue();
    private boolean dcht;
    private List<Ligne> lignes = new ArrayList<>();
    private List<Agregat> directions = new ArrayList<>();
    private Agregat total;
    private String onglet = "aFaire";      // aFaire | retard | aSigner | signees | tous
    private String filtreDirection = "";
    private String recherche = "";

    @PostConstruct
    public void init() {
        Users moi = loginBean.getUsers();
        dcht = (moi.getProfil() != null && moi.getProfil().getId() == 1)
                || privilegeBean.hasPrivilege("EVAL_DCHT") || privilegeBean.hasPrivilege("CAMP_CREATE") || privilegeBean.hasPrivilege("OBJ_SUIVI");
        charger();
    }

    public void charger() {
        Set<Integer> perimetre = null;
        if (!dcht) {
            perimetre = new HashSet<>();
            collecter(loginBean.getUsers().getId(), perimetre, new HashSet<>());
        }
        lignes = service.etat(exercice, perimetre);
        directions = service.parDirection(lignes);
        total = service.total(lignes);
    }

    private void collecter(int chefId, Set<Integer> out, Set<Integer> vus) {
        if (!vus.add(chefId)) return;
        for (Users u : userDAO.findBySuperieur(chefId)) { out.add(u.getId()); collecter(u.getId(), out, vus); }
    }

    public List<Integer> getExercices() { int y = Year.now().getValue(); return List.of(y + 1, y, y - 1); }
    public void changerExercice() { charger(); }

    public List<Ligne> getLignesFiltrees() {
        String q = recherche == null ? "" : recherche.trim().toLowerCase(Locale.ROOT);
        return lignes.stream().filter(l -> {
            if (!filtreDirection.isEmpty() && !filtreDirection.equals(l.direction())) return false;
            if (!q.isEmpty()) {
                String h = (s(l.salarie().getNomComplet()) + " " + s(l.salarie().getMatricule()) + " " + s(l.salarie().getFonction()) + " " + (l.n1() == null ? "" : s(l.n1().getNomComplet()))).toLowerCase(Locale.ROOT);
                if (!h.contains(q)) return false;
            }
            return switch (onglet) {
                case "retard" -> l.enRetard();
                case "aSigner" -> SuiviObjectifsService.A_SIGNER.equals(l.statut());
                case "signees" -> l.isSignee();
                case "tous" -> true;
                default -> !l.isSignee();
            };
        }).toList();
    }

    public List<String> getNomsDirections() { return directions.stream().map(Agregat::getNom).sorted().toList(); }
    public long compter(String o) {
        return switch (o) {
            case "retard" -> lignes.stream().filter(Ligne::enRetard).count();
            case "aSigner" -> lignes.stream().filter(l -> SuiviObjectifsService.A_SIGNER.equals(l.statut())).count();
            case "signees" -> lignes.stream().filter(Ligne::isSignee).count();
            case "tous" -> lignes.size();
            default -> lignes.stream().filter(l -> !l.isSignee()).count();
        };
    }
    /** Managers ayant au moins un collaborateur direct sans fiche signée. */
    public long getNbManagersConcernes() { return service.aRelancerParN1(lignes).size(); }

    public void relancer(Ligne l) {
        authorization.requirePrivilege("OBJ_SUIVI");
        if (!dcht || l.n1() == null) return;
        if (service.relancerN1(l.n1().getId(), exercice)) {
            auditService.log("NOTIFICATION", "Objectifs", "Users", l.n1().getId(), "Rappel de fixation des objectifs", "N+1 : " + l.n1().getNomComplet() + " · exercice " + exercice, true);
            info("Rappel envoyé", "À " + l.n1().getNomComplet() + ", pour tous ses collaborateurs sans fiche signée.");
        } else info("Rien à rappeler", "Toutes les fiches de ce N+1 sont signées.");
    }

    public void envoyerRappels() {
        authorization.requirePrivilege("OBJ_SUIVI");
        if (!dcht) return;
        int n = service.envoyerRappels(exercice);
        auditService.log("NOTIFICATION", "Objectifs", "FicheObjectifs", null, "Rappels de fixation des objectifs envoyés", n + " e-mail(s) · exercice " + exercice, true);
        info(n == 0 ? "Aucun rappel à envoyer" : n + " rappel" + (n > 1 ? "s" : "") + " envoyé" + (n > 1 ? "s" : ""), n == 0 ? "Toutes les fiches sont signées." : "Les N+1 concernés et la DCHT ont été prévenus.");
    }

    public String initiales(Users u) {
        String p = s(u.getPrenom()), n = s(u.getNom());
        return ((p.isEmpty() ? "" : p.substring(0, 1)) + (n.isEmpty() ? "" : n.substring(0, 1))).toUpperCase(Locale.ROOT);
    }
    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private static String s(String v) { return v == null ? "" : v; }
}
