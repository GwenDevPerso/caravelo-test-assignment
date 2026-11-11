package com.caravelo.routesync.support;

import com.caravelo.routesync.core.source.TenantSourceConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Test helpers to load JSON payloads and build a source config. */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** Reads a classpath resource, e.g. {@code "fixtures/tenant-a/duplicate-route.json"}. */
    public static JsonNode fixture(String classpathPath) {
        try (InputStream in = Json.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture not found: " + classpathPath);
            }
            return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a file relative to the working directory (the repo root under Maven). */
    public static JsonNode file(Path path) {
        try {
            return MAPPER.readTree(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static TenantSourceConfig config(String adapter, String airlineIata) {
        return new TenantSourceConfig("tenant-x", adapter, "http://unused", null, airlineIata, Map.of());
    }
}
