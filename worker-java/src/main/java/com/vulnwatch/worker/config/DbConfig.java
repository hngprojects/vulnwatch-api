package com.vulnwatch.worker.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DbConfig {

    public static Connection getConnection() throws SQLException {
        String url = System.getenv("DB_URL");
        String username = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");

        if (url == null || username == null || password == null)
            throw new IllegalStateException("DB_URL, DB_USERNAME or DB_PASSWORD not set.");

        // ensure jdbc prefix is present
        if (!url.startsWith("jdbc:postgresql://"))
            url = "jdbc:postgresql://" + url;

        return DriverManager.getConnection(url, username, password);
    }
}