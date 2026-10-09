package com.orazaka.test.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;

/**
 * Configuration-binding fitness function [CFG-001]: no type the configuration binder builds may
 * have a constructor set the binder cannot choose from.
 *
 * <p><b>The defect, twice.</b> {@code SecurityProperties} (ADR-055) and {@code
 * CoreProperties.OrchestrationConfig} (ADR-062) were both records with a second, convenience
 * constructor and no {@code @ConstructorBinding}. Given two constructors the binder builds no
 * instance, every value under the prefix is dropped, and the field default — the permissive one,
 * both times — wins. {@code disable-ai} could not be switched on; the orchestration switch could
 * not be switched off. Both logged their state truthfully, which is why neither was noticed.
 *
 * <p><b>The anchor, derived from the two types rather than from a description of them.</b> What is
 * true of both, visible to a scanner, and not true of the types this rule must leave alone:
 *
 * <ul>
 *   <li><i>Not</i> {@code @ConfigurationProperties}. Only the first carried it; the second is a
 *       component of a record bound by hand with {@code Binder.bind("orazaka.core",
 *       CoreProperties.class)}. The first sweep keyed on the annotation and missed it.
 *   <li><i>Not</i> "a record with a second constructor". Seventeen records in this repository have
 *       one, and fourteen — {@code User}, {@code JobCommand}, {@code PromptContext}, request DTOs —
 *       are built by code and never touched by the binder.
 *   <li><b>Reachable from a binding root</b>, walking record components and their type arguments,
 *       where a root is an {@code @ConfigurationProperties} type or a class literal handed to
 *       {@code Binder.bind}; <b>and</b> more than one constructor; <b>and</b> none of them
 *       annotated {@code @ConstructorBinding}. That is exactly the two, and not the fourteen.
 * </ul>
 *
 * <p>Read from bytecode, not source: a constructor set, an annotation on a constructor, a field's
 * generic type and a class literal beside a {@code bind} call are structure, and the regular
 * expressions that would reconstruct them from text are the fragile anchor this rule exists to
 * replace. ArchUnit is the scanner this repository already uses for structure.
 *
 * <p><b>The same defect has a second injector</b> (ADR-069). {@code PackBundleResolver} was a
 * {@code @Component} with two constructors and no {@code @Autowired}: the container cannot choose
 * either, so it looked for a no-arg constructor, found none, and <b>the studio service did not
 * start at all</b> — every {@code /api/v1/studios/**} request answered 502 for a whole milestone,
 * because no unit test builds a bean through the container and no integration test boots that
 * service's context. {@link #assertInjectableComponentsHaveOneConstructor()} is the container half
 * of what {@link #assertConfigurationBindsUnambiguously()} does for the binder.
 */
public final class ConfigBindingRules {

  /** Orazaka binds its own types and the Krizaka artifacts it is built on. */
  private static final String[] OWN_CODE = {"com.orazaka", "com.krizaka"};

  private ConfigBindingRules() {}

  /**
   * [CFG-001] over the Orazaka and Krizaka classes on this module's classpath —
   * krizaka-test-support's {@link
   * com.krizaka.test.architecture.ConfigBindingRules#assertConfigurationBindsUnambiguously}.
   */
  public static void assertConfigurationBindsUnambiguously() {
    com.krizaka.test.architecture.ConfigBindingRules.assertConfigurationBindsUnambiguously(
        OWN_CODE);
  }

  /**
   * [CFG-001] over the given classes.
   *
   * @param classes the classes to search for configuration roots
   */
  public static void assertConfigurationBindsUnambiguously(JavaClasses classes) {
    com.krizaka.test.architecture.ConfigBindingRules.assertConfigurationBindsUnambiguously(
        classes, OWN_CODE);
  }

  /**
   * [CFG-001] every Spring bean on this module's classpath has a constructor it can be built with.
   */
  public static void assertInjectableComponentsHaveOneConstructor() {
    com.krizaka.test.architecture.ConfigBindingRules.assertInjectableComponentsHaveOneConstructor(
        OWN_CODE);
  }

  /**
   * [CFG-001] the bean-constructor rule over the given classes.
   *
   * @param classes production classes to judge
   */
  public static void assertInjectableComponentsHaveOneConstructor(JavaClasses classes) {
    com.krizaka.test.architecture.ConfigBindingRules.assertInjectableComponentsHaveOneConstructor(
        classes, OWN_CODE);
  }
}
