package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.Profil;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.include.UpdatableBCrypt;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.primefaces.event.SelectEvent;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.security.SecureRandom;

@Named
@ViewScoped
@Data
public class UserBean implements Serializable {

    // Constantes & utilitaires
    private static final long serialVersionUID = 1L;
    private static final int MIN_PWD_LEN = 8;
    private static final UpdatableBCrypt BCRYPT = new UpdatableBCrypt(11);
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789@#$%";

    private static String hash(String plain) {
        return BCRYPT.hash(plain);
    }

    // Injections
    @Inject
    private LoginBean loginBean;

    @Inject
    private AuditService auditService;

    @Inject
    private AuthorizationService authorization;

    @EJB
    private UserDAO userDAO;

    // Champs de saisie (UI)
    private String currentPassword;
    private String newPassword;
    private String confirmPassword;
    private Boolean passChange;

    // Modèle & listes
    private Users user;
    private Users usersSelected;
    private List<Users> usersList;
    private List<Users> usersListActif;
    private List<Users> usersFiltered;
    private List<Users> superieurOptions;

    @NotNull(message = "Veuillez choisir un profil.")
    private Profil profil;

    // Cycle de vie
    @PostConstruct
    private void init() {
        user = new Users();
        usersSelected = new Users();
        usersSelected.setPrenom("");
        usersSelected.setNom("");
        usersSelected.setEmail("");
        usersSelected.setLogin("");
        usersSelected.setPassword("");
        usersList = new ArrayList<>();
        usersListActif = new ArrayList<>();
        superieurOptions = new ArrayList<>();
        profil = new Profil();
        findAll();
        findAllActif();
    }

    // DAO wrappers
    private void findAll() {
        usersList = userDAO.findAll();
    }

    private void findAllActif() {
        usersListActif = userDAO.findAllActif();
        superieurOptions = usersListActif;
    }

    public void refreshLists() {
        findAll();
        findAllActif();
    }

    // Helpers messages
    private void info(String summary, String detail) {
        FacesContext.getCurrentInstance()
                .addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, summary, detail));
    }

    private void warn(String summary, String detail) {
        FacesContext.getCurrentInstance()
                .addMessage(null, new FacesMessage(FacesMessage.SEVERITY_WARN, summary, detail));
    }

    private void error(String summary, String detail) {
        FacesContext.getCurrentInstance()
                .addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, summary, detail));
    }

    // CRUD
    /**
     * ✅ CORRIGÉ : Validation AVANT de persister pour éviter les doublons
     */
    public void save() {
        authorization.requirePrivilege("USR_EDIT");
        // 1️⃣ Vérifier si l'email existe déjà
        Users existingByEmail = userDAO.findUserByLoginEmail(user.getEmail());
        if (existingByEmail != null) {
            error("❌ Email déjà utilisé",
                    "Un utilisateur avec l'email '" + user.getEmail() + "' existe déjà dans le système.");
            return;
        }

        // 2️⃣ Vérifier si le login existe déjà
        Users existingByLogin = userDAO.findUserByLoginEmail(user.getLogin());
        if (existingByLogin != null) {
            error("❌ Login déjà utilisé",
                    "Un utilisateur avec le login '" + user.getLogin() + "' existe déjà dans le système.");
            return;
        }

        // 3️⃣ Valider que le profil est sélectionné
        if (profil == null || profil.getId() <= 0) {
            error("❌ Profil requis", "Veuillez sélectionner un profil pour cet utilisateur.");
            return;
        }

        // 4️⃣ Persister l'utilisateur
        try {
            String temporaryPassword = temporaryPassword();
            user.setPassword(hash(temporaryPassword));
            user.setPassChange(1);
            user.setCreePar(loginBean.getUsers().getId());
            user.setProfil(profil);
            user.setDateCreation(new Date());
            user.setEditable(1);
            user.setStatut(1); // Actif par défaut

            Users saved = userDAO.persist(user);

            if (saved != null && saved.getId() > 0) {
                info("✅ Utilisateur créé",
                        "Le compte de " + user.getPrenom() + " " + user.getNom()
                                + " a été créé. Mot de passe initial : " + temporaryPassword
                                + " (à transmettre par un canal sûr)." );
                auditService.created("Administration", "Users", saved.getId(),
                        "Création utilisateur", userLabel(saved));

                // Réinitialiser le formulaire
                user = new Users();
                profil = new Profil();
                findAll();
                findAllActif();
            }
        } catch (Exception e) {
            // Si une erreur inattendue survient malgré les validations
            error("❌ Erreur lors de la création",
                    "Une erreur inattendue s'est produite. Veuillez réessayer.");
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
    }

    public Users find(int id) {
        return userDAO.findUserById(id);
    }

    public void remove(Users toDelete) {
        authorization.requirePrivilege("USR_EDIT");
        int userId = toDelete != null ? toDelete.getId() : 0;
        String label = userLabel(toDelete);
        if (userDAO.remove(toDelete) == 1) {
            info("Utilisateur supprimé", "Le compte a été supprimé.");
            auditService.deleted("Administration", "Users", userId,
                    "Suppression utilisateur", label);
            findAll();
            findAllActif();
        }
    }

    public void onRowSelect(SelectEvent<Users> event) {
        usersSelected = event.getObject();
    }

    /**
     * ✅ CORRIGÉ : Validation AVANT de modifier pour éviter les doublons
     */
    public void update() {
        authorization.requirePrivilege("USR_EDIT");
        // 1️⃣ Vérifier si l'email est déjà utilisé par un AUTRE utilisateur
        Users existingByEmail = userDAO.findUserByLoginEmail(usersSelected.getEmail());
        if (existingByEmail != null && existingByEmail.getId() != usersSelected.getId()) {
            error("❌ Email déjà utilisé",
                    "Un autre utilisateur utilise déjà l'email '" + usersSelected.getEmail() + "'.");
            return;
        }

        // 2️⃣ Vérifier si le login est déjà utilisé par un AUTRE utilisateur
        Users existingByLogin = userDAO.findUserByLoginEmail(usersSelected.getLogin());
        if (existingByLogin != null && existingByLogin.getId() != usersSelected.getId()) {
            error("❌ Login déjà utilisé",
                    "Un autre utilisateur utilise déjà le login '" + usersSelected.getLogin() + "'.");
            return;
        }

        // 3️⃣ Mettre à jour
        try {
            if (usersSelected.getSuperieurHierarchique() != null
                    && usersSelected.getSuperieurHierarchique().getId() == usersSelected.getId()) {
                usersSelected.setSuperieurHierarchique(null);
                warn("Supérieur ignoré", "Un utilisateur ne peut pas être son propre supérieur hiérarchique.");
            }
            usersSelected.setDateMaj(new Date());
            if (userDAO.merge(usersSelected) == 1) {
                info("✅ Mise à jour effectuée",
                        "Les informations de l'utilisateur ont été mises à jour.");
                auditService.updated("Administration", "Users", usersSelected.getId(),
                        "Mise à jour utilisateur", userLabel(usersSelected));
                findAll();
                findAllActif();
            }
        } catch (Exception e) {
            error("❌ Erreur lors de la modification",
                    "Une erreur s'est produite lors de la mise à jour.");
            java.util.logging.Logger.getLogger(getClass().getName()).log(java.util.logging.Level.SEVERE, "Operation impossible", e);
        }
    }

    public void changeStatus(int statut, Users u) {
        authorization.requirePrivilege("USR_EDIT");
        u.setStatut(statut);
        if (userDAO.merge(u) == 1) {
            info("Statut modifié", "Le statut de l'utilisateur a été mis à jour.");
            auditService.log("STATUS_CHANGE", "Administration", "Users", u.getId(),
                    "Statut utilisateur modifié",
                    userLabel(u) + " -> " + (statut == 1 ? "Actif" : "Inactif"), true);
            findAll();
            findAllActif();
        }
    }

    // Réinitialisation admin (dans la fiche d'un user)
    public void passReset() {
        authorization.requirePrivilege("USR_RESET");
        if (!validateNewPasswords(newPassword, confirmPassword)) return;

        usersSelected.setPassChange(Boolean.TRUE.equals(passChange) ? 1 : 0);
        usersSelected.setPassword(hash(newPassword));
        usersSelected.setDateMaj(new Date());

        if (userDAO.merge(usersSelected) == 1) {
            info("Mot de passe réinitialisé",
                    "Le mot de passe de l'utilisateur a été mis à jour.");
            auditService.security("PASSWORD_RESET", "Réinitialisation mot de passe",
                    "Utilisateur cible : " + userLabel(usersSelected));
            findAll();
        }
    }

    // Changement de mon propre mot de passe
    public String changeMyPassword() {
        final Users me = loginBean.getUsers();
        final String currentHash = me.getPassword();

        if (currentHash == null || currentHash.isBlank() || !isBcrypt(currentHash)) {
            error("Impossible de vérifier votre mot de passe",
                    "Le mot de passe enregistré pour votre compte est invalide ou non initialisé. " +
                            "Merci de demander une réinitialisation à un administrateur.");
            return "";
        }

        if (!BCRYPT.verifyAndUpdateHash(currentPassword, currentHash, h -> true)) {
            error("Mot de passe actuel incorrect",
                    "Le mot de passe actuel saisi est invalide.");
            return "";
        }

        if (!validateNewPasswords(newPassword, confirmPassword)) return "";

        if (BCRYPT.verifyAndUpdateHash(newPassword, currentHash, h -> true)) {
            warn("Aucun changement appliqué",
                    "Le nouveau mot de passe est identique à l'actuel.");
            return "";
        }

        me.setPassword(hash(newPassword));
        me.setDateMaj(new Date());
        me.setPassChange(0);

        if (userDAO.merge(me) == 1) {
            info("Mot de passe mis à jour",
                    "Votre mot de passe a été enregistré avec succès.");
            auditService.security("PASSWORD_CHANGE", "Changement de mot de passe",
                    "Mot de passe personnel mis à jour");
            findAll();
            return "/ui/home?faces-redirect=true";
        }
        return "";
    }

    private boolean isBcrypt(String h) {
        return h != null && (h.startsWith("$2a$") || h.startsWith("$2b$") || h.startsWith("$2y$"));
    }

    // Helpers validations
    private boolean validateNewPasswords(String pwd, String confirm) {
        String violation = cd.mura.hrx.include.PasswordPolicy.violation(pwd,
                loginBean.getUsers() == null ? null : loginBean.getUsers().getLogin());
        if (violation != null) {
            error("Mot de passe non conforme", violation);
            return false;
        }
        if (!safeEquals(pwd, confirm)) {
            error("Non concordance",
                    "Les deux mots de passe ne correspondent pas.");
            return false;
        }
        return true;
    }

    private boolean safeEquals(String a, String b) {
        return a != null && a.equals(b);
    }

    private String temporaryPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            value.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return value.toString();
    }

    // Filtre global (datatable)
    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String q = (filter == null) ? "" : filter.toString().trim().toLowerCase(locale);
        if (q.isEmpty()) return true;

        Users u = (Users) value;
        String haystack = String.join(" ",
                nullSafe(u.getNom()),
                nullSafe(u.getPrenom()),
                nullSafe(u.getEmail()),
                nullSafe(u.getLogin()),
                nullSafe(u.getPhone()),
                u.getDirection() != null ? nullSafe(u.getDirection().getNom()) : "",
                u.getProfil() != null ? nullSafe(u.getProfil().getNom()) : "",
                u.getSuperieurHierarchique() != null ? nullSafe(u.getSuperieurHierarchique().getPrenom()) + " " + nullSafe(u.getSuperieurHierarchique().getNom()) : "",
                u.getStatut() == 1 ? "actif" : "inactif"
        ).toLowerCase(locale);

        return haystack.contains(q);
    }

    private String nullSafe(String s) {
        return (s == null) ? "" : s;
    }

    private String userLabel(Users u) {
        if (u == null) {
            return "Utilisateur non renseigné";
        }
        String fullName = (nullSafe(u.getPrenom()) + " " + nullSafe(u.getNom())).trim();
        return fullName
                + " | login=" + nullSafe(u.getLogin())
                + " | email=" + nullSafe(u.getEmail())
                + " | profil=" + (u.getProfil() != null ? nullSafe(u.getProfil().getNom()) : "")
                + " | site=" + (u.getDirection() != null ? nullSafe(u.getDirection().getNom()) : "")
                + " | supérieur=" + (u.getSuperieurHierarchique() != null ? nullSafe(u.getSuperieurHierarchique().getLogin()) : "");
    }
}
