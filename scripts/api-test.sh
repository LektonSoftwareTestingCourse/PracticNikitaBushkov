#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
[ -f .env ] || cp .env.example .env

if [[ "${SKIP_STACK:-0}" != "1" ]]; then
  echo "==> Building and starting the stand"
  docker compose up -d --build
fi

echo "==> Waiting for Gateway health"
for _ in {1..150}; do
  curl -fsS http://localhost:8080/health >/dev/null 2>&1 && break
  sleep 2
done

exec mvn -B -f services/pom.xml -Papi-tests test
