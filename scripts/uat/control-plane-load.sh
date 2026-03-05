#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required." >&2
  exit 1
fi

DWARVENPICK_URL="${DWARVENPICK_URL:-http://localhost:3000}"
DWARVENPICK_USERNAME="${DWARVENPICK_USERNAME:-admin}"
DWARVENPICK_PASSWORD="${DWARVENPICK_PASSWORD:-Admin1234!}"
COOKIE_JAR="${COOKIE_JAR:-/tmp/dwarvenpick-uat-cookie.txt}"
COOKIE_JAR_READONLY=""

MARIADB_DATASOURCE_ID="${MARIADB_DATASOURCE_ID:-mariadb-mart}"
STARROCKS_DATASOURCE_ID="${STARROCKS_DATASOURCE_ID:-starrocks-warehouse}"

MARIADB_SQL="${MARIADB_SQL:-SELECT SLEEP(30) AS slept;}"
STARROCKS_SQL="${STARROCKS_SQL:-SELECT SLEEP(30) AS slept;}"

CONCURRENCY="${CONCURRENCY:-5}"
MODE="${MODE:-burst}" # burst | loop

echo "Logging in to ${DWARVENPICK_URL} as ${DWARVENPICK_USERNAME}..."

csrf_json="$(curl -fsS -c "${COOKIE_JAR}" "${DWARVENPICK_URL}/api/auth/csrf")"
csrf_token="$(echo "${csrf_json}" | jq -r '.token')"
csrf_header="$(echo "${csrf_json}" | jq -r '.headerName')"

login_payload="$(jq -nc --arg u "${DWARVENPICK_USERNAME}" --arg p "${DWARVENPICK_PASSWORD}" '{username:$u,password:$p}')"
curl -fsS -b "${COOKIE_JAR}" -c "${COOKIE_JAR}" \
  -H "Content-Type: application/json" \
  -H "${csrf_header}: ${csrf_token}" \
  -d "${login_payload}" \
  "${DWARVENPICK_URL}/api/auth/login" >/dev/null

csrf_json="$(curl -fsS -b "${COOKIE_JAR}" -c "${COOKIE_JAR}" "${DWARVENPICK_URL}/api/auth/csrf")"
csrf_token="$(echo "${csrf_json}" | jq -r '.token')"
csrf_header="$(echo "${csrf_json}" | jq -r '.headerName')"

tmp_dir="$(mktemp -d)"
COOKIE_JAR_READONLY="${tmp_dir}/cookies.txt"
cp "${COOKIE_JAR}" "${COOKIE_JAR_READONLY}"

echo "OK. Starting load (mode=${MODE}, concurrency=${CONCURRENCY}). Ctrl+C to stop."

post_query() {
  local datasource_id="$1"
  local sql="$2"

  local payload
  payload="$(jq -nc --arg ds "${datasource_id}" --arg sql "${sql}" --arg cp "admin-ro" \
    '{datasourceId:$ds,sql:$sql,credentialProfile:$cp}')"

  # Do not write to the cookie jar from multiple workers (it corrupts the file and breaks CSRF).
  # We only need to read cookies for this UAT load generator.
  local http_code
  http_code="$(
    curl -sS -o /dev/null -w "%{http_code}" \
      -b "${COOKIE_JAR_READONLY}" \
      -H "Content-Type: application/json" \
      -H "${csrf_header}: ${csrf_token}" \
      -d "${payload}" \
      "${DWARVENPICK_URL}/api/queries" || echo "000"
  )"

  case "${http_code}" in
    200) return 0 ;;
    429) return 0 ;; # per-user concurrency limit reached (expected under load)
    423) echo "Query rejected: connection '${datasource_id}' is paused (423)." >&2; return 0 ;;
    401) echo "Query rejected: authentication required (401). Re-login and retry." >&2; return 0 ;;
    403) echo "Query rejected: forbidden/CSRF failed (403). If this repeats, re-run the script." >&2; return 0 ;;
    000) echo "Query failed: network/transport error." >&2; return 0 ;;
    *) echo "Query rejected: HTTP ${http_code}." >&2; return 0 ;;
  esac
}

worker_burst() {
  local worker_id="$1"

  # Avoid starving one datasource when the backend enforces a per-user concurrency cap by
  # alternating which datasource is fired first across workers.
  if (( worker_id % 2 == 0 )); then
    post_query "${MARIADB_DATASOURCE_ID}" "${MARIADB_SQL}" || true
    post_query "${STARROCKS_DATASOURCE_ID}" "${STARROCKS_SQL}" || true
  else
    post_query "${STARROCKS_DATASOURCE_ID}" "${STARROCKS_SQL}" || true
    post_query "${MARIADB_DATASOURCE_ID}" "${MARIADB_SQL}" || true
  fi
}

worker_loop() {
  local worker_id="$1"
  while true; do
    worker_burst "${worker_id}"
    sleep 1
  done
}

cleanup() {
  if [[ -n "${tmp_dir:-}" && -d "${tmp_dir}" ]]; then
    rm -rf "${tmp_dir}" || true
  fi
}

trap 'echo; echo "Stopping..."; cleanup; exit 0' INT TERM
trap 'cleanup' EXIT

for worker_id in $(seq 1 "${CONCURRENCY}"); do
  if [[ "${MODE}" == "loop" ]]; then
    worker_loop "${worker_id}" &
  else
    worker_burst "${worker_id}" &
  fi
done

wait
