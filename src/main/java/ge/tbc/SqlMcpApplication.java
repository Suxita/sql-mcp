package ge.tbc;

import ge.tbc.tools.DataTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Schedulers;

@SpringBootApplication
public class SqlMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlMcpApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider sqlTools(DataTools dataTools) {
        return MethodToolCallbackProvider.builder().toolObjects(dataTools).build();
    }

    @Bean
    public ApplicationRunner registerReactorSessionPropagation() {
        return args -> Schedulers.onScheduleHook("session-propagation", runnable -> {
            String captured = SessionContext.SESSION_ID.get();
            return () -> {
                String previous = SessionContext.SESSION_ID.get();
                if (captured != null) SessionContext.SESSION_ID.set(captured);
                try {
                    runnable.run();
                } finally {
                    if (previous == null) SessionContext.SESSION_ID.remove();
                    else SessionContext.SESSION_ID.set(previous);
                }
            };
        });
    }
}
