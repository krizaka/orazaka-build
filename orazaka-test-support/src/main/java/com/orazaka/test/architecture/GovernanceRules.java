package com.orazaka.test.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared ArchUnit rule library for governance enforcement across all Orazaka modules.
 *
 * <p>Contains exclusively ArchUnit-based rules operating on {@link JavaClasses}. For file-system
 * source scanning utilities, see {@link SourceFileScanner}.
 */
public final class GovernanceRules {

  private static final String MAPPER_SUFFIX = "Mapper";
  private static final String DOMAIN_LAYER = "Domain";
  private static final String APPLICATION_LAYER = "Application";
  private static final String APPLICATION_PKG_PATTERN = ".application..";

  private GovernanceRules() {}

  // ─── Class Import Helpers ───

  /** Imports production classes for the given base package (excludes test sources). */
  public static JavaClasses importProductionClasses(String basePackage) {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(basePackage);
  }

  /** Imports all classes (production + test) for the given base package. */
  public static JavaClasses importAllClasses(String basePackage) {
    return new ClassFileImporter().importPackages(basePackage);
  }

  // ─── ERR-102: Module Boundary Enforcement ───

  /** Asserts that classes in {@code modulePackage} do not depend on {@code forbiddenPackage}. */
  public static void assertNoDependencyOn(
      JavaClasses classes, String modulePackage, String forbiddenPackage, String reason) {
    noClasses()
        .that()
        .resideInAPackage(modulePackage + "..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(forbiddenPackage + "..")
        .because(reason + " [ERR-102]")
        .check(classes);
  }

  /**
   * Tier-3 (owned-domain) implementation packs, per the AGENTS.md §2 sharing tiers. These may be
   * used only by their owning bounded context — never across a context boundary. The identity
   * <em>contract</em> ({@code com.orazaka.identity.domain.model}/{@code .exception}, i.e.
   * orazaka-identity-api) is deliberately absent: it is Tier-1 and shared freely.
   *
   * <p>Persistence is listed as its two <b>implementation</b> packs rather than as the whole {@code
   * com.orazaka.persistence..} prefix, for the same reason identity is. That context's Tier-1
   * contract — {@code orazaka-libs/orazaka-contracts/orazaka-persistence-app-api} — publishes under
   * {@code com.orazaka.persistence.domain..}, sharing a prefix with the Tier-3 module beside it, so
   * a blanket ban on the prefix forbids exactly the contract the tiers exist to encourage. The
   * blanket form went unnoticed while the rule was wired only into services that touch no
   * persistence at all; wiring it into the job service, which reaches the app-persistence context
   * through its ports, produced 48 hits of which every single one was a Tier-1 contract type. The
   * two packs below exist only in {@code orazaka-libs/orazaka-ai-engine/orazaka-persistence-app},
   * so the ban still lands on the implementation and no longer on the contract.
   */
  private static final String[] TIER3_IMPL_PACKAGES = {
    "com.orazaka.business..",
    "com.orazaka.persistence.application..",
    "com.orazaka.persistence.infrastructure..",
    "com.orazaka.identity.application..",
    "com.orazaka.identity.infrastructure..",
    "com.orazaka.identity.domain.ports.."
  };

  /**
   * [SEAM-002] Asserts an autonomous service depends on no bounded context's Tier-3 (owned-domain)
   * implementation. Such a service owns no shared Tier-3, so it collaborates across contexts only
   * through Tier-1 contracts ({@code orazaka-libs/orazaka-contracts/*-api}); Tier-2 platform SDK
   * ({@code core}/{@code interceptors}/{@code tools}/{@code test-support}) and its own code stay
   * allowed. A cross-context Tier-3 dependency is a distributed monolith forming.
   */
  public static void assertNoForeignTier3Dependency(JavaClasses classes, String servicePackage) {
    noClasses()
        .that()
        .resideInAPackage(servicePackage + "..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(TIER3_IMPL_PACKAGES)
        .because(
            "[SEAM-002] a service uses Tier-1 contracts + Tier-2 SDK + its own Tier-3, never another"
                + " context's Tier-3 impl (business/persistence/identity-impl)")
        .check(classes);
  }

  // ─── ERR-103: One Top-Level Class Per File ───

  /** Asserts every top-level class resides in a dedicated file matching its simple name. */
  public static void assertOneTopLevelClassPerFile(JavaClasses classes, String modulePackage) {
    classes()
        .that()
        .resideInAPackage(modulePackage + "..")
        .and()
        .doNotHaveModifier(JavaModifier.SYNTHETIC)
        .should(
            new ArchCondition<com.tngtech.archunit.core.domain.JavaClass>(
                "reside in a dedicated file matching their class name") {
              @Override
              public void check(
                  com.tngtech.archunit.core.domain.JavaClass javaClass, ConditionEvents events) {
                if (javaClass.isAnonymousClass() || javaClass.isMemberClass()) {
                  return;
                }
                String sourceFileName =
                    javaClass.getSource().flatMap(Source::getFileName).orElse(null);
                if (sourceFileName != null && !sourceFileName.equals("Unknown Source")) {
                  String expectedFileName = javaClass.getSimpleName() + ".java";
                  if (!sourceFileName.equals(expectedFileName)) {
                    events.add(
                        SimpleConditionEvent.violated(
                            javaClass,
                            String.format(
                                "Architecture Violation [ERR-103]: Class '%s' is illegally bundled"
                                    + " inside '%s'.",
                                javaClass.getFullName(), sourceFileName)));
                  }
                }
              }
            })
        .because("Every top-level class must reside in its own dedicated .java file [ERR-103]")
        .check(classes);
  }

  // ─── ERR-104: No Redundant Project Prefix ───

  /** Asserts no class names start with 'Orazaka' prefix. */
  public static void assertNoRedundantPrefix(JavaClasses classes, String modulePackage) {
    classes()
        .that()
        .resideInAPackage(modulePackage + "..")
        .should()
        .haveSimpleNameNotStartingWith("Orazaka")
        .because("Project name must not be prepended to class names [ERR-104]")
        .check(classes);
  }

  // ─── ERR-105: *Impl Package-Private Visibility ───

  /** Asserts *Impl classes in the given service package are not public. */
  public static void assertImplClassesPackagePrivate(JavaClasses classes, String servicePackage) {
    classes()
        .that()
        .resideInAPackage(servicePackage + "..")
        .and()
        .haveSimpleNameEndingWith("Impl")
        .should()
        .notBePublic()
        .because(
            "Implementation classes (*Impl) must be package-private."
                + " Cross-module interaction through interfaces only [ERR-105]")
        .check(classes);
  }

  // ─── ERR-107: Mapper Visibility ───

  /** Asserts *Mapper classes in the given package are final. */
  public static void assertMappersFinal(JavaClasses classes, String modulePackage) {
    classes()
        .that()
        .resideInAPackage(modulePackage + "..")
        .and()
        .haveSimpleNameEndingWith(MAPPER_SUFFIX)
        .should()
        .haveModifier(JavaModifier.FINAL)
        .because("Mapper classes must be final static utility classes [ERR-107]")
        .check(classes);
  }

  /** Asserts *Mapper classes in the given package are not public. */
  public static void assertMappersPackagePrivate(JavaClasses classes, String servicePackage) {
    classes()
        .that()
        .resideInAPackage(servicePackage + "..")
        .and()
        .haveSimpleNameEndingWith(MAPPER_SUFFIX)
        .should()
        .notBePublic()
        .because("Mapper utilities are internal package-private details [ERR-107]")
        .check(classes);
  }

  // ─── ERR-109: Persistence Package Hygiene ───

  /** Asserts JPA components reside in correct sub-packs. */
  public static void assertPersistencePackageHygiene(JavaClasses classes) {
    classes()
        .that()
        .implement(jakarta.persistence.AttributeConverter.class)
        .should()
        .resideInAPackage("..infrastructure.adapter.persistence.converter..")
        .because("JPA AttributeConverters must live in .converter package [ERR-109]")
        .check(classes);

    classes()
        .that()
        .areAnnotatedWith(jakarta.persistence.Entity.class)
        .should()
        .resideInAPackage("..infrastructure.adapter.persistence.entity..")
        .because("JPA Entity classes must live in .entity package [ERR-109]")
        .check(classes);

    classes()
        .that()
        .areAssignableTo(org.springframework.data.repository.Repository.class)
        .should()
        .resideInAPackage("..infrastructure.adapter.persistence.repository..")
        .because("Spring Data Repositories must live in .repository package [ERR-109]")
        .check(classes);
  }

  // ─── ADR-007: Collection Field Immutability ───

  /** Asserts collection fields (List, Map, Set) in non-record classes are private final. */
  public static void assertCollectionFieldsPrivateFinal(JavaClasses classes) {
    fields()
        .that()
        .haveRawType(List.class)
        .or()
        .haveRawType(Map.class)
        .or()
        .haveRawType(Set.class)
        .and()
        .areDeclaredInClassesThat()
        .areNotRecords()
        .and()
        .areDeclaredInClassesThat()
        .areNotEnums()
        .and()
        .areDeclaredInClassesThat()
        .areNotInterfaces()
        .and()
        .areDeclaredInClassesThat()
        .haveSimpleNameNotContaining("Abstract")
        .and()
        .areDeclaredInClassesThat()
        .areNotAnnotatedWith(jakarta.persistence.Entity.class)
        .should(bePrivateAndFinal())
        .because(
            "Collection fields must be private final for immutability [ADR-007, ADR-008]."
                + " JPA @Entity collections are ORM-managed (Hibernate replaces them on load) and"
                + " are exempt")
        .check(classes);
  }

  // ─── ADR-009: Fields Must Be Private ───

  /** Asserts instance fields in concrete non-record classes are private. */
  public static void assertFieldsPrivate(JavaClasses classes) {
    fields()
        .that()
        .areDeclaredInClassesThat()
        .areNotRecords()
        .and()
        .areDeclaredInClassesThat()
        .areNotEnums()
        .and()
        .areDeclaredInClassesThat()
        .areNotInterfaces()
        .and()
        .areDeclaredInClassesThat()
        .haveSimpleNameNotContaining("Abstract")
        .and()
        .areNotStatic()
        .should(
            new ArchCondition<JavaField>("be private") {
              @Override
              public void check(JavaField field, ConditionEvents events) {
                if (!field.getModifiers().contains(JavaModifier.PRIVATE)) {
                  events.add(
                      SimpleConditionEvent.violated(
                          field,
                          String.format(
                              "Field <%s> in <%s> must be private [ADR-009]",
                              field.getName(), field.getOwner().getName())));
                }
              }
            })
        .because("Instance fields in concrete classes must be private [ADR-009]")
        .check(classes);
  }

  // ─── GOV-001: No Anonymous Classes ───

  /**
   * Asserts no anonymous classes in production (with enum/TypeReference exemption). Compiler
   * SYNTHETIC classes — e.g. the {@code Outer$1} enum switch-map javac emits for a {@code switch}
   * over an enum — are not developer-authored and are excluded from the check.
   */
  public static void assertNoAnonymousClasses(JavaClasses classes, String modulePackage) {
    classes()
        .that()
        .resideInAPackage(modulePackage + "..")
        .and()
        .doNotHaveModifier(JavaModifier.SYNTHETIC)
        .should()
        .notBeAnonymousClasses()
        .orShould(beEnumConstantOrTypeReference())
        .because("Anonymous classes are banned in production [GOV-001]")
        .check(classes);
  }

  // ─── GOV-004: No Standard Streams ───

  /** Asserts no classes access System.out or System.err. */
  public static void assertNoStandardStreams(JavaClasses classes) {
    com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
        .because("Use SLF4J loggers instead of standard output/error streams [GOV-004]")
        .check(classes);
  }

  // ─── GOV-005: No Field Injection ───

  /** Asserts no @Autowired field injection in the given module package. */
  public static void assertNoFieldInjection(JavaClasses classes, String modulePackage) {
    noFields()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage(modulePackage + "..")
        .should()
        .beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
        .because("Field injection is prohibited — constructor-based DI is mandatory [GOV-005]")
        .check(classes);
  }

  // ─── ERR-112: No Web Controllers in Non-Router ───

  /** Asserts no @RestController or @Controller annotations exist in the given module. */
  public static void assertNoWebControllers(JavaClasses classes, String modulePackage) {
    classes()
        .that()
        .resideInAPackage(modulePackage + "..")
        .should()
        .notBeAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
        .andShould()
        .notBeAnnotatedWith(org.springframework.stereotype.Controller.class)
        .because("Web controllers belong only in orazaka-router [ERR-112]")
        .check(classes);
  }

  // ─── ERR-129: application/service holds only *Service ───

  /**
   * Asserts every top-level class in {@code servicePackage} is named {@code *Service}
   * (capability-oriented). A {@code *Mapper}/{@code *Properties}/exception belongs with the code it
   * serves, not in the service package [ERR-129].
   */
  public static void assertServicePackageOnlyServices(JavaClasses classes, String servicePackage) {
    classes()
        .that()
        .resideInAPackage(servicePackage + "..")
        .and()
        .areTopLevelClasses()
        .should()
        .haveSimpleNameEndingWith("Service")
        .because(
            "application/service holds only application *Service (capability-oriented, never"
                + " pattern-named *Orchestrator/*Manager/*Handler, and not a *Mapper — mapping"
                + " belongs with the code it maps for) [ERR-129]")
        .check(classes);
  }

  // ─── ERR-130: One Package, One Component Kind ───

  /**
   * Asserts the domain package holds no transport DTOs ({@code *Request}/{@code *Response}).
   *
   * <p>Empty is allowed, as it already is for {@link #assertServicePackageOnlyServices}: a service
   * host whose domain types live in a Tier-1 contract has no {@code domain} pack of its own, and
   * ArchUnit's default treats "nothing to check" as a failure. The job service is exactly that
   * shape — its {@code JobCommand} and friends are in {@code orazaka-jobs-api} — so without this
   * the rule could not be wired there at all, and a module that later grows a {@code domain} pack
   * would inherit no guard. Allowing empty keeps the rule armed for that day.
   */
  public static void assertDomainHasNoTransportDtos(JavaClasses classes, String domainPackage) {
    noClasses()
        .that()
        .resideInAPackage(domainPackage + "..")
        .should()
        .haveSimpleNameEndingWith("Request")
        .orShould()
        .haveSimpleNameEndingWith("Response")
        .because(
            "Transport DTOs (*Request/*Response) belong in the adapter dto packs, never in"
                + " domain [ERR-130]")
        .check(classes);
  }

  /**
   * Asserts {@code infrastructure.support} holds only cross-cutting helpers / shared plumbing —
   * never a use-case {@code *Service}, a {@code *Controller}/{@code *Adapter}, or config [ERR-130].
   */
  public static void assertSupportPackageHygiene(JavaClasses classes, String supportPackage) {
    noClasses()
        .that()
        .resideInAPackage(supportPackage + "..")
        .should()
        .haveSimpleNameEndingWith("Service")
        .orShould()
        .haveSimpleNameEndingWith("Controller")
        .orShould()
        .haveSimpleNameEndingWith("Adapter")
        .orShould()
        .haveSimpleNameEndingWith("Properties")
        .orShould()
        .haveSimpleNameEndingWith("Configuration")
        .orShould()
        .beAnnotatedWith(org.springframework.stereotype.Service.class)
        .because(
            "infrastructure/support holds cross-cutting helpers and shared infra plumbing only —"
                + " never a *Service/*Controller/*Adapter/config [ERR-130]")
        .check(classes);
  }

  /**
   * Asserts the exact {@code infrastructure.adapter.persistence} package (excluding its
   * entity/repository/converter sub-packs) holds only {@code *Adapter}/{@code *Mapper} — one
   * package, one component kind [ERR-130].
   */
  public static void assertPersistenceAdapterPackageKind(
      JavaClasses classes, String modulePackage) {
    classes()
        .that()
        .resideInAPackage(modulePackage + ".infrastructure.adapter.persistence")
        .and()
        .areTopLevelClasses()
        .should()
        .haveSimpleNameEndingWith("Adapter")
        .orShould()
        .haveSimpleNameEndingWith(MAPPER_SUFFIX)
        .because(
            "infrastructure/adapter/persistence holds outbound-port adapters (*Adapter) and their"
                + " mappers (*Mapper) only — one package, one component kind [ERR-130]")
        .check(classes);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // HEX-001 — HEX-004: STRICT HEXAGONAL ARCHITECTURE ENFORCEMENT
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * [HEX-001] Enforces strict hexagonal layer dependencies using ArchUnit's layeredArchitecture.
   *
   * <ul>
   *   <li>Domain depends on NOTHING (pure POJO/Record territory).
   *   <li>Application may access Domain. Infrastructure may access Application and Domain.
   *   <li>No reverse flow is permitted.
   * </ul>
   */
  public static void assertStrictHexagonalBoundaries(JavaClasses classes, String modulePackage) {
    com.tngtech.archunit.library.Architectures.layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer(DOMAIN_LAYER)
        .definedBy(modulePackage + ".domain..")
        .layer(APPLICATION_LAYER)
        .definedBy(modulePackage + APPLICATION_PKG_PATTERN)
        .layer("Infrastructure")
        .definedBy(modulePackage + ".infrastructure..")
        .whereLayer(DOMAIN_LAYER)
        .mayNotAccessAnyLayer()
        .whereLayer(APPLICATION_LAYER)
        .mayOnlyAccessLayers(DOMAIN_LAYER)
        .whereLayer("Infrastructure")
        .mayOnlyAccessLayers(APPLICATION_LAYER, DOMAIN_LAYER)
        .because(
            "Hexagonal architecture mandates: Domain → nothing, Application → Domain only,"
                + " Infrastructure → Application + Domain [HEX-001]")
        .check(classes);
  }

  /**
   * [HEX-002] Prohibits domain classes from depending on framework packs.
   *
   * <p>The domain layer must remain pristine POJO/Record territory — zero Spring, JPA, or Jackson
   * dependencies allowed.
   */
  public static void assertDomainPurity(JavaClasses classes, String modulePackage) {
    noClasses()
        .that()
        .resideInAPackage(modulePackage + ".domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "jakarta.persistence..",
            "com.fasterxml.jackson..",
            "tools.jackson..")
        .because(
            "Domain layer must remain framework-free POJO/Record territory."
                + " No Spring, JPA, or Jackson (2 or 3) allowed [HEX-002]")
        .check(classes);
  }

  // ─── Private ArchCondition Helpers ───

  /**
   * Fails when a {@code SecurityConfig} opens {@code /internal/**} or {@code /uploads/**}.
   *
   * <p>Wave 1 closed four of these by hand. That is worth little on its own: the fifth service to
   * be written reintroduces the pattern, and nothing notices — which is exactly how three services
   * ended up with the same {@code permitAll()} on the money path (audit #3).
   *
   * <p>Source-scanned rather than expressed in ArchUnit because the subject is a *fluent argument*,
   * not a type relationship: {@code .requestMatchers("/internal/v1/**").permitAll()} is one
   * expression whose meaning lives entirely in a string literal, and bytecode analysis cannot see
   * which matcher a {@code permitAll} belongs to.
   *
   * @param sourceRoot the module's {@code src/main/java}
   */
  public static void assertNoPermitAllOnInternalOrUploads(Path sourceRoot) {
    List<String> violations = new ArrayList<>();
    // It returned green for a source root that did not exist; a misspelt path guarded nothing.
    try (Stream<Path> paths =
        Files.isDirectory(sourceRoot) ? Files.walk(sourceRoot) : Stream.empty()) {
      for (Path file :
          GovernanceSubjects.require(
              "ADR-035",
              "SecurityConfig.java files under " + sourceRoot,
              paths
                  .filter(p -> p.getFileName().toString().endsWith("SecurityConfig.java"))
                  .toList())) {
        String source = Files.readString(file);
        // Comments are stripped first: this file's own explanations name both the matcher and
        // permitAll, and a rule that fails on the prose justifying it is a rule nobody keeps.
        String code = source.replaceAll("(?m)^\\s*(//|\\*|/\\*).*$", "");
        Matcher matcher = PERMIT_ALL_ON_PROTECTED_PREFIX.matcher(code);
        while (matcher.find()) {
          violations.add(file.getFileName() + " opens " + matcher.group(1));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to scan " + sourceRoot, e);
    }
    if (!violations.isEmpty()) {
      throw new AssertionError(
          "A service-to-service or media surface must never be permitAll — the edge not routing it"
              + " is topology, and topology is not a credential (ADR-035, audit #1/#3):\n  "
              + String.join("\n  ", violations));
    }
  }

  /**
   * Fails when a {@code SecurityConfig} matches {@code /internal/v1/**} without demanding the
   * {@code SERVICE} authority.
   *
   * <p>The companion to {@link #assertNoPermitAllOnInternalOrUploads}, and the one that matters
   * more. Removing {@code permitAll()} is not the guarantee — {@code .authenticated()} also removes
   * it, and would let *any signed-in user* call the credit ledger's machine surface, which is the
   * SSRF case the finding is about. Only the positive form is the property worth asserting.
   *
   * @param sourceRoot the module's {@code src/main/java}
   */
  public static void assertInternalSurfaceRequiresServiceAuthority(Path sourceRoot) {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths =
        Files.isDirectory(sourceRoot) ? Files.walk(sourceRoot) : Stream.empty()) {
      List<Path> sources =
          GovernanceSubjects.require("ADR-035", "source files under " + sourceRoot, paths.toList());

      // A module that publishes an /internal controller and has NO SecurityConfig is the case the
      // rule below cannot see: there is no `permitAll` to catch and no matcher to inspect, only an
      // absence. That is exactly how knowledge-service served its RAG surface with no credential
      // and no line of configuration admitting it (ADR-035, "Known gap").
      boolean publishesInternal =
          sources.stream()
              .filter(p -> p.getFileName().toString().endsWith("Controller.java"))
              .anyMatch(p -> INTERNAL_CONTROLLER.matcher(readOrEmpty(p)).find());
      List<Path> configs =
          sources.stream()
              .filter(p -> p.getFileName().toString().endsWith("SecurityConfig.java"))
              .toList();
      if (publishesInternal && configs.isEmpty()) {
        violations.add(
            "a controller publishes /internal/** but the module has no SecurityConfig at all");
      }

      for (Path file : configs) {
        String code = readOrEmpty(file).replaceAll("(?m)^\\s*(//|\\*|/\\*).*$", "");
        if (!INTERNAL_MATCHER.matcher(code).find()) {
          continue;
        }
        if (!SERVICE_AUTHORITY_RULE.matcher(code).find()) {
          violations.add(
              file.getFileName() + " matches /internal/v1/** without hasAuthority(SERVICE)");
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to scan " + sourceRoot, e);
    }
    if (!violations.isEmpty()) {
      throw new AssertionError(
          "A machine surface must demand the SERVICE authority, not merely authentication — a user"
              + " session must never reach it (ADR-035):\n  "
              + String.join("\n  ", violations));
    }
  }

  /** A controller mapped onto the machine-to-machine prefix. */
  private static final Pattern INTERNAL_CONTROLLER =
      Pattern.compile("@RequestMapping\\(\\s*\"/internal/");

  private static String readOrEmpty(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + file, e);
    }
  }

  /** Any matcher on the internal prefix. */
  private static final Pattern INTERNAL_MATCHER =
      Pattern.compile("requestMatchers\\(\\s*\"/internal/v1/\\*\\*\"\\s*\\)");

  /** That matcher immediately followed by the SERVICE authority. */
  private static final Pattern SERVICE_AUTHORITY_RULE =
      Pattern.compile(
          "requestMatchers\\(\\s*\"/internal/v1/\\*\\*\"\\s*\\)"
              + "\\s*\\.\\s*hasAuthority\\(\\s*\"SERVICE\"\\s*\\)");

  /** {@code .requestMatchers("…/internal…|…/uploads…")} followed by {@code .permitAll()}. */
  private static final Pattern PERMIT_ALL_ON_PROTECTED_PREFIX =
      Pattern.compile(
          "requestMatchers\\(\\s*\"([^\"]*(?:/internal|/uploads)[^\"]*)\"\\s*\\)\\s*\\.\\s*permitAll");

  /**
   * Fails when a service that serves HTTP does not run its requests on virtual threads.
   *
   * <p>AGENTS.md §4 mandates {@code spring.threads.virtual.enabled=true} without reservation, and
   * two services of eight had silently drifted without it — including the interactive ingress,
   * which holds an SSE stream open for the whole exchange. On the default Tomcat pool that is a
   * ceiling of 200 concurrent conversations, reached with no error and no log line.
   *
   * <p>A configuration rule rather than an ArchUnit one because the subject is a YAML key, and
   * because the mistake is an *omission*: there is no class to inspect for a setting nobody wrote.
   * That is exactly why it went unnoticed — the contract said it, and nothing could see it.
   *
   * <p>Skipped for a module with no {@code application.yml} or no web server: a pure library has no
   * request pool to configure.
   *
   * @param moduleRoot the module directory (usually {@code System.getProperty("user.dir")})
   */
  public static void assertVirtualThreadsEnabled(Path moduleRoot) {
    Path config = moduleRoot.resolve("src/main/resources/application.yml");
    GovernanceSubjects.require(
        "AGENTS.md §4",
        "application.yml at " + config,
        Files.isRegularFile(config) ? List.of(config) : List.of());
    String yaml;
    try {
      yaml = Files.readString(config);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + config, e);
    }
    // Comments stripped: this block's own rationale names the setting, and a rule that reads its
    // justification as compliance is a rule that passes on a service which opted out in prose.
    String code = yaml.replaceAll("(?m)^\\s*#.*$", "");
    if (!VIRTUAL_THREADS_ENABLED.matcher(code).find()) {
      throw new AssertionError(
          "AGENTS.md §4 requires spring.threads.virtual.enabled=true — blocking I/O on a platform"
              + " thread caps this service at the Tomcat pool size, silently: "
              + config);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // PACK-002 / PACK-003: THE ENGINE DOES NOT KNOW A PACK BY NAME (ADR-037 §4.2)
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * [PACK-002] Fails when a pack, studio, or pack-capability key appears as a literal in engine
   * code under {@code orazaka-libs/**}, {@code orazaka-apps/services/**} or {@code
   * orazaka-apps/workers/**}.
   *
   * <p>The architecture promises "a new pack is a row, never a deploy". Each such literal is a
   * place where that promise is already false — the pack's identity is compiled into the engine, so
   * the next pack needs an edit here. Keys belong in {@code infra/initdb/**} or in the pack's own
   * bundle, both outside the scanned roots.
   *
   * <p>Deliberately silent about {@code orazaka.core.*}: the engine's own capabilities are not pack
   * knowledge, and a rule that flagged them would be met with an allowlist rather than a fix.
   *
   * @param repositoryRoot the repository root, from {@link
   *     PackPurityRules#locateRepositoryRoot(Path)}
   */
  public static void assertNoPackKeyLiterals(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "PACK-002");
    PackPurityRules.assertNoPackKeyLiterals(repositoryRoot);
  }

  /**
   * [PACK-003] Fails when engine code branches on one of those identifiers — inline, or through a
   * constant declared in the same file.
   *
   * <p>The stricter half, and the one that catches the shape the violations actually took. A branch
   * on a capability key is a dispatch table pretending to be a heuristic: it decides correctly for
   * every pack that existed when it was written, and silently wrongly for the next one. That is the
   * failure mode this whole phase removed — a capability the chain did not recognise reached the
   * text queue and was answered by a model that had no idea what it had been asked.
   *
   * @param repositoryRoot the repository root, from {@link
   *     PackPurityRules#locateRepositoryRoot(Path)}
   */
  public static void assertNoPackKeyConditionals(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "PACK-003");
    PackPurityRules.assertNoPackKeyConditionals(repositoryRoot);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // EXEC-001 / EXEC-002: THE TWO DISPATCH DISCRIMINANTS COHERE (ADR-038)
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * [EXEC-001] Fails when an in-process capability's {@code handler_key} has no {@code
   * JobExecutor}.
   *
   * <p>Since routing became data (ADR-037) a capability carries two independent discriminants:
   * {@code routing_key} picks the process, {@code handler_key} picks the code path inside it. This
   * guards the second — a capability whose handler nobody implements reaches the right process and
   * dies on arrival.
   *
   * <p>Scoped to capabilities routed to a JVM service: a worker that dispatches on the routing key
   * alone, like the Python media worker, has no use for a handler key, and demanding one would fail
   * the build on the very capabilities that show the AMQP contract is the real SPI.
   *
   * @param repositoryRoot the repository root, from {@link
   *     ExecutorCoherenceRules#locateRepositoryRoot(Path)}
   */
  public static void assertEveryCapabilityHasAnExecutor(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "EXEC-001");
    ExecutorCoherenceRules.assertEveryCapabilityHasAnExecutor(repositoryRoot);
  }

  /**
   * [EXEC-002] Fails when a capability's {@code routing_key} is drained by no declared worker.
   *
   * <p>The first discriminant's guard, and the complement of publish-time route resolution: a route
   * can exist and still lead nowhere. Binding-based rather than family-based, because {@code
   * worker_family} proved too coarse to answer it — phase A seeded one family across three routing
   * keys and two processes.
   *
   * @param repositoryRoot the repository root, from {@link
   *     ExecutorCoherenceRules#locateRepositoryRoot(Path)}
   */
  public static void assertEveryCapabilityIsDrained(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "EXEC-002");
    ExecutorCoherenceRules.assertEveryCapabilityIsDrained(repositoryRoot);
  }

  /** {@code threads: virtual: enabled: true}, whatever the surrounding indentation. */
  private static final Pattern VIRTUAL_THREADS_ENABLED =
      Pattern.compile("threads:\\s*\\n\\s*virtual:\\s*\\n\\s*enabled:\\s*true");

  private static ArchCondition<JavaField> bePrivateAndFinal() {
    return new ArchCondition<>("be private and final") {
      @Override
      public void check(JavaField field, ConditionEvents events) {
        boolean isPrivate = field.getModifiers().contains(JavaModifier.PRIVATE);
        boolean isFinal = field.getModifiers().contains(JavaModifier.FINAL);
        if (!isPrivate || !isFinal) {
          events.add(
              SimpleConditionEvent.violated(
                  field,
                  String.format(
                      "Field <%s> in <%s> is not private final (private=%s, final=%s)",
                      field.getName(), field.getOwner().getName(), isPrivate, isFinal)));
        }
      }
    };
  }

  private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass>
      beEnumConstantOrTypeReference() {
    return new ArchCondition<>("be an enum constant body or TypeReference") {
      @Override
      public void check(
          com.tngtech.archunit.core.domain.JavaClass javaClass, ConditionEvents events) {
        boolean isEnumBody = javaClass.getEnclosingClass().map(JavaClass::isEnum).orElse(false);
        boolean isTypeRef =
            javaClass
                .getSuperclass()
                .map(superClass -> superClass.getName().contains("TypeReference"))
                .orElse(false);
        if (!isEnumBody && !isTypeRef) {
          events.add(
              SimpleConditionEvent.violated(
                  javaClass, "Anonymous class <" + javaClass.getName() + "> is not exempt"));
        }
      }
    };
  }

  /**
   * [CAP-001] One record models a capability, and the projections of it say so.
   *
   * <p>The concept was modelled six times. Three of those are legitimate and stay: {@code
   * CapabilityRoute} (four fields, the dispatcher's view), {@code CapabilityDescriptor} (six, the
   * engine's — deliberately without routing or billing), and {@code CapabilityEntity} (the ORM
   * mapping, which cannot be a record). A fourth, {@code PackCapability}, is a manifest grammar a
   * third party writes and may legitimately name its fields differently.
   *
   * <p>The fifth, {@code CapabilityDto}, was {@code CapabilityDeclaration} again — eleven
   * components, identical names, identical types, and <b>no invariants at all</b>. The copy that
   * reached the database was the one that had dropped every check on the way (ADR-057 §1).
   *
   * <p>This rule fires on a new type that carries the capability's <i>whole</i> shape rather than a
   * projection of it: a record or class naming at least six of the ten fields and not on the list
   * below. A narrow projection is what the list exists to permit — the rule is about a sixth
   * <i>copy</i>, not a fifth <i>view</i>.
   *
   * @param repositoryRoot the repository root
   */
  public static void assertOneCapabilityModel(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "CAP-001");
    Set<String> permitted =
        Set.of(
            "CapabilityDeclaration", // the one source: validated, Tier-1, owned by the job plane
            "CapabilityEntity", // the ORM mapping; an @Entity cannot be a record
            "PackCapability", // the manifest grammar a third party writes, versioned separately
            "CapabilityRoute", // four fields: the dispatcher's projection
            "CapabilityDescriptor"); // six fields: the engine's, without routing or billing
    List<String> fields =
        List.of(
            "featureKey",
            "label",
            "icon",
            "handlerKey",
            "uriPath",
            "httpMethod",
            "payloadTemplate",
            "routingKey",
            "billableUnit",
            "billableCapability");
    List<Path> declarations = new ArrayList<>();
    List<String> offenders = new ArrayList<>();
    for (Path file : PackPurityRules.productionSources(repositoryRoot)) {
      String name = file.getFileName().toString().replace(".java", "");
      if (permitted.contains(name) || name.endsWith("Test") || name.endsWith("IT")) {
        continue;
      }
      String source = readSource(file);
      // A DECLARATION, not a use. A mapper names every field because it copies them one by one,
      // and flagging it would make this rule fire on exactly the code that keeps the models in
      // step. What is looked at is a record's component list, or a class's own fields.
      String declaration = capabilityDeclarationBody(source, name);
      if (declaration.isEmpty()) {
        continue;
      }
      declarations.add(file);
      long present = fields.stream().filter(declaration::contains).count();
      if (present >= 6) {
        offenders.add(
            file.getFileName()
                + " carries "
                + present
                + " of the capability's ten fields — a sixth copy of the concept, not a projection");
      }
    }
    GovernanceSubjects.require("CAP-001", "type declarations in production sources", declarations);
    assertTrue(
        offenders.isEmpty(),
        "[CAP-001] one record models a capability and the rest are narrow projections of it."
            + " The assumption \"every capability has an HTTP surface\" was written four times and"
            + " three were wrong; each cost a separate production failure to find (ADR-057). A new"
            + " whole-shape copy starts that again:\n  "
            + String.join("\n  ", offenders));
  }

  /**
   * [CAP-002] The endpoint rule has one author.
   *
   * <p>{@code uriPath} and {@code httpMethod} are both present or both absent, and the only place
   * that decides so is {@link com.orazaka.jobs.domain.model.CapabilityDeclaration}. A model that
   * re-derives it — a {@code "POST"} default of its own, a {@code nullable = false} of its own — is
   * how three of the four sites came to disagree with each other.
   *
   * <p>The database's CHECK constraint is exempt and stays: it is the same rule in another system,
   * which is what catches a writer that never passed through Java at all.
   *
   * @param repositoryRoot the repository root
   */
  public static void assertEndpointRuleHasOneAuthor(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "CAP-002");
    List<String> offenders = new ArrayList<>();
    // The population is the author this rule protects: if CapabilityDeclaration is renamed or
    // moved,
    // "every other file" is everything, and the rule would be guarding a name that no longer
    // exists.
    GovernanceSubjects.require(
        "CAP-002",
        "CapabilityDeclaration.java (the endpoint rule's one author)",
        PackPurityRules.productionSources(repositoryRoot).stream()
            .filter(file -> file.getFileName().toString().equals("CapabilityDeclaration.java"))
            .toList());
    for (Path file : PackPurityRules.productionSources(repositoryRoot)) {
      String name = file.getFileName().toString();
      if (name.equals("CapabilityDeclaration.java") || name.endsWith("Test.java")) {
        continue;
      }
      String source = readSource(file);
      if (!source.contains("httpMethod")) {
        continue;
      }
      if (name.equals("GovernanceRules.java") || name.equals("PackPurityRules.java")) {
        // The rules' own text names the thing they forbid; that is what a rule is.
        continue;
      }
      if (source.contains("\"POST\"") && source.contains("uriPath")) {
        offenders.add(name + " defaults httpMethod itself instead of calling defaultHttpMethod");
      }
      if (source.contains("name = \"http_method\", nullable = false")) {
        offenders.add(name + " maps http_method as NOT NULL, which the schema no longer is");
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "[CAP-002] the endpoint rule belongs to CapabilityDeclaration; call"
            + " defaultHttpMethod/requireWholeEndpoint rather than restating it (ADR-057 §2):\n  "
            + String.join("\n  ", offenders));
  }

  /** One source file, or empty when it cannot be read — a rule must not fail on a locked file. */
  private static String readSource(Path file) {
    try {
      return java.nio.file.Files.readString(file);
    } catch (java.io.IOException | RuntimeException unreadable) {
      return "";
    }
  }

  /**
   * A type's own field declaration — a record's component list, or a class's {@code private} fields
   * — and nothing else.
   *
   * <p>The distinction that makes [CAP-001] usable: a mapper mentions every field because copying
   * them is its job, and it is the code that keeps the permitted models in step. Only a type that
   * <i>declares</i> the shape is a copy of the concept.
   */
  private static String capabilityDeclarationBody(String source, String name) {
    int header = source.indexOf("record " + name + "(");
    if (header >= 0) {
      int close = source.indexOf(") {", header);
      return close < 0 ? "" : source.substring(header, close);
    }
    if (source.indexOf("class " + name) < 0) {
      return "";
    }
    StringBuilder declared = new StringBuilder();
    for (String line : source.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("private ") && trimmed.endsWith(";")) {
        declared.append(trimmed).append('\n');
      }
    }
    return declared.toString();
  }

  /**
   * [KIT-001] Every service's filter chain starts from the one security baseline.
   *
   * <p>The invariant: <i>which paths are open, which require SERVICE, which require a user.</i>
   * Measured across six services it had six authors, and two had drifted — {@code identity-service}
   * declared no CORS preflight exemption and no {@code /error}, {@code conversation-service}
   * omitted {@code /actuator/info} (ADR-058 §3). It now has one author, krizaka-security's {@code
   * SecurityBaseline}, and the rule is that every {@code SecurityConfig} applies it: a service that
   * builds its chain by hand is the seventh author again (ADR-073).
   *
   * @param repositoryRoot the repository root
   */
  public static void assertSecurityBaselineIsUniform(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "KIT-001");
    List<String> offenders = new ArrayList<>();
    List<Path> securityConfigs =
        GovernanceSubjects.require(
            "KIT-001",
            "SecurityConfig.java files",
            PackPurityRules.productionSources(repositoryRoot).stream()
                .filter(file -> file.getFileName().toString().equals("SecurityConfig.java"))
                .toList());
    for (Path file : securityConfigs) {
      String source = withoutComments(readSource(file));
      if (!source.contains("SecurityBaseline.apply(")) {
        offenders.add(serviceOf(file) + " builds its filter chain without SecurityBaseline.apply");
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "[KIT-001] the security baseline has one author, krizaka-security; a chain built by hand is"
            + " how /internal/v1 stayed open in three services at once (ADR-058, ADR-073):\n  "
            + String.join("\n  ", offenders));
  }

  /**
   * [KIT-002] Message deduplication has one author: krizaka-messaging.
   *
   * <p>The invariant: <i>a message seen twice is processed once, and a message whose processing
   * failed is seen again.</i> It had five authors and none of them held both halves — three checked
   * then acted, one claimed and never released (ADR-058 §2). The kit's {@code MessageDedup} holds
   * both; this rule fails on any production code that claims {@code processed_messages} itself or
   * declares a dedup type of its own, so the sixth author is caught the day it is written.
   *
   * @param repositoryRoot the repository root
   */
  public static void assertDedupIsAtomic(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "KIT-002");
    List<Path> sources = PackPurityRules.productionSources(repositoryRoot);
    GovernanceSubjects.require(
        "KIT-002",
        "listeners using krizaka-messaging's MessageDedup",
        sources.stream()
            .filter(file -> readSource(file).contains("import com.krizaka.messaging.dedup."))
            .toList());
    List<String> offenders = new ArrayList<>();
    for (Path file : sources) {
      // The rules name the table in order to forbid it; that is not authoring a dedup.
      if (!file.toString().endsWith(".java") || file.toString().contains("orazaka-test-support")) {
        continue;
      }
      // Code, not comments: naming a defect in order to record it is not committing it.
      String source = withoutComments(readSource(file));
      if (source.contains("processed_messages")
          || source.matches("(?s).*\\b(class|interface|record)\\s+\\w*Dedup\\w*.*")) {
        offenders.add(serviceOf(file) + "/" + file.getFileName() + " is its own dedup author");
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "[KIT-002] dedup claims atomically and releases on failure — use krizaka-messaging's"
            + " MessageDedup (ADR-058 §2, ADR-073):\n  "
            + String.join("\n  ", offenders));
  }

  /**
   * [KIT-003] An outbox store claims the rows the relay publishes, and the relay has one author.
   *
   * <p>The invariant: <i>an outbox row is published once.</i> The relay is krizaka-messaging's;
   * what stays with each context is its store, and the store's {@code lockPendingBatch} is where
   * the claim lives. One of four relays selected pending rows with no lock at all — a second
   * instance reading between the select and the mark publishes the same row again (ADR-058 §3). So
   * every select of pending rows must say {@code SKIP LOCKED}, and no context may write a relay of
   * its own.
   *
   * @param repositoryRoot the repository root
   */
  public static void assertOutboxRelaysClaim(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "KIT-003");
    List<String> offenders = new ArrayList<>();
    List<Path> sources = PackPurityRules.productionSources(repositoryRoot);
    List<Path> pendingSelects =
        GovernanceSubjects.require(
            "KIT-003",
            "sources selecting pending outbox rows",
            sources.stream()
                .filter(file -> file.toString().endsWith(".java"))
                .filter(file -> readSource(file).contains("published_at IS NULL"))
                .toList());
    for (Path file : pendingSelects) {
      if (!readSource(file).contains("SKIP LOCKED")) {
        offenders.add(
            serviceOf(file) + "/" + file.getFileName() + " selects pending rows without claiming");
      }
    }
    for (Path file : sources) {
      if (file.getFileName().toString().equals("OutboxRelay.java")) {
        offenders.add(
            serviceOf(file) + " writes its own OutboxRelay; the relay is krizaka-messaging's");
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "[KIT-003] an outbox store claims what it hands the relay — FOR UPDATE SKIP LOCKED — or two"
            + " instances publish the same row (ADR-058 §3, ADR-073):\n  "
            + String.join("\n  ", offenders));
  }

  /**
   * [KIT-004] Session-token security has one author: krizaka-security.
   *
   * <p>{@code SessionJwtProperties} was byte-identical in five services and {@code
   * ServiceTokenProvider} in seven; the first carries the 256-bit minimum of the HS256 secret, the
   * second the exact claim shape every {@code /internal/v1} matcher expects. A copy is a place to
   * relax one of them while the others do not, so the rule is that there is no copy (ADR-073).
   *
   * @param repositoryRoot the repository root
   */
  public static void assertSessionSecurityHasOneAuthor(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "KIT-004");
    List<Path> sources = PackPurityRules.productionSources(repositoryRoot);
    GovernanceSubjects.require(
        "KIT-004",
        "sources using krizaka-security",
        sources.stream()
            .filter(file -> readSource(file).contains("import com.krizaka.security."))
            .toList());
    List<String> offenders = new ArrayList<>();
    for (Path file : sources) {
      String name = file.getFileName().toString();
      if (name.equals("SessionJwtProperties.java") || name.equals("ServiceTokenProvider.java")) {
        offenders.add(serviceOf(file) + " keeps its own " + name);
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "[KIT-004] the session secret and the service token are krizaka-security's (ADR-073):\n  "
            + String.join("\n  ", offenders));
  }

  /** The service a source file belongs to, for a message an operator can act on. */
  private static String serviceOf(Path file) {
    String path = file.toString();
    int at = path.indexOf("/services/orazaka-");
    if (at < 0) {
      return path.substring(path.lastIndexOf('/') + 1);
    }
    String rest = path.substring(at + "/services/orazaka-".length());
    return rest.substring(0, rest.indexOf('/'));
  }

  /** A source with its comments removed, for a rule that must read code and not prose. */
  private static String withoutComments(String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\n]*", "");
  }

  /**
   * [SAGA-001] One author closes a run's credit hold.
   *
   * <p>The invariant: <i>a run's hold is closed exactly once — settled at the sum of what its steps
   * measured, or released, never both.</i> It had three authors, and the concentration had already
   * cost: {@code RunSagaService} carried it beside DAG advancement and leaked one into the other,
   * passing {@code run.holdId()} into every {@code StepDispatch} because the method building a
   * step's payload had the run's billing identity in scope. The first step to finish settled the
   * whole run at the hold's own rate — 480 credits reserved, 5 debited (ADR-041, ADR-059).
   *
   * <p>Only {@code RunSettlementService} may call {@code settle} or {@code release} now. A second
   * caller is how that defect gets written again.
   *
   * @param repositoryRoot the repository root
   */
  public static void assertOneSettlementAuthor(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "SAGA-001");
    List<String> offenders = new ArrayList<>();
    // The invariant belongs to the STUDIO context: a studio run's hold is closed once, there.
    // The billing service is the counterparty that executes a settlement and prices a
    // MeteredStep on the ledger's behalf — a different role, not a second author of this rule.
    List<Path> studioSources =
        GovernanceSubjects.require(
            "SAGA-001",
            "orazaka-studio-service production sources",
            PackPurityRules.productionSources(repositoryRoot).stream()
                .filter(file -> file.toString().contains("/orazaka-studio-service/"))
                .toList());
    for (Path file : studioSources) {
      String name = file.getFileName().toString();
      if (name.equals("RunSettlementService.java")
          || name.equals("CreditReservationService.java")
          || name.endsWith("Test.java")) {
        continue;
      }
      String source = withoutComments(readSource(file));
      if (source.contains("creditReservationService.settle(")
          || source.contains("creditReservationService.release(")) {
        offenders.add(name + " closes a run's hold; only RunSettlementService may");
      }
      // Computing what a run measured is half of closing its hold, and the half this rule missed.
      // ADR-059's own refactor left `meteredSteps` behind in RunSagaService as dead code: a second
      // author of the settlement computation, created by the run whose ADR says there is one. It
      // called neither settle nor release, so the check above saw nothing, and 2772 tests passed
      // because an uncalled private method compiles (ADR-060).
      if (source.contains("MeteredStep")
          && (source.contains("BillableCapability.valueOf")
              || source.contains("consumptionReport("))) {
        offenders.add(
            name + " computes what a run measured; that is settlement's half of the invariant");
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "[SAGA-001] a run's hold is closed by one author (ADR-059 §3):\n  "
            + String.join("\n  ", offenders));
  }

  /**
   * [SAGA-002] One author resolves what a dispatched step carries from its pack.
   *
   * <p>The invariant: <i>a dispatched step carries what its pack declared, and the engine supplies
   * no subject of its own.</i> Four declarations arrived over three phases — the refused domain,
   * the crisis terms, the reviewed crisis reply, and what the user actually wrote — and all four
   * were ferried by the method that advances the DAG, for the same reason {@code holdId} was: it
   * was the method holding the run when the payload was being built (ADR-059 §2).
   *
   * <p>Separated before it cost a second time. Only {@code StepDeclarationService} reads {@code
   * pack.scope_guard} or {@code pack.safety}.
   *
   * @param repositoryRoot the repository root
   */
  public static void assertOneDeclarationAuthor(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "SAGA-002");
    List<String> offenders = new ArrayList<>();
    GovernanceSubjects.require(
        "SAGA-002",
        "StepDeclarationService.java (the declaration reader's one author)",
        PackPurityRules.productionSources(repositoryRoot).stream()
            .filter(file -> file.getFileName().toString().equals("StepDeclarationService.java"))
            .toList());
    for (Path file : PackPurityRules.productionSources(repositoryRoot)) {
      String name = file.getFileName().toString();
      // The installer reads the same columns to answer a DIFFERENT question — may this pack
      // install here, in this region — and that is not a dispatch carrying a declaration. A rule
      // that conflated the two would force the installer to go through a dispatch-shaped service
      // to ask something dispatch has no opinion about (ADR-059 §3).
      if (name.equals("StepDeclarationService.java")
          || name.startsWith("JdbcPackInstall")
          || name.equals("StudioInstallationService.java")
          || name.equals("PackInstallerService.java")
          || name.equals("GovernanceRules.java")
          || name.endsWith("Test.java")) {
        continue;
      }
      String source = withoutComments(readSource(file));
      if (source.contains("p.scope_guard") || source.contains("p.safety")) {
        offenders.add(name + " reads a pack's declarations; only StepDeclarationService may");
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "[SAGA-002] a pack's declarations reach a dispatch through one author (ADR-059 §3):\n  "
            + String.join("\n  ", offenders));
  }

  /**
   * [SAGA-003] The saga's read-only invariants stay read-only.
   *
   * <p>ADR-059 left six invariants in {@code RunSagaService} on the argument that they "need each
   * other". The read/write matrix says something sharper and different: <b>four of the six write
   * nothing.</b> Deciding whether a step is ready, resolving a template, expanding a fan-out and
   * building an event body are pure functions of state.
   *
   * <p>That matters because the defect this whole thread is about is a <i>write</i> defect. ADR-041
   * cost 475 credits when a method holding the run's billing identity used it while building
   * something else. <b>A method that writes nothing has no identity to leak.</b> So the four are
   * not merely tolerable where they are — they are structurally incapable of producing that
   * failure, which is a stronger reason to leave them than the one ADR-059 gave (ADR-060 §3).
   *
   * <p>The reason to pin it: the day one of them starts writing is the day the argument expires,
   * and it would expire silently. This rule is that day's alarm.
   *
   * @param repositoryRoot the repository root
   */
  public static void assertSagaReadersDoNotWrite(Path repositoryRoot) {
    Workspace.require(repositoryRoot, "SAGA-003");
    Set<String> readOnly =
        Set.of(
            "advance",
            "dependenciesSatisfied",
            "allTerminal",
            "isTerminal",
            "loadRun",
            "buildScope",
            "effectiveConfig",
            "installationConfig",
            "expand",
            "itemFor",
            "worst",
            "stepStates",
            "stepCauses",
            "stepFailureMessage",
            "locateStep",
            "runEvent");
    List<String> offenders = new ArrayList<>();
    List<String> located = new ArrayList<>();
    for (Path file : PackPurityRules.productionSources(repositoryRoot)) {
      if (!file.getFileName().toString().equals("RunSagaService.java")) {
        continue;
      }
      String source = withoutComments(readSource(file));
      for (String method : readOnly) {
        String body = methodBody(source, method);
        if (body.isEmpty()) {
          // Skipped before GOV-006, which is how a renamed reader would have left this rule
          // guarding
          // fifteen methods, then fourteen, then none, all green. Now it is a violation by name.
          offenders.add(
              method + " is listed as a pure reader and no longer exists under that name");
          continue;
        }
        located.add(method);
        if (body.contains("UPDATE ")
            || body.contains("INSERT INTO ")
            || body.contains("DELETE FROM ")
            || body.contains("settlementService.")
            || body.contains("outboxService.append")
            || body.contains("runAuditService.record")) {
          offenders.add(
              method
                  + " writes; it is one of the saga's pure readers and its purity is why the"
                  + " concentration is safe there");
        }
      }
    }
    GovernanceSubjects.require(
        "SAGA-003", "pure reader methods located in RunSagaService", located);
    assertTrue(
        offenders.isEmpty(),
        "[SAGA-003] the saga's read-only invariants write nothing — a reader cannot leak a write"
            + " identity, which is the whole reason they may share a class (ADR-060 §3):\n  "
            + String.join("\n  ", offenders));
  }

  /**
   * One method's body, by brace matching from its signature.
   *
   * <p>Brace matching rather than "until the next method", because a rule that reads the wrong span
   * either misses a violation or invents one, and both have happened in this file.
   */
  private static String methodBody(String source, String method) {
    java.util.regex.Matcher signature =
        java.util.regex.Pattern.compile(
                // Anchored to a member DECLARATION, not to the name. `\bstepStates\(...\)[^;{]*\{`
                // also matches the call inside `allTerminal(blueprint, stepStates(runId))) {`, so
                // the rule read an `if` block and found no writes in it — the exact wrong-span
                // failure the comment above claims to avoid, shipped once before being caught.
                "\n  (?:private|public|static|final|[A-Za-z<>,\\[\\] ]+)\\s*[\\w<>,\\[\\] ]*\\b"
                    + method
                    + "\\([^)]*\\)[^;{]*\\{")
            .matcher(source);
    if (!signature.find()) {
      return "";
    }
    int depth = 0;
    for (int at = signature.end() - 1; at < source.length(); at++) {
      char c = source.charAt(at);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return source.substring(signature.end(), at);
        }
      }
    }
    return "";
  }
}
