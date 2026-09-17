FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /build
COPY . .
RUN mvn -B -DskipTests package

FROM maven:3.9.9-eclipse-temurin-17

RUN useradd --create-home --uid 10001 autotestforge \
    && mkdir -p /app /workspace /home/autotestforge/.autotestforge/cache /home/autotestforge/.m2 \
    && chown -R autotestforge:autotestforge /app /workspace /home/autotestforge

WORKDIR /app
COPY --from=build --chown=autotestforge:autotestforge /build/atf-web/target/atf-web-0.1.0.jar /app/autotestforge.jar

USER autotestforge
EXPOSE 8080

ENV ATF_ALLOWED_ROOTS=/workspace \
    SERVER_ADDRESS=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.io.tmpdir=/tmp"

ENTRYPOINT ["java", "-jar", "/app/autotestforge.jar"]
