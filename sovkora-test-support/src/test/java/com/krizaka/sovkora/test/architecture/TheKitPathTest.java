package com.krizaka.sovkora.test.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The KIT rules let the kit's own sources implement what they forbid everywhere else. The kit
 * folder was krizaka-platform-kit and is krizaka-service-kit since krizaka-java 0.12.0: the rule
 * must know both, or every service's governance suite flags the kit itself (sovkora-job-service CI,
 * KIT-002 to KIT-004).
 */
class TheKitPathTest {

  @Test
  @DisplayName("the service kit and its former name are the kit")
  void bothKitFoldersAreTheKit() {
    assertThat(
            GovernanceRules.isTheKit(
                Path.of("/w/krizaka/krizaka-service-kit/krizaka-messaging/A.java")))
        .isTrue();
    assertThat(
            GovernanceRules.isTheKit(
                Path.of("/w/krizaka/krizaka-platform-kit/krizaka-messaging/A.java")))
        .isTrue();
  }

  @Test
  @DisplayName("a service source is not the kit")
  void aServiceIsNotTheKit() {
    assertThat(GovernanceRules.isTheKit(Path.of("/w/apps/services/sovkora-job-service/A.java")))
        .isFalse();
  }
}
