package ge.tbc.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.tbc.SessionContext;
import ge.tbc.config.DbEnvironment;
import ge.tbc.config.RequestCredentials;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
public class CredentialsFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(CredentialsFilter.class);
    private static final String HEADER_PREFIX = "x-conn-";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RequestCredentials credentials;

    public CredentialsFilter(RequestCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        String sessionId = http.getHeader("mcp-session-id");
        SessionContext.SESSION_ID.set(sessionId);
        try {
            credentials.store(sessionId, parseConnHeaders(http));
            chain.doFilter(request, response);
        } finally {
            SessionContext.SESSION_ID.remove();
        }
    }

    private Map<String, DbEnvironment> parseConnHeaders(HttpServletRequest http) {
        Map<String, DbEnvironment> result = new LinkedHashMap<>();
        Collections.list(http.getHeaderNames()).stream()
                .filter(name -> name.toLowerCase().startsWith(HEADER_PREFIX))
                .forEach(name -> parseHeader(name, http.getHeader(name), result));
        log.debug("{} {} – parsed {} X-Conn header(s)", http.getMethod(), http.getRequestURI(), result.size());
        return result;
    }

    private void parseHeader(String name, String json, Map<String, DbEnvironment> target) {
        String envName = name.substring(HEADER_PREFIX.length());
        try {
            DbEnvironment env = MAPPER.readValue(json, DbEnvironment.class);
            env.setName(envName);
            target.put(envName, env);
        } catch (Exception e) {
            log.warn("Failed to parse X-Conn-{}: {}", envName, e.getMessage());
        }
    }
}
