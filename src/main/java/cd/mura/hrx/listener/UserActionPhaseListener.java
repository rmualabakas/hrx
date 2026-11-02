package cd.mura.hrx.listener;

import cd.mura.hrx.service.AuditService;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.PhaseEvent;
import jakarta.faces.event.PhaseId;
import jakarta.faces.event.PhaseListener;
import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

public class UserActionPhaseListener implements PhaseListener {

    @Override
    public void afterPhase(PhaseEvent event) {
        FacesContext facesContext = event.getFacesContext();
        if (facesContext == null || facesContext.getViewRoot() == null) {
            return;
        }

        Object requestObject = facesContext.getExternalContext().getRequest();
        if (!(requestObject instanceof HttpServletRequest request)) {
            return;
        }

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return;
        }

        String viewId = facesContext.getViewRoot().getViewId();
        if (viewId == null || viewId.contains("userLogs")) {
            return;
        }

        String source = request.getParameter("jakarta.faces.source");
        if (source == null || source.isBlank()) {
            source = request.getParameter("javax.faces.source");
        }
        if (source == null || source.isBlank()) {
            return;
        }

        try {
            AuditService auditService = CDI.current().select(AuditService.class).get();
            auditService.uiAction(viewId, source, usefulParameters(request));
        } catch (Exception ignored) {
            // L'audit ne doit jamais bloquer le cycle JSF.
        }
    }

    @Override
    public void beforePhase(PhaseEvent event) {
        // Nothing to do.
    }

    @Override
    public PhaseId getPhaseId() {
        return PhaseId.INVOKE_APPLICATION;
    }

    private Map<String, String> usefulParameters(HttpServletRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (key == null || key.contains("ViewState") || key.startsWith("javax.faces")
                    || key.startsWith("jakarta.faces")) {
                return;
            }
            if (values != null && values.length > 0) {
                String value = values[0];
                if (value != null && !value.isBlank()) {
                    result.put(key, value);
                }
            }
        });
        return result;
    }
}
