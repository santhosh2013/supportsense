package io.github.santhosh2013.supportsense.auth.app;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

// Design choice: this filter is the single point that decides "credentials present but
// invalid" vs "credentials absent." A present-but-invalid Bearer header throws
// AuthenticationException immediately, delegated to AuthenticationEntryPoint (401) —
// it never silently falls through to the anonymous/"no credentials" path, which would
// otherwise be indistinguishable from a request with no Authorization header at all.
// An absent header is left alone; Spring Security's own anonymous-then-denied handling
// takes over, and .authenticated() routes that through the same AuthenticationEntryPoint.
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(
            JwtTokenService jwtTokenService, AuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtTokenService = jwtTokenService;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                Claims claims = jwtTokenService.parseClaims(header.substring(BEARER_PREFIX.length()));
                String role = claims.get("role", String.class);

                var authentication = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("Rejected invalid JWT", e);
                SecurityContextHolder.clearContext();
                // Same generic message regardless of *why* the token was rejected (missing
                // vs expired vs tampered vs forged alg) — a differing body would let an
                // attacker probe which failure mode applies to a given token.
                AuthenticationException authException = new BadCredentialsException("Invalid credentials");
                authenticationEntryPoint.commence(request, response, authException);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
