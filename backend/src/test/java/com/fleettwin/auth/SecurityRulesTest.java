package com.fleettwin.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.time.Duration;

import com.fleettwin.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The access rules, tested without any controller: a request that passes security finds no handler
 * and answers 404, so 404 means "allowed", 403 "forbidden" and 401 "log in first".
 */
@WebMvcTest(useDefaultFilters = false)
@Import({SecurityConfig.class, TokenService.class})
@EnableConfigurationProperties(SecurityProperties.class)
@TestPropertySource(properties = {
        "fleet.security.jwt-secret=test-secret-test-secret-test-secret-0123456789",
        "fleet.security.access-token-ttl=15m", "fleet.security.refresh-token-ttl=7d",
        "fleet.security.min-password-length=10", "fleet.cors.allowed-origins=http://localhost:4200"})
class SecurityRulesTest {

    private static final int ALLOWED = 404;
    private static final int FORBIDDEN = 403;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TokenService tokens;

    private int status(String role, HttpMethod method, String path) throws Exception {
        var req = request(method, path);
        if (role != null) {
            req = req.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private void expect(HttpMethod method, String path, int admin, int manager, int technician, int viewer) throws Exception {
        assertEquals(401, status(null, method, path), "anonymous " + method + " " + path);
        assertEquals(admin, status("ADMIN", method, path), "ADMIN " + method + " " + path);
        assertEquals(manager, status("FLEET_MANAGER", method, path), "FLEET_MANAGER " + method + " " + path);
        assertEquals(technician, status("TECHNICIAN", method, path), "TECHNICIAN " + method + " " + path);
        assertEquals(viewer, status("VIEWER", method, path), "VIEWER " + method + " " + path);
    }

    @Test
    void onlyLoginRefreshAndHealthAreOpen() throws Exception {
        assertEquals(ALLOWED, status(null, HttpMethod.POST, "/api/auth/login"));
        assertEquals(ALLOWED, status(null, HttpMethod.POST, "/api/auth/refresh"));
        assertEquals(ALLOWED, status(null, HttpMethod.GET, "/actuator/health"));
        assertEquals(401, status(null, HttpMethod.GET, "/actuator/metrics"));
        assertEquals(401, status(null, HttpMethod.POST, "/api/auth/logout"));
        assertEquals(401, status(null, HttpMethod.GET, "/anything-else"));
    }

    @Test
    void everyRoleCanReadVehiclesAlertsMaintenanceAndRecommendations() throws Exception {
        for (String path : new String[] {"/api/vehicles", "/api/vehicles/1/twin", "/api/vehicles/1/trips", "/api/alerts",
                "/api/maintenance-records", "/api/recommendations", "/api/auth/me"}) {
            expect(HttpMethod.GET, path, ALLOWED, ALLOWED, ALLOWED, ALLOWED);
        }
    }

    @Test
    void viewersCannotChangeAnything() throws Exception {
        expect(HttpMethod.POST, "/api/alerts/1/acknowledge", ALLOWED, ALLOWED, ALLOWED, FORBIDDEN);
        expect(HttpMethod.PATCH, "/api/recommendations/1", ALLOWED, ALLOWED, ALLOWED, FORBIDDEN);
        expect(HttpMethod.POST, "/api/maintenance-records", ALLOWED, ALLOWED, ALLOWED, FORBIDDEN);
        expect(HttpMethod.DELETE, "/api/maintenance-records/1", ALLOWED, ALLOWED, ALLOWED, FORBIDDEN);
    }

    @Test
    void fleetAnalysisRoutesAndReportsAreNotForTechnicians() throws Exception {
        expect(HttpMethod.GET, "/api/fleet/fuel-summary", ALLOWED, ALLOWED, FORBIDDEN, ALLOWED);
        expect(HttpMethod.GET, "/api/fleet/utilisation", ALLOWED, ALLOWED, FORBIDDEN, ALLOWED);
        expect(HttpMethod.GET, "/api/reports", ALLOWED, ALLOWED, FORBIDDEN, ALLOWED);
        expect(HttpMethod.GET, "/api/reports/1/download", ALLOWED, ALLOWED, FORBIDDEN, ALLOWED);
        expect(HttpMethod.POST, "/api/reports", ALLOWED, ALLOWED, FORBIDDEN, FORBIDDEN);
        expect(HttpMethod.POST, "/api/routes/optimise", ALLOWED, ALLOWED, FORBIDDEN, FORBIDDEN);
    }

    @Test
    void usersAuditLogSettingsAndMetricsAreAdminOnly() throws Exception {
        expect(HttpMethod.GET, "/api/users", ALLOWED, FORBIDDEN, FORBIDDEN, FORBIDDEN);
        expect(HttpMethod.POST, "/api/users", ALLOWED, FORBIDDEN, FORBIDDEN, FORBIDDEN);
        expect(HttpMethod.PATCH, "/api/users/2", ALLOWED, FORBIDDEN, FORBIDDEN, FORBIDDEN);
        expect(HttpMethod.GET, "/api/audit-log", ALLOWED, FORBIDDEN, FORBIDDEN, FORBIDDEN);
        expect(HttpMethod.GET, "/api/admin/settings", ALLOWED, FORBIDDEN, FORBIDDEN, FORBIDDEN);
        expect(HttpMethod.POST, "/api/admin/reanalyse", ALLOWED, FORBIDDEN, FORBIDDEN, FORBIDDEN);
        expect(HttpMethod.GET, "/actuator/prometheus", ALLOWED, FORBIDDEN, FORBIDDEN, FORBIDDEN);
    }

    @Test
    void realTokensCarryTheRoleAndARefreshTokenIsNotAnAccessToken() throws Exception {
        User user = new User();
        user.setUsername("tina");
        user.setRole(Role.TECHNICIAN);
        user.setTokenVersion(3);
        TokenService.Tokens issued = tokens.issue(user);

        assertEquals(ALLOWED, bearer(issued.accessToken(), "/api/alerts"));
        assertEquals(FORBIDDEN, bearer(issued.accessToken(), "/api/fleet/utilisation"));
        assertEquals(401, bearer(issued.refreshToken(), "/api/alerts"), "refresh token used as access token");
        assertEquals(401, bearer(issued.accessToken() + "x", "/api/alerts"), "tampered signature");

        assertEquals("tina", tokens.parseRefresh(issued.refreshToken()).getSubject());
        assertEquals(3L, ((Number) tokens.parseRefresh(issued.refreshToken()).getClaim("ver")).longValue());
        assertThrows(JwtException.class, () -> tokens.parseRefresh(issued.accessToken()));
        assertEquals(Duration.ofMinutes(15).toSeconds(), issued.expiresIn());
    }

    private int bearer(String token, String path) throws Exception {
        return mvc.perform(request(HttpMethod.GET, path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }
}
