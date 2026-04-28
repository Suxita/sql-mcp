package ge.tbc.model;

import ge.tbc.config.RequestCredentials;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;

/**
 * Reads every X-Conn-{EnvName} header from the request and loads it into
 * the request-scoped {@link RequestCredentials} bean.
 *
 * End users add this to their mcp.json:
 * <pre>
 * "headers": {
 *   "X-Conn-Bank2000": "{\"host\":\"q12-sql-01.hq.tbc\",\"port\":2020,\"database\":\"Bank2000\",\"username\":\"USR_Automation\",\"password\":\"...\"}",
 *   "X-Conn-CONSSQL":  "{\"host\":\"q12-sql-01.hq.tbc\",\"port\":17155,\"database\":\"CONSSQL\",\"username\":\"USR_Automation\",\"password\":\"...\"}"
 * }
 * </pre>
 */
@Component
public class CredentialsFilter implements Filter {

    private static final String PREFIX = "x-conn-";

    private final RequestCredentials credentials;

    public CredentialsFilter(RequestCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest http = (HttpServletRequest) request;

        Collections.list(http.getHeaderNames()).forEach(name -> {
            if (name.toLowerCase().startsWith(PREFIX)) {
                String envName = name.substring(PREFIX.length()); // e.g. "Bank2000"
                String json    = http.getHeader(name);
                credentials.addFromHeader(envName, json);
            }
        });

        chain.doFilter(request, response);
    }
}
