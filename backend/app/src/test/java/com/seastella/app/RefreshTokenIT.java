package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session renewal with rotating refresh tokens (SEC-02), including security
 * test S-44: reusing a revoked refresh token revokes the whole chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-refresh-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DisplayName("refresh tokens")
class RefreshTokenIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String COOKIE = "seastella_refresh";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("sign-in sets an httpOnly, SameSite=Strict cookie limited to the auth path")
    void loginSetsHardenedCookie() throws Exception {
        MvcResult r = login("master.kestrel@acme-shipmanagement.example");
        String header = r.getResponse().getHeader(HttpHeaders.SET_COOKIE);

        assertThat(header).startsWith(COOKIE + "=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth");
        assertThat(body(r).path("accessToken").asText()).isNotBlank();
        assertThat(body(r).toString()).doesNotContain(cookieValue(r));
    }

    @Test
    @DisplayName("refresh returns a new access token and rotates the cookie")
    void refreshRotates() throws Exception {
        String first = cookieValue(login("d.fernandes@acme-shipmanagement.example"));

        MvcResult r = refresh(first);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String second = cookieValue(r);
        assertThat(second).isNotBlank().isNotEqualTo(first);

        String access = body(r).path("accessToken").asText();
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("two tabs refreshing at once do not sign the user out")
    void concurrentRefreshWithinGrace() throws Exception {
        String first = cookieValue(login("k.oyelaran@acme-shipmanagement.example"));
        String tabA = cookieValue(refresh(first));
        // Tab B presents the same token a moment later.
        MvcResult tabB = refresh(first);
        assertThat(tabB.getResponse().getStatus()).isEqualTo(200);

        assertThat(refresh(tabA).getResponse().getStatus()).isEqualTo(200);
        assertThat(refresh(cookieValue(tabB)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("S-44: reusing a rotated token revokes the whole sign-in")
    void reuseRevokesFamily() throws Exception {
        String first = cookieValue(login("coordinator@seastella.example"));
        String second = cookieValue(refresh(first));
        ageRotation(first, 60);

        // The original token again, long after it was rotated: it can only be a copy.
        assertThat(refresh(first).getResponse().getStatus()).isEqualTo(401);
        // And the legitimate successor no longer works either.
        assertThat(refresh(second).getResponse().getStatus()).isEqualTo(401);

        Integer audited = jdbc.queryForObject(
                "select count(*) from audit_entry where action = 'REFRESH_TOKEN_REUSED'", Integer.class);
        assertThat(audited).isPositive();
    }

    @Test
    @DisplayName("signing out ends the session and clears the cookie")
    void logoutRevokes() throws Exception {
        String token = cookieValue(login("tech.head@acme-shipmanagement.example"));

        MvcResult out = mvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(COOKIE, token))
                        .header("X-Requested-With", "SeaStella"))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(out.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        assertThat(refresh(token).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a suspended user's session cannot be renewed")
    void suspendedUserCannotRefresh() throws Exception {
        String email = "master.coral@acme-shipmanagement.example";
        String token = cookieValue(login(email));

        jdbc.update("update app_user set status = 'SUSPENDED' where email = ?", email);
        try {
            assertThat(refresh(token).getResponse().getStatus()).isEqualTo(401);
        } finally {
            jdbc.update("update app_user set status = 'ACTIVE' where email = ?", email);
        }
    }

    @Test
    @DisplayName("renewal needs the anti-forgery header and a cookie")
    void refreshRequiresHeaderAndCookie() throws Exception {
        String token = cookieValue(login("admin@seastella.example"));

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh").header("X-Requested-With", "SeaStella"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, "not-a-real-token"))
                        .header("X-Requested-With", "SeaStella"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Netlify production origin is allowed for refresh preflight requests")
    void productionOriginAllowsPreflightForRefresh() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "https://seastella.netlify.app")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,authorization,x-requested-with")
                        .header("X-Requested-With", "SeaStella"))
                .andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "https://seastella.netlify.app")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,authorization,x-requested-with"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String allowOrigin = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
                    assertThat(allowOrigin).isEqualTo("https://seastella.netlify.app");
                    assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
                    assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("POST");
                    assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)).contains("Authorization");
                });
    }

    private MvcResult login(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult refresh(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(COOKIE, token))
                        .header("X-Requested-With", "SeaStella"))
                .andReturn();
    }

    /** Moves a token's rotation into the past, beyond the reuse grace period. */
    private void ageRotation(String rawToken, long seconds) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("update refresh_token set revoked_at = ? where token_hash = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(seconds)), hash);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static String cookieValue(MvcResult r) {
        String header = r.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).as("Set-Cookie").isNotNull();
        String pair = header.split(";", 2)[0];
        return pair.substring(pair.indexOf('=') + 1);
    }
}
