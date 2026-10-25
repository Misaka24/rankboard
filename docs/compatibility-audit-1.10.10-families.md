# RankBoard 1.10.10 family build and smoke-test report

Date: 2026-10-11

**2026-10-13 update:** Cross-version feature synchronization, new archived JARs, four player-login feature regressions, a 26.3 shared-port test, and a fresh 34/34 startup matrix are documented in [the feature synchronization report](feature-sync-1.10.11.md). The paths below record the earlier build and test run.

**2026-10-15 update:** Version 1.10.11 was built for all four loader/family combinations and passed a fresh 34/34 startup plus dashboard HTTP matrix. Current artifacts and logs are listed in the same [feature synchronization report](feature-sync-1.10.11.md).

## Artifacts

Fabric and NeoForge each produce one direct JAR for `1.21.x` and one for `26.x`. The family JARs contain no embedded per-minor JARs.

The archive paths below are local timestamped build archives. This source push does not include the binary archives or local smoke logs.

| Loader | Family | Artifact | Archived build |
| --- | --- | --- | --- |
| Fabric | `1.21.x` | `rankboard-1.10.10+fabric+mc1.21.x.jar` | `mod-builds/20261011-191829` |
| Fabric | `26.x` | `rankboard-1.10.10+fabric+mc26.x.jar` | `mod-builds/20261011-191925` |
| NeoForge | `1.21.x` | `rankboard-1.10.10+neoforge+mc1.21.x.jar` | `mod-builds/20261011-192321` |
| NeoForge | `26.x` | `rankboard-1.10.10+neoforge+mc26.x.jar` | `mod-builds/20261011-192517` |

All four Gradle family builds succeeded. JAR metadata reports version `1.10.10`; the four filenames match the requested family scheme, and inspection found no `META-INF/jars/` embedded variants.

## Startup and web checks

Every supported point version passed an isolated dedicated-server startup check and an HTTP 200 check for the built-in dashboard:

| Loader | Minecraft versions | Result log |
| --- | --- | --- |
| Fabric `1.21.x` | `1.21` through `1.21.11` (12 versions) | `.tmp/fabric-direct-smoke-20261011-192755/test-results.json` |
| Fabric `26.x` | `26.1`, `26.1.1`, `26.1.2`, `26.2`, `26.3` (5 versions) | `.tmp/fabric-direct-smoke-20261011-193057/test-results.json` and `.tmp/fabric-direct-smoke-20261011-193126/test-results.json` |
| NeoForge `1.21.x` | `1.21` through `1.21.11` (12 versions) | `.tmp/neoforge-direct-smoke-20261011-193507/test-results.json` |
| NeoForge `26.x` | `26.1`, `26.1.1`, `26.1.2`, `26.2`, `26.3` (5 versions) | `.tmp/neoforge-direct-smoke-20261011-194531/test-results.json` |

That is **34/34 startup and dashboard checks passed**. These checks did not test a graphical client login on every point version.

Focused login and gameplay-command smoke tests on Minecraft `1.21.10` also passed for both loaders. They set/read an elytra score above the vanilla integer limit, verified kilometer formatting and incremental sidebar refresh, then reset the test override:

- Fabric: `.tmp/fabric-direct-smoke-20261011-195131/test-results.json`
- NeoForge: `.tmp/neoforge-direct-smoke-20261011-195215/test-results.json`

The pure flight-distance conversion test and the compact-join configuration/translation test passed. The project's Gradle `test` task reports `NO-SOURCE`, so those standalone tests were invoked explicitly.

The standalone `test-sidebar-flight-format.ps1` harness could not be completed with the available fixture classpath: the Fabric wrapper fixture's server JAR is obfuscated, and the patched NeoForge Minecraft JAR needs additional FML runtime classes not present in that fixture. This is a test-harness classpath limitation, not a server-startup failure; the focused 1.21.10 server/client smoke above covered kilometer formatting and delta updates on both loaders.

## Automatic build workflow

`.github/workflows/build.yml` builds the four family targets for pushes to `main`, pull requests to `main`, or manual dispatch. It validates that each job emits exactly one correctly versioned direct JAR and rejects embedded per-version variants. This workflow only uploads Actions build artifacts; external publication remains confined to the existing Release-triggered workflows.
