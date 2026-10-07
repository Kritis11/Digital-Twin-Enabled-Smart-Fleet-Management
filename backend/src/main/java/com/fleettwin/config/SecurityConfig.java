package com.fleettwin.config;

import java.util.List;

import com.fleettwin.auth.TokenService;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Every request needs a valid access token (JWT) except logging in, refreshing, the health check and
 * the WebSocket handshake (the token is checked on the STOMP CONNECT frame instead, see WebSocketConfig).
 *
 * Who may do what:
 *   ADMIN          everything, including users, the audit log and settings
 *   FLEET_MANAGER  every dashboard, route planning, recommendations, reports
 *   TECHNICIAN     vehicles, alerts, maintenance records and recommendations (read and write)
 *   VIEWER         read-only: everything a fleet manager can see, nothing can be changed
 */
@Configuration
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String MANAGER = "FLEET_MANAGER";
    private static final String TECHNICIAN = "TECHNICIAN";
    private static final String VIEWER = "VIEWER";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            @Value("${server.port:8080}") int serverPort,
                                            @Value("${management.server.port:${server.port:8080}}") int managementPort)
            throws Exception {
        // When actuator has its own port (production), that port is not published and Prometheus scrapes it
        // from inside the Docker network. On the shared port only the health check is open.
        RequestMatcher onManagementPort = request -> managementPort != serverPort && request.getLocalPort() == managementPort;
        return http
                .csrf(AbstractHttpConfigurer::disable)   // no cookies: the token travels in a header
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .authorizeHttpRequests(auth -> auth
                        // the error page is rendered by an internal dispatch after the real request was judged
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(onManagementPort).permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole(ADMIN)
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/ws/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/users/**", "/api/audit-log/**", "/api/admin/**").hasRole(ADMIN)
                        // fleet-wide analysis, route planning and reports: not for technicians
                        .requestMatchers(HttpMethod.POST, "/api/routes/**", "/api/reports/**").hasAnyRole(ADMIN, MANAGER)
                        .requestMatchers(HttpMethod.GET, "/api/fleet/**", "/api/reports/**").hasAnyRole(ADMIN, MANAGER, VIEWER)
                        .requestMatchers("/api/fleet/**", "/api/routes/**", "/api/reports/**").denyAll()
                        // vehicles, alerts, maintenance records, recommendations
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .requestMatchers("/api/**").hasAnyRole(ADMIN, MANAGER, TECHNICIAN)
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(TokenService tokens) {
        return tokens.accessDecoder();
    }

    /** The token's "role" claim becomes the authority ROLE_<role>. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("role");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${fleet.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
