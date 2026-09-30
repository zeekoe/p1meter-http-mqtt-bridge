package nl.zeekoe.p1mqtt;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class BuildInfo {

    private static final String VERSION = load();

    private BuildInfo() {
    }

    public static String version() {
        return VERSION;
    }

    private static String load() {
        Package mainPackage = BuildInfo.class.getPackage();
        if (mainPackage != null && mainPackage.getImplementationVersion() != null) {
            return mainPackage.getImplementationVersion();
        }
        try (InputStream stream = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (stream == null) {
                return "";
            }
            Properties properties = new Properties();
            properties.load(stream);
            return properties.getProperty("version", "");
        } catch (IOException e) {
            return "";
        }
    }
}
