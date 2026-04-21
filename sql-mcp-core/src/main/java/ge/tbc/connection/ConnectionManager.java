package ge.tbc.connection;


import ge.tbc.config.EnvironmentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Builds JDBC connections from an {@link EnvironmentConfig}.
 * Supports sql, windows, and aad auth modes.
 *
 * <p>This class does NOT pool connections; open a new connection per tool call
 * and close it in a try-with-resources block. For high-throughput scenarios
 * you can drop in HikariCP here without changing the tool layer.</p>
 */
public class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private final EnvironmentConfig config;

    public ConnectionManager(EnvironmentConfig config) {
        this.config = config;
    }

    /**
     * Opens a fresh JDBC connection. Caller must close it.
     */
    public Connection openConnection() throws SQLException {
        String url = buildJdbcUrl();
        Properties props = buildProperties();
        log.debug("Opening JDBC connection to {} / {}", config.getServerName(), config.getDatabaseName());
        return DriverManager.getConnection(url, props);
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
            case "aad"     -> sb.append(";authentication=ActiveDirectoryIntegrated");
            // "sql" – credentials go in Properties below
        }
        return sb.toString();
    }

    private Properties buildProperties() {
        Properties p = new Properties();
        if ("sql".equalsIgnoreCase(config.getAuthMode())) {
            if (config.getUsername() != null) p.setProperty("user",     config.getUsername());
            if (config.getPassword() != null) p.setProperty("password", config.getPassword());
        }
        return p;
    }

    /** Quick liveness check – used by test_connection tool. */
    public boolean testConnection() {
        try (Connection conn = openConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            log.warn("Connection test failed: {}", e.getMessage());
            return false;
        }
    }

    public EnvironmentConfig getConfig() { return config; }
}
