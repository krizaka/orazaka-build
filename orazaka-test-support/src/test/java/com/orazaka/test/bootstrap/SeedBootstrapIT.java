package com.orazaka.test.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.orazaka.test.architecture.Workspace;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

/**
 * Every file in {@code infra/initdb} loads into an empty database, in order (audit #31).
 *
 * <p><b>Nothing ran these files.</b> They are the source of truth for capabilities, routing,
 * pricing, packs and dev fixtures, and the whole build read them only with regular expressions —
 * {@link SqlBoundaryRules} for [SEAM-001], {@code PackCoherenceRules} for [PACK-001]. A regex does
 * not notice a missing comma: {@code orazaka_capabilities} stopped parsing on 2026-09-09 and every
 * build since was green, while a fresh {@code orazaka start} died on the file that carries the
 * platform's capability registry. Existing volumes never re-run initdb, so no developer machine
 * showed it either.
 *
 * <p>The mechanism under test is the real one: the directory is copied into the image's {@code
 * /docker-entrypoint-initdb.d}, where Postgres applies each file alphabetically with {@code psql -v
 * ON_ERROR_STOP=1}, on the same image {@code infra/docker-compose.yml} runs. Applying the files
 * through JDBC instead would prove less than it looks — three of them use {@code \c} and {@code SET
 * ROLE}, which are psql's and not the server's.
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

  @BeforeAll
  static void bootstrapFromEmpty() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    // The platform bootstrap is every context's file, and each context's file lives in the
    // repository that owns it: this suite needs the whole workspace.
    Path workspace =
        Workspace.root(Path.of(System.getProperty("user.dir")), "Every context's infra/initdb");
    List<Path> files = Workspace.initDbFiles(workspace.resolve("infra").resolve("initdb"));
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
  }

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
  @DisplayName("each cut-over context has its own database")
  void eachContextHasItsDatabase() {
    List<String> databases =
        strings(
            "SELECT datname FROM pg_database WHERE datname LIKE 'orazaka%' ORDER BY 1", DATABASE);

    assertThat(databases)
        .contains(
            "orazaka_db",
            "orazaka_identity_db",
            "orazaka_knowledge_db",
            "orazaka_automation_db",
            "orazaka_billing_db",
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
            "orazaka_billing_db");

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
            "orazaka_billing_db");

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
            "orazaka_billing_db");

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
            if (key != null && published) {
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

  private static List<String> strings(String sql, String database) {
    String url =
        "jdbc:postgresql://%s:%d/%s"
            .formatted(
                postgres.getHost(),
                postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                database);
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
