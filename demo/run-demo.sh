#!/usr/bin/env bash
# Runs the 3-session demo (SPEC R9). Requires a running PostgreSQL+pgvector.
# Override DB connection via the MEMSTORE_DB_* environment variables.
set -euo pipefail

cd "$(dirname "$0")/.."

DB_URL="${MEMSTORE_DB_URL:-jdbc:postgresql://localhost:5432/memstore}"
DB_USER="${MEMSTORE_DB_USER:-memstore}"
DB_PASSWORD="${MEMSTORE_DB_PASSWORD:-memstore}"

# Uses fake providers by default (deterministic, no keys). Set
# MEMSTORE_LLM_PROVIDER=claude and EMBEDDING_PROVIDER=openai plus the matching
# API keys for a real-model run.
./gradlew demoRun --console=plain \
  -Dspring.datasource.url="$DB_URL" \
  -Dspring.datasource.username="$DB_USER" \
  -Dspring.datasource.password="$DB_PASSWORD" \
  -Dlogging.level.root=WARN
