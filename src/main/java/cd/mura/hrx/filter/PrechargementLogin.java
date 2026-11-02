package cd.mura.hrx.filter;

import cd.mura.hrx.bean.config.LoginBean;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebFilter(urlPatterns = {"/login", "/login.xhtml",
        "/lostPassword", "/lostPassword.xhtml",
        "/passChange", "/passChange.xhtml"})
public class PrechargementLogin implements Filter {

    @Inject
    private LoginBean identification;

    @Override public void init(FilterConfig filterConfig) { }
    @Override public void destroy() { }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        // Fallback si @Inject n'a pas marché
        if (identification == null) {
            try { identification = CDI.current().select(LoginBean.class).get(); }
            catch (Exception ignored) { }
        }

        String ctx  = request.getContextPath();
        String path = request.getRequestURI().substring(ctx.length()); // ex: /passChange

        boolean onLogin      = path.equals("/login")       || path.equals("/login.xhtml");
        boolean onLost       = path.equals("/lostPassword")|| path.equals("/lostPassword.xhtml");
        boolean onPassChange = path.equals("/passChange")  || path.equals("/passChange.xhtml");

        boolean logged = identification != null && identification.isLogged();
        boolean mustChange = logged && identification.getUsers() != null
                && identification.getUsers().getPassChange() == 1;

        // Utilisateur non connecté : /login et /lostPassword autorisés, /passChange interdit
        if (!logged) {
            if (onPassChange) {
                JsfRedirect.redirect(request, response, ctx + "/login");
                return;
            }
            chain.doFilter(req, resp);
            return;
        }

        // Connecté + doit changer de mot de passe :
        // autoriser uniquement /passChange, rediriger toute autre page vers /passChange
        if (mustChange) {
            if (!onPassChange) {
                JsfRedirect.redirect(request, response, ctx + "/passChange");
                return;
            }
            chain.doFilter(req, resp); // déjà sur /passChange
            return;
        }

        // Connecté + mot de passe OK : empêcher l'accès à /login, /lostPassword, /passChange
        if (onLogin || onLost || onPassChange) {
            JsfRedirect.redirect(request, response, ctx + "/ui/home");
            return;
        }

        chain.doFilter(req, resp);
    }
}
