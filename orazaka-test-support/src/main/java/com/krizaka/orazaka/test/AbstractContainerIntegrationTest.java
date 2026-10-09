package com.krizaka.orazaka.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Testcontainers base class for integration tests [ADR-034].
 *
 * <p>Bootstraps exactly <strong>one</strong> static instance of each infrastructure container
 * (PostgreSQL + PGVector, Redis, RabbitMQ) for the entire test execution sequence. Subclasses
 * inherit all container wiring via {@link DynamicPropertySource} — zero hardcoded ports, zero test
 * profiles, zero manual configuration.
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * @SpringBootTest
 * class MyIntegrationTest extends AbstractContainerIntegrationTest {
 *
 *     @Test
 *     void shouldPersistAndRetrieve() {
 *         // Spring DataSource, Redis, and RabbitMQ are auto-wired
 *         // to the running Testcontainers instances.
 *     }
 * }
 * }</pre>
 *
 * <h3>Container Lifecycle</h3>
 *
 * <ul>
 *   <li>Containers are {@code static final} — started once per JVM, reused across all test classes.
 *   <li>Ephemeral ports are dynamically mapped via {@link DynamicPropertySource}.
 *   <li>No {@code @Testcontainers} annotation needed — containers are manually started in a static
 *       initializer for true singleton behavior.
 * </ul>
 *
 * @see <a href="../../../../../../AGENTS.md">AGENTS.md §2.21 — Unified Test Pyramid Rule</a>
 */
public abstract class AbstractContainerIntegrationTest {

  // ── PostgreSQL + PGVector ─────────────────────────────────────────────────

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("orazaka_test")
          .withUsername("orazaka")
          .withPassword("orazaka_test_pwd")
          .withCommand("postgres", "-c", "max_connections=100")
          .withReuse(true);

  // ── Redis ─────────────────────────────────────────────────────────────────

  @SuppressWarnings("resource")
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true)
          .waitingFor(Wait.forListeningPort());

  // ── RabbitMQ ──────────────────────────────────────────────────────────────

  // Use RabbitMQContainer's built-in readiness strategy: Wait.forListeningPort()
  // would block on every exposed port (epmd/clustering ports never open in
  // single-node mode), timing out startup.
  private static final RabbitMQContainer RABBITMQ =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine")).withReuse(true);

  // ── Singleton bootstrap ───────────────────────────────────────────────────

  static {
    // Pin the Docker Engine API version before the docker-java client initializes.
    // Its default (1.32) is rejected (HTTP 400) by recent Docker Desktop daemons
    // whose minimum is 1.40+. Respect an explicit external override if present.
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    REDIS.start();
    RABBITMQ.start();
  }

  // ── Dynamic property wiring ───────────────────────────────────────────────

  /**
   * Dynamically maps container connection properties into the Spring {@code Environment}. This
   * eliminates all hardcoded ports, hostnames, and test-specific {@code application-test.yml}
   * profiles.
   *
   * @param registry the dynamic property registry provided by Spring Boot Test.
   */
  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    // PostgreSQL
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

    // JPA / Flyway
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    registry.add("spring.flyway.enabled", () -> "false");

    // Redis
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

    // RabbitMQ
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
  }
}
