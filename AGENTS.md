# orazaka-build — Governance scope (agent-neutral)

> This repository is one component of the **Orazaka platform**. The normative contract is
> [`AGENTS.md`](https://github.com/krizaka/orazaka/blob/main/AGENTS.md) at the root of the Orazaka workspace
> ([`krizaka/orazaka`](https://github.com/krizaka/orazaka)), together with its `.agent/rules/*`. When this repository
> is cloned inside the workspace (`orazaka-libs/orazaka-build`), that contract is loaded first and applies
> without exception. **No rule lives here** — this file only scopes it.

## Scope of this repository

- **Role:** Parent POM (Spring Boot / Spring AI BOMs, plugin management, Orazaka BOM) and the shared governance test kit (ArchUnit rules, Testcontainers base) for every Orazaka JVM repository.
- **Layer:** Foundation — reusable by any Krizaka application
- **Depends on:** nothing — never on another repository's Tier-3 implementation (AGENTS.md §2, [SEAM-002]).
- **Workspace path:** `orazaka-libs/orazaka-build`

## Definition of done

1. `./mvnw verify` is green (unit + Testcontainers ITs + this repository's `*GovernanceTest`).
2. Inside the workspace, the cross-repository rules are green as well
   (`./mvnw -f orazaka-libs/orazaka-build/pom.xml verify` from the workspace root).
3. Spotless (google-java-format) passes — it is bound to `validate`.
