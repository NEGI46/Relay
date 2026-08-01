#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu

die() {
  echo "gradle wrapper bootstrap: $*" >&2
  exit 1
}

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES_FILE="$SCRIPT_DIR/gradle-wrapper.properties"
[ -f "$PROPERTIES_FILE" ] || die "missing $PROPERTIES_FILE"

read_property() {
  property_name=$1
  sed -n "s/^${property_name}=//p" "$PROPERTIES_FILE" | head -n 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die "sha256sum or shasum is required"
  fi
}

WRAPPER_URL=$(read_property wrapperJarUrl)
WRAPPER_SHA256=$(read_property wrapperJarSha256)
printf '%s' "$WRAPPER_URL" | grep -Eq '^https://raw\.githubusercontent\.com/gradle/gradle/[0-9a-f]{40}/gradle/wrapper/gradle-wrapper\.jar$' || die "wrapperJarUrl must be an immutable Gradle raw URL"
printf '%s' "$WRAPPER_SHA256" | grep -Eq '^[0-9a-f]{64}$' || die "wrapperJarSha256 must be a lowercase SHA-256 digest"

USER_HOME_DIR=$(CDPATH= cd -- ~ && pwd)
GRADLE_USER_HOME=${GRADLE_USER_HOME:-$USER_HOME_DIR/.gradle}
JAR_DIR="$GRADLE_USER_HOME/wrapper/jars"
JAR_PATH="$JAR_DIR/gradle-wrapper-$WRAPPER_SHA256.jar"
DOWNLOAD_PATH="$JAR_PATH.download.$$"
mkdir -p "$JAR_DIR"

JAR_PROPERTIES_PATH="$JAR_DIR/gradle-wrapper-$WRAPPER_SHA256.properties"

if [ ! -f "$JAR_PROPERTIES_PATH" ] || ! cmp -s "$PROPERTIES_FILE" "$JAR_PROPERTIES_PATH"; then
  cp "$PROPERTIES_FILE" "$JAR_PROPERTIES_PATH"
fi

NEEDS_DOWNLOAD=true
if [ -f "$JAR_PATH" ] && [ "$(sha256_file "$JAR_PATH")" = "$WRAPPER_SHA256" ]; then
  NEEDS_DOWNLOAD=false
fi

if [ "$NEEDS_DOWNLOAD" = true ]; then
  mkdir -p "$JAR_DIR"
  trap 'rm -f "$DOWNLOAD_PATH"' EXIT HUP INT TERM
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error --output "$DOWNLOAD_PATH" "$WRAPPER_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget --no-verbose --output-document="$DOWNLOAD_PATH" "$WRAPPER_URL"
  else
    die "curl or wget is required to download the Gradle wrapper"
  fi
  [ "$(sha256_file "$DOWNLOAD_PATH")" = "$WRAPPER_SHA256" ] || die "downloaded Gradle wrapper failed SHA-256 verification"
  mv -f "$DOWNLOAD_PATH" "$JAR_PATH"
  trap - EXIT HUP INT TERM
fi

if [ -n "${JAVA_HOME:-}" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=$(command -v java || true)
fi
[ -x "$JAVACMD" ] || die "JAVA_HOME or java must point to an executable Java runtime"

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
set -- "-Dorg.gradle.appname=gradlew" -jar "$JAR_PATH" "$@"
eval "set -- $DEFAULT_JVM_OPTS ${JAVA_OPTS:-} ${GRADLE_OPTS:-} \"\$@\""
exec "$JAVACMD" "$@"
