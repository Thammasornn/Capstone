# Spring Boot (Java 25) · ใช้ทั้ง UAT และ Production — ค่าทั้งหมดมาจาก env ตอนรัน (ดู backend/README.md)
#   docker build -t bangmod-backend:latest backend/
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/*.jar app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /app/app.jar app.jar
USER 10001
EXPOSE 8081
ENV TZ=Asia/Bangkok
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
