package com.orazaka.test.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one guard every scanning governance rule passes its subjects through [GOV-006].
 *
 * <p><b>Inert in a third way.</b> A control can be declared and bound to nothing, bound and invoked
 * by nothing — or invoked, and handed the empty set. The third is the dangerous one because it
 * reports green: [PACK-001] checked zero packs for several phases, in the layer meant to catch the
 * other two. So a rule that scans a population must hand that population to {@link #require} before
 * judging it, and an empty population fails the rule, naming the rule and what it found none of.
 *
 * <p><b>The subjects are what the rule judges.</b> A rule about SecurityConfigs judges
 * SecurityConfigs; one about mappers judges mappers; [PACK-001]'s seed half judged seeded pack
 * rows. If there are none of them where it is invoked, it judged nothing, whatever else it walked
 * past. The first draft of this guard drew the line elsewhere — "every Mapper is final" over a
 * module with no mappers "examined every class" — and applying it showed that line would have kept
 * exactly [PACK-001]'s shape green. A rule is invoked where its subjects exist.
 *
 * <p>Made structural rather than remembered by {@link #assertEveryRuleIsNonVacuous}: a public
 * {@code assert*} that neither evaluates an ArchUnit rule (whose own {@code failOnEmptyShould}
 * applies, and which no rule may switch off) nor passes through {@link #require} fails the build —
 * so the seventh rule cannot forget what the first six were told.
 */
public final class GovernanceSubjects {

  private GovernanceSubjects() {}

  /**
   * Fails the calling rule when the population it is about to judge is empty.
   *
   * @param rule the rule's identifier, e.g. {@code PACK-001}
   * @param population what the rule scanned, in words, e.g. {@code "pack.yaml manifests"}
   * @param subjects the population itself
   * @param <T> the collection type, returned unchanged
   * @return {@code subjects}, so the call can wrap the scan
   */
  public static <T extends Collection<?>> T require(String rule, String population, T subjects) {
    if (subjects == null || subjects.isEmpty()) {
      fail(emptyMessage(rule, population));
    }
    return subjects;
  }

  /**
   * The same guard for a population held as a map.
   *
   * @param rule the rule's identifier
   * @param population what the rule scanned, in words
   * @param subjects the population
   * @param <T> the map type, returned unchanged
   * @return {@code subjects}
   */
  public static <T extends Map<?, ?>> T require(String rule, String population, T subjects) {
    if (subjects == null || subjects.isEmpty()) {
      fail(emptyMessage(rule, population));
    }
    return subjects;
  }

  /**
   * [GOV-006] Asserts every governance rule in this package is non-vacuous by construction.
   *
   * <p>Each public {@code assert*} must reach — directly or through the helpers it calls — either
   * an ArchUnit rule's {@code check}/{@code evaluate}, whose {@code failOnEmptyShould} refuses an
   * empty selection, or {@link #require}. And nothing may call {@code allowEmptyShould}, which is
   * how that ArchUnit guard was switched off on thirteen rules before this existed. Its population
   * is the rule entry points of {@code com.orazaka.test.architecture}.
   */
  public static void assertEveryRuleIsNonVacuous() {
    JavaClasses rules = new ClassFileImporter().importPackages(RULES_PACKAGE);
    List<JavaMethod> entryPoints =
        require(
            "GOV-006",
            "public assert* rule entry points in " + RULES_PACKAGE,
            rules.stream()
                .flatMap(type -> type.getMethods().stream())
                .filter(method -> method.getName().startsWith("assert"))
                .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                .filter(method -> method.getModifiers().contains(JavaModifier.STATIC))
                .toList());

    List<String> violations = new ArrayList<>();
    for (JavaMethod entryPoint : entryPoints) {
      if (!reachesAGuard(entryPoint, new HashSet<>())) {
        violations.add(
            entryPoint.getOwner().getSimpleName()
                + "."
                + entryPoint.getName()
                + " judges a population it never proves non-empty: pass it through"
                + " GovernanceSubjects.require, or express the rule as an ArchUnit rule");
      }
    }
    for (JavaClass type : rules) {
      for (JavaCodeUnit unit : type.getCodeUnits()) {
        for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
          if (call.getName().equals("allowEmptyShould")) {
            violations.add(
                type.getSimpleName()
                    + "."
                    + unit.getName()
                    + " calls allowEmptyShould, which lets an ArchUnit rule pass having selected"
                    + " nothing — the guard this rule exists to keep on");
          }
        }
      }
    }
    if (!violations.isEmpty()) {
      fail(
          "[GOV-006] governance rules that can pass over nothing:\n  "
              + String.join("\n  ", violations));
    }
  }

  private static final String RULES_PACKAGE = "com.orazaka.test.architecture";

  private static boolean reachesAGuard(JavaCodeUnit unit, Set<String> visited) {
    if (!visited.add(unit.getFullName())) {
      return false;
    }
    for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
      JavaClass owner = call.getTargetOwner();
      String name = call.getName();
      if (owner.isAssignableTo(ArchRule.class)
          && (name.equals("check") || name.equals("evaluate"))) {
        return true;
      }
      if (owner.isEquivalentTo(GovernanceSubjects.class) && name.equals("require")) {
        return true;
      }
      if (owner.getPackageName().equals(RULES_PACKAGE)) {
        java.util.Optional<JavaMethod> target = call.getTarget().resolveMember();
        if (target.isPresent() && reachesAGuard(target.get(), visited)) {
          return true;
        }
      }
    }
    return false;
  }

  private static String emptyMessage(String rule, String population) {
    return "["
        + rule
        + "] examined no "
        + population
        + " — a rule that judges the empty set reports green and is believed. Either the scan is"
        + " pointed at the wrong place, or what it guards has moved (GOV-006).";
  }
}
