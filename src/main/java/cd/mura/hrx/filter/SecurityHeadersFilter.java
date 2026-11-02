package cd.mura.hrx.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** En-têtes de sécurité communs à toutes les réponses HRX. */
@WebFilter(urlPatterns = "/*")
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        httpResponse.setHeader("X-Frame-Options", "DENY");
        httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        httpResponse.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        httpResponse.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        httpResponse.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        // JSF/PrimeFaces utilise encore des gestionnaires inline. Cette politique les
        // autorise temporairement tout en interdisant plugins, frames et base externes.
        httpResponse.setHeader("Content-Security-Policy",
                "default-src 'self'; base-uri 'self'; frame-ancestors 'none'; object-src 'none'; "
                        + "form-action 'self'; img-src 'self' data:; font-src 'self' data:; "
                        + "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline' 'unsafe-eval'");

        if (httpRequest.isSecure()) {
            httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        if (httpRequest.getRequestURI().contains("/ui/")
                || httpRequest.getRequestURI().endsWith("/login")
                || httpRequest.getRequestURI().endsWith("/login.xhtml")) {
            httpResponse.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, private");
            httpResponse.setHeader("Pragma", "no-cache");
        }

        chain.doFilter(request, response);
    }
}
