package cd.mura.hrx.filter;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.dao.config.MenuDAO;
import cd.mura.hrx.entity.config.Menu;
import jakarta.ejb.EJB;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@WebFilter(urlPatterns = "/ui/*")
public class FilterProfilPage implements Filter {

    @Inject
    private LoginBean identification;   // @SessionScoped attendu

    @EJB
    private MenuDAO menuDAO;
    private static final Set<String> ALWAYS_ALLOW = Set.of(
            "ui/home", "ui/monCompte", "ui/rechercheGlobale", "ui/fiche", "ui/objectifs", "ui/suiviObjectifs", "ui/organigramme",
            // fiche de fin d'essai (N+1, collaborateur) et questionnaire d'évaluation à chaud (participant) : droits vérifiés dans la page
            "ui/periodeEssai", "ui/questionnaire");

    @Override public void init(FilterConfig filterConfig) { /* no-op */ }
    @Override public void destroy() { /* no-op */ }

    @SuppressWarnings("unchecked")
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;
        String ctx = request.getContextPath();

        // Fallback si @Inject n'a pas encore fourni le bean
        if (identification == null) {
            try { identification = CDI.current().select(LoginBean.class).get(); }
            catch (Exception ignored) { /* laisse null */ }
        }

        // Non connecté -> /login
        if (identification == null || !identification.isLogged()) {
            rememberRequestedPage(request);
            JsfRedirect.redirect(request, response, ctx + "/login");
            return;
        }

        // ⚠️ Forcer le changement de mot de passe pour toute requête /ui/*
        // (cette page n'est pas sous /ui, donc pas de boucle)
        if (identification.getUsers() != null && identification.getUsers().getPassChange() == 1) {
            JsfRedirect.redirect(request, response, ctx + "/passChange");
            return;
        }

        // Le profil root (id 1) n'est jamais verrouillé hors de l'application.
        if (identification.getUsers().getProfil() != null && identification.getUsers().getProfil().getId() == 1) {
            chain.doFilter(req, resp);
            return;
        }

        // Relecture à chaque navigation : la désactivation d'un menu ou de son
        // module parent doit prendre effet immédiatement, même pour une session
        // déjà ouverte.
        Set<String> allowed = loadAllowedPaths();

        // Normalise la requête courante (ex: /app/ui/clients/list -> "ui/clients/list")
        String requested = normalizeRequestPath(request);

        if (allowed.contains(requested)) {
            chain.doFilter(req, resp);
        } else {
            JsfRedirect.redirect(request, response, ctx + "/ui/home");
        }
    }

    // Helpers

    private Set<String> loadAllowedPaths() {
        List<Menu> accessibleMenus = menuDAO
                .findAllActifByProfilForSearch(identification.getUsers().getProfil());
        Set<String> allowed = accessibleMenus
                .stream()
                .map(Menu::getUrl)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.equals("#"))
                .map(FilterProfilPage::normalizeMenuUrl)
                .filter(p -> p.startsWith("ui/"))
                .collect(Collectors.toCollection(() -> new LinkedHashSet<>(128)));
        allowed.addAll(ALWAYS_ALLOW);
        return allowed;
    }

    private static void rememberRequestedPage(HttpServletRequest request) {
        String path = requestPathWithoutContext(request);
        if (path == null || !path.startsWith("/ui/") || hasInvalidRedirectChars(path)) {
            return;
        }

        String target = path;
        String query = "GET".equalsIgnoreCase(request.getMethod()) ? request.getQueryString() : null;
        if (query != null && !query.isBlank() && !hasInvalidRedirectChars(query)) {
            target += "?" + query;
        }

        request.getSession(true).setAttribute(LoginBean.REDIRECT_AFTER_LOGIN_KEY, target);
    }

    /** /app/ui/clients/list -> ui/clients/list (sans leading slash) */
    private static String normalizeRequestPath(HttpServletRequest request) {
        String path = requestPathWithoutContext(request);
        if (path.startsWith("/")) path = path.substring(1);                    // ui/...
        return stripTrailingSlashes(path);
    }

    private static String requestPathWithoutContext(HttpServletRequest request) {
        String ctx = request.getContextPath();
        String uri = request.getRequestURI();
        return uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri; // /ui/...
    }

    /** Normalise l’URL en base vers "ui/..." (sans gérer d’extensions, car non utilisées). */
    private static String normalizeMenuUrl(String raw) {
        String s = raw.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) return s; // hors /ui/*
        if (s.startsWith("/")) s = s.substring(1);
        s = stripTrailingSlashes(s);
        if (!s.startsWith("ui/")) s = "ui/" + s;
        return s;
    }

    private static String stripTrailingSlashes(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static boolean hasInvalidRedirectChars(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }
}
