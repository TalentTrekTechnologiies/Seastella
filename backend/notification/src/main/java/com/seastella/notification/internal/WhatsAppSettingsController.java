package com.seastella.notification.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ValidationException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The Platform Admin's WhatsApp controls: whether it is set up, one switch to
 * pause it, which approved template each alert uses, and a test message to
 * one's own phone - the first thing to try the day the Meta account arrives.
 *
 * <p>Which alerts go to WhatsApp, and to which roles, is the WhatsApp tick on
 * each alert rule. Who receives them is each person's own choice of number.
 */
@RestController
@RequestMapping("/api/v1/notifications/whatsapp")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class WhatsAppSettingsController {

    /** Meta's own rule for template names. */
    private static final Pattern TEMPLATE_NAME = Pattern.compile("^[a-z0-9_]{1,80}$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(_[A-Z]{2})?$");

    private final NotificationEmailSettingsRepository settings;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationRuleRepository rules;
    private final WhatsAppTemplateRepository templates;
    private final WhatsAppDispatcher dispatcher;
    private final WhatsAppGateway gateway;
    private final UserDirectory users;
    private final ScopeResolver scopeResolver;
    private final AuditService audit;

    WhatsAppSettingsController(NotificationEmailSettingsRepository settings, NotificationDeliveryRepository deliveries,
                               NotificationRuleRepository rules, WhatsAppTemplateRepository templates,
                               WhatsAppDispatcher dispatcher, WhatsAppGateway gateway, UserDirectory users,
                               ScopeResolver scopeResolver, AuditService audit) {
        this.settings = settings;
        this.deliveries = deliveries;
        this.rules = rules;
        this.templates = templates;
        this.dispatcher = dispatcher;
        this.gateway = gateway;
        this.users = users;
        this.scopeResolver = scopeResolver;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    ResponseEntity<SettingsView> settings() {
        return ResponseEntity.ok(view());
    }

    @PutMapping
    @Transactional
    ResponseEntity<SettingsView> configure(@RequestBody SettingsUpdate body) {
        if (body == null || body.enabled() == null) {
            throw new ValidationException("Say whether WhatsApp alerts should be on.");
        }
        NotificationEmailSettings s = settings.findById(NotificationEmailSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Alert settings are missing."));
        boolean was = s.isWhatsappEnabled();
        s.setWhatsappEnabled(body.enabled());
        settings.save(s);
        if (!body.enabled()) {
            deliveries.skipPending(NotificationDelivery.WHATSAPP, NotificationDelivery.PENDING,
                    NotificationDelivery.SKIPPED, WhatsAppDispatcher.PAUSED);
        }
        record("NotificationEmailSettings", s.getId(), AuditJson.of("whatsappEnabled", was),
                AuditJson.of("whatsappEnabled", body.enabled()));
        return ResponseEntity.ok(view());
    }

    /** Every kind of alert, with the template it uses - its own, or the default. */
    @GetMapping("/templates")
    @Transactional(readOnly = true)
    ResponseEntity<List<TemplateView>> templates() {
        Map<String, WhatsAppTemplate> chosen = templates.findAll().stream()
                .collect(Collectors.toMap(WhatsAppTemplate::getEventType, Function.identity()));
        TreeSet<String> events = rules.findAll().stream().map(NotificationRule::getEventType)
                .collect(Collectors.toCollection(TreeSet::new));
        WhatsAppDispatcher.Defaults d = dispatcher.defaults();
        return ResponseEntity.ok(events.stream().map(e -> {
            WhatsAppTemplate t = chosen.get(e);
            return t == null
                    ? new TemplateView(e, d.template(), d.language(), d.linkButton(), true)
                    : new TemplateView(e, t.getTemplateName(), t.getLanguageCode(), t.isLinkButton(), false);
        }).toList());
    }

    /** Names an approved template for one kind of alert; a blank name returns it to the default. */
    @PutMapping("/templates/{eventType}")
    @Transactional
    ResponseEntity<List<TemplateView>> useTemplate(@PathVariable String eventType, @RequestBody TemplateUpdate body) {
        if (rules.findAll().stream().noneMatch(r -> r.getEventType().equals(eventType))) {
            throw new ValidationException("There is no alert called " + eventType + ".");
        }
        WhatsAppTemplate existing = templates.findByEventType(eventType).orElse(null);
        String before = existing == null ? AuditJson.of("eventType", eventType, "template", "default")
                : AuditJson.of("eventType", eventType, "template", existing.getTemplateName());

        String name = body == null || body.templateName() == null ? "" : body.templateName().trim();
        if (name.isEmpty()) {
            if (existing != null) {
                templates.delete(existing);
                record("WhatsAppTemplate", existing.getId(), before,
                        AuditJson.of("eventType", eventType, "template", "default"));
            }
            return templates();
        }
        if (!TEMPLATE_NAME.matcher(name).matches()) {
            throw new ValidationException("A template name is lower-case letters, digits and underscores, "
                    + "exactly as approved in Meta, for example thawe_invoice_raised.");
        }
        String language = body.languageCode() == null || body.languageCode().isBlank()
                ? dispatcher.defaults().language() : body.languageCode().trim();
        if (!LANGUAGE.matcher(language).matches()) {
            throw new ValidationException("Enter the template's language code as Meta shows it, for example en or en_US.");
        }
        WhatsAppTemplate t = existing == null ? new WhatsAppTemplate(eventType) : existing;
        t.use(name, language, Boolean.TRUE.equals(body.linkButton()));
        templates.save(t);
        record("WhatsAppTemplate", t.getId(), before,
                AuditJson.of("eventType", eventType, "template", name, "language", language,
                        "linkButton", t.isLinkButton()));
        return templates();
    }

    /**
     * Sends one message to the administrator's own WhatsApp number, now, and
     * says what Meta answered. {@code helloWorld} uses the sample template every
     * new Meta account already has, to prove the account before any of the
     * platform's own templates is approved.
     */
    @PostMapping("/test")
    ResponseEntity<TestResult> test(@RequestBody(required = false) TestRequest body) {
        if (!gateway.configured()) {
            return ResponseEntity.ok(new TestResult("NOT_SET_UP", WhatsAppDispatcher.NOT_SET_UP));
        }
        Long me = scopeResolver.currentScope().userId();
        String number = users.find(me).map(UserDirectory.UserRef::whatsapp).orElse(null);
        if (number == null) {
            return ResponseEntity.ok(new TestResult("NO_NUMBER",
                    "Add your own WhatsApp number first, under \"WhatsApp alerts\" beside Change password."));
        }
        boolean helloWorld = body != null && Boolean.TRUE.equals(body.helloWorld());
        WhatsAppDispatcher.Defaults d = dispatcher.defaults();
        WhatsAppGateway.Outgoing message = helloWorld
                ? new WhatsAppGateway.Outgoing(number, "hello_world", "en_US", List.of(), null)
                : new WhatsAppGateway.Outgoing(number, d.template(), d.language(),
                        List.of("Test message", "WhatsApp alerts from Thawe Marine are working."),
                        d.linkButton() ? "?from=whatsapp" : null);
        try {
            gateway.send(message);
            return ResponseEntity.ok(new TestResult("SENT", "Sent to " + WhatsAppDispatcher.masked(number)
                    + " using the " + message.template() + " template. Check that phone."));
        } catch (WhatsAppGateway.WhatsAppException e) {
            return ResponseEntity.ok(new TestResult("FAILED", e.getMessage()));
        }
    }

    private SettingsView view() {
        WhatsAppDispatcher.Defaults d = dispatcher.defaults();
        return new SettingsView(settings.whatsappEnabled(), gateway.configured(), d.template(), d.language(),
                d.linkButton(), users.whatsappOptInCount());
    }

    private void record(String entity, Long id, String before, String after) {
        AccessScope scope = scopeResolver.currentScope();
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role() == null ? null : scope.role().name())
                .action(AuditAction.CONFIGURATION_CHANGED)
                .entity(entity, id)
                .before(before)
                .after(after)
                .build());
    }

    record SettingsView(boolean enabled, boolean configured, String defaultTemplate, String defaultLanguage,
                        boolean defaultLinkButton, long optedInUsers) {}

    record SettingsUpdate(Boolean enabled) {}

    record TemplateView(String eventType, String templateName, String languageCode, boolean linkButton,
                        boolean usesDefault) {}

    record TemplateUpdate(String templateName, String languageCode, Boolean linkButton) {}

    record TestRequest(Boolean helloWorld) {}

    record TestResult(String status, String detail) {}
}
