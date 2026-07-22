FROM gradle:8.13-jdk17 AS build
WORKDIR /src
COPY . .
RUN gradle :broker:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /relay
COPY --from=build /src/broker/build/install/broker/ ./broker/
RUN mkdir -p /var/lib/relay-broker
ENV RELAY_BROKER_PROFILE=production \
    RELAY_BROKER_HOST=127.0.0.1 \
    RELAY_BROKER_PORT=8443 \
    RELAY_BROKER_DB_PATH=/var/lib/relay-broker/broker.db
EXPOSE 8443
ENTRYPOINT ["/relay/broker/bin/broker"]
