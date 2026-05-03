package ge.tbc.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DbEnvironment {
    private String  name;
    private String  host;
    private int     port = 1433;
    private String  database;
    private String  username;
    private String  password;
    private boolean trustServerCertificate = true;
    private boolean encrypt = true;
}
