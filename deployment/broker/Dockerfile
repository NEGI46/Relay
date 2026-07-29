FROM gradle:8.13-jdk17@sha256:ec973fb6579488ecba8cd24f4728cd819eda45d6b26f7f44a81682e1c034898f AS build
WORKDIR /src
COPY . .
RUN gradle :broker:installDist --no-daemon

FROM eclipse-temurin:17-jre@sha256:92999aea37688157a53a40bfcb187c30f317422e028045fd5fc5c548fde9e626
WORKDIR /relay
COPY --from=build /src/broker/build/install/broker/ ./broker/
RUN mkdir -p /var/lib/relay-broker
ENV RELAY_BROKER_PROFILE=production \
    RELAY_BROKER_HOST=127.0.0.1 \
    RELAY_BROKER_PORT=8443 \
    RELAY_BROKER_DB_PATH=/var/lib/relay-broker/broker.db
EXPOSE 8443
ENTRYPOINT ["/relay/broker/bin/broker"]
