package com.krizaka.sovkora.test.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A rule matches module folders inside the workspace, never the checkout's own folder: a CI runner
 * checks a repository out under a folder of its name, so the absolute path of every file of the
 * workspace said {@code /sovkora-studio-service/} in that repository's CI (SAGA-001 flagged the
 * rules themselves).
 */
class InWorkspacePathTest {

  private static final Path ROOT =
      Path.of("/work/sovkora-studio-service/sovkora-studio-service/workspace");

  @Test
  @DisplayName("the checkout's folder is not part of the path a rule reads")
  void theCheckoutFolderIsNotRead() {
    Path rules = ROOT.resolve("libs/build/sovkora-test-support/src/main/java/GovernanceRules.java");

    assertThat(GovernanceRules.inWorkspace(ROOT, rules))
        .isEqualTo("/libs/build/sovkora-test-support/src/main/java/GovernanceRules.java")
        .doesNotContain("/sovkora-studio-service/");
  }

  @Test
  @DisplayName("a module folder inside the workspace still matches")
  void aModuleFolderStillMatches() {
    Path settlement =
        ROOT.resolve("apps/studio/sovkora-studio-service/src/main/java/RunSettlementService.java");

    assertThat(GovernanceRules.inWorkspace(ROOT, settlement)).contains("/sovkora-studio-service/");
  }
}
