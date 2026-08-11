# GitHub Copilot Instructions: Debricked IntelliJ IDEA Plugin

## Purpose

You are implementing an IntelliJ IDEA plugin that integrates with Fortify Software Composition Analysis (Debricked), to show open source vulnerability findings directly inside the IDE.

The plugin should prioritize pulling existing repository, branch, and commit findings from Debricked rather than always running a local scan. It should enrich Debricked findings with IntelliJ-specific context such as modules, dependency declarations, transitive dependency paths, stale workspace state, and fix suggestions.

The implementation language should be Kotlin using the IntelliJ Platform SDK.

---

## Product Goals

Build an IntelliJ plugin that allows developers to:

1. Connect an IntelliJ project to a Debricked repository.
2. Select the correct Debricked repository explicitly on first setup.
3. Automatically detect the current Git branch and commit.
4. Pull vulnerability findings from Debricked for the exact commit when available.
5. Fall back to the latest scan on the current branch if the exact commit has not been scanned.
6. Fall back to the default branch if the current branch has not been scanned.
7. Detect local dependency changes and warn when remote findings may be stale.
8. Build a dependency-to-module index for Maven, Gradle, and npm projects.
9. Group findings by IntelliJ module.
10. Support direct and transitive dependencies.
11. Show dependency introduction paths for transitive vulnerabilities.
12. Provide fix suggestions and quick actions where possible.
13. Validate newly added or modified dependencies, including dependencies added by AI assistants.
14. Offer optional local Debricked CLI scans when remote data is missing or stale.

---

## Architecture Overview

The plugin should use the following high-level architecture:

```text
IntelliJ Project
   │
   ├── Git Context Resolver
   │      ├── Remote URL
   │      ├── Branch name
   │      ├── Commit SHA
   │      └── Dirty dependency files
   │
   ├── Debricked Repository Mapping
   │      ├── Explicit repository selection
   │      ├── Stored repository ID
   │      └── Optional auto-suggest based on Git remote/name
   │
   ├── Debricked API Client
   │      ├── Repository lookup
   │      ├── Branch lookup
   │      ├── Commit findings lookup
   │      ├── Latest branch findings
   │      └── Default branch findings
   │
   ├── Dependency Index Service
   │      ├── Maven provider
   │      ├── Gradle provider
   │      ├── npm provider
   │      ├── Dependency-to-module index
   │      └── Module-to-dependency index
   │
   ├── Findings Correlator
   │      ├── Match Debricked component to dependency key
   │      ├── Attach affected modules
   │      ├── Attach direct/transitive metadata
   │      └── Attach dependency paths
   │
   ├── UI Layer
   │      ├── Tool window
   │      ├── Repository connection setup
   │      ├── Findings tree grouped by module
   │      ├── Staleness banners
   │      ├── Quick fixes
   │      └── Open in Debricked action
   │
   └── Optional CLI Scanner
          ├── Run local scan
          ├── Parse local results
          └── Merge local results into UI
```

---

## Repository Selection

Do not assume the Debricked repository name always matches the Git repository name.

Debricked repositories may be created with the same name as the Git repository by default, but users can rename them. Therefore, the plugin should require explicit repository selection on first setup.

### First Launch Flow

1. Detect the local Git remote URL.
2. Query Debricked repositories available to the authenticated user.
3. Suggest likely matches based on:
   - Git repository name
   - Git remote URL
   - Organization or namespace
   - Similarity score
4. Preselect the best candidate if confidence is high.
5. Require the user to click **Connect**.
6. Store the selected Debricked repository ID in project-level settings.

Example UI:

```text
Debricked Repository Not Configured

Detected Git Repository:
customer-api

Possible Debricked Repositories:

○ customer-api
○ Customer API Production
○ Customer API Develop
○ Platform Monorepo

[Connect]
```

### Repository Configuration Model

```kotlin
data class RepositoryConfiguration(
    val organizationId: String,
    val repositoryId: String,
    val repositoryName: String,
    val defaultBranch: String?
)
```

Persist this configuration using IntelliJ project settings.

---

## Branch and Commit Awareness

Debricked supports multiple branches and records individual commits. The plugin should therefore resolve findings using the most specific available context.

### Lookup Priority

Use this order:

1. Exact commit findings for the selected Debricked repository.
2. Latest scanned commit on the current Git branch.
3. Latest findings for the default branch.
4. Empty state with option to run a local scan.

### Git Context

Use Git4Idea where possible:

```kotlin
val repository = GitRepositoryManager
    .getInstance(project)
    .repositories
    .firstOrNull()

val branchName = repository
    ?.currentBranch
    ?.name

val commitSha = repository
    ?.currentRevision
```

### Scan Context Model

```kotlin
data class DebrickedScanContext(
    val repositoryId: String,
    val repositoryName: String,
    val branchName: String?,
    val commitSha: String?,
    val scanId: String?,
    val displayedCommitSha: String?,
    val isExactCommitMatch: Boolean,
    val fallbackReason: FallbackReason?
)

enum class FallbackReason {
    COMMIT_NOT_SCANNED,
    BRANCH_NOT_SCANNED,
    DEFAULT_BRANCH_USED,
    NO_REMOTE_DATA
}
```

### Findings Resolution Pseudocode

```kotlin
fun resolveFindings(
    repositoryId: String,
    branch: String?,
    commitSha: String?
): FindingsResult {

    if (commitSha != null) {
        val commitFindings = debrickedApi.getFindingsByCommit(
            repositoryId,
            commitSha
        )

        if (commitFindings != null) {
            return FindingsResult.exactCommit(commitFindings)
        }
    }

    if (branch != null) {
        val latestBranchFindings = debrickedApi.getLatestFindingsForBranch(
            repositoryId,
            branch
        )

        if (latestBranchFindings != null) {
            return FindingsResult.branchFallback(latestBranchFindings)
        }
    }

    val defaultBranchFindings = debrickedApi.getLatestFindingsForDefaultBranch(
        repositoryId
    )

    if (defaultBranchFindings != null) {
        return FindingsResult.defaultBranchFallback(defaultBranchFindings)
    }

    return FindingsResult.empty()
}
```

### UI Rules

If findings are from the exact current commit:

```text
Showing findings for current commit abc123.
```

If findings are from an older commit on the same branch:

```text
No Debricked findings found for current commit abc123.
Showing latest scanned commit on feature/new-auth: 9f8e7d6.
```

If falling back to default branch:

```text
No Debricked findings found for branch feature/new-auth.
Showing findings from main.
```

---

## Handling Uncommitted Changes

The plugin must distinguish between remote Debricked findings and the local workspace state.

### Security-Relevant Files

Treat changes to these files as potentially affecting dependency findings:

```text
pom.xml
build.gradle
build.gradle.kts
settings.gradle
settings.gradle.kts
gradle.lockfile
package.json
package-lock.json
yarn.lock
pnpm-lock.yaml
requirements.txt
poetry.lock
Pipfile
Pipfile.lock
go.mod
go.sum
Cargo.toml
Cargo.lock
```

The first version of the plugin should focus on Maven, Gradle, and npm. Other ecosystems can be detected for future extensibility.

### Findings State

```kotlin
enum class FindingsState {
    CURRENT,
    STALE_DEPENDENCY_CHANGES,
    STALE_COMMIT,
    NO_REMOTE_RESULTS
}
```

### UX for Dependency Changes

If dependency files have uncommitted changes:

```text
Dependency files have changed locally.
Remote Debricked findings may not reflect the current workspace.

Changed files:
- pom.xml
- package-lock.json

[Run Local Scan]
[Refresh Findings]
```

Do not silently show old findings as if they represent the current workspace.

### Advanced Local Comparison

In a later phase, compare the dependency graph from the current workspace with Debricked findings:

```text
Reported by Debricked:
commons-io 2.6 is vulnerable

Current workspace:
commons-io 2.11.0

Status:
Possibly fixed locally. Run a local scan to verify.
```

---

## Dependency Indexing

The plugin should build a local dependency index to map Debricked component findings to IntelliJ modules.

### Core Data Model

```kotlin
data class DependencyKey(
    val ecosystem: Ecosystem,
    val groupId: String?,
    val artifactId: String,
    val version: String
)

enum class Ecosystem {
    MAVEN,
    GRADLE,
    NPM
}
```

For Maven and Gradle, use:

```text
groupId:artifactId:version
```

For npm, use:

```text
npm:packageName:version
```

### Occurrence Model

```kotlin
data class DependencyOccurrence(
    val moduleName: String,
    val dependency: DependencyKey,
    val direct: Boolean,
    val dependencyPath: List<DependencyKey>,
    val declarationFile: VirtualFile?,
    val declarationLine: Int?
)
```

### Indexes

Maintain both directions:

```kotlin
private val dependencyToOccurrences =
    ConcurrentHashMap<DependencyKey, MutableList<DependencyOccurrence>>()

private val moduleToDependencies =
    ConcurrentHashMap<String, MutableSet<DependencyKey>>()
```

Why both?

- `dependencyToOccurrences` maps Debricked findings to modules.
- `moduleToDependencies` supports fast incremental updates when one module changes.

---

## Maven Support

Prefer IntelliJ Maven APIs over manual XML parsing.

### Provider Interface

```kotlin
interface DependencyProvider {
    fun supports(module: Module): Boolean
    fun buildIndex(module: Module): List<DependencyOccurrence>
}
```

### Maven Provider

Use Maven project models where available:

```kotlin
val mavenProjects = MavenProjectsManager
    .getInstance(project)
    .projects

mavenProjects.forEach { mavenProject ->
    mavenProject.dependencies.forEach { dep ->
        val key = DependencyKey(
            ecosystem = Ecosystem.MAVEN,
            groupId = dep.groupId,
            artifactId = dep.artifactId,
            version = dep.version ?: ""
        )
    }
}
```

The resolved dependency graph should include transitive dependencies where the API gives access to them.

For transitive dependencies, preserve enough information to display how the vulnerable component was introduced.

---

## Gradle Support

Gradle projects should use resolved dependency information rather than parsing `build.gradle` text.

Target configurations:

```text
compileClasspath
runtimeClasspath
implementation
api
testRuntimeClasspath
```

Prefer IntelliJ Gradle or external system APIs where feasible. If exact resolved path information is difficult to obtain in version 1, begin by mapping resolved components to modules, then add dependency paths later.

---

## npm Support

npm support should be added through the same provider abstraction.

### Detect Node Projects

A module or content root can be treated as an npm project if it contains:

```text
package.json
package-lock.json
yarn.lock
pnpm-lock.yaml
```

### Index npm Dependencies

For npm, package lockfiles usually contain resolved dependency data. Support these files initially:

1. `package-lock.json`
2. `yarn.lock`
3. `pnpm-lock.yaml`

Start with `package-lock.json` because it is JSON and easiest to parse reliably.

### npm Dependency Key

```kotlin
val key = DependencyKey(
    ecosystem = Ecosystem.NPM,
    groupId = null,
    artifactId = packageName,
    version = version
)
```

### npm Workspaces

Support npm, yarn, and pnpm workspaces in a later phase.

For monorepos, map each workspace to the nearest IntelliJ module where possible.

Example:

```text
repo/
├── package.json
├── apps/
│   ├── web/
│   └── admin/
└── packages/
    ├── ui/
    └── shared/
```

The index may map one vulnerable package to multiple workspaces/modules:

```text
lodash@4.17.20 -> web, admin, shared
```

---

## Handling Transitive Dependencies

Do not try to calculate transitive dependency graphs from raw manifest files if a resolved graph is available.

Use Maven, Gradle, or npm lockfile/resolved model data.

### Direct vs Transitive

For every occurrence, set:

```kotlin
val direct: Boolean
```

Direct means the dependency is declared in the module manifest.

Transitive means the dependency is introduced through another dependency.

### Dependency Path

Store the introduction path:

```kotlin
data class DependencyOccurrence(
    val moduleName: String,
    val dependency: DependencyKey,
    val direct: Boolean,
    val dependencyPath: List<DependencyKey>,
    val declarationFile: VirtualFile?,
    val declarationLine: Int?
)
```

Example:

```text
spring-boot-starter-web
  -> spring-web
    -> jackson-databind
```

For a finding on `jackson-databind`, show:

```text
Introduced Through:
spring-boot-starter-web -> spring-web -> jackson-databind
```

### Navigation for Transitives

For transitive dependencies, navigate to the nearest declared dependency in the path, not necessarily the vulnerable component itself.

Example:

```text
Vulnerable:
jackson-databind

Declared dependency to open:
spring-boot-starter-web
```

---

## Findings Correlation

Debricked findings should be normalized into the same dependency key format used by dependency providers.

### Finding Model

```kotlin
data class VulnerabilityFinding(
    val id: String,
    val ecosystem: Ecosystem,
    val packageName: String,
    val groupId: String?,
    val version: String,
    val severity: Severity,
    val fixedVersion: String?,
    val description: String?,
    val scanContext: DebrickedScanContext
)

enum class Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
}
```

### Correlated Finding

```kotlin
data class CorrelatedFinding(
    val finding: VulnerabilityFinding,
    val occurrences: List<DependencyOccurrence>,
    val fixSuggestion: FixSuggestion?,
    val confidence: FindingConfidence
)

enum class FindingConfidence {
    VERIFIED_CURRENT_COMMIT,
    VERIFIED_BRANCH_LATEST,
    FALLBACK_DEFAULT_BRANCH,
    STALE_LOCAL_DEPENDENCY_CHANGES,
    UNKNOWN
}
```

### Correlation Process

```text
Debricked Finding
   ↓
Normalize to DependencyKey
   ↓
Lookup DependencyOccurrence list
   ↓
Attach modules and dependency paths
   ↓
Group by module for display
```

---

## Grouping Findings by Module

The UI should primarily group findings by module.

Example:

```text
Debricked

▼ service-a
    CRITICAL
    log4j-core 2.14.1
    Fix: 2.17.2

    HIGH
    commons-io 2.6
    Fix: 2.11.0

▼ frontend-web
    HIGH
    lodash 4.17.20
    Fix: 4.17.21

▼ shared
    No vulnerabilities
```

### Tree Structure

```text
Root
 ├─ Module
 │   ├─ Severity
 │   │   └─ Finding
 │   └─ Severity
 │       └─ Finding
 └─ Module
```

### Basic Swing Tree Example

```kotlin
val root = DefaultMutableTreeNode("Debricked")

groupedFindings.forEach { moduleName, findings ->
    val moduleNode = DefaultMutableTreeNode(moduleName)

    findings
        .groupBy { it.finding.severity }
        .forEach { severity, severityFindings ->
            val severityNode = DefaultMutableTreeNode(severity)

            severityFindings.forEach { finding ->
                severityNode.add(DefaultMutableTreeNode(finding))
            }

            moduleNode.add(severityNode)
        }

    root.add(moduleNode)
}
```

---

## Fix Suggestions

Fix suggestions should come primarily from Debricked where available.

### Fix Suggestion Model

```kotlin
data class FixSuggestion(
    val affectedDependency: DependencyKey,
    val upgradeTarget: DependencyKey?,
    val suggestedVersion: String?,
    val confidence: FixConfidence,
    val explanation: String,
    val dependencyPath: List<DependencyKey>,
    val directDependency: Boolean
)

enum class FixConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
}
```

### Direct Dependency Fix

If a vulnerable direct dependency has a known fixed version:

```text
commons-io 2.6
Suggested fix: upgrade to 2.11.0
```

### Transitive Dependency Fix

If the vulnerable dependency is transitive:

```text
Vulnerable component:
jackson-databind 2.12.0

Introduced through:
spring-boot-starter-web -> spring-web -> jackson-databind

Suggested fix:
Upgrade spring-boot-starter-web if a safe version is known.
```

If Debricked provides a safe version for the vulnerable transitive component but not the parent dependency, show the result as lower confidence:

```text
Safe version exists for jackson-databind: 2.15.4
Review parent dependency upgrade path.
```

### Major Version Changes

Flag major version changes as requiring review:

```text
Review required: suggested fix changes major version 2.x -> 3.x.
```

---

## Quick Fixes

Implement IntelliJ quick fixes gradually.

### Phase 1

Tool window buttons:

```text
[Open Dependency]
[Open in Debricked]
[Copy Suggested Version]
```

### Phase 2

Add local quick fixes for direct dependencies:

```kotlin
class UpgradeDependencyQuickFix(
    private val newVersion: String
) : LocalQuickFix {

    override fun getName(): String =
        "Upgrade dependency to $newVersion"

    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor
    ) {
        // Use PSI APIs to update pom.xml, build.gradle, or package.json.
    }
}
```

### Important Rules

- Use PSI APIs where possible rather than raw text replacement.
- Preserve formatting.
- Support undo.
- Do not automatically apply fixes for transitives unless the correct parent upgrade is known.
- Warn when a fix requires a major version upgrade.

---

## Detecting Dependency Changes, Including AI Assistant Changes

Do not try to detect whether an AI assistant made a change. Instead, detect dependency changes regardless of source.

A dependency change may come from:

- Human editing
- GitHub Copilot
- JetBrains AI
- Cursor
- Merge conflict resolution
- Refactoring
- Git checkout or cherry-pick

All should be treated the same.

### Dependency Change Model

```kotlin
data class DependencyChange(
    val dependency: DependencyKey,
    val previousVersion: String?,
    val newVersion: String?,
    val changeType: DependencyChangeType,
    val file: VirtualFile?,
    val timestampMillis: Long
)

enum class DependencyChangeType {
    ADDED,
    REMOVED,
    UPDATED
}
```

### Real-Time Validation

When a dependency file changes:

1. Debounce file events.
2. Rebuild the dependency index for the affected module.
3. Diff old dependencies vs new dependencies.
4. For added or upgraded dependencies, query Debricked if package-level lookup is available.
5. Otherwise mark findings as stale and offer local scan.

Example warning:

```text
Dependency change detected:
lodash 4.17.20

Debricked reports known vulnerabilities for this version.
Suggested version: 4.17.21
```

### Pre-Commit Review

Add a pre-commit check in a later phase.

Example:

```text
Security Review

Added:
- lodash 4.17.20

Status:
HIGH vulnerability found

Recommended:
4.17.21

[Fix Before Commit]
[Commit Anyway]
```

---

## Debouncing File Events

Dependency files can trigger many events in rapid succession. Use debounce and coalescing.

### Service Example

```kotlin
@Service(Service.Level.PROJECT)
class DependencyIndexService(
    private val project: Project
) {
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    private val pendingModules =
        ConcurrentHashMap.newKeySet<Module>()

    fun scheduleModuleRefresh(module: Module) {
        pendingModules.add(module)

        alarm.cancelAllRequests()

        alarm.addRequest(
            {
                processPendingModules()
            },
            1000
        )
    }

    private fun processPendingModules() {
        val modules = pendingModules.toList()
        pendingModules.clear()

        modules.forEach { updateModule(it) }
    }

    private fun updateModule(module: Module) {
        removeExistingEntries(module)
        val dependencies = extractDependencies(module)
        addEntries(module, dependencies)
    }
}
```

### VFS Listener

```kotlin
class DependencyFileListener(
    private val project: Project
) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val indexService = project.service<DependencyIndexService>()

        events.forEach { event ->
            val path = event.path

            if (!isDependencyFile(path)) {
                return@forEach
            }

            val file = LocalFileSystem.getInstance()
                .findFileByPath(path)
                ?: return@forEach

            val module = fileIndex.getModuleForFile(file)
                ?: return@forEach

            indexService.scheduleModuleRefresh(module)
        }
    }
}
```

---

## Optional Local CLI Scan

The plugin should not require local scanning by default, but it should offer it in these situations:

1. No Debricked findings exist for the current commit.
2. No findings exist for the current branch.
3. Dependency files have uncommitted changes.
4. The user explicitly clicks **Run Local Scan**.

### CLI Service

```kotlin
class DebrickedCliService {

    fun scan(projectPath: Path): ProcessOutput {
        val commandLine = GeneralCommandLine(
            "debricked",
            "scan",
            "--json"
        )

        commandLine.workDirectory = projectPath.toFile()

        return ExecUtil.execAndGetOutput(commandLine)
    }
}
```

### UI Rules

If the CLI is not installed:

```text
Debricked CLI was not found.
Install the CLI or continue using remote repository findings.
```

Do not block repository findings if the CLI is unavailable.

---

## Authentication

Support API token authentication first.

Settings should include:

```text
Debricked API URL
API token
Organization
Selected repository
```

Store secrets securely using IntelliJ password-safe APIs. Do not write API tokens into project files.

---

## Caching

Cache findings by repository, branch, and commit.

```kotlin
data class FindingsCacheKey(
    val repositoryId: String,
    val branchName: String?,
    val commitSha: String?
)
```

Cache dependency index by module.

Invalidate cache when:

- Branch changes
- Commit changes
- Dependency files change
- Repository selection changes
- User clicks refresh
- Debricked scan completion is detected

---

## Tool Window UX

The tool window should include:

```text
Debricked

Repository: customer-api
Branch: feature/new-auth
Current Commit: abc123
Displayed Scan: abc123
Status: Showing findings for current commit

[Refresh Findings]
[Run Local Scan]
[Open in Debricked]
[Settings]

▼ service-a
    CRITICAL log4j-core 2.14.1
    Fix: 2.17.2

▼ frontend-web
    HIGH lodash 4.17.20
    Fix: 4.17.21
```

When stale:

```text
⚠ Dependency files have changed locally.
Remote Debricked findings may be outdated.

[Run Local Scan]
```

---

## Implementation Phases

### Phase 1: Remote Findings MVP

Implement:

- Authentication
- Repository selection
- Git branch and commit detection
- Pull findings by exact commit
- Fallback to branch and default branch
- Basic tool window
- Group findings by repository-level data only

### Phase 2: Dependency Index

Implement:

- Maven provider
- Gradle provider
- npm package-lock provider
- Dependency-to-module mapping
- Group findings by IntelliJ module
- Basic navigation to dependency files

### Phase 3: Staleness and Local Changes

Implement:

- Dependency file VFS listener
- Debounced index updates
- Dirty dependency file detection
- Stale findings banners
- Optional local CLI scan

### Phase 4: Transitives and Fixes

Implement:

- Dependency path display
- Direct/transitive classification
- Fix suggestions
- Tool window fix buttons
- Quick fixes for direct Maven/npm dependencies

### Phase 5: AI/Change Validation and Pre-Commit

Implement:

- Dependency diff engine
- Package-level vulnerability validation if supported by Debricked API
- Pre-commit security review
- Warnings for newly added vulnerable dependencies

---

## Coding Guidelines

- Use Kotlin.
- Prefer IntelliJ services for long-lived state.
- Do not block the UI thread.
- Use background tasks or coroutines for API calls and scans.
- Use IntelliJ PSI APIs for edits.
- Use Git4Idea APIs for Git state.
- Use IntelliJ Maven/Gradle APIs where possible.
- Treat CLI scanning as optional.
- Treat Debricked remote findings as the default source of truth.
- Always show whether findings are exact, branch fallback, default branch fallback, or stale.
- Do not silently mix remote findings with local uncommitted dependency changes.

---

## Non-Goals for Initial Version

Do not implement these in the first version unless explicitly requested:

- Full custom vulnerability database.
- Mandatory local scanning on every save.
- AI provenance detection.
- Automatic transitive dependency upgrades without confidence.
- Automatic changes to dependency files without user action.
- Full support for every package ecosystem.

---

## Expected Developer Experience

The final plugin should feel like this:

1. Developer opens project.
2. Plugin asks them to connect to a Debricked repository.
3. Plugin detects branch and commit.
4. Plugin pulls Debricked findings for that commit.
5. Findings are grouped by module.
6. Transitive findings show dependency paths.
7. Fix suggestions appear where available.
8. If dependency files change, the plugin warns that findings may be stale.
9. The developer can run a local scan or wait for CI/Debricked to scan the commit.
10. Newly added vulnerable dependencies are flagged before commit where possible.

The plugin should prioritize accuracy, transparency, and developer trust over aggressive automation.
