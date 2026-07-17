#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MVN_VERSION=3.9.11
WRAPPER_HOME="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/apache-maven-$MVN_VERSION"
MVN_BIN="$WRAPPER_HOME/apache-maven-$MVN_VERSION/bin/mvn"

if [ ! -x "$MVN_BIN" ]; then
  mkdir -p "$WRAPPER_HOME"
  ARCHIVE="$WRAPPER_HOME/apache-maven-$MVN_VERSION-bin.zip"
  URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MVN_VERSION/apache-maven-$MVN_VERSION-bin.zip"
  curl --fail --location --silent --show-error "$URL" --output "$ARCHIVE"
  unzip -q -o "$ARCHIVE" -d "$WRAPPER_HOME"
fi

exec "$MVN_BIN" -f "$BASE_DIR/pom.xml" "$@"
