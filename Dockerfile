# Stage 1: Build
# Dùng Maven image để build — không cần cài Maven trên host
FROM maven:3.9.9-eclipse-temurin-21 AS builder
ARG SERVICE=order-service

WORKDIR /app

# Copy pom.xml trước để tận dụng Docker layer cache
COPY pom.xml .
COPY services/${SERVICE}/pom.xml services/${SERVICE}/pom.xml
RUN mvn -B -f services/${SERVICE}/pom.xml dependency:go-offline

# Copy source code và build (skip test — test chạy riêng trong CI)
COPY services/${SERVICE}/src services/${SERVICE}/src
RUN mvn -B -f services/${SERVICE}/pom.xml package -DskipTests && \
    cp services/${SERVICE}/target/*.jar /app/app.jar

# Stage 2: Runtime
# JRE nhẹ hơn JDK — image nhỏ gọn
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Non-root user vì lý do security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy JAR từ stage builder
COPY --from=builder /app/app.jar app.jar

# Đổi owner về spring user
RUN chown spring:spring app.jar

USER spring

# Order Service dùng 8080, Inventory Service dùng 8081.
EXPOSE 8080 8081

# JVM flags:
# -XX:+UseContainerSupport: đọc CPU/memory limits từ container
# -XX:MaxRAMPercentage=75.0: dùng tối đa 75% RAM container
# -Djava.security.egd=...: tăng tốc SecureRandom
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
