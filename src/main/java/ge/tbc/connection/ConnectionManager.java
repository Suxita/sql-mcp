package ge.tbc.connection;

import ge.tbc.config.DbEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private ConnectionManager() {}

    /**
     * Opens a read-only JDBC connection from a {@link DbEnvironment}
     * that was parsed directly from the caller's request header.
     */
    public static Connection open(DbEnvironment env) throws SQLException {
        String url = "jdbc:sqlserver://" + env.getHost() + ":" + env.getPort()
                + ";databaseName=" + env.getDatabase()
                + ";encrypt=true"
                + ";trustServerCertificate=" + env.isTrustServerCertificate();

        Properties props = new Properties();
        props.setProperty("user",     env.getUsername());
        props.setProperty("password", env.getPassword());

        log.debug("Connecting → {}/{}@{}:{}", env.getUsername(),
                env.getDatabase(), env.getHost(), env.getPort());

        Connection conn = DriverManager.getConnection(url, props);
        conn.setReadOnly(true);
        return conn;
    }
}
