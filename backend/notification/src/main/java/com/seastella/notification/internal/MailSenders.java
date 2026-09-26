package com.seastella.notification.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The configured mail sender, or none.
 *
 * <p>Spring creates a sender whenever {@code spring.mail.host} is present, even
 * blank - which an env file with an empty {@code SPRING_MAIL_HOST=} line makes
 * it. A blank host is treated as no mail server, so deliveries are recorded as
 * SKIPPED rather than failing against an address that does not exist.
 */
final class MailSenders {

    private MailSenders() {
    }

    static JavaMailSender usable(ObjectProvider<JavaMailSender> provider) {
        JavaMailSender sender = provider.getIfAvailable();
        if (sender instanceof JavaMailSenderImpl impl && (impl.getHost() == null || impl.getHost().isBlank())) {
            return null;
        }
        return sender;
    }
}
