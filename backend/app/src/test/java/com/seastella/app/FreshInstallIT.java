package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A new installation as the VPS runs it: no demo data, one Platform Admin from
 * the bootstrap settings, and the reference data the migrations provide. Every
 * screen the first admin opens must work on that, not only on the seed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.bootstrap.admin-email=first.admin@thawe.example",
        "seastella.bootstrap.admin-password=Harbour-Lantern-2026",
        "spring.datasource.url=jdbc:h2:mem:seastella-fresh-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DisplayName("a fresh installation, without demo data")
class FreshInstallIT {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("the bootstrap admin signs in and the settings screens load")
    void firstAdminScreens() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "email", "first.admin@thawe.example", "password", "Harbour-Lantern-2026")))).andReturn();
        assertThat(login.getResponse().getStatus()).as("the bootstrap admin exists").isEqualTo(200);
        String token = json.readTree(login.getResponse().getContentAsString()).path("accessToken").asText();

        // The Maintenance bands page: the platform default bands come from the migrations.
        JsonNode bands = ok(token, "/api/v1/maintenance/thresholds");
        List<String> codes = new ArrayList<>();
        bands.forEach(b -> codes.add(b.toString()));
        assertThat(String.join(" ", codes)).contains("URGENT").contains("APPROACHING");

        // The other screens a first admin opens.
        for (String path : List.of("/api/v1/dashboards/platform-admin", "/api/v1/organizations", "/api/v1/users",
                "/api/v1/problem-types", "/api/v1/invoices", "/api/v1/conversations",
                "/api/v1/notifications/rules")) {
            ok(token, path);
        }
    }

    private JsonNode ok(String token, String path) throws Exception {
        MvcResult r = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        assertThat(r.getResponse().getStatus()).as(path + " " + r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }
}
