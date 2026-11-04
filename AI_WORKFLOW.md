# AI_WORKFLOW

How this project was built with AI. This is a living log: it is updated as the work progresses. Sections marked **TODO (author)** can only be filled in by the author.

## 1. Tools and models

| Tool | Used for | Why |
| --- | --- | --- |
| **Claude Code** (CLI), model **Claude Opus 5.5** | Reading the spec, planning, scaffolding, writing code, tests and docs | Works directly in the repo and terminal: it can run `./mvnw verify` and Docker itself and read the failures, so its claims are checked against real output. Plan mode keeps it from editing before a plan is approved. |
| **Spring Initializr** (`start.spring.io`, called with `curl`) | Project skeleton | Generates the official Spring Boot 4.1.2 layout instead of the model writing a `pom.xml` from memory, which risks outdated versions. |

**TODO (author):** other tools used (e.g. for writing `SPEC.md`), if any.

## 2. Harness

All files below are committed in the repo.

| File / component | Role |
| --- | --- |
| [`CLAUDE.md`](CLAUDE.md) | Project rules loaded in every session: `SPEC.md` is the source of truth and changes first; core must not depend on adapters; `tenantId` scoping; one test per acceptance criterion; `./mvnw verify` green before "done". |
| [`.claude/settings.json`](.claude/settings.json) | Shared settings: allowed commands (`./mvnw`, `docker compose`, read-only git) and the plugins below. |
| Plugin **superpowers** | Process skills: plan mode, brainstorming before design, verification before claiming completion. |
| Plugin **ponytail** | A `SessionStart` hook that injects "smallest complete change" rules: no unrequested features, no dependency for a few lines, state what was skipped. |
| Plugin **code-review** | Review of a diff before committing. |
| Plan files | Each task starts in plan mode; the approved plan is written to a file and approved before any edit. |
| MCP servers | Several were available in the session (docs, web search, Vercel…) but none were used for this project. |
| Skill [`commit`](.claude/skills/commit/SKILL.md) | Conventional Commits with project scopes (`spec`, `adapter`, `sync`, `diff`, `db`, `api`…); `SPEC.md` changes committed alone first; reads the real diff; asks for confirmation before `git commit`. Adapted from a skill written for another project. |
| Skill [`tdd`](.claude/skills/tdd/SKILL.md) | Test-first loop (red → green → `./mvnw verify`); which test type for which layer (unit/Mockito, Testcontainers, WireMock, ArchUnit); tests named after the SPEC criterion (`AC7 — …`); WireMock recipes for each failure scenario. Adapted from a React Native/Jest skill of another project. |
| Custom commands, subagents | None so far. |

## 3. Key prompts

Verbatim, in order. Prompts that shaped the design are marked ★.

1. `read the spec.md` — the model read `SPEC.md` and, unprompted, listed gaps (see §4).
2. ★ `here is the stack i want to use … init the project with springboot last version` — the model queried Initializr for the latest stable version (4.1.2, not the 4.2 RC), proposed a plan (dependencies, what to leave out), then scaffolded.
3. ★ `add those files` (with the brief's deliverables list) — produced this file, `README.md`, `DECISIONS.md`, `CLAUDE.md` and `.claude/settings.json`, and the SPEC fix for the anti-wipe guard.

**TODO (author):** prompts used to write `SPEC.md`, if it was written with AI.

## 4. Where the AI got it wrong, and how it was noticed

| # | What | How it was noticed | Fix |
| --- | --- | --- | --- |
| 1 | **Spec contradiction**: the anti-wipe guard (snapshot < 50 % of the *whole* stored network → rejected) would reject Tenant A's fallback to Source B, which has no connecting routes. That contradicts AC12. | The model's review on first reading `SPEC.md` | `SPEC.md` §4.3: the guard counts only stored routes of the types the source covers, consistent with R12. |
| 2 | The generated test configuration used `postgres:latest`, which is unpinned and differs from Compose. | Review of the generated files | Pinned to `postgres:17-alpine`, same image as `docker-compose.yml`. |
| 3 | The spec named "Spring Boot 3" while the requested stack was "latest Spring Boot" (4.1.2). | Comparing the request with the spec | `SPEC.md` §1 updated to Spring Boot 4. |
| 4 | The first `./mvnw verify` failed. Not a code error: Docker Desktop was not running, so Testcontainers could not start. | The test output (`DockerClientFactory`) | The model reported it as unverified instead of claiming success. |

## 5. Written or rewritten by hand

**TODO (author):** what you wrote or rewrote yourself, and why.

## 6. Time split (rough)

**TODO (author):** fill in at the end.

| Activity | Time |
| --- | --- |
| Spec | |
| AI-assisted implementation | |
| Review / fixing | |
| Testing | |
