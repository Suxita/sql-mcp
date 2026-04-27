package ge.tbc.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnvironmentConfig {

    // Default values
    private static final int DEFAULT_PORT = 1433;
    private static final String DEFAULT_AUTH_MODE = "sql";
    private static final boolean DEFAULT_ENCRYPT = true;
    private static final boolean DEFAULT_TRUST_SERVER_CERTIFICATE = false;

    // Basic environment info
    @JsonProperty("name")
    private String name;

    @JsonProperty("serverName")
    private String serverName;

    @JsonProperty("databaseName")
    private String databaseName;

    // Connection settings
    @JsonProperty("port")
    private int port = DEFAULT_PORT;

    @JsonProperty("authMode")
    private String authMode = DEFAULT_AUTH_MODE;

    @JsonProperty("username")
    private String username;

    @JsonProperty("password")
    private String password;

    @JsonProperty("encrypt")
    private boolean encrypt = DEFAULT_ENCRYPT;

    @JsonProperty("trustServerCertificate")
    private boolean trustServerCertificate = DEFAULT_TRUST_SERVER_CERTIFICATE;



    // Tool access control
    @JsonProperty("allowedTools")
    private List<String> allowedTools;

    @JsonProperty("deniedTools")
    private List<String> deniedTools;
}