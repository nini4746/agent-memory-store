package dev.nini.memstore.extraction;

import dev.nini.memstore.domain.FactRelationEntity;
import dev.nini.memstore.domain.FactStatus;
import dev.nini.memstore.domain.RelationType;
import dev.nini.memstore.service.FactStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Correction propagation (SPEC R6). When a fact is invalidated, dependency edges
 * (refines/duplicates) are walked up to depth 2 with a visited set for cycle
 * safety, and every reached active fact is flagged needs_review. Contradiction
 * and supersedes edges are the resolution record itself and are not followed, so
 * the superseding winner is never flagged.
 *
 * <p>This step only sets flags; the actual re-decision is made by the review
 * step in the maintenance job.
 */
@Primary
@Component
public class DefaultPropagationService implements PropagationService {

    private static final int MAX_DEPTH = 2;

    private final FactStore factStore;

    public DefaultPropagationService(FactStore factStore) {
        this.factStore = factStore;
    }

    @Override
    @Transactional
    public void propagateFrom(UUID invalidatedFactId) {
        Set<UUID> visited = new HashSet<>();
        visited.add(invalidatedFactId);
        List<UUID> frontier = List.of(invalidatedFactId);

        for (int depth = 1; depth <= MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<UUID> next = new ArrayList<>();
            for (UUID factId : frontier) {
                for (UUID neighbor : dependencyNeighbors(factId)) {
                    if (visited.add(neighbor)) {
                        next.add(neighbor);
                        factStore.find(neighbor).ifPresent(f -> {
                            if (f.getStatus() == FactStatus.ACTIVE) {
                                factStore.setNeedsReview(neighbor, true);
                            }
                        });
                    }
                }
            }
            frontier = next;
        }
    }

    private List<UUID> dependencyNeighbors(UUID factId) {
        List<UUID> neighbors = new ArrayList<>();
        for (FactRelationEntity r : factStore.relationsFrom(factId)) {
            if (isDependency(r.getRelation())) {
                neighbors.add(r.getToFactId());
            }
        }
        for (FactRelationEntity r : factStore.relationsTo(factId)) {
            if (isDependency(r.getRelation())) {
                neighbors.add(r.getFromFactId());
            }
        }
        return neighbors;
    }

    private static boolean isDependency(RelationType relation) {
        return relation == RelationType.REFINES || relation == RelationType.DUPLICATES;
    }
}
