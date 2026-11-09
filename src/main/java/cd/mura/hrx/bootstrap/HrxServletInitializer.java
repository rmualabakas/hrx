package cd.mura.hrx.bootstrap;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

import java.util.Set;

/** Configuration du conteneur avant la création des sessions. */
public class HrxServletInitializer implements ServletContainerInitializer {
    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext context) throws ServletException {
        String configured = System.getProperty("hrx.cookie.secure");
        if (configured == null || configured.isBlank()) configured = System.getenv("HRX_COOKIE_SECURE");
        boolean secure = Boolean.parseBoolean(configured);
        context.getSessionCookieConfig().setHttpOnly(true);
        context.getSessionCookieConfig().setSecure(secure);
        context.log("HRX : cookie de session Secure=" + secure
                + (secure ? "" : " (activer avec -Dhrx.cookie.secure=true en production)"));
    }
}
