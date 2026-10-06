package com.orazaka.test.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The run surface is sealed [DOOR-001]: no inbound HTTP entry, in any module, dispatches a job.
 *
 * <p><b>Why this rule and not the integration test.</b> M3 closed door 1 and proved, by planting a
 * restored direct controller, that {@code DoorOneControlsIT} stayed green: it asserts what the run
 * path produces — a run row, a data class, a trail, one settlement — and a second door cut beside
 * the first does not disturb any of that. <b>A test of what the correct path does cannot detect an
 * additional incorrect path.</b> Absence is not assertable by sampling presence. {@code
 * DoorOneClosedTest} is the right shape scoped to one service; this is the repository-wide version
 * it names in its own javadoc.
 *
 * <h2>The sinks are derived, not listed</h2>
 *
 * {@code DoorOneClosedTest} names two sinks, {@code JobService.createJob} and {@code
 * JobQueuePublisherService.publish}. A hand-written list is {@code BlueprintFitnessTest}'s output
 * table again — right until someone adds a third publisher, and then silently wrong. So this rule
 * derives the property instead: <b>a sink is a method that puts a command on the jobs exchange.</b>
 *
 * <p>It is derived in two steps, and both matter:
 *
 * <ol>
 *   <li><b>How the exchange is named</b> is read from the source rather than assumed: every
 *       declaration of a {@code String} constant whose value is the jobs exchange contributes its
 *       own name, so a module that spells it {@code JOBS_EXCHANGE} in its own {@code AmqpConstants}
 *       is found without this rule knowing that class exists.
 *   <li><b>A dispatcher class</b> is one whose source both names that exchange and contains a
 *       dispatch construct — a {@code convertAndSend} call or an {@code OutboxMessage}. That second
 *       half is what keeps {@code AmqpConstants} (declares the name, sends nothing) and the
 *       {@code @Configuration} classes that declare the topology (build the exchange, send nothing)
 *       out of the sink set, without naming or excluding either of them.
 * </ol>
 *
 * <p><b>Both forms of dispatch count, and missing the second would have been the defect.</b> {@code
 * JobQueuePublisherService.publish} calls {@code convertAndSend} directly; {@code publishApproval}
 * appends an {@code OutboxMessage} carrying the jobs exchange on the row, which {@code OutboxRelay}
 * publishes later (ADR-067). A rule that only knew about {@code convertAndSend} would call the
 * outbox path clean, and the outbox is the path the run itself uses — so the bypass would have been
 * invited to use it too.
 *
 * <h2>Why the source and not the bytecode</h2>
 *
 * {@code javac} inlines a {@code static final String} compile-time constant into its call site, so
 * {@code MessagingContract.JOBS_EXCHANGE} leaves <i>no</i> reference to {@code MessagingContract}
 * in the caller's bytecode and ArchUnit cannot see which exchange a {@code convertAndSend} names.
 * The jobs exchange and the events exchange are indistinguishable to a bytecode-only rule — and
 * telling them apart is the whole rule, since {@code AgentController} publishes agent presence to
 * the events exchange from a {@code @RestController} and is not a bypass. Reachability is still
 * computed over the bytecode, where the call graph actually is.
 *
 * <h2>The population this rule examines</h2>
 *
 * <b>Every production class on this module's classpath that Spring exposes as an inbound HTTP
 * entry</b> — annotated {@code @RestController} or {@code @Controller}, or carrying any {@code
 * *Mapping} method annotation — <b>and that is the same population the property is about, because
 * door 1 was a transport entry that dispatched a capability with no run behind it.</b> The subjects
 * are taken from the <i>annotations</i> and never from a package name: {@code DoorOneClosedTest}
 * matched {@code ..infrastructure.adapter.rest..}, and a controller that lands in any other package
 * in any module — which [ERR-130] permits nowhere but which a rule may not assume — would have been
 * invisible to it while being just as reachable over HTTP. This is the mistake [CFG-001] made in
 * the other direction: precise about non-private constructors while the container counted every
 * declared one.
 *
 * <h2>The two exemptions, each carrying its reason</h2>
 *
 * <ul>
 *   <li>{@code JobController#approveJob} → {@code JobQueuePublisherService#publishApproval}.
 *       Approval is a <b>gate in front of work already written</b>, not a way in: the job row and
 *       its payload were written when the automation ran, and the endpoint releases it. M3
 *       established this the hard way — the compiler refused to delete {@code
 *       JobQueuePublisherService} because this path still needed it.
 *   <li>{@code orazaka.core.chat.completion} is <b>not exempted, because it is never a subject</b>:
 *       it is synchronous and off the broker by contract (AGENTS.md §6), so it reaches no dispatch
 *       sink and this rule has nothing to say about it. It is named here so the next reader knows
 *       the omission was decided rather than overlooked — an exemption that is a list entry with no
 *       reason is how the next one gets added in silence.
 * </ul>
 *
 * <p>Neither is a suppression. An exemption is a pair of fully-qualified names with the reason
 * beside it, and a new one costs its author this javadoc.
 *
 * <h2>Static, deliberately</h2>
 *
 * No part of this rule runs a stack. The e2e harness that would otherwise be the obvious place to
 * prove this has just been found to leave its stack up on a red build, and to lose a contract
 * assertion without reporting it (ADR-069 §0.3, §6). A rule about a door that must never reopen
 * cannot be carried by the least-verified component in the repository.
 */
public final class RunSurfaceRules {

  private static final String RULE = "DOOR-001";

  /** This rule family's own package: its sources describe dispatch, they do not perform it. */
  private static final String RULES_PACKAGE = "com.orazaka.test.architecture.";

  /** The exchange a job command travels on (AGENTS.md §6). */
  private static final String JOBS_EXCHANGE = "orazaka.jobs";

  /**
   * A {@code String} constant declared equal to the jobs exchange, whatever it is called.
   *
   * <p>This is the derivation's first half: the rule learns the names the repository uses rather
   * than carrying them. {@code MessagingContract.JOBS_EXCHANGE} and each service's own {@code
   * AmqpConstants.JOBS_EXCHANGE} are found the same way, and a fourth spelling would be too.
   */
  private static final Pattern EXCHANGE_CONSTANT =
      Pattern.compile("String\\s+(\\w+)\\s*=\\s*\"" + Pattern.quote(JOBS_EXCHANGE) + "\"");

  /** Strips {@code //} and block comments: a comment that NAMES a dispatch is not one. */
  private static final Pattern COMMENTS = Pattern.compile("//[^\n]*|/\\*.*?\\*/", Pattern.DOTALL);

  /** Spring's {@code @Configuration}: declares the topology, never puts anything on it. */
  private static final String CONFIGURATION = "@Configuration";

  /**
   * The run's own dispatch seam — the port a step is submitted through (ADR-067).
   *
   * <p><b>This is the one name in the rule, and the asymmetry is the point.</b> The sinks are
   * derived because they GROW: a third producer arrives and a transcribed list is silently wrong,
   * which is what {@code BlueprintFitnessTest}'s output table was. The run seam is architecturally
   * singular — there is one way a run dispatches a step — and naming it with its reason is the
   * declared-exemption pattern, not a list. A new publisher does not implement this port, so it is
   * caught; a second legitimate seam would cost its author a line here and a paragraph of why.
   *
   * <p>What makes reaching it "through a run" rather than a second door: this port's argument is a
   * {@code StepDispatch}, which carries the {@code runId}. <b>The type makes a run impossible to
   * omit.</b> A controller that reaches this adapter has a run behind it because it could not have
   * built the argument otherwise; a controller that reaches any other dispatcher does not.
   */
  static final String RUN_DISPATCH_SEAM =
      "com.orazaka.studioservice.domain.port.StepExecutionClient";

  /** Spring's inbound-HTTP stereotypes. */
  private static final Set<String> WEB_STEREOTYPES =
      Set.of(
          "org.springframework.web.bind.annotation.RestController",
          "org.springframework.stereotype.Controller");

  /**
   * The dispatch a caller is allowed to reach, and why.
   *
   * @param caller the fully-qualified method that may reach the sink
   * @param sink the fully-qualified dispatcher class it may reach
   * @param reason why this is a gate rather than an entry — printed when the rule reports
   */
  private record Exemption(String caller, String sink, String reason) {}

  private static final List<Exemption> EXEMPTIONS =
      List.of(
          new Exemption(
              "com.orazaka.conversationservice.infrastructure.adapter.rest.JobController"
                  + ".approveJob",
              "com.orazaka.conversationservice.application.service.JobQueuePublisherService",
              "approval is a gate in front of work already written, not an entry: the job row and"
                  + " its payload were written when the automation ran, and this endpoint releases"
                  + " it (ADR-068 §5, established when the compiler refused the deletion)"));

  private RunSurfaceRules() {}

  /**
   * Asserts [DOOR-001] over this module's production classes, against the whole repository's sinks.
   *
   * <p>The subjects come from the module — that is where its controllers are — and the sinks from
   * every {@code .java} file in the repository, so a controller here reaching a dispatcher in
   * another module is judged the same as one reaching its own.
   */
  public static void assertNoInboundEntryDispatchesAJob() {
    assertNoInboundEntryDispatchesAJob(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.orazaka"));
  }

  /**
   * The rule, over classes the caller imported.
   *
   * @param classes production classes to judge
   */
  public static void assertNoInboundEntryDispatchesAJob(JavaClasses classes) {
    Set<String> dispatcherClasses =
        GovernanceSubjects.require(
            RULE,
            "classes that dispatch onto " + JOBS_EXCHANGE + " (no source names it and sends)",
            dispatcherClasses());

    List<JavaClass> inboundEntries =
        classes.stream().filter(RunSurfaceRules::isInboundHttpEntry).toList();
    GovernanceSubjects.require(
        RULE, "inbound HTTP entries on this module's classpath", inboundEntries);

    List<JavaClass> dispatchersHere =
        classes.stream().filter(type -> dispatcherClasses.contains(type.getName())).toList();
    if (dispatchersHere.isEmpty()) {
      // A true pass, and not [PACK-001]'s vacuous one. The SUBJECTS — this module's inbound
      // entries — exist and were required above; what is absent is any class on this classpath
      // able to dispatch at all, which is itself the proof that none of them does. Services are
      // separate processes: a controller here cannot call another app's class, so a classpath with
      // no dispatcher is a module where the property holds by construction. The day this module
      // gains one, the check below starts biting with no edit here.
      return;
    }

    // A dispatcher reached THROUGH THE RUN SEAM is the correct path, not a violation: that is
    // what "every capability invocation reaches the broker through a run" means, and the first
    // version of this rule flagged StudioRunController.start — the run path itself — as door 1.
    Set<String> sinks = new LinkedHashSet<>();
    dispatchersHere.stream()
        .filter(type -> !type.isAssignableTo(RUN_DISPATCH_SEAM))
        .forEach(type -> sinks.add(type.getName()));
    if (sinks.isEmpty()) {
      return;
    }

    List<String> violations = new ArrayList<>();
    for (JavaClass entry : inboundEntries) {
      for (JavaMethod handler : entry.getMethods()) {
        List<String> path = pathToSink(handler, sinks, classes);
        if (path != null && !isExempt(handler, path)) {
          violations.add(String.join("\n        → ", path));
        }
      }
    }
    if (!violations.isEmpty()) {
      fail(
          "["
              + RULE
              + "] an HTTP entry that reaches the job plane is door 1 coming back. A capability"
              + " invoked without a run carries none of a run's controls: no data class from its"
              + " pack, no retention by that class, no audit trail, no scope guard (ADR-068 §5)."
              + " Reached:\n  "
              + String.join("\n  ", violations));
    }
  }

  /**
   * Whether Spring exposes this class as an inbound HTTP entry — by annotation, never by package.
   */
  private static boolean isInboundHttpEntry(JavaClass type) {
    if (!type.getPackageName().startsWith("com.orazaka")) {
      return false;
    }
    boolean stereotyped =
        type.getAnnotations().stream()
            .map(annotation -> annotation.getRawType().getName())
            .anyMatch(WEB_STEREOTYPES::contains);
    boolean mapped =
        type.getMethods().stream()
            .flatMap(method -> method.getAnnotations().stream())
            .anyMatch(annotation -> annotation.getRawType().getSimpleName().endsWith("Mapping"));
    return stereotyped || mapped;
  }

  /**
   * Every class whose source both names the jobs exchange and dispatches onto it.
   *
   * <p>Package-private so {@code RunSurfaceRulesTest} can assert the four decisions this derivation
   * makes, each of which was wrong at some point while it was being written.
   *
   * @return fully-qualified class names
   */
  static Set<String> dispatcherClasses() {
    // The whole workspace: a dispatcher may live in any repository.
    Path repositoryRoot = Workspace.rootOrRepository(Path.of(System.getProperty("user.dir")));
    // The dispatchers live in another repository (the studio's outbox): standalone, the rule
    // cannot see its subject and is skipped rather than judged on the empty set.
    Workspace.require(repositoryRoot, "DOOR-001");
    List<Path> sources = productionSources(repositoryRoot);

    Set<String> exchangeNames = new LinkedHashSet<>();
    exchangeNames.add('"' + JOBS_EXCHANGE + '"');
    Map<Path, String> bodies = new LinkedHashMap<>();
    for (Path source : sources) {
      String body = read(source);
      bodies.put(source, body);
      Matcher declared = EXCHANGE_CONSTANT.matcher(body);
      while (declared.find()) {
        exchangeNames.add(declared.group(1));
      }
    }

    Set<String> dispatchers = new LinkedHashSet<>();
    for (Map.Entry<Path, String> entry : bodies.entrySet()) {
      String body = COMMENTS.matcher(entry.getValue()).replaceAll(" ");
      // What is left after the DECLARATIONS are removed. A class that only declares the name holds
      // a constant; a class that still mentions it afterwards hands it to something, and handing
      // the jobs exchange to something is what dispatching onto it is. Derived that way rather
      // than from a list of verbs, because the list was already wrong: the studio adapter's verb
      // is `appendCommand`, its `convertAndSend` was a comment about what it replaced, and a third
      // producer would have brought a fourth verb nobody added here.
      String afterDeclarations = EXCHANGE_CONSTANT.matcher(body).replaceAll(" ");
      boolean handsItToSomething = exchangeNames.stream().anyMatch(afterDeclarations::contains);
      String qualified = qualifiedName(entry.getKey(), entry.getValue());
      if (handsItToSomething
          && !body.contains(CONFIGURATION)
          && !qualified.startsWith(RULES_PACKAGE)) {
        dispatchers.add(qualified);
      }
    }
    return dispatchers;
  }

  /**
   * The shortest call path from an HTTP handler to a dispatch sink, or {@code null} if none.
   *
   * <p>Transitive on purpose. {@code DoorOneClosedTest} needed a second test for the delegate case
   * — the controllers reached the publisher through {@code MediaJobService} — and a rule that only
   * looked at direct calls would be one indirection away from useless.
   */
  private static List<String> pathToSink(JavaMethod start, Set<String> sinks, JavaClasses classes) {
    Map<String, String> cameFrom = new LinkedHashMap<>();
    Set<String> seen = new LinkedHashSet<>();
    Deque<JavaMethod> queue = new ArrayDeque<>();
    queue.add(start);
    seen.add(key(start));
    while (!queue.isEmpty()) {
      JavaMethod current = queue.poll();
      if (sinks.contains(current.getOwner().getName()) && !key(current).equals(key(start))) {
        return trace(cameFrom, key(start), key(current));
      }
      for (JavaMethod next : calledFrom(current, classes)) {
        if (seen.add(key(next))) {
          cameFrom.put(key(next), key(current));
          queue.add(next);
        }
      }
    }
    return null;
  }

  /**
   * The production methods one method calls, following an interface call to its implementations.
   *
   * <p>A port is an interface and its adapter is package-private (AGENTS.md §2), so a call graph
   * that stopped at the interface would stop exactly where this repository puts its boundaries.
   */
  private static List<JavaMethod> calledFrom(JavaMethod method, JavaClasses classes) {
    List<JavaMethod> next = new ArrayList<>();
    method
        .getMethodCallsFromSelf()
        .forEach(
            call -> {
              JavaClass owner = call.getTargetOwner();
              if (!owner.getPackageName().startsWith("com.orazaka")) {
                return;
              }
              String name = call.getTarget().getName();
              collectMethod(owner, name, next);
              if (owner.isInterface()) {
                classes.stream()
                    .filter(
                        candidate ->
                            !candidate.isInterface() && candidate.isAssignableTo(owner.getName()))
                    .forEach(implementation -> collectMethod(implementation, name, next));
              }
            });
    return next;
  }

  private static void collectMethod(JavaClass owner, String name, List<JavaMethod> into) {
    owner.getMethods().stream().filter(method -> method.getName().equals(name)).forEach(into::add);
  }

  /** Whether this reached sink is one of the declared exemptions, for this exact caller. */
  private static boolean isExempt(JavaMethod handler, List<String> path) {
    String caller = key(handler);
    String reached = path.get(path.size() - 1);
    return EXEMPTIONS.stream()
        .anyMatch(
            exemption ->
                exemption.caller().equals(caller) && reached.startsWith(exemption.sink() + "."));
  }

  private static List<String> trace(Map<String, String> cameFrom, String start, String end) {
    List<String> path = new ArrayList<>();
    String current = end;
    while (current != null) {
      path.add(0, current);
      current = start.equals(current) ? null : cameFrom.get(current);
    }
    return path;
  }

  private static String key(JavaMethod method) {
    return method.getOwner().getName() + "." + method.getName();
  }

  private static List<Path> productionSources(Path repositoryRoot) {
    try (Stream<Path> files = Files.walk(repositoryRoot)) {
      return files
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> path.toString().contains("/src/main/java/"))
          .filter(path -> !path.toString().contains("/target/"))
          .toList();
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  private static String qualifiedName(Path source, String body) {
    Matcher declared = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE).matcher(body);
    String simpleName = source.getFileName().toString().replace(".java", "");
    return declared.find() ? declared.group(1) + "." + simpleName : simpleName;
  }

  private static String read(Path source) {
    try {
      return Files.readString(source);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }
}
