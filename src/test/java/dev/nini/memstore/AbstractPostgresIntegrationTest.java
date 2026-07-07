package dev.nini.memstore;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that need a real PostgreSQL + pgvector
 * instance.
 *
 * <p>Two modes are supported:
 * <ul>
 *   <li>Default: a Testcontainers-managed {@code pgvector/pgvector:pg16}
 *       container is started. When Docker is unavailable the tests are skipped
 *       (JUnit assumption), so the default test task still passes.</li>
 *   <li>External override: when the system property
 *       {@code memstore.test.jdbcUrl} is set, tests run against that
 *       externally-managed database and no container is started. This is the
 *       fallback for environments where the docker-java client cannot negotiate
 *       with a very new Docker Engine even though the daemon is healthy.</li>
 * </ul>
 */
public abstract class AbstractPostgresIntegrationTest {

    private static final String EXTERNAL_URL =
            System.getProperty("memstore.test.jdbcUrl");

    private static final PostgreSQLContainer<?> POSTGRES = startContainerIfPossible();

    private static PostgreSQLContainer<?> startContainerIfPossible() {
        if (EXTERNAL_URL != null) {
            return null;
        }
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            dockerAvailable = false;
        }
        if (!dockerAvailable) {
            return null;
        }
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("memstore")
                .withUsername("memstore")
                .withPassword("memstore");
        container.start();
        return container;
    }

    @BeforeAll
    static void requireDatabase() {
        Assumptions.assumeTrue(EXTERNAL_URL != null || POSTGRES != null,
                "No external DB provided and Docker is unavailable; skipping "
                        + "integration test");
    }

    @Autowired(required = false)
    private JdbcTemplate cleanupJdbc;

    /**
     * Resets application tables before each test for isolation on a shared
     * database. TRUNCATE is a table-level operation and does not fire the
     * per-row immutability trigger, so it is safe for test teardown only.
     */
    @BeforeEach
    void cleanDatabase() {
        if (cleanupJdbc != null) {
            cleanupJdbc.execute("TRUNCATE fact_relation, fact_provenance, fact, "
                    + "utterance, session CASCADE");
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        if (EXTERNAL_URL != null) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username",
                    () -> System.getProperty("memstore.test.dbUser", "memstore"));
            registry.add("spring.datasource.password",
                    () -> System.getProperty("memstore.test.dbPassword", "memstore"));
        } else if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }
    }
}
