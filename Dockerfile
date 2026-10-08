# ---- Build Stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

# ---- Run Stage ----
FROM eclipse-temurin:17-jre
RUN useradd -r -u 1001 sba
USER sba
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 9900
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
