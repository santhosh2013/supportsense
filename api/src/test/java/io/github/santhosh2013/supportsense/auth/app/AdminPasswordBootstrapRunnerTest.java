package io.github.santhosh2013.supportsense.auth.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.santhosh2013.supportsense.auth.persistence.User;
import io.github.santhosh2013.supportsense.auth.persistence.UserRepository;
import io.github.santhosh2013.supportsense.auth.persistence.UserRole;
import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminPasswordBootstrapRunnerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void resolvesThePendingPlaceholderIntoARealHash() {
        User pendingAdmin = new User(
                "admin@supportsense.local", AdminPasswordBootstrapRunner.PENDING_MARKER, "Admin", UserRole.ADMIN, null);
        when(userRepository.findAll()).thenReturn(List.of(pendingAdmin));
        when(passwordEncoder.encode("bootstrap-password")).thenReturn("bcrypt-hash-value");

        SupportSenseProperties properties = new SupportSenseProperties(
                new SupportSenseProperties.Security(
                        "jwt-secret", "bootstrap-password", Duration.ofMinutes(15), Duration.ofDays(7)),
                new SupportSenseProperties.Ingestion(4, 8, 500, Duration.ofMinutes(15), 3, Duration.ofHours(1), "customer-success"),
                new SupportSenseProperties.PreScreen(List.of("refund")));

        new AdminPasswordBootstrapRunner(userRepository, passwordEncoder, properties).run(null);

        assertThat(pendingAdmin.getPasswordHash()).isEqualTo("bcrypt-hash-value");
        verify(userRepository).save(pendingAdmin);
    }

    @Test
    void doesNothingWhenNoAccountIsPending() {
        User alreadyResolvedAdmin =
                new User("admin@supportsense.local", "already-a-real-hash", "Admin", UserRole.ADMIN, null);
        when(userRepository.findAll()).thenReturn(List.of(alreadyResolvedAdmin));

        SupportSenseProperties properties = new SupportSenseProperties(
                new SupportSenseProperties.Security(
                        "jwt-secret", "bootstrap-password", Duration.ofMinutes(15), Duration.ofDays(7)),
                new SupportSenseProperties.Ingestion(4, 8, 500, Duration.ofMinutes(15), 3, Duration.ofHours(1), "customer-success"),
                new SupportSenseProperties.PreScreen(List.of("refund")));

        new AdminPasswordBootstrapRunner(userRepository, passwordEncoder, properties).run(null);

        verify(userRepository, never()).save(any());
    }
}
