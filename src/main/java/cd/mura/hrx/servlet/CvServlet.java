package cd.mura.hrx.servlet;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.entity.recrutement.Cv;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.recrutement.CvthequeService;
import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Sert le fichier d'un CV : /cv/{id} (aperçu dans la page) ou /cv/{id}?dl=1 (téléchargement).
 * Réservé aux utilisateurs habilités à la CVthèque ou à la présélection.
 */
@WebServlet("/cv/*")
public class CvServlet extends HttpServlet {

    @Inject private LoginBean loginBean;
    @Inject private AuthorizationService authorization;
    @EJB private CvthequeService cvtheque;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (req.getSession(false) == null || loginBean == null || !loginBean.isLogged()
                || loginBean.getUsers() == null || loginBean.getUsers().getId() <= 0
                || !(authorization.isRoot() || authorization.hasPrivilege("CVTHEQUE") || authorization.hasPrivilege("RECRUT"))) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String brut = req.getPathInfo() == null ? "" : req.getPathInfo().replace("/", "").trim();
        if (!brut.matches("\\d{1,9}")) { resp.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
        Cv cv = cvtheque.cv(Integer.parseInt(brut));
        if (cv == null) { resp.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
        if (req.getParameter("mini") != null) {
            byte[] png = cvtheque.miniature(cv);
            if (png == null) { resp.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
            resp.setContentType("image/png");
            resp.setHeader("X-Content-Type-Options", "nosniff");
            resp.setHeader("Cache-Control", "private, max-age=86400");
            resp.setContentLength(png.length);
            resp.getOutputStream().write(png);
            return;
        }
        byte[] data;
        try { data = cvtheque.contenu(cv); } catch (IllegalStateException e) { resp.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
        boolean dl = req.getParameter("dl") != null;
        String nom = URLEncoder.encode(cv.getNomFichier(), StandardCharsets.UTF_8).replace("+", "%20");
        resp.setContentType(cv.getTypeMime() == null ? "application/octet-stream" : cv.getTypeMime() + (cv.getTypeMime().startsWith("text/") ? ";charset=UTF-8" : ""));
        resp.setHeader("Content-Disposition", (dl ? "attachment" : "inline") + "; filename*=UTF-8''" + nom);
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Cache-Control", "private, no-store");
        // aperçu affiché dans un cadre de l'application : autorisé pour la même origine uniquement
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        resp.setHeader("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; object-src 'self'; frame-ancestors 'self'");
        resp.setContentLength(data.length);
        resp.getOutputStream().write(data);
    }
}
