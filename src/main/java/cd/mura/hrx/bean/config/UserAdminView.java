package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.DirectionDAO;
import cd.mura.hrx.dao.config.ProfilDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.config.Profil;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.include.UpdatableBCrypt;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.LdapAccountStatusService;
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
import java.security.SecureRandom;
import java.util.*;

/** Écran « Utilisateurs » */
@Named
@ViewScoped
@Getter
@Setter
public class UserAdminView implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final UpdatableBCrypt BCRYPT = new UpdatableBCrypt(11);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789@#$%";

    @Inject private LoginBean loginBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private UserDAO userDAO;
    @EJB private ProfilDAO profilDAO;
    @EJB private DirectionDAO directionDAO;
    @EJB private LdapAccountStatusService ldapAccountStatusService;

    private List<Users> users = new ArrayList<>();
    private List<Profil> profils = new ArrayList<>();
    private List<Direction> directions = new ArrayList<>();

    /** Filtres. */
    private String recherche = "";
    private Integer filtreProfilId;
    private Integer filtreDirectionId;
    private String filtreStatut = "actifs"; // actifs | inactifs | tous

    /** Fiche en cours (création ou modification). */
    private Users edit = new Users();
    private Integer editProfilId;
    private Integer editDirectionId;
    private Integer editSuperieurId;
    private boolean editEvaluable = true;

    /** Réinitialisation de mot de passe. */
    private Users cible;
    private String nouveauMdp;
    private boolean forcerChangement = true;

    @PostConstruct
    public void init() {
        profils = profilDAO.findAllActive();
        directions = directionDAO.findAllActif();
        load();
    }

    public void load() { users = userDAO.findAll(); }

    // Liste

    public List<Users> getUsersFiltres() {
        String q = recherche == null ? "" : recherche.trim().toLowerCase(Locale.ROOT);
        return users.stream()
                .filter(u -> "tous".equals(filtreStatut) || ("inactifs".equals(filtreStatut) ? u.getStatut() == 0 : u.getStatut() == 1))
                .filter(u -> filtreProfilId == null || (u.getProfil() != null && u.getProfil().getId() == filtreProfilId))
                .filter(u -> filtreDirectionId == null || (u.getDirection() != null && u.getDirection().getId() == filtreDirectionId))
                .filter(u -> q.isEmpty() || contient(u, q))
                .toList();
    }

    private static boolean contient(Users u, String q) {
        return (s(u.getNom()) + " " + s(u.getPrenom()) + " " + s(u.getPostnom()) + " " + s(u.getLogin()) + " " + s(u.getEmail()) + " " + s(u.getMatricule()) + " " + s(u.getFonction()))
                .toLowerCase(Locale.ROOT).contains(q);
    }
    private static String s(String v) { return v == null ? "" : v; }

    public long getNbActifs() { return users.stream().filter(u -> u.getStatut() == 1).count(); }
    public long getNbInactifs() { return users.stream().filter(u -> u.getStatut() == 0).count(); }
    public long getNbEvaluables() { return users.stream().filter(u -> u.getStatut() == 1 && u.getEvaluable() == 1).count(); }

    public String initiales(Users u) {
        String p = s(u.getPrenom()), n = s(u.getNom());
        return ((p.isEmpty() ? "" : p.substring(0, 1)) + (n.isEmpty() ? "" : n.substring(0, 1))).toUpperCase(Locale.ROOT);
    }

    /** Candidats N+1 : actifs, hors l'utilisateur lui-même. */
    public List<Users> getSuperieursPossibles() {
        return users.stream().filter(u -> u.getStatut() == 1 && (edit == null || u.getId() != edit.getId())).toList();
    }

    // Fiche

    public void nouveau() {
        edit = new Users();
        editProfilId = profils.stream().filter(p -> Boolean.TRUE.equals(p.getProfilParDefaut())).map(Profil::getId).findFirst().orElse(null);
        editDirectionId = null; editSuperieurId = null; editEvaluable = true;
    }

    public void modifier(Users u) {
        edit = userDAO.findAccountById(u.getId());
        if (edit == null) edit = u;
        editProfilId = edit.getProfil() == null ? null : edit.getProfil().getId();
        editDirectionId = edit.getDirection() == null ? null : edit.getDirection().getId();
        editSuperieurId = edit.getSuperieurHierarchique() == null ? null : edit.getSuperieurHierarchique().getId();
        editEvaluable = edit.getEvaluable() == 1;
    }

    public void enregistrer() {
        authorization.requirePrivilege("USR_EDIT");
        boolean creation = edit.getId() == 0;
        edit.setNom(s(edit.getNom()).trim().toUpperCase(Locale.ROOT));
        edit.setPrenom(s(edit.getPrenom()).trim());
        edit.setPostnom(s(edit.getPostnom()).trim().isEmpty() ? null : s(edit.getPostnom()).trim());
        edit.setEmail(s(edit.getEmail()).trim().toLowerCase(Locale.ROOT));
        edit.setLogin(s(edit.getLogin()).trim().toLowerCase(Locale.ROOT));
        if (edit.getNom().isEmpty() || edit.getPrenom().isEmpty() || edit.getLogin().isEmpty() || edit.getEmail().isEmpty()) {
            error("Champs obligatoires", "Nom, prénom, identifiant et e-mail sont requis."); return;
        }
        if (editProfilId == null) { error("Profil requis", "Choisissez un profil."); return; }
        Users dbl = userDAO.findUserByLoginEmail(edit.getEmail());
        if (dbl != null && dbl.getId() != edit.getId()) { error("E-mail déjà utilisé", "Un autre compte utilise " + edit.getEmail()); return; }
        dbl = userDAO.findUserByLoginEmail(edit.getLogin());
        if (dbl != null && dbl.getId() != edit.getId()) { error("Identifiant déjà utilisé", "Un autre compte utilise " + edit.getLogin()); return; }
        if (editSuperieurId != null && editSuperieurId == edit.getId()) { error("N+1 invalide", "Un collaborateur ne peut pas être son propre supérieur."); return; }

        edit.setProfil(profilDAO.findById(editProfilId));
        edit.setDirection(editDirectionId == null ? null : directionDAO.find(editDirectionId));
        edit.setSuperieurHierarchique(editSuperieurId == null ? null : userDAO.getReferenceById(editSuperieurId));
        edit.setEvaluable(editEvaluable ? 1 : 0);
        edit.setDateMaj(new Date());

        String mdpInitial = null;
        boolean ok;
        if (creation) {
            mdpInitial = genererMotDePasse();
            edit.setPassword(BCRYPT.hash(mdpInitial));
            edit.setPassChange(1);
            edit.setStatut(1);
            edit.setEditable(1);
            edit.setCreePar(loginBean.getUsers().getId());
            edit.setDateCreation(new Date());
            Users saved = userDAO.persist(edit);
            ok = saved != null && saved.getId() > 0;
        } else {
            ok = userDAO.merge(edit) == 1;
        }
        if (ok) {
            auditService.log(creation ? "CREATE" : "UPDATE", "Administration", "Users", edit.getId(),
                    (creation ? "Création" : "Modification") + " du compte", edit.getLogin() + " - profil " + edit.getProfil().getNom(), true);
            if (creation) {
                info("Compte créé", edit.getPrenom() + " " + edit.getNom() + " - mot de passe initial : " + mdpInitial + " (à changer à la première connexion ; sans effet pour un compte réseau).");
            } else {
                info("Compte mis à jour", edit.getPrenom() + " " + edit.getNom());
            }
            load();
            edit = new Users();
        } else {
            error("Enregistrement impossible", "Vérifiez les informations saisies.");
        }
    }

    public void basculerStatut(Users u) {
        authorization.requirePrivilege("USR_EDIT");
        if (u.getId() == loginBean.getUsers().getId()) { error("Action refusée", "Vous ne pouvez pas désactiver votre propre compte."); return; }
        u.setStatut(u.getStatut() == 1 ? 0 : 1);
        u.setDateMaj(new Date());
        if (userDAO.merge(u) == 1) {
            auditService.log("STATUS_CHANGE", "Administration", "Users", u.getId(), u.getStatut() == 1 ? "Compte activé" : "Compte désactivé", u.getLogin(), true);
            info(u.getStatut() == 1 ? "Compte activé" : "Compte désactivé", u.getPrenom() + " " + u.getNom());
            load();
        }
    }

    public void supprimer(Users u) {
        authorization.requirePrivilege("USR_EDIT");
        if (u.getId() == loginBean.getUsers().getId()) { error("Action refusée", "Vous ne pouvez pas supprimer votre propre compte."); return; }
        try {
            if (userDAO.remove(u) == 1) {
                auditService.deleted("Administration", "Users", u.getId(), "Suppression du compte", u.getLogin());
                info("Compte supprimé", u.getPrenom() + " " + u.getNom());
                load();
            } else {
                error("Suppression impossible", "Ce compte est référencé (évaluations, journal, hiérarchie). Désactivez-le plutôt.");
            }
        } catch (Exception e) {
            error("Suppression impossible", "Ce compte est référencé ailleurs. Désactivez-le plutôt.");
        }
    }

    // Mot de passe

    public void preparerReset(Users u) {
        cible = u;
        nouveauMdp = genererMotDePasse();
        forcerChangement = true;
    }

    public void regenerer() { nouveauMdp = genererMotDePasse(); }

    public void reinitialiser() {
        authorization.requirePrivilege("USR_RESET");
        if (cible == null) return;
        if (nouveauMdp == null || nouveauMdp.length() < 8) { error("Mot de passe trop court", "8 caractères minimum."); return; }
        cible.setPassword(BCRYPT.hash(nouveauMdp));
        cible.setPassChange(forcerChangement ? 1 : 0);
        cible.setDateMaj(new Date());
        if (userDAO.merge(cible) == 1) {
            auditService.security("PASSWORD_RESET", "Réinitialisation du mot de passe", "Compte : " + cible.getLogin());
            info("Mot de passe réinitialisé", "Communiquez le mot de passe à " + cible.getPrenom() + " " + cible.getNom() + " par un canal sûr.");
            load();
        }
    }

    private static String genererMotDePasse() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    // Helpers

    public String etatLdap(Users u) {
        try {
            LdapAccountStatusService.AccountStatus st = ldapAccountStatusService.check(u);
            return switch (st.state()) {
                case ACTIVE -> "Actif dans l'annuaire";
                case DISABLED -> "Désactivé dans l'annuaire";
                case NOT_FOUND -> "Absent de l'annuaire";
                default -> "";
            };
        } catch (Exception e) { return ""; }
    }

    private void info(String summary, String detail) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, summary, detail)); }
    private void error(String summary, String detail) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, summary, detail)); }
}
