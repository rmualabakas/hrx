package cd.mura.hrx.servlet;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.service.PhotoService;
import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Sert la photo d'un collaborateur : /photo/{id}. Réservé aux utilisateurs connectés. */
@WebServlet("/photo/*")
public class PhotoServlet extends HttpServlet {

    @Inject private LoginBean loginBean;
    @Inject private PhotoService photos;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // LoginBean est créé avec un Users vide pour tout visiteur : seul isLogged() prouve l'authentification.
        if (req.getSession(false) == null || loginBean == null || !loginBean.isLogged()
                || loginBean.getUsers() == null || loginBean.getUsers().getId() <= 0) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String p = req.getPathInfo();
        int id;
        String brut = p == null ? "" : p.replace("/", "").trim();
        if (!brut.matches("\\d{1,9}")) { resp.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
        id = Integer.parseInt(brut);
        byte[] data = photos.octets(id);
        if (data == null) { resp.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
        resp.setContentType("image/jpeg");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Content-Disposition", "inline; filename=\"photo-" + id + ".jpg\"");
        resp.setContentLength(data.length);
        // l'adresse porte la version (?v=...) : on peut laisser le navigateur la garder longtemps
        resp.setHeader("Cache-Control", req.getParameter("v") != null ? "private, max-age=31536000, immutable" : "private, no-cache");
        resp.getOutputStream().write(data);
    }
}
