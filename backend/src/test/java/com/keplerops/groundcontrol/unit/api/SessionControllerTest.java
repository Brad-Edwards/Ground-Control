package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.session.SessionController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-slice contract for {@code GET /api/v1/session} (GC-Q015 clause (a)). Filters are
 * disabled so the slice exercises the controller's principal projection directly; the ADR-037
 * filter-chain behavior (401 entry point, CSRF, session expiry) is proven in the security
 * integration tests, not here.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(SessionController.class)
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static Authentication auth(String name, String... roles) {
        var authorities =
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList();
        return new UsernamePasswordAuthenticationToken(name, "n/a", authorities);
    }

    @Test
    void returnsPrincipalDisplayAndAdminCapability() throws Exception {
        mockMvc.perform(get("/api/v1/session").principal(auth("alice", "ROLE_USER", "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName", is("alice")))
                .andExpect(jsonPath("$.roles", contains("ROLE_ADMIN", "ROLE_USER")))
                .andExpect(jsonPath("$.canAdminister", is(true)));
    }

    @Test
    void nonAdminHasNoAdminCapability() throws Exception {
        mockMvc.perform(get("/api/v1/session").principal(auth("bob", "ROLE_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName", is("bob")))
                .andExpect(jsonPath("$.roles", contains("ROLE_USER")))
                .andExpect(jsonPath("$.canAdminister", is(false)));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/session")).andExpect(status().isUnauthorized());
    }
}
