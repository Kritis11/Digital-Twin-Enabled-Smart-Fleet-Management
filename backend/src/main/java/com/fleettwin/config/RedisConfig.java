package com.fleettwin.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    /**
     * While Redis is unreachable, commands fail at once instead of queueing until it is back (the
     * client's default). Twins are updated on the thread that stores telemetry: waiting there for
     * Redis would stall ingestion, and readings would be lost at the broker.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastWhenRedisIsDown() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .build());
    }
}
