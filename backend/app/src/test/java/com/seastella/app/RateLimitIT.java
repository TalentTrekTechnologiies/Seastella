package com.seastella.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-23: sign-in and account links are capped per address, writes per user,
 * and reads are never capped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "seastella.security.rate-limit.enabled=true",
        "seastella.security.rate-limit.sensitive-per-minute=4",
        "seastella.security.rate-limit.writes-per-minute=3",
        "spring.datasource.url=jdbc:h2:mem:seastella-ratelimit-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DisplayName("rate limiting")
class RateLimitIT {

    private static final String PASSWORD = "SeaStella#Demo2026";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("password guessing from one address is cut off, with Retry-After")
    void signInIsCapped() throws Exception {
        String ip = "203.0.113.10";
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(login("admin@seastella.example", "not-the-password", ip).getResponse().getStatus())
                    .as("attempt " + attempt)
                    .isEqualTo(401);
        }
        MvcResult refused = login("admin@seastella.example", "not-the-password", ip);
        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getContentAsString()).contains("RATE_LIMITED");
        assertThat(Integer.parseInt(refused.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1, 60);

        // The right password from that address is refused too: the limit is the address.
        assertThat(login("admin@seastella.example", PASSWORD, ip).getResponse().getStatus()).isEqualTo(429);
        // Another address is unaffected.
        assertThat(login("admin@seastella.example", PASSWORD, "203.0.113.99").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("the account-link endpoints share the same cap")
    void accountLinksAreCapped() throws Exception {
        String ip = "203.0.113.20";
        for (int i = 0; i < 4; i++) {
            assertThat(mvc.perform(post("/api/v1/account/password-resets").with(r -> { r.setRemoteAddr(ip); return r; })
                            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody@example.com\"}"))
                    .andReturn().getResponse().getStatus()).isEqualTo(202);
        }
        assertThat(mvc.perform(post("/api/v1/account/password-resets").with(r -> { r.setRemoteAddr(ip); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody@example.com\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("writes are capped per signed-in user, reads are not")
    void writesAreCappedPerUser() throws Exception {
        String token = token("master.kestrel@acme-shipmanagement.example", "198.51.100.5");

        for (int i = 0; i < 3; i++) {
            assertThat(markAllRead(token, "198.51.100.5").getResponse().getStatus()).as("write " + i).isEqualTo(200);
        }
        assertThat(markAllRead(token, "198.51.100.5").getResponse().getStatus()).isEqualTo(429);

        // Reading is never limited, even once writes are cut off.
        for (int i = 0; i < 6; i++) {
            assertThat(mvc.perform(get("/api/v1/notifications/unread-count")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .with(r -> { r.setRemoteAddr("198.51.100.5"); return r; }))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
        }

        // Another user from the same address still writes: the limit follows the account.
        String other = token("d.fernandes@acme-shipmanagement.example", "198.51.100.5");
        assertThat(markAllRead(other, "198.51.100.5").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("the proxy's forwarded address is what counts, not the proxy itself")
    void forwardedAddressIsUsed() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(mvc.perform(post("/api/v1/auth/login").header("X-Forwarded-For", "198.51.100.77, 10.0.0.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("admin@seastella.example", "not-the-password")))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(mvc.perform(post("/api/v1/auth/login").header("X-Forwarded-For", "198.51.100.77, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON).content(body("admin@seastella.example", PASSWORD)))
                .andReturn().getResponse().getStatus()).isEqualTo(429);
        // A different client behind the same proxy is judged on its own address.
        assertThat(mvc.perform(post("/api/v1/auth/login").header("X-Forwarded-For", "198.51.100.78, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON).content(body("admin@seastella.example", PASSWORD)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult markAllRead(String token, String ip) throws Exception {
        return mvc.perform(post("/api/v1/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .with(r -> { r.setRemoteAddr(ip); return r; }))
                .andReturn();
    }

    private MvcResult login(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content(body(email, password)))
                .andReturn();
    }

    private String token(String email, String ip) throws Exception {
        MvcResult r = login(email, PASSWORD, ip);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }

    private String body(String email, String password) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "password", password));
    }
}
