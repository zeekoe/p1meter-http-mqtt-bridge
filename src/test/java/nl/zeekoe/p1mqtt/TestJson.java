package nl.zeekoe.p1mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

final class TestJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestJson() {
    }

    static Map<String, Object> parse(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("not valid JSON: " + json, e);
        }
    }

    static List<Object> parseList(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("not valid JSON: " + json, e);
        }
    }
}
