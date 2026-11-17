package com.edupulse.compliance.rag;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * Tolerant JSON extraction from LLM output. Small local models (e.g. llama3.2) often wrap JSON in
 * prose or markdown fences, or ignore the strict schema of {@code BeanOutputConverter}; this finds
 * the first JSON object/array in the text and parses it.
 */
public final class LenientJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LenientJson() {
    }

    public static Optional<JsonNode> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.replace("```json", "").replace("```", "");
        int obj = t.indexOf('{');
        int arr = t.indexOf('[');
        int start = obj < 0 ? arr : arr < 0 ? obj : Math.min(obj, arr);
        if (start < 0) {
            return Optional.empty();
        }
        char open = t.charAt(start);
        char close = open == '{' ? '}' : ']';
        int end = t.lastIndexOf(close);
        if (end <= start) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readTree(t.substring(start, end + 1)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Case-insensitive field lookup returning text ("" when absent). */
    public static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return "";
        }
        for (var it = node.properties().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().equalsIgnoreCase(field)) {
                JsonNode v = e.getValue();
                return v == null || v.isNull() ? "" : v.isValueNode() ? v.asString() : v.toString();
            }
        }
        return "";
    }

    public static JsonNode field(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (var it = node.properties().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().equalsIgnoreCase(field)) {
                return e.getValue();
            }
        }
        return null;
    }
}
