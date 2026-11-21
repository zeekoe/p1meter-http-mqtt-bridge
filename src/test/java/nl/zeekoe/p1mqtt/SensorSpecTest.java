package nl.zeekoe.p1mqtt;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class SensorSpecTest {

    @Test
    void isValid() {
        assertTrue(SensorSpec.POWER_USAGE.isValid(new BigDecimal("0")));
        assertTrue(SensorSpec.POWER_USAGE.isValid(new BigDecimal("10000")));
        assertFalse(SensorSpec.POWER_USAGE.isValid(new BigDecimal("15000")));
        assertFalse(SensorSpec.POWER_USAGE.isValid(new BigDecimal("-1")));

        assertTrue(SensorSpec.POWER_RETURNED.isValid(new BigDecimal("0")));
        assertTrue(SensorSpec.POWER_RETURNED.isValid(new BigDecimal("3300")));
        assertFalse(SensorSpec.POWER_RETURNED.isValid(new BigDecimal("3600")));
        assertFalse(SensorSpec.POWER_RETURNED.isValid(new BigDecimal("-1")));

        // unbounded
        assertTrue(SensorSpec.ENERGY_IMPORTED.isValid(new BigDecimal("-100000")));
        assertTrue(SensorSpec.ENERGY_IMPORTED.isValid(new BigDecimal("10000000")));
    }
}