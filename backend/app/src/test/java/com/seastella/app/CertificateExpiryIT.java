package com.seastella.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Certificate expiry reminders (SoW s7 and s11; NOT-11, MNT-13, DOC-03).
 *
 * <p>The scan is put on a one-second schedule here rather than called directly,
 * so what the test exercises is the same wiring that runs nightly in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "seastella.upload.storage-dir=./target/test-certificates",
        "seastella.notification.certificate.warning-days=90,30,7,0",
        "seastella.notification.certificate.scan-cron=*/1 * * * * *",
        "spring.datasource.url=jdbc:h2:mem:seastella-certificate-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DisplayName("certificate expiry reminders")
class CertificateExpiryIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String HEAD = "tech.head@acme-shipmanagement.example";
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("a certificate close to expiry alerts the vessel's people, once per threshold")
    void remindsOncePerThreshold() throws Exception {
        String head = token(HEAD);
        Map<String, Object> target = jdbc.queryForMap("""
                select s.id as spare_id, s.vessel_id from spare s
                join user_vessel_assignment a on a.vessel_id = s.vessel_id
                join app_user u on u.id = a.user_id and u.role = 'CAPTAIN'
                join app_user th on th.organization_id = (select organization_id from app_user where email = ?)
                where th.email = ?
                order by s.id limit 1
                """, HEAD, HEAD);
        long spareId = ((Number) target.get("spare_id")).longValue();
        long vesselId = ((Number) target.get("vessel_id")).longValue();

        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "radio-licence.pdf", null, PDF));
        request.param("ownerType", "SPARE").param("ownerId", String.valueOf(spareId))
                .param("documentType", "CERTIFICATE").param("title", "Ship radio licence")
                .param("certificateNumber", "SRL-2026")
                .param("expiryDate", LocalDate.now().plusDays(25).toString());
        MvcResult uploaded = mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn();
        assertThat(uploaded.getResponse().getStatus()).as(uploaded.getResponse().getContentAsString()).isEqualTo(201);

        int alerts = awaitAtLeastOneAlert();
        assertThat(alerts).as("one alert per person in scope").isPositive();

        // The people told are the vessel's Captain and Ship Manager, and the
        // organization's Technical Head - the SoW s11 shape.
        List<String> roles = jdbc.queryForList("""
                select distinct u.role from notification n
                join app_user u on u.id = n.recipient_user_id
                where n.event_type = 'CERTIFICATE_EXPIRING'
                """, String.class);
        assertThat(roles).contains("CAPTAIN", "SHIP_MANAGER", "TECHNICAL_HEAD");

        String body = jdbc.queryForObject("""
                select body from notification where event_type = 'CERTIFICATE_EXPIRING' order by id limit 1
                """, String.class);
        assertThat(body).contains("Ship radio licence", "SRL-2026").contains("25 days from now");
        assertThat(jdbc.queryForObject("select count(*) from notification where event_type = 'CERTIFICATE_EXPIRING' "
                + "and vessel_id = ?", Integer.class, vesselId)).isEqualTo(alerts);

        // The scan runs again every second; the same threshold must not alert twice.
        Thread.sleep(2_500);
        assertThat(jdbc.queryForObject("select count(*) from notification where event_type = 'CERTIFICATE_EXPIRING'",
                Integer.class)).isEqualTo(alerts);
        assertThat(jdbc.queryForObject("select last_threshold_days from certificate_alert_state "
                + "order by id desc limit 1", Integer.class)).isEqualTo(30);
    }

    private int awaitAtLeastOneAlert() throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from notification where event_type = 'CERTIFICATE_EXPIRING'", Integer.class);
            if (count != null && count > 0) {
                // Let the scan finish writing every recipient before counting.
                Thread.sleep(500);
                return jdbc.queryForObject(
                        "select count(*) from notification where event_type = 'CERTIFICATE_EXPIRING'", Integer.class);
            }
            Thread.sleep(250);
        }
        return 0;
    }

    private String token(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }
}
