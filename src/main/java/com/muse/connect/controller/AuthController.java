package com.muse.connect.controller;

import com.muse.connect.config.RelayAuthFilter;
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

    @GetMapping("/health")
    @ResponseBody
    public Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/login")
    public String loginPage(
            @RequestParam(required = false) String next,
            @RequestParam(required = false) String loggedOut,
            HttpServletRequest request,
            Model model
    ) {
        if (!adminAuthService.isEnabled()) {
            return "redirect:/";
        }
        if (request.getAttribute(RelayAuthFilter.REQUEST_SESSION_ATTR) instanceof AdminSession) {
            return "redirect:" + resolveNext(next);
        }
        model.addAttribute("next", resolveNext(next));
        if (loggedOut != null) {
            model.addAttribute("message", "已安全退出登录。");
        }
        long lockedSec = adminAuthService.getLockoutRemainingSeconds(request.getRemoteAddr());
        if (lockedSec > 0) {
            model.addAttribute("error", formatLockoutMessage(lockedSec));
        }
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(
            @RequestParam(defaultValue = "") String username,
            @RequestParam(defaultValue = "") String password,
            @RequestParam(required = false) String next,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) {
        if (!adminAuthService.isEnabled()) {
            return "redirect:/";
        }

        // 使先前可能存在的会话 Cookie 失效，防止会话固定攻击
        RelayAuthFilter.extractSessionCookie(request).ifPresent(adminAuthService::invalidateSession);

        AuthResult result = adminAuthService.authenticate(username, password, request.getRemoteAddr());
        String safeNext = resolveNext(next);

        if (result.status() == AuthResult.Status.SUCCESS && result.session() != null) {
            ResponseCookie cookie = ResponseCookie.from(RelayAuthFilter.SESSION_COOKIE_NAME, result.session().id())
                    .httpOnly(true)
                    .secure(request.isSecure())
                    .path("/")
                    .sameSite("Lax")
                    .maxAge(adminAuthService.getSessionTtl())
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
            return "redirect:" + safeNext;
        }

        model.addAttribute("next", safeNext);
        model.addAttribute("username", username);
        if (result.status() == AuthResult.Status.LOCKED_OUT) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));
            model.addAttribute("error", formatLockoutMessage(result.retryAfterSeconds()));
        } else {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            model.addAttribute("error", "用户名或密码错误，请重试。");
        }
        return "login";
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        RelayAuthFilter.extractSessionCookie(request).ifPresent(adminAuthService::invalidateSession);
        RelayAuthFilter.clearSessionCookie(request, response);
        return adminAuthService.isEnabled() ? "redirect:/login?loggedOut=1" : "redirect:/";
    }

    private String resolveNext(String next) {
        return RelayAuthFilter.isSafeRedirectTarget(next) ? next : "/";
    }

    private String formatLockoutMessage(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return "登录失败次数过多，当前 IP 已临时锁定，请约 " + minutes + " 分钟后再试。";
    }
}
