package ge.tbc.config;

import lombok.Getter;
import lombok.Setter;

/**
 * Represents one database connection parsed from an X-Conn-{Name} request header.
 * All fields are supplied by the end user at call time — nothing is stored on the server.
 */
@Getter
@Setter
public class DbEnvironment {
    private String  name;
    private String  host;
    private int     port= 1433;
    private String  database;
    private String  username;
    private String  password;
    private boolean trustServerCertificate = true;
}
