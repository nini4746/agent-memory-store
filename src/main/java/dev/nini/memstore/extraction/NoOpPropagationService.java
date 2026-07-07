package dev.nini.memstore.extraction;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Placeholder propagation service (Plan 06). Plan 08 supplies a {@code @Primary}
 * implementation that performs the depth-2 graph walk.
 */
@Component
public class NoOpPropagationService implements PropagationService {

    @Override
    public void propagateFrom(UUID invalidatedFactId) {
        // No-op until Plan 08.
    }
}
