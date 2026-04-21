package ge.tbc.model;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes JSON Lines audit entries for every tool invocation.
 * Auto-redacts password-like keys to protect credentials in logs.
 *
 * Log path is controlled by the AUDIT_LOG_PATH environment variable.
 * Defaults to mcp-audit.jsonl in the working directory.
 */
public class AuditLogger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(password|secret|token|key|credential|auth)", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper;
    private final PrintWriter writer;
    private final String sessionId = UUID.randomUUID().toString();

    public AuditLogger() {
        String path = coalesce(System.getenv("AUDIT_LOG_PATH"), "mcp-audit.jsonl");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        PrintWriter w;
        try {
            w = new PrintWriter(new BufferedWriter(new FileWriter(path, true)));
        } catch (IOException e) {
            log.warn("Cannot open audit log '{}': {}. Audit logging disabled.", path, e.getMessage());
            w = null;
        }
        this.writer = w;
    }

    /**
     * Log a tool invocation.
     *
     * @param toolName   name of the tool called
     * @param arguments  raw arguments map (will be redacted)
     * @param success    whether the tool succeeded
     * @param durationMs wall-clock time
     */
    public void log(String toolName, Map<String, Object> arguments, boolean success, long durationMs) {
        if (writer == null) return;
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sessionId",  sessionId);
            entry.put("timestamp",  Instant.now().toString());
            entry.put("tool",       toolName);
            entry.put("arguments",  redact(arguments));
            entry.put("success",    success);
            entry.put("durationMs", durationMs);
            writer.println(mapper.writeValueAsString(entry));
            writer.flush();
        } catch (Exception e) {
            log.warn("Audit write failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> redact(Map<String, Object> args) {
        if (args == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (SENSITIVE.matcher(e.getKey()).find()) {
                out.put(e.getKey(), "***REDACTED***");
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    @Override
    public void close() {
        if (writer != null) writer.close();
    }

    private static String coalesce(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
