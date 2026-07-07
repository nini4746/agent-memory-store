package dev.nini.memstore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the agent-native memory store server.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MemStoreApplication {

    public static void main(String[] args) {
        // In stdio MCP mode, stdout must carry only JSON-RPC frames, so suppress
        // the banner and console logging (logs still reach stderr via the runner).
        if (Boolean.getBoolean("memstore.mcp.stdio")) {
            System.setProperty("spring.main.banner-mode", "off");
            System.setProperty("logging.pattern.console", "");
        }
        SpringApplication.run(MemStoreApplication.class, args);
    }
}
