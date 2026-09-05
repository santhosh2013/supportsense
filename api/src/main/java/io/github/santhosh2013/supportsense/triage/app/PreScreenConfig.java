package io.github.santhosh2013.supportsense.triage.app;

import io.github.santhosh2013.supportsense.common.config.SupportSenseProperties;
import io.github.santhosh2013.supportsense.triage.domain.AutoAnswerGate;
import io.github.santhosh2013.supportsense.triage.domain.PreScreenMatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the YAML-owned sensitive-term list into pure domain components. */
@Configuration
public class PreScreenConfig {

    @Bean
    public PreScreenMatcher preScreenMatcher(SupportSenseProperties properties) {
        return new PreScreenMatcher(properties.preScreen().terms());
    }

    @Bean
    public AutoAnswerGate autoAnswerGate(PreScreenMatcher preScreenMatcher) {
        return new AutoAnswerGate(preScreenMatcher);
    }
}