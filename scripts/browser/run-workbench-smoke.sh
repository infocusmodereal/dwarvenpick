#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/deploy/docker/docker-compose.yml"
COMPOSE_OVERRIDE="$ROOT_DIR/deploy/docker/docker-compose.browser-smoke.yml"
COMPOSE_PROJECT_NAME="${BROWSER_SMOKE_COMPOSE_PROJECT:-dwarvenpick-browser-smoke}"
MODE="${BROWSER_SMOKE_MODE:-local}"
REPORT_DIR="${BROWSER_SMOKE_REPORT_DIR:-$ROOT_DIR/build/reports/browser-smoke}"
BACKEND_PORT="${BROWSER_SMOKE_BACKEND_PORT:-8080}"
FRONTEND_PORT="${BROWSER_SMOKE_FRONTEND_PORT:-3000}"
BUILD_IMAGES="${BROWSER_SMOKE_BUILD:-true}"
APP_VERSION="${BROWSER_SMOKE_APP_VERSION:-}"
PLAYWRIGHT_OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/dwarvenpick-browser-smoke.XXXXXX")"
compose_owned=0

export BROWSER_SMOKE_MODE="$MODE"
export BROWSER_SMOKE_REPORT_DIR="$REPORT_DIR"
export BASE_URL="${BASE_URL:-http://localhost:$FRONTEND_PORT}"
export BROWSER_SMOKE_BACKEND_PORT="$BACKEND_PORT"
export BROWSER_SMOKE_FRONTEND_PORT="$FRONTEND_PORT"
export BROWSER_SMOKE_PLAYWRIGHT_OUTPUT_DIR="$PLAYWRIGHT_OUTPUT_DIR"

cleanup() {
  local status="${1:-0}"
  local cleanup_failed=0
  trap - EXIT INT TERM

  if [[ "$compose_owned" -eq 1 ]]; then
    docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" -f "$COMPOSE_OVERRIDE" down -v --remove-orphans --timeout 60 >/dev/null 2>&1 || true
    if [[ -n "$(docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME")" ]] ||
       [[ -n "$(docker volume ls -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME")" ]]; then
      echo "Browser smoke cleanup incomplete; retrying dedicated Compose project removal." >&2
      sleep 2
      docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" -f "$COMPOSE_OVERRIDE" down -v --remove-orphans --timeout 60 || cleanup_failed=1
    fi
    if [[ -n "$(docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME")" ]] ||
       [[ -n "$(docker volume ls -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME")" ]]; then
      echo "Browser smoke cleanup left Docker resources for project $COMPOSE_PROJECT_NAME." >&2
      cleanup_failed=1
    fi
  fi
  rm -rf -- "$PLAYWRIGHT_OUTPUT_DIR"
  rm -rf -- "$REPORT_DIR/playwright"
  if [[ "$status" -eq 0 && "$cleanup_failed" -ne 0 ]]; then
    status=1
  fi
  exit "$status"
}

wait_for_http() {
  local name="$1"
  local url="$2"
  local deadline=$((SECONDS + 240))

  until curl -fsS --connect-timeout 3 --max-time 10 -o /dev/null "$url"; do
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for $name readiness at $url." >&2
      return 1
    fi
    sleep 2
  done
}

wait_for_local_auth() {
  local url="$1"
  local deadline=$((SECONDS + 60))

  until curl -fsS --connect-timeout 3 --max-time 10 "$url" | grep -q '"local"'; do
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for local authentication readiness." >&2
      return 1
    fi
    sleep 2
  done
}

trap 'cleanup $?' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for tool in node npm curl git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool is required for browser smoke." >&2; exit 127; }
done

if [[ -z "$APP_VERSION" ]]; then
  APP_VERSION="$(node -p "require('$ROOT_DIR/frontend/package.json').version")"
fi
export BROWSER_SMOKE_APP_VERSION="$APP_VERSION"
export BROWSER_SMOKE_SOURCE_SHA="${BROWSER_SMOKE_SOURCE_SHA:-$(git -C "$ROOT_DIR" rev-parse HEAD)}"
export BROWSER_SMOKE_SOURCE_REF="${BROWSER_SMOKE_SOURCE_REF:-$(git -C "$ROOT_DIR" branch --show-current)}"
if [[ -z "$(git -C "$ROOT_DIR" status --porcelain)" ]]; then
  export BROWSER_SMOKE_BUILD_TAG="${BROWSER_SMOKE_BUILD_TAG:-browser-smoke-clean}"
else
  export BROWSER_SMOKE_BUILD_TAG="${BROWSER_SMOKE_BUILD_TAG:-browser-smoke-dirty}"
fi

node "$ROOT_DIR/scripts/browser/validate-target.mjs"
mkdir -p "$REPORT_DIR"
rm -rf -- "$REPORT_DIR/playwright"

if [[ "$MODE" == "local" ]]; then
  command -v docker >/dev/null 2>&1 || { echo "docker is required for local browser smoke." >&2; exit 127; }
  docker compose version >/dev/null
  docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" -f "$COMPOSE_OVERRIDE" config --quiet
  compose_owned=1
  if [[ "$BUILD_IMAGES" == "true" ]]; then
    docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" -f "$COMPOSE_OVERRIDE" up --build -d
  elif [[ "$BUILD_IMAGES" == "false" ]]; then
    docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE" -f "$COMPOSE_OVERRIDE" up -d
  else
    echo "BROWSER_SMOKE_BUILD must be true or false." >&2
    exit 1
  fi
  wait_for_http "backend" "${BROWSER_SMOKE_BACKEND_HEALTH_URL:-http://localhost:$BACKEND_PORT/actuator/health}"
  wait_for_http "frontend" "${BASE_URL%/}/"
  wait_for_local_auth "${BASE_URL%/}/api/auth/methods"
elif [[ "$MODE" != "dev" ]]; then
  echo "BROWSER_SMOKE_MODE must be local or dev." >&2
  exit 1
fi

npm --prefix "$ROOT_DIR/frontend" run browser:smoke
echo "Sanitized browser smoke evidence written to $REPORT_DIR/workbench-browser-smoke.json"
