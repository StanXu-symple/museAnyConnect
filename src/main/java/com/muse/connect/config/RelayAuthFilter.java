package com.muse.connect.config;

import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.HttpConstants;
import com.muse.connect.constant.RouteConstants;
import com.muse.connect.constant.SecurityConstants;
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
 * 统一安全过滤器，同时保护以下两类路由：
 * 1) Web 控制台路由（/、/connectors/**、/playground、/logout）：通过管理员会话 Cookie + CSRF 令牌校验
 * 2) 机器 API 路由（/api/**）：通过 X-Muse-Key 请求头或已认证的管理员会话校验（写操作需附带 CSRF 令牌）
 */
@Component
public class RelayAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = SecurityConstants.HEADER_API_KEY;
    public static final String CSRF_HEADER_NAME = SecurityConstants.HEADER_CSRF_TOKEN;
    public static final String CSRF_PARAM_NAME = SecurityConstants.PARAM_CSRF;
    public static final String SESSION_COOKIE_NAME = SecurityConstants.COOKIE_SESSION;
    public static final String REQUEST_SESSION_ATTR = SecurityConstants.ATTR_ADMIN_SESSION;

    private static final Set<String> SAFE_METHODS = Set.of(
            HttpConstants.METHOD_GET,
            HttpConstants.METHOD_HEAD,
            HttpConstants.METHOD_OPTIONS,
            HttpConstants.METHOD_TRACE
    );

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
            if (RouteConstants.LOGIN.equals(path)) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, SecurityConstants.VALUE_NO_STORE);
            }
            attachSessionIfPresent(request);
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AdminSession> session = attachSessionIfPresent(request);

        if (path.startsWith(RouteConstants.API_PREFIX)) {
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
                    writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, ExceptionMessageConstants.AUTH_INVALID_CSRF_HEADER);
                    return;
                }
            }
            filterChain.doFilter(request, response);
            return;
        }

        String msg = apiKeyConfigured
                ? ExceptionMessageConstants.AUTH_INVALID_API_KEY
                : ExceptionMessageConstants.AUTH_REQUIRED;
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
            String redirectTarget = RouteConstants.LOGIN;
            if (HttpConstants.METHOD_GET.equalsIgnoreCase(request.getMethod())) {
                String requested = buildRequestedPath(request);
                if (isSafeRedirectTarget(requested) && !RouteConstants.ROOT.equals(requested)) {
                    redirectTarget = RouteConstants.LOGIN_NEXT_PREFIX + URLEncoder.encode(requested, StandardCharsets.UTF_8);
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
                response.sendError(HttpServletResponse.SC_FORBIDDEN, ExceptionMessageConstants.AUTH_INVALID_CSRF_TOKEN);
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
        return RouteConstants.LOGIN.equals(path)
                || RouteConstants.HEALTH.equals(path)
                || RouteConstants.FAVICON.equals(path)
                || path.startsWith(RouteConstants.CSS_PREFIX);
    }

    private boolean isSafeMethod(String method) {
        return method != null && SAFE_METHODS.contains(method.toUpperCase());
    }

    private String buildRequestedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        return StringUtils.hasText(query) ? uri + RouteConstants.QUERY_PREFIX + query : uri;
    }

    public static boolean isSafeRedirectTarget(String target) {
        return StringUtils.hasText(target)
                && target.startsWith(RouteConstants.ROOT)
                && !target.startsWith(RouteConstants.UNSAFE_REDIRECT_DOUBLE_SLASH)
                && !target.startsWith(RouteConstants.UNSAFE_REDIRECT_BACKSLASH)
                && !target.contains(RouteConstants.CHAR_CR)
                && !target.contains(RouteConstants.CHAR_LF)
                && !target.startsWith(RouteConstants.LOGIN);
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
        ResponseCookie cleared = ResponseCookie.from(SESSION_COOKIE_NAME, RouteConstants.EMPTY)
                .httpOnly(true)
                .secure(request.isSecure())
                .path(RouteConstants.ROOT)
                .sameSite(SecurityConstants.COOKIE_SAME_SITE_LAX)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
    }

    private void applySecurityHeaders(HttpServletResponse response) {
        response.setHeader(SecurityConstants.HEADER_CONTENT_TYPE_OPTIONS, SecurityConstants.VALUE_NOSNIFF);
        response.setHeader(SecurityConstants.HEADER_FRAME_OPTIONS, SecurityConstants.VALUE_DENY);
        response.setHeader(SecurityConstants.HEADER_REFERRER_POLICY, SecurityConstants.VALUE_SAME_ORIGIN);
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(SecurityConstants.FILTER_JSON_ERROR_FMT.formatted(status, message));
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }
}
