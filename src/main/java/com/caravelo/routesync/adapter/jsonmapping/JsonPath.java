package com.caravelo.routesync.adapter.jsonmapping;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * The small JSONPath subset of SPEC §6.5: {@code $}, {@code .field}, {@code [n]} and a final {@code [*]}.
 * shortcut: no filters, slices or recursive descent; switch to a JSONPath library if a tenant needs them.
 */
record JsonPath(String expression, List<Segment> segments, boolean wildcard) {

    private static final Pattern SEGMENT = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_-]*)|\\[(\\d+)]|\\[\\*]");

    record Segment(String field, int index) {
    }

    static JsonPath parse(String expression, boolean allowWildcard) {
        if (expression == null || !expression.trim().startsWith("$")) {
            throw new IllegalArgumentException("path must start with '$': " + expression);
        }
        String rest = expression.trim().substring(1);
        List<Segment> segments = new ArrayList<>();
        boolean wildcard = false;
        Matcher matcher = SEGMENT.matcher(rest);
        int position = 0;
        while (position < rest.length()) {
            if (!matcher.find(position) || matcher.start() != position || wildcard) {
                throw new IllegalArgumentException("unsupported path syntax: " + expression);
            }
            if (matcher.group(1) != null) {
                segments.add(new Segment(matcher.group(1), -1));
            } else if (matcher.group(2) != null) {
                segments.add(new Segment(null, Integer.parseInt(matcher.group(2))));
            } else if (allowWildcard) {
                wildcard = true;
            } else {
                throw new IllegalArgumentException("[*] is only allowed at the end of 'items': " + expression);
            }
            position = matcher.end();
        }
        return new JsonPath(expression, List.copyOf(segments), wildcard);
    }

    /** The node at this path, or null when absent. */
    JsonNode resolve(JsonNode root) {
        JsonNode node = root;
        for (Segment segment : segments) {
            if (node == null) {
                return null;
            }
            node = segment.field() != null ? node.get(segment.field()) : node.get(segment.index());
        }
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }
}
