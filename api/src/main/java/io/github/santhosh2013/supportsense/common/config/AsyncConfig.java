package io.github.santhosh2013.supportsense.common.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The ingestion executor. Rejection policy is {@code AbortPolicy} — never {@code
 * CallerRunsPolicy}, which would let the request thread execute the work and silently
 * convert queue saturation into multi-second request latency (see ADR-0002, ADR-0015).
 *
 * <p>Per ADR-0015, a rejection no longer means lost work: the ticket is already durably
 * persisted before this executor is ever touched. Callers catch the resulting {@code
 * RejectedExecutionException}, increment a counter, and rely on the scheduled sweep to
 * collect the row — see {@code TicketIngestionService}.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    private final SupportSenseProperties.Ingestion ingestionProperties;
    private final ApplicationContext applicationContext;

    public AsyncConfig(SupportSenseProperties properties, ApplicationContext applicationContext) {
        this.ingestionProperties = properties.ingestion();
        this.applicationContext = applicationContext;
    }

    @Override
    @Bean(name = "ingestionExecutor")
    @ConditionalOnMissingBean(name = "ingestionExecutor")
    public ThreadPoolTaskExecutor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ingestionProperties.corePoolSize());
        executor.setMaxPoolSize(ingestionProperties.maxPoolSize());
        executor.setQueueCapacity(ingestionProperties.queueCapacity());
        executor.setThreadNamePrefix("ingestion-");
        executor.setTaskDecorator(mdcAndSecurityContextPropagatingDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new IngestionUncaughtExceptionHandler(applicationContext);
    }

    /**
     * Propagates the MDC trace/correlation ID and SecurityContext into the worker thread.
     * Without it, ingestion logs are uncorrelated from the request that triggered them and
     * the audit trail is lost — see FR-5.
     */
    private TaskDecorator mdcAndSecurityContextPropagatingDecorator() {
        return runnable -> {
            var contextMap = MDC.getCopyOfContextMap();
            SecurityContext securityContext = SecurityContextHolder.getContext();
            return () -> {
                try {
                    if (contextMap != null) {
                        MDC.setContextMap(contextMap);
                    }
                    SecurityContextHolder.setContext(securityContext);
                    runnable.run();
                } finally {
                    MDC.clear();
                    SecurityContextHolder.clearContext();
                }
            };
        };
    }

    /**
     * Spring's default TaskScheduler pool is a single thread, which would let the sweep
     * and reaper block each other. Kept distinct from the @Async executor above.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ingestion-scheduler-");
        scheduler.initialize();
        return scheduler;
    }
}
