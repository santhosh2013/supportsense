package io.github.santhosh2013.supportsense.auth.app;

import io.github.santhosh2013.supportsense.auth.persistence.User;
import io.github.santhosh2013.supportsense.auth.persistence.UserRepository;
import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the V2-seeded admin placeholder ({@code password_hash = '$PENDING$'}) into a
 * real BCrypt hash on startup, sourced from {@code SUPPORTSENSE_SECURITY_ADMIN_PASSWORD}.
 *
 * <p>Idempotent: once the placeholder is resolved, subsequent restarts find no pending row
 * and do nothing. This runner exists specifically so the admin password is never a
 * migration-time secret — {@link io.github.santhosh2013.supportsense.common.config.RequiredSecretsValidator}
 * already guarantees the property is present outside the {@code local} profile before this
 * runner ever executes.
 */
@Component
public class AdminPasswordBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordBootstrapRunner.class);
    static final String PENDING_MARKER = "$PENDING$";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SupportSenseProperties properties;

    public AdminPasswordBootstrapRunner(
            UserRepository userRepository, PasswordEncoder passwordEncoder, SupportSenseProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (User user : userRepository.findAll()) {
            if (PENDING_MARKER.equals(user.getPasswordHash())) {
                user.setPasswordHash(passwordEncoder.encode(properties.security().adminPassword()));
                userRepository.save(user);
                log.info("Resolved bootstrap password for admin account {}", user.getEmail());
            }
        }
    }
}
