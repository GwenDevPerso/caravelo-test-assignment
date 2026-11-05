package com.caravelo.routesync.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SPEC §4.1 retry policy, on the HTTP client alone (no Spring), against a WireMock server. */
class SourceHttpClientTest {

    private static final String PATH = "/source";
    private static final String SECRET = "super-secret-key-123";
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(200);

    private static final WireMockServer WM = new WireMockServer(options().dynamicPort());

    private final SourceHttpClient client = new SourceHttpClient(JsonMapper.builder().build(),
            new SyncProperties(false, false, Duration.ofSeconds(1), Duration.ofMillis(300),
                    new SyncProperties.Retry(3, INITIAL_BACKOFF)));

    @BeforeAll
    static void start() {
        WM.start();
    }

    @AfterAll
    static void stop() {
        WM.stop();
    }

    @BeforeEach
    void reset() {
        WM.resetAll();
    }

    private static URI uri() {
        return URI.create(WM.baseUrl() + PATH + "?api_key=" + SECRET);
    }

    /** Request receive times in ms, oldest first. */
    private static List<Long> requestTimes() {
        return WM.getAllServeEvents().stream()
                .map(ServeEvent::getRequest)
                .filter(r -> r.getUrl().startsWith(PATH))
                .map(r -> r.getLoggedDate().getTime())
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private SourceFetchException fetchFails() {
        SourceFetchException e = catchThrowableOfType(SourceFetchException.class, () -> client.getJson(uri()));
        assertThat(e).as("SourceFetchException thrown").isNotNull();
        return e;
    }

    private static void assertNoSecret(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).as("message of %s", t.getClass().getName()).doesNotContain(SECRET);
        }
    }

    @Test
    @DisplayName("AC7 — read timeout: called 3 times with increasing delays between attempts")
    void ac7_timeoutRetriedWithIncreasingDelays() {
        WM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(1000).withBody("[]")));

        SourceFetchException e = fetchFails();

        List<Long> times = requestTimes();
        assertThat(times).hasSize(3);
        long gap1 = times.get(1) - times.get(0);
        long gap2 = times.get(2) - times.get(1);
        assertThat(gap2).as("2nd gap (%d ms) > 1st gap (%d ms)", gap2, gap1).isGreaterThan(gap1);
        assertThat(e.retryable()).isTrue();
    }

    @Test
    @DisplayName("§4.1 — 5xx: at most 3 attempts, backoff doubles (~200 ms then ~400 ms, ±20 % jitter)")
    void serverErrorRetriedWithExponentialBackoff() {
        WM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        fetchFails();

        List<Long> times = requestTimes();
        assertThat(times).hasSize(3);
        long gap1 = times.get(1) - times.get(0);
        long gap2 = times.get(2) - times.get(1);
        assertThat(gap1).as("1st gap").isGreaterThanOrEqualTo(150);
        assertThat(gap2).as("2nd gap (%d ms) > 1st gap (%d ms)", gap2, gap1).isGreaterThan(gap1);
        assertThat(gap2).as("2nd gap").isGreaterThanOrEqualTo(300);
    }

    @Test
    @DisplayName("§4.1 — 429 with Retry-After: 1 then 200: retried after at least ~1 s, JSON returned")
    void rateLimitedHonoursRetryAfter() {
        WM.stubFor(get(urlPathEqualTo(PATH)).inScenario("rl").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")).willSetStateTo("ok"));
        WM.stubFor(get(urlPathEqualTo(PATH)).inScenario("rl").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"a\":1}]")));

        JsonNode body = client.getJson(uri());

        assertThat(body.isArray()).isTrue();
        assertThat(body.get(0).path("a").asInt()).isEqualTo(1);
        List<Long> times = requestTimes();
        assertThat(times).hasSize(2);
        assertThat(times.get(1) - times.get(0)).as("gap honours Retry-After: 1").isGreaterThanOrEqualTo(950);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 401, 403, 404})
    @DisplayName("§4.1 — 400/401/403/404: called once, not retryable")
    void clientErrorsNotRetried(int status) {
        WM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)));

        SourceFetchException e = fetchFails();

        assertThat(requestTimes()).hasSize(1);
        assertThat(e.retryable()).isFalse();
    }

    @Test
    @DisplayName("§4.1 — malformed JSON body with 200: called once, not retryable")
    void malformedBodyNotRetried() {
        WM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("[{\"stationCode\": \"ACA\", ")));

        SourceFetchException e = fetchFails();

        assertThat(requestTimes()).hasSize(1);
        assertThat(e.retryable()).isFalse();
    }

    @Test
    @DisplayName("A10 — HTML body with 200: malformed, called once, not retryable")
    void htmlBodyNotRetried() {
        WM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html><body>Error</body></html>")));

        SourceFetchException e = fetchFails();

        assertThat(requestTimes()).hasSize(1);
        assertThat(e.retryable()).isFalse();
    }

    @Test
    @DisplayName("§2 secrets — api_key never appears in the exception message (non-retried 401)")
    void apiKeyNotInExceptionOn401() {
        WM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertNoSecret(fetchFails());
    }

    @Test
    @DisplayName("§2 secrets — api_key never appears in the exception or in logs after retries are exhausted")
    @ExtendWith(OutputCaptureExtension.class)
    void apiKeyNotInExceptionOrLogsAfterRetries(CapturedOutput output) {
        WM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        SourceFetchException e = fetchFails();

        assertNoSecret(e);
        assertThat(output.getAll()).doesNotContain(SECRET);
    }
}
