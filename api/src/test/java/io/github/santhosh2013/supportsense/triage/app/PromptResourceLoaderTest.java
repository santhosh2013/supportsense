package io.github.santhosh2013.supportsense.triage.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class PromptResourceLoaderTest {

    private final PromptResourceLoader loader = new PromptResourceLoader(new DefaultResourceLoader());

    @Test
    void loadsVersionedPromptFromClasspathAndRendersExpectedPlaceholders() {
        String rendered = loader.loadAndRender(
                "triage-v1",
                Map.of("subject", "Cannot sign in", "body", "Login is rejected", "taxonomy", "account-login-access"));

        assertThat(rendered)
                .contains("Cannot sign in", "Login is rejected", "account-login-access")
                .doesNotContain("{subject}", "{body}", "{taxonomy}");
    }

    @Test
    void missingPromptFailsWithAUsefulMessage() {
        assertThatThrownBy(() -> loader.load("missing-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-v1");
    }
}
