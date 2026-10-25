package com.seastella.notification.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WhatsApp through Meta's own Cloud API - no reseller in between, so the
 * client pays Meta's per-message price and nothing on top (SoW s16: third-party
 * costs are the client's).
 *
 * <p>Two values from the client's Meta Business account switch it on: the
 * sending number's id and a permanent access token. Without them
 * {@link #configured()} is false and every WhatsApp alert is recorded as
 * skipped, the way email was before its mailbox existed.
 */
@Component
class MetaWhatsAppGateway implements WhatsAppGateway {

    /** A template's whole body is capped at 1024 characters; two parameters must fit inside it. */
    private static final int MAX_PARAMETER = 450;

    private final RestClient http;
    private final ObjectMapper json;
    private final String phoneNumberId;
    private final String accessToken;
    private final String apiVersion;

    @Autowired
    MetaWhatsAppGateway(ObjectMapper json,
                        @Value("${seastella.notification.whatsapp.base-url:https://graph.facebook.com}") String baseUrl,
                        @Value("${seastella.notification.whatsapp.api-version:v21.0}") String apiVersion,
                        @Value("${seastella.notification.whatsapp.phone-number-id:}") String phoneNumberId,
                        @Value("${seastella.notification.whatsapp.access-token:}") String accessToken) {
        this(RestClient.builder().requestFactory(timeouts()).baseUrl(baseUrl).build(),
                json, apiVersion, phoneNumberId, accessToken);
    }

    /** For tests, with a client bound to a mock server. */
    MetaWhatsAppGateway(RestClient http, ObjectMapper json, String apiVersion, String phoneNumberId, String accessToken) {
        this.http = http;
        this.json = json;
        this.apiVersion = apiVersion;
        this.phoneNumberId = phoneNumberId == null ? "" : phoneNumberId.trim();
        this.accessToken = accessToken == null ? "" : accessToken.trim();
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(20));
        return f;
    }

    @Override
    public boolean configured() {
        return !phoneNumberId.isEmpty() && !accessToken.isEmpty();
    }

    @Override
    public String send(Outgoing message) {
        if (!configured()) {
            throw new WhatsAppException("WhatsApp is not set up yet.", true);
        }
        try {
            String response = http.post()
                    .uri("/{version}/{phoneNumberId}/messages", apiVersion, phoneNumberId)
                    .headers(h -> h.setBearerAuth(accessToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(message))
                    .retrieve()
                    .body(String.class);
            JsonNode id = response == null ? null : json.readTree(response).path("messages").path(0).path("id");
            return id == null || id.isMissingNode() ? null : id.asText();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // Too many requests and Meta's own outages pass; everything else is an answer.
            boolean permanent = status != 429 && status < 500;
            throw new WhatsAppException("WhatsApp refused it (" + status + "): " + metaError(e.getResponseBodyAsString()),
                    permanent);
        } catch (ResourceAccessException e) {
            throw new WhatsAppException("WhatsApp could not be reached: " + e.getMessage(), false);
        } catch (java.io.IOException e) {
            // Accepted, but the reply was not readable; the message went.
            return null;
        }
    }

    Map<String, Object> body(Outgoing m) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", m.template());
        template.put("language", Map.of("code", m.language()));

        List<Map<String, Object>> components = new ArrayList<>();
        if (m.bodyParameters() != null && !m.bodyParameters().isEmpty()) {
            List<Map<String, Object>> params = new ArrayList<>();
            for (String p : m.bodyParameters()) {
                params.add(Map.of("type", "text", "text", parameter(p)));
            }
            components.add(Map.of("type", "body", "parameters", params));
        }
        if (m.linkSuffix() != null) {
            components.add(Map.of("type", "button", "sub_type", "url", "index", "0",
                    "parameters", List.of(Map.of("type", "text", "text", m.linkSuffix()))));
        }
        if (!components.isEmpty()) template.put("components", components);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", m.to().startsWith("+") ? m.to().substring(1) : m.to());
        body.put("type", "template");
        body.put("template", template);
        return body;
    }

    /** One line, no runs of spaces, never empty - Meta rejects each of those. */
    static String parameter(String text) {
        String s = text == null ? "" : text.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").trim();
        if (s.isEmpty()) s = "-";
        return s.length() > MAX_PARAMETER ? s.substring(0, MAX_PARAMETER - 3) + "..." : s;
    }

    private String metaError(String body) {
        try {
            JsonNode error = json.readTree(body).path("error");
            String text = error.path("error_data").path("details").asText(error.path("message").asText(""));
            int code = error.path("code").asInt(0);
            if (text.isEmpty()) return body == null ? "" : body;
            return code == 0 ? text : text + " (code " + code + ")";
        } catch (Exception e) {
            return body == null ? "" : body;
        }
    }
}
