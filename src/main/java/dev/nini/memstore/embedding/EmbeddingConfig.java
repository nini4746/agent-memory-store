package dev.nini.memstore.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the active {@link EmbeddingProvider} from configuration. */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    public EmbeddingProvider embeddingProvider(EmbeddingProperties props) {
        if ("openai".equalsIgnoreCase(props.getProvider())) {
            log.info("Using OpenAI embedding provider model={} dim={}",
                    props.getModel(), props.getDimension());
            return new OpenAiEmbeddingProvider(props.getBaseUrl(), props.getApiKey(),
                    props.getModel(), props.getDimension());
        }
        log.info("Using fake deterministic embedding provider dim={}",
                props.getDimension());
        return new FakeEmbeddingProvider(props.getDimension(), props.getModel());
    }
}
