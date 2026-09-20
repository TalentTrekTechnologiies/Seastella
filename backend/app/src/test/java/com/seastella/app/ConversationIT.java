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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The conversation on a request (SoW §6.1; CHT-04, CHT-07 to CHT-10).
 *
 * <p>What is proved here is that there is <em>one</em> thread: the guided
 * checks, the live chat, the attachments and the platform's own notes are the
 * same transcript in the same order, with a status that says where it is. Then
 * the three things a transcript needs to be usable — knowing what you have not
 * read, sending a photograph, and finding a word again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "seastella.upload.max-bytes=8192",
        "seastella.upload.storage-dir=./target/test-conversation",
        "spring.datasource.url=jdbc:h2:mem:seastella-conversation-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("the conversation on a request")
class ConversationIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String CAPTAIN = "master.kestrel@acme-shipmanagement.example";
    private static final String COORDINATOR = "coordinator@seastella.example";
    private static final String OTHER_CAPTAIN = "master.bergen@nordic-tanker.example";

    /** A one-pixel PNG: what matters is that it starts with the PNG signature. */
    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    /** MZ: a Windows executable, whatever it is called. */
    private static final byte[] EXECUTABLE = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String captain;
    private String coordinator;
    private String otherCaptain;

    @BeforeAll
    void setUp() throws Exception {
        captain = token(CAPTAIN);
        coordinator = token(COORDINATOR);
        otherCaptain = token(OTHER_CAPTAIN);
    }

    @Test
    @DisplayName("guided checks, escalation and the live chat are one thread (CHT-04, CHT-10)")
    void oneThread() throws Exception {
        long requestId = raise("Gyro heading drifting", "Heading wanders a degree either side.");

        // Before anything is said there is no thread, and nothing to write in.
        JsonNode empty = body(getJson("/api/v1/service-requests/" + requestId + "/conversation", captain), 200);
        assertThat(empty.path("status").asText()).isEqualTo("NONE");
        assertThat(empty.path("canSend").asBoolean()).isFalse();

        // The guided checks open it, and ask their first question in it.
        runChecks(requestId);
        JsonNode afterChecks = body(getJson("/api/v1/service-requests/" + requestId + "/conversation", captain), 200);
        assertThat(afterChecks.path("status").asText()).isEqualTo("ASSISTANT");
        assertThat(afterChecks.path("canSend").asBoolean()).as("no live agent yet").isFalse();

        List<String> kinds = kinds(afterChecks);
        assertThat(kinds).startsWith("SYSTEM", "ASSISTANT", "USER");
        assertThat(kinds).contains("ASSISTANT", "USER");
        assertThat(text(afterChecks)).contains("Guided checks started").contains("Findings recorded");

        // Escalation moves the same thread to LIVE; it does not start a new one.
        act(captain, requestId, "ESCALATE_TO_LIVE_AGENT");
        JsonNode live = body(getJson("/api/v1/service-requests/" + requestId + "/conversation", captain), 200);
        assertThat(live.path("status").asText()).isEqualTo("LIVE");
        assertThat(live.path("canSend").asBoolean()).isTrue();
        assertThat(live.hasNonNull("escalatedAt")).isTrue();
        assertThat(live.hasNonNull("openedAt")).isTrue();
        assertThat(live.path("messages").size()).isGreaterThan(afterChecks.path("messages").size());
        assertThat(jdbc.queryForObject(
                "select count(*) from conversation where service_request_id = ?", Integer.class, requestId))
                .as("one conversation, never two").isEqualTo(1);

        // Both sides write in it, and the transcript stays in order.
        body(postJson("/api/v1/service-requests/" + requestId + "/conversation/messages", captain,
                Map.of("body", "Gyro is drifting again after the restart.")), 201);
        body(postJson("/api/v1/service-requests/" + requestId + "/conversation/messages", coordinator,
                Map.of("body", "Understood. Check the follow-up gearing before we send anyone.")), 201);

        JsonNode both = body(getJson("/api/v1/service-requests/" + requestId + "/conversation", coordinator), 200);
        assertThat(text(both)).contains("Gyro is drifting again").contains("follow-up gearing");

        // Moving the request on closes the thread and says so, keeping the transcript.
        act(captain, requestId, "SUBMIT_FOR_APPROVAL");
        JsonNode closed = body(getJson("/api/v1/service-requests/" + requestId + "/conversation", captain), 200);
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.path("canSend").asBoolean()).isFalse();
        assertThat(text(closed)).contains("Chat closed").contains("Gyro is drifting again");

        // A closed chat refuses new lines rather than silently dropping them.
        assertThat(postJson("/api/v1/service-requests/" + requestId + "/conversation/messages", captain,
                Map.of("body", "One more thing")).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("unread is counted per person and never moves backwards (CHT-07)")
    void readState() throws Exception {
        long requestId = escalatedRequest("Radar picture breaking up");

        body(postJson("/api/v1/service-requests/" + requestId + "/conversation/messages", captain,
                Map.of("body", "Picture breaks up above twelve miles.")), 201);

        // The Coordinator has read none of it.
        JsonNode unread = body(getJson("/api/v1/service-requests/" + requestId + "/conversation", coordinator), 200);
        long total = unread.path("messages").size();
        assertThat(unread.path("unreadCount").asLong()).isEqualTo(total);
        assertThat(unread.hasNonNull("lastReadMessageId")).as("nothing read yet").isFalse();

        long latest = unread.path("messages").get((int) total - 1).path("id").asLong();
        long earlier = unread.path("messages").get(0).path("id").asLong();

        JsonNode read = body(postJson("/api/v1/service-requests/" + requestId + "/conversation/read", coordinator,
                Map.of("lastMessageId", latest)), 200);
        assertThat(read.path("unreadCount").asLong()).isZero();

        // Scrolling back up does not un-read what has already been seen.
        JsonNode again = body(postJson("/api/v1/service-requests/" + requestId + "/conversation/read", coordinator,
                Map.of("lastMessageId", earlier)), 200);
        assertThat(again.path("lastReadMessageId").asLong()).isEqualTo(latest);
        assertThat(again.path("unreadCount").asLong()).isZero();

        // The Captain's own count is their own, and they can see how far the
        // other side has read.
        JsonNode captainView = body(getJson("/api/v1/service-requests/" + requestId + "/conversation", captain), 200);
        assertThat(captainView.path("unreadCount").asLong()).isGreaterThan(0);
        assertThat(captainView.path("readByOthersMessageId").asLong()).isEqualTo(latest);

        // A message id from another conversation cannot mark this one read.
        long otherRequest = escalatedRequest("Speed log reading high");
        long elsewhere = body(getJson("/api/v1/service-requests/" + otherRequest + "/conversation", captain), 200)
                .path("messages").get(0).path("id").asLong();
        assertThat(postJson("/api/v1/service-requests/" + requestId + "/conversation/read", coordinator,
                Map.of("lastMessageId", elsewhere)).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("a photograph is sent in the chat and stored like any document (CHT-08)")
    void attachments() throws Exception {
        long requestId = escalatedRequest("Antenna mount cracked");

        JsonNode sent = body(attach(captain, requestId, PNG, "mount.png", "Crack at the base of the mount"), 201);
        assertThat(sent.path("body").asText()).isEqualTo("Crack at the base of the mount");
        assertThat(sent.path("attachment").path("image").asBoolean()).isTrue();
        assertThat(sent.path("attachment").path("contentType").asText()).isEqualTo("image/png");
        long documentId = sent.path("attachment").path("documentId").asLong();

        // It is a document on this request, audited like every other upload.
        assertThat(jdbc.queryForObject(
                "select owner_type from document where id = ?", String.class, documentId))
                .isEqualTo("SERVICE_REQUEST");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_entry where action = 'DOCUMENT_UPLOADED' and entity_id = ?",
                Integer.class, documentId)).isEqualTo(1);

        // The Coordinator sees it in the thread and can open the file itself.
        JsonNode thread = body(getJson("/api/v1/service-requests/" + requestId + "/conversation", coordinator), 200);
        JsonNode last = thread.path("messages").get(thread.path("messages").size() - 1);
        assertThat(last.path("attachment").path("documentId").asLong()).isEqualTo(documentId);
        assertThat(mvc.perform(get("/api/v1/documents/" + documentId + "/content")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + coordinator)).andReturn()
                .getResponse().getStatus()).isEqualTo(200);

        // A file is judged by its bytes, not its name (SEC-15).
        assertThat(attach(captain, requestId, EXECUTABLE, "photo.png", null).getResponse().getStatus()).isEqualTo(400);

        // Another fleet's Captain cannot reach the file or the thread.
        assertThat(mvc.perform(get("/api/v1/documents/" + documentId + "/content")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherCaptain)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(getJson("/api/v1/service-requests/" + requestId + "/conversation", otherCaptain)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a word is found again inside one thread (CHT-09)")
    void search() throws Exception {
        long requestId = escalatedRequest("Echo sounder reading zero");
        body(postJson("/api/v1/service-requests/" + requestId + "/conversation/messages", captain,
                Map.of("body", "The transducer cable looks chafed where it enters the trunk.")), 201);
        body(postJson("/api/v1/service-requests/" + requestId + "/conversation/messages", coordinator,
                Map.of("body", "Send a photograph of the cable and we will order a replacement.")), 201);

        JsonNode hits = body(getJson("/api/v1/service-requests/" + requestId
                + "/conversation/search?q=transducer", coordinator), 200);
        assertThat(hits.size()).isEqualTo(1);
        assertThat(hits.get(0).path("body").asText()).contains("transducer");

        // Case does not matter, and a word that is not there finds nothing.
        assertThat(body(getJson("/api/v1/service-requests/" + requestId
                + "/conversation/search?q=CABLE", coordinator), 200).size()).isEqualTo(2);
        assertThat(body(getJson("/api/v1/service-requests/" + requestId
                + "/conversation/search?q=azimuth", coordinator), 200).size()).isZero();

        // One character would return the whole transcript; that is not a search.
        assertThat(getJson("/api/v1/service-requests/" + requestId + "/conversation/search?q=a", coordinator)
                .getResponse().getStatus()).isEqualTo(400);

        // Search reaches only this request, like every other read.
        assertThat(getJson("/api/v1/service-requests/" + requestId + "/conversation/search?q=cable", otherCaptain)
                .getResponse().getStatus()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- helpers

    /** Raises a request on the Captain's own vessel and returns its id. */
    private long raise(String title, String description) throws Exception {
        // Equipment that has published guided checks: every test here runs them.
        long spareId = jdbc.queryForObject("""
                select s.id from spare s
                join user_vessel_assignment a on a.vessel_id = s.vessel_id
                join app_user u on u.id = a.user_id
                where u.email = ?
                  and s.id not in (select spare_id from service_request)
                  and s.equipment_category_id in (
                      select equipment_category_id from troubleshooting_flow where status = 'PUBLISHED')
                order by s.id limit 1
                """, Long.class, CAPTAIN);
        return body(postJson("/api/v1/service-requests", captain, Map.of(
                "spareId", spareId, "title", title, "description", description, "priority", "HIGH")), 201)
                .path("request").path("id").asLong();
    }

    /** A request whose guided checks are done and which is with a live agent. */
    private long escalatedRequest(String title) throws Exception {
        long requestId = raise(title, "Raised for the conversation tests.");
        runChecks(requestId);
        act(captain, requestId, "ESCALATE_TO_LIVE_AGENT");
        return requestId;
    }

    /** Starts the checks, answers through to an outcome, and records findings. */
    private void runChecks(long requestId) throws Exception {
        JsonNode view = body(postJson("/api/v1/service-requests/" + requestId + "/troubleshooting", captain,
                Map.of()), 200);
        for (int answered = 0; answered < 20; answered++) {
            JsonNode step = view.path("session").path("currentStep");
            if (step.isMissingNode() || step.isNull()) break;
            view = body(postJson("/api/v1/service-requests/" + requestId + "/troubleshooting/answers", captain,
                    Map.of("stepId", step.path("id").asLong(), "yes", false)), 200);
        }
        body(postJson("/api/v1/service-requests/" + requestId + "/troubleshooting/completion", captain, Map.of(
                "rootCauseNote", "Nothing found on board.",
                "temporaryFixNote", "Watch kept on the standby unit.")), 200);
    }

    private List<String> kinds(JsonNode conversation) {
        List<String> kinds = new ArrayList<>();
        conversation.path("messages").forEach(m -> kinds.add(m.path("kind").asText()));
        return kinds;
    }

    private String text(JsonNode conversation) {
        StringBuilder all = new StringBuilder();
        conversation.path("messages").forEach(m -> all.append(m.path("body").asText()).append('\n'));
        return all.toString();
    }

    private void act(String token, long requestId, String action) throws Exception {
        body(postJson("/api/v1/service-requests/" + requestId + "/actions", token, Map.of("action", action)), 200);
    }

    private MvcResult attach(String token, long requestId, byte[] content, String fileName, String caption)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request =
                multipart("/api/v1/service-requests/" + requestId + "/conversation/attachments")
                        .file(new MockMultipartFile("file", fileName, null, content));
        if (caption != null) request.param("caption", caption);
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private MvcResult postJson(String path, String token, Object payload) throws Exception {
        return mvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    private MvcResult getJson(String path, String token) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
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
