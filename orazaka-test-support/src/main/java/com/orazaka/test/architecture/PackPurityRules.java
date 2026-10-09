package com.orazaka.test.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anti-pollution fitness function [PACK-002 / PACK-003]: <b>a pack's identity never appears in the
 * engine's code</b> (ADR-037 §4.2).
 *
 * <p><b>Why this is a build rule and not a review habit.</b> The promise is "a new pack is a row,
 * never a deploy". Every literal that names a pack, a studio or one of a pack's capabilities inside
 * engine code is a place where that promise is already false, and the two the design found — {@code
 * COMPOSE_ROUTING_KEY = "job.compose.assemble"} in the Studio dispatcher and the media worker's
 * {@code COMPOSE_FEATURE_KEY} comparison — were both written by people who knew the rule. A rule
 * stated in a document and checked by nobody is decorative.
 *
 * <p><b>P1</b> forbids the literal; <b>P2</b> forbids branching on it, including through a constant
 * declared in the same file — which is exactly the shape both known violations took, and the shape
 * a rule matching only inline literals would have missed.
 *
 * <p><b>Known limit: this rule matches capability KEYS, not fragments of them.</b> {@code
 * JobListener.resolveInputFilename} chose an upload's file extension by testing whether the feature
 * key contained {@code "video"} or {@code "audio"} — dispatch driven by the capability registry's
 * naming, which is what P2 exists to forbid, and P2 did not see it because {@code "video"} is not a
 * key. The limit is deliberate: those fragments are also model categories, MIME type halves and
 * icon names, so matching them would fail the build on legitimate code every time and the rule
 * would be suppressed rather than obeyed. What closes the gap is removing the reason to branch —
 * that method now reads the payload's {@code mimeType} — not widening the pattern. A reviewer
 * should still read a {@code contains()} over a feature key as a violation of ADR-037 §4.2 even
 * when the build stays green.
 *
 * <p>Source-scanned rather than expressed in ArchUnit for two reasons: the subject is a string, not
 * a type relationship, and the worst offender is <b>Python</b>. {@code orazaka-worker-media} is in
 * no Maven reactor, so no bytecode rule can see it — and a worker naming a capability is precisely
 * how a worker becomes coupled to a pack.
 *
 * <p>Pure static utility over the source text, reusing {@link SqlBoundaryRules}' walk-up discipline
 * to locate the repository root rather than assuming a module's working directory.
 *
 * @see GovernanceRules#assertNoPackKeyLiterals(Path)
 * @see GovernanceRules#assertNoPackKeyConditionals(Path)
 */
public final class PackPurityRules {

  /**
   * Namespaces the <b>engine</b> owns. Everything else under {@code orazaka.} belongs to a pack.
   *
   * <p><b>A whitelist since ADR-050, and the inversion is the whole point.</b> This used to be the
   * two pack namespaces that existed, plus seven pack keys — and phase G measured what that bought:
   * {@code "orazaka.validation.pdf.ocr"} and {@code "acme-compliance"} planted in engine code
   * passed fifteen governance tests. A blacklist catches the packs somebody already thought of,
   * which are exactly the ones nobody was going to add.
   *
   * <p><b>The two failure modes are asymmetric, and that is the argument.</b> A new ENGINE
   * namespace is added by somebody editing the engine, in review, who can add a line here in the
   * same change. A new PACK namespace arrives from outside the repository, from an author who has
   * never read this file and cannot be asked to. The guard must fail closed against the party who
   * is not in the room — [BILL-001]'s reasoning applied to vocabulary instead of to metering.
   *
   * <p>Every entry is a namespace observed in engine production code, never a precaution: adding
   * one speculatively re-opens by the back door the hole this list closes.
   */
  private static final Set<String> ENGINE_NAMESPACES =
      Set.of(
          "assets", // orazaka.assets.encryption.* — the envelope's own wiring (ADR-054)
          "billing", // krizaka.billing.hold.released.credits — the ledger's own metrics
          "core", // orazaka.core.chat.completion — the engine's own capabilities
          "edge", // orazaka.edge.identity — the transport facade's wiring
          // Added by the same change that introduced it (ADR-050) — the rule fired on it first,
          // which is the asymmetry working: an engine namespace costs its author one line, in
          // review; a pack namespace has nobody to ask.
          "enrichment", // orazaka.enrichment.namespace — which prefix the pipeline enriches from
          "events", // orazaka.events.* — the events exchange and its queues
          "guard", // orazaka.guard.subject — what the user wrote, declared by the producer
          // (ADR-055)
          "identity", // orazaka.identity.jwt — the trust root's wiring
          "jobs", // orazaka.jobs.* — the jobs exchange and its queues
          "messaging", // orazaka.messaging.broker
          "metering", // orazaka.metering.deferred — the BILL-001 marker
          // Added by the change that split the notification context out of automation: its own
          // request queue (krizaka.notifications.requests) is engine wiring, not a pack.
          "notifications", // orazaka.notifications.* — the notification service's queues
          "pipeline", // orazaka.pipeline.* — a Context preference namespace
          "safety", // orazaka.safety.crisis-terms — the crisis guard's wire keys (ADR-055)
          "saga", // orazaka.saga.run.failed — the run saga's metrics
          // The MECHANISM is the engine's; the domain it refuses is the pack's and lives in a row
          // (ADR-051). That split is why "scope" belongs here and "juridique" belongs nowhere.
          "scope", // orazaka.scope.refused-terms — the declared out-of-scope domain
          "tools", // orazaka.tools.sandbox
          "user", // orazaka.user.tier — a Context preference namespace
          // Added by the multi-repository split: the workspace manifest's file name.
          "workspace"); // orazaka.workspace.json — the workspace marker (Workspace)

  /**
   * Any dotted {@code orazaka.} key, captured with its namespace so the namespace can be judged.
   *
   * <p>The trailing character class is load-bearing and unchanged: a capability key never ends in a
   * separator, which is what kept {@code "orazaka.studio.brand."} — a Context preference prefix —
   * from being read as one. ADR-050 turned that prefix into a declared input, so it no longer
   * appears anywhere, but the distinction still protects every other prefix.
   */
  private static final Pattern DOTTED_KEY =
      Pattern.compile("\"(orazaka\\.([a-z0-9]+)\\.[a-z0-9.]*[a-z0-9])\"");

  /**
   * A literal the code itself declares to be a pack or studio key.
   *
   * <p>A pack key is bare kebab-case and carries no namespace to judge. What can be judged is the
   * name the code gives it: {@code CLOUD_PACK = "acme-compliance"} states what the string is, and
   * that statement is the violation. The <i>identifier</i> must name a pack or studio, never the
   * value — which is what keeps {@code CONSUMER = "studio-saga"} and {@code
   * "orazaka-studio-service"} out, a consumer tag and a token subject that a rule flagging them
   * would be suppressed over. Measured on the tree when it was written: <b>zero</b> false
   * positives, against fifty-eight for the variant that also read the value.
   */
  private static final Pattern DECLARED_PACK_KEY =
      Pattern.compile(
          "\\b([A-Za-z_][A-Za-z0-9_]*(?:[Pp][Aa][Cc][Kk]|[Ss][Tt][Uu][Dd][Ii][Oo])[A-Za-z0-9_]*)"
              + "\\s*=\\s*\"([a-z0-9]+(?:-[a-z0-9]+)+)\"");

  /** Where engine code lives. A pack's own directory is not engine code and is not scanned. */
  private static final List<String> SCANNED_ROOTS =
      List.of("krizaka", "orazaka-libs", "orazaka-apps/services", "orazaka-apps/workers");

  /**
   * Branching forms. {@code ==} and {@code !=} are here for Python, where the known violation was
   * {@code job.get("featureKey") == COMPOSE_FEATURE_KEY}.
   */
  private static final List<String> CONDITIONALS =
      List.of("if ", "if(", "switch", ".contains(", ".startsWith(", ".equals(", "==", "!=");

  /** Java block comments, blanked before matching so prose explaining the rule never trips it. */
  private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

  /** {@code X = "literal"} — Java constant or Python module constant, one form covers both. */
  private static final Pattern CONSTANT_DECLARATION =
      Pattern.compile("\\b([A-Z][A-Z0-9_]{2,})\\s*(?::\\s*\\w+\\s*)?=\\s*\"([^\"]*)\"");

  private PackPurityRules() {}

  /** One offending line, with everything needed to find and judge it. */
  private record Violation(Path file, int line, String identifier, String text) {
    @Override
    public String toString() {
      return file + ":" + line + " names '" + identifier + "' -> " + text.trim();
    }
  }

  /**
   * [PACK-002] Asserts no pack, studio or pack-capability key appears as a literal in engine code.
   *
   * @param repositoryRoot the repository root (see {@link #locateRepositoryRoot(Path)})
   */
  static void assertNoPackKeyLiterals(Path repositoryRoot) {
    List<Violation> violations = new ArrayList<>();
    for (Path file :
        GovernanceSubjects.require(
            "PACK-002", "engine production sources", productionSources(repositoryRoot))) {
      collectLiterals(file, violations);
    }
    if (!violations.isEmpty()) {
      throw new AssertionError(
          "[PACK-002] A pack's identity is a row, never a literal in engine code — every one of"
              + " these is a place where \"a new pack is data\" is already false (ADR-037 §4.2)."
              + " It belongs in infra/initdb/** or in the pack's own bundle:\n  "
              + join(violations));
    }
  }

  /**
   * [PACK-003] Asserts engine code never branches on one of those identifiers, inline or through a
   * constant declared in the same file.
   *
   * @param repositoryRoot the repository root (see {@link #locateRepositoryRoot(Path)})
   */
  static void assertNoPackKeyConditionals(Path repositoryRoot) {
    List<Violation> violations = new ArrayList<>();
    for (Path file :
        GovernanceSubjects.require(
            "PACK-003", "engine production sources", productionSources(repositoryRoot))) {
      collectConditionals(file, violations);
    }
    if (!violations.isEmpty()) {
      throw new AssertionError(
          "[PACK-003] Engine code must not recognise a pack by name. A branch on a capability key"
              + " is a dispatch table pretending to be a heuristic: it decides correctly for the"
              + " packs that existed when it was written and silently wrongly for the next one"
              + " (ADR-037 §2.1). Decide from the message or from a row:\n  "
              + join(violations));
    }
  }

  /**
   * Walks up from {@code startDir} to the Orazaka workspace root ({@link Workspace#MANIFEST}),
   * where every repository is cloned at its workspace path.
   *
   * <p>Needed because this rule is repository-wide while the tests that run it execute in a module
   * directory: the Python worker sits in no Maven reactor, so a per-module scan could never see the
   * violation the rule exists for.
   *
   * @param startDir usually {@code Path.of(System.getProperty("user.dir"))} (the module dir)
   * @return the workspace root, or — in a standalone clone, where the rules skip — this
   *     repository's root
   */
  public static Path locateRepositoryRoot(Path startDir) {
    return Workspace.rootOrRepository(startDir);
  }

  // ─── Internal scanners ───

  private static void collectLiterals(Path file, List<Violation> violations) {
    int lineNumber = 0;
    for (String line : strippedLines(file)) {
      lineNumber++;
      for (String identifier : identifiersIn(line)) {
        violations.add(new Violation(file, lineNumber, identifier, line));
      }
    }
  }

  private static void collectConditionals(Path file, List<Violation> violations) {
    List<String> lines = strippedLines(file);
    Map<String, String> constants = constantsIn(lines);
    for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
      String line = lines.get(lineNumber - 1);
      if (CONDITIONALS.stream().noneMatch(line::contains)) {
        continue;
      }
      for (String identifier : identifiersIn(line)) {
        violations.add(new Violation(file, lineNumber, identifier, line));
      }
      // The indirect form, and the one both known violations used: a constant holding the key,
      // compared somewhere else. A rule matching only inline literals is trivially side-stepped by
      // extracting one, which is what a reviewer would ask for on style grounds.
      for (Map.Entry<String, String> constant : constants.entrySet()) {
        if (line.contains(constant.getKey())) {
          violations.add(
              new Violation(
                  file, lineNumber, constant.getKey() + " = \"" + constant.getValue() + '"', line));
        }
      }
    }
  }

  /**
   * Every pack identifier a single line names.
   *
   * <p>Two questions, because a pack has two kinds of name. A dotted key is judged by its NAMESPACE
   * against {@link #ENGINE_NAMESPACES} — anything the engine has not declared as its own belongs to
   * a pack, whether or not anyone has heard of that pack. A bare key has no namespace, so it is
   * judged by what the code calls it.
   */
  private static Set<String> identifiersIn(String line) {
    Set<String> found = new LinkedHashSet<>();
    Matcher dotted = DOTTED_KEY.matcher(line);
    while (dotted.find()) {
      if (!ENGINE_NAMESPACES.contains(dotted.group(2))) {
        found.add(dotted.group(1));
      }
    }
    Matcher declared = DECLARED_PACK_KEY.matcher(line);
    while (declared.find()) {
      found.add(declared.group(2));
    }
    return found;
  }

  /** Constants in this file whose value is a pack identifier, by name. */
  private static Map<String, String> constantsIn(List<String> lines) {
    Map<String, String> constants = new HashMap<>();
    for (String line : lines) {
      Matcher matcher = CONSTANT_DECLARATION.matcher(line);
      while (matcher.find()) {
        // The whole line, not the bare value: a pack key carries no namespace, so what marks it
        // is the name the declaration gives it, and that name is on this line (ADR-050).
        if (!identifiersIn(line).isEmpty()) {
          constants.put(matcher.group(1), matcher.group(2));
        }
      }
    }
    return constants;
  }

  /**
   * Every production source under the scanned roots, comment lines blanked.
   *
   * <p>Comments go first because these rules are about code: the prose that <i>names</i> a pack key
   * to explain why it must not be hardcoded is the documentation the rule wants to encourage, and a
   * rule that fails on its own justification is a rule nobody keeps.
   */
  private static List<String> strippedLines(Path file) {
    String source;
    try {
      source = Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + file, e);
    }
    // Block comments are blanked, keeping their newlines, so reported line numbers stay true.
    String withoutBlocks =
        BLOCK_COMMENT
            .matcher(source)
            .replaceAll(match -> "\n".repeat(countNewlines(match.group())));
    return List.of(withoutBlocks.replaceAll("(?m)^\\s*(//|\\*|#).*$", "").split("\n", -1));
  }

  private static int countNewlines(String text) {
    return (int) text.chars().filter(c -> c == '\n').count();
  }

  /**
   * Every production Java source in the repository.
   *
   * <p>Package-visible so a rule in {@link GovernanceRules} walks the same tree with the same
   * exclusions rather than growing a second walker that drifts from this one.
   *
   * @param repositoryRoot the repository root
   * @return the sources
   */
  static List<Path> productionSources(Path repositoryRoot) {
    List<Path> sources = new ArrayList<>();
    for (String root : SCANNED_ROOTS) {
      Path dir = repositoryRoot.resolve(root);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      // Pruned at the DIRECTORY, not filtered at the file. `Files.walk` descends into every
      // `target/` in the repository and reads its entries lazily, so a parallel build writing
      // jacoco reports underneath makes the stream throw NoSuchFileException before the filter
      // ever sees the path — which failed a governance suite on a file it would have discarded
      // anyway (ADR-060 §5). Not descending is both correct and much faster.
      try {
        Files.walkFileTree(
            dir,
            new java.nio.file.SimpleFileVisitor<Path>() {
              @Override
              public java.nio.file.FileVisitResult preVisitDirectory(
                  Path directory, java.nio.file.attribute.BasicFileAttributes attributes) {
                String name =
                    directory.getFileName() == null ? "" : directory.getFileName().toString();
                return switch (name) {
                  case "target", "node_modules", "__pycache__", ".venv", ".git", "site-packages" ->
                      java.nio.file.FileVisitResult.SKIP_SUBTREE;
                  default -> java.nio.file.FileVisitResult.CONTINUE;
                };
              }

              @Override
              public java.nio.file.FileVisitResult visitFile(
                  Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                if (isScannedSource(file)) {
                  sources.add(file);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
              }

              @Override
              public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException failure) {
                // A file that vanished between listing and reading is a build artefact racing us.
                return java.nio.file.FileVisitResult.CONTINUE;
              }
            });
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to walk " + dir, e);
      }
    }
    return sources;
  }

  /**
   * Whether one path is engine production source.
   *
   * <p>The exclusions are narrow by construction. {@code infra/initdb/**} and {@code
   * orazaka-packs/**} are outside the scanned roots entirely — seeds and bundles are exactly where
   * a pack key belongs. Test sources are excluded because a test asserting the routing of a
   * capability must be able to name it. This file is excluded because it holds the list.
   */
  private static boolean isScannedSource(Path path) {
    String normalised = path.toString().replace('\\', '/');
    if (!normalised.endsWith(".java") && !normalised.endsWith(".py")) {
      return false;
    }
    if (normalised.contains("/target/")
        || normalised.contains("/node_modules/")
        || normalised.contains("/__pycache__/")) {
      return false;
    }
    // The vendored Python environment is not engine code. Before ADR-050 the scan walked into it
    // and read 12 198 files where 888 are ours — eleven thousand third-party modules whose only
    // effect could ever be a false positive.
    if (normalised.contains("/.venv/") || normalised.contains("/site-packages/")) {
      return false;
    }
    if (normalised.contains("/src/test/")
        || normalised.contains("/test_")
        || normalised.endsWith("_test.py")) {
      return false;
    }
    return !normalised.endsWith("/PackPurityRules.java");
  }

  private static String join(List<Violation> violations) {
    return violations.stream().map(Violation::toString).reduce((a, b) -> a + "\n  " + b).orElse("");
  }
}
