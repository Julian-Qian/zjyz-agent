#!/usr/bin/env bash
set -euo pipefail

: "${QDRANT_URL:?QDRANT_URL is required}"
: "${QDRANT_API_KEY:?QDRANT_API_KEY is required}"

DIMENSIONS="${AGENT_EMBEDDING_DIMENSIONS:-1024}"
PLATFORM_COLLECTION="${QDRANT_PLATFORM_PHYSICAL_COLLECTION:-zjyz_agent_platform_embedding3_1024_v1}"
TENANT_COLLECTION="${QDRANT_TENANT_PHYSICAL_COLLECTION:-zjyz_agent_tenant_embedding3_1024_v1}"
PLATFORM_ALIAS="${QDRANT_PLATFORM_COLLECTION:-zjyz_agent_platform_current}"
TENANT_ALIAS="${QDRANT_TENANT_COLLECTION:-zjyz_agent_tenant_current}"
BASE_URL="${QDRANT_URL%/}"

qdrant() {
  curl --fail-with-body --silent --show-error \
    -H "api-key: ${QDRANT_API_KEY}" \
    -H 'Content-Type: application/json' "$@"
}

ensure_collection() {
  local collection="$1"
  if qdrant "${BASE_URL}/collections/${collection}" >/dev/null 2>&1; then
    return
  fi
  qdrant -X PUT "${BASE_URL}/collections/${collection}" \
    -d "{\"vectors\":{\"size\":${DIMENSIONS},\"distance\":\"Cosine\"}}" >/dev/null
}

ensure_index() {
  local collection="$1"
  local field="$2"
  qdrant -X PUT "${BASE_URL}/collections/${collection}/index?wait=true" \
    -d "{\"field_name\":\"${field}\",\"field_schema\":\"keyword\"}" >/dev/null || true
}

ensure_alias() {
  local collection="$1"
  local alias="$2"
  if qdrant "${BASE_URL}/aliases/${alias}" >/dev/null 2>&1; then
    return
  fi
  qdrant -X POST "${BASE_URL}/collections/aliases" \
    -d "{\"actions\":[{\"create_alias\":{\"collection_name\":\"${collection}\",\"alias_name\":\"${alias}\"}}]}" >/dev/null
}

ensure_collection "$PLATFORM_COLLECTION"
ensure_collection "$TENANT_COLLECTION"

for field in scope_type domain authority lifecycle_status source_id source_version; do
  ensure_index "$PLATFORM_COLLECTION" "$field"
  ensure_index "$TENANT_COLLECTION" "$field"
done
ensure_index "$TENANT_COLLECTION" cid

ensure_alias "$PLATFORM_COLLECTION" "$PLATFORM_ALIAS"
ensure_alias "$TENANT_COLLECTION" "$TENANT_ALIAS"

qdrant "${BASE_URL}/collections/${PLATFORM_ALIAS}" >/dev/null
qdrant "${BASE_URL}/collections/${TENANT_ALIAS}" >/dev/null

echo "Qdrant knowledge collections are ready."
