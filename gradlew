#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# The Gradle wrapper JAR is intentionally not committed. The bootstrapper
# downloads the pinned JAR and verifies it before starting Gradle.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$SCRIPT_DIR/gradle/wrapper/bootstrap-gradle-wrapper.sh" "$@"
