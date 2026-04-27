package ge.tbc.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ge.tbc.config.EnvironmentRegistry;
import ge.tbc.connection.ConnectionManager;
import ge.tbc.model.AuditLogger;
import ge.tbc.model.McpProtocol.*;
import ge.tbc.tools.DataTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * SQL MCP Reader Server – main entry point.
 *
 * Supports two transports, selected via MCP_TRANSPORT env var:
 *
 *   stdio (default) – reads JSON-RPC line-by-line from stdin, writes to stdout.
 *                     Used when the MCP client spawns the process locally
 *                     (e.g. via "command"/"args" in mcp.json).
 *
 *   http            – starts an HTTP server on MCP_PORT (default 8080).
 *                     POST /mcp  → accepts a JSON-RPC request, returns JSON-RPC response.
 *                     Used when the app runs on a remote server/Docker and the client
 *                     connects via "type":"http","url":"http://host:8080/mcp".
 *
 * Tools: read_data, explain_query  (both read-only)
 *
 * Required env vars: SERVER_NAME + DATABASE_NAME  (or ENVIRONMENTS_CONFIG_PATH)
 */
public class McpReaderServer {

    private static final Logger log = LoggerFactory.getLogger(McpReaderServer.class);

    private static final String SERVER_NAME    = "sql-mcp-reader";
    private static final String SERVER_VERSION = "1.0.0";

    private final ObjectMapper mapper;
    private final DataTools    dataTools;
    private final AuditLogger  auditLogger;
    private final Set<String>  ALL_TOOLS;

    public McpReaderServer() {
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EnvironmentRegistry registry = EnvironmentRegistry.load();
        ConnectionManager cm = new ConnectionManager(registry.getActive());
        this.dataTools   = new DataTools();
        this.auditLogger = new AuditLogger();

        Set<String> tools = new LinkedHashSet<>();
        dataTools.definitions().forEach(d -> tools.add(d.name));
        ALL_TOOLS = Collections.unmodifiableSet(tools);

        log.info("SQL MCP Reader started. {} tools registered: {}", ALL_TOOLS.size(), ALL_TOOLS);
    }

    // ------------------------------------------------------------------ //
    //  Transport: stdio                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Blocking loop – reads one JSON-RPC request per line from stdin,
     * writes one JSON response per line to stdout.
     * stderr is used for logging so it never pollutes the protocol stream.
     */
    public void runStdio() throws IOException {
        BufferedReader in  = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter    out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)));

        log.info("[stdio] Listening for MCP messages on stdin…");
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            String response = processRaw(line);
            if (response == null) continue; // notifications have no response
            out.println(response);
            out.flush();
        }
        log.info("[stdio] stdin closed – shutting down.");
        auditLogger.close();
    }

    // ------------------------------------------------------------------ //
    //  Transport: HTTP                                                     //
    // ------------------------------------------------------------------ //

    /**
     * Starts a lightweight HTTP server (JDK built-in, no extra dependencies).

     * Endpoint:  POST /mcp
     *   Request body  – JSON-RPC 2.0 object
     *   Response body – JSON-RPC 2.0 object
     *   Content-Type  – application/json

     * MCP client config:
     *   { "type": "http", "url": "http://<host>:<MCP_PORT>/mcp" }
     */
    public void runHttp() throws IOException {
        int port = Integer.parseInt(coalesce(System.getenv("MCP_PORT"), "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/mcp", this::handleHttpExchange);

        // Health-check endpoint – useful for Docker/K8s liveness probes
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        server.start();
        log.info("[http] MCP server listening on http://0.0.0.0:{}/mcp", port);
        log.info("[http] Health check available at http://0.0.0.0:{}/health", port);

        // Block forever (HTTP server runs in daemon threads)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[http] Shutting down…");
            server.stop(2);
            auditLogger.close();
        }));

        // Park the main thread
        Object lock = new Object();
        synchronized (lock) {
            try { lock.wait(); } catch (InterruptedException ignored) {}
        }
    }

    private void handleHttpExchange(HttpExchange exchange) throws IOException {
        // Only accept POST
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        // CORS – allow any origin so browser-based MCP clients can connect
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        String requestBody;
        try (InputStream is = exchange.getRequestBody()) {
            requestBody = new String(is.readAllBytes());
        }

        String responseJson = processRaw(requestBody.trim());
        if (responseJson == null) {
            // Notification (no id) – return 204 No Content
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        byte[] bytes = responseJson.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ------------------------------------------------------------------ //
    //  Core request processing (shared by both transports)                //
    // ------------------------------------------------------------------ //

    /**
     * Parse one raw JSON-RPC string, dispatch, and return the serialised response.
     * Returns {@code null} for notifications (no "id") that require no response.
     */
    private String processRaw(String json) {
        try {
            Request req = mapper.readValue(json, Request.class);
            Response res = handle(req);
            return res == null ? null : mapper.writeValueAsString(res);
        } catch (Exception e) {
            log.error("Failed to process message: {}", e.getMessage(), e);
            try {
                return mapper.writeValueAsString(
                        Response.error(null, -32700, "Parse error: " + e.getMessage()));
            } catch (Exception ignored) {
                return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Internal error\"}}";
            }
        }
    }

    private Response handle(Request req) {
        return switch (req.method == null ? "" : req.method) {
            case "initialize"                -> handleInitialize(req);
            case "notifications/initialized" -> null;
            case "tools/list"                -> handleToolsList(req);
            case "tools/call"                -> handleToolsCall(req);
            case "ping"                      -> Response.ok(req.id, Map.of());
            default -> Response.error(req.id, -32601, "Method not found: " + req.method);
        };
    }

    private Response handleInitialize(Request req) {
        InitializeResult result = new InitializeResult();
        result.serverInfo = new ServerInfo();
        result.serverInfo.name    = SERVER_NAME;
        result.serverInfo.version = SERVER_VERSION;
        return Response.ok(req.id, result);
    }

    private Response handleToolsList(Request req) {
        return Response.ok(req.id, new ToolsListResult(dataTools.definitions()));
    }

    @SuppressWarnings("unchecked")
    private Response handleToolsCall(Request req) {
        if (req.params == null)
            return Response.error(req.id, -32602, "Missing params");
        String toolName = (String) req.params.get("name");
        if (toolName == null)
            return Response.error(req.id, -32602, "Missing 'name' in params");
        if (!ALL_TOOLS.contains(toolName))
            return Response.error(req.id, -32602, "Unknown tool: " + toolName);

        Map<String, Object> args = (Map<String, Object>) req.params.getOrDefault("arguments", Map.of());
        long start = System.currentTimeMillis();
        ToolCallResult result = dataTools.dispatch(toolName, args);
        auditLogger.log(toolName, args, !result.isError, System.currentTimeMillis() - start);

        return Response.ok(req.id, result);
    }

    // ------------------------------------------------------------------ //
    //  Entry point                                                         //
    // ------------------------------------------------------------------ //

    public static void main(String[] args) {
        try {
            McpReaderServer server = new McpReaderServer();
            String transport = coalesce(System.getenv("MCP_TRANSPORT"), "stdio").toLowerCase();
            switch (transport) {
                case "http"  -> server.runHttp();
                case "stdio" -> server.runStdio();
                default -> {
                    LoggerFactory.getLogger(McpReaderServer.class)
                            .error("Unknown MCP_TRANSPORT '{}'. Use 'stdio' or 'http'.", transport);
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(McpReaderServer.class)
                    .error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static String coalesce(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
