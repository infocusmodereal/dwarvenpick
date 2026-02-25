# AGENTS.md

Contributor playbook for `dwarvenpick`.

## 1) Scope and priorities
- Keep changes scoped to one concern (bug fix, feature slice, or refactor).
- Prefer incremental, reviewable diffs over broad rewrites.
- Preserve behavior unless a change is explicitly requested.
- If work maps to an issue/PR, reference it in the PR description and commit message body when helpful.

## 2) Tech stack and prerequisites
- Backend: Kotlin + Spring Boot (Java 21, Gradle).
- Frontend: React + TypeScript (Node.js 22, npm).
- Local stack: Docker Compose in `/deploy/docker`.
- Optional local k8s validation: Helm + Minikube.

## 3) Branch, commit, and push workflow
- Branch naming: `<type>/<short-slug>` (example: `fix/query-history-overflow`). No other prefixes allowed.
- Commit style: Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, etc.).
- Commit only relevant files; do not bundle unrelated edits.
- Push after each completed task or task slice so progress is visible.

## 4) Required checks before opening/updating a PR
Run the narrowest useful checks for your change, and run full checks for cross-cutting changes.

### Backend
```bash
./gradlew clean ktlintCheck test
```

### Frontend
```bash
cd frontend
npm ci
npm run lint
npm run format:check
npm run test
npm run build
```

### Full local stack smoke test (recommended for integration/UI changes)
```bash
docker compose -f deploy/docker/docker-compose.yml up --build -d
```
Validate:
- UI: `http://localhost:3000`
- API health: `GET /api/health`
- Auth/session flow and one query execution path

When done:
```bash
docker compose -f deploy/docker/docker-compose.yml down -v
```

## 5) CI expectations (GitHub Actions)
- CI must pass on branch/PR before merge.
- Keep workflows green for:
  - backend build/lint/tests
  - frontend lint/format/tests/build
- If CI fails, fix root cause (do not bypass checks).

## 6) Security and compliance guardrails
- Never commit secrets, credentials, private keys, or `.env` contents.
- Do not log sensitive connection data or passwords.
- Keep auth and RBAC protections intact:
  - session + CSRF behavior
  - role checks on admin endpoints
  - datasource access enforcement
- Keep audit/history retention behavior and redaction settings functioning.

## 7) Database and connection best practices
- Use "Connection" terminology in UI/docs (not mixed with "Datasource" unless required by API/model naming).
- Maintain compatibility for supported engines (PostgreSQL, MySQL, MariaDB, Trino, StarRocks).
- Ensure connection pooling remains configurable and sane by default.
- Validate connection test flows for both success and clear sanitized failure messages.

## 8) UX and frontend best practices used in this project
- Keep UI compact and task-focused; remove redundant labels/headings.
- Prefer icon actions for dense toolbars; always provide hover tooltip text.
- Maintain consistent visual hierarchy, spacing, and alignment across pages.
- Keep explorer/workbench layouts responsive, scroll-safe, and non-overflowing.
- Preserve SQL-editor usability: syntax highlighting, tab management, query status clarity, and actionable error messages.

## 9) Documentation discipline
- Update docs when behavior/config changes:
  - `/README.md`
  - `/CONTRIBUTING.md`
  - `/docs/*` (published to GitHub Pages)
- For user-visible changes, include concise release notes in PR description.
- Include local validation evidence (commands + results) in PR.

## 10) Definition of done
A change is done when:
- Code is implemented and formatted/linted.
- Relevant tests pass locally.
- CI is green.
- Docs are updated when applicable.
- Branch is pushed to remote.
