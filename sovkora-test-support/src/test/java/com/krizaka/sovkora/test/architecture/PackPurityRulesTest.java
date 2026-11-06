package com.krizaka.sovkora.test.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** [PACK-002] The engine namespaces: a declared engine namespace passes, a pack key still fails. */
class PackPurityRulesTest {

  @TempDir Path root;

  @Test
  @DisplayName("the law watcher's settings namespace is an engine namespace (ADR-089)")
  void lawNamespaceIsEngineOwned() throws IOException {
    write(
        "orazaka-libs/engine/src/main/java/Watch.java",
        """
        @ConfigurationProperties(prefix = "orazaka.law.watcher")
        public record Watch(boolean enabled) {}
        """);

    assertThatCode(() -> PackPurityRules.assertNoPackKeyLiterals(root)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the governance schema's migration switch is an engine namespace (Flyway V1)")
  void governanceNamespaceIsEngineOwned() throws IOException {
    write(
        "orazaka-libs/engine/src/main/java/GovernanceMigrations.java",
        """
        public final class GovernanceMigrations {
          public static final String ENABLED_PROPERTY = "orazaka.governance.migrations.enabled";
        }
        """);

    assertThatCode(() -> PackPurityRules.assertNoPackKeyLiterals(root)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a pack key in engine code is still flagged")
  void packKeyStillFlagged() throws IOException {
    write(
        "orazaka-libs/engine/src/main/java/Pack.java",
        """
        public final class Pack {
          private static final String KEY = "orazaka.acme.compliance";
        }
        """);

    assertThatThrownBy(() -> PackPurityRules.assertNoPackKeyLiterals(root))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("orazaka.acme.compliance");
  }

  private void write(String relative, String content) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
