package cd.mura.hrx.exception;

import jakarta.faces.application.ViewExpiredException;
import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerWrapper;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.ExceptionQueuedEvent;
import jakarta.faces.event.ExceptionQueuedEventContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.OptimisticLockException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Recovers expired JSF views without turning this expected lifecycle event into
 * a severe application error. All other exceptions are delegated unchanged.
 */
public final class HrxExceptionHandler extends ExceptionHandlerWrapper {

    private static final Logger LOGGER = Logger.getLogger(HrxExceptionHandler.class.getName());

    private final ExceptionHandler wrapped;

    public HrxExceptionHandler(ExceptionHandler wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public ExceptionHandler getWrapped() {
        return wrapped;
    }

    @Override
    public void handle() {
        Iterator<ExceptionQueuedEvent> events = getUnhandledExceptionQueuedEvents().iterator();

        while (events.hasNext()) {
            ExceptionQueuedEvent event = events.next();
            ExceptionQueuedEventContext eventContext =
                    (ExceptionQueuedEventContext) event.getSource();
            ViewExpiredException expiredView = findViewExpiredException(eventContext.getException());

            if (expiredView != null) {
                events.remove();
                recoverExpiredView(expiredView);
            } else if (findCause(eventContext.getException(), OptimisticLockException.class) != null) {
                events.remove();
                recoverConcurrentUpdate();
            } else if (findCause(eventContext.getException(), SecurityException.class) != null) {
                events.remove();
                sendForbidden();
            } else if (redirectToErrorPage(eventContext.getException())) {
                events.remove();
            }
        }

        // Les erreurs non redirigeables (réponse déjà engagée) suivent le traitement standard,
        // puis la page d'erreur déclarée dans web.xml.
        wrapped.handle();
    }

    private void recoverConcurrentUpdate() {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null || context.getResponseComplete()) return;
        ExternalContext external = context.getExternalContext();
        String target = external.getRequestContextPath() + "/ui/home?conflit=true";
        String referer = external.getRequestHeaderMap().get("Referer");
        if (referer != null && !referer.isBlank()) {
            try {
                URI uri = new URI(referer);
                String path = uri.getPath();
                if (path != null && path.startsWith(external.getRequestContextPath() + "/")) {
                    String query = uri.getRawQuery();
                    target = path + (query == null || query.isBlank() ? "?" : "?" + query + "&") + "conflit=true";
                }
            } catch (URISyntaxException ignored) {
                LOGGER.log(Level.FINE, "Referer non exploitable après conflit concurrent");
            }
        }
        try {
            external.redirect(target);
            context.responseComplete();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Impossible de restaurer la vue après conflit concurrent", e);
        }
    }

    private void sendForbidden() {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null || context.getResponseComplete()) return;
        try {
            context.getExternalContext().responseSendError(403, "Action non autorisée");
            context.responseComplete();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Impossible de retourner la réponse 403", e);
        }
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            if (current == current.getCause()) break;
            current = current.getCause();
        }
        return null;
    }

    /** Erreur applicative inattendue */
    private boolean redirectToErrorPage(Throwable throwable) {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (facesContext == null || facesContext.getResponseComplete()) return false;
        ExternalContext externalContext = facesContext.getExternalContext();
        if (externalContext.isResponseCommitted()) return false;   // trop tard : le conteneur prendra le relais via web.xml
        String ref = java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        String vue = facesContext.getViewRoot() == null ? "?" : facesContext.getViewRoot().getViewId();
        LOGGER.log(Level.SEVERE, "Incident HRX " + ref + " sur " + vue, throwable);
        try {
            externalContext.getSessionMap().put("hrx.incident", ref);
            externalContext.redirect(externalContext.getRequestContextPath() + "/erreur?ref=" + ref);
            facesContext.responseComplete();
            return true;
        } catch (Exception redirectFailure) {
            LOGGER.log(Level.WARNING, "Redirection vers la page d'erreur impossible (incident " + ref + ")", redirectFailure);
            return false;
        }
    }

    private ViewExpiredException findViewExpiredException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ViewExpiredException viewExpiredException) {
                return viewExpiredException;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private void recoverExpiredView(ViewExpiredException exception) {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (facesContext == null || facesContext.getResponseComplete()) {
            return;
        }

        ExternalContext externalContext = facesContext.getExternalContext();
        String target = buildRecoveryTarget(externalContext, exception.getViewId());

        try {
            // ExternalContext emits a JSF partial redirect for Ajax requests and
            // a regular HTTP redirect otherwise. The browser receives a fresh
            // view-state token in both cases.
            externalContext.redirect(target);
            facesContext.responseComplete();
            LOGGER.log(Level.FINE, "Vue JSF expirée restaurée silencieusement : {0}", target);
        } catch (IOException redirectFailure) {
            // A failed recovery is unexpected and must remain diagnosable.
            LOGGER.log(Level.WARNING, "Impossible de restaurer la vue JSF expirée " + target,
                    redirectFailure);
        }
    }

    private String buildRecoveryTarget(ExternalContext externalContext, String viewId) {
        String contextPath = externalContext.getRequestContextPath();
        String safeViewId = viewId != null && viewId.startsWith("/") ? viewId : "/home.xhtml";
        String fallbackPath = contextPath + safeViewId;
        String queryString = null;
        if (externalContext.getRequest() instanceof HttpServletRequest request) {
            queryString = request.getQueryString();
            if (request.getRequestURI() != null && request.getRequestURI().startsWith(contextPath + "/")) {
                fallbackPath = request.getRequestURI();
            }
        }

        // JSF Ajax posts do not always retain the page query string in the form
        // action. Prefer the local Referer so pretty URLs and kiosk/display
        // settings encoded in the URL are not lost after an expiration.
        String referer = externalContext.getRequestHeaderMap().get("Referer");
        if (referer != null && !referer.isBlank()) {
            try {
                URI refererUri = new URI(referer);
                String refererPath = refererUri.getPath();
                if (refererPath != null && refererPath.startsWith(contextPath + "/")) {
                    return refererUri.getRawQuery() == null
                            ? refererPath
                            : refererPath + "?" + refererUri.getRawQuery();
                }
            } catch (URISyntaxException ignored) {
                LOGGER.log(Level.FINE, "En-tête Referer non exploitable lors de la restauration JSF");
            }
        }

        return queryString == null || queryString.isBlank()
                ? fallbackPath
                : fallbackPath + "?" + queryString;
    }
}
