package com.orazaka.test.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Two-discriminant coherence [EXEC-001 / EXEC-002]: every enabled capability must have somewhere to
 * run and something to run it (ADR-038).
 *
 * <p>ADR-037 turned routing into data, which left the job plane with <b>two</b> independent
 * dispatch discriminants on every capability row. They fail differently and neither used to be
 * checked:
 *
 * <ul>
 *   <li>{@code routing_key} picks the <b>process</b>. Wrong, and the message lands on a queue no
 *       worker drains — it simply sits there.
 *   <li>{@code handler_key} picks the <b>code path</b> inside that process. Wrong, and the message
 *       arrives correctly and dies on arrival.
 * </ul>
 *
 * <p><b>Why a seed-file rule rather than a Spring test.</b> The facts live in three places that no
 * single runtime has all of: capability rows in {@code infra/initdb}, {@code handlerKey()} literals
 * in Java across two modules, and {@code bindings} in each worker's {@code worker.yaml} — one of
 * which belongs to a Python worker that is in no Maven reactor. A build-time read of the files is
 * the only place all three are visible at once, and it costs nothing.
 *
 * <p>The runtime half is {@code ExecutorCoherenceService}, which catches what this cannot: a row an
 * operator added after the build, and a jar that failed to load.
 */
public final class ExecutorCoherenceRules {

  private static final String JOBS_CONTEXT = "30-jobs-config.sql";

  /** {@code handlerKey()} implementations: {@code return "image.generate";}. */
  private static final Pattern HANDLER_KEY_RETURN =
      Pattern.compile("handlerKey\\(\\)\\s*\\{\\s*return\\s+\"([^\"]+)\"", Pattern.DOTALL);

  /**
   * A capability row: {@code ('key', 'handler.key', 'job.family.action', …)}.
   *
   * <p>It anchored on the endpoint columns — {@code '/some/path', 'POST'} between the handler and
   * the routing key — which is what a positional pattern does until the positions move. They moved
   * when {@code uri_path} and {@code http_method} were dropped (ADR-069 §5) and this rule said
   * "parsed no capability rows" rather than passing over an empty population, which is GOV-006
   * working. Anchored on the two values it actually needs now, neither of which is positional: a
   * handler key and a routing key are recognisable by their own shape.
   */
  private static final Pattern CAPABILITY_ROW =
      Pattern.compile(
          "\\('(?<key>orazaka\\.[a-z.]+)',\\s*'(?<handler>[a-z]+\\.[a-z]+)',"
              + "\\s*'(?<routing>job\\.[a-z.]+)'",
          Pattern.DOTALL);

  private ExecutorCoherenceRules() {}

  /**
   * [EXEC-001] Every enabled capability executed <b>in process</b> has a {@code JobExecutor} for
   * its {@code handler_key}.
   *
   * <p><b>Scoped to in-process execution, and that scope is the point.</b> {@code handler_key}
   * picks a code path inside a Java service; it means nothing to a worker that dispatches on the
   * routing key alone. The Python media worker is exactly that — it drains {@code job.video.*} and
   * {@code job.compose.*} and has never read a handler key — so requiring a Java executor for
   * {@code video.generate} or {@code media.compose} would fail the build on the two capabilities
   * that best demonstrate the AMQP contract being the real SPI (ADR-037 §3.4).
   *
   * <p>The scope is read from the repository's own taxonomy rather than a hard-coded name: {@code
   * orazaka-apps/services/**} is run as a JVM deployable and dispatches by handler key; {@code
   * orazaka-apps/workers/**} is an external worker and does not (AGENTS.md §2).
   *
   * @param repositoryRoot the repository root
   */
  public static void assertEveryCapabilityHasAnExecutor(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "EXEC-001");
    Map<String, String> handlers =
        GovernanceSubjects.require(
            "EXEC-001", "capabilities with a handler key", capabilityHandlerKeys(repositoryRoot));
    Map<String, String> routes = capabilityRoutingKeys(repositoryRoot);
    Set<String> inProcessBindings = inProcessBindings(repositoryRoot);
    Set<String> implemented = implementedHandlerKeys(repositoryRoot);

    List<String> violations = new ArrayList<>();
    handlers.forEach(
        (featureKey, handlerKey) -> {
          String routingKey = routes.get(featureKey);
          boolean inProcess =
              routingKey != null
                  && inProcessBindings.stream().anyMatch(b -> matches(b, routingKey));
          if (inProcess && !implemented.contains(handlerKey)) {
            violations.add(
                featureKey
                    + " declares handler_key '"
                    + handlerKey
                    + "' which no JobExecutor implements — every job for it would reach the right"
                    + " process and die there");
          }
        });
    if (!violations.isEmpty()) {
      throw new AssertionError(
          "[EXEC-001] A capability with no executor is a job that fails on arrival (ADR-038)."
              + " Implemented handler keys: "
              + implemented
              + "\n  "
              + String.join("\n  ", violations));
    }
  }

  /**
   * [EXEC-002] Every enabled capability's {@code routing_key} is drained by some worker's declared
   * bindings.
   *
   * <p>Binding-based because it is the only thing that answers the question. The {@code
   * worker_family} column that once sat beside {@code routing_key} has been removed: its only
   * coherent reading was "the family segment of the routing key", which made it derivable, and its
   * seeded values disagreed with even that on the two capabilities the Python worker serves. A
   * binding matched against the routing key answers exactly (ADR-038 follow-up).
   *
   * @param repositoryRoot the repository root
   */
  public static void assertEveryCapabilityIsDrained(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "EXEC-002");
    Map<String, String> routes =
        GovernanceSubjects.require(
            "EXEC-002", "capabilities with a routing key", capabilityRoutingKeys(repositoryRoot));
    Map<String, List<String>> bindingsByWorker = declaredBindings(repositoryRoot);

    List<String> violations = new ArrayList<>();
    routes.forEach(
        (featureKey, routingKey) -> {
          boolean drained =
              bindingsByWorker.values().stream()
                  .flatMap(List::stream)
                  .anyMatch(binding -> matches(binding, routingKey));
          if (!drained) {
            violations.add(
                featureKey
                    + " routes to '"
                    + routingKey
                    + "' which no worker.yaml declares a binding for — its messages would sit on a"
                    + " queue nobody drains");
          }
        });
    if (!violations.isEmpty()) {
      throw new AssertionError(
          "[EXEC-002] A capability nobody drains is a job that never runs (ADR-038). Declared"
              + " bindings: "
              + bindingsByWorker
              + "\n  "
              + String.join("\n  ", violations));
    }
  }

  /**
   * Walks up to the repository root.
   *
   * @param startDir usually the module directory
   * @return the repository root
   */
  public static Path locateRepositoryRoot(Path startDir) {
    return PackPurityRules.locateRepositoryRoot(startDir);
  }

  // ─── readers ───

  private static Map<String, String> capabilityHandlerKeys(Path root) {
    return capabilityColumn(root, "handler");
  }

  private static Map<String, String> capabilityRoutingKeys(Path root) {
    return capabilityColumn(root, "routing");
  }

  private static Map<String, String> capabilityColumn(Path root, String group) {
    String sql =
        SqlBoundaryRules.readContext(root.resolve("infra").resolve("initdb"), JOBS_CONTEXT);
    int insert = sql.indexOf("INSERT INTO orazaka_capabilities");
    if (insert < 0) {
      throw new AssertionError("no orazaka_capabilities seed found in " + JOBS_CONTEXT);
    }
    Map<String, String> byKey = new LinkedHashMap<>();
    Matcher matcher = CAPABILITY_ROW.matcher(sql.substring(insert));
    while (matcher.find()) {
      byKey.put(matcher.group("key"), matcher.group(group));
    }
    if (byKey.isEmpty()) {
      throw new AssertionError("parsed no capability rows — the rule would assert nothing");
    }
    return byKey;
  }

  /**
   * The bindings of workers that run inside a JVM service and therefore dispatch by handler key.
   *
   * <p>{@code orazaka-apps/services/**} is imported-and-run Java; {@code orazaka-apps/workers/**}
   * is an external process speaking only the wire contract (AGENTS.md §2).
   */
  private static Set<String> inProcessBindings(Path root) {
    Set<String> bindings = new LinkedHashSet<>();
    try (Stream<Path> paths = Files.walk(root.resolve("orazaka-apps").resolve("services"))) {
      for (Path file :
          paths
              .filter(p -> p.getFileName().toString().equals("worker.yaml"))
              .filter(p -> !p.toString().contains("/target/"))
              .toList()) {
        Matcher binding =
            Pattern.compile("(?m)^\\s*-\\s*\"?(job\\.[^\"\\s]+)\"?").matcher(read(file));
        while (binding.find()) {
          bindings.add(binding.group(1));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to walk services for worker.yaml", e);
    }
    return bindings;
  }

  /** Every {@code handlerKey()} literal across production Java. */
  private static Set<String> implementedHandlerKeys(Path root) {
    Set<String> keys = new LinkedHashSet<>();
    for (Path file : javaSources(root)) {
      Matcher matcher = HANDLER_KEY_RETURN.matcher(read(file));
      while (matcher.find()) {
        keys.add(matcher.group(1));
      }
    }
    return keys;
  }

  /** Every {@code worker.yaml}'s declared bindings, by worker name. */
  private static Map<String, List<String>> declaredBindings(Path root) {
    Map<String, List<String>> byWorker = new LinkedHashMap<>();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path file :
          paths
              .filter(p -> p.getFileName().toString().equals("worker.yaml"))
              .filter(p -> !p.toString().contains("/target/"))
              .toList()) {
        String yaml = read(file);
        String name =
            find(yaml, Pattern.compile("(?m)^name:\\s*(\\S+)"))
                .orElse(file.getParent().getFileName().toString());
        List<String> bindings = new ArrayList<>();
        Matcher binding = Pattern.compile("(?m)^\\s*-\\s*\"?(job\\.[^\"\\s]+)\"?").matcher(yaml);
        while (binding.find()) {
          bindings.add(binding.group(1));
        }
        byWorker.put(name, bindings);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to walk " + root, e);
    }
    if (byWorker.isEmpty()) {
      throw new AssertionError("found no worker.yaml — the rule would assert nothing");
    }
    return byWorker;
  }

  private static java.util.Optional<String> find(String text, Pattern pattern) {
    Matcher matcher = pattern.matcher(text);
    return matcher.find() ? java.util.Optional.of(matcher.group(1)) : java.util.Optional.empty();
  }

  private static List<Path> javaSources(Path root) {
    List<Path> sources = new ArrayList<>();
    for (String module : List.of("orazaka-apps", "orazaka-libs")) {
      Path dir = root.resolve(module);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (Stream<Path> paths = Files.walk(dir)) {
        paths
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> p.toString().contains("/src/main/"))
            .filter(p -> !p.toString().contains("/target/"))
            .forEach(sources::add);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to walk " + dir, e);
      }
    }
    return sources;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + file, e);
    }
  }

  /** AMQP topic semantics restricted to what a binding may contain: {@code *} is one segment. */
  private static boolean matches(String binding, String routingKey) {
    String[] pattern = binding.split("\\.");
    String[] key = routingKey.split("\\.");
    if (pattern.length != key.length) {
      return false;
    }
    for (int i = 0; i < pattern.length; i++) {
      if (!"*".equals(pattern[i]) && !pattern[i].equals(key[i])) {
        return false;
      }
    }
    return true;
  }
}
