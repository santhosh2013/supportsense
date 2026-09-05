package io.github.santhosh2013.supportsense.common.config;

import java.util.List;

public class MissingRequiredSecretException extends IllegalStateException {

    private final transient List<String> missingProperties;

    public MissingRequiredSecretException(List<String> missingProperties) {
        super(buildMessage(missingProperties));
        this.missingProperties = List.copyOf(missingProperties);
    }

    public List<String> missingProperties() {
        return missingProperties;
    }

    private static String buildMessage(List<String> missingProperties) {
        return """
                Required configuration is missing outside the 'local' profile: %s

                Supply them via environment variables:
                  SUPPORTSENSE_SECURITY_JWT_SECRET
                  SUPPORTSENSE_SECURITY_ADMIN_PASSWORD

                There is no default fallback by design."""
                .formatted(String.join(", ", missingProperties));
    }
}
