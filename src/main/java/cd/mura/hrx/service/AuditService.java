package cd.mura.hrx.service;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.dao.config.UserLogDAO;
import cd.mura.hrx.entity.config.UserLog;
import cd.mura.hrx.entity.config.Users;
import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Map;
import java.util.stream.Collectors;

@Named
@RequestScoped
@Slf4j
public class AuditService implements Serializable {

    @EJB
    private UserLogDAO userLogDAO;

    public void created(String module, String entityName, Object entityId, String summary, String details) {
        log("CREATE", module, entityName, entityId, summary, details, true);
    }

    public void updated(String module, String entityName, Object entityId, String summary, String details) {
        log("UPDATE", module, entityName, entityId, summary, details, true);
    }

    public void deleted(String module, String entityName, Object entityId, String summary, String details) {
        log("DELETE", module, entityName, entityId, summary, details, true);
    }

    public void security(String actionType, String summary, String details) {
        log(actionType, "Sécurité", "Users", null, summary, details, true);
    }

    public void loginSuccess(Users user, String mode) {
        logForUser(user, "LOGIN_SUCCESS", "Sécurité", "Users", user != null ? user.getId() : null,
                "Connexion réussie", "Mode de connexion : " + safe(mode), true);
    }

    public void loginFailure(String login, String reason) {
        UserLog logEntry = baseLog("LOGIN_FAILED", "Sécurité", "Users", null,
                "Échec de connexion", "Login saisi : " + safe(login) + "\nRaison : " + safe(reason), false);
        logEntry.setUserLogin(safe(login));
        persistQuietly(logEntry);
    }

    public void logout(Users user) {
        logForUser(user, "LOGOUT", "Sécurité", "Users", user != null ? user.getId() : null,
                "Déconnexion", "Fin de session utilisateur", true);
    }

    public void uiAction(String viewId, String source, Map<String, String> params) {
        String details = "Vue : " + safe(viewId)
                + "\nComposant : " + simplifyComponentId(source)
                + "\nChamps : " + sanitizeParams(params);
        log("UI_ACTION", "Interface", "JSF", source, "Action interface utilisateur", details, true);
    }

    public void log(String actionType, String module, String entityName, Object entityId,
                    String summary, String details, boolean success) {
        logForUser(currentUser(), actionType, module, entityName, entityId, summary, details, success);
    }

    public void logForUser(Users user, String actionType, String module, String entityName, Object entityId,
                           String summary, String details, boolean success) {
        UserLog logEntry = baseLog(actionType, module, entityName, entityId, summary, details, success);
        if (user != null && user.getId() > 0) {
            logEntry.setUser(user);
            logEntry.setUserLogin(user.getLogin());
            logEntry.setUserFullName((safe(user.getPrenom()) + " " + safe(user.getNom())).trim());
        }
        persistQuietly(logEntry);
    }

    private UserLog baseLog(String actionType, String module, String entityName, Object entityId,
                            String summary, String details, boolean success) {
        UserLog logEntry = new UserLog();
        logEntry.setActionType(limit(actionType, 40));
        logEntry.setModule(limit(module, 120));
        logEntry.setEntityName(limit(entityName, 120));
        logEntry.setEntityId(entityId == null ? null : limit(String.valueOf(entityId), 80));
        logEntry.setSummary(limit(summary, 255));
        logEntry.setDetails(limit(details, 8000));
        logEntry.setSuccess(success);
        enrichWithRequest(logEntry);
        return logEntry;
    }

    private void enrichWithRequest(UserLog logEntry) {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (facesContext == null) {
            return;
        }

        ExternalContext externalContext = facesContext.getExternalContext();
        Object requestObject = externalContext.getRequest();
        if (!(requestObject instanceof HttpServletRequest request)) {
            return;
        }

        logEntry.setIpAddress(limit(resolveIp(request), 80));
        logEntry.setHostname(limit(request.getRemoteHost(), 180));
        logEntry.setUserAgent(limit(request.getHeader("User-Agent"), 500));
        logEntry.setRequestUri(limit(request.getRequestURI(), 500));
        logEntry.setHttpMethod(limit(request.getMethod(), 12));
        logEntry.setSessionId(sessionFingerprint(request.getRequestedSessionId()));
    }

    private Users currentUser() {
        try {
            LoginBean injectedLoginBean = CDI.current().select(LoginBean.class).get();
            if (injectedLoginBean != null && injectedLoginBean.isLogged()) {
                return injectedLoginBean.getUsers();
            }
        } catch (Exception ignore) {
            // Fallback session map below.
        }

        try {
            FacesContext facesContext = FacesContext.getCurrentInstance();
            if (facesContext == null) {
                return null;
            }
            Object bean = facesContext.getExternalContext().getSessionMap().get("loginBean");
            if (bean instanceof LoginBean loginBean && loginBean.isLogged()) {
                return loginBean.getUsers();
            }
        } catch (Exception ignore) {
            return null;
        }
        return null;
    }

    private void persistQuietly(UserLog logEntry) {
        try {
            userLogDAO.persist(logEntry);
        } catch (Exception e) {
            log.warn("Impossible d'enregistrer le journal utilisateur", e);
        }
    }

    private String resolveIp(HttpServletRequest request) {
        // Les en-têtes de proxy ne sont fiables que si le frontal est explicitement
        // configuré pour les réécrire. Utiliser l'adresse observée par le conteneur.
        return request.getRemoteAddr();
    }

    private String sanitizeParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        return params.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .filter(e -> !e.getKey().contains("ViewState"))
                .filter(e -> !e.getKey().toLowerCase().contains("password"))
                .filter(e -> !e.getKey().toLowerCase().contains("pass"))
                .filter(e -> !e.getKey().toLowerCase().contains("token"))
                .filter(e -> !e.getKey().toLowerCase().contains("secret"))
                .filter(e -> !e.getKey().toLowerCase().contains("globalfilter"))
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .filter(e -> !e.getValue().startsWith("cd.mura.hrx."))
                .limit(10)
                // Le journal générique décrit les champs touchés, jamais leur contenu :
                // commentaires d'évaluation, motifs et données personnelles sont sensibles.
                .map(e -> simplifyComponentId(e.getKey()) + "=<modifié>")
                .collect(Collectors.joining("; "));
    }

    private String sessionFingerprint(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return null;
        }
    }

    private String simplifyComponentId(String value) {
        String v = safe(value);
        int lastColon = v.lastIndexOf(':');
        if (lastColon >= 0 && lastColon < v.length() - 1) {
            v = v.substring(lastColon + 1);
        }
        return limit(v, 80);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
