#!/bin/sh
set -eu

die() {
    echo "Gradle Wrapper bootstrap error: $*" >&2
    exit 1
}

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
properties_file="$script_dir/gradle-wrapper.properties"

[ -f "$properties_file" ] || die "missing $properties_file"

read_property() {
    property_name=$1
    awk -F= -v key="$property_name" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$properties_file"
}

wrapper_jar_url=$(read_property wrapperJarUrl)
wrapper_jar_sha256=$(read_property wrapperJarSha256)

printf '%s\n' "$wrapper_jar_url" |
    grep -Eq '^https://raw\.githubusercontent\.com/gradle/gradle/[0-9a-f]{40}/gradle/wrapper/gradle-wrapper\.jar$' ||
    die "wrapperJarUrl must point to an immutable Gradle commit"
printf '%s\n' "$wrapper_jar_sha256" |
    grep -Eq '^[0-9a-f]{64}$' ||
    die "wrapperJarSha256 must be a lowercase SHA-256 digest"

gradle_user_home=$(printenv GRADLE_USER_HOME 2>/dev/null || true)
if [ -z "$gradle_user_home" ]; then
    gradle_user_home=$(CDPATH= cd ~ && pwd)/.gradle
fi

jar_directory="$gradle_user_home/wrapper/jars"
jar_path="$jar_directory/gradle-wrapper-$wrapper_jar_sha256.jar"
mkdir -p "$jar_directory"

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        die "sha256sum or shasum is required"
    fi
}

if [ -f "$jar_path" ] && [ "$(sha256 "$jar_path")" != "$wrapper_jar_sha256" ]; then
    rm -f "$jar_path"
fi

if [ ! -f "$jar_path" ]; then
    temporary_path="$jar_path.tmp.$$"
    trap 'rm -f "$temporary_path" 2>/dev/null || true' EXIT HUP INT TERM
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --retry 3 --connect-timeout 10 --max-time 120 \
            -o "$temporary_path" "$wrapper_jar_url" ||
            die "could not download the Gradle Wrapper JAR"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --tries=3 --timeout=120 -O "$temporary_path" "$wrapper_jar_url" ||
            die "could not download the Gradle Wrapper JAR"
    else
        die "curl or wget is required to download the Gradle Wrapper JAR"
    fi
    [ "$(sha256 "$temporary_path")" = "$wrapper_jar_sha256" ] ||
        die "downloaded Gradle Wrapper JAR failed SHA-256 verification"
    mv -f "$temporary_path" "$jar_path"
    trap - EXIT HUP INT TERM
fi

# Gradle 9.5 resolves its distribution properties beside the wrapper JAR.
properties_cache_path="$jar_directory/gradle-wrapper-$wrapper_jar_sha256.properties"
cp "$properties_file" "$properties_cache_path"

java_home=$(printenv JAVA_HOME 2>/dev/null || true)
if [ -n "$java_home" ]; then
    java_cmd="$java_home/bin/java"
    [ -x "$java_cmd" ] || die "JAVA_HOME does not point to a usable Java installation"
else
    java_cmd=$(command -v java 2>/dev/null) ||
        die "JAVA_HOME is not set and no java command could be found"
fi

default_jvm_opts='-Xmx64m -Xms64m'
java_opts=$(printenv JAVA_OPTS 2>/dev/null || true)
gradle_opts=$(printenv GRADLE_OPTS 2>/dev/null || true)
# Intentional word splitting preserves the conventional Gradle wrapper option interface.
# shellcheck disable=SC2086
exec "$java_cmd" $default_jvm_opts $java_opts $gradle_opts \
    "-Dorg.gradle.appname=gradlew" -jar "$jar_path" "$@"
