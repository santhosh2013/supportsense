package io.github.santhosh2013.supportsense.common.config;

import io.github.santhosh2013.supportsense.common.domain.TimeSource;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TimeSource timeSource(Clock clock) {
        return clock::instant;
    }
}
