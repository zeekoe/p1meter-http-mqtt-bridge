package nl.zeekoe.p1mqtt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SValueParser {

    private SValueParser() {
    }

    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            params.put(decode(key.trim()), decode(value));
        }
        return params;
    }

    public static Map<String, String> parseValues(String svalue) {
        Map<String, String> values = new LinkedHashMap<>();
        if (svalue == null || svalue.isBlank()) {
            return values;
        }
        for (String pair : svalue.split("[;|]")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals < 0) {
                values.putIfAbsent(trimmed, "");
                continue;
            }
            String key = trimmed.substring(0, equals).trim();
            if (!key.isEmpty()) {
                values.put(key, trimmed.substring(equals + 1).trim());
            }
        }
        return values;
    }

    public static String decode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value == null ? "" : value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.write((high << 4) + low);
                    i += 2;
                    continue;
                }
            }
            byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(encoded);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    public static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = b & 0xFF;
            char c = (char) unsigned;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '+') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", unsigned));
            }
        }
        return out.toString();
    }

    public static List<String> keys(Map<String, String> values) {
        return List.copyOf(values.keySet());
    }
}
