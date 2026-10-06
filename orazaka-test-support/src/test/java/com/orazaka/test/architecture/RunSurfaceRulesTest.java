package com.orazaka.test.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The derivation behind [DOOR-001], asserted on the repository it derives from.
 *
 * <p>Seven governance suites exercise the rule's <i>verdict</i>. Nothing exercised the four
 * decisions its sink derivation makes — and every one of them was wrong at some point while this
 * rule was being written, each caught only because the next thing failed:
 *
 * <ol>
 *   <li>a verb list ({@code convertAndSend}, {@code OutboxMessage}) missed the studio adapter's
 *       {@code appendCommand} — the transcription defect, inside the rule meant to avoid it;
 *   <li>the same scan matched {@code convertAndSend} in a <b>comment</b> explaining what had been
 *       replaced, the Python-docstring failure of M4 one language over;
 *   <li>the rules' own sources name the exchange in prose and counted themselves as dispatchers;
 *   <li>the run's own seam was classified as a sink, so the rule's first verdict was that {@code
 *       StudioRunController.start} — the correct path — was door 1.
 * </ol>
 *
 * <p>Each assertion below is one of those, stated as a property rather than as the list of classes
 * that happen to satisfy it today.
 */
class RunSurfaceRulesTest {

  /** Derived in {@code @BeforeAll}: outside the workspace the derivation skips the class. */
  private static Set<String> DISPATCHERS;

  @BeforeAll
  static void derive() {
    DISPATCHERS = RunSurfaceRules.dispatcherClasses();
  }

  @Test
  @DisplayName("the repository has dispatchers, or the derivation reads nothing")
  void theDerivationFindsDispatchers() {
    assertThat(DISPATCHERS)
        .as(
            "classes that hand the jobs exchange to something — an empty set means the scan is"
                + " pointed at the wrong tree and every [DOOR-001] verdict is vacuous")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a class that only declares the exchange is not a dispatcher")
  void declaringTheNameIsNotDispatching() {
    assertThat(DISPATCHERS)
        .as(
            "an AmqpConstants holds the name and sends nothing; counting it would make every"
                + " service that imports its own constants a dispatcher")
        .noneMatch(name -> name.endsWith("AmqpConstants") || name.endsWith("MessagingContract"));
  }

  @Test
  @DisplayName("the topology declarations are not dispatchers")
  void declaringTheTopologyIsNotDispatching() {
    assertThat(DISPATCHERS)
        .as("a @Configuration builds the exchange; it never puts a command on it")
        .noneMatch(name -> name.endsWith("AmqpConfiguration"));
  }

  @Test
  @DisplayName("the rules themselves are not dispatchers")
  void theRulesAreNotDispatchers() {
    assertThat(DISPATCHERS)
        .as(
            "this file and MeteringMarkerRules name the exchange and the verbs in prose, to say"
                + " what they check — a rule that counted itself would be true and useless")
        .noneMatch(name -> name.startsWith("com.orazaka.test.architecture."));
  }

  @Test
  @DisplayName("the run's own dispatch seam is named, so it can be excluded rather than flagged")
  void theRunSeamIsDeclared() {
    assertThat(RunSurfaceRules.RUN_DISPATCH_SEAM)
        .as(
            "without it the rule's verdict is that starting a run is door 1, which is the whole"
                + " property inverted")
        .endsWith("StepExecutionClient");
  }
}
