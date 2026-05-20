package com.vulnwatch.worker.config;

import java.io.InputStream;
import java.util.Properties;

public class AppConfig {
    private static final Properties props = new Properties();

    static {
        try (InputStream in = AppConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception e) {
            System.out.println("[AppConfig] Could not load application.properties — using env vars only");
        }
    }

    public static String get(String key) {
        return System.getenv(key.toUpperCase().replace('.', '_')) != null
                ? System.getenv(key.toUpperCase().replace('.', '_'))
                : props.getProperty(key);
    }

    public static int getInt(String key) {
        return Integer.parseInt(get(key));
    }
}