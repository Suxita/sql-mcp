package ge.tbc.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public record ConnectionManager(ge.tbc.config.EnvironmentConfig config) {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    public Connection openConnection() throws SQLException {
        String url = buildJdbcUrl();
        Properties props = buildProperties();
        log.debug("Opening JDBC connection to {} / {}", config.getServerName(), config.getDatabaseName());
        Connection conn = DriverManager.getConnection(url, props);
        conn.setReadOnly(true);
        return conn;
    }

    private String buildJdbcUrl() {
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:sqlserver://")
                .append(config.getServerName())
                .append(":").append(config.getPort())
                .append(";databaseName=").append(config.getDatabaseName())
                .append(";encrypt=").append(config.isEncrypt())
                .append(";trustServerCertificate=").append(config.isTrustServerCertificate());

        switch (config.getAuthMode().toLowerCase()) {
            case "windows" -> sb.append(";integratedSecurity=true");
            case "aad" -> sb.append(";authentication=ActiveDirectoryIntegrated");
            // "sql" – credentials go in Properties below
        }
        return sb.toString();
    }

    private Properties buildProperties() {
        Properties p = new Properties();
        if ("sql".equalsIgnoreCase(config.getAuthMode())) {
            if (config.getUsername() != null) p.setProperty("user", config.getUsername());
            if (config.getPassword() != null) p.setProperty("password", config.getPassword());
        }
        return p;
    }

    public boolean testConnection() {
        try (Connection conn = openConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            log.warn("Connection test failed: {}", e.getMessage());
            return false;
        }
    }
}

