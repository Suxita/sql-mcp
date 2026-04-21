package ge.tbc.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ge.tbc.config.EnvironmentRegistry;
import ge.tbc.connection.ConnectionManager;
import ge.tbc.model.AuditLogger;
import ge.tbc.model.McpProtocol.*;
import ge.tbc.tools.DataTools;
import ge.tbc.tools.ProfileTools;
import ge.tbc.tools.SchemaTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 * MSSQL MCP Reader Server – main entry point.
 *
 * Reads JSON-RPC 2.0 messages from stdin line-by-line and writes responses to stdout.
 * stderr is reserved for logging so it does not pollute the MCP stream.
 *
 * Tools registered (14 read-only, zero writers):
 *   Schema:   search_schema, describe_table, list_table, list_databases
 *   Data:     read_data, explain_query
 *   Profile:  profile_table, inspect_relationships, inspect_dependencies
 *   Ops:      list_environments, test_connection, validate_environment_config
 *   Scripts:  list_scripts, run_script
 *
 * Launch:
 *   java -jar mssql-mcp-reader-1.0.0.jar
 *
 * Required env vars: SERVER_NAME, DATABASE_NAME
 * Optional:          SQL_USERNAME, SQL_PASSWORD, SQL_AUTH_MODE, SQL_PORT,
 *                    TRUST_SERVER_CERTIFICATE, ENVIRONMENTS_CONFIG_PATH,
 *                    SCRIPTS_PATH, AUDIT_LOG_PATH
 */
public class McpReaderServer {

    private static final Logger log = LoggerFactory.getLogger(McpReaderServer.class);

    private static final String SERVER_NAME    = "mssql-mcp-reader";
    private static final String SERVER_VERSION = "1.0.0";

    private final ObjectMapper mapper;
    private final EnvironmentRegistry registry;
    private final SchemaTools  schemaTools;
    private final DataTools    dataTools;
    private final ProfileTools profileTools;
    private final AuditLogger  auditLogger;

    /** All tool names this server exposes – used for validation. */
    private final Set<String> ALL_TOOLS;

    public McpReaderServer() {
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.registry = EnvironmentRegistry.load();
        ConnectionManager cm = new ConnectionManager(registry.getActive());
        this.schemaTools  = new SchemaTools(cm);
        this.dataTools    = new DataTools(cm);
        this.profileTools = new ProfileTools(cm, registry);
        this.auditLogger  = new AuditLogger();

        Set<String> tools = new LinkedHashSet<>();
        schemaTools.definitions() .forEach(d -> tools.add(d.name));
        dataTools.definitions()   .forEach(d -> tools.add(d.name));
        profileTools.definitions().forEach(d -> tools.add(d.name));
        ALL_TOOLS = Collections.unmodifiableSet(tools);

        log.info("MSSQL MCP Reader started. {} tools registered: {}",
                ALL_TOOLS.size(), ALL_TOOLS);
    }

    // ------------------------------------------------------------------ //
    //  Main loop                                                           //
    // ------------------------------------------------------------------ //

    public void run() throws IOException {
        BufferedReader in  = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter    out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)));

        log.info("Listening for MCP messages on stdin…");
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            String response;
            try {
                Request req = mapper.readValue(line, Request.class);
                Response res = handle(req);
                response = mapper.writeValueAsString(res);
            } catch (Exception e) {
                log.error("Failed to process message: {}", e.getMessage(), e);
                Response err = Response.error(null, -32700, "Parse error: " + e.getMessage());
                response = mapper.writeValueAsString(err);
            }
            out.println(response);
            out.flush();
        }
        log.info("stdin closed – shutting down.");
        auditLogger.close();
    }

    // ------------------------------------------------------------------ //
    //  Request dispatcher                                                  //
    // ------------------------------------------------------------------ //

    private Response handle(Request req) {
        return switch (req.method == null ? "" : req.method) {
            case "initialize"        -> handleInitialize(req);
            case "notifications/initialized" -> { yield null; } // no response needed
            case "tools/list"        -> handleToolsList(req);
            case "tools/call"        -> handleToolsCall(req);
            case "ping"              -> Response.ok(req.id, Map.of());
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
        List<ToolDefinition> all = new ArrayList<>();
        all.addAll(schemaTools.definitions());
        all.addAll(dataTools.definitions());
        all.addAll(profileTools.definitions());
        return Response.ok(req.id, new ToolsListResult(all));
    }

    @SuppressWarnings("unchecked")
    private Response handleToolsCall(Request req) {
        if (req.params == null) {
            return Response.error(req.id, -32602, "Missing params");
        }
        String toolName = (String) req.params.get("name");
        if (toolName == null) {
            return Response.error(req.id, -32602, "Missing 'name' in params");
        }
        if (!ALL_TOOLS.contains(toolName)) {
            return Response.error(req.id, -32602, "Unknown tool: " + toolName);
        }

        Map<String, Object> args = (Map<String, Object>) req.params.getOrDefault("arguments", Map.of());
        long start = System.currentTimeMillis();
        ToolCallResult result = dispatchTool(toolName, args);
        long elapsed = System.currentTimeMillis() - start;
        auditLogger.log(toolName, args, !result.isError, elapsed);

        return Response.ok(req.id, result);
    }

    private ToolCallResult dispatchTool(String toolName, Map<String, Object> args) {
        // Schema tools
        if (Set.of("search_schema", "describe_table", "list_table", "list_databases")
                .contains(toolName)) {
            return schemaTools.dispatch(toolName, args);
        }
        // Data tools
        if (Set.of("read_data", "explain_query").contains(toolName)) {
            return dataTools.dispatch(toolName, args);
        }
        // Profile / ops / script tools
        return profileTools.dispatch(toolName, args);
    }

    // ------------------------------------------------------------------ //
    //  Entry point                                                         //
    // ------------------------------------------------------------------ //

    public static void main(String[] args) {
        try {
            new McpReaderServer().run();
        } catch (Exception e) {
            LoggerFactory.getLogger(McpReaderServer.class)
                    .error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
