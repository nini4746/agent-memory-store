package dev.nini.memstore.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the active {@link LlmClient} from configuration. */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmClient llmClient(LlmProperties props) {
        if ("claude".equalsIgnoreCase(props.getProvider())) {
            log.info("Using Claude LLM client model={}", props.getModel());
            return new ClaudeLlmClient(props.getBaseUrl(), props.getApiKey(),
                    props.getModel());
        }
        log.info("Using fake deterministic LLM client");
        return new FakeLlmClient();
    }
}
