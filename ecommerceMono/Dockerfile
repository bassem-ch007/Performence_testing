# ---------- build stage ----------
# Ubuntu-based (glibc) image: the protobuf plugin downloads a native protoc binary.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src/ src/
# the Maven repository is cached between builds with a BuildKit cache mount
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q package -DskipTests \
 && cp target/ecommerceMono-*.jar /workspace/app.jar

# ---------- runtime stage ----------
FROM eclipse-temurin:25-jre
WORKDIR /app

# curl is used by the docker-compose healthcheck (/actuator/health)
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system spring && useradd --system --gid spring spring
COPY --from=build --chown=spring:spring /workspace/app.jar app.jar
USER spring

ENV SPRING_PROFILES_ACTIVE=dev \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
