#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPORT_DIR="${REPORT_DIR:-$ROOT_DIR/build/reports/perf}"
PERF_PROFILE="${PERF_PROFILE:-smoke}"
K6_BIN="${K6_BIN:-k6}"
REQUIRED_K6_VERSION="${REQUIRED_K6_VERSION:-0.53.0}"
K6_SUMMARY_PATH="$REPORT_DIR/query-smoke-summary.json"
PROMETHEUS_SNAPSHOT_PATH="$REPORT_DIR/prometheus-samples.json"
JSON_REPORT_PATH="$REPORT_DIR/query-smoke-report.json"
TEXT_REPORT_PATH="$REPORT_DIR/query-smoke-report.txt"
sampler_pid=""

mkdir -p "$REPORT_DIR"
export PERF_PROFILE K6_SUMMARY_PATH

node "$ROOT_DIR/scripts/perf/validate-target.mjs"
if ! "$K6_BIN" version | grep -E "^k6 v${REQUIRED_K6_VERSION//./\\.}([[:space:]]|$)" >/dev/null; then
  echo "k6 v$REQUIRED_K6_VERSION is required for comparable evidence." >&2
  exit 1
fi

stop_sampler() {
  if [ -n "$sampler_pid" ] && kill -0 "$sampler_pid" 2>/dev/null; then
    kill -TERM "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" || true
  fi
  sampler_pid=""
}

handle_signal() {
  local status="$1"
  stop_sampler
  exit "$status"
}

trap stop_sampler EXIT
trap 'handle_signal 130' INT
trap 'handle_signal 143' TERM

if [ -n "${PROMETHEUS_URL:-}" ] || [ -n "${PROMETHEUS_NAMESPACE:-}" ]; then
  node "$ROOT_DIR/scripts/perf/prometheus-sampler.mjs" "$PROMETHEUS_SNAPSHOT_PATH" &
  sampler_pid="$!"
else
  node "$ROOT_DIR/scripts/perf/prometheus-sampler.mjs" "$PROMETHEUS_SNAPSHOT_PATH"
fi

set +e
"$K6_BIN" run "$ROOT_DIR/scripts/perf/query-load.k6.js"
k6_status="$?"
set -e

stop_sampler

set +e
node "$ROOT_DIR/scripts/perf/generate-report.mjs" \
  "$K6_SUMMARY_PATH" \
  "$PROMETHEUS_SNAPSHOT_PATH" \
  "$JSON_REPORT_PATH" \
  "$TEXT_REPORT_PATH"
report_status="$?"
set -e

echo "Perf smoke artifacts written to $REPORT_DIR"
if [ "$k6_status" -ne 0 ]; then
  exit "$k6_status"
fi
exit "$report_status"
