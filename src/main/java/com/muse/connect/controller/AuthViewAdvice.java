package com.muse.connect.controller;

import com.muse.connect.config.RelayAuthFilter;
import com.muse.connect.constant.RouteConstants;
import com.muse.connect.constant.WebViewConstants;
import com.muse.connect.service.AdminAuthService;
import com.muse.connect.service.AdminAuthService.AdminSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {WebConsoleController.class, AuthController.class})
public class AuthViewAdvice {

    private final AdminAuthService adminAuthService;

    public AuthViewAdvice(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @ModelAttribute(WebViewConstants.ATTR_AUTH_ENABLED)
    public boolean authEnabled() {
        return adminAuthService.isEnabled();
    }

    @ModelAttribute(WebViewConstants.ATTR_ALLOW_RAW_EXPORT)
    public boolean allowRawExport() {
        return adminAuthService.isAllowRawExport();
    }

    @ModelAttribute(WebViewConstants.ATTR_CURRENT_USER)
    public String currentUser(HttpServletRequest request) {
        Object attr = request.getAttribute(RelayAuthFilter.REQUEST_SESSION_ATTR);
        if (attr instanceof AdminSession session) {
            return session.username();
        }
        return null;
    }

    @ModelAttribute(WebViewConstants.ATTR_CSRF_TOKEN)
    public String csrfToken(HttpServletRequest request) {
        Object attr = request.getAttribute(RelayAuthFilter.REQUEST_SESSION_ATTR);
        if (attr instanceof AdminSession session) {
            return session.csrfToken();
        }
        return RouteConstants.EMPTY;
    }
}
