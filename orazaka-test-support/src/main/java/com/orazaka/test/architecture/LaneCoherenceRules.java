package com.orazaka.test.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lane coherence [LANE-001]: a capability's declared {@code latency_class} must match the lane its
 * {@code routing_key} actually feeds (ADR-067).
 *
 * <p>Without this the column is documentation. That is exactly what {@code billable_unit} was — a
 * column four capabilities left NULL while their executors measured nothing, for as long as nothing
 * read it — and M2 closed that by making the seed assert its unit is priced. A lane declaration
 * nobody checks is the same defect one phase later: a capability could say INTERACTIVE and be
 * published to the queue where a 67-second image is in flight, and the only symptom would be a user
 * waiting.
 *
 * <p><b>Both directions are violations, and they fail differently.</b> A BATCH capability on the
 * interactive queue blocks work someone is waiting through; an INTERACTIVE one on the batch queue
 * is merely slow for its own user — the first is the reason lanes exist, the second is a promise
 * broken.
 *
 * <p>A routing key that <b>no lane binds</b> is not a violation here: it belongs to a worker with
 * its own queue — the Python media worker drains {@code job.video.*} and {@code job.compose.*}, a
 * Tier-W pack drains its own family — and whether anything drains it at all is [EXEC-002]'s
 * question, asked against every {@code worker.yaml} in the repository. What this rule owns is the
 * capabilities the job service's two lanes carry.
 */
public final class LaneCoherenceRules {

  private static final String JOBS_CONTEXT = "30-jobs-config.sql";

  /**
   * A seeded capability row, read for the three columns this rule compares: the key, the routing
   * key and the latency class. Line-anchored like {@code BlueprintFitnessTest}'s reader and for the
   * same reason — a DOTALL pattern silently skips the last row of an INSERT.
   */
  private static final Pattern CAPABILITY_ROW =
      Pattern.compile(
          "^\\('(?<key>orazaka\\.[a-z0-9.]+)',[^\\n]*?'(?<routing>job\\.[a-z0-9.*#]+)',"
              + "[^\\n]*?'(?<class>INTERACTIVE|BATCH)'",
          Pattern.MULTILINE);

  private LaneCoherenceRules() {}

  /**
   * [LANE-001] Every seeded capability whose routing key a lane binds declares that lane's class.
   *
   * @param repositoryRoot the repository root
   * @param lanes the binding patterns of each lane, by the class that lane serves — {@code
   *     INTERACTIVE} to {@code ["job.text.*", "job.media.analyze"]}, and so on
   */
  public static void assertEveryCapabilityDeclaresItsLane(
      Path repositoryRoot, Map<String, List<String>> lanes) {
    Workspace.require(repositoryRoot, "LANE-001");
    Map<String, String[]> rows =
        GovernanceSubjects.require(
            "LANE-001", "seeded capabilities with a latency class", capabilities(repositoryRoot));
    if (lanes.isEmpty()) {
      fail("[LANE-001] no lane was declared — the rule would judge the empty set");
    }

    List<String> violations = new ArrayList<>();
    Set<String> covered = new LinkedHashSet<>();
    rows.forEach(
        (featureKey, row) -> {
          String routingKey = row[0];
          String declared = row[1];
          String lane = laneOf(routingKey, lanes);
          if (lane == null) {
            // A worker's own queue. [EXEC-002] asks whether anything drains it.
            return;
          }
          covered.add(featureKey);
          if (!lane.equals(declared)) {
            violations.add(
                featureKey
                    + " declares "
                    + declared
                    + " and its routing key "
                    + routingKey
                    + " feeds the "
                    + lane
                    + " lane");
          }
        });

    if (covered.isEmpty()) {
      fail("[LANE-001] no capability matched a lane binding — the rule is blind");
    }
    if (!violations.isEmpty()) {
      fail(
          "[LANE-001] a declared lane that contradicts the queue its key feeds is a column nobody"
              + " reads:\n  "
              + String.join("\n  ", violations));
    }
  }

  /** The lane whose bindings match this routing key, or {@code null} when none does. */
  private static String laneOf(String routingKey, Map<String, List<String>> lanes) {
    for (Map.Entry<String, List<String>> lane : lanes.entrySet()) {
      for (String binding : lane.getValue()) {
        if (matches(binding, routingKey)) {
          return lane.getKey();
        }
      }
    }
    return null;
  }

  /** AMQP topic matching, for the two wildcards a binding may carry. */
  private static boolean matches(String binding, String routingKey) {
    String regex =
        binding
            .replace(".", "\\.")
            .replace("*", "[^.]+")
            .replace("#", ".+")
            .replace("\\.[^.]+", "\\.[^.]+");
    return routingKey.matches(regex);
  }

  /** Seeded capability rows: key → {routing key, latency class}. */
  private static Map<String, String[]> capabilities(Path repositoryRoot) {
    String sql =
        SqlBoundaryRules.readContext(
            repositoryRoot.resolve("infra").resolve("initdb"), JOBS_CONTEXT);
    int insert = sql.indexOf("INSERT INTO orazaka_capabilities");
    if (insert < 0) {
      throw new AssertionError("no orazaka_capabilities seed found in " + JOBS_CONTEXT);
    }
    Map<String, String[]> byKey = new LinkedHashMap<>();
    Matcher matcher = CAPABILITY_ROW.matcher(sql.substring(insert));
    while (matcher.find()) {
      byKey.put(
          matcher.group("key"), new String[] {matcher.group("routing"), matcher.group("class")});
    }
    return byKey;
  }
}
