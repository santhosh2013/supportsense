package io.github.santhosh2013.supportsense.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.santhosh2013.supportsense.auth.app.JwtAuthenticationFilter;
import io.github.santhosh2013.supportsense.auth.app.JwtTokenService;
import io.github.santhosh2013.supportsense.common.domain.TimeSource;
import io.github.santhosh2013.supportsense.common.web.SecurityResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// Every @PreAuthorize in the codebase was inert until this annotation was added — no
// method-security advisor existed to evaluate them. Verified newly-active behavior in
// TicketMethodSecurityIT and AuthControllerValidationTest before this was merged.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Strength 10, per sheet 06 / requirements FR-3. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(passwordEncoder);
        provider.setUserDetailsService(userDetailsService);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityResponseWriter securityResponseWriter(ObjectMapper objectMapper, TimeSource timeSource) {
        return new SecurityResponseWriter(objectMapper, timeSource);
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(SecurityResponseWriter responseWriter) {
        // Same generic body regardless of *why* authentication failed (missing header,
        // expired, tampered, forged alg) — a differing body would let an attacker probe
        // which failure mode applies to a given token.
        return (request, response, authException) ->
                responseWriter.write(response, HttpStatus.UNAUTHORIZED, "Invalid credentials", "authentication-failed");
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler(SecurityResponseWriter responseWriter) {
        return (request, response, accessDeniedException) ->
                responseWriter.write(response, HttpStatus.FORBIDDEN, "Access denied", "access-denied");
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtTokenService jwtTokenService,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/actuator/health",
                                // The bare paths are listed alongside the /** patterns because
                                // "/v3/api-docs/**" does not match "/v3/api-docs" itself.
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // Without these, Spring Security's defaults (Http403ForbiddenEntryPoint,
                // AccessDeniedHandlerImpl) fire for BOTH missing/invalid credentials and
                // authenticated-but-forbidden access, so every case came back 403. Missing
                // or invalid credentials must be 401 per RFC 7235; only an authenticated
                // principal lacking the required role/permission is 403.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenService, authenticationEntryPoint),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
