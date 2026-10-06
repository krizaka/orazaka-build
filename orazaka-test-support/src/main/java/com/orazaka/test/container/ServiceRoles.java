package com.orazaka.test.container;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Gives a per-service database role its password inside a test container.
 *
 * <p>The {@code infra/initdb/*.sql} files deliberately create their service role <b>without</b> a
 * password (ADR-035): psql 15 cannot read the environment, {@code \getenv} is 16+, and ERR-125 bans
 * a shell script, so {@code orazaka start} applies the {@code ALTER ROLE} from the {@code
 * *_DB_PASSWORD} environment variable once the container is healthy. A role created without a
 * password cannot authenticate, which is the intended fail-closed behaviour — a skipped step locks
 * the door rather than leaving a guessable password committed in the repository.
 *
 * <p>An integration test mounts the same initdb file and therefore inherits the same passwordless
 * role. It has to perform the step {@code orazaka start} performs, and this is that step: without
 * it every connection as the service role fails with {@code password authentication failed}, which
 * is a wiring gap rather than a finding about the code under test.
 *
 * <p>Call it once, after the container starts and before the first application connection.
 */
public final class ServiceRoles {

  private ServiceRoles() {}

  /**
   * Assigns a login password to a role created by an initdb script.
   *
   * <p>Connects as the container's superuser, which is the only account that can authenticate
   * before this runs.
   *
   * @param container the started Postgres container
   * @param role the service role named by the initdb file, e.g. {@code orazaka_billing}
   * @param password the password the test's DataSource will present
   * @throws IllegalStateException when the statement cannot be applied — a test that proceeds past
   *     this would fail later with a misleading authentication error
   */
  public static void assignPassword(
      JdbcDatabaseContainer<?> container, String role, String password) {
    // ALTER ROLE takes no bind parameters, so the identifier and the literal are quoted by hand.
    String statement =
        "ALTER ROLE %s WITH PASSWORD %s".formatted(quoteIdentifier(role), quoteLiteral(password));
    try (Connection connection =
            DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        Statement jdbc = connection.createStatement()) {
      jdbc.execute(statement);
    } catch (SQLException e) {
      throw new IllegalStateException("Could not assign a password to role " + role, e);
    }
  }

  private static String quoteIdentifier(String identifier) {
    return '"' + identifier.replace("\"", "\"\"") + '"';
  }

  private static String quoteLiteral(String literal) {
    return '\'' + literal.replace("'", "''") + '\'';
  }
}
