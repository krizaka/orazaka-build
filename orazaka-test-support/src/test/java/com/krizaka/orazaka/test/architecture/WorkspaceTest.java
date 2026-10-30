package com.krizaka.orazaka.test.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

  @Test
  @DisplayName(
      "a git worktree (.git is a FILE) is the root of its repository, not the package.json above it")
  void worktreeRootStopsAtTheGitFile(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("package.json"), "{}");
    Path repo = Files.createDirectories(tmp.resolve("worktree"));
    Files.writeString(repo.resolve(".git"), "gitdir: /elsewhere");
    Path module = Files.createDirectories(repo.resolve("module"));
    Files.writeString(module.resolve("pom.xml"), "<project/>");

    assertThat(Workspace.rootOrRepository(module)).isEqualTo(repo);
  }

  @Test
  @DisplayName("a classic clone (.git is a directory) is still its own root")
  void cloneRootStopsAtTheGitDirectory(@TempDir Path tmp) throws IOException {
    Path repo = Files.createDirectories(tmp.resolve("clone"));
    Files.createDirectories(repo.resolve(".git"));
    Path module = Files.createDirectories(repo.resolve("module"));

    assertThat(Workspace.rootOrRepository(module)).isEqualTo(repo);
  }

  @Test
  @DisplayName("without any .git the climb stops below the user home: a test never reads the home")
  void neverClimbsIntoTheUserHome(@TempDir Path tmp) throws IOException {
    Path home = Files.createDirectories(tmp.resolve("home"));
    Files.writeString(home.resolve("package.json"), "{}");
    Path project = Files.createDirectories(home.resolve("project"));
    Files.writeString(project.resolve("pom.xml"), "<project/>");
    String previous = System.getProperty("user.home");
    System.setProperty("user.home", home.toString());
    try {
      assertThat(Workspace.rootOrRepository(project)).isEqualTo(project);
    } finally {
      System.setProperty("user.home", previous);
    }
  }
}
