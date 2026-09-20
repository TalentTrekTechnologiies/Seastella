package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seastella.identity.api.AccountEmails;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Onboarding by email invitation, password resets and changing one's own
 * password (IAM-08, IAM-11). Nobody but the account holder ever knows a
 * password, and every link is single-use.
 *
 * <p>No mail server is configured in tests, so the real sender reports
 * NOT_CONFIGURED and the API hands the link back to the administrator - the
 * documented fallback. The SENT path is exercised by stubbing the sender.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "seastella.app-base-url=https://ops.seastella.test",
        "spring.datasource.url=jdbc:h2:mem:seastella-onboarding-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DisplayName("account onboarding and password resets")
class AccountOnboardingIT {

    private static final String SEED_PASSWORD = "SeaStella#Demo2026";
    private static final String COOKIE = "seastella_refresh";
    private static final String CHOSEN = "harbour lights at dawn";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private AccountEmails emails;

    // ------------------------------------------------------------ invitations

    @Test
    @DisplayName("a new account is invited: no password is shown, and it cannot sign in yet")
    void creatingInvites() throws Exception {
        String email = unique("th");
        JsonNode created = createTechnicalHead(email);

        assertThat(created.path("user").path("status").asText()).isEqualTo("INVITED");
        assertThat(created.path("email").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(created.path("link").asText()).startsWith("https://ops.seastella.test/invite/");
        assertThat(created.has("temporaryPassword")).isFalse();
        // Only a hash of the link is stored.
        assertThat(jdbc.queryForObject("select count(*) from user_token t join app_user u on u.id = t.user_id "
                + "where u.email = ? and t.token_hash = ?", Integer.class, email, sha256(token(created)))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from user_token where token_hash = ?",
                Integer.class, token(created))).isZero();

        assertThat(login(email, CHOSEN).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("accepting sets the person's own password, activates the account and signs them in")
    void acceptingActivates() throws Exception {
        String email = unique("th");
        String token = token(createTechnicalHead(email));

        JsonNode pending = body(mvc.perform(get("/api/v1/account/invitations/" + token)).andReturn());
        assertThat(pending.path("email").asText()).isEqualTo(email);
        assertThat(pending.path("roleLabel").asText()).isNotBlank();

        assertThat(accept(token, "short").getResponse().getStatus()).isEqualTo(400);

        MvcResult accepted = accept(token, CHOSEN);
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(accepted.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith(COOKIE + "=").contains("HttpOnly");
        assertThat(body(accepted).path("accessToken").asText()).isNotBlank();
        assertThat(status(email)).isEqualTo("ACTIVE");

        assertThat(login(email, CHOSEN).getResponse().getStatus()).isEqualTo(200);
        // Single use.
        assertThat(accept(token, "another long passphrase").getResponse().getStatus()).isEqualTo(410);
        assertThat(mvc.perform(get("/api/v1/account/invitations/" + token)).andReturn().getResponse().getStatus())
                .isEqualTo(410);
    }

    @Test
    @DisplayName("resending an invitation retires the earlier link")
    void resendRetiresEarlierLink() throws Exception {
        String email = unique("th");
        JsonNode created = createTechnicalHead(email);
        String first = token(created);

        JsonNode resent = body(mvc.perform(post("/api/v1/users/" + created.path("user").path("id").asLong() + "/invitation")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))).andReturn());
        String second = token(resent);

        assertThat(second).isNotEqualTo(first);
        assertThat(accept(first, CHOSEN).getResponse().getStatus()).isEqualTo(410);
        assertThat(accept(second, CHOSEN).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("an invited account cannot be switched to active by an administrator")
    void invitedCannotBeActivatedDirectly() throws Exception {
        JsonNode created = createTechnicalHead(unique("th"));
        long id = created.path("user").path("id").asLong();

        assertThat(setStatus(id, "ACTIVE").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("suspending an invited account kills its link, and reactivating returns it to invited")
    void suspendingInvitedRevokesLink() throws Exception {
        String email = unique("th");
        JsonNode created = createTechnicalHead(email);
        long id = created.path("user").path("id").asLong();
        String token = token(created);

        assertThat(setStatus(id, "SUSPENDED").getResponse().getStatus()).isEqualTo(200);
        assertThat(accept(token, CHOSEN).getResponse().getStatus()).isEqualTo(410);

        JsonNode reactivated = body(setStatus(id, "ACTIVE"));
        assertThat(reactivated.path("status").asText()).isEqualTo("INVITED");
    }

    @Test
    @DisplayName("when the email reaches the mail server, the link is not returned to the administrator")
    void sentEmailHidesLink() throws Exception {
        doReturn(AccountEmails.Delivery.SENT).when(emails)
                .sendInvitation(any(), anyString(), anyString(), anyString(), any());

        JsonNode created = createTechnicalHead(unique("th"));

        assertThat(created.path("email").asText()).isEqualTo("SENT");
        assertThat(created.path("link").isNull() || created.path("link").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("the delivery log records the invitation without its link")
    void deliveryLogOmitsLink() throws Exception {
        String email = unique("th");
        String token = token(createTechnicalHead(email));

        Integer logged = jdbc.queryForObject("""
                select count(*) from notification_delivery d join notification n on n.id = d.notification_id
                where d.address = ? and n.event_type = 'ACCOUNT_INVITATION' and d.status = 'SKIPPED'
                  and n.in_app = false
                """, Integer.class, email);
        assertThat(logged).isEqualTo(1);
        Integer leaked = jdbc.queryForObject(
                "select count(*) from notification where body like ? or title like ?", Integer.class,
                "%" + token + "%", "%" + token + "%");
        assertThat(leaked).isZero();
    }

    // --------------------------------------------------------- password resets

    @Test
    @DisplayName("an administrator's reset sends a link; the old password works until it is used")
    void adminResetFlow() throws Exception {
        String email = unique("th");
        activate(email);
        String oldSession = cookie(login(email, CHOSEN));
        long id = userId(email);

        JsonNode reset = body(mvc.perform(post("/api/v1/users/" + id + "/password-reset")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))).andReturn());
        String token = token(reset);
        assertThat(reset.path("link").asText()).contains("/reset/");
        assertThat(login(email, CHOSEN).getResponse().getStatus()).isEqualTo(200);

        String renewed = "a completely new passphrase";
        MvcResult done = mvc.perform(post("/api/v1/account/password-resets/" + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Password(renewed))))
                .andReturn();
        assertThat(done.getResponse().getStatus()).isEqualTo(200);

        assertThat(login(email, CHOSEN).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(email, renewed).getResponse().getStatus()).isEqualTo(200);
        // Every session from before the reset has ended.
        assertThat(refresh(oldSession).getResponse().getStatus()).isEqualTo(401);
        // Single use.
        assertThat(mvc.perform(post("/api/v1/account/password-resets/" + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Password("yet another passphrase"))))
                .andReturn().getResponse().getStatus()).isEqualTo(410);
    }

    @Test
    @DisplayName("an invited account is re-invited, not reset")
    void resetRefusedForInvited() throws Exception {
        long id = createTechnicalHead(unique("th")).path("user").path("id").asLong();

        assertThat(mvc.perform(post("/api/v1/users/" + id + "/password-reset")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))).andReturn().getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("forgot password answers 202 whether or not the address has an account")
    void forgotPasswordRevealsNothing() throws Exception {
        String email = unique("th");
        activate(email);

        assertThat(forgot("nobody-" + UUID.randomUUID() + "@example.com").getResponse().getStatus()).isEqualTo(202);
        assertThat(forgot(email).getResponse().getStatus()).isEqualTo(202);

        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emails, timeout(5000)).sendPasswordReset(any(), link.capture(), any());
        assertThat(link.getValue()).startsWith("https://ops.seastella.test/reset/");

        String token = link.getValue().substring(link.getValue().lastIndexOf('/') + 1);
        JsonNode pending = body(mvc.perform(get("/api/v1/account/password-resets/" + token)).andReturn());
        assertThat(pending.path("email").asText()).isEqualTo(email);
    }

    @Test
    @DisplayName("forgot password sends nothing for a suspended account")
    void forgotPasswordIgnoresSuspended() throws Exception {
        String email = unique("th");
        activate(email);
        jdbc.update("update app_user set status = 'SUSPENDED' where email = ?", email);

        assertThat(forgot(email).getResponse().getStatus()).isEqualTo(202);
        verify(emails, after(1500).never()).sendPasswordReset(any(), anyString(), any());
    }

    @Test
    @DisplayName("forgot password cannot be used to flood an inbox: three emails an hour per account")
    void forgotPasswordIsThrottled() throws Exception {
        String email = unique("th");
        activate(email);

        for (int i = 1; i <= 3; i++) {
            forgot(email);
            verify(emails, timeout(5000).times(i)).sendPasswordReset(any(), anyString(), any());
        }
        assertThat(forgot(email).getResponse().getStatus()).isEqualTo(202);
        verify(emails, after(1500).times(3)).sendPasswordReset(any(), anyString(), any());
    }

    // ----------------------------------------------------------- own password

    @Test
    @DisplayName("changing one's own password needs the current one and ends other sessions")
    void changeOwnPassword() throws Exception {
        String email = unique("th");
        activate(email);
        MvcResult first = login(email, CHOSEN);
        String otherDevice = cookie(login(email, CHOSEN));
        String access = body(first).path("accessToken").asText();

        assertThat(changePassword(access, "not my password", "a fresh long passphrase").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(changePassword(access, CHOSEN, CHOSEN).getResponse().getStatus()).isEqualTo(400);

        MvcResult changed = changePassword(access, CHOSEN, "a fresh long passphrase");
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(changed.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith(COOKIE + "=");

        assertThat(refresh(otherDevice).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(cookie(changed)).getResponse().getStatus()).isEqualTo(200);
        assertThat(login(email, "a fresh long passphrase").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("changing a password requires being signed in")
    void changePasswordNeedsAuthentication() throws Exception {
        assertThat(mvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"y\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- helpers

    private record Password(String password) {}

    private record PasswordChange(String currentPassword, String newPassword) {}

    private JsonNode createTechnicalHead(String email) throws Exception {
        Long organizationId = jdbc.queryForObject(
                "select organization_id from app_user where email = 'tech.head@acme-shipmanagement.example'", Long.class);
        String request = json.writeValueAsString(java.util.Map.of(
                "fullName", "Invited Person", "email", email, "role", "TECHNICAL_HEAD",
                "organizationId", organizationId));
        MvcResult r = mvc.perform(post("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r);
    }

    /** Invites and accepts, leaving an active account whose password is {@link #CHOSEN}. */
    private void activate(String email) throws Exception {
        assertThat(accept(token(createTechnicalHead(email)), CHOSEN).getResponse().getStatus()).isEqualTo(200);
    }

    private MvcResult accept(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/account/invitations/" + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Password(password))))
                .andReturn();
    }

    private MvcResult setStatus(long id, String status) throws Exception {
        return mvc.perform(post("/api/v1/users/" + id + "/status").header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}")).andReturn();
    }

    private MvcResult forgot(String email) throws Exception {
        return mvc.perform(post("/api/v1/account/password-resets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andReturn();
    }

    private MvcResult changePassword(String access, String current, String next) throws Exception {
        return mvc.perform(post("/api/v1/auth/password").header(HttpHeaders.AUTHORIZATION, bearer(access))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new PasswordChange(current, next)))).andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", password)))).andReturn();
    }

    private MvcResult refresh(String cookie) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, cookie))
                .header("X-Requested-With", "SeaStella")).andReturn();
    }

    private String adminToken() throws Exception {
        return body(login("admin@seastella.example", SEED_PASSWORD)).path("accessToken").asText();
    }

    private String status(String email) {
        return jdbc.queryForObject("select status from app_user where email = ?", String.class, email);
    }

    private long userId(String email) {
        return jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
    }

    private static String token(JsonNode linkSent) {
        String link = linkSent.path("link").asText();
        assertThat(link).as("link in response").isNotBlank();
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private static String sha256(String raw) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@acme-shipmanagement.example";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static String cookie(MvcResult r) {
        String header = r.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).as("Set-Cookie").isNotNull();
        String pair = header.split(";", 2)[0];
        return pair.substring(pair.indexOf('=') + 1);
    }
}
