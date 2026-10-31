package com.krizaka.orazaka.test.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** [layout standard] Nothing at a package root but the Spring Boot entry point. */
class PackageRootRulesTest {

  private static final String FIXTURES = "com.krizaka.orazaka.test.architecture.layoutfixture";

  @Test
  @DisplayName("the entry point alone at the package root passes")
  void entryPointAlonePasses() {
    JavaClasses classes = GovernanceRules.importAllClasses(FIXTURES + ".clean");

    assertThatCode(() -> GovernanceRules.assertNoClassAtPackageRoot(classes, FIXTURES + ".clean"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a class at the package root that is not the entry point fails")
  void strayClassFails() {
    JavaClasses classes = GovernanceRules.importAllClasses(FIXTURES + ".stray");

    assertThatThrownBy(
            () -> GovernanceRules.assertNoClassAtPackageRoot(classes, FIXTURES + ".stray"))
        .hasMessageContaining("StrayHelper");
  }

  @Test
  @DisplayName("a module with nothing at its package root passes: there is nothing to judge")
  void emptyRootPasses() {
    JavaClasses classes = GovernanceRules.importAllClasses(FIXTURES + ".clean");

    assertThatCode(() -> GovernanceRules.assertNoClassAtPackageRoot(classes, FIXTURES + ".absent"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("[GOV-006] the rules of this library are themselves non-vacuous")
  void theLibraryRulesAreNonVacuous() {
    assertThatCode(GovernanceSubjects::assertEveryRuleIsNonVacuous).doesNotThrowAnyException();
  }
}
