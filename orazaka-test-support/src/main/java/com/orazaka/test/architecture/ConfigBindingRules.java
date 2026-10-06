package com.orazaka.test.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaWildcardType;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  private static final String CONFIGURATION_PROPERTIES =
      "org.springframework.boot.context.properties.ConfigurationProperties";
  private static final String CONSTRUCTOR_BINDING =
      "org.springframework.boot.context.properties.bind.ConstructorBinding";
  private static final String BINDER = "org.springframework.boot.context.properties.bind.Binder";
  private static final String OWN_CODE = "com.orazaka";
  private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";

  /** The stereotypes whose classes the container instantiates itself. */
  private static final java.util.Set<String> SPRING_BEAN_ANNOTATIONS =
      java.util.Set.of(
          "org.springframework.stereotype.Component",
          "org.springframework.stereotype.Service",
          "org.springframework.stereotype.Repository",
          "org.springframework.stereotype.Controller",
          "org.springframework.web.bind.annotation.RestController",
          "org.springframework.context.annotation.Configuration");

  private ConfigBindingRules() {}

  /**
   * Asserts [CFG-001] over every production class on this module's runtime classpath.
   *
   * <p>The classpath, not the module: binding happens in a running service, over the configuration
   * types of every library it pulls in — a library such as {@code orazaka-assets} has no suite of
   * its own, and its properties are bound in the services that depend on it.
   */
  public static void assertConfigurationBindsUnambiguously() {
    assertConfigurationBindsUnambiguously(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(OWN_CODE));
  }

  /**
   * Asserts [CFG-001] over the given classes: no type reachable from a configuration-binding root
   * has more than one constructor without one of them annotated {@code @ConstructorBinding}.
   *
   * <p>Its population is the set of types the binder builds — the roots and everything reachable
   * from them — and an empty one fails the rule (GOV-006).
   *
   * @param classes the classes to search for roots; components are followed wherever they resolve
   */
  public static void assertConfigurationBindsUnambiguously(JavaClasses classes) {
    Map<JavaClass, String> roots = roots(classes);
    Map<JavaClass, String> bound =
        GovernanceSubjects.require(
            "CFG-001",
            "types the configuration binder builds (no @ConfigurationProperties type and no"
                + " Binder.bind target was found)",
            reachable(roots));

    List<String> violations = new ArrayList<>();
    for (Map.Entry<JavaClass, String> entry : bound.entrySet()) {
      JavaClass type = entry.getKey();
      List<JavaConstructor> constructors = new ArrayList<>(type.getConstructors());
      boolean ambiguous =
          constructors.size() > 1
              && constructors.stream().noneMatch(c -> c.isAnnotatedWith(CONSTRUCTOR_BINDING));
      // A class with a no-argument constructor and setters is bound through its setters, so its
      // constructor set does not decide whether binding happens.
      if (ambiguous && !isJavaBean(type)) {
        violations.add(
            type.getName()
                + " is built by the configuration binder ("
                + entry.getValue()
                + ") and has "
                + constructors.size()
                + " constructors, none annotated @ConstructorBinding. The binder cannot choose,"
                + " builds no instance, and every value under its prefix is dropped for the field"
                + " default. Keep one constructor, or annotate the canonical one @ConstructorBinding.");
      }
    }
    if (!violations.isEmpty()) {
      fail(
          "[CFG-001] configuration types the binder cannot construct (ADR-055, ADR-062):\n  "
              + String.join("\n  ", violations));
    }
  }

  /** Every {@code @ConfigurationProperties} type, and every class literal passed to a bind call. */
  private static Map<JavaClass, String> roots(JavaClasses classes) {
    Map<JavaClass, String> roots = new LinkedHashMap<>();
    for (JavaClass type : classes) {
      if (type.isAnnotatedWith(CONFIGURATION_PROPERTIES)) {
        roots.putIfAbsent(type, "@ConfigurationProperties on " + type.getSimpleName());
      }
      for (JavaCodeUnit unit : type.getCodeUnits()) {
        boolean binds =
            unit.getMethodCallsFromSelf().stream()
                .anyMatch(
                    call ->
                        call.getTargetOwner().getName().equals(BINDER)
                            && call.getName().equals("bind"));
        if (!binds) {
          continue;
        }
        unit.getReferencedClassObjects().stream()
            .map(reference -> reference.getRawType())
            .filter(ConfigBindingRules::isOwnCode)
            .forEach(
                target ->
                    roots.putIfAbsent(
                        target,
                        "Binder.bind in "
                            + type.getSimpleName()
                            + "."
                            + unit.getName()
                            + "() binds "
                            + target.getSimpleName()));
      }
    }
    return roots;
  }

  /** The roots and every own type reachable through their fields' types and type arguments. */
  private static Map<JavaClass, String> reachable(Map<JavaClass, String> roots) {
    Map<JavaClass, String> seen = new LinkedHashMap<>();
    Deque<JavaClass> queue = new ArrayDeque<>();
    roots.forEach(
        (root, why) -> {
          if (seen.putIfAbsent(root, why) == null) {
            queue.add(root);
          }
        });
    while (!queue.isEmpty()) {
      JavaClass owner = queue.poll();
      for (JavaField field : owner.getFields()) {
        if (field.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.STATIC)) {
          continue;
        }
        for (JavaClass component : rawTypesIn(field.getType())) {
          // An enum is converted from its name, never constructed; an interface cannot be built.
          if (!isOwnCode(component) || component.isEnum() || component.isInterface()) {
            continue;
          }
          if (seen.putIfAbsent(component, seen.get(owner) + " → " + field.getName()) == null) {
            queue.add(component);
          }
        }
      }
    }
    return seen;
  }

  private static List<JavaClass> rawTypesIn(JavaType type) {
    List<JavaClass> raw = new ArrayList<>();
    Deque<JavaType> pending = new ArrayDeque<>();
    pending.add(type);
    while (!pending.isEmpty()) {
      JavaType current = pending.poll();
      raw.add(current.toErasure());
      if (current instanceof JavaParameterizedType parameterized) {
        pending.addAll(parameterized.getActualTypeArguments());
      } else if (current instanceof JavaWildcardType wildcard) {
        pending.addAll(wildcard.getUpperBounds());
      }
      JavaClass erasure = current.toErasure();
      if (erasure.isArray()) {
        pending.add(erasure.getComponentType());
      }
    }
    return raw;
  }

  private static boolean isJavaBean(JavaClass type) {
    boolean noArgs =
        type.getConstructors().stream().anyMatch(c -> c.getRawParameterTypes().isEmpty());
    boolean setters =
        type.getMethods().stream()
            .map(HasName::getName)
            .anyMatch(name -> name.startsWith("set") && name.length() > 3);
    return !type.isRecord() && noArgs && setters;
  }

  private static boolean isOwnCode(JavaClass type) {
    return type.getPackageName().startsWith(OWN_CODE);
  }

  /**
   * Asserts every Spring-instantiated component has a constructor set the container can choose
   * from.
   *
   * <p>The container's own rule, applied as written — and "as written" is load-bearing, because the
   * first version of this rule paraphrased it and cleared the bean that had just failed to start.
   * {@code AutowiredAnnotationBeanPostProcessor.determineCandidateConstructors} begins at {@code
   * beanClass.getDeclaredConstructors()} and takes its one-candidate shortcut only when {@code
   * rawCandidates.length == 1}. <b>{@code getDeclaredConstructors()} includes private ones.</b> So
   * a {@code private} constructor behind a static factory does <i>not</i> hide a second injection
   * point from the container: it is a second raw candidate, the shortcut does not apply, no
   * {@code @Autowired} marks a choice, and instantiation falls back to a no-arg constructor that
   * does not exist. That is exactly how {@code PackBundleResolver} was "fixed" and stayed broken —
   * the rule judged non-private constructors while the container counted all of them, so the rule
   * and the thing it protects were reading two different populations.
   *
   * <p>A <b>candidate</b> is therefore any declared constructor, whatever its modifier. A test seam
   * belongs behind a static factory <i>over the same single constructor</i>, or behind an explicit
   * {@code @Autowired} on the one the container must use.
   *
   * <p>Scoped to what the container builds: {@code @Component}, {@code @Service},
   * {@code @Repository}, {@code @Controller}, {@code @RestController} and {@code @Configuration}. A
   * record or a value object with four constructors is nobody's problem; a bean with two is a
   * service that does not start.
   */
  public static void assertInjectableComponentsHaveOneConstructor() {
    assertInjectableComponentsHaveOneConstructor(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(OWN_CODE));
  }

  /**
   * The rule, over classes the caller imported.
   *
   * @param classes production classes to judge
   */
  public static void assertInjectableComponentsHaveOneConstructor(JavaClasses classes) {
    List<String> violations = new ArrayList<>();
    List<String> judged = new ArrayList<>();
    for (JavaClass type : classes) {
      if (!isOwnCode(type) || type.isInterface() || type.isEnum() || !isSpringBean(type)) {
        continue;
      }
      judged.add(type.getSimpleName());
      // Every DECLARED constructor, private included — see the class rule's javadoc. Filtering
      // private ones out is what let a two-constructor bean pass while the container refused it.
      List<JavaConstructor> candidates = List.copyOf(type.getConstructors());
      if (candidates.size() < 2) {
        continue;
      }
      boolean chosen =
          candidates.stream()
              .anyMatch(
                  constructor ->
                      constructor.getAnnotations().stream()
                          .anyMatch(a -> AUTOWIRED.equals(a.getRawType().getName())));
      boolean noArg = candidates.stream().anyMatch(c -> c.getRawParameterTypes().isEmpty());
      if (!chosen && !noArg) {
        violations.add(
            type.getSimpleName()
                + " is a Spring bean with "
                + candidates.size()
                + " constructors the container cannot choose from"
                + " (none is @Autowired and none is no-arg)");
      }
    }
    GovernanceSubjects.require("CFG-001", "Spring beans on this module's classpath", judged);
    if (!violations.isEmpty()) {
      fail(
          "[CFG-001] a bean the container cannot instantiate is a service that does not start,"
              + " and no test that builds it by hand will ever say so:\n  "
              + String.join("\n  ", violations));
    }
  }

  /** Whether the container instantiates this type. */
  private static boolean isSpringBean(JavaClass type) {
    return type.getAnnotations().stream()
        .map(a -> a.getRawType().getName())
        .anyMatch(SPRING_BEAN_ANNOTATIONS::contains);
  }
}
