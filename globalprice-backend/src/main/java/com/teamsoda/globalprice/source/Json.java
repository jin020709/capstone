package com.teamsoda.globalprice.source;

import java.math.BigDecimal;

import tools.jackson.databind.JsonNode;

/** 외부 응답 JSON 에서 값을 꺼낼 때 쓰는 작은 도우미 (값이 없으면 null) */
public final class Json {

    private Json() {
    }

    public static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.isValueNode() ? n.asString() : null;
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static String text(JsonNode n, String field) {
        return n == null ? null : text(n.path(field));
    }

    public static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) return n.decimalValue();
        String s = text(n);
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Integer integer(JsonNode n) {
        BigDecimal d = decimal(n);
        return d == null ? null : d.intValue();
    }
}
