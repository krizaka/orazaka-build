package com.krizaka.orazaka.test.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pack-catalogue fitness function [PACK-001]: the invariants of ADR-036 §4, checked against the DB
 * bootstrap on every build.
 *
 * <p><b>Why a static seed-file rule and not a test with a database.</b> The invariant that matters
 * most is cross-context: a {@code pack_studio} row in the studio database implies a {@code
 * billing_pack_entitlement} row in the <i>billing</i> database, joined only by an opaque string. No
 * ArchUnit rule can see that — it is a relation between rows, not between classes. An integration
 * test cannot see it either without connecting to both databases at once, which is precisely the
 * coupling AGENTS.md §5 forbids. The one place both facts are visible is the pair of seed files, at
 * build time, for free.
 *
 * <p><b>Why it is worth a fitness function at all.</b> A Pack whose Studio has no matching
 * entitlement row is the single most likely production defect in this feature: the user pays, the
 * subscription is written, the entitlement union comes back without the Studio, and they stay
 * locked out. It is silent, it reads as a billing bug, and it burns the exact customer who just
 * gave you money. Nothing else in the build would catch it.
 *
 * <p><b>Where the invariant lives now (phase D).</b> The seeds this rule reads no longer carry any
 * pack: the catalogue ships as bundles under {@code orazaka-packs/} and is applied by {@code
 * PackInstallerService}. This rule therefore passes vacuously today, and saying so matters more
 * than the fact — a fitness function that guards nothing is worse than none, because it reads like
 * cover. It is kept, not deleted, because {@code infra/initdb} may seed a pack again (a reference
 * pack for a bare {@code docker compose up}, say) and the day it does the check must already exist.
 *
 * <p>What replaced it is stronger than a text scan. Invariant #3 — a bundled Studio has a matching
 * {@code billing_pack_entitlement} — is no longer CHECKED but made unrepresentable: {@code
 * PackInstallerService.toProvision} DERIVES each grant from the Studio's own {@code
 * entitlementKey}, so the two halves cannot disagree, and {@code PackStudio}'s constructor refuses
 * any {@code entitlementKey} that is not {@code studio.<key>}. The pricing invariants moved the
 * same way, into {@code PackBundle}'s compact constructor: a catalogued pack without pricing, or a
 * PAID Studio without a catalog entry, cannot be constructed at all. {@code PackBundleTest} pins
 * each of them.
 *
 * <p><b>The one check that reads the catalogue as it is (ADR-061).</b> A pack's {@code kind} is
 * checked on both sources: the seed rows, like everything above, and the bundle manifests under
 * {@code orazaka-packs/}, which are where packs actually are. The invariant is the one the CHECKs
 * on {@code pack} enforce — a TOOLKIT is never REGULATED and declares neither consent nor safety —
 * and the database is its guard. This is the build noticing a committed manifest the database would
 * refuse at install, before anyone installs it. Extending only the seed half would have extended a
 * check that reads nothing.
 *
 * <p>Pure static utility over the SQL text, reusing {@link SqlBoundaryRules#readContext} rather
 * than opening a second reader on the same files.
 */
public final class PackCoherenceRules {

  private static final String STUDIO_CONTEXT = "80-studio.sql";

  private PackCoherenceRules() {}

  /**
   * Asserts the pack catalogue is coherent: {@code infra/initdb} seeds no pack, and no bundle
   * manifest declares a pack the database would refuse.
   *
   * <p><b>The seed half examined nothing for several phases.</b> It checked ADR-036's invariants #1
   * to #3 over the {@code pack} rows seeded in {@code 80-studio.sql}, and there have been none
   * since phase D moved the catalogue into bundles (ADR-039) — so it iterated the empty set and
   * reported green. GOV-006 turned that red. What it guarded now lives in {@code PackBundle}'s and
   * {@code PackStudio}'s constructors, and {@code PackInstallerService} derives each grant rather
   * than checking it. What remains true of the seeds is the decision itself: packs are bundles.
   * That is a prohibition, and its subject exists — the {@code CREATE TABLE pack} statement it
   * reads the rows of, which fails this rule if the table ever moves to another file.
   *
   * <p>The manifest half judges {@code orazaka-packs/*}{@code /pack.yaml} for the kind invariant
   * (ADR-061).
   *
   * @param initdbDir the {@code infra/initdb} directory
   */
  public static void assertPackCatalogueIsCoherent(Path initdbDir) {
    String studioSql = SqlBoundaryRules.readContext(initdbDir, STUDIO_CONTEXT);
    GovernanceSubjects.require(
        "PACK-001",
        "CREATE TABLE pack statement in " + STUDIO_CONTEXT,
        CREATE_PACK_TABLE.matcher(studioSql).results().toList());

    List<String> violations = new ArrayList<>();
    List<Map<String, String>> seeded = rowsOf(studioSql, "pack");
    if (!seeded.isEmpty()) {
      violations.add(
          STUDIO_CONTEXT
              + " seeds "
              + seeded.size()
              + " pack row(s): packs are bundles under orazaka-packs/ (ADR-039), and the invariants a"
              + " seeded pack would need are enforced in PackBundle, not on seeds. Ship it as a bundle,"
              + " or reinstate the seed checks this rule no longer has.");
    }
    violations.addAll(manifestKindViolations(initdbDir));
    if (!violations.isEmpty()) {
      fail("[PACK-001] Pack catalogue incoherent:\n  " + String.join("\n  ", violations));
    }
  }

  /** The pack table's own definition — the subject the seed prohibition reads rows under. */
  private static final Pattern CREATE_PACK_TABLE =
      Pattern.compile(
          "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?pack\\s*\\(", Pattern.CASE_INSENSITIVE);

  /** Top-level scalar of a {@code pack.yaml}: a key at column 0, its value unquoted. */
  private static final Pattern TOP_LEVEL_SCALAR =
      Pattern.compile(
          "^([A-Za-z]+):[ \\t]*[\"']?([A-Za-z_]*)[\"']?[ \\t]*(?:#.*)?$", Pattern.MULTILINE);

  /**
   * The kind invariant over every bundle manifest in {@code orazaka-packs/} (ADR-061).
   *
   * <p>Read as text, top-level keys only, because those three classifications are top-level scalars
   * by the manifest's own schema, and a YAML dependency in a fitness function would be one more
   * thing that can fail for a reason unrelated to the rule. A manifest that nests them is one
   * {@code pack.schema.json} already refuses.
   *
   * @param initdbDir the {@code infra/initdb} directory, from which the repository root is found
   * @return every violation found, or none
   */
  private static List<String> manifestKindViolations(Path initdbDir) {
    Path packs = Workspace.packs(initdbDir);
    List<Path> manifests;
    try (java.util.stream.Stream<Path> dirs = java.nio.file.Files.list(packs)) {
      manifests =
          dirs.map(dir -> dir.resolve("pack.yaml"))
              .filter(java.nio.file.Files::isRegularFile)
              .sorted()
              .toList();
    } catch (java.io.IOException unreadable) {
      throw new java.io.UncheckedIOException("cannot list " + packs, unreadable);
    }
    GovernanceSubjects.require("PACK-001", "pack.yaml manifests under " + packs, manifests);
    List<String> violations = new ArrayList<>();
    for (Path manifest : manifests) {
      String yaml;
      try {
        yaml = java.nio.file.Files.readString(manifest);
      } catch (java.io.IOException unreadable) {
        throw new java.io.UncheckedIOException("cannot read " + manifest, unreadable);
      }
      Map<String, String> top = new HashMap<>();
      Matcher matcher = TOP_LEVEL_SCALAR.matcher(yaml);
      while (matcher.find()) {
        top.putIfAbsent(matcher.group(1), matcher.group(2));
      }
      String where = manifest.getParent().getFileName() + "/pack.yaml";
      if ("TOOLKIT".equals(top.get("kind"))) {
        violations.addAll(
            toolkitViolations(
                where,
                top.getOrDefault("regulatoryClass", "STANDARD"),
                top.containsKey("consent"),
                top.containsKey("safety"),
                top.containsKey("catalog")));
      }
      if (top.containsKey("catalog") && !declaresItsShelf(yaml)) {
        violations.add(
            where
                + " is catalogued and does not declare the shelf it stands on: pack.category_key"
                + " references pack_category, which nothing seeds, so this bundle installs only"
                + " into a database where some other bundle happened to open that shelf first"
                + " (ADR-068, #45). Declare catalog.category — the upsert is DO NOTHING, so a"
                + " shelf that already exists is left exactly as it is.");
      }
    }
    return violations;
  }

  /**
   * Whether a manifest opens the shelf it puts itself on.
   *
   * <p>The block under {@code catalog:}, read to the next key at column 0. A nested {@code
   * category:} there is the seed {@code PackInstallRepository.apply} writes before the pack row
   * that references it; without one, {@code pack.category_key} points at a {@code pack_category}
   * row that exists only if another bundle brought it — which on an empty catalogue is a race
   * decided by directory order, and the bundle that loses cannot be installed at all.
   */
  private static boolean declaresItsShelf(String yaml) {
    Matcher catalog = Pattern.compile("(?m)^catalog:[ \\t]*$").matcher(yaml);
    if (!catalog.find()) {
      return false;
    }
    String block = yaml.substring(catalog.end());
    Matcher nextTopLevel = Pattern.compile("(?m)^[A-Za-z]").matcher(block);
    if (nextTopLevel.find()) {
      block = block.substring(0, nextTopLevel.start());
    }
    return Pattern.compile("(?m)^[ \\t]+category:[ \\t]*$").matcher(block).find();
  }

  /** What a TOOLKIT cannot be, as the CHECKs on {@code pack} and {@code PackBundle} both say. */
  private static List<String> toolkitViolations(
      String where,
      String regulatoryClass,
      boolean declaresConsent,
      boolean declaresSafety,
      boolean catalogued) {
    List<String> violations = new ArrayList<>();
    if ("REGULATED".equals(regulatoryClass)) {
      violations.add(
          where
              + " is a TOOLKIT and REGULATED — consent is recorded on an installation, and a"
              + " TOOLKIT's is derived (ck_pack_toolkit_not_regulated, ADR-061)");
    }
    if (declaresConsent || declaresSafety) {
      violations.add(
          where
              + " is a TOOLKIT declaring consent or safety — both are answered from an installation"
              + " row it does not have (ck_pack_toolkit_no_installation_controls, ADR-061)");
    }
    if (!catalogued) {
      violations.add(
          where
              + " is a TOOLKIT with no catalog entry — its kind lives on the pack row, and it would"
              + " write none (ADR-061)");
    }
    return violations;
  }

  /**
   * Every row of one {@code INSERT INTO table (cols) VALUES …} statement, as column-to-value maps.
   *
   * <p>Statement-scoped rather than file-scoped on purpose: {@code studio_blueprint} seeds are
   * dollar-quoted JSON full of raw apostrophes, and a parser tracking quotes across the whole file
   * would desynchronise on the first one. Matching one statement at a time never enters them.
   */
  private static List<Map<String, String>> rowsOf(String sql, String table) {
    // The trailing \s*\( pins the table name to its own column list, so `pack` does not match
    // `pack_studio` and `studio` does not match `studio_blueprint` — the difference between
    // checking an invariant and checking nothing.
    Pattern statement =
        Pattern.compile(
            "INSERT\\s+INTO\\s+" + table + "\\s*\\(([^)]*)\\)\\s*VALUES(.*?)(?:ON\\s+CONFLICT|;)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    List<Map<String, String>> rows = new ArrayList<>();
    Matcher matcher = statement.matcher(sql);
    while (matcher.find()) {
      List<String> columns = splitTopLevel(matcher.group(1));
      for (String tuple : tuplesOf(matcher.group(2))) {
        List<String> values = splitTopLevel(tuple);
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size() && i < values.size(); i++) {
          row.put(columns.get(i).trim().toLowerCase(Locale.ROOT), unquote(values.get(i)));
        }
        rows.add(row);
      }
    }
    return rows;
  }

  /** The parenthesised row tuples of a {@code VALUES} clause, quote- and nesting-aware. */
  private static List<String> tuplesOf(String values) {
    List<String> tuples = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuote = false;
    int depth = 0;
    for (int i = 0; i < values.length(); i++) {
      char c = values.charAt(i);
      if (inQuote) {
        if (c == '\'' && i + 1 < values.length() && values.charAt(i + 1) == '\'') {
          current.append("''");
          i++;
          continue;
        }
        if (c == '\'') {
          inQuote = false;
        }
        current.append(c);
        continue;
      }
      if (c == '\'') {
        inQuote = true;
        current.append(c);
        continue;
      }
      if (c == '(') {
        depth++;
        if (depth == 1) {
          current.setLength(0);
          continue;
        }
      } else if (c == ')') {
        depth--;
        if (depth == 0) {
          tuples.add(current.toString());
          continue;
        }
      }
      if (depth > 0) {
        current.append(c);
      }
    }
    return tuples;
  }

  /** Splits on commas that are neither inside a string literal nor inside nested parentheses. */
  private static List<String> splitTopLevel(String tuple) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuote = false;
    int depth = 0;
    for (int i = 0; i < tuple.length(); i++) {
      char c = tuple.charAt(i);
      if (inQuote) {
        if (c == '\'' && i + 1 < tuple.length() && tuple.charAt(i + 1) == '\'') {
          current.append("''");
          i++;
          continue;
        }
        if (c == '\'') {
          inQuote = false;
        }
        current.append(c);
        continue;
      }
      switch (c) {
        case '\'' -> {
          inQuote = true;
          current.append(c);
        }
        case '(' -> {
          depth++;
          current.append(c);
        }
        case ')' -> {
          depth--;
          current.append(c);
        }
        case ',' -> {
          if (depth == 0) {
            parts.add(current.toString());
            current.setLength(0);
          } else {
            current.append(c);
          }
        }
        default -> current.append(c);
      }
    }
    if (!current.toString().isBlank()) {
      parts.add(current.toString());
    }
    return parts;
  }

  /** A quoted literal becomes its text (doubled quotes unescaped); {@code NULL} becomes null. */
  private static String unquote(String value) {
    String trimmed = value.trim();
    if (trimmed.equalsIgnoreCase("NULL")) {
      return null;
    }
    if (trimmed.length() >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
      return trimmed.substring(1, trimmed.length() - 1).replace("''", "'");
    }
    return trimmed;
  }
}
