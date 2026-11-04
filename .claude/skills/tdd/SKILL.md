---
name: tdd
description: Test-first workflow for this project. Use whenever adding or changing a feature, an adapter, the sync/diff logic, an endpoint, or fixing a bug — write the failing JUnit 5 test (unit test with Mockito, or integration test with Testcontainers PostgreSQL + WireMock) before the implementation, named after the SPEC acceptance criterion it covers.
---

# Test-first workflow

Never write implementation before a test that fails for the right reason.

`SPEC.md` is the target: every acceptance criterion `AC1`–`AC20` (§5) and every mapping rule `R1`–`R13` (§3.4)
has at least one test. Read the criterion before writing the test; the test asserts what the criterion says, not
what the code happens to do.

Stack: **JUnit 5**, **AssertJ** and **Mockito** (all from `spring-boot-starter-test`), **Testcontainers**
PostgreSQL, **WireMock** for the external route APIs, **ArchUnit** for architecture rules. Unit tests are the
default; an integration test is added when the behaviour involves the database, HTTP, transactions or Spring wiring.

## Pick the test type

| What you are changing | Test type | How |
|---|---|---|
| An adapter's mapping (Source A, backup provider, `json-mapping`), rules R1–R13 | Unit | Plain JUnit, JSON fixture in, `SourceResult` out. No Spring, no HTTP. |
| Diff engine, anti-wipe guard, R12 coverage filter | Unit | Plain JUnit on in-memory sets of `CanonicalRoute`. |
| Sync orchestration: fallback order, statuses (`SUCCESS`/`DEGRADED`/`FAILED`/`REJECTED`), lock | Unit | Mockito mocks of the adapters and repositories. |
| Retry, timeouts, 401/429/5xx, malformed body (AC7–AC10) | Integration | `@SpringBootTest` + WireMock stubbing the source. |
| Persistence, idempotency, one transaction, change log (AC5, AC6, AC10, AC11) | Integration | `@SpringBootTest` + Testcontainers PostgreSQL. |
| REST endpoints: response shape, filters, `400` on bad code (AC1, AC3, AC14) | Slice or integration | `@WebMvcTest` with `@MockitoBean` services for shape/validation; full `@SpringBootTest` for end-to-end AC1. |
| Core never depends on adapters (AC17) | Architecture | ArchUnit rule on the package layout. |
| Logs carry `tenantId` + `correlationId` (AC13) | Integration | Capture logs with `OutputCaptureExtension`, assert on the JSON lines. |

Tests mirror the main package (`src/test/java/com/caravelo/routesync/...`). Unit tests end in `Test`, integration
tests in `IntegrationTest`; both are run by Surefire, so `./mvnw verify` runs everything.

## Naming

- The test class or method `@DisplayName` starts with the criterion or rule it covers:
  `@DisplayName("AC7 — primary timeout: 3 attempts with backoff, fallback used, run DEGRADED")`,
  `@DisplayName("R9 — direct route listed twice is unioned and logged")`.
- Method names describe behaviour: `fallsBackWhenPrimaryReturns401`, not `test1`.

## Loop

1. **Write the test** describing the expected behaviour. Bug fix → first a test that reproduces the bug.
2. **Run it and see it fail**: `./mvnw test -Dtest=ClassName` (or `-Dtest='ClassName#method'`). Check the failure
   is the missing behaviour, not a compilation error, a missing bean or a container that did not start.
3. **Implement the minimum** to pass.
4. **Run it green.**
5. **Before finishing**: `./mvnw verify` — all tests, including integration (Docker must be running).

Never weaken or delete a test to make it pass; if the test was wrong, say so and fix it deliberately.

When covering code that already exists, the test passes on the first run. Prove it can fail: break the behaviour
(or the assertion) once, watch the test go red, then restore.

## Unit tests

- Adapters: load payloads from `src/test/resources/fixtures/<source>/` (small, hand-trimmed extracts of the real
  samples, plus one fixture per inconsistency). Assert on the routes **and** on the `MappingIssue`s produced.
- Diff and guard: build `CanonicalRoute` sets inline; cover added, removed, modified, identical input (empty diff),
  empty snapshot, snapshot under the threshold, and a fallback source that does not cover `CONNECTING`.
- Sync orchestration: mock the `RouteSourceAdapter`s with Mockito; assert call order and the persisted run status
  with `verify(...)` / `ArgumentCaptor`. Do not mock the class under test or the diff engine.
- AssertJ for assertions (`assertThat(routes).containsExactlyInAnyOrder(...)`); no JUnit `assertEquals`.
- No dependence on the clock: inject a `java.time.Clock` and pass `Clock.fixed(...)` in tests.

```java
class BackupProviderAdapterTest {

    @Test
    @DisplayName("AC2 — only Y4, non-codeshare, operating flights, de-duplicated by pair")
    void keepsOnlyOperatingFlightsOfTheTenantAirline() {
        var result = adapter.map(fixture("backup-provider/mixed-airlines.json"), "Y4");

        assertThat(result.routes()).containsExactlyInAnyOrder(
                new CanonicalRoute("ACA", "MEX", RouteType.DIRECT, Set.of()),
                new CanonicalRoute("MEX", "ACA", RouteType.DIRECT, Set.of()));
    }
}
```

The names in this template are illustrative until the adapter exists.

## Integration tests

- Import the shared container config: `@Import(TestcontainersConfiguration.class)` (in
  `src/test/java/com/caravelo/routesync/`). It uses `@ServiceConnection`, so no datasource properties are needed.
  Same `postgres:17-alpine` image as `docker-compose.yml`.
- External APIs are WireMock stubs on a random port (`WireMockExtension` with `dynamicPort()`); point the tenant
  source URLs at it with `@DynamicPropertySource`. Never call the real provider URLs from a test.
- Failure scenarios with WireMock: `withFixedDelay(...)` beyond the read timeout (timeout), `withStatus(401)`,
  `withStatus(503)`, `withStatus(429)` + `Retry-After`, `withFault(Fault.CONNECTION_RESET_BY_PEER)`, a non-JSON
  body with status 200 (A10). Assert the number of calls with `verify(exactly(3), getRequestedFor(...))`.
- Retry delays: set the backoff to a few milliseconds in the test properties; assert attempt count and increasing
  order, not wall-clock seconds.
- Each test starts from a known database state: clean the tables in `@BeforeEach` (or use unique tenant ids per
  test). Do not rely on test order. Do not annotate integration tests `@Transactional`: the sync's own transaction
  and rollback are what is under test.
- "Nothing persisted" assertions (AC9–AC11) read the database before and after and compare.

## Setup notes

- Spring Boot 4: `@MockBean` is gone, use `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`);
  `@WebMvcTest` lives in `org.springframework.boot.webmvc.test.autoconfigure`.
- WireMock and ArchUnit are not in the `pom.xml` yet: add `org.wiremock:wiremock-standalone` and
  `com.tngtech.archunit:archunit-junit5` (scope `test`) with the first test that needs them.
- A Testcontainers error mentioning `DockerClientFactory` means Docker is not running — an environment problem,
  not a test failure. Report it; do not skip or disable the test.
