package ge.tbc.config;

import ge.tbc.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RequestCredentials {

    private static final Logger log = LoggerFactory.getLogger(RequestCredentials.class);

    private final ConcurrentHashMap<String, Map<String, DbEnvironment>> sessionStore = new ConcurrentHashMap<>();

    public void store(String sessionId, Map<String, DbEnvironment> connections) {
        if (sessionId == null || connections == null || connections.isEmpty()) return;
        sessionStore.put(sessionId, Collections.unmodifiableMap(new LinkedHashMap<>(connections)));
        log.debug("Stored {} connection(s) for session {}", connections.size(), sessionId);
    }

    public DbEnvironment get(String envName) {
        if (envName == null || envName.isBlank()) return getDefault();
        Map<String, DbEnvironment> conns = current();
        DbEnvironment direct = conns.get(envName);
        if (direct != null) return direct;
        return conns.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(envName))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public DbEnvironment getDefault() {
        Map<String, DbEnvironment> conns = current();
        return conns.isEmpty() ? null : conns.values().iterator().next();
    }

    public Map<String, DbEnvironment> getAll() {
        return current();
    }

    private Map<String, DbEnvironment> current() {
        String sessionId = SessionContext.SESSION_ID.get();
        log.debug("current() – sessionId={}, knownSessions={}", sessionId, sessionStore.keySet());
        if (sessionId != null) {
            Map<String, DbEnvironment> conns = sessionStore.get(sessionId);
            if (conns != null) return conns;
        }
        return sessionStore.isEmpty() ? Collections.emptyMap()
                : sessionStore.values().iterator().next();
    }
}
