package com.muse.connect.controller;

import com.muse.connect.config.RelayAuthFilter;
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

    @ModelAttribute("authEnabled")
    public boolean authEnabled() {
        return adminAuthService.isEnabled();
    }

    @ModelAttribute("allowRawExport")
    public boolean allowRawExport() {
        return adminAuthService.isAllowRawExport();
    }

    @ModelAttribute("currentUser")
    public String currentUser(HttpServletRequest request) {
        Object attr = request.getAttribute(RelayAuthFilter.REQUEST_SESSION_ATTR);
        if (attr instanceof AdminSession session) {
            return session.username();
        }
        return null;
    }

    @ModelAttribute("csrfToken")
    public String csrfToken(HttpServletRequest request) {
        Object attr = request.getAttribute(RelayAuthFilter.REQUEST_SESSION_ATTR);
        if (attr instanceof AdminSession session) {
            return session.csrfToken();
        }
        return "";
    }
}
