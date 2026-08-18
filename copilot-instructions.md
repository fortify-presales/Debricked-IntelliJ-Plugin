# Copilot Instructions: Debricked IntelliJ Plugin

## Project Overview

This is an IntelliJ IDEA plugin that integrates **Debricked** (open-source vulnerability scanning) directly into the IDE. Developers can authenticate with Debricked, select a repository, and view vulnerability findings organized by severity in a tool window.

**Status**: Phase 1 complete (authentication + basic findings display). Phase 2 in progress (branch selection + UI refinement).

---

## Key Resources

- **Implementation Details**: See [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md) for phases, architecture, decisions, and testing
- **API Reference**: [Debricked API Docs](https://docs.debricked.com/tools-and-integrations/debricked-apis)
- **Plugin Descriptor**: [`src/main/resources/META-INF/plugin.xml`](./src/main/resources/META-INF/plugin.xml)
- **Gradle Config**: [`build.gradle.kts`](./build.gradle.kts) (Kotlin 1.9.21, SDK 2023.2+)

---

## Before You Start

1. **Read [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md)** first
   - Understand the architecture and phases
   - Review key technical decisions (MessageBus, ModalityState, credential caching, pagination)
   - Check Phase 2 planned work if implementing new features

2. **Understand the Thread Model**
   - Background work: `ApplicationManager.executeOnPooledThread { }`
   - Settings: `Thread { ... isDaemon = true }.start()`
   - UI updates: `ApplicationManager.invokeLater { ... ModalityState.any() }`
   - **Never** use `GlobalScope.launch()` or block the EDT

3. **Credential Handling**
   - Credentials stored in: `DebrickedCredentialStore` (in-memory cache + PasswordSafe backend)
   - Always call `loadFromStorage()` on EDT during tool window init
   - Background threads read from memory; setters update memory + PasswordSafe on EDT
   - JWT never persisted—lives only in `DebrickedApiClient.cachedJwt`

4. **Settings Change Propagation**
   - Settings UI publishes `DebrickedSettingsNotifier.TOPIC` in `apply()`
   - All project services receive `onSettingsApplied()` callback via MessageBus
   - Tool window immediately emits `LOADING` state before background fetch
   - **Do not** use `ProjectManager.openProjects` lookup—unreliable

---

## Phase 2 Major Change: Findings Table Redesign

**Important**: Phase 2 includes a major UI redesign from severity-grouped tree to a flat, sortable table matching the Debricked web UI.

### Key Architectural Changes (Phase 2)

**1. Severity is now derived from CVSS, not an API field**
```kotlin
enum class Severity {
    // Computed from CVSS score, not from API
    CRITICAL, HIGH, MEDIUM, LOW, UNKNOWN
}

// In VulnerabilityFinding:
val cvssScore: Double? // Primary; ranges 0.0–10.0
fun calculatedSeverity(): Severity = when (cvssScore) {
    in 9.0..10.0 -> Severity.CRITICAL
    in 7.0..8.9 -> Severity.HIGH
    in 4.0..6.9 -> Severity.MEDIUM
    in 0.1..3.9 -> Severity.LOW
    else -> Severity.UNKNOWN
}
```

**2. Findings display is now a JTable, not a tree**
```kotlin
// Old (Phase 1): ColoredTreeCellRenderer + severity grouping
// New (Phase 2): JTable with columns: Name, CVSS, Dependencies, Review Status

val table = JTable(DefaultTableModel())
val sorter = TableRowSorter(table.model)
table.rowSorter = sorter
```

**3. Search/filter is real-time, not modal**
```kotlin
val searchField = JTextField()
val filter = RowFilter.regexFilter(".*${searchField.text}.*", NAME_COLUMN, DEPENDENCY_COLUMN)
sorter.setRowFilter(filter)
```

### When Implementing Phase 2 Features

#### Replacing the findings tree with a JTable

1. **Update Models**:
   - Change `VulnerabilityFinding.severity: Severity` to optional or computed
   - Add `cvssScore: Double?` field
   - Add `reviewStatus: ReviewStatus?` enum (UNEXAMINED, IN_REVIEW, ACCEPTED, REJECTED)
   - Add `dependencyName: String` and `dependencyEcosystem: String` for table display

2. **Create Table Column Definitions**:
   ```kotlin
   data class FindingColumn(
       val name: String,
       val modelIndex: Int,
       val preferredWidth: Int = 100,
       val sortable: Boolean = true
   )
   
   val columns = listOf(
       FindingColumn("Name", 0, 200),        // CVE-ID
       FindingColumn("CVSS", 1, 80),         // Score with badge
       FindingColumn("Dependencies", 2, 150), // Package(s)
       FindingColumn("Review Status", 3, 120),
       FindingColumn("Discovered", 4, 100)
   )
   ```

3. **Implement CVSS Badge Renderer**:
   ```kotlin
   class CvssTableCellRenderer : DefaultTableCellRenderer() {
       override fun getTableCellRendererComponent(...) {
           val cvss = value as? Double ?: return super.getTableCellRendererComponent(...)
           val severity = when (cvss) {
               in 9.0..10.0 -> "🔴"
               in 7.0..8.9 -> "🟠"
               in 4.0..6.9 -> "🟡"
               else -> "🔵"
           }
           text = "$cvss $severity"
           // Set foreground color based on severity
           foreground = when (cvss) {
               in 9.0..10.0 -> JBColor.RED
               in 7.0..8.9 -> JBColor.ORANGE
               in 4.0..6.9 -> JBColor.YELLOW
               else -> JBColor.BLUE
           }
           return this
       }
   }
   ```

4. **Add Row Sorting**:
   ```kotlin
   val sorter = TableRowSorter(table.model)
   table.rowSorter = sorter
   
   // Allow sorting by clicking column headers (default JTable behavior)
   // Disable sorting for Dependencies column (not meaningful)
   sorter.setSortable(DEPENDENCY_COLUMN, false)
   ```

5. **Add Real-Time Search/Filter**:
   ```kotlin
   val searchField = JTextField()
   searchField.document.addDocumentListener(object : DocumentListener {
       override fun insertUpdate(e: DocumentEvent) = applyFilter()
       override fun removeUpdate(e: DocumentEvent) = applyFilter()
       override fun changedUpdate(e: DocumentEvent) = applyFilter()
       
       fun applyFilter() {
           val text = searchField.text.trim()
           val filter = if (text.isEmpty()) {
               null
           } else {
               RowFilter.regexFilter("(?i).*${Pattern.quote(text)}.*", NAME_COLUMN, DEPENDENCY_COLUMN)
           }
           sorter.setRowFilter(filter)
       }
   })
   ```

6. **Update API Client**:
   - Extract `cvss` (numeric) from vulnerability response
   - Extract `reviewStatus` if available in response
   - Parse `dependencies` array into comma-separated display format

#### Handling Review Status (if available in API)

Review Status may not be immediately available in `/vulnerabilities` endpoint. Options:
1. Fetch separately via `GET /api/{version}/open/vulnerability/{vulnerabilityId}/review-status` (slower, batched)
2. Leave blank initially, populate on-demand when user clicks row
3. Mark as "TBD" until API is extended

Check `debricked-api.json` for current response schema before implementing.

## When Implementing Features

### Adding a new setting (e.g., branch preference per repository)

1. Add field to `DebrickedSettingsManager` + `@State` persistence
2. Add input in `DebrickedSettingsConfigurable` UI
3. Publish `DebrickedSettingsNotifier.TOPIC` in `apply()`
4. Subscribe in `DebrickedPluginManager.init()` if refresh is needed
5. Read value in `DebrickedApiClient` when calling Debricked API

**Example**: See Phase 2 branch selection in [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md#phase-2)

### Adding a new UI panel (e.g., details side-pane)

1. Create class in `src/main/kotlin/.../ui/Panels.kt` extending `JPanel`
2. Use `GridBagLayout` for alignment or `BorderLayout` for sectioning
3. Apply theme-aware colors: `JBColor(lightColor, darkColor)`
4. Register update listener in `DebrickedToolWindowContent.onFindingsUpdated()`
5. Test in `./gradlew.bat runIde`

**Example**: See `RepositoryBar` and `DebrickedFindingsPanel` in `Panels.kt`

### Adding API pagination or new endpoint

1. Add method to `DebrickedApiClient` (handle JWT auth, error responses)
2. Use `rowsPerPage=100` if paginating; loop until `size < rowsPerPage`
3. Parse nested JSON responses carefully (Debricked wraps fields in objects)
4. Cache JWT in `DebrickedApiClient.cachedJwt` to avoid re-auth on every call
5. Test locally with real credentials via `./gradlew.bat runIde`

**Example**: See `getVulnerabilities()` pagination loop in `DebrickedApiClient.kt`

### Handling modal dialog blockers

**Problem**: `ApplicationManager.invokeLater { }` is silently suppressed while Settings dialog is open

**Solution**: Always use `ModalityState.any()`
```kotlin
ApplicationManager.getApplication().invokeLater({
    // update UI
}, ModalityState.any())
```

**Example**: See all `invokeLater` calls in `DebrickedSettingsConfigurable.kt`

---

## Testing Checklist

Before submitting changes:

- [ ] Code builds: `.\gradlew.bat build -x test` (or `.\gradlew.bat build` if unit tests added)
- [ ] IDE launches: `.\gradlew.bat runIde 2>$null`
- [ ] Unit tests pass: `.\gradlew.bat test` (if modified tested components)
- [ ] Manual testing completed (see [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md#testing--validation) for checklist)
- [ ] No blocking EDT calls (no `HttpURLConnection.connect()`, no `PasswordSafe.getPassword()` without EDT guard)
- [ ] Settings changes properly propagate via MessageBus
- [ ] Error messages are user-friendly (not stack traces)
- [ ] All `invokeLater` calls use `ModalityState.any()`

### Adding Unit Tests (Phase 2)

When adding tests for critical logic:

1. **Identify testable component**: API client, settings manager, credential store, domain models (good); UI components (skip—manual test instead)

2. **Add test dependencies** to `build.gradle.kts`:
   ```gradle
   testImplementation 'junit:junit:4.13.2'
   testImplementation 'org.mockito:mockito-core:5.2.0'
   ```

3. **Write focused tests** (see examples in [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md#high-priority-unit-tests)):
   - Test JSON parsing, especially nested fields
   - Test pagination loop behavior
   - Test credential cache to prevent redundant PasswordSafe calls
   - Test persistence across IDE restarts

4. **Run tests**:
   ```bash
   .\gradlew.bat test --tests YourTestClassName
   ```

5. **Coverage targets**: 80%+ for business logic, skip UI (manual testing preferred)

---

## Key Files to Understand

| File | Purpose | When to Edit |
|------|---------|--------------|
| `DebrickedPluginManager.kt` | Orchestrator, listener callbacks, state machine | Adding refresh logic, new listeners |
| `DebrickedApiClient.kt` | HTTP + JWT auth, Debricked API calls | Adding endpoints, fixing parse errors, extracting CVSS/reviewStatus |
| `DebrickedSettingsConfigurable.kt` | Settings UI, publishes MessageBus topic | Adding new settings, fixing modal blockers |
| `DebrickedToolWindowFactory.kt` | Tool window container, three content areas | Changing layout, adding new UI sections |
| `Panels.kt` | UI components (bar, tree, renderers) | **[Phase 2]** Replace tree with JTable; add column renderers |
| `DebrickedCredentialStore.kt` | Credential cache (memory + PasswordSafe) | Fixing auth issues, adding new credential types |
| `Models.kt` | Domain models | **[Phase 2]** Update VulnerabilityFinding (add cvssScore, reviewStatus, remove severity) |
| `IMPLEMENTATION_PLAN.md` | Phases, architecture, decisions | Reference for design questions, Phase 2 table spec |

---

## Common Pitfalls

❌ **Don't**:
- Call `PasswordSafe.getPassword()` from background thread (deadlock on Windows)
- Use `GlobalScope.launch()` or blocking HTTP on EDT
- Assume `ProjectManager.openProjects` will be reliable
- Update UI without `ModalityState.any()` while modal dialog is open
- Store JWT in PasswordSafe (do that for access token/password only)
- Forget to publish `DebrickedSettingsNotifier.TOPIC` when settings change
- Parse Debricked API responses as flat JSON (they're nested; use `textValue()` helper)
- **[Phase 2]** Use severity enum from API (derive from CVSS score client-side)
- **[Phase 2]** Forget to use `TableRowSorter` when adding sorting to JTable
- **[Phase 2]** Update JTable model directly while filter/sorter is active (causes IndexOutOfBoundsException)
- **[Phase 2]** Add document listeners without checking `searchField.text.isEmpty()` before applying regex filter

✅ **Do**:
- Use `ApplicationManager.executeOnPooledThread { }` for background work
- Use in-memory cache for credentials; load once on EDT
- Subscribe to MessageBus topics for settings changes
- Test all changes with `./gradlew.bat runIde` before committing
- Include `ModalityState.any()` on all `invokeLater` calls
- Verify with real Debricked API (test locally first)
- **[Phase 2]** Compute severity from CVSS range (9-10 = CRITICAL, 7-8.9 = HIGH, etc.)
- **[Phase 2]** Use `TableRowSorter` for column sorting (standard IntelliJ pattern)
- **[Phase 2]** Convert model row index to view row index when handling selection: `table.convertRowIndexToModel(selectedRow)`
- **[Phase 2]** Use regex filter with `Pattern.quote()` to escape special characters in search text

---

## Questions or Issues?

- **Architecture/Design**: See [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md#decisions--rationale)
- **API Behavior**: Check [Debricked API Docs](https://docs.debricked.com/tools-and-integrations/debricked-apis)
- **IntelliJ Platform**: Refer to [JetBrains Plugin SDK](https://plugins.jetbrains.com/docs/intellij/)
- **Gradle Build**: Check [`build.gradle.kts`](./build.gradle.kts) and run `./gradlew.bat build --info`

---

**Last Updated**: Phase 1 complete, Phase 2 underway (branch selection + UI polish)
