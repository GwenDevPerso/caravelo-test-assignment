# AI_WORKFLOW

How this project was built with AI. This is a living log: it is updated as the work progresses. Sections marked **TODO (author)** can only be filled in by the author.

## 1. Tools and models

| Tool | Used for | Why |
| --- | --- | --- |
| **Claude Code** (CLI), model **Claude Opus 5.5** | Reading the spec, planning, scaffolding, writing code, tests and docs | Works directly in the repo and terminal: it can run `./mvnw verify` and Docker itself and read the failures, so its claims are checked against real output. Plan mode keeps it from editing before a plan is approved. |
| **Spring Initializr** (`start.spring.io`, called with `curl`) | Project skeleton | Generates the official Spring Boot 4.1.2 layout instead of the model writing a `pom.xml` from memory, which risks outdated versions. |

**TODO (author):** other tools used (e.g. for writing `SPEC.md`), if any.

## 2. Harness

Everything below is committed in the repo, except the global skills (§2.6), which live in the author's `~/.claude`.

### 2.1 Context and rules

| File | Role |
| --- | --- |
| [`CLAUDE.md`](CLAUDE.md) | Loaded in every session. `SPEC.md` is the source of truth and changes first, in its own commit; core never depends on adapters; every query scoped by `tenantId`; Flyway owns the schema; one test per acceptance criterion (`ACn — …`); tests for a new AC written first by `test-designer`; `spec-reviewer` before committing a feature; `./mvnw verify` green before "done". |
| [`SPEC.md`](SPEC.md) | Spec-Driven Development target: scope, tenant model, mapping rules R1–R13, failure matrix, acceptance criteria AC1–AC20. Every agent reads it. |
| Plan files | Every task starts in plan mode; the plan is written to a file and approved before any edit. |

### 2.2 Settings — [`.claude/settings.json`](.claude/settings.json)

| Setting | Value | Why |
| --- | --- | --- |
| `permissions.allow` | `./mvnw` (build/tests without confirmation), `docker compose`, `docker ps`, read-only git (`status`, `diff`, `log`) | Fast red/green loop without a prompt for every build. |
| `permissions.deny` | `Read(./.env)`, `Read(./.env.*)`, `Read(**/.env)` | `.env` holds the local secrets; the model never needs to see them. |
| `hooks` | `PreToolUse` → `guard-bash.sh`, `PostToolUse` → `compile-java.sh` (see §2.4) | Guardrails and fast feedback enforced by the harness, not by instructions. |
| `enabledPlugins` | superpowers, code-review, ponytail | See §2.6. |

### 2.3 Skills (project)

| Skill | Role |
| --- | --- |
| [`commit`](.claude/skills/commit/SKILL.md) | Conventional Commits with project scopes (`spec`, `adapter`, `sync`, `diff`, `db`, `api`, `tenant`, `resilience`, `obs`, `harness`). Reads the real diff, one logical change per commit, `SPEC.md` committed alone first, no secrets, `BREAKING CHANGE` for schema/API/contract breaks, asks for confirmation before `git commit`. Adapted from a skill written for another project. |
| [`tdd`](.claude/skills/tdd/SKILL.md) | Test-first loop (red → green → `./mvnw verify`). Which test type for which layer (plain JUnit, Mockito, Testcontainers PostgreSQL, WireMock, `@WebMvcTest`, ArchUnit), tests named after the SPEC criterion, WireMock recipes for every failure scenario, Spring Boot 4 notes. Adapted from a React Native/Jest skill of another project. |

### 2.4 Hooks

| Hook | Event / scope | Role |
| --- | --- | --- |
| [`guard-bash.sh`](.claude/hooks/guard-bash.sh) | `PreToolUse` on `Bash`, whole project | Blocks any command touching `.env` / `.env.local` (`.env.example` allowed) and any force push (`--force`, `-f`, `-uf`, `--force-with-lease`, `+refspec`). Tested on 20 allow/deny cases. |
| [`compile-java.sh`](.claude/hooks/compile-java.sh) | `PostToolUse` on `Write\|Edit`, whole project | After a `.java` edit: `./mvnw -q -o compile` (`test-compile` for a test file), no tests, ~3–6 s. Compile errors go back to Claude at once, reduced to the `file:[line,col]` lines. |
| [`block-main-source.sh`](.claude/hooks/block-main-source.sh) | `PreToolUse`, declared in `test-designer` only | Blocks any read, search, write or shell command touching `src/main`, `target/` or git history/diffs, and searches without an explicit path, so the black-box rule is enforced, not just requested. |

### 2.5 Subagents

| Subagent | When | Role |
| --- | --- | --- |
| [`test-designer`](.claude/agents/test-designer.md) | Before implementing an AC | Writes the failing tests from `SPEC.md` only (`tdd` skill preloaded, `block-main-source.sh` hook). Writes only under `src/test`. Reports the "assumed API" the implementation must provide and the spec gaps found. |
| [`spec-reviewer`](.claude/agents/spec-reviewer.md) | After a task, before committing | Read-only. Reviews the git diff (since `origin/main` + uncommitted) against `SPEC.md` / `CLAUDE.md`: core/adapter isolation, coverage tables for AC1–AC20, the §4 failure scenarios and R1–R13, `./mvnw verify`. Verdict `OK` / `CHANGES NEEDED`; never fixes. |
| [`frontend-vue`](.claude/agents/frontend-vue.md) | Phase 2 Vue view | Builds `frontend/` only (Vue 3.5 `<script setup>`, vue-router 5, Vite, Vitest), API consumer, never touches the backend. Enforces the official Vue style guide (priorities A+B), `Base*` presentational components, global CSS only in `App.vue`, scoped styles with CSS variables elsewhere, no CSS framework. Design from the author's site gwenaelbihan.fr: tokens extracted from its CSS by scraping and embedded in the agent. |

Intended loop for a feature: `test-designer` (red) → implementation with the `tdd` skill (green, `compile-java.sh` feedback) → `spec-reviewer` → `commit` skill.

### 2.6 Plugins, global skills, MCP

| Component | Role |
| --- | --- |
| Plugin **superpowers** | Process skills: plan mode, brainstorming before design, verification before claiming completion. |
| Plugin **ponytail** | `SessionStart` hook injecting "smallest complete change" rules: no unrequested features, no dependency for a few lines, state what was skipped. |
| Plugin **code-review** | Review of a diff before committing. |
| Global skills `commit` and `tdd` (`~/.claude/skills/`) | Generic versions of the two project skills, reusable in other projects: they first look for the project's own skill / `CLAUDE.md`, then infer conventions from git history and the build file. |
| MCP server `postgres` ([`.mcp.json`](.mcp.json)) | `crystaldba/postgres-mcp` in Docker, restricted (read-only) mode, connection string read from `.env` with `--env-file`. Used to inspect the local database (schema, data after a sync). |
| MCP server Firecrawl | Used once, to scrape the design tokens of gwenaelbihan.fr for `frontend-vue`. |
| Other MCP servers | Available in the session (docs, Vercel…) but not used. |
| Custom slash commands | None. |

## 3. Key prompts

Verbatim, in order. Prompts that shaped the design are marked ★.

1. `read the spec.md` — the model read `SPEC.md` and, unprompted, listed gaps (see §4).
2. ★ `here is the stack i want to use … init the project with springboot last version` — the model queried Initializr for the latest stable version (4.1.2, not the 4.2 RC), proposed a plan (dependencies, what to leave out), then scaffolded.
3. ★ `add those files` (with the brief's deliverables list) — produced this file, `README.md`, `DECISIONS.md`, `CLAUDE.md` and `.claude/settings.json`, and the SPEC fix for the anti-wipe guard.

4. `adapt the skill claude commit for the project` / `modifie le skill tdd pour qu'il s'adapte également au projet` — the two skills, then generic copies in the global config.
5. `j'ai modifié le docker compose pour mettre mes secrets dans le .env, j'ai ajouté un mcp postgresql, lance la base et utilise le mcp`
6. ★ `create a subagent test-designer that will use the tdd skill, it will write the test reading the SPEC.md without viewing the implementation` — led to enforcing the rule with a hook instead of trusting the prompt.
7. ★ `create a subagent spec-reviwer that will read the diff between what has been done during the session and the SPEC / CLAUDE.md, it will verify the core isolation, all criteria and error cover`
8. `add hook, block the read of .env and git push --force, authorise ./mvnw without confirm, hook PostToolUse on .java file to compile fast without the test`
9. ★ `create a frontend-vue agent composition api last version in a frontend folder … use my https://gwenaelbihan.fr/ website design`, refined during plan review with: `follow https://vuejs.org/style-guide/ good practice, limit the style to the component style scoped, only App can have global style, css vars and component reuse them, no css framework, … Base prefix for the presentation one`.

10. ★ `now we will start implementing everything, use the main session for the Java and migration db, tenant1.json and tenant2.json are the example of the api endpoint and generic, test-designer agent to write test, spec-reviewer to verify all and frontend to create the frontend` — led to SPEC §6 (API shapes) and `docs/implementation-contract.md`, so tests, code and frontend could be built in parallel against the same names.
11. `a quoi sert implementation contract ?` — the author questioned the extra document during plan review; the answer (it is what lets black-box tests compile against code they never see) is in CLAUDE.md.

**TODO (author):** prompts used to write `SPEC.md`, if it was written with AI.

## 4. Where the AI got it wrong, and how it was noticed

| # | What | How it was noticed | Fix |
| --- | --- | --- | --- |
| 1 | **Spec contradiction**: the anti-wipe guard (snapshot < 50 % of the *whole* stored network → rejected) would reject Tenant A's fallback to Source B, which has no connecting routes. That contradicts AC12. | The model's review on first reading `SPEC.md` | `SPEC.md` §4.3: the guard counts only stored routes of the types the source covers, consistent with R12. |
| 2 | The generated test configuration used `postgres:latest`, which is unpinned and differs from Compose. | Review of the generated files | Pinned to `postgres:17-alpine`, same image as `docker-compose.yml`. |
| 3 | The spec named "Spring Boot 3" while the requested stack was "latest Spring Boot" (4.1.2). | Comparing the request with the spec | `SPEC.md` §1 updated to Spring Boot 4. |
| 4 | The first `./mvnw verify` failed. Not a code error: Docker Desktop was not running, so Testcontainers could not start. | The test output (`DockerClientFactory`) | The model reported it as unverified instead of claiming success. |
| 5 | The postgres MCP server did not connect: `.mcp.json` used `${DATABASE_URI}`, which Claude Code reads from its own environment (not `.env`), and the URI pointed at `localhost`, i.e. the MCP container itself. | Startup error `CONNECT_TIMEOUT`, then a manual stdio smoke test of the server | `--env-file .env` and `host.docker.internal` in the URI; verified with `select current_user`. |
| 6 | After moving credentials to `.env`, the compose healthcheck still used `pg_isready -U routesync`, logging `FATAL: role "routesync" does not exist` every 5 s. | PostgreSQL logs | Healthcheck uses `$${POSTGRES_USER}` / `$${POSTGRES_DB}`. |
| 7 | First version of `block-main-source.sh` let `grep -rn … .` through (regex expected the `r` flag last). | Table of allow/deny cases run against the hook | Regex fixed, cases re-run. |
| 8 | Subagents launched while the session was in plan mode inherited it and were read-only: they each produced a plan instead of tests/frontend, three times. | Their reports ("plan mode is active, I can't write") | Asked the author to leave plan mode; relaunched them with their own approved plan files. |
| 9 | The secrets guard hook blocked legitimate commands whose *text* mentioned the env file name (a heredoc appending SPEC §6, a script editing this file). | The hook's error | Used the file editor, or a script file, instead. Known limit of a text-matching hook, kept as is (false positives are safe). |
| 10 | Jackson 3 API written from Jackson 2 memory: `isContainerNode()` / `getOriginalMessage()` don't exist in `tools.jackson`. A concurrent Maven run (subagents compiling in parallel) made a compile look green once. | Compile errors, then a full `./mvnw compile` log instead of the filtered hook output | `isContainer()`, `getMessage()`. |
| 11 | Spring Boot 4 no longer auto-configures `RestClient.Builder` with the webmvc starter alone; the app failed to start. | All 48 integration tests red at context load (reported by test-designer), and the first `docker compose up` | `SourceHttpClient` builds its own `RestClient` (no extra starter). |
| 12 | Tests written in parallel with the implementation were never seen red, so they could pass while testing nothing. | Reported by the test-designer agents themselves | Mutation check: removing the R12 condition from `NetworkDiff` and changing `>=` to `>` in `AntiWipeGuard` made 3 tests fail, then restored; spec-reviewer asked to flag tests that would pass on broken behaviour. |

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
