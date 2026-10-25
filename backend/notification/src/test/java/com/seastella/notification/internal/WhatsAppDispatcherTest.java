package com.seastella.notification.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens to a queued WhatsApp alert: sent, skipped while WhatsApp is not
 * set up or is paused, failed with Meta's reason.
 */
@DisplayName("the WhatsApp dispatcher")
class WhatsAppDispatcherTest {

    private NotificationDeliveryRepository deliveries;
    private NotificationRepository notifications;
    private NotificationEmailSettingsRepository settings;
    private WhatsAppTemplateRepository templates;
    private WhatsAppGateway gateway;
    private WhatsAppDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        deliveries = mock(NotificationDeliveryRepository.class);
        notifications = mock(NotificationRepository.class);
        settings = mock(NotificationEmailSettingsRepository.class);
        templates = mock(WhatsAppTemplateRepository.class);
        gateway = mock(WhatsAppGateway.class);
        when(settings.whatsappEnabled()).thenReturn(true);
        when(gateway.configured()).thenReturn(true);
        when(templates.findByEventType(anyString())).thenReturn(Optional.empty());
        dispatcher = new WhatsAppDispatcher(deliveries, notifications, settings, templates, gateway,
                mock(PlatformTransactionManager.class), "thawe_alert", "en", true);
    }

    private NotificationDelivery queued(long notificationId) {
        NotificationDelivery d = new NotificationDelivery(notificationId, NotificationDelivery.WHATSAPP, "+919876543210");
        when(deliveries.findTop50ByChannelAndStatusOrderByIdAsc(NotificationDelivery.WHATSAPP, NotificationDelivery.PENDING))
                .thenReturn(List.of(d));
        return d;
    }

    private Notification alert(long id, String eventType, String entityType, Long entityId) {
        Notification n = new Notification(5L, eventType, Notification.Category.ACTION, "Invoice raised: INV-7",
                "Accept or reject it.", entityType, entityId, 1L, 2L, null, true);
        ReflectionTestUtils.setField(n, "id", id);
        when(notifications.findAllById(any())).thenReturn(List.of(n));
        return n;
    }

    @Test
    @DisplayName("sends with the default template, the alert's words and a link to the request")
    void sends() {
        NotificationDelivery d = queued(9L);
        alert(9L, "RAISE_INVOICE", "SERVICE_REQUEST", 12L);

        dispatcher.sendPending();

        ArgumentCaptor<WhatsAppGateway.Outgoing> sent = ArgumentCaptor.forClass(WhatsAppGateway.Outgoing.class);
        verify(gateway).send(sent.capture());
        assertThat(sent.getValue()).isEqualTo(new WhatsAppGateway.Outgoing("+919876543210", "thawe_alert", "en",
                List.of("Invoice raised: INV-7", "Accept or reject it."), "requests/12"));
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.SENT);
    }

    @Test
    @DisplayName("uses the template the Platform Admin named for that alert")
    void namedTemplate() {
        queued(9L);
        alert(9L, "RAISE_INVOICE", "SERVICE_REQUEST", 12L);
        WhatsAppTemplate t = new WhatsAppTemplate("RAISE_INVOICE");
        t.use("thawe_invoice", "en_US", false);
        when(templates.findByEventType("RAISE_INVOICE")).thenReturn(Optional.of(t));

        dispatcher.sendPending();

        ArgumentCaptor<WhatsAppGateway.Outgoing> sent = ArgumentCaptor.forClass(WhatsAppGateway.Outgoing.class);
        verify(gateway).send(sent.capture());
        assertThat(sent.getValue().template()).isEqualTo("thawe_invoice");
        assertThat(sent.getValue().language()).isEqualTo("en_US");
        assertThat(sent.getValue().linkSuffix()).isNull();
    }

    @Test
    @DisplayName("until the Meta account is set up, nothing is attempted and the reason is recorded")
    void notSetUp() {
        when(gateway.configured()).thenReturn(false);
        NotificationDelivery d = queued(9L);
        alert(9L, "RAISE", "SERVICE_REQUEST", 12L);

        dispatcher.sendPending();

        verify(gateway, never()).send(any());
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.SKIPPED);
        assertThat(d.getLastError()).isEqualTo(WhatsAppDispatcher.NOT_SET_UP);
    }

    @Test
    @DisplayName("while paused, what is queued is dropped, not sent later")
    void paused() {
        when(settings.whatsappEnabled()).thenReturn(false);
        queued(9L);

        dispatcher.sendPending();

        verify(gateway, never()).send(any());
        verify(deliveries).skipPending(eq(NotificationDelivery.WHATSAPP), eq(NotificationDelivery.PENDING),
                eq(NotificationDelivery.SKIPPED), eq(WhatsAppDispatcher.PAUSED));
    }

    @Test
    @DisplayName("Meta's refusal fails the delivery at once, with Meta's reason")
    void refused() {
        NotificationDelivery d = queued(9L);
        alert(9L, "RAISE", "SERVICE_REQUEST", 12L);
        when(gateway.send(any())).thenThrow(new WhatsAppGateway.WhatsAppException("template not approved", true));

        dispatcher.sendPending();

        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.FAILED);
        assertThat(d.getLastError()).isEqualTo("template not approved");
    }

    @Test
    @DisplayName("a network failure stays queued for another try")
    void retried() {
        NotificationDelivery d = queued(9L);
        alert(9L, "RAISE", "SERVICE_REQUEST", 12L);
        when(gateway.send(any())).thenThrow(new WhatsAppGateway.WhatsAppException("timed out", false));

        dispatcher.sendPending();

        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.PENDING);
        assertThat(d.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("an alert about equipment links to the app itself")
    void linkWithoutARequest() {
        Notification n = new Notification(5L, "MAINTENANCE_DUE", Notification.Category.MAINTENANCE, "t", "b",
                "SPARE", 40L, 1L, 2L, "OVERDUE", true);
        assertThat(WhatsAppDispatcher.linkSuffix(n)).isEqualTo("?from=whatsapp");
        assertThat(WhatsAppDispatcher.masked("+919876543210")).isEqualTo("...3210");
    }

    @Test
    @DisplayName("colour-banded alerts reach WhatsApp only once they are red")
    void redOnly() {
        assertThat(NotificationFanout.redEnoughForWhatsapp("APPROACHING")).isFalse();
        assertThat(NotificationFanout.redEnoughForWhatsapp("NORMAL")).isFalse();
        assertThat(NotificationFanout.redEnoughForWhatsapp("URGENT")).isTrue();
        assertThat(NotificationFanout.redEnoughForWhatsapp("DUE")).isTrue();
        assertThat(NotificationFanout.redEnoughForWhatsapp("OVERDUE")).isTrue();
        assertThat(NotificationFanout.redEnoughForWhatsapp(null)).isTrue();
    }
}
