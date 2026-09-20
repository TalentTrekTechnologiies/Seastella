package com.seastella.identity.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Links into the web app that arrive by email. */
@Component
class AccountLinks {

    private final String baseUrl;

    AccountLinks(@Value("${seastella.app-base-url:http://localhost:5173}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    String invitation(String rawToken) {
        return baseUrl + "/invite/" + rawToken;
    }

    String passwordReset(String rawToken) {
        return baseUrl + "/reset/" + rawToken;
    }
}
