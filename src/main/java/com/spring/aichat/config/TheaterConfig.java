package com.spring.aichat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

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

    /**
     * [Phase6/Tier4 / H-17] 배경 이미지 생성 @Async 전용 Executor.
     *   기존엔 executor 미지정 → SimpleAsyncTaskExecutor → 매 호출 새 스레드 → OOM 위험
     *   (pollUntilComplete 최대 3분 점유 + 스레드당 ~1MB 스택).
     *   포화 시 CallerRuns로 호출 스레드에서 직접 실행 → 백프레셔.
     */
    @Bean(name = "backgroundGenExecutor")
    public Executor backgroundGenExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("bg-gen-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * [Phase6/Tier4 / H-17] 일러스트 생성 @Async 전용 Executor.
     *   IllustrationService의 generateAutoIllustration 등에서 사용.
     */
    @Bean(name = "illustrationExecutor")
    public Executor illustrationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("illust-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}