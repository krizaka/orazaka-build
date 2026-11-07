<!-- krizaka-header -->
<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/sovkora-dark.svg">
  <img src="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/sovkora-light.svg" alt="Sovkora" width="96">
</picture>

# Sovkora Build

**The AI that never leaves home.**

Parent POM (Spring Boot / Spring AI BOMs, plugin management, Sovkora BOM) and the shared governance test kit (ArchUnit rules, Testcontainers base) for every Sovkora JVM repository.

[![CI](https://github.com/krizaka/sovkora-build/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/sovkora-build/actions/workflows/ci.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/krizaka/sovkora-build/badge)](https://scorecard.dev/viewer/?uri=github.com/krizaka/sovkora-build)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Sovkora](https://img.shields.io/badge/part%20of-Sovkora-f67e23)](https://github.com/krizaka/sovkora#repositories)
[![Docs](https://img.shields.io/badge/docs-krizaka.com-f67e23)](https://www.krizaka.com/en/products/sovkora)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-build&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-build) [![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-build&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-build) [![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-build&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-build) [![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-build&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-build) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-build&metric=coverage)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-build)

[Documentation](https://www.krizaka.com/en/products/sovkora) · [Website](https://www.krizaka.com) · [Krizaka on GitHub](https://github.com/krizaka)

</div>
<!-- /krizaka-header -->

**Layer:** Foundation — reusable by any Krizaka application · **Version:** `1.0.0-SNAPSHOT` · **License:** Apache-2.0 ·
part of the [Sovkora platform](https://github.com/krizaka/sovkora) by [Krizaka](https://krizaka.com)

## What it provides

| Module | Artifact | Role |
|:---|:---|:---|
| `sovkora-parent` | `com.krizaka.sovkora:sovkora-parent` (pom) | Java 21, Spring Boot 4.0 + Spring AI 2.0 BOMs, the **Sovkora BOM** (every component at one platform version), Spotless (google-java-format), JaCoCo, Surefire/Failsafe, `dev`/`staging`/`prod` profiles, GitHub Packages distribution. |
| `sovkora-test-support` | `com.krizaka.sovkora:sovkora-test-support` | The governance kit: ArchUnit rules (`GovernanceRules`, naming, tiers, security, outbox, metering…), SQL seam rules, Testcontainers singletons (`AbstractContainerIntegrationTest`), workspace locator. |

## Use it

Every Sovkora JVM repository inherits the parent:

```xml
<parent>
    <groupId>com.krizaka.sovkora</groupId>
    <artifactId>sovkora-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <relativePath/>
</parent>
```

and runs the governance rules from its tests:

```xml
<dependency>
    <groupId>com.krizaka.sovkora</groupId>
    <artifactId>sovkora-test-support</artifactId>
    <scope>test</scope>
</dependency>
```

A new Krizaka application can adopt the same parent to get the same stack, versions and
quality gates with zero copy-paste.

### Workspace-wide rules

Some rules are **cross-repository** by nature (no pack name in engine code, every capability has an
executor, one settlement author…). They need every repository on disk, so they run inside the
Sovkora workspace ([`krizaka/sovkora`](https://github.com/krizaka/sovkora)) and are reported as *skipped* in a standalone
clone — never as passed.

## Position in the platform

| | |
|:---|:---|
| Depends on | _none — this repository is a root of the dependency graph._ |
| Used by | [`sovkora-contracts`](https://github.com/krizaka/sovkora-contracts) · [`krizaka-users`](https://github.com/krizaka/krizaka-build/tree/main/krizaka-users) · [`krizaka-notifications`](https://github.com/krizaka/krizaka-build/blob/main/krizaka-platform-kit/docs/notifications.md) · [`krizaka-billing`](https://github.com/krizaka/krizaka-build/tree/main/krizaka-billing) · [`sovkora-studio-service`](https://github.com/krizaka/sovkora-studio-service) · [`sovkora-engine`](https://github.com/krizaka/sovkora-engine) · [`sovkora-conversation-service`](https://github.com/krizaka/sovkora-conversation-service) · [`sovkora-job-service`](https://github.com/krizaka/sovkora-job-service) · [`sovkora-knowledge-service`](https://github.com/krizaka/sovkora-knowledge-service) · [`sovkora-automation-service`](https://github.com/krizaka/sovkora-automation-service) · [`sovkora-edge-service`](https://github.com/krizaka/sovkora-edge-service) |
| Workspace path | `orazaka-libs/orazaka-build` |

## Build

**Inside the Sovkora workspace** (recommended — every dependency is built from source):

```bash
git clone https://github.com/krizaka/sovkora.git && cd sovkora
node scripts/workspace.mjs clone          # clones every repository at its workspace path
./mvnw -f orazaka-libs/orazaka-build/pom.xml verify
```

**Standalone** — upstream artifacts must be in `~/.m2` (built by the workspace) or resolvable from
GitHub Packages (`https://maven.pkg.github.com/krizaka/<repository>`, see the
[workspace README](https://github.com/krizaka/sovkora#consuming-packages)):

```bash
./mvnw verify
```

Requirements: JDK 21, Docker (Testcontainers integration tests).

## Governance

This repository follows the Sovkora governance contract — [AGENTS.md](https://github.com/krizaka/sovkora/blob/main/AGENTS.md)
in the workspace is normative; the local [AGENTS.md](AGENTS.md) only scopes it to this repository.

<!-- krizaka:workflows:start -->
## Workflows

What runs in the Actions tab of this repository.

- **CI** (`ci.yml`): Lint, tests and build of the repository. Runs on push, on every pull request, on demand.
- **Dependabot auto-merge** (`dependabot-automerge.yml`): Auto-merges safe Dependabot updates. Runs on every pull request.
- **Merge resolution** (`merge-resolution.yml`): Fails a PR that drops what main added without a deletion-explained line. Runs on every pull request.
- **Scorecard** (`scorecard.yml`): OpenSSF Scorecard of the repository. Runs when branch rules change, on a schedule, on demand.
- **Security scan** (`security.yml`): Secret scan (gitleaks) and workflow scan (zizmor); both are required checks. Runs on every pull request, in the merge queue, on push.
<!-- krizaka:workflows:end -->

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

---

<sub>A Krizaka product · [krizaka.com](https://www.krizaka.com) · Apache-2.0</sub>
