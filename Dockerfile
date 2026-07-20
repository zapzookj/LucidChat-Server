# 경량화된 JRE 이미지를 베이스로 사용 (용량 절감)
FROM eclipse-temurin:17-jre-alpine

# 컨테이너 내부에 작업 디렉토리 생성
WORKDIR /app

# 빌드된 jar 파일을 컨테이너 내부로 복사
# (Gradle 기준 경로입니다. Maven이면 target/*.jar 로 변경하세요)
COPY build/libs/aichat-0.0.1-SNAPSHOT.jar app.jar

# 컨테이너의 8080 포트 개방
EXPOSE 8080

# 컨테이너 실행 시 Spring Boot 구동
ENTRYPOINT ["java", "-jar", "app.jar"]