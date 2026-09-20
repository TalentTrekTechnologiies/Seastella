package com.seastella.app.config;

import com.seastella.identity.api.AuthenticationFilter;
import com.seastella.identity.api.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The filter chain.
 *
 * <p>Everything under {@code /api/**} is authenticated by default and each
 * endpoint additionally carries its own {@code @PreAuthorize} - the chain is
 * the floor, not the whole policy (SEC-25). {@code anyRequest().authenticated()}
 * means a newly added endpoint is closed until someone deliberately opens it,
 * rather than open until someone remembers to close it.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Cost 12 rather than the default 10: these hashes protect vessel
     * operational data, and the extra work factor costs a few tens of
     * milliseconds at sign-in only.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           AuthenticationFilter jwtFilter,
                                           RateLimitProperties rateLimits,
                                           CurrentUser currentUser) throws Exception {
        http
                // Stateless bearer-token API: there is no session to fixate and
                // no cookie to forge, so CSRF protection is not applicable here.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Refresh and logout authenticate by the refresh cookie, not the
                        // access token, which may already have expired.
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        // Invitation and reset links: the single-use link is the credential.
                        .requestMatchers("/api/v1/account/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/h2-console/**").permitAll()
                        .anyRequest().authenticated())

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            response.getWriter().write("""
                                    {"type":"about:blank","title":"Unauthorized","status":401,\
                                    "detail":"Authentication is required.","code":"UNAUTHENTICATED"}""");
                        })
                        .accessDeniedHandler((request, response, e) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            response.getWriter().write("""
                                    {"type":"about:blank","title":"Forbidden","status":403,\
                                    "detail":"You do not have permission to perform this action.",\
                                    "code":"FORBIDDEN"}""");
                        }))

                .headers(h -> h
                        .frameOptions(f -> f.sameOrigin())   // H2 console in dev only
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; frame-ancestors 'none'"))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)))

                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // After authentication, so a signed-in user's writes are counted
                // against their account rather than a shared office address
                // (SEC-23). Anchored on UsernamePasswordAuthenticationFilter
                // rather than on the token filter's own class: that arrives
                // here as an interface, and a proxy's class is not a class the
                // chain knows, so ordering against it silently misplaces this
                // filter instead of failing.
                .addFilterAfter(new RateLimitFilter(rateLimits, currentUser),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
