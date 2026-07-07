package dev.nini.memstore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the agent-native memory store server.
 */
@SpringBootApplication
@EnableScheduling
public class MemStoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemStoreApplication.class, args);
    }
}
