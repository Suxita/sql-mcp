package ge.tbc.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class AuditLogger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(password|secret|token|key|credential|auth)");
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final PrintWriter writer;
    private final String sessionId = UUID.randomUUID().toString();

    public AuditLogger() {
        this.writer = openWriter(resolveLogPath());
    }

    public void log(String tool, Map<String, Object> args, boolean success, long durationMs) {
        if (writer == null) return;
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sessionId",  sessionId);
            entry.put("timestamp",  Instant.now().toString());
            entry.put("tool",       tool);
            entry.put("arguments",  redact(args));
            entry.put("success",    success);
            entry.put("durationMs", durationMs);
            writer.println(MAPPER.writeValueAsString(entry));
            writer.flush();
        } catch (Exception e) {
            log.warn("Audit write failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (writer != null) writer.close();
    }

    private Map<String, Object> redact(Map<String, Object> args) {
        if (args == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        args.forEach((k, v) -> out.put(k, SENSITIVE.matcher(k).find() ? "***" : v));
        return out;
    }

    private static PrintWriter openWriter(String path) {
        try {
            return new PrintWriter(new BufferedWriter(new FileWriter(path, true)));
        } catch (IOException e) {
            log.warn("Cannot open audit log '{}': {}. Audit logging disabled.", path, e.getMessage());
            return null;
        }
    }

    private static String resolveLogPath() {
        String path = System.getenv("AUDIT_LOG_PATH");
        return (path != null && !path.isBlank()) ? path : "mcp-audit.jsonl";
    }
}
