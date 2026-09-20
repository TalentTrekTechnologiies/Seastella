package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Documents and certificates (SoW s7; DOC-01 to DOC-07, SEC-15 to SEC-17,
 * security tests S-40 to S-43).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "seastella.upload.max-bytes=4096",
        "seastella.upload.storage-dir=./target/test-documents",
        "spring.datasource.url=jdbc:h2:mem:seastella-document-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("documents and certificates")
class DocumentIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String HEAD = "tech.head@acme-shipmanagement.example";
    private static final String CAPTAIN = "master.kestrel@acme-shipmanagement.example";

    /** A minimal but genuine PDF: what matters is that it starts %PDF. */
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);
    /** MZ: a Windows executable, whatever it is called. */
    private static final byte[] EXECUTABLE = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String head;
    private String captain;
    private long vesselId;
    private long spareId;
    private long otherVesselSpareId;

    @BeforeAll
    void setUp() throws Exception {
        head = token(HEAD);
        captain = token(CAPTAIN);
        Map<String, Object> own = jdbc.queryForMap("""
                select s.id as spare_id, s.vessel_id from spare s
                join user_vessel_assignment a on a.vessel_id = s.vessel_id
                join app_user u on u.id = a.user_id
                where u.email = ? order by s.id limit 1
                """, CAPTAIN);
        spareId = ((Number) own.get("spare_id")).longValue();
        vesselId = ((Number) own.get("vessel_id")).longValue();
        otherVesselSpareId = jdbc.queryForObject("""
                select s.id from spare s
                where s.vessel_id <> ? and s.vessel_id in (
                    select v.id from vessel v where v.organization_id <> (
                        select organization_id from app_user where email = ?))
                order by s.id limit 1
                """, Long.class, vesselId, HEAD);
    }

    @Test
    @DisplayName("a manual is attached to a spare, listed, and downloaded as an attachment")
    void attachAndDownload() throws Exception {
        JsonNode created = body(upload(head, PDF, "radar-manual.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(spareId),
                "documentType", "MANUAL", "title", "Radar operating manual")), 201);
        assertThat(created.path("contentType").asText()).isEqualTo("application/pdf");
        assertThat(created.path("uploadedBy").asText()).isNotBlank();
        assertThat(created.path("current").asBoolean()).isTrue();

        JsonNode listed = body(mvc.perform(get("/api/v1/documents?ownerType=SPARE&ownerId=" + spareId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn(), 200);
        assertThat(listed.findValuesAsText("title")).contains("Radar operating manual");

        MvcResult download = mvc.perform(get("/api/v1/documents/" + created.path("id").asLong() + "/content")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + captain)).andReturn();
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment", "radar-manual.pdf");
        assertThat(download.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(PDF);

        assertThat(jdbc.queryForObject("select count(*) from audit_entry where action = 'DOCUMENT_UPLOADED'",
                Integer.class)).isPositive();
    }

    @Test
    @DisplayName("S-41/S-42: what a file claims to be is ignored; the bytes decide")
    void refusesDisguisedAndOversizeFiles() throws Exception {
        MvcResult disguised = upload(head, EXECUTABLE, "invoice.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(spareId), "title", "Not really a PDF"));
        assertThat(disguised.getResponse().getStatus()).isEqualTo(400);
        assertThat(disguised.getResponse().getContentAsString()).contains("not accepted");

        byte[] oversize = new byte[5_000];
        System.arraycopy(PDF, 0, oversize, 0, PDF.length);
        MvcResult tooBig = upload(head, oversize, "big.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(spareId), "title", "Too large"));
        assertThat(tooBig.getResponse().getStatus()).isEqualTo(400);

        MvcResult empty = upload(head, new byte[0], "empty.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(spareId), "title", "Empty"));
        assertThat(empty.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("S-43: the stored file is never the uploaded name, and never leaves the storage directory")
    void storedSafely() throws Exception {
        JsonNode created = body(upload(head, PDF, "../../etc/passwd.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(spareId), "title", "Odd name")), 201);
        assertThat(created.path("fileName").asText()).isEqualTo("passwd.pdf");

        String key = jdbc.queryForObject("select storage_key from document where id = ?", String.class,
                created.path("id").asLong());
        assertThat(key).matches("\\d{4}/\\d{2}/[0-9a-f]{32}");
        Path stored = Path.of("./target/test-documents").toAbsolutePath().normalize().resolve(key);
        assertThat(Files.exists(stored)).isTrue();
    }

    @Test
    @DisplayName("S-40: a document on another client's vessel is not there at all")
    void scopedToTheFleet() throws Exception {
        MvcResult refused = upload(head, PDF, "other.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(otherVesselSpareId), "title", "Another client"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(404);

        JsonNode mine = body(upload(head, PDF, "mine.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(spareId), "title", "Ours")), 201);
        String otherCaptain = token("master.coral@acme-shipmanagement.example");
        assertThat(mvc.perform(get("/api/v1/documents/" + mine.path("id").asLong() + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherCaptain))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a certificate needs an expiry date, and replacing one keeps the old record")
    void certificateHistory() throws Exception {
        MvcResult noExpiry = upload(head, PDF, "cert.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(spareId),
                "documentType", "CERTIFICATE", "title", "Radar type approval"));
        assertThat(noExpiry.getResponse().getStatus()).isEqualTo(400);

        JsonNode first = body(upload(head, PDF, "cert-2026.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(spareId), "documentType", "CERTIFICATE",
                "title", "Radar type approval", "certificateNumber", "RTA-1",
                "issuedDate", LocalDate.now().minusYears(1).toString(),
                "expiryDate", LocalDate.now().plusDays(40).toString())), 201);
        assertThat(first.path("daysToExpiry").asInt()).isEqualTo(40);

        JsonNode replacement = body(upload(head, PDF, "cert-2027.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(spareId), "documentType", "CERTIFICATE",
                "title", "Radar type approval", "certificateNumber", "RTA-2",
                "expiryDate", LocalDate.now().plusYears(1).toString(),
                "supersedesId", String.valueOf(first.path("id").asLong()))), 201);

        JsonNode current = body(mvc.perform(get("/api/v1/documents?ownerType=SPARE&ownerId=" + spareId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn(), 200);
        List<String> currentNumbers = current.findValuesAsText("certificateNumber");
        assertThat(currentNumbers).contains("RTA-2").doesNotContain("RTA-1");

        JsonNode withHistory = body(mvc.perform(get("/api/v1/documents?ownerType=SPARE&ownerId=" + spareId + "&history=true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn(), 200);
        assertThat(withHistory.findValuesAsText("certificateNumber")).contains("RTA-1", "RTA-2");
        assertThat(replacement.path("id").asLong()).isEqualTo(
                jdbc.queryForObject("select superseded_by_id from document where id = ?", Long.class,
                        first.path("id").asLong()));
    }

    @Test
    @DisplayName("removing keeps the record and the trail")
    void removeIsSoft() throws Exception {
        JsonNode created = body(upload(head, PDF, "old.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(spareId), "title", "Superseded manual")), 201);
        long id = created.path("id").asLong();

        assertThat(mvc.perform(delete("/api/v1/documents/" + id + "?reason=Replaced by the 2026 edition")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn().getResponse().getStatus())
                .isEqualTo(204);
        assertThat(jdbc.queryForObject("select count(*) from document where id = ? and removed_at is not null",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where action = 'DOCUMENT_DELETED'",
                Integer.class)).isPositive();

        // Removed documents are out of the list, but the row and the file remain.
        JsonNode listed = body(mvc.perform(get("/api/v1/documents?ownerType=SPARE&ownerId=" + spareId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn(), 200);
        assertThat(listed.findValuesAsText("title")).doesNotContain("Superseded manual");
    }

    @Test
    @DisplayName("a Captain files what is on their own equipment, but not fleet paperwork")
    void captainMayAttachToSpares() throws Exception {
        assertThat(upload(captain, PDF, "photo-note.pdf", Map.of(
                "ownerType", "SPARE", "ownerId", String.valueOf(spareId), "documentType", "PHOTO",
                "title", "Display fault")).getResponse().getStatus()).isEqualTo(201);

        assertThat(upload(captain, PDF, "class.pdf", Map.of(
                "ownerType", "VESSEL", "ownerId", String.valueOf(vesselId), "documentType", "CERTIFICATE",
                "title", "Class certificate", "expiryDate", LocalDate.now().plusDays(90).toString()))
                .getResponse().getStatus()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult upload(String token, byte[] content, String fileName, Map<String, String> fields) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", fileName, null, content));
        fields.forEach(request::param);
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private JsonNode body(MvcResult r, int expectedStatus) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String token(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }
}
