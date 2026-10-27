package com.nsure.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.nsure.security.domain.AppUser;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // Spring's own error page and forwards are not API endpoints.
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll();
                    // Every endpoint and the permission that opens it: see ApiAccessRules.
                    for (ApiAccessRules.ApiRule rule : ApiAccessRules.RULES) {
                        auth.requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(rule.method(), rule.pattern()))
                                .access((authentication, context) ->
                                        new AuthorizationDecision(rule.access().allows(authentication.get())));
                    }
                    // Fail closed: an endpoint without a rule is reachable by nobody.
                    auth.anyRequest().denyAll();
                })
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, authException) ->
                                write(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthenticated"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                write(response, HttpServletResponse.SC_FORBIDDEN,
                                        isPortalCustomer(SecurityContextHolder.getContext().getAuthentication())
                                                ? "Customer accounts can only use the customer portal."
                                                : "Access denied")));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(List.of("http://127.0.0.1:*", "http://localhost:*"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "Idempotency-Retry"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static boolean isPortalCustomer(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AppUser user && user.isPortalCustomer();
    }

    private void write(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("status", status, "message", message));
    }
}
