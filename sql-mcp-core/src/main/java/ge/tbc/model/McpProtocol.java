package ge.tbc.model;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Minimal MCP (Model Context Protocol) JSON-RPC message models.
 *
 * The MCP protocol is JSON-RPC 2.0 over stdin/stdout.
 * Only the shapes needed by a read-only tool server are defined here.
 */
public final class McpProtocol {

    private McpProtocol() {}

    // ------------------------------------------------------------------ //
    //  Inbound                                                             //
    // ------------------------------------------------------------------ //

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Request {
        @JsonProperty("jsonrpc") public String jsonrpc;
        @JsonProperty("id")      public Object id;       // String | Integer | null
        @JsonProperty("method")  public String method;
        @JsonProperty("params")  public Map<String, Object> params;
    }

    // ------------------------------------------------------------------ //
    //  Outbound                                                            //
    // ------------------------------------------------------------------ //

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        @JsonProperty("jsonrpc") public final String jsonrpc = "2.0";
        @JsonProperty("id")      public Object id;
        @JsonProperty("result")  public Object result;
        @JsonProperty("error")   public ErrorBody error;

        public static Response ok(Object id, Object result) {
            Response r = new Response();
            r.id = id;
            r.result = result;
            return r;
        }

        public static Response error(Object id, int code, String message) {
            Response r = new Response();
            r.id = id;
            r.error = new ErrorBody(code, message);
            return r;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorBody {
        @JsonProperty("code")    public int    code;
        @JsonProperty("message") public String message;
        ErrorBody(int code, String message) { this.code = code; this.message = message; }
    }

    // ------------------------------------------------------------------ //
    //  tools/list result shapes                                           //
    // ------------------------------------------------------------------ //

    public static class ToolsListResult {
        @JsonProperty("tools") public List<ToolDefinition> tools;
        public ToolsListResult(List<ToolDefinition> tools) { this.tools = tools; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolDefinition {
        @JsonProperty("name")        public String name;
        @JsonProperty("description") public String description;
        @JsonProperty("inputSchema") public Map<String, Object> inputSchema;
    }

    // ------------------------------------------------------------------ //
    //  tools/call result shapes                                           //
    // ------------------------------------------------------------------ //

    public static class ToolCallResult {
        @JsonProperty("content") public List<ContentBlock> content;
        @JsonProperty("isError") public boolean isError;

        public static ToolCallResult text(String text) {
            ToolCallResult r = new ToolCallResult();
            r.content = List.of(ContentBlock.text(text));
            r.isError = false;
            return r;
        }

        public static ToolCallResult error(String message) {
            ToolCallResult r = new ToolCallResult();
            r.content = List.of(ContentBlock.text(message));
            r.isError = true;
            return r;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentBlock {
        @JsonProperty("type") public String type;
        @JsonProperty("text") public String text;

        public static ContentBlock text(String text) {
            ContentBlock b = new ContentBlock();
            b.type = "text";
            b.text = text;
            return b;
        }
    }

    // ------------------------------------------------------------------ //
    //  initialize result                                                  //
    // ------------------------------------------------------------------ //

    public static class InitializeResult {
        @JsonProperty("protocolVersion") public String protocolVersion = "2024-11-05";
        @JsonProperty("capabilities")    public Capabilities capabilities = new Capabilities();
        @JsonProperty("serverInfo")      public ServerInfo serverInfo;
    }

    public static class Capabilities {
        @JsonProperty("tools") public Map<String, Object> tools = Map.of();
    }

    public static class ServerInfo {
        @JsonProperty("name")    public String name;
        @JsonProperty("version") public String version;
    }
}
