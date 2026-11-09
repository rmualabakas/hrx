package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.dao.config.UserPreferenceDAO;
import cd.mura.hrx.entity.config.App;
import cd.mura.hrx.entity.config.UserPreference;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.include.UpdatableBCrypt;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.LdapAccountStatusService;
import cd.mura.hrx.service.LoginAttemptService;
import cd.mura.hrx.utils.LdapUrl;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.*;
import java.io.Serializable;
import java.util.Date;
import java.util.Hashtable;
import java.util.function.Function;

@Data
@SessionScoped
@Named
@Slf4j
public class LoginBean implements Serializable {

    private static final UpdatableBCrypt bcrypt = new UpdatableBCrypt(11);
    public static final String REDIRECT_AFTER_LOGIN_KEY = "HRX_REDIRECT_AFTER_LOGIN";
    private static final String MSG_IDENTIFIANTS_INVALIDES = "Identifiant ou mot de passe incorrect.";
    /** Hash factice : le temps de réponse est le même que le compte existe ou non. */
    private static final String DUMMY_HASH = org.mindrot.jbcrypt.BCrypt.hashpw("hrx-dummy", org.mindrot.jbcrypt.BCrypt.gensalt(11));

    // Champs écran
    @NotNull(message = "Veuillez saisir votre mot de passe")
    private String pass;

    @NotNull(message = "Veuillez saisir votre adresse email")
    private String email; // sert aussi de login

    private boolean resteConnecte = false;

    private Date today;

    /**
     * 1 = Natif (DB) ; 2 = LDAP
     */
    private int modeConnexion = 2;

    // Etat session
    private Users users;
    private boolean isLogged;

    // Injections
    @EJB
    private UserDAO userDAO;

    @EJB
    private AppDAO appDAO;

    @EJB
    private UserPreferenceDAO userPreferenceDAO;

    @Inject
    private MenuView menuView;

    @Inject
    private AuditService auditService;

    @EJB
    private LdapAccountStatusService ldapAccountStatusService;

    @Inject
    private LoginAttemptService loginAttemptService;

    // BCrypt
    String[] mutableHash = new String[1];
    Function<String, Boolean> update = hash -> {
        mutableHash[0] = hash;
        return true;
    };

    private static boolean verifyAndUpdateHash(String password, String hash, Function<String, Boolean> updateFunc) {
        return bcrypt.verifyAndUpdateHash(password, hash, updateFunc);
    }

    @PostConstruct
    public void init() {
        users = new Users();
        isLogged = false;
        today = new Date();
        try {
            App app = appDAO.findFirst();
            if (app != null && app.getModeConnexionDefaut() != null) modeConnexion = app.getModeConnexionDefaut() == 1 ? 1 : 2;
        } catch (Exception ignored) { }
    }

    // Cadran du cycle (page de connexion)

    /** Reste de l'arc (pathLength 1000) : 1000 au 1er janvier, 0 au 31 décembre. */
    public int getCycleDash() {
        java.time.LocalDate now = java.time.LocalDate.now();
        double progress = (now.getDayOfYear() - 1) / (double) now.lengthOfYear();
        return (int) Math.round(1000 - progress * 1000);
    }

    /** Libellé du point du cycle, ex. « octobre 2026 ». */
    public String getCycleLabel() {
        java.time.LocalDate now = java.time.LocalDate.now();
        return now.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.FRENCH));
    }

    /** Phase du cycle selon la date : préparation, mi-parcours, fin d'année. */
    public String getCyclePhase() {
        int m = java.time.LocalDate.now().getMonthValue();
        if (m <= 4) return "Objectifs fixés, campagne de mi-parcours en préparation";
        if (m <= 7) return "Période de la campagne de mi-parcours";
        if (m <= 10) return "Suivi des plans d'action, fin d'année en préparation";
        return "Période de la campagne de fin d'année";
    }

    public String connexion() {
        try {
            return authentifier();
        } finally {
            // Le mot de passe ne doit pas survivre en session (sérialisation, dump mémoire).
            pass = null;
        }
    }

    private String authentifier() {
        try {
            if (email == null || email.isBlank() || pass == null || pass.isBlank()) {
                addMsg(FacesMessage.SEVERITY_WARN, "", "Veuillez renseigner le login et le mot de passe.");
                logAuthFailure("Login ou mot de passe vide");
                return "";
            }

            String clientIp = clientIp();
            if (loginAttemptService.isBlocked(email, clientIp)) {
                addMsg(FacesMessage.SEVERITY_ERROR, "Accès temporairement suspendu",
                        "Trop de tentatives ont échoué. Réessayez dans 15 minutes.");
                auditService.loginFailure(email, "Trop de tentatives de connexion");
                return "";
            }

            // 1) Auth selon mode
            if (modeConnexion == 2) {
                // LDAP AUTH
                App app = appDAO.findFirst();
                if (app == null) {
                    addMsg(FacesMessage.SEVERITY_ERROR, "", "Configuration application introuvable (LDAP non configuré).");
                    logAuthFailure("Configuration LDAP introuvable");
                    return "";
                }

                boolean ldapOk = authenticateAgainstLdap(app, email.trim(), pass);
                if (!ldapOk) {
                    // Sans mot de passe valide, on ne révèle jamais l'existence ni l'état du compte
                    // (énumération). Le motif précis reste dans le journal d'audit.
                    Users knownUser = userDAO.findUserByLoginEmail(email.trim());
                    LdapAccountStatusService.AccountStatus ldapStatus = knownUser == null ? null : ldapAccountStatusService.check(knownUser, true);
                    addMsg(FacesMessage.SEVERITY_ERROR, "", MSG_IDENTIFIANTS_INVALIDES);
                    logAuthFailure(ldapStatus != null && !ldapStatus.assignable() ? ldapStatus.detail() : "Mot de passe LDAP incorrect");
                    resetFailedAuthentication();
                    return "";
                }

                // LDAP OK -> on charge l'utilisateur dans la base native pour profil/menus
                Users dbUser = userDAO.findUserByLoginEmail(email.trim());
                if (dbUser == null || dbUser.getId() < 1) {
                    addMsg(FacesMessage.SEVERITY_ERROR, "", "Accès refusé : utilisateur non enrôlé dans la base applicative.");
                    logAuthFailure("Utilisateur LDAP non enrôlé dans la base applicative");
                    resetFailedAuthentication();
                    return "";
                }

                // statut
                if (dbUser.getStatut() == 0) {
                    addMsg(FacesMessage.SEVERITY_WARN, "", "Vos accès sont désactivés, veuillez contacter votre administrateur.");
                    auditService.loginFailure(email, "Compte désactivé");
                    resetFailedAuthentication();
                    return "";
                }

                LdapAccountStatusService.AccountStatus ldapStatus=ldapAccountStatusService.check(dbUser,true);
                if(!ldapStatus.assignable()){
                    addMsg(FacesMessage.SEVERITY_ERROR,"Compte LDAP indisponible",ldapStatus.detail()+". Contactez votre administrateur.");
                    auditService.loginFailure(email, ldapStatus.detail());
                    resetFailedAuthentication();
                    return "";
                }

                // success
                afterSuccessfulAuth(dbUser);
                return navigationAfterSuccessfulAuth();

            } else {
                // NATIF (DB)
                Users dbUser = userDAO.findUserByLoginEmail(email.trim());
                if (dbUser == null || dbUser.getId() < 1) {
                    org.mindrot.jbcrypt.BCrypt.checkpw(pass, DUMMY_HASH);
                    addMsg(FacesMessage.SEVERITY_ERROR, "", MSG_IDENTIFIANTS_INVALIDES);
                    logAuthFailure("Utilisateur introuvable");
                    resetFailedAuthentication();
                    return "";
                }

                mutableHash[0] = null;
                if (!verifyAndUpdateHash(pass, dbUser.getPassword(), update)) {
                    addMsg(FacesMessage.SEVERITY_ERROR, "", MSG_IDENTIFIANTS_INVALIDES);
                    logAuthFailure("Mot de passe natif incorrect");
                    resetFailedAuthentication();
                    return "";
                }
                // Si le coût BCrypt a évolué, conserver réellement le nouveau hash.
                // afterSuccessfulAuth() fusionne ensuite le compte dans la même opération de connexion.
                if (mutableHash[0] != null) {
                    dbUser.setPassword(mutableHash[0]);
                    mutableHash[0] = null;
                }

                if (dbUser.getStatut() == 0) {
                    addMsg(FacesMessage.SEVERITY_WARN, "", "Vos accès à cette application sont désactivés, veuillez contacter votre administrateur.");
                    auditService.loginFailure(email, "Compte désactivé");
                    resetFailedAuthentication();
                    return "";
                }

                afterSuccessfulAuth(dbUser);
                return navigationAfterSuccessfulAuth();
            }

        } catch (Exception e) {
            log.error("Erreur connexion()", e);
            addMsg(FacesMessage.SEVERITY_ERROR, "", "Erreur technique lors de la connexion.");
            logAuthFailure("Erreur technique lors de la connexion");
            resetFailedAuthentication();
            return "";
        }
    }

    private void resetFailedAuthentication() {
        loginAttemptService.failure(email, clientIp());
        isLogged = false;
        users = new Users();
    }

    private void afterSuccessfulAuth(Users dbUser) {
        loginAttemptService.success(email, clientIp());
        renewSessionId();
        // last_login
        dbUser.setLastLogin(new Date());
        userDAO.merge(dbUser);

        this.users = dbUser;

        // menus / privilèges (comme aujourd’hui)
        menuView.loadFor(users);

        isLogged = true;
        auditService.loginSuccess(dbUser, modeConnexion == 2 ? "LDAP" : "NATIF");
    }

    private String navigationAfterSuccessfulAuth() {
        String target = consumeRedirectAfterLogin();
        if (isSafeUiRedirectTarget(target)) {
            return appendFacesRedirect(target);
        }
        UserPreference preference = userPreferenceDAO.find(users.getId());
        if (preference != null && isSafeUiRedirectTarget(preference.getDefaultPage())) {
            return appendFacesRedirect(preference.getDefaultPage());
        }
        return "/ui/home?faces-redirect=true";
    }

    private String consumeRedirectAfterLogin() {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (facesContext == null) {
            return null;
        }

        Object rawTarget = facesContext.getExternalContext()
                .getSessionMap()
                .remove(REDIRECT_AFTER_LOGIN_KEY);

        if (!(rawTarget instanceof String target)) {
            return null;
        }

        String cleaned = target.trim();
        String ctx = facesContext.getExternalContext().getRequestContextPath();
        if (ctx != null && !ctx.isBlank() && cleaned.startsWith(ctx + "/")) {
            cleaned = cleaned.substring(ctx.length());
        }
        return cleaned;
    }

    private boolean isSafeUiRedirectTarget(String target) {
        return target != null
                && target.startsWith("/ui/")
                && !target.startsWith("//")
                && target.indexOf('\r') < 0
                && target.indexOf('\n') < 0;
    }

    private String appendFacesRedirect(String target) {
        String separator = target.contains("?") ? "&" : "?";
        return target + separator + "faces-redirect=true";
    }

    public String deconnection() {
        Users currentUser = users;
        boolean wasLogged = isLogged;
        isLogged = false;
        if (wasLogged && currentUser != null && currentUser.getId() > 0) {
            auditService.logout(currentUser);
        }
        try {
            ((HttpSession) FacesContext.getCurrentInstance().getExternalContext().getSession(true)).invalidate();
        } catch (Exception ignore) {
        }
        return "/login?faces-redirect=true";
    }

    /** Protection contre la fixation de session : nouvel identifiant dès l'authentification. */
    private void renewSessionId() {
        try {
            Object request = FacesContext.getCurrentInstance().getExternalContext().getRequest();
            if (request instanceof HttpServletRequest http && http.getSession(false) != null) {
                http.changeSessionId();
            }
        } catch (Exception e) {
            log.warn("Renouvellement de l'identifiant de session impossible", e);
        }
    }

    private void addMsg(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }

    private void logAuthFailure(String reason) {
        auditService.loginFailure(email, reason);
    }

    private String clientIp() {
        try {
            Object request = FacesContext.getCurrentInstance().getExternalContext().getRequest();
            if (request instanceof HttpServletRequest http) {
                // Ne pas faire confiance à X-Forwarded-For ici : il est falsifiable si le
                // frontal ne le réécrit pas. La clé contient aussi le login, donc un proxy
                // commun ne bloque pas les autres comptes.
                return http.getRemoteAddr();
            }
        } catch (Exception ignored) { }
        return "?";
    }

    // LDAP

    /** Authentifie uniquement (login/pass) dans LDAP */
    private boolean authenticateAgainstLdap(App app, String loginOrEmail, String password) {
        String server = safe(app.getLdapServerName());
        String port = safe(app.getLdapPort());
        String domain = safe(app.getLdapDomainName());
        String baseDn = safe(app.getLdapBaseDn());
        String bindUser = safe(app.getLdapBindUser());
        String bindPassword = safe(app.getLdapBindPassword());

        if (server.isBlank() || port.isBlank()) {
            log.warn("LDAP config incomplete: server/port missing");
            return false;
        }

        String url = LdapUrl.of(server, port);

        // 1) si baseDN + compte de service => search DN
        if (!baseDn.isBlank() && !bindUser.isBlank() && !bindPassword.isBlank()) {
            try {
                String userDn = findUserDnBySamAccountName(url, baseDn, bindUser, bindPassword, extractLogin(loginOrEmail));
                if (userDn == null || userDn.isBlank()) {
                    log.warn("LDAP user DN not found for {}", loginOrEmail);
                    return false;
                }
                return tryBind(url, userDn, password);
            } catch (Exception e) {
                log.error("LDAP search/bind error", e);
                return false;
            }
        }

        // 2) sinon bind direct (on tente plusieurs formats)
        String login = extractLogin(loginOrEmail);

        // a) user@domain
        if (!domain.isBlank()) {
            if (tryBind(url, login + "@" + domain, password)) return true;
            // b) DOMAIN\\user  (on prend le prefix avant le premier point comme "NETBIOS-like")
            String netbios = domain.contains(".") ? domain.substring(0, domain.indexOf('.')) : domain;
            if (tryBind(url, netbios + "\\" + login, password)) return true;
        }

        // c) login brut
        return tryBind(url, login, password);
    }

    private String extractLogin(String loginOrEmail) {
        String v = safe(loginOrEmail).trim();
        if (v.contains("@")) {
            return v.substring(0, v.indexOf('@'));
        }
        return v;
    }

    private boolean tryBind(String url, String principal, String password) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, password);

        // timeouts (évite blocage)
        env.put("com.sun.jndi.ldap.connect.timeout", "4000");
        env.put("com.sun.jndi.ldap.read.timeout", "4000");

        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            return true;
        } catch (NamingException ne) {
            log.info("LDAP bind failed for principal={}", principal);
            return false;
        } finally {
            if (ctx != null) {
                try { ctx.close(); } catch (Exception ignore) {}
            }
        }
    }

    private String findUserDnBySamAccountName(
            String url,
            String baseDn,
            String serviceUser,
            String servicePassword,
            String samAccountName
    ) throws NamingException {

        // bind service account
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, serviceUser);
        env.put(Context.SECURITY_CREDENTIALS, servicePassword);
        env.put("com.sun.jndi.ldap.connect.timeout", "4000");
        env.put("com.sun.jndi.ldap.read.timeout", "4000");

        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);

            // filtre AD classique (sAMAccountName)
            String filter = "(&(objectClass=user)(sAMAccountName=" + escapeLdapFilter(samAccountName) + "))";

            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            sc.setReturningAttributes(new String[]{"distinguishedName"});

            NamingEnumeration<SearchResult> results = ctx.search(baseDn, filter, sc);
            if (!results.hasMore()) return null;

            SearchResult sr = results.next();
            // DN complet
            return sr.getNameInNamespace();

        } finally {
            if (ctx != null) {
                try { ctx.close(); } catch (Exception ignore) {}
            }
        }
    }

    private String escapeLdapFilter(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\5c")
                .replace("*", "\\2a")
                .replace("(", "\\28")
                .replace(")", "\\29")
                .replace("\u0000", "\\00");
    }

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
