package nl.zeekoe.p1mqtt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record P1Reading(Map<String, String> values, String deviceId, Instant receivedAt) {

    public P1Reading {
        values = Numbers.unmodifiableCopy(values);
        deviceId = deviceId == null ? "" : deviceId;
    }

    public static P1Reading of(Map<String, String> values, String deviceId, Instant receivedAt) {
        return new P1Reading(values, deviceId, receivedAt);
    }

    public static P1Reading empty() {
        return new P1Reading(Map.of(), "", Instant.EPOCH);
    }

    public Optional<String> raw(String key) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    public Optional<BigDecimal> number(String key) {
        return raw(key).map(Numbers::parse);
    }

    public Optional<BigDecimal> total(List<String> keys) {
        BigDecimal sum = null;
        for (String key : keys) {
            Optional<BigDecimal> value = number(key);
            if (value.isEmpty()) {
                continue;
            }
            sum = sum == null ? value.get() : sum.add(value.get());
        }
        return Optional.ofNullable(sum);
    }

    public List<String> missingKeys(List<String> keys) {
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (raw(key).isEmpty() || number(key).isEmpty()) {
                missing.add(key);
            }
        }
        return missing;
    }

    public Map<String, BigDecimal> numbers() {
        Map<String, BigDecimal> numbers = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            BigDecimal parsed = Numbers.parse(value);
            if (parsed != null) {
                numbers.put(key, parsed);
            }
        });
        return numbers;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
