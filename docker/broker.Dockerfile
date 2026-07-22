# The application distribution is built by Gradle before `docker compose up`.
FROM eclipse-temurin:17-jre

WORKDIR /opt/relay
COPY broker/build/install/broker/lib/ ./lib/

EXPOSE 8443
ENTRYPOINT ["java", "-cp", "/opt/relay/lib/*", "com.example.relay.broker.MainKt"]
