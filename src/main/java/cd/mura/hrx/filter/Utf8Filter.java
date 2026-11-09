package cd.mura.hrx.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

public class Utf8Filter implements Filter {

    private static final String UTF8 = "UTF-8";

    @Override
    public void init(FilterConfig config) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        // IMPORTANT: forcer AVANT lecture des params JSF
        request.setCharacterEncoding("UTF-8");
        // Pas de Content-Type forcé ici : chaque ressource (CSS, JS, images, polices) garde le sien,
        // ce qui est indispensable avec X-Content-Type-Options: nosniff.

        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {

    }
}