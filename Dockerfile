FROM maven:3.9.9-eclipse-temurin-21 AS builder
ARG SERVICE=order-service
WORKDIR /app

COPY pom.xml .
COPY services/${SERVICE}/pom.xml services/${SERVICE}/pom.xml
COPY services/${SERVICE}/src/main services/${SERVICE}/src/main
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -f services/${SERVICE}/pom.xml package -Dmaven.test.skip=true && \
    cp services/${SERVICE}/target/*.jar /app/app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
COPY --from=builder --chown=spring:spring /app/app.jar app.jar
USER spring
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
