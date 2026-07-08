#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPORT_DIR="${REPORT_DIR:-$ROOT_DIR/build/reports/perf}"
BASE_URL="${BASE_URL:-http://localhost:3000}"
PERF_PROFILE="${PERF_PROFILE:-smoke}"
K6_BIN="${K6_BIN:-k6}"

mkdir -p "$REPORT_DIR"

capture_metrics() {
  local label="$1"
  local output="$REPORT_DIR/prometheus-$label.prom"
  if command -v curl >/dev/null 2>&1; then
    curl -fsS "$BASE_URL/actuator/prometheus" -o "$output" || true
  fi
}

capture_metrics before

"$K6_BIN" run \
  --summary-export "$REPORT_DIR/query-smoke-summary.json" \
  -e "BASE_URL=$BASE_URL" \
  -e "PERF_PROFILE=$PERF_PROFILE" \
  -e "CAPTURE_APP_METRICS=${CAPTURE_APP_METRICS:-true}" \
  "$ROOT_DIR/scripts/perf/query-load.k6.js"

capture_metrics after

cat >"$REPORT_DIR/query-smoke-report.txt" <<EOF
Dwarvenpick query perf smoke
base_url=$BASE_URL
profile=$PERF_PROFILE
summary=$REPORT_DIR/query-smoke-summary.json
metrics_before=$REPORT_DIR/prometheus-before.prom
metrics_after=$REPORT_DIR/prometheus-after.prom
EOF

echo "Perf smoke artifacts written to $REPORT_DIR"
