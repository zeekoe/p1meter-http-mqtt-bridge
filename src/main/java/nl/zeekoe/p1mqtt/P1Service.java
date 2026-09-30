package nl.zeekoe.p1mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class P1Service {

    private static final Logger log = LoggerFactory.getLogger(P1Service.class);

    private final HaPublisher publisher;
    private final List<SensorSpec> specs;
    private final BigDecimal energyScale;
    private final Clock clock;
    private final AtomicReference<P1Reading> lastReading = new AtomicReference<>(P1Reading.empty());
    private final Set<SensorSpec> warnedMissing = EnumSet.noneOf(SensorSpec.class);
    private volatile long receivedCount;

    public P1Service(AppConfig config, HaPublisher publisher) {
        this(config, publisher, Clock.systemUTC());
    }

    public P1Service(AppConfig config, HaPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
        this.energyScale = new BigDecimal(config.getString(AppConfig.HA_ENERGY_SCALE));
        this.specs = activeSpecs(config.booleanValue(AppConfig.HA_DIAGNOSTIC_SENSORS));
    }

    public static List<SensorSpec> activeSpecs(boolean includeDiagnostic) {
        List<SensorSpec> active = new ArrayList<>();
        for (SensorSpec spec : SensorSpec.values()) {
            if (spec.diagnostic() && !includeDiagnostic) {
                continue;
            }
            active.add(spec);
        }
        return List.copyOf(active);
    }

    public HandleResult handle(P1Reading reading) {
        receivedCount++;
        lastReading.set(reading);

        int published = 0;
        int unchanged = 0;
        List<String> problems = new ArrayList<>();
        for (SensorSpec spec : specs) {
            Optional<BigDecimal> value = spec.value(reading, scaleFor(spec));
            if (value.isEmpty()) {
                if (warnedMissing.add(spec)) {
                    log.warn("reading contains none of {} for sensor {}, its value stays unchanged",
                            spec.sourceKeys(), spec.id());
                }
                problems.add(spec.id() + " missing (" + String.join("/", spec.sourceKeys()) + ")");
                continue;
            }
            if (publisher.publishState(spec, Numbers.format(value.get()))) {
                published++;
            } else {
                unchanged++;
            }
        }
        publisher.publishJsonState(reading.numbers());

        String device = reading.deviceId().isBlank() ? "" : " (device " + reading.deviceId() + ")";
        if (problems.isEmpty()) {
            log.info("reading at {}{}: usage {} W, returned {} W, published {}",
                    clock.instant(), device, show(reading, SensorSpec.POWER_USAGE),
                    show(reading, SensorSpec.POWER_RETURNED), published);
        } else {
            log.info("reading at {}{}: published {}, missing {}",
                    clock.instant(), device, published, String.join(", ", problems));
        }
        if (log.isDebugEnabled()) {
            log.debug("raw values {}", reading.values());
        }
        return new HandleResult(published, unchanged, List.copyOf(problems));
    }

    public Optional<P1Reading> lastReading() {
        P1Reading reading = lastReading.get();
        return reading.isEmpty() ? Optional.empty() : Optional.of(reading);
    }

    public long receivedCount() {
        return receivedCount;
    }

    public List<SensorSpec> specs() {
        return specs;
    }

    private BigDecimal scaleFor(SensorSpec spec) {
        return "W".equals(spec.unit()) ? BigDecimal.ONE : energyScale;
    }

    private static String show(P1Reading reading, SensorSpec spec) {
        return reading.total(spec.sourceKeys()).map(Numbers::format).orElse("-");
    }

    public record HandleResult(int published, int unchanged, List<String> problems) {

        public boolean complete() {
            return problems.isEmpty();
        }
    }
}
