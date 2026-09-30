package nl.zeekoe.p1mqtt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SValueParserTest {

    private static final String RAW_QUERY =
            "type=command&param=udevice&idx=294&nvalue=0&svalue=mEVLT=10584731;mEVHT=10414854;"
                    + "meOLT=3255478;mEOHT=7207975;mEAV=418;mEAT=0";

    @Test
    void parsesTheQueryStringOfTheRealSender() {
        Map<String, String> params = SValueParser.parseQuery(RAW_QUERY);

        assertEquals("command", params.get("type"));
        assertEquals("udevice", params.get("param"));
        assertEquals("294", params.get("idx"));
        assertEquals("0", params.get("nvalue"));
        assertEquals("mEVLT=10584731;mEVHT=10414854;meOLT=3255478;mEOHT=7207975;mEAV=418;mEAT=0", params.get("svalue"));
    }

    @Test
    void parsesTheSvaluePairs() {
        Map<String, String> values = SValueParser.parseValues(
                "mEVLT=10584731;mEVHT=10414854;meOLT=3255478;mEOHT=7207975;mEAV=418;mEAT=0");

        assertEquals(6, values.size());
        assertEquals("10584731", values.get("mEVLT"));
        assertEquals("418", values.get("mEAV"));
        assertEquals("0", values.get("mEAT"));
    }

    @Test
    void parsesPercentEncodedSvalue() {
        Map<String, String> params = SValueParser.parseQuery(
                "type=command&svalue=mEAV%3D418%3BmEAT%3D1234&token=abc%2B123");

        assertEquals("mEAV=418;mEAT=1234", params.get("svalue"));
        assertEquals("abc+123", params.get("token"));
    }

    @Test
    void toleratesWhitespaceTrailingSeparatorsAndPipes() {
        Map<String, String> values = SValueParser.parseValues(" mEAV = 418 ; mEAT=0; |mEVLT=12; ");

        assertEquals("418", values.get("mEAV"));
        assertEquals("0", values.get("mEAT"));
        assertEquals("12", values.get("mEVLT"));
    }

    @Test
    void keepsUnknownAndNonNumericValues() {
        Map<String, String> values = SValueParser.parseValues("mEAV=418;TARIFF=2;GAS=1234.567;SOMETHING");

        assertEquals("418", values.get("mEAV"));
        assertEquals("2", values.get("TARIFF"));
        assertEquals("1234.567", values.get("GAS"));
        assertEquals("", values.get("SOMETHING"));
    }

    @Test
    void handlesMissingInput() {
        assertTrue(SValueParser.parseQuery(null).isEmpty());
        assertTrue(SValueParser.parseQuery("").isEmpty());
        assertTrue(SValueParser.parseQuery("&&").isEmpty());
        assertTrue(SValueParser.parseValues(null).isEmpty());
        assertTrue(SValueParser.parseValues("   ").isEmpty());
        assertTrue(SValueParser.parseValues(";;").isEmpty());
    }

    @Test
    void decodesAndEncodesWithoutTouchingPlusSigns() {
        assertEquals("a=b;c", SValueParser.decode("a%3Db%3Bc"));
        assertEquals("a+b", SValueParser.decode("a+b"));
        assertEquals("a%3Db%3Bc", SValueParser.encode("a=b;c"));
        assertEquals("kWh", SValueParser.encode("kWh"));
        assertEquals("a+b", SValueParser.encode("a+b"));
        assertEquals("kWh", SValueParser.decode("%6B%57%68"));
        assertEquals("", SValueParser.decode(null));
    }

    @Test
    void valuesAreFoundRegardlessOfCase() {
        P1Reading reading = P1Reading.of(SValueParser.parseValues("meav=418;MEAT=7"), "1", java.time.Instant.EPOCH);

        assertEquals(418, reading.number("mEAV").orElseThrow().intValue());
        assertEquals(7, reading.number("mEAT").orElseThrow().intValue());
        assertEquals(425, reading.total(List.of("mEAV", "mEAT")).orElseThrow().intValue());
    }
}
