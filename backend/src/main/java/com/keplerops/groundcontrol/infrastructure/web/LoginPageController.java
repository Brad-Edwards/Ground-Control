package com.keplerops.groundcontrol.infrastructure.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the standalone login bundle's HTML at {@code GET /login}.
 *
 * <p>The login screen ships as its own Vite build (output to {@code static/login.html} and
 * {@code static/login-assets/}) rather than as an SPA route, so an unauthenticated browser can
 * render the React login UI without fetching the main app bundle. The main app shell
 * ({@code /index.html} + {@code /assets/**}) stays gated by the path matrix in
 * {@link com.keplerops.groundcontrol.shared.security.BrowserSecurityConfig} per ADR-037 §2,
 * §3 (amended).
 *
 * <p>The body is streamed via {@code ResponseEntity<Resource>} rather than a {@code forward:}
 * view — a forward re-enters the security filter chain on the forwarded path, and the obvious
 * target ({@code /index.html}) is correctly gated to authenticated requests. Streaming the
 * classpath resource directly returns the bytes to the caller in this same request without
 * re-running any filter. Caching is suppressed because the login state is per-session.
 */
@Controller
public class LoginPageController {

    private static final Resource LOGIN_HTML = new ClassPathResource("static/login.html");

    @GetMapping("/login")
    public ResponseEntity<Resource> loginPage() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .body(LOGIN_HTML);
    }
}
