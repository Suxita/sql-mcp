package ge.tbc.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents a single named database environment (e.g. "prod", "staging", "dev").
 * Mirrors the environments.json schema from the TypeScript mssql-mcp-core.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnvironmentConfig {

    @JsonProperty("name")
    private String name;

    @JsonProperty("serverName")
    private String serverName;

    @JsonProperty("databaseName")
    private String databaseName;

    @JsonProperty("port")
    private int port = 1433;

    @JsonProperty("authMode")
    private String authMode = "sql"; // sql | windows | aad

    @JsonProperty("username")
    private String username;

    /** Supports ${secret:ENV_VAR} placeholders; resolved at runtime. */
    @JsonProperty("password")
    private String password;

    @JsonProperty("trustServerCertificate")
    private boolean trustServerCertificate = false;

    @JsonProperty("encrypt")
    private boolean encrypt = true;

    /** Optional: restrict which schemas Copilot / the AI may see. */
    @JsonProperty("allowedSchemas")
    private List<String> allowedSchemas;

    @JsonProperty("deniedSchemas")
    private List<String> deniedSchemas;

    /** Optional: restrict which tools are active for this environment. */
    @JsonProperty("allowedTools")
    private List<String> allowedTools;

    @JsonProperty("deniedTools")
    private List<String> deniedTools;

    // ------------------------------------------------------------------ //
    //  Getters & setters                                                   //
    // ------------------------------------------------------------------ //

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getAuthMode() { return authMode; }
    public void setAuthMode(String authMode) { this.authMode = authMode; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isTrustServerCertificate() { return trustServerCertificate; }
    public void setTrustServerCertificate(boolean v) { this.trustServerCertificate = v; }

    public boolean isEncrypt() { return encrypt; }
    public void setEncrypt(boolean encrypt) { this.encrypt = encrypt; }

    public List<String> getAllowedSchemas() { return allowedSchemas; }
    public void setAllowedSchemas(List<String> v) { this.allowedSchemas = v; }

    public List<String> getDeniedSchemas() { return deniedSchemas; }
    public void setDeniedSchemas(List<String> v) { this.deniedSchemas = v; }

    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> v) { this.allowedTools = v; }

    public List<String> getDeniedTools() { return deniedTools; }
    public void setDeniedTools(List<String> v) { this.deniedTools = v; }

    @Override
    public String toString() {
        return "EnvironmentConfig{name='" + name + "', server='" + serverName
                + "', db='" + databaseName + "', auth='" + authMode + "'}";
    }
}
