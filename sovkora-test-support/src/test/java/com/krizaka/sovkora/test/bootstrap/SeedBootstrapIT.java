package com.krizaka.sovkora.test.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.sovkora.test.architecture.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

/**
 * The platform bootstraps from an empty database: {@code infra/initdb}, then every service's Flyway
 * baseline (audit #31, krizaka-internal#2255).
 *
 * <p><b>Nothing ran these files.</b> They are the source of truth for capabilities, routing,
 * pricing, packs and dev fixtures, and the whole build read them only with regular expressions —
 * {@link SqlBoundaryRules} for [SEAM-001], {@code PackCoherenceRules} for [PACK-001]. A regex does
 * not notice a missing comma: {@code orazaka_capabilities} stopped parsing on 2026-09-09 and every
 * build since was green, while a fresh {@code sovkora start} died on the file that carries the
 * platform's capability registry. Existing volumes never re-run initdb, so no developer machine
 * showed it either.
 *
 * <p>The mechanism under test is the real one: the directory is copied into the image's {@code
 * /docker-entrypoint-initdb.d}, where Postgres applies each file alphabetically with {@code psql -v
 * ON_ERROR_STOP=1}, on the same image {@code infra/docker-compose.yml} runs. Applying the files
 * through JDBC instead would prove less than it looks — three of them use {@code \c} and {@code SET
 * ROLE}, which are psql's and not the server's.
 *
 * <p><b>Two steps, like the real bootstrap.</b> Since krizaka-internal#2255 {@code infra/initdb}
 * only creates what needs an administrator — databases, roles, extensions — and the tables come
 * from each service's {@code db/migration/V1__<context>_baseline.sql}, which the service applies at
 * startup with Flyway as its database's owner role. This suite does the same with Flyway itself
 * (never a hand-rolled SQL runner): same history table, same baseline version, same credentials
 * kind as each service's {@code spring.flyway} block, so a baseline that only works on a superuser,
 * or that collides with the other contexts of the shared {@code orazaka_db}, fails here and not on
 * the first deploy. A context whose schema has not moved to Flyway yet still brings it in its
 * initdb file, which the first step applies.
 *
 * <p>The dev dataset ({@code 90-dev-fixtures.sql}, when the workspace still carries it) is not part
 * of the platform: it is applied last, on the migrated schema, as {@code dev-reset} does.
 *
 * <p>The assertions after the bootstrap are anchors, not a second copy of the seed: what the
 * platform reads at runtime must exist after a fresh install — the per-context databases, the two
 * table constraints (one of which is what vanished), the capability registry and a current
 * pricebook.
 */
class SeedBootstrapIT {

  /**
   * The image {@code infra/docker-compose.yml} runs; a bootstrap test on another one proves less.
   */
  private static final DockerImageName IMAGE =
      DockerImageName.parse("ankane/pgvector:latest").asCompatibleSubstituteFor("postgres");

  private static final String DATABASE = "orazaka_db";
  private static final String USER = "orazaka_app";
  private static final String PASSWORD = "orazaka_pass";

  private static final StringBuilder CONTAINER_LOG = new StringBuilder();

  private static PostgreSQLContainer<?> postgres;
  private static List<String> seedFiles;
  private static Optional<Path> devFixtures;
  private static final List<String> migrated = new ArrayList<>();

  @BeforeAll
  static void bootstrapFromEmpty() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    // The platform bootstrap is every context's file, and each context's file lives in the
    // repository that owns it: this suite needs the whole workspace.
    Path workspace =
        Workspace.root(Path.of(System.getProperty("user.dir")), "Every context's infra/initdb");
    Path initdb = workspace.resolve("infra").resolve("initdb");
    devFixtures = findDevFixtures(Workspace.initDbFiles(initdb));
    List<Path> files = platformFiles(Workspace.initDbFiles(initdb));
    seedFiles = files.stream().map(file -> file.getFileName().toString()).toList();
    // A guard against judging the empty set: a wrong directory would otherwise bootstrap nothing
    // and pass.
    assertThat(seedFiles).as("the files in infra/initdb").hasSizeGreaterThanOrEqualTo(9);

    postgres =
        new PostgreSQLContainer<>(IMAGE)
            .withDatabaseName(DATABASE)
            .withUsername(USER)
            .withPassword(PASSWORD)
            .withLogConsumer(frame -> CONTAINER_LOG.append(frame.getUtf8String()));
    for (Path file : files) {
      postgres.withCopyFileToContainer(
          MountableFile.forHostPath(file), "/docker-entrypoint-initdb.d/" + file.getFileName());
    }
    try {
      postgres.start();
    } catch (RuntimeException failed) {
      throw new AssertionError(
          "infra/initdb does not bootstrap an empty database:\n" + psqlFailures(), failed);
    }
    for (Migration migration : MIGRATIONS) {
      Optional<Path> baseline = Workspace.findMigration(initdb, migration.baselineFile());
      if (baseline.isEmpty() && migration.optional()) {
        continue; // this context's schema is still in its initdb file, applied by step one
      }
      Path directory =
          baseline
              .orElseThrow(
                  () ->
                      new AssertionError(
                          migration.baselineFile() + " is not in the workspace: " + migration))
              .getParent();
      migrate(migration, directory);
      migrated.add(migration.baselineFile());
    }
    devFixtures.ifPresent(SeedBootstrapIT::applyDevFixtures);
  }

  /** One service's Flyway settings, as its {@code spring.flyway} block gives them. */
  private record Migration(
      String database,
      String role,
      String baselineFile,
      String historyTable,
      String baselineVersion,
      boolean optional) {}

  /**
   * The services' migrations, in the order they start. The two settings that matter are the ones
   * the services trap on: the shared {@code orazaka_db} (one history table per context, baselining
   * at 0 so V1 always runs) and the owner role as the migrator.
   */
  private static final List<Migration> MIGRATIONS =
      List.of(
          new Migration(
              "krizaka_users_db",
              "krizaka_users",
              "V1__users_baseline.sql",
              "flyway_schema_history",
              "1",
              false),
          new Migration(
              "krizaka_billing_db",
              "krizaka_billing",
              "V1__billing_baseline.sql",
              "flyway_schema_history",
              "1",
              false),
          new Migration(
              "orazaka_studio_db",
              "orazaka_studio",
              "V1__studio_baseline.sql",
              "flyway_schema_history",
              "1",
              false),
          new Migration(
              "orazaka_automation_db",
              "orazaka_automation",
              "V1__automation_baseline.sql",
              "flyway_schema_history",
              "1",
              false),
          new Migration(
              DATABASE,
              USER,
              "V1__conversation_baseline.sql",
              "flyway_history_conversation",
              "0",
              false),
          new Migration(DATABASE, USER, "V1__job_baseline.sql", "flyway_history_job", "0", false),
          // Not moved to Flyway on every branch yet: until it is, its initdb file carries the
          // schema.
          new Migration(
              DATABASE,
              USER,
              "V1__governance_baseline.sql",
              "flyway_history_governance",
              "0",
              true),
          new Migration(
              "orazaka_knowledge_db",
              "orazaka_knowledge",
              "V1__knowledge_baseline.sql",
              "flyway_schema_history",
              "1",
              true));

  /**
   * Applies one baseline directory as the service does: Flyway, as the database's owner role.
   *
   * <p>The roles are created without a password by initdb ({@code sovkora start} sets them from the
   * environment once the container is healthy); the test does the same so the migrator really is
   * the owner and not the bootstrap superuser.
   */
  private static void migrate(Migration migration, Path directory) {
    String password = "migrator-" + migration.role();
    if (!USER.equals(migration.role())) {
      execute("ALTER ROLE " + migration.role() + " PASSWORD '" + password + "'", DATABASE);
    }
    try {
      Flyway.configure()
          .dataSource(
              jdbcUrl(migration.database()),
              migration.role(),
              USER.equals(migration.role()) ? PASSWORD : password)
          .locations("filesystem:" + directory)
          .table(migration.historyTable())
          .baselineOnMigrate(true)
          .baselineVersion(migration.baselineVersion())
          .validateOnMigrate(true)
          .cleanDisabled(true)
          .outOfOrder(false)
          .placeholderReplacement(false)
          .load()
          .migrate();
    } catch (RuntimeException failed) {
      throw new AssertionError(
          "%s does not apply on top of infra/initdb in %s:%n%s"
              .formatted(migration.baselineFile(), migration.database(), psqlFailures()),
          failed);
    }
  }

  /** The dev dataset is applied on the migrated schema, last, with psql (it uses {@code \c}). */
  private static void applyDevFixtures(Path file) {
    String target = "/tmp/" + file.getFileName();
    postgres.copyFileToContainer(MountableFile.forHostPath(file), target);
    try {
      var result =
          postgres.execInContainer(
              "psql", "-v", "ON_ERROR_STOP=1", "-U", USER, "-d", DATABASE, "-f", target);
      if (result.getExitCode() != 0) {
        throw new AssertionError(
            "%s does not apply on the migrated schema:%n%s"
                .formatted(file.getFileName(), result.getStderr()));
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /**
   * The initdb files of the platform: by name, one each (an archived clone of a repository keeps a
   * stale copy of a file another repository now owns, and the later path is the live one), and
   * without the dev dataset, which is data and runs last.
   */
  private static List<Path> platformFiles(List<Path> all) {
    Map<String, Path> byName = new LinkedHashMap<>();
    all.stream()
        .sorted(Comparator.comparing(Path::toString))
        .forEach(file -> byName.put(file.getFileName().toString(), file));
    return byName.values().stream()
        .filter(file -> !DEV_FIXTURES.equals(file.getFileName().toString()))
        .sorted(Comparator.comparing(file -> file.getFileName().toString()))
        .toList();
  }

  private static Optional<Path> findDevFixtures(List<Path> all) {
    return all.stream()
        .filter(file -> DEV_FIXTURES.equals(file.getFileName().toString()))
        .findFirst();
  }

  private static final String DEV_FIXTURES = "90-dev-fixtures.sql";

  @AfterAll
  static void stop() {
    if (postgres != null) {
      postgres.stop();
    }
  }

  @Test
  @DisplayName("every file in infra/initdb was applied, in order")
  void everyFileWasApplied() {
    String log = CONTAINER_LOG.toString();
    assertThat(seedFiles)
        .allSatisfy(file -> assertThat(log).as("%s was run by initdb", file).contains(file));
    assertThat(psqlFailures()).as("what psql reported while applying them").isEmpty();
  }

  @Test
  @DisplayName("every service's Flyway baseline was applied by its own migrator")
  void everyBaselineWasApplied() {
    assertThat(migrated)
        .contains(
            "V1__users_baseline.sql",
            "V1__billing_baseline.sql",
            "V1__studio_baseline.sql",
            "V1__automation_baseline.sql",
            "V1__conversation_baseline.sql",
            "V1__job_baseline.sql");
    // The shared database keeps one history per context: no baseline marked another's as applied.
    assertThat(
            strings(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_name LIKE 'flyway_history_%' ORDER BY 1",
                DATABASE))
        .contains("flyway_history_conversation", "flyway_history_job");
  }

  @Test
  @DisplayName("each cut-over context has its own database")
  void eachContextHasItsDatabase() {
    List<String> databases =
        strings(
            "SELECT datname FROM pg_database WHERE datname LIKE 'sovkora%' OR datname LIKE 'krizaka%'"
                + " ORDER BY 1",
            DATABASE);

    assertThat(databases)
        .contains(
            "orazaka_db",
            "krizaka_users_db",
            "orazaka_knowledge_db",
            "orazaka_automation_db",
            "krizaka_billing_db",
            "orazaka_studio_db");
  }

  @Test
  @DisplayName("the capability table keeps the constraint that disappeared once")
  void theCapabilityTableKeepsItsConstraints() {
    // The routing-key constraint is the one that disappeared, and it disappeared by being written
    // next to another one without a comma. That other one — ck_orazaka_capabilities_endpoint,
    // "uri_path and http_method are NULL together or present together" — is gone with the columns
    // it guarded (ADR-069 §5), so this asserts the survivor rather than a pair that no longer is
    // one. A capability has no HTTP surface any more; it has a routing key, and that key must be
    // one the broker can bind.
    List<String> constraints =
        strings(
            "SELECT conname FROM pg_constraint WHERE conrelid = 'orazaka_capabilities'::regclass"
                + " ORDER BY 1",
            DATABASE);

    assertThat(constraints).contains("ck_orazaka_capabilities_routing_key");
    assertThat(constraints)
        .as("the endpoint constraint went with uri_path and http_method")
        .doesNotContain("ck_orazaka_capabilities_endpoint");
  }

  @Test
  @DisplayName("the capability registry is seeded and every routing key binds to a queue")
  void theCapabilityRegistryIsSeeded() {
    List<String> keys =
        strings("SELECT feature_key FROM orazaka_capabilities ORDER BY 1", DATABASE);

    assertThat(keys).hasSizeGreaterThanOrEqualTo(7).allSatisfy(key -> assertThat(key).isNotBlank());
    assertThat(
            strings(
                "SELECT feature_key FROM orazaka_capabilities WHERE routing_key NOT LIKE 'job.%'",
                DATABASE))
        .as("keys the broker would discard")
        .isEmpty();
  }

  @Test
  @DisplayName("a capability nobody classified waits in the batch lane")
  void anUnclassifiedCapabilityDefaultsToBatch() {
    // §3.2 of M2.5: a pack does not choose its own lane, or everything is INTERACTIVE. Its
    // manifest has no field for it, so what decides is this column's DEFAULT — and the fail-closed
    // direction is the one where an unmeasured capability waits where waiting is expected rather
    // than blocking the queue someone is sitting in front of (ADR-067).
    List<String> defaults =
        strings(
            "SELECT column_default FROM information_schema.columns"
                + " WHERE table_name = 'orazaka_capabilities' AND column_name = 'latency_class'",
            DATABASE);

    assertThat(defaults).singleElement().asString().contains("BATCH");
  }

  @Test
  @DisplayName("the pricebook has a current rate for every billable capability")
  void thePricebookIsSeeded() {
    List<String> capabilities =
        strings(
            "SELECT DISTINCT capability FROM credit_pricebook WHERE effective_to IS NULL ORDER BY 1",
            "krizaka_billing_db");

    assertThat(capabilities).contains("CHAT", "IMAGE", "VIDEO", "AUDIO", "AGENT");
  }

  @Test
  @DisplayName("every capability's declared unit is priced by a current pricebook row")
  void everyDeclaredUnitIsPriced() {
    // What makes billable_unit a declaration rather than a decoration (ADR-066). A capability whose
    // executor measures in a unit nothing prices settles nothing: the hold is released and the work
    // is served free, which is how four media capabilities went unbilled for as long as they did
    // (audit #22). The two sides live in two databases and two files; this is the only place they
    // meet.
    List<String> declared =
        strings(
            "SELECT feature_key || ' ' || billable_capability || ' ' || billable_unit"
                + " FROM orazaka_capabilities WHERE billable_unit IS NOT NULL ORDER BY 1",
            DATABASE);
    List<String> priced =
        strings(
            "SELECT DISTINCT capability || ' ' || unit FROM credit_pricebook"
                + " WHERE effective_to IS NULL",
            "krizaka_billing_db");

    assertThat(declared).as("capabilities declaring a unit").hasSizeGreaterThanOrEqualTo(8);
    assertThat(declared)
        .allSatisfy(
            row -> {
              String capabilityAndUnit = row.substring(row.indexOf(' ') + 1);
              assertThat(priced)
                  .as("%s declares a unit no current pricebook row prices", row)
                  .contains(capabilityAndUnit);
            });
  }

  @Test
  @DisplayName("every PUBLISHED Studio of a FREE pack is granted by at least one plan")
  void everyFreeStudioIsGrantedByAPlan() {
    // A Studio nothing grants is a Studio nobody can run. The first version of this guard judged
    // TOOLKIT packs only, reasoning that a VERTICAL's grant arrives with its purchase — true about
    // the mechanism, false about the outcome, because nothing walks the purchase path on a fresh
    // install. Four of six shipped packs were unusable on the highest plan while this guard stayed
    // green, because they were not in its population (docs/evaluations/first-real-use.md, B2).
    //
    // Widened to every PUBLISHED Studio, it reddened on seven — and reading that list showed two of
    // the four packs were behaving CORRECTLY: document-validation and realestate-studio declare
    // priceCents 4900, so their grant is meant to arrive with a purchase and a plan row would make
    // a sold pack free. That is a pricing decision no guard should take.
    //
    // So the population is what each pack DECLARES about itself: `pricing.priceCents == 0`. A free
    // pack no plan grants is unreachable by anyone, which is a defect; a paid pack no plan grants
    // is a pack you buy, which is the product. The discriminant is the manifest's, not this rule's
    // (AGENTS.md §12).
    Map<String, String> publishedStudios = freeStudioGrants();
    assertThat(publishedStudios)
        .as(
            "Studios declared PUBLISHED by a shipped pack — a rule over none asserts nothing"
                + " (GOV-006)")
        .isNotEmpty();

    List<String> grantedBySomePlan =
        strings(
            "SELECT DISTINCT entitlement_key FROM billing_plan_entitlement"
                + " WHERE value = 'true' ORDER BY 1",
            "krizaka_billing_db");

    assertThat(publishedStudios)
        .allSatisfy(
            (entitlementKey, studio) ->
                assertThat(grantedBySomePlan)
                    .as(
                        "%s ships FREE and no plan grants %s — on a fresh install it is locked"
                            + " for every actor on every plan, and there is no purchase to unlock it",
                        studio, entitlementKey)
                    .contains(entitlementKey));
  }

  /**
   * Every {@code entitlementKey} a FREE pack's PUBLISHED Studios declare, mapped to the Studio.
   *
   * <p>Read from the manifests rather than from the database, because at this point no pack is
   * installed: the seed creates the tables and the bootstrap installs the bundles at runtime. The
   * manifest is where a pack declares what it needs granted, so it is the honest left-hand side.
   *
   * @return entitlement key → {@code <pack>/<studio>}, for PUBLISHED Studios of free packs
   */
  private static Map<String, String> freeStudioGrants() {
    Path packs = Workspace.packs(Path.of(System.getProperty("user.dir")));
    Map<String, String> grants = new LinkedHashMap<>();
    try (Stream<Path> bundles = Files.list(packs)) {
      for (Path bundle : bundles.sorted(Comparator.naturalOrder()).toList()) {
        Path manifest = bundle.resolve("pack.yaml");
        if (!Files.isRegularFile(manifest)) {
          continue;
        }
        Map<String, Object> declared = new Yaml().load(Files.readString(manifest));
        // What the pack says it costs. A pack with no pricing block declares no price and is read
        // as free: silence must not buy an exemption from the guard.
        Object pricing = declared.get("pricing");
        long priceCents =
            pricing instanceof Map<?, ?> priced && priced.get("priceCents") instanceof Number cents
                ? cents.longValue()
                : 0L;
        if (priceCents > 0) {
          continue;
        }
        Object studios = declared.get("studios");
        if (!(studios instanceof List<?> rows)) {
          continue;
        }
        for (Object row : rows) {
          if (row instanceof Map<?, ?> studio) {
            Object key = studio.get("entitlementKey");
            // PUBLISHED only: a DRAFT Studio is not offered to anyone, so nothing grants it and
            // nothing should.
            boolean published = "PUBLISHED".equals(String.valueOf(studio.get("status")));
            // A Studio that declares itself `pricing: PAID` is sold, whatever its pack's price
            // says today: the price may not be set yet (code-contributor, sovkora#450), and a
            // plan row would give away what is meant to be bought or granted by an operator.
            boolean sold = "PAID".equals(String.valueOf(studio.get("pricing")));
            if (key != null && published && !sold) {
              grants.put(key.toString(), declared.get("key") + "/" + studio.get("key"));
            }
          }
        }
      }
    } catch (java.io.IOException unreadable) {
      throw new java.io.UncheckedIOException(unreadable);
    }
    return grants;
  }

  /**
   * The lines psql wrote that report a failure — the message a broken seed file deserves.
   *
   * <p>{@code ERROR}/{@code FATAL} only: psql prefixes its {@code NOTICE}s the same way, and a
   * bootstrap that drops what does not exist yet emits dozens of them on an empty volume.
   */
  private static String psqlFailures() {
    return CONTAINER_LOG
        .toString()
        .lines()
        .filter(line -> line.contains("ERROR:") || line.contains("FATAL:"))
        .reduce("", (all, line) -> all.isEmpty() ? line : all + "\n" + line);
  }

  private static String jdbcUrl(String database) {
    return "jdbc:postgresql://%s:%d/%s"
        .formatted(
            postgres.getHost(),
            postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            database);
  }

  private static void execute(String sql, String database) {
    try (Connection connection = DriverManager.getConnection(jdbcUrl(database), USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException("Statement failed against " + database + ": " + sql, e);
    }
  }

  private static List<String> strings(String sql, String database) {
    String url = jdbcUrl(database);
    List<String> values = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Query failed against " + database + ": " + sql, e);
    }
    return values;
  }
}
