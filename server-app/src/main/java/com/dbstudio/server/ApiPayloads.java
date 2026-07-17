package com.dbstudio.server;

import java.util.LinkedHashMap;
import java.util.Map;

final class ApiPayloads {
    private ApiPayloads() { }
    static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
    static String text(Map<String, ?> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }
    static String required(Map<String, ?> body, String key) {
        String value = text(body, key).trim();
        if (value.isEmpty()) throw new ApiException("INVALID_REQUEST", key + " 不能为空");
        return value;
    }
    static boolean bool(Map<String, ?> body, String key, boolean fallback) {
        Object value = body == null ? null : body.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, ?> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }
}
