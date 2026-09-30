package nl.zeekoe.p1mqtt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public enum SensorSpec {

    POWER_USAGE(
            "power_usage", "Power Usage", List.of("mEAV"),
            "W", "power", "measurement", "mdi:flash", 0, false),
    POWER_RETURNED(
            "power_returned", "Power Returned", List.of("mEAT"),
            "W", "power", "measurement", "mdi:solar-power", 0, false),
    ENERGY_IMPORTED(
            "energy_imported", "Energy Imported", List.of("mEVLT", "mEVHT"),
            "kWh", "energy", "total_increasing", "mdi:transmission-tower", 3, false),
    ENERGY_EXPORTED(
            "energy_exported", "Energy Exported", List.of("meOLT", "meOHT"),
            "kWh", "energy", "total_increasing", "mdi:solar-power", 3, false),
    ENERGY_IMPORTED_LOW(
            "energy_imported_low", "Energy Imported (low tariff)", List.of("mEVLT"),
            "kWh", "energy", "measurement", "mdi:counter", 3, true),
    ENERGY_IMPORTED_HIGH(
            "energy_imported_high", "Energy Imported (high tariff)", List.of("mEVHT"),
            "kWh", "energy", "measurement", "mdi:counter", 3, true),
    ENERGY_EXPORTED_LOW(
            "energy_exported_low", "Energy Exported (low tariff)", List.of("meOLT"),
            "kWh", "energy", "measurement", "mdi:counter", 3, true),
    ENERGY_EXPORTED_HIGH(
            "energy_exported_high", "Energy Exported (high tariff)", List.of("meOHT"),
            "kWh", "energy", "measurement", "mdi:counter", 3, true);

    private final String id;
    private final String displayName;
    private final List<String> sourceKeys;
    private final String unit;
    private final String deviceClass;
    private final String stateClass;
    private final String icon;
    private final int displayPrecision;
    private final boolean diagnostic;

    SensorSpec(String id, String displayName, List<String> sourceKeys, String unit, String deviceClass, String stateClass,
               String icon, int displayPrecision, boolean diagnostic) {
        this.id = id;
        this.displayName = displayName;
        this.sourceKeys = List.copyOf(sourceKeys);
        this.unit = unit;
        this.deviceClass = deviceClass;
        this.stateClass = stateClass;
        this.icon = icon;
        this.displayPrecision = displayPrecision;
        this.diagnostic = diagnostic;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> sourceKeys() {
        return sourceKeys;
    }

    public String unit() {
        return unit;
    }

    public String deviceClass() {
        return deviceClass;
    }

    public String stateClass() {
        return stateClass;
    }

    public String icon() {
        return icon;
    }

    public int displayPrecision() {
        return displayPrecision;
    }

    public boolean diagnostic() {
        return diagnostic;
    }

    public Optional<BigDecimal> value(P1Reading reading, BigDecimal scale) {
        return reading.total(sourceKeys).map(raw -> raw.multiply(scale));
    }

    public String stateTopic(String topicPrefix) {
        return topicPrefix + "/" + id + "/state";
    }

    public String discoveryTopic(String discoveryPrefix, String deviceId) {
        return discoveryPrefix + "/sensor/" + deviceId + "_" + id + "/config";
    }
}
