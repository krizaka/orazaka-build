package com.orazaka.test.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.test.architecture.SourceRules;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;

/**
 * Stateless file-system scanner for detecting banned patterns in Java production source files.
 *
 * <p>Uses Apache Commons IO {@link LineIterator} for low-memory streaming instead of eager heap
 * array loading. Pure static utility — no inheritance, no state.
 *
 * @see GovernanceRules
 */
public final class SourceFileScanner {

  private SourceFileScanner() {}

  /** Banned literal pattern for source scanning. */
  public record BannedPattern(String contains, String label) {}

  private static final List<BannedPattern> DEFAULT_BANNED_PATTERNS =
      List.of(
          new BannedPattern("localhost:", "banned literal"),
          new BannedPattern("127.0.0.1", "banned literal"),
          new BannedPattern("dummy-key", "hardcoded credential"),
          new BannedPattern("\"dummy", "hardcoded credential"),
          new BannedPattern("System.getenv(", "raw env access (use typed Properties)"));

  /**
   * Scans Java source files under {@code sourceRoot} for default banned patterns [GOV-003]. Skips
   * comment lines. Asserts no violations found.
   */
  public static void assertNoBannedLiterals(Path sourceRoot) {
    assertNoBannedPatterns(sourceRoot, DEFAULT_BANNED_PATTERNS);
  }

  /**
   * Scans Java source files for the given banned patterns. Asserts no violations found.
   *
   * @param sourceRoot path to the module's src/main/java directory
   * @param patterns list of patterns to scan for
   */
  private static void assertNoBannedPatterns(Path sourceRoot, List<BannedPattern> patterns) {
    // It returned green for a source root that did not exist.
    GovernanceSubjects.require(
        "GOV-003",
        "Java sources under " + sourceRoot,
        Files.exists(sourceRoot) ? collectJavaFiles(sourceRoot) : List.of());
    List<String> violations = scanForPatterns(sourceRoot, patterns);
    assertTrue(
        violations.isEmpty(),
        "Production source contains banned literals:\n" + String.join("\n", violations));
  }

  /**
   * Scans for {@code org.springframework.core.env.Environment} imports in non-@Configuration
   * classes [ERR-113]. Asserts no violations found.
   */
  public static void assertNoEnvironmentInjection(Path sourceRoot) {
    SourceRules.assertNoEnvironmentInjection(sourceRoot);
  }

  // ─── Internal Scanners ───

  private static List<String> scanForPatterns(Path sourceRoot, List<BannedPattern> patterns) {
    List<String> violations = new ArrayList<>();
    collectJavaFiles(sourceRoot)
        .forEach(
            file -> {
              int lineNum = 0;
              try (LineIterator it = FileUtils.lineIterator(file, StandardCharsets.UTF_8.name())) {
                while (it.hasNext()) {
                  lineNum++;
                  String line = it.next().trim();
                  if (isComment(line)) {
                    continue;
                  }
                  for (BannedPattern pattern : patterns) {
                    if (line.contains(pattern.contains())) {
                      violations.add(
                          file.getName() + ":" + lineNum + " -> " + pattern.label() + ": " + line);
                    }
                  }
                }
              } catch (IOException e) {
                throw new UncheckedIOException("Failed to scan " + file.getName(), e);
              }
            });
    return violations;
  }

  private static List<File> collectJavaFiles(Path sourceRoot) {
    try (Stream<Path> paths = Files.walk(sourceRoot)) {
      return paths.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to walk source directory: " + sourceRoot, e);
    }
  }

  private static boolean isComment(String trimmedLine) {
    return trimmedLine.startsWith("//")
        || trimmedLine.startsWith("*")
        || trimmedLine.startsWith("/*");
  }
}
