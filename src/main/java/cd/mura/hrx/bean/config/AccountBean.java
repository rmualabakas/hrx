package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.dao.config.UserPreferenceDAO;
import cd.mura.hrx.entity.config.UserPreference;
import cd.mura.hrx.entity.config.UserTheme;
import cd.mura.hrx.entity.config.MenuOrientation;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.include.UpdatableBCrypt;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.LdapAccountStatusService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Named
@SessionScoped
public class AccountBean implements Serializable {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9+_.-]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> ALLOWED_START_PAGES = Set.of("/ui/home", "/ui/monCompte");
    private static final Set<String> HR_MODULE_VIEWS = Set.of(
            "campagnes", "evaluations", "mesevaluations", "equipe", "modelesfiche", "reporting"
    );
    private static final UpdatableBCrypt BCRYPT = new UpdatableBCrypt(11);

    @Inject
    private LoginBean loginBean;

    @Inject
    private AuditService auditService;

    @EJB
    private UserDAO userDAO;

    @EJB
    private UserPreferenceDAO preferenceDAO;
    @EJB private LdapAccountStatusService ldapAccountStatusService;

    private Users profile;
    private UserPreference preferences;
    private int activeTab;
    private String currentPassword;
    private String newPassword;
    private String confirmPassword;
    private LdapAccountStatusService.AccountStatus ldapAccountStatus;

    @PostConstruct
    public void init() {
        reload();
    }

    public void reload() {
        Users logged = loginBean.getUsers();
        if (logged == null || logged.getId() <= 0) return;
        profile = userDAO.findAccountById(logged.getId());
        preferences = preferenceDAO.find(logged.getId());
        if (preferences == null) {
            preferences = new UserPreference();
            preferences.setUserId(logged.getId());
        }
        preferences.setThemeCode(UserTheme.fromCode(preferences.getThemeCode()).name());
        preferences.setMenuOrientation(MenuOrientation.fromCode(preferences.getMenuOrientation()).name());
        ldapAccountStatus=ldapAccountStatusService.check(profile);
    }

    public String openProfile() {
        activeTab = 0;
        reload();
        return "/ui/monCompte?faces-redirect=true";
    }

    public String openSettings() {
        activeTab = 1;
        reload();
        return "/ui/monCompte?faces-redirect=true";
    }

    public void saveProfile() {
        if (profile == null || loginBean.getUsers() == null
                || profile.getId() != loginBean.getUsers().getId()) {
            error("Session invalide", "Reconnectez-vous avant de modifier votre profil.");
            return;
        }
        // Seul le téléphone est modifiable ici : l'identité (nom, prénom, post-nom, e-mail) est tenue par la DCHT.
        // On repart de la fiche en base pour ignorer toute valeur d'identité envoyée par le navigateur.
        String telephone = clean(profile.getPhone());
        profile = userDAO.findAccountById(profile.getId());
        if (profile == null) { error("Session invalide", "Reconnectez-vous avant de modifier votre profil."); return; }
        profile.setPhone(telephone.isBlank() ? null : telephone);

        profile.setDateMaj(new Date());
        if (userDAO.merge(profile) == 1) {
            profile = userDAO.findAccountById(profile.getId());
            loginBean.setUsers(profile);
            auditService.updated("Compte", "Users", profile.getId(),
                    "Mise à jour du téléphone", profile.getLogin());
            info("Téléphone enregistré", "");
        } else {
            error("Échec de l’enregistrement", "Les informations n’ont pas pu être enregistrées.");
        }
    }

    public void savePreferences() {
        if (preferences == null || loginBean.getUsers() == null) return;
        preferences.setUserId(loginBean.getUsers().getId());
        if (!ALLOWED_START_PAGES.contains(preferences.getDefaultPage())) {
            preferences.setDefaultPage("/ui/home");
        }
        UserTheme selectedTheme = UserTheme.fromCode(preferences.getThemeCode());
        preferences.setThemeCode(selectedTheme.name());
        MenuOrientation selectedOrientation = MenuOrientation.fromCode(preferences.getMenuOrientation());
        preferences.setMenuOrientation(selectedOrientation.name());
        preferences = preferenceDAO.save(preferences);
        auditService.updated("Compte", "UserPreference", preferences.getUserId(),
                "Mise à jour des préférences personnelles",
                "compact=" + preferences.isCompactMode()
                        + ", notifications=" + preferences.isSessionNotifications()
                        + ", accueil=" + preferences.getDefaultPage()
                        + ", theme=" + selectedTheme.name()
                        + ", menu=" + selectedOrientation.name());
        info("Préférences enregistrées", "Le thème « " + selectedTheme.getLabel()
                + " » et le menu « " + selectedOrientation.getLabel()
                + " » sont appliqués et seront conservés pour vos prochaines connexions.");
    }

    public void changePassword() {
        if (!isNativeAccount()) {
            error("Compte d’annuaire", "Le mot de passe doit être modifié dans l’annuaire de votre organisation.");
            return;
        }
        Users me = userDAO.findUserById(loginBean.getUsers().getId());
        if (me == null || !validHash(me.getPassword())
                || currentPassword == null || !BCRYPT.verifyHash(currentPassword, me.getPassword())) {
            error("Mot de passe incorrect", "Le mot de passe actuel ne correspond pas.");
            return;
        }
        String violation = cd.mura.hrx.include.PasswordPolicy.violation(newPassword, me.getLogin());
        if (violation != null) {
            error("Mot de passe non conforme", violation);
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            error("Confirmation différente", "Les deux nouveaux mots de passe ne correspondent pas.");
            return;
        }
        if (BCRYPT.verifyHash(newPassword, me.getPassword())) {
            error("Mot de passe inchangé", "Choisissez un mot de passe différent de l’actuel.");
            return;
        }
        me.setPassword(BCRYPT.hash(newPassword));
        me.setPassChange(0);
        me.setDateMaj(new Date());
        if (userDAO.merge(me) == 1) {
            loginBean.getUsers().setPassword(me.getPassword());
            currentPassword = null;
            newPassword = null;
            confirmPassword = null;
            auditService.security("PASSWORD_CHANGE", "Changement de mot de passe",
                    "Mot de passe personnel mis à jour depuis Mon compte");
            info("Mot de passe modifié", "Votre nouveau mot de passe est actif.");
        }
    }

    public void refreshLdapStatus(){ldapAccountStatus=ldapAccountStatusService.check(profile,true);if(ldapAccountStatus.assignable())info("État de l’annuaire",ldapAccountStatus.detail());else error("Alerte annuaire",ldapAccountStatus.detail());}
    public String getLdapStatusLabel(){if(ldapAccountStatus==null)return "Non vérifié";return switch(ldapAccountStatus.state()){case ACTIVE->"Actif dans l’annuaire";case DISABLED->"Désactivé dans l’annuaire";case NOT_FOUND->"Absent de l’annuaire";case UNAVAILABLE->"Annuaire injoignable";case NOT_CONFIGURED->"Contrôle non configuré";};}
    public String getLdapStatusClass(){return ldapAccountStatus!=null&&ldapAccountStatus.assignable()?"ldap-ok":"ldap-alert";}
    public String getLdapStatusDetail(){return ldapAccountStatus==null?"État de l’annuaire non vérifié":ldapAccountStatus.detail();}

    public boolean isNativeAccount() {
        return loginBean.getModeConnexion() == 1;
    }

    public String getBodyPreferenceClass() {
        UserTheme theme = UserTheme.fromCode(preferences == null ? null : preferences.getThemeCode());
        MenuOrientation orientation = MenuOrientation.fromCode(preferences == null ? null : preferences.getMenuOrientation());
        String density = preferences != null && preferences.isCompactMode() ? " hrx-density-compact" : "";
        return theme.getBodyClass() + " " + orientation.getBodyClass() + density + currentModuleClass();
    }

    private String currentModuleClass() {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null || context.getViewRoot() == null) return "";
        String viewId = context.getViewRoot().getViewId();
        if (viewId == null || viewId.isBlank()) return "";
        int slash = viewId.lastIndexOf('/');
        String view = (slash >= 0 ? viewId.substring(slash + 1) : viewId)
                .replaceFirst("\\.xhtml$", "")
                .toLowerCase();
        return HR_MODULE_VIEWS.contains(view) ? " hrx-module-evaluation" : "";
    }

    public String getLayoutDirection() {
        return MenuOrientation.fromCode(preferences == null ? null : preferences.getMenuOrientation()).isRtl()
                ? "rtl" : "ltr";
    }

    public boolean isSessionNotificationsEnabled() {
        return preferences == null || preferences.isSessionNotifications();
    }

    public UserTheme[] getThemes() { return UserTheme.values(); }
    public String getInitiales() {
        if (profile == null) return "";
        String p = profile.getPrenom() == null || profile.getPrenom().isEmpty() ? "" : profile.getPrenom().substring(0, 1);
        String n = profile.getNom() == null || profile.getNom().isEmpty() ? "" : profile.getNom().substring(0, 1);
        return (p + n).toUpperCase();
    }
    public Users getProfile() { return profile; }
    public UserPreference getPreferences() { return preferences; }
    public int getActiveTab() { return activeTab; }
    public void setActiveTab(int activeTab) { this.activeTab = Math.max(0, Math.min(2, activeTab)); }
    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String value) { currentPassword = value; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String value) { newPassword = value; }
    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String value) { confirmPassword = value; }

    private boolean validHash(String value) {
        return value != null && (value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$"));
    }

    private String clean(String value) { return value == null ? "" : value.trim(); }
    private String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String detail = root.getMessage();
        return detail == null || detail.isBlank()
                ? "Une erreur technique est survenue. Réessayez ou contactez un administrateur."
                : detail;
    }
    private void info(String summary, String detail) { message(FacesMessage.SEVERITY_INFO, summary, detail); }
    private void error(String summary, String detail) { message(FacesMessage.SEVERITY_ERROR, summary, detail); }
    private void message(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }
}
