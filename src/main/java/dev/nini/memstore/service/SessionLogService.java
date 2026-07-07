package dev.nini.memstore.service;

import dev.nini.memstore.domain.SessionEntity;
import dev.nini.memstore.domain.UtteranceEntity;
import dev.nini.memstore.repo.SessionRepository;
import dev.nini.memstore.repo.UtteranceRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only session log service (SPEC R1). Stores raw utterances verbatim and
 * never mutates them.
 */
@Service
public class SessionLogService {

    /** A single log entry to append. */
    public record LogEntry(String role, String content) {
    }

    private final SessionRepository sessions;
    private final UtteranceRepository utterances;
    private final Clock clock;

    public SessionLogService(SessionRepository sessions,
                             UtteranceRepository utterances, Clock clock) {
        this.sessions = sessions;
        this.utterances = utterances;
        this.clock = clock;
    }

    /**
     * Appends entries to a session, creating the session if {@code sessionId} is
     * null. Sequence numbers continue from the current maximum for the session.
     *
     * @return the session id the entries were appended to
     */
    @Transactional
    public UUID append(UUID sessionId, String source, List<LogEntry> entries) {
        return append(sessionId, source, entries, OffsetDateTime.now(clock));
    }

    /**
     * Same as {@link #append(UUID, String, List)} but with an explicit timestamp
     * for the session/utterances. Used by seeding and benchmark harnesses that
     * need relative time offsets.
     */
    @Transactional
    public UUID append(UUID sessionId, String source, List<LogEntry> entries,
                       OffsetDateTime timestamp) {
        UUID sid = sessionId;
        if (sid == null || !sessions.existsById(sid)) {
            SessionEntity session = new SessionEntity(
                    sid != null ? sid : UUID.randomUUID(), source, timestamp);
            sessions.save(session);
            sid = session.getId();
        }

        int nextSeq = utterances.maxSeqForSession(sid) + 1;
        for (LogEntry entry : entries) {
            UtteranceEntity utterance = new UtteranceEntity(
                    UUID.randomUUID(), sid, nextSeq++, entry.role(),
                    entry.content(), timestamp);
            utterances.save(utterance);
        }
        return sid;
    }

    @Transactional(readOnly = true)
    public List<UtteranceEntity> utterancesForSession(UUID sessionId) {
        return new ArrayList<>(utterances.findBySessionIdOrderBySeqAsc(sessionId));
    }
}
