package com.muse.connect.config;

import com.muse.connect.service.AdminAuthService;
import com.muse.connect.service.AdminAuthService.AdminSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;

/**
 * Unified security filter protecting both:
 * 1) Web console routes (/, /connectors/**, /playground, /logout) via admin session cookie + CSRF token
 * 2) Machine API routes (/api/**) via X-Muse-Key header or authenticated admin session (with CSRF on mutations)
 */
@Component
public class RelayAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Muse-Key";
    public static final String CSRF_HEADER_NAME = "X-CSRF-Token";
    public static final String CSRF_PARAM_NAME = "_csrf";
    public static final String SESSION_COOKIE_NAME = "MUSE_SESSION";
    public static final String REQUEST_SESSION_ATTR = "muse.admin.session";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final AppProperties appProperties;
    private final AdminAuthService adminAuthService;

    public RelayAuthFilter(AppProperties appProperties, AdminAuthService adminAuthService) {
        this.appProperties = appProperties;
        this.adminAuthService = adminAuthService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        applySecurityHeaders(response);

        String path = request.getRequestURI();
        if (isPublicPath(path)) {
            if ("/login".equals(path)) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            }
            attachSessionIfPresent(request);
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AdminSession> session = attachSessionIfPresent(request);

        if (path.startsWith("/api/")) {
            handleApiRequest(request, response, filterChain, session);
            return;
        }

        handleWebConsoleRequest(request, response, filterChain, session);
    }

    private void handleApiRequest(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            Optional<AdminSession> session
    ) throws ServletException, IOException {
        boolean apiKeyConfigured = StringUtils.hasText(appProperties.getApiKey());
        boolean adminAuthEnabled = adminAuthService.isEnabled();

        if (!apiKeyConfigured && !adminAuthEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        if (apiKeyConfigured && hasValidApiKey(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (adminAuthEnabled && session.isPresent()) {
            if (!isSafeMethod(request.getMethod())) {
                String csrf = request.getHeader(CSRF_HEADER_NAME);
                if (!adminAuthService.verifyCsrf(session.get(), csrf)) {
                    writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Invalid or missing X-CSRF-Token header");
                    return;
                }
            }
            filterChain.doFilter(request, response);
            return;
        }

        String msg = apiKeyConfigured
                ? "Invalid or missing X-Muse-Key header"
                : "Authentication required. Please log in or configure X-Muse-Key";
        writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, msg);
    }

    private void handleWebConsoleRequest(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            Optional<AdminSession> session
    ) throws ServletException, IOException {
        if (!adminAuthService.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (session.isEmpty()) {
            if (extractSessionCookie(request).isPresent()) {
                clearSessionCookie(request, response);
            }
            String redirectTarget = "/login";
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                String requested = buildRequestedPath(request);
                if (isSafeRedirectTarget(requested) && !"/".equals(requested)) {
                    redirectTarget = "/login?next=" + URLEncoder.encode(requested, StandardCharsets.UTF_8);
                }
            }
            response.sendRedirect(redirectTarget);
            return;
        }

        if (!isSafeMethod(request.getMethod())) {
            String csrf = request.getHeader(CSRF_HEADER_NAME);
            if (!StringUtils.hasText(csrf)) {
                csrf = request.getParameter(CSRF_PARAM_NAME);
            }
            if (!adminAuthService.verifyCsrf(session.get(), csrf)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid CSRF token");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private Optional<AdminSession> attachSessionIfPresent(HttpServletRequest request) {
        if (!adminAuthService.isEnabled()) {
            return Optional.empty();
        }
        Optional<String> cookieVal = extractSessionCookie(request);
        if (cookieVal.isEmpty()) {
            return Optional.empty();
        }
        Optional<AdminSession> session = adminAuthService.resolveSession(cookieVal.get());
        session.ifPresent(s -> request.setAttribute(REQUEST_SESSION_ATTR, s));
        return session;
    }

    private boolean hasValidApiKey(HttpServletRequest request) {
        String expected = appProperties.getApiKey().trim();
        String provided = request.getHeader(HEADER_NAME);
        return provided != null && constantTimeEquals(expected, provided.trim());
    }

    private boolean isPublicPath(String path) {
        return "/login".equals(path)
                || "/health".equals(path)
                || "/favicon.ico".equals(path)
                || path.startsWith("/css/");
    }

    private boolean isSafeMethod(String method) {
        return method != null && SAFE_METHODS.contains(method.toUpperCase());
    }

    private String buildRequestedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        return StringUtils.hasText(query) ? uri + "?" + query : uri;
    }

    public static boolean isSafeRedirectTarget(String target) {
        return StringUtils.hasText(target)
                && target.startsWith("/")
                && !target.startsWith("//")
                && !target.startsWith("/\\")
                && !target.contains("\r")
                && !target.contains("\n")
                && !target.startsWith("/login");
    }

    public static Optional<String> extractSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE_NAME.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return Optional.of(cookie.getValue().trim());
            }
        }
        return Optional.empty();
    }

    public static void clearSessionCookie(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cleared = ResponseCookie.from(SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .path("/")
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
    }

    private void applySecurityHeaders(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"success\":false,\"statusCode\":" + status + ",\"error\":\"" + message + "\"}");
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }
}
