package com.krizaka.orazaka.test.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** [SEAM-001] on the Flyway migrations (krizaka-internal#2255). */
class SqlBoundaryRulesTest {

  private static final String MIGRATIONS = "service/src/main/resources/db/migration/";

  @TempDir Path root;

  @Test
  @DisplayName("a foreign key to a table an earlier V file of the same context creates passes")
  void ownTablesAcrossVersions() throws IOException {
    write(MIGRATIONS + "V1__baseline.sql", "CREATE TABLE IF NOT EXISTS job (id uuid PRIMARY KEY);");
    write(
        MIGRATIONS + "V2__steps.sql",
        "create table job_step (id uuid primary key, job_id uuid references public.job(id));");

    assertThatCode(() -> SqlBoundaryRules.assertNoCrossContextForeignKeysInMigrations(root))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a foreign key to another context's table fails, lower case included")
  void crossContextFails() throws IOException {
    write(MIGRATIONS + "V1__baseline.sql", "CREATE TABLE job (id uuid PRIMARY KEY);");
    write(
        "other/src/main/resources/db/migration/V1__baseline.sql",
        "CREATE TABLE conversation (id uuid PRIMARY KEY, job_id uuid references job(id));");

    assertThatThrownBy(() -> SqlBoundaryRules.assertNoCrossContextForeignKeysInMigrations(root))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("references table 'job'");
  }

  @Test
  @DisplayName("a commented-out foreign key and a repository without migrations pass")
  void commentsAndNoFolder() throws IOException {
    write(
        MIGRATIONS + "V1__baseline.sql",
        "CREATE TABLE a (id int);\n-- b_id int REFERENCES b(id)\n");
    write("target/classes/db/migration/V1__copy.sql", "CREATE TABLE c (x int REFERENCES z(id));");

    assertThatCode(() -> SqlBoundaryRules.assertNoCrossContextForeignKeysInMigrations(root))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                SqlBoundaryRules.assertNoCrossContextForeignKeysInMigrations(
                    Files.createDirectories(root.resolve("empty"))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a context whose schema is a Flyway baseline is read from it, the stub aside")
  void readContextFollowsTheBaseline() throws IOException {
    write("infra/initdb/30-jobs-config.sql", "-- role and database only\nCREATE ROLE job_owner;");
    write(
        "service/src/main/resources/db/migration/V1__job_baseline.sql",
        "CREATE TABLE orazaka_capabilities (k text);\nINSERT INTO orazaka_capabilities VALUES ('a');");

    String sql = SqlBoundaryRules.readContext(root.resolve("infra/initdb"), "30-jobs-config.sql");

    assertThat(sql).contains("CREATE ROLE job_owner").contains("INSERT INTO orazaka_capabilities");
  }

  @Test
  @DisplayName("a context with no baseline is read from its bootstrap file alone")
  void readContextWithoutBaseline() throws IOException {
    write("infra/initdb/30-jobs-config.sql", "INSERT INTO orazaka_capabilities VALUES ('b');");

    assertThat(SqlBoundaryRules.readContext(root.resolve("infra/initdb"), "30-jobs-config.sql"))
        .contains("VALUES ('b')");
  }

  private void write(String path, String content) throws IOException {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
