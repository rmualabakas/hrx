package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.UserLogDAO;
import cd.mura.hrx.entity.config.UserLog;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Named
@ViewScoped
@Getter
@Setter
public class UserLogBean implements Serializable {

    @EJB
    private UserLogDAO userLogDAO;

    private List<UserLog> logs;
    private List<UserLog> filteredLogs;
    private Date dateDebut;
    private Date dateFin;
    private String actionType;
    private String query;

    private final String[] actionTypes = {
            "LOGIN_SUCCESS", "LOGIN_FAILED", "LOGOUT", "UI_ACTION",
            "CREATE", "UPDATE", "DELETE", "STATUS_CHANGE", "PASSWORD_RESET",
            "PASSWORD_CHANGE", "LDAP_IMPORT", "NOTIFICATION", "EXPORT"
    };

    @PostConstruct
    public void init() {
        Calendar calendar = Calendar.getInstance();
        dateFin = endOfDay(calendar.getTime());
        calendar.add(Calendar.DAY_OF_MONTH, -7);
        dateDebut = startOfDay(calendar.getTime());
        search();
    }

    public void search() {
        logs = userLogDAO.search(startOfDay(dateDebut), endOfDay(dateFin), actionType, query, 1000);
    }

    public void reset() {
        actionType = null;
        query = null;
        init();
    }

    public String dateLabel(UserLog log) {
        if (log == null || log.getActionAt() == null) return "";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        f.setTimeZone(java.util.TimeZone.getTimeZone("Africa/Kinshasa"));
        return f.format(log.getActionAt());
    }

    public String actionLabel(UserLog log) {
        return log == null ? "Action non renseignée" : actionTypeLabel(log.getActionType());
    }

    public String actionTypeLabel(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return "Action non renseignée";
        }
        return switch (actionType) {
            case "LOGIN_SUCCESS" -> "Connexion réussie";
            case "LOGIN_FAILED" -> "Échec de connexion";
            case "LOGOUT" -> "Déconnexion";
            case "UI_ACTION" -> "Action dans l'application";
            case "CREATE" -> "Création";
            case "UPDATE" -> "Modification";
            case "DELETE" -> "Suppression";
            case "STATUS_CHANGE" -> "Changement de statut";
            case "PASSWORD_RESET" -> "Réinitialisation du mot de passe";
            case "PASSWORD_CHANGE" -> "Changement du mot de passe";
            case "LDAP_IMPORT" -> "Import depuis l'annuaire LDAP";
            case "NOTIFICATION" -> "Notification e-mail";
            case "EXPORT" -> "Export de document";
            default -> humanize(actionType);
        };
    }

    public String actionIcon(UserLog log) {
        if (log == null || !log.isSuccess()) {
            return "pi pi-times-circle";
        }
        return switch (safe(log.getActionType())) {
            case "LOGIN_SUCCESS" -> "pi pi-sign-in";
            case "LOGIN_FAILED" -> "pi pi-lock";
            case "LOGOUT" -> "pi pi-sign-out";
            case "CREATE" -> "pi pi-plus-circle";
            case "UPDATE", "STATUS_CHANGE" -> "pi pi-pencil";
            case "DELETE" -> "pi pi-trash";
            case "PASSWORD_RESET", "PASSWORD_CHANGE" -> "pi pi-key";
            case "LDAP_IMPORT" -> "pi pi-download";
            case "NOTIFICATION" -> "pi pi-envelope";
            case "EXPORT" -> "pi pi-file-export";
            default -> "pi pi-check-circle";
        };
    }

    public String actionTone(UserLog log) {
        if (log == null || !log.isSuccess()) {
            return "danger";
        }
        return switch (safe(log.getActionType())) {
            case "DELETE" -> "warning";
            case "CREATE", "LOGIN_SUCCESS" -> "success";
            default -> "info";
        };
    }

    public String eventDescription(UserLog log) {
        if (log == null) {
            return "Aucune description disponible";
        }
        String summary = safe(log.getSummary()).trim();
        if (!summary.isEmpty() && !"Action interface utilisateur".equalsIgnoreCase(summary)) {
            return summary;
        }
        if ("UI_ACTION".equals(log.getActionType())) {
            return "Interaction effectuée sur l'écran « " + screenLabel(log) + " »";
        }
        return actionLabel(log);
    }

    public String screenLabel(UserLog log) {
        String view = detailValue(log, "Vue");
        if (view.isBlank()) {
            view = log == null ? "" : safe(log.getRequestUri());
        }
        if (view.isBlank()) {
            return "Écran non identifié";
        }
        int slash = view.lastIndexOf('/');
        String name = slash >= 0 ? view.substring(slash + 1) : view;
        int extension = name.lastIndexOf('.');
        if (extension > 0) {
            name = name.substring(0, extension);
        }
        return humanize(name);
    }

    public String resourceLabel(UserLog log) {
        if (log == null) {
            return "Ressource non renseignée";
        }
        if ("UI_ACTION".equals(log.getActionType())) {
            return screenLabel(log);
        }
        String entity = humanize(safe(log.getEntityName()));
        if (entity.isBlank()) {
            entity = humanize(safe(log.getModule()));
        }
        return entity.isBlank() ? "Ressource non renseignée" : entity;
    }

    public String resourceReference(UserLog log) {
        if (log == null) {
            return "Aucune référence";
        }
        if ("UI_ACTION".equals(log.getActionType())) {
            return "Module : " + displayValue(log.getModule());
        }
        return safe(log.getEntityId()).isBlank()
                ? "Identifiant non renseigné"
                : "Identifiant : " + log.getEntityId();
    }

    public String statusLabel(UserLog log) {
        return log != null && log.isSuccess() ? "Opération réussie" : "Opération échouée";
    }

    public String detailValue(UserLog log, String key) {
        if (log == null || key == null || log.getDetails() == null) {
            return "";
        }
        String prefix = key.trim().toLowerCase(Locale.ROOT) + " :";
        for (String line : log.getDetails().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    public String displayValue(String value) {
        return value == null || value.isBlank() ? "Non renseigné" : value;
    }

    public long getSuccessCount() {
        return logs == null ? 0 : logs.stream().filter(UserLog::isSuccess).count();
    }

    public long getFailureCount() {
        return logs == null ? 0 : logs.stream().filter(log -> !log.isSuccess()).count();
    }

    public int getUserCount() {
        if (logs == null) {
            return 0;
        }
        Set<String> users = new LinkedHashSet<>();
        logs.forEach(log -> {
            String identity = !safe(log.getUserLogin()).isBlank()
                    ? log.getUserLogin()
                    : log.getUserFullName();
            if (!safe(identity).isBlank()) {
                users.add(identity.toLowerCase(Locale.ROOT));
            }
        });
        return users.size();
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String q = filter == null ? "" : filter.toString().trim().toLowerCase(locale);
        if (q.isEmpty()) {
            return true;
        }
        UserLog log = (UserLog) value;
        String haystack = String.join(" ",
                safe(log.getUserLogin()),
                safe(log.getUserFullName()),
                safe(log.getActionType()),
                safe(log.getModule()),
                safe(log.getEntityName()),
                safe(log.getSummary()),
                safe(log.getDetails()),
                safe(log.getIpAddress()),
                safe(log.getHostname()),
                safe(log.getRequestUri()),
                safe(log.getHttpMethod()),
                safe(log.getUserAgent())
        ).toLowerCase(locale);
        return haystack.contains(q);
    }

    private String humanize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String readable = value
                .replaceAll("([a-zà-ÿ0-9])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .replaceAll("\\s+", " ");
        if (readable.isEmpty()) {
            return readable;
        }
        return readable.substring(0, 1).toUpperCase(Locale.ROOT) + readable.substring(1);
    }

    private Date startOfDay(Date date) {
        if (date == null) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private Date endOfDay(Date date) {
        if (date == null) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
