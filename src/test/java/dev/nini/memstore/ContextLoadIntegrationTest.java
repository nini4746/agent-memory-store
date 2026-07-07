package dev.nini.memstore;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the Spring context loads against a real database and that Flyway
 * has applied the pgvector extension (Plan 01 verification).
 */
@SpringBootTest
class ContextLoadIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void contextLoadsAndVectorExtensionInstalled() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
