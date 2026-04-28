package ge.tbc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.tbc.model.CredentialsFilter;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request-scoped bean that holds the DB connections parsed from X-Conn-* headers.
 * Populated by {@link CredentialsFilter} on every incoming request.
 *
 * Header format:  X-Conn-{EnvName}: {"host":"...","port":2020,"database":"...","username":"...","password":"..."}
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class RequestCredentials {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, DbEnvironment> connections = new LinkedHashMap<>();

    /** Called by {@link CredentialsFilter} — parses the JSON header value and stores it. */
    public void addFromHeader(String envName, String json) {
        try {
            DbEnvironment env = MAPPER.readValue(json, DbEnvironment.class);
            env.setName(envName);
            connections.put(envName, env);
        } catch (Exception e) {
            // malformed header — skip silently, tool will return a clear error
        }
    }

    /** Returns the connection config for the named environment, or null if not present. */
    public DbEnvironment get(String envName) {
        return connections.get(envName);
    }

    /** Returns the first connection if available (used as the default). */
    public DbEnvironment getDefault() {
        return connections.isEmpty() ? null : connections.values().iterator().next();
    }

    public Map<String, DbEnvironment> getAll() {
        return Collections.unmodifiableMap(connections);
    }
}
