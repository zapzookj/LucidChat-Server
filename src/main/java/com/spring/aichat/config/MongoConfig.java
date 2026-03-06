package com.spring.aichat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * [Phase 5] MongoDB Configuration
 *
 * [설정 사항]
 * 1. @EnableMongoAuditing — @CreatedDate 자동 주입 활성화
 * 2. @EnableMongoRepositories — ChatLogMongoRepository 스캔 범위 지정
 *
 * [application.yml 필수 설정]
 * spring:
 *   data:
 *     mongodb:
 *       uri: mongodb://localhost:27017/lucidchat
 *       # 프로덕션:
 *       # uri: mongodb+srv://<user>:<pass>@<cluster>.mongodb.net/lucidchat?retryWrites=true&w=majority
 *
 * [JPA와의 공존]
 * - JPA: @EnableJpaRepositories(basePackages = "com.spring.aichat.domain") — 기존 그대로
 * - MongoDB: @EnableMongoRepositories — MongoRepository 인터페이스만 스캔
 * - Spring Data는 리포지토리의 extends 타입(JpaRepository vs MongoRepository)으로 자동 판별
 *
 * [인덱스 자동 생성]
 * ChatLogDocument의 @CompoundIndex 어노테이션에 의해
 * 애플리케이션 시작 시 MongoDB에 인덱스가 자동 생성됨.
 * spring.data.mongodb.auto-index-creation=true 설정 필요.
 */
@Configuration
@EnableMongoAuditing
@EnableMongoRepositories(basePackages = "com.spring.aichat.domain.chat")
public class MongoConfig {
    // Spring Boot auto-configuration이 application.yml의 spring.data.mongodb 속성을 읽어
    // MongoClient, MongoTemplate 등을 자동으로 설정.
    // 추가 커스텀 설정이 필요하면 여기에 @Bean 정의.
}