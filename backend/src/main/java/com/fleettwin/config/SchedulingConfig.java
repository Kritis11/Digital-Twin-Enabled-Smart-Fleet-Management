package com.fleettwin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class SchedulingConfig {

    /**
     * A scheduled job that fails (Redis or the database being away, typically) is logged in one line
     * and runs again at its next turn. The default is a full stack trace every time, which for a job
     * that runs every few seconds buries everything else in the log.
     */
    @Bean
    ThreadPoolTaskSchedulerCustomizer oneLinePerFailedJob() {
        return scheduler -> scheduler.setErrorHandler(e -> log.warn("Scheduled job failed, will run again: {}", e.toString()));
    }
}
