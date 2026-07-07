package dev.nini.memstore.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * stdio transport for the MCP server (SPEC R7). Reads newline-delimited JSON-RPC
 * requests from stdin and writes responses to stdout. Enabled only when
 * {@code memstore.mcp.stdio=true} so it does not interfere with the web app or
 * tests. Register with a client via
 * {@code claude mcp add memstore -- java -Dmemstore.mcp.stdio=true -jar app.jar}.
 */
@Component
@ConditionalOnProperty(name = "memstore.mcp.stdio", havingValue = "true")
public class McpStdioServer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpStdioServer.class);

    private final McpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpStdioServer(McpServer server) {
        this.server = server;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        log.info("MCP stdio server ready");
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode request = mapper.readTree(line);
                JsonNode response = server.handle(request);
                if (response != null) {
                    writer.println(mapper.writeValueAsString(response));
                }
            } catch (Exception e) {
                log.warn("failed to process MCP line: {}", e.getMessage());
            }
        }
    }
}
