package io.github.santhosh2013.supportsense.common.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fails startup when a required secret is absent outside the {@code local} profile.
 *
 * <p>Runs at {@code ApplicationEnvironmentPreparedEvent} so the failure happens before any
 * bean is created and before a datasource connection is attempted. There is deliberately no
 * default fallback: a development default that silently reaches production is the exact
 * failure mode this guards against.
 */
public class RequiredSecretsValidator
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final String LOCAL_PROFILE = "local";

    static final List<String> REQUIRED_PROPERTIES =
            List.of("supportsense.security.jwt-secret", "supportsense.security.admin-password");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        validate(event.getEnvironment());
    }

    static void validate(ConfigurableEnvironment environment) {
        if (List.of(environment.getActiveProfiles()).contains(LOCAL_PROFILE)) {
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String property : REQUIRED_PROPERTIES) {
            String value = environment.getProperty(property);
            if (value == null || value.isBlank()) {
                missing.add(property);
            }
        }

        if (!missing.isEmpty()) {
            throw new MissingRequiredSecretException(missing);
        }
    }
}
