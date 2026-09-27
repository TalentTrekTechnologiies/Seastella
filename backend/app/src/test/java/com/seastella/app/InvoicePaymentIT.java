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
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-payment-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("invoice payment tracking")
class InvoicePaymentIT {

    private static final String PASSWORD = "SeaStella#Demo2026";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext context;

    private String shipManager;
    private String coordinator;
    private String captain;

    @BeforeAll
    void setUp() throws Exception {
        shipManager = token("d.fernandes@acme-shipmanagement.example");
        coordinator = token("coordinator@seastella.example");
        captain = token("master.kestrel@acme-shipmanagement.example");
    }

    @Test
    @DisplayName("terms, half and full payment, overdue alert - and nothing in the workflow is blocked")
    void paymentLifecycle() throws Exception {
        // The seeded invoice awaiting the Ship Manager on one of their vessels.
        Map<String, Object> row = jdbc.queryForMap("""
                select i.id, i.service_request_id, i.amount from invoice i
                join user_vessel_assignment a on a.vessel_id = i.vessel_id
                join app_user u on u.id = a.user_id
                where u.email = 'd.fernandes@acme-shipmanagement.example' and i.status = 'RAISED'
                order by i.id limit 1""");
        long invoiceId = ((Number) row.get("id")).longValue();
        long requestId = ((Number) row.get("service_request_id")).longValue();
        java.math.BigDecimal amount = (java.math.BigDecimal) row.get("amount");
        java.math.BigDecimal half = amount.divide(java.math.BigDecimal.valueOf(2));

        // Before acceptance nothing is owed.
        assertThat(payment(requestId, invoiceId).path("status").asText()).isEqualTo("NOT_DUE");

        body(send(post("/api/v1/invoices/" + invoiceId + "/decision"), shipManager,
                Map.of("decision", "ACCEPT")), 200);

        // The Coordinator sets the terms: half in advance, the balance due yesterday.
        String yesterday = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString();
        body(send(put("/api/v1/invoices/" + invoiceId + "/terms"), coordinator,
                Map.of("advancePercent", 50, "paymentDueDate", yesterday)), 200);
        JsonNode unpaid = payment(requestId, invoiceId);
        assertThat(unpaid.path("status").asText()).isEqualTo("UNPAID");
        assertThat(unpaid.path("advanceReceived").asBoolean()).isFalse();
        assertThat(unpaid.path("overdue").asBoolean()).isTrue();

        // Nothing waits on payment: an engineer is assigned with the advance still unpaid.
        long engineerId = jdbc.queryForObject(
                "select id from app_user where email = 't.okafor@marine-electronics.example'", Long.class);
        Map<String, Object> assign = new HashMap<>();
        assign.put("action", "ASSIGN_ENGINEER");
        assign.put("engineerUserId", engineerId);
        body(send(post("/api/v1/service-requests/" + requestId + "/actions"), coordinator, assign), 200);

        // Half received: the advance is in, the balance is still owed and late.
        body(send(post("/api/v1/invoices/" + invoiceId + "/payments"), coordinator, Map.of(
                "amount", half, "receivedOn", LocalDate.now(java.time.ZoneOffset.UTC).toString(),
                "method", "BANK_TRANSFER", "reference", "UTR 12345", "note", "50% advance")), 201);
        JsonNode part = payment(requestId, invoiceId);
        assertThat(part.path("status").asText()).isEqualTo("PART_PAID");
        assertThat(part.path("advanceReceived").asBoolean()).isTrue();
        assertThat(new java.math.BigDecimal(part.path("balance").asText())).isEqualByComparingTo(amount.subtract(half));
        assertThat(part.path("lines").get(0).path("reference").asText()).isEqualTo("UTR 12345");

        // The nightly scan alerts the Ship Manager and the Coordinator, once.
        Object monitor = context.getBean("invoicePaymentMonitor");
        java.lang.reflect.Method scan = monitor.getClass().getMethod("scan");
        scan.setAccessible(true);   // the monitor is internal to the invoice module
        scan.invoke(monitor);
        scan.invoke(monitor);
        long alerts = jdbc.queryForObject("""
                select count(*) from notification n join app_user u on u.id = n.recipient_user_id
                where n.event_type = 'INVOICE_PAYMENT_OVERDUE' and n.entity_id = ?
                  and u.email in ('d.fernandes@acme-shipmanagement.example', 'coordinator@seastella.example')""",
                Long.class, requestId);
        assertThat(alerts).as("one alert each, not one per scan").isEqualTo(2);

        // More than is owed is refused; the rest clears it.
        assertThat(send(post("/api/v1/invoices/" + invoiceId + "/payments"), coordinator, Map.of(
                "amount", amount, "method", "BANK_TRANSFER")).getResponse().getStatus()).isEqualTo(400);
        body(send(post("/api/v1/invoices/" + invoiceId + "/payments"), coordinator, Map.of(
                "amount", amount.subtract(half), "method", "CHEQUE", "reference", "CHQ 0042")), 201);
        JsonNode paid = payment(requestId, invoiceId);
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        assertThat(paid.path("overdue").asBoolean()).isFalse();

        // A payment entered by mistake comes off again.
        long lastPayment = paid.path("lines").get(1).path("id").asLong();
        body(send(delete("/api/v1/invoices/" + invoiceId + "/payments/" + lastPayment), coordinator, null), 200);
        assertThat(payment(requestId, invoiceId).path("status").asText()).isEqualTo("PART_PAID");

        // The register carries the same figures.
        JsonNode register = body(send(get("/api/v1/invoices"), shipManager, null), 200);
        JsonNode listed = null;
        for (JsonNode i : register.path("invoices")) if (i.path("id").asLong() == invoiceId) listed = i;
        assertThat(listed.path("payment").path("status").asText()).isEqualTo("PART_PAID");
    }

    @Test
    @DisplayName("only the Coordinator records payments, and the Captain never sees money")
    void whoMaySee() throws Exception {
        long invoiceId = jdbc.queryForObject("select min(id) from invoice where status = 'ACCEPTED'", Long.class);
        Map<String, Object> pay = Map.of("amount", 1, "method", "CASH");
        assertThat(send(post("/api/v1/invoices/" + invoiceId + "/payments"), shipManager, pay)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(post("/api/v1/invoices/" + invoiceId + "/payments"), captain, pay)
                .getResponse().getStatus()).isEqualTo(403);

        long requestId = jdbc.queryForObject("select service_request_id from invoice where id = ?", Long.class, invoiceId);
        MvcResult asCaptain = send(get("/api/v1/service-requests/" + requestId), captain, null);
        if (asCaptain.getResponse().getStatus() == 200) {
            assertThat(json.readTree(asCaptain.getResponse().getContentAsString()).path("invoices").size()).isZero();
        }
    }

    private JsonNode payment(long requestId, long invoiceId) throws Exception {
        JsonNode detail = body(send(get("/api/v1/service-requests/" + requestId), coordinator, null), 200);
        for (JsonNode i : detail.path("invoices")) {
            if (i.path("id").asLong() == invoiceId) return i.path("payment");
        }
        throw new AssertionError("invoice " + invoiceId + " not on request " + requestId);
    }

    private MvcResult send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                           String token, Object payload) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (payload != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult r, int expectedStatus) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        String text = r.getResponse().getContentAsString();
        return text.isEmpty() ? null : json.readTree(text);
    }

    private String token(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }
}
