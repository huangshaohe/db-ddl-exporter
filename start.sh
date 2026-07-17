#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_JAR="$SCRIPT_DIR/db-ddl-exporter.jar"
if [ ! -f "$APP_JAR" ]; then
  APP_JAR="$SCRIPT_DIR/target/db-ddl-exporter.jar"
fi
if [ ! -f "$APP_JAR" ]; then
  APP_JAR="$SCRIPT_DIR/kingbase-ddl-exporter.jar"
fi
if [ ! -f "$APP_JAR" ]; then
  APP_JAR="$SCRIPT_DIR/target/kingbase-ddl-exporter.jar"
fi
if [ ! -f "$APP_JAR" ]; then
  echo "Application JAR not found. Run mvn clean package first." >&2
  exit 1
fi
exec java -jar "$APP_JAR" "$@"
