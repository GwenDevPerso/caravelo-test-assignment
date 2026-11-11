package com.caravelo.routesync.core.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.caravelo.routesync.core.config.SyncProperties;
import com.caravelo.routesync.core.exception.SourceFetchException;

/**
 * GETs a JSON payload from an external source with the retry policy of SPEC §4.1: timeouts, connection errors,
 * 5xx and 429 are retried with exponential backoff and jitter; 4xx and malformed payloads fail at once.
 */
@Component
public class SourceHttpClient {

    private static final Logger log = LoggerFactory.getLogger(SourceHttpClient.class);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    private final RestClient rest;
    private final JsonMapper json;
    private final SyncProperties.Retry retry;

    public SourceHttpClient(JsonMapper json, SyncProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL) // Apps Script endpoints answer with a redirect (A10)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.rest = RestClient.builder().requestFactory(factory).build();
        this.json = json;
        this.retry = properties.retry();
    }

    public JsonNode getJson(URI uri) {
        Duration backoff = retry.initialBackoff();
        for (int attempt = 1; ; attempt++) {
            try {
                return parse(fetch(uri), uri);
            } catch (TransientFailure failure) {
                if (attempt >= retry.maxAttempts()) {
                    throw new SourceFetchException("%s failed after %d attempts: %s"
                            .formatted(redact(uri), attempt, failure.getMessage()), true, failure);
                }
                Duration delay = failure.retryAfter != null ? min(failure.retryAfter, MAX_RETRY_AFTER) : withJitter(backoff);
                log.warn("Attempt {}/{} on {} failed ({}), retrying in {} ms",
                        attempt, retry.maxAttempts(), redact(uri), failure.getMessage(), delay.toMillis());
                sleep(delay);
                backoff = backoff.multipliedBy(2);
            }
        }
    }

    private byte[] fetch(URI uri) {
        try {
            return rest.get().uri(uri).accept(MediaType.APPLICATION_JSON).exchange((request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (status.is5xxServerError() || status.value() == 429) {
                    throw new TransientFailure("HTTP " + status.value(),
                            retryAfter(response.getHeaders().getFirst("Retry-After")));
                }
                if (!status.is2xxSuccessful()) {
                    throw new SourceFetchException("%s answered HTTP %d".formatted(redact(uri), status.value()), false, null);
                }
                return response.getBody().readAllBytes();
            }, false);
        } catch (ResourceAccessException e) { // timeout, connection refused or reset
            throw new TransientFailure(rootMessage(e), null);
        } catch (TransientFailure | SourceFetchException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TransientFailure(rootMessage(e), null);
        }
    }

    private JsonNode parse(byte[] body, URI uri) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || node.isMissingNode() || !node.isContainer()) {
                throw new SourceFetchException(redact(uri) + " returned an empty or non-JSON body", false, null);
            }
            return node;
        } catch (JacksonException e) { // e.g. an HTML error page served with status 200 (A10)
            throw new SourceFetchException(redact(uri) + " returned a malformed payload: " + e.getMessage(), false, e);
        }
    }

    /** Never log query strings: they may carry the API key. */
    static String redact(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority() + (uri.getPath() == null ? "" : uri.getPath());
    }

    private static Duration retryAfter(String header) {
        try {
            return header == null ? null : Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return null; // HTTP-date form: fall back to the normal backoff
        }
    }

    private static Duration withJitter(Duration base) {
        double factor = ThreadLocalRandom.current().nextDouble(0.8, 1.2);
        return Duration.ofMillis(Math.round(base.toMillis() * factor));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceFetchException("Interrupted while waiting to retry", false, e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    private static final class TransientFailure extends RuntimeException {

        private final transient Duration retryAfter;

        TransientFailure(String message, Duration retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }
    }
}
