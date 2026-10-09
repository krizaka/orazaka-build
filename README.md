<!-- krizaka-header -->
<div align="center">

<img src=".github/assets/orazaka-logo.svg" alt="Orazaka" width="420">

# Orazaka Build

**The AI that never leaves home.**

Parent POM (Spring Boot / Spring AI BOMs, plugin management, Orazaka BOM) and the shared governance test kit (ArchUnit rules, Testcontainers base) for every Orazaka JVM repository.

[![CI](https://github.com/krizaka/orazaka-build/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/orazaka-build/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Orazaka](https://img.shields.io/badge/part%20of-Orazaka-f59e0b)](https://github.com/krizaka/orazaka#repositories)
[![Docs](https://img.shields.io/badge/docs-krizaka.com-6366f1)](https://www.krizaka.com/en/products/orazaka)

[Documentation](https://www.krizaka.com/en/products/orazaka) · [Website](https://www.krizaka.com) · [Krizaka on GitHub](https://github.com/krizaka)

</div>
<!-- /krizaka-header -->

**Layer:** Foundation — reusable by any Krizaka application · **Version:** `1.0.0-SNAPSHOT` · **License:** Apache-2.0 ·
part of the [Orazaka platform](https://github.com/krizaka/orazaka) by [Krizaka](https://krizaka.com)

## What it provides

| Module | Artifact | Role |
|:---|:---|:---|
| `orazaka-parent` | `com.krizaka.orazaka:orazaka-parent` (pom) | Java 21, Spring Boot 4.0 + Spring AI 2.0 BOMs, the **Orazaka BOM** (every component at one platform version), Spotless (google-java-format), JaCoCo, Surefire/Failsafe, `dev`/`staging`/`prod` profiles, GitHub Packages distribution. |
| `orazaka-test-support` | `com.krizaka.orazaka:orazaka-test-support` | The governance kit: ArchUnit rules (`GovernanceRules`, naming, tiers, security, outbox, metering…), SQL seam rules, Testcontainers singletons (`AbstractContainerIntegrationTest`), workspace locator. |

## Use it

Every Orazaka JVM repository inherits the parent:

```xml
<parent>
    <groupId>com.krizaka.orazaka</groupId>
    <artifactId>orazaka-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <relativePath/>
</parent>
```

and runs the governance rules from its tests:

```xml
<dependency>
    <groupId>com.krizaka.orazaka</groupId>
    <artifactId>orazaka-test-support</artifactId>
    <scope>test</scope>
</dependency>
```

A new Krizaka application can adopt the same parent to get the same stack, versions and
quality gates with zero copy-paste.

### Workspace-wide rules

Some rules are **cross-repository** by nature (no pack name in engine code, every capability has an
executor, one settlement author…). They need every repository on disk, so they run inside the
Orazaka workspace ([`krizaka/orazaka`](https://github.com/krizaka/orazaka)) and are reported as *skipped* in a standalone
clone — never as passed.

## Position in the platform

| | |
|:---|:---|
| Depends on | _none — this repository is a root of the dependency graph._ |
| Used by | [`orazaka-contracts`](https://github.com/krizaka/orazaka-contracts) · [`krizaka-users`](https://github.com/krizaka/krizaka-users) · [`krizaka-notifications`](https://github.com/krizaka/krizaka-notifications) · [`krizaka-billing`](https://github.com/krizaka/krizaka-billing) · [`orazaka-studio`](https://github.com/krizaka/orazaka-studio) · [`orazaka-ai-engine`](https://github.com/krizaka/orazaka-ai-engine) · [`orazaka-conversation-service`](https://github.com/krizaka/orazaka-conversation-service) · [`orazaka-job-service`](https://github.com/krizaka/orazaka-job-service) · [`orazaka-knowledge-service`](https://github.com/krizaka/orazaka-knowledge-service) · [`orazaka-automation-service`](https://github.com/krizaka/orazaka-automation-service) · [`orazaka-edge`](https://github.com/krizaka/orazaka-edge) |
| Workspace path | `orazaka-libs/orazaka-build` |

## Build

**Inside the Orazaka workspace** (recommended — every dependency is built from source):

```bash
git clone https://github.com/krizaka/orazaka.git && cd orazaka
node scripts/workspace.mjs clone          # clones every repository at its workspace path
./mvnw -f orazaka-libs/orazaka-build/pom.xml verify
```

**Standalone** — upstream artifacts must be in `~/.m2` (built by the workspace) or resolvable from
GitHub Packages (`https://maven.pkg.github.com/krizaka/<repository>`, see the
[workspace README](https://github.com/krizaka/orazaka#consuming-packages)):

```bash
./mvnw verify
```

Requirements: JDK 21, Docker (Testcontainers integration tests).

## Governance

This repository follows the Orazaka governance contract — [AGENTS.md](https://github.com/krizaka/orazaka/blob/main/AGENTS.md)
in the workspace is normative; the local [AGENTS.md](AGENTS.md) only scopes it to this repository.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
