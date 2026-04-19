package com.spring.aichat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * [Phase 5.5-Theater] Theater 전용 설정
 *
 * - Prefetch 비동기 실행을 위한 전용 Executor
 * - 메인 요청 스레드와 격리하여 prefetch가 막힐 때 본 흐름에 영향 없도록
 */
@Configuration
@EnableAsync
public class TheaterConfig {

    /**
     * Theater prefetch 전용 Executor
     * - Core pool: 2 (로우 트래픽 전제)
     * - Max pool: 8
     * - Queue: 32 (초과 시 CallerRuns 정책)
     */
    @Bean(name = "theaterPrefetchExecutor")
    public Executor theaterPrefetchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("theater-prefetch-");
        executor.setKeepAliveSeconds(60);
        executor.initialize();
        return executor;
    }
}