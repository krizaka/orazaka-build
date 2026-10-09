package com.krizaka.orazaka.test.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.krizaka.test.sql.InitDb;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data-seam fitness function (strangler-fig Phase 0): the DB bootstrap is one file per bounded
 * context under {@code infra/initdb/}, and a {@code FOREIGN KEY} may only target a table created in
 * the <b>same</b> file — cross-context references are opaque ids, never FKs (AGENTS.md §5).
 *
 * <p>Pure static utility over the SQL text — no database needed, so the rule runs on every build
 * like the ArchUnit governance rules.
 *
 * @see SourceFileScanner
 */
public final class SqlBoundaryRules {

  private static final Pattern CREATE_TABLE =
      Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z_][A-Za-z0-9_]*)");
  private static final Pattern REFERENCES =
      Pattern.compile("REFERENCES\\s+([A-Za-z_][A-Za-z0-9_]*)");

  private SqlBoundaryRules() {}

  /**
   * Asserts that every {@code REFERENCES} in every {@code *.sql} file under {@code initdbDir}
   * targets a table created in the same file (same bounded context).
   *
   * @param initdbDir the {@code infra/initdb} directory
   */
  public static void assertNoCrossContextForeignKeys(Path initdbDir) {
    assertTrue(
        Files.isDirectory(initdbDir),
        () -> "infra/initdb directory not found at: " + initdbDir.toAbsolutePath());

    List<String> violations = new ArrayList<>();
    // Every context's file the workspace holds (each repository owns its own), or only this
    // repository's standalone. The rule is per file, so a partial set is judged correctly.
    for (Path file :
        GovernanceSubjects.require(
            "SEAM-001", "*.sql files in " + initdbDir, Workspace.initDbFiles(initdbDir))) {
      String sql = stripComments(readFile(file));
      Set<String> ownTables = extract(CREATE_TABLE, sql);
      for (String referenced : extract(REFERENCES, sql)) {
        if (!ownTables.contains(referenced)) {
          violations.add(
              file.getFileName()
                  + " references table '"
                  + referenced
                  + "' owned by another context — cross-context FKs are banned; use an opaque id");
        }
      }
    }
    if (!violations.isEmpty()) {
      fail("Cross-context foreign keys in infra/initdb:\n  " + String.join("\n  ", violations));
    }
  }

  /**
   * Walks up from {@code startDir} to locate the repository root containing {@code infra/initdb}.
   *
   * @param startDir usually {@code Path.of(System.getProperty("user.dir"))} (the module dir)
   * @return the initdb directory
   */
  public static Path locateInitDb(Path startDir) {
    return InitDb.locate(startDir);
  }

  /**
   * Reads one bootstrap file with its {@code --} comments stripped.
   *
   * <p>Exposed so a second SQL fitness function does not need a second SQL reader: {@link
   * PackCoherenceRules} parses the same files for a different invariant and shares this one
   * accessor. Comments go first for the same reason they do here — commented-out DDL and prose that
   * <i>names</i> a table to explain a rule are documentation, not violations.
   *
   * @param initdbDir the {@code infra/initdb} directory
   * @param fileName the bootstrap file, e.g. {@code 80-studio.sql}
   * @return its SQL text, comment-free
   */
  public static String readContext(Path initdbDir, String fileName) {
    Path own = initdbDir.resolve(fileName);
    // A context's file lives in the repository that owns the context; from any other repository it
    // is read from the workspace, and the caller is skipped when that repository is not on disk.
    Path file = Files.isRegularFile(own) ? own : Workspace.initDbFile(initdbDir, fileName);
    return stripComments(readFile(file));
  }

  private static String readFile(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Drops {@code -- …} line comments so commented-out DDL never triggers the rule. */
  private static String stripComments(String sql) {
    return sql.replaceAll("(?m)--.*$", "");
  }

  private static Set<String> extract(Pattern pattern, String sql) {
    Set<String> names = new HashSet<>();
    Matcher matcher = pattern.matcher(sql);
    while (matcher.find()) {
      names.add(matcher.group(1).toLowerCase(Locale.ROOT));
    }
    return names;
  }
}
