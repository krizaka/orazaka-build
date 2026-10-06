package com.orazaka.test.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;

/**
 * Where the rest of the platform is, for a rule that needs more than the repository it runs in.
 *
 * <p>Orazaka is one repository per component, assembled by the workspace ({@code krizaka/orazaka},
 * marked by {@value #MANIFEST}): every repository is cloned at its workspace path, so a
 * cross-repository rule — no pack name in engine code, every capability has an executor, a
 * context's seed matches another's — can read all of them. A repository cloned on its own cannot
 * answer those questions, and a rule that cannot see its subject must not report that it passed: it
 * is <b>skipped</b>, with the reason, through a JUnit assumption. Inside the workspace (and
 * therefore in CI, which always builds there) nothing is skipped.
 */
public final class Workspace {

  /** The file that marks the workspace root. */
  public static final String MANIFEST = "orazaka.workspace.json";

  /** Directories never descended into when looking for bootstrap files. */
  private static final Set<String> PRUNED =
      Set.of("node_modules", "target", ".git", ".next", "dist", ".venv", "src", "docs");

  private Workspace() {}

  /**
   * The workspace root above {@code startDir}, if this checkout is inside one.
   *
   * @param startDir usually {@code Path.of(System.getProperty("user.dir"))} (the module dir)
   * @return the directory holding {@value #MANIFEST}
   */
  public static Optional<Path> find(Path startDir) {
    Path current = startDir.toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve(MANIFEST))) {
        return Optional.of(current);
      }
      current = current.getParent();
    }
    return Optional.empty();
  }

  /**
   * The workspace root when there is one, otherwise the root of the repository {@code startDir} is
   * in. Never throws, so it can initialise a static field; the rules that need the workspace check
   * it with {@link #require}.
   *
   * @param startDir any directory inside the checkout
   * @return the workspace root, or the outermost directory holding a {@code pom.xml}/{@code .git}
   */
  public static Path rootOrRepository(Path startDir) {
    return find(startDir).orElseGet(() -> repositoryRoot(startDir));
  }

  /**
   * Skips the calling rule unless {@code root} is the workspace root.
   *
   * @param root what {@link #rootOrRepository} returned
   * @param rule the rule id, for the skip reason
   */
  public static void require(Path root, String rule) {
    Assumptions.assumeTrue(
        Files.isRegularFile(root.resolve(MANIFEST)),
        () ->
            "["
                + rule
                + "] is a cross-repository rule: it runs inside the Orazaka workspace"
                + " (github.com/krizaka/orazaka) and is skipped in a standalone clone of "
                + root.getFileName());
  }

  /**
   * The workspace root, skipping the caller when there is none.
   *
   * @param startDir any directory inside the checkout
   * @param what what the caller needs from the workspace, for the skip reason
   * @return the workspace root
   */
  public static Path root(Path startDir, String what) {
    Optional<Path> root = find(startDir);
    Assumptions.assumeTrue(
        root.isPresent(),
        () -> what + " lives in another repository: run inside the Orazaka workspace");
    return root.orElseThrow();
  }

  /**
   * The {@code orazaka-packs} directory of the workspace, skipping the caller when it is absent.
   *
   * @param startDir any directory inside the checkout
   * @return {@code <workspace>/orazaka-packs}
   */
  public static Path packs(Path startDir) {
    Path packs = root(startDir, "orazaka-packs").resolve("orazaka-packs");
    Assumptions.assumeTrue(
        Files.isDirectory(packs), () -> "orazaka-packs is not cloned in the workspace");
    return packs;
  }

  /**
   * Every {@code infra/initdb} directory: all of the workspace's (the umbrella's and each
   * repository's) when inside one, otherwise only the given one.
   *
   * @param initdbDir the caller's own {@code infra/initdb}
   * @return the directories, the given one first
   */
  public static List<Path> initDbDirectories(Path initdbDir) {
    List<Path> dirs = new ArrayList<>();
    dirs.add(initdbDir.toAbsolutePath().normalize());
    find(initdbDir).ifPresent(root -> collectInitDbDirectories(root, dirs));
    return dirs;
  }

  /**
   * Every bootstrap file of the platform, in the order psql applies them (by file name).
   *
   * @param initdbDir the caller's own {@code infra/initdb}
   * @return the {@code *.sql} files of {@link #initDbDirectories}, sorted by name
   */
  public static List<Path> initDbFiles(Path initdbDir) {
    List<Path> files = new ArrayList<>();
    for (Path dir : initDbDirectories(initdbDir)) {
      try (var stream = Files.list(dir)) {
        stream.filter(p -> p.toString().endsWith(".sql")).forEach(files::add);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    files.sort(Comparator.comparing(p -> p.getFileName().toString()));
    return files;
  }

  /**
   * One context's bootstrap file: from {@code initdbDir} when this repository owns it, otherwise
   * from the repository of the workspace that does. Skips the caller when neither has it — the
   * owning repository is not on disk.
   *
   * @param initdbDir the caller's own {@code infra/initdb}
   * @param fileName e.g. {@code 30-jobs-config.sql}
   * @return the file
   */
  public static Path initDbFile(Path initdbDir, String fileName) {
    for (Path dir : initDbDirectories(initdbDir)) {
      Path file = dir.resolve(fileName);
      if (Files.isRegularFile(file)) {
        return file;
      }
    }
    Assumptions.abort(
        fileName + " is owned by another repository: run inside the Orazaka workspace");
    throw new IllegalStateException("unreachable");
  }

  private static void collectInitDbDirectories(Path root, List<Path> dirs) {
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
              if (!dir.equals(root) && PRUNED.contains(name)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              if (name.equals("initdb")
                  && dir.getParent() != null
                  && dir.getParent().getFileName().toString().equals("infra")) {
                Path normalized = dir.toAbsolutePath().normalize();
                if (!dirs.contains(normalized)) {
                  dirs.add(normalized);
                }
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to walk the workspace at " + root, e);
    }
  }

  private static Path repositoryRoot(Path startDir) {
    Path current = startDir.toAbsolutePath();
    Path outermost = current;
    while (current != null) {
      if (Files.isDirectory(current.resolve(".git"))) {
        return current;
      }
      if (Files.isRegularFile(current.resolve("pom.xml"))
          || Files.isRegularFile(current.resolve("package.json"))) {
        outermost = current;
      }
      current = current.getParent();
    }
    return outermost;
  }
}
