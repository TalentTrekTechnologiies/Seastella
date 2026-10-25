package com.seastella.notification.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The request Meta's Cloud API receives, checked against a stand-in server -
 * the client's real account does not exist yet, and when it does, a wrong
 * field name here is a day lost to "why is nothing arriving".
 */
@DisplayName("sending through Meta's WhatsApp Cloud API")
class MetaWhatsAppGatewayTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer meta;
    private MetaWhatsAppGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://graph.example");
        meta = MockRestServiceServer.bindTo(builder).build();
        gateway = new MetaWhatsAppGateway(builder.build(), json, "v21.0", "1234567890", "secret-token");
    }

    @Test
    @DisplayName("an alert goes as the approved template, with its words as parameters and its link")
    void sendsATemplate() {
        meta.expect(requestTo("https://graph.example/v21.0/1234567890/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andExpect(content().json("""
                        {"messaging_product":"whatsapp","to":"919876543210","type":"template",
                         "template":{"name":"thawe_alert","language":{"code":"en"},
                          "components":[
                           {"type":"body","parameters":[
                             {"type":"text","text":"Invoice raised: INV-7"},
                             {"type":"text","text":"Approve it in the app. Vessel: MV Kestrel"}]},
                           {"type":"button","sub_type":"url","index":"0",
                            "parameters":[{"type":"text","text":"requests/12"}]}]}}
                        """, true))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.ABC\"}]}", MediaType.APPLICATION_JSON));

        String id = gateway.send(new WhatsAppGateway.Outgoing("+919876543210", "thawe_alert", "en",
                List.of("Invoice raised: INV-7", "Approve it in the app.\n\nVessel:   MV Kestrel"), "requests/12"));

        assertThat(id).isEqualTo("wamid.ABC");
        meta.verify();
    }

    @Test
    @DisplayName("Meta's sample template needs no parameters, so none are sent")
    void helloWorld() {
        meta.expect(requestTo("https://graph.example/v21.0/1234567890/messages"))
                .andExpect(content().json("""
                        {"to":"919876543210","template":{"name":"hello_world","language":{"code":"en_US"}}}
                        """))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.HW\"}]}", MediaType.APPLICATION_JSON));

        gateway.send(new WhatsAppGateway.Outgoing("+919876543210", "hello_world", "en_US", List.of(), null));
        meta.verify();
    }

    @Test
    @DisplayName("a refusal carries Meta's own reason and is not retried")
    void refusalIsPermanent() {
        meta.expect(requestTo("https://graph.example/v21.0/1234567890/messages"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"message":"(#132001) Template name does not exist in the translation",
                          "code":132001,"error_data":{"details":"template name (thawe_alert) does not exist in en"}}}
                        """));

        assertThatThrownBy(() -> gateway.send(new WhatsAppGateway.Outgoing("+919876543210", "thawe_alert", "en",
                List.of("a", "b"), null)))
                .isInstanceOfSatisfying(WhatsAppGateway.WhatsAppException.class, e -> {
                    assertThat(e.permanent()).isTrue();
                    assertThat(e.getMessage()).contains("does not exist in en").contains("132001");
                });
    }

    @Test
    @DisplayName("Meta being busy or down is worth another try")
    void outageIsRetried() {
        meta.expect(requestTo("https://graph.example/v21.0/1234567890/messages"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> gateway.send(new WhatsAppGateway.Outgoing("+919876543210", "thawe_alert", "en",
                List.of("a", "b"), null)))
                .isInstanceOfSatisfying(WhatsAppGateway.WhatsAppException.class,
                        e -> assertThat(e.permanent()).isFalse());
    }

    @Test
    @DisplayName("without the account details nothing is configured")
    void unconfigured() {
        MetaWhatsAppGateway blank = new MetaWhatsAppGateway(RestClient.create(), json, "v21.0", "", " ");
        assertThat(blank.configured()).isFalse();
        assertThat(gateway.configured()).isTrue();
    }

    @Test
    @DisplayName("parameters are folded to what Meta accepts")
    void parameters() {
        assertThat(MetaWhatsAppGateway.parameter("line one\nline two\t\tend")).isEqualTo("line one line two end");
        assertThat(MetaWhatsAppGateway.parameter("   ")).isEqualTo("-");
        assertThat(MetaWhatsAppGateway.parameter("x".repeat(2000))).hasSize(450).endsWith("...");
    }
}
