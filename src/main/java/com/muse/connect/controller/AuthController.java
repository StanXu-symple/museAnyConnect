package com.muse.connect.controller;

import com.muse.connect.config.RelayAuthFilter;
import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.RouteConstants;
import com.muse.connect.constant.SecurityConstants;
import com.muse.connect.constant.WebViewConstants;
import com.muse.connect.service.AdminAuthService;
import com.muse.connect.service.AdminAuthService.AdminSession;
import com.muse.connect.service.AdminAuthService.AuthResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Controller
public class AuthController {

    private final AdminAuthService adminAuthService;

    public AuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @GetMapping(RouteConstants.HEALTH)
    @ResponseBody
    public Map<String, Object> health() {
        return Map.of(WebViewConstants.KEY_STATUS, WebViewConstants.VALUE_UP);
    }

    @GetMapping(RouteConstants.LOGIN)
    public String loginPage(
            @RequestParam(required = false) String next,
            @RequestParam(required = false) String loggedOut,
            HttpServletRequest request,
            Model model
    ) {
        if (!adminAuthService.isEnabled()) {
            return RouteConstants.REDIRECT_ROOT;
        }
        if (request.getAttribute(RelayAuthFilter.REQUEST_SESSION_ATTR) instanceof AdminSession) {
            return RouteConstants.REDIRECT_PREFIX + resolveNext(next);
        }
        model.addAttribute(WebViewConstants.ATTR_NEXT, resolveNext(next));
        if (loggedOut != null) {
            model.addAttribute(WebViewConstants.ATTR_MESSAGE, WebViewConstants.MSG_LOGGED_OUT);
        }
        long lockedSec = adminAuthService.getLockoutRemainingSeconds(request.getRemoteAddr());
        if (lockedSec > 0) {
            model.addAttribute(WebViewConstants.ATTR_ERROR, formatLockoutMessage(lockedSec));
        }
        return RouteConstants.VIEW_LOGIN;
    }

    @PostMapping(RouteConstants.LOGIN)
    public String doLogin(
            @RequestParam(defaultValue = RouteConstants.EMPTY) String username,
            @RequestParam(defaultValue = RouteConstants.EMPTY) String password,
            @RequestParam(required = false) String next,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) {
        if (!adminAuthService.isEnabled()) {
            return RouteConstants.REDIRECT_ROOT;
        }

        // 使先前可能存在的会话 Cookie 失效，防止会话固定攻击
        RelayAuthFilter.extractSessionCookie(request).ifPresent(adminAuthService::invalidateSession);

        AuthResult result = adminAuthService.authenticate(username, password, request.getRemoteAddr());
        String safeNext = resolveNext(next);

        if (result.status() == AuthResult.Status.SUCCESS && result.session() != null) {
            ResponseCookie cookie = ResponseCookie.from(RelayAuthFilter.SESSION_COOKIE_NAME, result.session().id())
                    .httpOnly(true)
                    .secure(request.isSecure())
                    .path(RouteConstants.ROOT)
                    .sameSite(SecurityConstants.COOKIE_SAME_SITE_LAX)
                    .maxAge(adminAuthService.getSessionTtl())
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
            return RouteConstants.REDIRECT_PREFIX + safeNext;
        }

        model.addAttribute(WebViewConstants.ATTR_NEXT, safeNext);
        model.addAttribute(WebViewConstants.ATTR_USERNAME, username);
        if (result.status() == AuthResult.Status.LOCKED_OUT) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));
            model.addAttribute(WebViewConstants.ATTR_ERROR, formatLockoutMessage(result.retryAfterSeconds()));
        } else {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            model.addAttribute(WebViewConstants.ATTR_ERROR, ExceptionMessageConstants.WEB_LOGIN_INVALID);
        }
        return RouteConstants.VIEW_LOGIN;
    }

    @PostMapping(RouteConstants.LOGOUT)
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        RelayAuthFilter.extractSessionCookie(request).ifPresent(adminAuthService::invalidateSession);
        RelayAuthFilter.clearSessionCookie(request, response);
        return adminAuthService.isEnabled() ? RouteConstants.REDIRECT_LOGIN_LOGGED_OUT : RouteConstants.REDIRECT_ROOT;
    }

    private String resolveNext(String next) {
        return RelayAuthFilter.isSafeRedirectTarget(next) ? next : RouteConstants.ROOT;
    }

    private String formatLockoutMessage(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return WebViewConstants.MSG_LOCKOUT_FMT.formatted(minutes);
    }
}
