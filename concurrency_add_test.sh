#!/usr/bin/env bash

# Concurrent write smoke test for the local FiveTech API.
# The test intentionally submits the same unique business key in parallel.
# Expected result: exactly one row is created and all other requests are
# rejected by the uniqueness check or a database unique constraint.

set -Eeuo pipefail

BASE_URL="${BASE_URL:-https://d3ajrxvlugsarb.cloudfront.net/}"
API_PREFIX="${API_PREFIX:-}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
CONCURRENCY="${CONCURRENCY:-20}"
TEST_PREFIX="${TEST_PREFIX:-ct_$(date +%Y%m%d%H%M%S)}"

if [[ -z "$AUTH_TOKEN" ]]; then
  printf 'AUTH_TOKEN is required. Example: AUTH_TOKEN=<jwt> %s\n' "$0" >&2
  exit 2
fi

# Accept either a raw JWT or a complete "Bearer <JWT>" value, but always send
# exactly one Authorization scheme to the backend.
AUTH_TOKEN="${AUTH_TOKEN#Bearer }"
AUTH_TOKEN="${AUTH_TOKEN#bearer }"

if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
  printf 'curl and jq are required. Install jq before running this test.\n' >&2
  exit 2
fi

if ! [[ "$CONCURRENCY" =~ ^[1-9][0-9]*$ ]]; then
  printf 'CONCURRENCY must be a positive integer.\n' >&2
  exit 2
fi

# nickName and roleName are limited to 30 characters by the backend model.
if (( ${#TEST_PREFIX} > 24 )); then
  printf 'TEST_PREFIX must be no longer than 24 characters.\n' >&2
  exit 2
fi

BASE_URL="${BASE_URL%/}"
API_PREFIX="/${API_PREFIX#/}"
[[ "$API_PREFIX" == "/" ]] && API_PREFIX=""
AUTH_HEADER="Authorization: Bearer ${AUTH_TOKEN}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fivetech-concurrency.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

request() {
  local path="$1"
  local payload="$2"
  local output="$3"
  curl --silent --show-error --fail-with-body \
    --connect-timeout 3 --max-time 30 \
    -X POST "${BASE_URL}${API_PREFIX}${path}" \
    -H "$AUTH_HEADER" \
    -H 'Content-Type: application/json' \
    --data "$payload" >"$output" 2>&1 || true
}

get_list() {
  local path="$1"
  local query_name="$2"
  local query_value="$3"
  local output="$4"

  curl --silent --show-error --fail-with-body \
    --connect-timeout 3 --max-time 30 \
    --get "${BASE_URL}${API_PREFIX}${path}" \
    -H "$AUTH_HEADER" \
    -H 'Content-Type: application/json' \
    --data-urlencode "${query_name}=${query_value}" \
    --data-urlencode 'pageNum=1' \
    --data-urlencode 'pageSize=100' >"$output" 2>&1 || true
}

run_parallel() {
  local path="$1"
  local payload="$2"
  local label="$3"
  local i

  for ((i = 1; i <= CONCURRENCY; i++)); do
    request "$path" "$payload" "$WORK_DIR/${label}-${i}.json" &
  done
  wait
}

summarize() {
  local label="$1"
  local success_count
  local total_count
  local code
  local file
  local -a codes=()

  total_count=0
  success_count=0
  for file in "$WORK_DIR"/${label}-*.json; do
    [[ -f "$file" ]] || continue
    if code="$(jq -r 'if type == "object" then (.code // "transport_error") else "transport_error" end' "$file" 2>/dev/null)"; then
      codes+=("$code")
      total_count=$((total_count + 1))
      if [[ "$code" == "200" ]]; then
        success_count=$((success_count + 1))
      fi
    else
      codes+=("transport_error")
      total_count=$((total_count + 1))
    fi
  done

  printf '%s requests: %s, business successes: %s\n' "$label" "$total_count" "$success_count"
  printf 'Response codes:\n'
  if ((${#codes[@]} > 0)); then
    printf '%s\n' "${codes[@]}" | sort | uniq -c | sed 's/^/  /'
  fi
  printf 'Representative responses:\n'
  local shown=0
  for file in "$WORK_DIR"/${label}-*.json; do
    [[ -f "$file" ]] || continue
    if jq -c . "$file" 2>/dev/null; then :; else head -c 300 "$file"; printf '\n'; fi
    shown=$((shown + 1))
    if ((shown >= 5)); then
      break
    fi
  done | sed 's/^/  /'
  printf '\n'
}

USER_NAME="${TEST_PREFIX}_user"
ROLE_KEY="${TEST_PREFIX}_role"
ROLE_NAME="${TEST_PREFIX} role"

printf 'FiveTech concurrent add test\n'
printf 'Base URL: %s%s\n' "$BASE_URL" "$API_PREFIX"
printf 'Concurrency: %s\n' "$CONCURRENCY"
printf 'Test prefix: %s\n\n' "$TEST_PREFIX"

user_payload="$(jq -cn \
  --arg userName "$USER_NAME" \
  --arg nickName "$TEST_PREFIX user" \
  '{userName:$userName,nickName:$nickName,password:"TestOnly-ChangeMe-123!",deptId:100,roleIds:[2],sex:"0",status:"0"}')"

role_payload="$(jq -cn \
  --arg roleName "$ROLE_NAME" \
  --arg roleKey "$ROLE_KEY" \
  '{roleName:$roleName,roleKey:$roleKey,roleSort:99,dataScope:"2",menuCheckStrictly:true,deptCheckStrictly:true,menuIds:[],status:"0",remark:"temporary concurrency test"}')"

printf '%s\n' '1/2 Testing concurrent user creation...'
run_parallel '/system/user' "$user_payload" user
summarize user

printf '%s\n' '2/2 Testing concurrent role creation...'
run_parallel '/system/role' "$role_payload" role
summarize role

get_list '/system/user/list' 'userName' "$USER_NAME" "$WORK_DIR/user-list.json"
get_list '/system/role/list' 'roleKey' "$ROLE_KEY" "$WORK_DIR/role-list.json"

user_matches="$(jq --arg value "$USER_NAME" '[.rows[]? | select(.userName == $value)] | length' "$WORK_DIR/user-list.json" 2>/dev/null || printf '0')"
role_matches="$(jq --arg value "$ROLE_KEY" '[.rows[]? | select(.roleKey == $value)] | length' "$WORK_DIR/role-list.json" 2>/dev/null || printf '0')"

printf '%s\n' 'Database/API uniqueness assertions:'
printf '  Matching users: %s (expected 1)\n' "$user_matches"
printf '  Matching roles: %s (expected 1)\n' "$role_matches"

if [[ "$user_matches" != "1" || "$role_matches" != "1" ]]; then
  printf 'FAIL: concurrent inserts did not produce exactly one row per business key.\n' >&2
  exit 1
fi

printf '\nExpected result: one matching user and one matching role, with all other requests rejected.\n'
printf 'Cleanup is intentionally manual; delete only records with prefix: %s\n' "$TEST_PREFIX"
