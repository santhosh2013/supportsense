package io.github.santhosh2013.supportsense.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** AC-14: startup must fail rather than fall back to a default secret. */
class RequiredSecretsValidatorTest {

    @Test
    @DisplayName("cloud profile without secrets fails fast")
    void failsWhenSecretsMissingOutsideLocal() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("cloud");

        assertThatThrownBy(() -> RequiredSecretsValidator.validate(environment))
                .isInstanceOf(MissingRequiredSecretException.class)
                .hasMessageContaining("supportsense.security.jwt-secret")
                .hasMessageContaining("supportsense.security.admin-password");
    }

    @Test
    @DisplayName("blank values are treated as missing")
    void treatsBlankAsMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("cloud");
        environment.setProperty("supportsense.security.jwt-secret", "   ");
        environment.setProperty("supportsense.security.admin-password", "supplied");

        assertThatThrownBy(() -> RequiredSecretsValidator.validate(environment))
                .isInstanceOf(MissingRequiredSecretException.class)
                .satisfies(thrown -> assertThat(((MissingRequiredSecretException) thrown).missingProperties())
                        .containsExactly("supportsense.security.jwt-secret"));
    }

    @Test
    @DisplayName("local profile may rely on development defaults")
    void allowsLocalProfileWithoutSecrets() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertThatCode(() -> RequiredSecretsValidator.validate(environment)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cloud profile with both secrets starts")
    void passesWhenSecretsSupplied() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("cloud");
        Map.of(
                        "supportsense.security.jwt-secret", "a-real-secret-value",
                        "supportsense.security.admin-password", "a-real-password")
                .forEach(environment::setProperty);

        assertThatCode(() -> RequiredSecretsValidator.validate(environment)).doesNotThrowAnyException();
    }
}
