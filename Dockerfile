# Stage 1: Build
# Dùng Maven image để build — không cần cài Maven trên host
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml trước để tận dụng Docker layer cache
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code và build (skip test — test chạy riêng trong CI)
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Runtime
# JRE nhẹ hơn JDK — image nhỏ gọn
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Non-root user vì lý do security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy JAR từ stage builder
COPY --from=builder /app/target/*.jar app.jar

# Đổi owner về spring user
RUN chown spring:spring app.jar

USER spring

# Expose port
EXPOSE 8080

# JVM flags:
# -XX:+UseContainerSupport: đọc CPU/memory limits từ container
# -XX:MaxRAMPercentage=75.0: dùng tối đa 75% RAM container
# -Djava.security.egd=...: tăng tốc SecureRandom
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
