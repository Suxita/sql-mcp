package ge.tbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.tbc.tools.DataTools;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@SpringBootApplication
public class SqlMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlMcpApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider sqlTools(DataTools dataTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(dataTools)
                .build();
    }

//    /**
//     * Override the default SSE transport to use /mcp as the POST message endpoint.
//     * SSE stream stays at /sse; JSON-RPC POSTs go to /mcp.
//     * This is what GitHub Copilot expects with "type":"http","url":"http://…:8080/mcp".
//     */
//    @Bean
//    public WebMvcSseServerTransportProvider webMvcSseServerTransportProvider(ObjectMapper mapper) {
//        return new WebMvcSseServerTransportProvider(mapper, "/mcp");
//    }
//
//    @Bean
//    public RouterFunction<ServerResponse> mcpRouterFunction(WebMvcSseServerTransportProvider transport) {
//        return transport.getRouterFunction();
//    }
}
