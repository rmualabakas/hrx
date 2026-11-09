package cd.mura.hrx.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

final class JsfRedirect {

    private JsfRedirect() {
    }

    static void redirect(HttpServletRequest request, HttpServletResponse response, String url) throws IOException {
        String encodedUrl = response.encodeRedirectURL(url);

        if (isFacesAjaxRequest(request)) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/xml");
            response.setCharacterEncoding("UTF-8");
            response.getWriter()
                    .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .append("<partial-response><redirect url=\"")
                    .append(xmlEscape(encodedUrl))
                    .append("\"></redirect></partial-response>");
            return;
        }

        response.sendRedirect(encodedUrl);
    }

    private static boolean isFacesAjaxRequest(HttpServletRequest request) {
        return "partial/ajax".equals(request.getHeader("Faces-Request"))
                || "true".equals(request.getParameter("jakarta.faces.partial.ajax"))
                || "true".equals(request.getParameter("javax.faces.partial.ajax"));
    }

    private static String xmlEscape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }
}
