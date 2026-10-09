package com.krizaka.orazaka.test.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * A service's real {@code application.yml}, loaded into a {@link StandardEnvironment} the way the
 * service itself loads it — for tests that bind configuration.
 *
 * <p><b>Why a binding test must start here.</b> Every inert switch this project found (ADR-055's
 * {@code disable-ai}, ADR-062's orchestration switch) passed a unit test over a hand-built
 * properties object. The defect was never in the object; it was in whether the binder could build
 * one from the yaml at all. A test that constructs the record by hand skips exactly the step that
 * was broken.
 *
 * <p>Overrides are added as the <b>highest-precedence</b> source, so an entry named after an
 * environment variable the yaml references ({@code ORCHESTRATION_ENABLED}) resolves its placeholder
 * the way the real variable would — which tests the yaml's wiring and not only the binder.
 *
 * <p>Needs SnakeYAML at runtime, which every service's test classpath carries.
 */
public final class ApplicationYaml {

  private ApplicationYaml() {}

  /**
   * Loads the {@code application.yml} of the service whose tests are running.
   *
   * @param overrides entries that win over the yaml, e.g. environment variables it references
   * @return an environment holding the real yaml beneath the overrides
   */
  public static StandardEnvironment ofThisService(Map<String, Object> overrides) {
    return of(Path.of("src", "main", "resources", "application.yml"), overrides);
  }

  /**
   * Loads one yaml file beneath the given overrides.
   *
   * @param yaml the file
   * @param overrides entries that win over it
   * @return the environment
   */
  public static StandardEnvironment of(Path yaml, Map<String, Object> overrides) {
    if (!Files.isRegularFile(yaml)) {
      throw new IllegalStateException(
          "no application.yml at "
              + yaml.toAbsolutePath()
              + " — a binding test must read the real one");
    }
    StandardEnvironment environment = new StandardEnvironment();
    try {
      new YamlPropertySourceLoader()
          .load(yaml.toString(), new FileSystemResource(yaml))
          .forEach(environment.getPropertySources()::addLast);
    } catch (IOException unreadable) {
      throw new UncheckedIOException("cannot read " + yaml, unreadable);
    }
    environment.getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
    return environment;
  }
}
