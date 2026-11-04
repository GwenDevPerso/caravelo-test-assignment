---
name: commit
description: Creates Git commit messages following the Conventional Commits convention for this project. Use whenever the user asks to commit, create a commit message, or after finishing an implementation task that needs to be committed.
---

# Commit

Generate clear commit messages following **Conventional Commits**, tailored to this project (multi-tenant route network sync service: Java 21 / Spring Boot 4 / PostgreSQL, see `SPEC.md`).

## Format

```
<type>(<scope>): <short imperative description>

[optional body: why, not just what; reference spec rules/criteria (R12, AC7) when relevant]

[optional footer: BREAKING CHANGE, refs, Co-Authored-By]
```

## Allowed types

- `feat`: new feature
- `fix`: bug fix
- `refactor`: code change with no behavior change
- `chore`: misc tasks (deps, config, tooling)
- `docs`: documentation only (SPEC, DECISIONS, README, AI_WORKFLOW, CLAUDE.md)
- `test`: adding/modifying tests
- `style`: formatting, no logic change
- `perf`: performance improvement
- `build`: Maven, Dockerfile, docker-compose

## Project scopes

Align the scope with the modules described in `SPEC.md` and `CLAUDE.md`:

- `spec`: changes to `SPEC.md`
- `adapter`: adapter contract or a specific adapter (`adapter-a`, `backup-provider`, `json-mapping` when only one is touched)
- `sync`: sync orchestration, fallback, per-tenant lock
- `diff`: change detection, anti-wipe guard
- `db`: Flyway migrations, entities, repositories
- `api`: REST endpoints (routes, changes, sync trigger, health)
- `tenant`: tenant configuration model and onboarding
- `resilience`: retry, timeouts, backoff
- `obs`: logging, MDC, health signals
- `harness`: `CLAUDE.md`, `.claude/` (settings, skills)

Omit the scope when the change spans the whole project (e.g. initial scaffolding).

Example: `feat(adapter): add tenant-a-stations adapter with R9–R11 mapping`

## Rules

1. **Always look at the actual diff before writing the message** — never guess the content from the user's request alone. Use `git diff --staged` (or `git diff` and `git status` if nothing is staged) to see what actually changed.
2. **One commit = one logical change.** If the diff mixes several unrelated topics (e.g. an adapter + an API endpoint), flag it to the user and suggest splitting into multiple commits instead of writing a catch-all message.
3. **Spec first, on its own.** A change to `SPEC.md` is committed alone, as `docs(spec): …`, before the code that implements it. If the diff mixes `SPEC.md` with code, propose two commits.
4. **Short description, imperative mood** ("add", "fix", "remove" — not "added" or "adds the feature that..."), max ~72 characters.
5. **The body explains why**, not a line-by-line restatement of the diff. Omit it if the short message is already clear enough.
6. **Never include sensitive data in the message or in the commit**: no API keys, tokens, real provider URLs with keys, or `.env` content. If the diff contains one, stop and warn the user.
7. **BREAKING CHANGE** in the footer if the change breaks a DB schema already released (a Flyway migration edited instead of added), a REST response shape, the tenant YAML format, or the `RouteSourceAdapter` contract.
8. **Tests before code commits**: for `feat`, `fix`, `refactor` and `perf`, check that `./mvnw verify` was run green in this session; if not, say so before committing.
9. **Attribution**: end the message with the `Co-Authored-By:` trailer given by the session's attribution instructions, when one is provided.
10. Never commit automatically without explicit user confirmation — propose the message, let the user approve it before running `git commit`.

## Examples

```
docs(spec): restrict anti-wipe guard to route types the source covers

The guard compared a fallback snapshot with the whole stored network,
so a fallback to the direct-only backup provider was always rejected,
contradicting AC12.

chore: scaffold Spring Boot 4.1.2 project with Docker Compose

feat(adapter): add generic-backup-provider adapter filtered by airline-iata

fix(diff): ignore CONNECTING routes when the source does not cover them (R12)

feat(db): add routes, sync_runs and route_changes tables

test(resilience): cover primary timeout with retries and fallback (AC7)

docs(harness): adapt commit skill to project scopes
```
