FROM gcr.io/oss-fuzz-base/base-builder-jvm:ubuntu-24-04

RUN apt-get update && \
    apt-get install -y --no-install-recommends openjdk-17-jdk-headless && \
    rm -rf /var/lib/apt/lists/*

COPY . $SRC/relay
COPY .clusterfuzzlite/build.sh $SRC/

WORKDIR $SRC/relay
