package com.orazaka.test.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Double-debit guard [BILL-001]: every producer of a {@code job.*} command declares whether it
 * meters the inference itself.
 *
 * <p>ADR-033 §1 decided that <i>"a double-debit is unrecoverable trust damage and warrants two
 * independent guards"</i>, which is why the ledger carries both an {@code idempotency_key UNIQUE}
 * and the {@code processed_messages} dedup for the same failure. This rule is the second guard for
 * a different one. The first guard is behavioural — {@code EntitlementInterceptor} skips its hold
 * when it sees {@code orazaka.metering.deferred} — and it fails <b>silently</b>: a producer that
 * forgets the marker does not error, it bills twice, and nothing in the run says so. That is the
 * defect ADR-044 measured at sixteen credits for eight inferences, and it went unseen for as long
 * as billing was off.
 *
 * <p><b>Why a rule with few subjects is still worth writing here.</b> The usual objection — a
 * fitness function with one subject is written against a hypothesis — does not survive the
 * precedent. ADR-033 wrote the hold sweeper as a build-enforced rule <i>before</i> a hold had ever
 * been stranded, on the reasoning that it is "the most commonly omitted component of credit
 * systems". The same applies: the marker is invisible at the call site, its absence is silent, and
 * the cost of finding out in production is a user charged twice.
 *
 * <p>A producer that does not meter its own work says so on {@link #EXEMPT}, with a reason a reader
 * can check. The reason is the point: an exemption without one is how a rule becomes a list of
 * whatever was there when it was written.
 *
 * @see SourceFileScanner
 */
public final class MeteringMarkerRules {

  /** The key a producer stamps into a {@code job.*} payload to claim the metering. */
  public static final String MARKER = "orazaka.metering.deferred";

  /**
   * How a producer may declare it: the literal, the Tier-1 constant, or the {@code JobCommand}
   * method that sets it.
   *
   * <p>Three forms rather than one because the check must not dictate the shape of the fix. The
   * studio adapter builds a raw payload map and names the constant; the conversation service holds
   * a {@code JobCommand} and calls the method on it. Requiring the literal would have forced the
   * key to stay copy-pasted in every context — the very duplication that let one of them be written
   * without a copy at all.
   */
  private static final Pattern DECLARES_METERING =
      Pattern.compile("orazaka\\.metering\\.deferred|DEFERRED_METERING_KEY|withDeferredMetering");

  /**
   * Long enough that "n/a" and "not needed" do not pass, mirroring the blueprint SKIP rationale.
   */
  private static final int MINIMUM_RATIONALE = 60;

  /**
   * Any reference to the jobs exchange as a destination.
   *
   * <p>Deliberately <b>not</b> matched on {@code convertAndSend}. The first draft of this rule did,
   * and it passed while missing {@code JobQueuePublisherService} entirely — that producer publishes
   * through the transactional outbox, so the exchange name reaches an {@code OutboxMessage} and the
   * broker call happens later in a relay. A rule that sees one of two transports is worse than no
   * rule, because it reports green. Matching the destination itself is transport-agnostic, and the
   * declarations of the exchange are excluded by {@link #DECLARES_TOPOLOGY} rather than by pattern.
   */
  private static final Pattern JOBS_DESTINATION =
      Pattern.compile("JOBS_EXCHANGE|\"orazaka\\.jobs\"");

  /**
   * Where a class that names the exchange is declaring topology rather than sending to it.
   *
   * <p>Keyed on the package, not the class name: ERR-130 already puts {@code @Configuration},
   * {@code *Properties} and the messaging constants in {@code infrastructure/config}, so the
   * taxonomy the repository already enforces answers this question without a second list of
   * suffixes to keep in step with it.
   */
  private static final String TOPOLOGY_PACKAGE = "/infrastructure/config/";

  /**
   * This module, which names the exchange only to describe it.
   *
   * <p>Test support ships from {@code src/main/java} so other modules can depend on it, which puts
   * this very class in the scan's path — it matched itself, and passed only because it quotes the
   * marker in its own patterns. A rule that counts itself among its subjects reports a number a
   * reader cannot trust.
   */
  private static final String RULES_MODULE = "/orazaka-test-support/";

  /**
   * Producers that publish {@code job.*} and deliberately carry no marker.
   *
   * <p>Keyed by simple class name, because a rule that keyed on the full path would have to be
   * edited by anyone who moves a file, and the noise would train people to edit it.
   */
  private static final Map<String, String> EXEMPT =
      Map.of(
          "ConnectorDispatcher",
          "Dispatches job.agent.dispatch.{userId} to a user's own CLI agent, which runs no"
              + " inference in this platform and therefore reaches no interceptor pipeline:"
              + " there is no hold to suppress. The work is metered, if at all, wherever that"
              + " agent runs — outside this deployment.");

  private MeteringMarkerRules() {}

  /**
   * Walks up from a module's working directory to the repository root.
   *
   * <p>Anchored on the workspace manifest ({@link Workspace#MANIFEST}): the producers this rule
   * judges live in several repositories, which only the workspace holds together. Standalone, the
   * repository root is returned and the rule reports itself skipped.
   *
   * @param startDir any directory inside the repository
   * @return the workspace root, or this repository's root
   */
  public static Path locateRepositoryRoot(Path startDir) {
    return Workspace.rootOrRepository(startDir);
  }

  /**
   * Asserts that every class publishing to the jobs exchange stamps {@link #MARKER} or is exempt
   * with a written reason.
   *
   * @param repositoryRoot the {@code products/orazaka} directory
   */
  public static void assertEveryJobProducerDeclaresItsMetering(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "BILL-001");
    for (Map.Entry<String, String> exemption : EXEMPT.entrySet()) {
      assertTrue(
          exemption.getValue() != null && exemption.getValue().length() >= MINIMUM_RATIONALE,
          () ->
              "Exempted producer "
                  + exemption.getKey()
                  + " must carry a written reason of at least "
                  + MINIMUM_RATIONALE
                  + " characters saying why it meters nothing [BILL-001]");
    }

    // The hand-written check this replaced said the same thing — a scan that finds no producer is
    // broken, which is worse than a violation — and is now said once, for every rule (GOV-006).
    Map<String, Path> producers =
        GovernanceSubjects.require("BILL-001", "job.* producers", findJobProducers(repositoryRoot));

    List<String> violations = new ArrayList<>();
    for (Map.Entry<String, Path> producer : producers.entrySet()) {
      if (EXEMPT.containsKey(producer.getKey())) {
        continue;
      }
      if (!DECLARES_METERING.matcher(readFile(producer.getValue())).find()) {
        violations.add(
            producer.getKey()
                + " ("
                + repositoryRoot.relativize(producer.getValue())
                + ") publishes a job.* command without stamping '"
                + MARKER
                + "'. Either stamp it — the orchestration settles the work, so the pipeline must"
                + " take no hold of its own — or add the class to MeteringMarkerRules.EXEMPT with"
                + " a reason saying why nothing meters it twice.");
      }
    }
    assertTrue(
        violations.isEmpty(),
        "Job producers that neither declare nor disclaim their metering [BILL-001]:\n  "
            + String.join("\n  ", violations));

    List<String> stale =
        EXEMPT.keySet().stream().filter(name -> !producers.containsKey(name)).sorted().toList();
    assertTrue(
        stale.isEmpty(),
        "MeteringMarkerRules.EXEMPT lists classes that no longer publish job.* commands"
            + " — delete them [BILL-001]: "
            + String.join(", ", stale));
  }

  /** Every production class under the repository that sends to the jobs exchange. */
  private static Map<String, Path> findJobProducers(Path repositoryRoot) {
    Map<String, Path> producers = new LinkedHashMap<>();
    try (Stream<Path> files = Files.walk(repositoryRoot)) {
      files
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> path.toString().contains("/src/main/java/"))
          .filter(path -> !path.toString().contains("/target/"))
          .forEach(
              path -> {
                if (path.toString().contains(TOPOLOGY_PACKAGE)
                    || path.toString().contains(RULES_MODULE)) {
                  return;
                }
                String name = path.getFileName().toString().replace(".java", "");
                if (JOBS_DESTINATION.matcher(readFile(path)).find()) {
                  producers.put(name, path);
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException("cannot scan " + repositoryRoot, e);
    }
    return producers;
  }

  private static String readFile(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
  }
}
