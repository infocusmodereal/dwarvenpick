# Contributing to dwarvenpick

Thanks for contributing to `dwarvenpick`.

## Development workflow

1. Create a focused branch for your change.
2. Keep diffs small and scoped to one concern.
3. Run relevant local checks before opening a PR.

## Local checks

Backend:

```bash
./gradlew clean ktlintCheck test
```

Frontend:

```bash
cd frontend
npm ci
npm run lint
npm run format:check
npm run test
npm run build
```

## Commit and branch conventions

- Use Conventional Commits for commit messages (for example `feat: add version endpoint`).
- Use short, conventional-style branch names (for example `feat/add-version-endpoint`).

## Pull requests

- Describe the intent and user impact clearly.
- Reference related roadmap item(s) when applicable.
- Include test evidence in the PR description.
