package ge.tbc.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads environments.json and resolves ${secret:ENV_VAR} placeholders.
 * Provides a single active environment at any time.
 */
public class EnvironmentRegistry {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentRegistry.class);
    private static final Pattern SECRET_PATTERN = Pattern.compile("\\$\\{secret:([^}]+)}");

    // Package-private: allows unit tests to construct an empty registry without
    // requiring real environment variables (which EnvironmentRegistry.load() needs).
    EnvironmentRegistry() {}

    private final Map<String, EnvironmentConfig> environments = new LinkedHashMap<>();
    private String activeEnvironmentName;

    /**
     * Load from ENVIRONMENTS_CONFIG_PATH env var, or fall back to
     * a single environment built from individual SERVER_NAME / DATABASE_NAME vars.
     */
    public static EnvironmentRegistry load() {
        EnvironmentRegistry registry = new EnvironmentRegistry();

        String configPath = System.getenv("ENVIRONMENTS_CONFIG_PATH");
        if (configPath != null && !configPath.isBlank()) {
            try {
                registry.loadFromFile(new File(configPath));
            } catch (IOException e) {
                log.warn("Could not load environments file '{}': {}", configPath, e.getMessage());
            }
        }

        // Always register a "default" environment from plain env vars (fallback / convenience)
        String serverName = System.getenv("SERVER_NAME");
        String dbName     = System.getenv("DATABASE_NAME");
        if (serverName != null && dbName != null) {
            EnvironmentConfig def = new EnvironmentConfig();
            def.setName("default");
            def.setServerName(serverName);
            def.setDatabaseName(dbName);
            def.setAuthMode(coalesce(System.getenv("SQL_AUTH_MODE"), "sql"));
            def.setUsername(System.getenv("SQL_USERNAME"));
            def.setPassword(System.getenv("SQL_PASSWORD"));
            String port = System.getenv("SQL_PORT");
            if (port != null) def.setPort(Integer.parseInt(port));
            String trust = System.getenv("TRUST_SERVER_CERTIFICATE");
            if ("true".equalsIgnoreCase(trust)) def.setTrustServerCertificate(true);
            registry.register(def);
            if (registry.activeEnvironmentName == null) {
                registry.activeEnvironmentName = "default";
            }
        }

        if (registry.environments.isEmpty()) {
            throw new IllegalStateException(
                    "No database environments configured. Set SERVER_NAME + DATABASE_NAME environment variables, "
                            + "or point ENVIRONMENTS_CONFIG_PATH to your environments.json.");
        }
        if (registry.activeEnvironmentName == null) {
            registry.activeEnvironmentName = registry.environments.keySet().iterator().next();
        }
        log.info("Loaded {} environment(s). Active: '{}'",
                registry.environments.size(), registry.activeEnvironmentName);
        return registry;
    }

    private void loadFromFile(File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<EnvironmentConfig> list = mapper.readValue(file, new TypeReference<>() {});
        for (EnvironmentConfig env : list) {
            resolveSecrets(env);
            register(env);
        }
        if (!list.isEmpty() && activeEnvironmentName == null) {
            activeEnvironmentName = list.get(0).getName();
        }
    }

    private void resolveSecrets(EnvironmentConfig env) {
        env.setPassword(resolveSecret(env.getPassword()));
        env.setUsername(resolveSecret(env.getUsername()));
    }

    private String resolveSecret(String value) {
        if (value == null) return null;
        Matcher m = SECRET_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String varName = m.group(1);
            String resolved = System.getenv(varName);
            if (resolved == null) {
                log.warn("Secret placeholder ${secret:{}} not found in environment variables", varName);
                resolved = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public void register(EnvironmentConfig env) {
        environments.put(env.getName(), env);
    }

    public EnvironmentConfig getActive() {
        return environments.get(activeEnvironmentName);
    }

    public EnvironmentConfig get(String name) {
        return environments.get(name);
    }

    public Map<String, EnvironmentConfig> getAll() {
        return Collections.unmodifiableMap(environments);
    }

    public void setActive(String name) {
        if (!environments.containsKey(name)) {
            throw new IllegalArgumentException("Unknown environment: " + name);
        }
        this.activeEnvironmentName = name;
        log.info("Switched active environment to '{}'", name);
    }

    public String getActiveEnvironmentName() { return activeEnvironmentName; }

    private static String coalesce(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
