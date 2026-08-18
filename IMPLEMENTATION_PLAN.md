# Debricked IntelliJ IDEA Plugin — Implementation Plan

## Executive Summary

This document provides a concrete, phased implementation plan for the Debricked IntelliJ IDEA plugin. The plugin integrates Fortify Software Composition Analysis (Debricked) directly into JetBrains IDEs to display open-source vulnerability findings, dependencies, and license information in real-time as developers code.

The implementation is divided into six phases using a **vertical-slice model** where each tab phase is not complete until both list and meaningful details are delivered:
- **Phase 1** (Largely complete): Vulnerabilities tab foundation + shared repo/branch selector, tabbed architecture and caching
- **Phase 2** (Planned): Local scan orchestration via Debricked CLI
- **Phase 3** (Planned): Dependencies tab (inventory + details)
- **Phase 4** (Planned): Licenses tab (governance + details)
- **Phase 5** (Planned): Dashboard tab (summary & navigation)
- **Phase 6** (Planned): Advanced cross-tab rich investigation (deep nested details, evidence views, editor reports)

See **UI Style Guide & Interaction Gestures** and **Minimum Information Contract** below — both are binding for all phases.

### Update: Server-driven vulnerabilities query behavior (2026-08-17)

The Vulnerabilities tab now follows Debricked-style query behavior:

1. Search input is sent to the backend on key input using a 250ms debounce.
2. In-flight requests are superseded by newer query states to avoid stale-result overwrite.
3. Vulnerabilities are fetched per page/query state (search, page, rows per page, sort column, order), not by eagerly loading all pages.
4. Paging controls (prev/next + page size) drive backend requests.
5. Count/status text follows server-backed paging semantics (showing range and total when available).
6. Query results are cached by repository/branch/query key for responsive back/forward and repeated lookups.

---

## Product Goals

The plugin allows developers to:

**Security Findings (Phase 1)**
1. Authenticate with Debricked (access token or username/password)
2. Select a Debricked repository to monitor
3. Select a Git branch (dropdown in shared header)
4. Pull vulnerability findings from Debricked for the selected branch
   - If branch has a scan: show findings from that scan
   - Else: show findings from default branch
5. Display findings in a flat, sortable table (CVE, CVSS, Dependencies, Review Status)
6. Search & filter by CVE name or dependency name
7. Sort by any column (Name, CVSS, status, etc.)

**Local Scan (Phase 2)**
8. Trigger local scan flow from the plugin (Debricked CLI)
9. Show scan progress/status and actionable errors
10. Refresh vulnerabilities from latest scan context

**Dependency Inventory (Phase 3)**
11. View all dependencies (direct & transitive)
12. Filter by ecosystem (Maven, npm, etc.)
13. Search by package name
14. See license for each dependency
15. Detect known vulnerabilities per dependency
16. View dependency details in the right pane (minimum information contract)

**License Governance (Phase 4)**
17. View licenses and policy status
18. Identify policy violations & warnings
19. Group packages by license
20. View license details in the right pane (minimum information contract)

**Dashboard Overview (Phase 5)**
21. View high-level summary: vulnerability counts, dependency counts, license issues
22. Quick navigation to detailed tabs
23. Last scan timestamp

**Advanced Investigation (Phase 6)**
24. Open detailed vulnerability reports in editor tabs
25. View dependency paths (transitive chains)
26. Access deep investigation tabs across entities (vulnerability/dependency/license evidence)
27. View usage locations for dependencies

---

## Architecture Overview

```
IntelliJ Project
   │
   ├── DebrickedPluginManager (Project Service)
   │      ├── Orchestrates findings refresh for all tabs
   │      ├── Manages tab state & caching per repository/branch
   │      ├── Manages listener callbacks
   │      └── Tracks UI state (LOADING, CURRENT, NO_REMOTE_RESULTS, etc.)
   │
   ├── Tab Data Providers (Per-Tab Orchestrators)
   │      ├── VulnerabilitiesTabProvider
   │      │    └── Fetches vulnerabilities via DebrickedApiClient
   │      ├── DependenciesTabProvider
   │      │    └── Fetches dependencies via DebrickedApiClient
   │      ├── LicensesTabProvider
   │      │    └── Fetches licenses via DebrickedApiClient
   │      └── DashboardTabProvider
   │           └── Aggregates counts from other providers
   │
   ├── DebrickedApiClient (App Service)
   │      ├── JWT authentication (token or username/password)
   │      ├── Repository listing
   │      ├── Branch listing
   │      ├── Paginated vulnerability fetching
   │      ├── Dependencies API fetching
   │      ├── Licenses API fetching
   │      └── Connection verification
   │
   ├── Data Cache (Per Repository/Branch)
   │      ├── CachedVulnerabilities (repositoryId + branch)
   │      ├── CachedDependencies (repositoryId + branch)
   │      ├── CachedLicenses (repositoryId + branch)
   │      └── Cache invalidation on repo/branch change
   │
   ├── UI Layer (Tabbed Tool Window)
   │      ├── Shared Header
   │      │     ├── Repository dropdown
   │      │     ├── Branch dropdown
   │      │     └── Repository refresh button
   │      │
   │      ├── Tab Container (JTabbedPane / dynamic registration)
   │      │     ├── Vulnerabilities Tab (Phase 1, always visible)
   │      │     ├── Local Scan actions (Phase 2, same tool window context)
   │      │     ├── Dependencies Tab (Phase 3, visible when implemented)
   │      │     ├── Licenses Tab (Phase 4, visible when implemented)
   │      │     └── Dashboard Tab (Phase 5, visible when implemented)
   │      │
   │      ├── Vulnerability Tab Content
   │      │     ├── Slim vertical action sidebar (Refresh, View options popup)
   │      │     ├── Inline SearchTextField + result count
   │      │     ├── Table with configurable columns & group header rows
   │      │     │     (Name, Introduced, CVSS, Dependencies,
   │      │     │      Reachable Path*, Review Status, Exploited*)  *hidden by default
   │      │     └── Vulnerability details pane (right side)
   │      │
   │      ├── Dependencies Tab Content
   │      │     ├── Ecosystem filter
   │      │     ├── Dependency list/tree
   │      │     ├── Search box
   │      │     └── Dependency details pane
   │      │
   │      ├── Licenses Tab Content
   │      │     ├── Policy status filter
   │      │     ├── License summary
   │      │     ├── Search box
   │      │     └── License details pane
   │      │
   │      └── Dashboard Tab Content
   │           ├── Vulnerability counts
   │           ├── Dependency counts
   │           ├── License issue counts
   │           └── Navigation buttons
   │
   ├── DebrickedSettingsManager (App Service)
   │      ├── API URL configuration
   │      ├── Authentication method (token / username:password)
   │      ├── Default startup tab preference
   │      └── Secure credential storage (PasswordSafe)
   │
   ├── DebrickedCredentialStore (App-level)
   │      ├── In-memory cache for access token & password
   │      ├── PasswordSafe integration for persistence
   │      └── EDT population on startup
   │
   ├── Project State (Project-level)
   │      ├── Selected repository ID & name
   │      ├── Selected branch per repository
   │      ├── Last selected tab
   │      └── Split pane positions per tab
   │
   ├── Session State (Transient)
   │      ├── Expanded/collapsed groups
   │      ├── Search text per tab
   │      ├── Filters per tab
   │      └── Selected items per tab
   │
   └── MessageBus Integration
          └── DebrickedSettingsNotifier
                 ├── Settings apply event publisher
                 └── Triggers cache invalidation & refresh

```

## Phase 1: Vulnerabilities Tab & Tabbed Architecture ✅ LARGELY COMPLETE

### Objectives (Clean Architecture First)
- Implement tabbed tool window architecture (JTabbedPane + TabProvider pattern)
- Build data cache layer (per-repository/branch, lazy-load, invalidation)
- Create Vulnerabilities tab as first tab (flat sortable table)
- Implement shared repository & branch selector (persistent across all tabs)
- Lay foundation for future tabs (Dashboard, Dependencies, Licenses) in Phases 2+

### Why Tabbed Architecture in Phase 1?

Building the tabbed infrastructure now ensures:
- ✅ Clean, extensible architecture from the start
- ✅ Easy to add Dashboard (Phase 2), Dependencies (Phase 3), Licenses (Phase 4) without major refactoring
- ✅ Consistent UX across all tabs (shared header, lazy-load strategy, caching)
- ✅ Single responsibility per tab (VulnerabilitiesTabProvider, DashboardTabProvider, etc.)
- ✅ Reduced technical debt; no "refactor later" risk

### Status

**Authentication & Core Infrastructure**: Complete
- ✅ JWT authentication (token and username/password)
- ✅ Credential storage (in-memory + PasswordSafe)
- ✅ Settings UI
- ✅ Repository listing API
- ✅ MessageBus for cross-project notifications

**Tabbed Architecture**
- [x] Create `DebrickedToolWindowContent` with JTabbedPane
- [x] Define `TabProvider` interface (loadData, invalidate, getPanel)
- [x] Implement `DataCache` layer (cache key = repositoryId:branch:tabType)
- [x] Build shared header panel (repo + branch selectors)
- [x] Wire tab change listener (lazy-load on selection)
- [x] Add cache invalidation on repo/branch change

**Vulnerabilities Tab (First Tab)**
- ✅ Paginated vulnerability fetching (by repository + branch)
- ✅ Flat table UI (replacing severity-grouped tree)
- ✅ Sortable columns (Name, CVSS, Dependencies, Review Status)
- ✅ Real-time search/filter (CVE name or dependency)
- ✅ CVSS-to-Severity calculation (no API enum)
- [x] Implement `VulnerabilitiesTabProvider` (extends TabProvider)
- [x] Create vulnerability table panel (`JBTable` + custom grouping table model)
- [x] Add vulnerability details pane (right side, master-detail layout via `JSplitPane`)
- [x] Integrate branch selector (fetch findings for selected branch)
- [x] Implement fallback: branch scan → default branch scan if no data
- [x] Implement tab caching (cache findings by repositoryId:branch)
- [x] Native IntelliJ action sidebar (Refresh + View options popup)
- [x] Configurable columns (Introduced, CVSS, Dependencies, Reachable Path, Review Status, Exploited)
- [x] Real Group By section headers in the table
- [x] IntelliJ `SearchTextField` inline filter with placeholder text

### Key Decisions Made

1. **Tabbed Architecture**: All functionality (vulnerabilities, dependencies, licenses) in one tool window with tabs—not separate tool windows
2. **Default Tab**: User-configurable (Dashboard default for new installs)
3. **Shared Header**: Repository and branch selectors above tabs; header refresh is for repositories
4. **Lazy Loading**: Only load the active tab; others load on-demand when selected
5. **Caching**: Cache vulnerabilities/dependencies/licenses per `(repositoryId, branch)` tuple
6. **Simple Fallback**: If selected branch has no scan → show default branch findings (no commit-specific logic)
7. **Branch-Only (Phase 1)**: No commit ID selector; defer commit-level investigation to Phase 5+
8. **CVSS-Derived Severity**: Severity calculated client-side from CVSS (9-10=CRITICAL, 7-8.9=HIGH, etc.)
9. **Flat Table Instead of Tree**: Debricked UI shows flat table, not grouped tree; users expect matching UX
10. **Sortable Columns**: Sorting is driven by the View options popup (native IntelliJ toggle actions), not only by header clicks
11. **Real-Time Search**: Document listener on an IntelliJ `SearchTextField`, filter applied immediately
12. **Native Action Sidebar**: Vulnerabilities tab actions live in a slim vertical `ActionToolbar` (Git/Problems style) rather than a row of buttons
13. **View Options Popup**: A single `ActionGroup` popup (eye icon) exposes Columns / Sort By / Group By with automatic checkmarks — mirrors the Problems, Project, Services and TODO tool windows
14. **Configurable Columns**: Users show/hide columns; `Reachable Path` and `Exploited (CISA)` are hidden by default
15. **Real Grouping**: Group By renders section header rows in a custom `AbstractTableModel`, not just a secondary sort key
16. **Searchable Selector Popups**: Repository/branch selection uses compact current-value actions + searchable popups (shortlist + "Search all repositories…") instead of large combo boxes

### Implemented Files (Phase 1)

| File | Purpose | Status |
|------|---------|--------|
| `DebrickedApiClient.kt` | HTTP client, JWT auth, vulnerability fetching (by branch) | ✅ Complete (auth & vuln) |
| `DebrickedSettingsManager.kt` | Persistent settings (URL, auth method, default tab) | ✅ Complete |
| `DebrickedCredentialStore.kt` | In-memory + PasswordSafe credential cache | ✅ Complete |
| `DebrickedPluginManager.kt` | Project-level service, tab state & caching orchestration | ✅ Complete |
| `DebrickedSettingsConfigurable.kt` | Settings UI (auth only) | ✅ Complete |
| `DebrickedToolWindowFactory.kt` | Tool window factory (creates tabbed content) | ✅ Complete |
| `Panels.kt` | Legacy panels (auth/repository prompts, reference severity tree) | ✅ Retained |
| `TabbedToolWindow.kt` | Tabbed UI: header selectors, sidebar toolbar, table, options popup, details pane | ✅ Complete |
| `Models.kt` | Domain: Severity (computed from CVSS), VulnerabilityFinding, FindingsState | ✅ Complete |
| `DebrickedSettingsNotifier.kt` | MessageBus topic for settings changes | ✅ Complete |
| `TabProvider.kt` | Interface + `VulnerabilitiesTabProvider` implementation | ✅ Complete |
| `DataCache.kt` | Per-repository/branch cache with invalidation | ✅ Complete |

### UI Layout (Phase 1)

**Preview-friendly structure**

| Area | Content |
|------|---------|
| Tabs | Dynamic: only implemented tabs are shown by default |
| Shared header | `Repository: [payment-service ▼]` `Branch: [main ▼]` `[↺ Refresh]` |
| Active tab in Phase 1 | `Vulnerabilities` |
| Inactive tabs in Phase 1 | Hidden by default; optional preview toggle may expose planned tabs |

**Vulnerabilities tab layout**

```text
+---+---------------------------------------------------------------+
| ↺ | [🔍 Search by name or dependency ]        19 vulnerabilities   |
| 👁 +---------------------------------+-----------------------------+
|   | Findings table                   | Vulnerability details      |
|   |----------------------------------|-----------------------------|
|   | Name | Introduced | CVSS | Deps  | CVE-2026-42043              |
|   |      | Review Status            | Severity: Critical          |
|   | ...  | ...        | 9.8  | ...   | CVSS: 9.8 (CVSS3)           |
|   | ...  | ...        | ...  | ...   | Package: log4j:2.14.0       |
|   | ...  | ...        | ...  | ...   | Fixed: 2.17.2               |
+---+----------------------------------+-----------------------------+
  ↑ slim vertical action sidebar (Refresh, View options popup)
```

- Left slim `ActionToolbar`: `Refresh findings` (↺) and `View options` (eye) popup
- `View options` popup sections: **Columns**, **Sort By**, **Group By** (checkmarks rendered by `ToggleAction`)
- Search: IntelliJ `SearchTextField`, capped width (~360px), placeholder "Search by name or dependency"
- Count label sits to the right of the search field

**Tab visibility policy**

- Show only implemented tabs by default.
- Hide placeholder-only tabs to avoid dead-end UX.
- Optional internal/testing flag: **Show preview tabs**.
- Default tab fallback: if saved default tab is unavailable, fall back to Vulnerabilities.

### Settings Panel

```
Server URL:           [https://debricked.com/api ..................]
Authentication:       ◉ Access Token  ○ Username/Password  ○ SSO
Username:             [________________________]  (disabled if token selected)
Password:             [________________________]  (disabled if token selected)
Access Token:         [________________________]  (disabled if user/pass selected)
[Verify Connection] ✓ Connection verified. Use the repository selector in the Debricked panel.
```

### Key Technical Decisions

**Tabbed Architecture**:
- Single tool window with JTabbedPane (not separate tool windows)
- Shared header (repo/branch) above tabs; changes propagate to all tabs
- TabProvider interface for clean separation of concerns
- Vulnerabilities tab as Phase 1; other tabs placeholder (Phases 2+)

**Data Caching**:
- Cache key: `repositoryId:branch:tabType` (e.g., "123:main:vulnerabilities")
- Lazy-load: only load active tab on first access
- Cache invalidation: clear all cached data when repo/branch changes
- MessageBus trigger: settings change → invalidate all caches

**Thread Model**:
- `ApplicationManager.executeOnPooledThread { }` for background API calls
- `ApplicationManager.invokeLater { ... ModalityState.any() }` for UI updates
- No blocking calls on EDT

**PasswordSafe & Credentials**:
- In-memory cache for credentials (avoid Windows deadlock)
- `loadFromStorage()` called once on EDT at tool window init
- Setters update both memory and PasswordSafe on EDT

**Severity Calculation**:
- CVSS score (API data) → severity badge (client-side calculation)
- 9.0–10.0 = CRITICAL (red), 7.0–8.9 = HIGH (orange), etc.
- No severity enum from API; computed on-demand

**Table, Sorting & Grouping**:
- `JBTable` backed by a custom `AbstractTableModel` (not `TableRowSorter`) so grouping can insert real section header rows
- Column visibility is user-configurable; hidden columns are removed from the `TableColumnModel` but remain in the model
- Default visible columns: Name, Introduced, CVSS, Dependencies, Review Status
- Default hidden columns: Reachable Path, Exploited (CISA)
- Sorting and grouping are chosen from the View options popup; sorting defaults to CVSS descending
- Group By supports Dependencies, Reachable path, Review status, Exploited (CISA), or None

---

## Phase 2: Local Scan (Debricked CLI) 🔮 PLANNED

### Objectives
- Trigger local scan execution from the plugin
- Validate CLI availability/configuration before run
- Show scan progress, completion, and failure states
- Refresh vulnerability results after a successful scan

### Recommended User Flow

```
[Run Local Scan]
  ↓
CLI preflight check (installed? auth/config available?)
  ↓
Execute scan process + stream progress/status
  ↓
On success: invalidate relevant cache + refresh findings query
On failure: show actionable remediation message
```

### Implementation Notes
- Reuse existing `RunLocalScanAction` entry point and make it functional
- Execute scan process in pooled background thread
- Surface progress and terminal output summary in tool window status
- Add cancellation support and timeout handling
- Persist last local scan timestamp in session state

---

## Phase 3: Dependencies Tab 🔮 PLANNED

### Objectives
- Show all detected dependencies (direct & transitive)
- Filter by ecosystem (Maven, npm, Gradle, etc.)
- Search by package name
- Display vulnerability count per dependency
- Show license for each dependency

### Recommended Layout

```
Dependencies Tab
┌──────────────────────────────────────────────┐
│ Ecosystem: [All ▼]  Search: [_______]        │
├─────────────────────┬──────────────────────-─┤
│ Dependencies (421)  │ Dependency Details     │
├─────────────────────┼───────────────────────-┤
│ spring-boot  3.3.2  │ Package: spring-boot   │
│ log4j        2.14.0 │ Version: 3.3.2         │
│ jackson      2.16.1 │ Ecosystem: Maven       │
│ ...                 │ Vulnerabilities: 0     │
│                     │ License: Apache-2.0    │
└─────────────────────┴───────────────────────-┘
```

### Implementation Notes
- Fetch via `/api/{version}/open/dependencies` endpoint
- Show Direct vs. Transitive in list
- Cache by repository/branch
- Details pane shows: Package, Version, Ecosystem, Vulnerabilities, License, Dependency Path

---

## Phase 4: Licenses Tab 🔮 PLANNED

### Objectives
- Show license summary grouped by license type
- Highlight policy violations & warnings
- Filter by policy status (Approved, Warning, Violation)
- Search by license name or package name

### Recommended Layout

```
Licenses Tab
┌──────────────────────────────────────────────┐
│ Policy: [All ▼]  Search: [_______]           │
├─────────────────────┬──────────────────────-─┤
│ License Summary     │ License Details        │
├─────────────────────┼───────────────────────-┤
│ ✅ Apache-2.0 (45)  │ License: GPL-3.0       │
│ ✅ MIT (21)         │ Policy: Violation      │
│ ⚠ GPL-3.0 (2)       │ Packages:              │
│ ⛔ AGPL-3.0 (1)      │ - library-a          │
│                     │ - library-b           │
└─────────────────────┴───────────────────────┘
```

### Implementation Notes
- Fetch via `/api/{version}/open/licenses` endpoint
- Group packages by license
- Show policy status (✅ Approved, ⚠ Warning, ⛔ Violation)
- Details pane lists affected packages & policy reasoning
- Optional: "View Policy" button links to policy definition

---

## Phase 5: Dashboard Tab 🔮 PLANNED

### Objectives
- Build summary/overview tab
- Show vulnerability, dependency, and license counts
- Provide quick navigation to detailed tabs
- Display last scan timestamp

### Recommended Content

```
Dashboard
Repository: payment-service
Branch: main

Vulnerabilities
  Critical: 2
  High:     5
  Medium:   12
  Low:      8

Dependencies
  Total:     421
  Outdated:  37
  Vulnerable: 9

Licenses
  Approved:     66
  Warnings:      2
  Violations:    1

Last scan: 2026-08-11 10:42

[View Vulnerabilities] [View Dependencies] [View Licenses] [Open in Debricked]
```

### Implementation Notes
- Lazy-load: fetch summary data only when Dashboard tab is selected
- Aggregate counts from cached vulnerability/dependency/license data
- Provide buttons to switch to other tabs
- Show timestamp of last API sync

---

## Phase 6: Advanced Rich Investigation 🔮 PLANNED

### Objectives
- Add nested tabs inside details panes for deeper investigation
- Support opening vulnerability reports in editor tabs
- Show dependency paths (transitive chains) and how a vulnerability was introduced
- Surface exploitability signals (CISA-KEV, reachability analysis, EPSS if available)
- Surface review/triage status and CVSS vector breakdown
- Display references & remediation guidance, including links to the affected manifest and the Debricked UI

### Recommended Nested Detail Tabs (cross-tab advanced mode)

**Vulnerability Details**:
```
Overview | Dependency Path | Exploitability | Review | Remediation | References
```

**Dependency Details**:
```
Overview | Dependency Path | Vulnerabilities | License
```

**License Details**:
```
Overview | Packages | Policy | References
```

### Implementation Notes
- Nested tabs only when information is too large for single pane
- Optional for first version; add later when details become complex
- Editor tabs: double-click finding to open CVE report in editor
- Use IntelliJ's FileEditorProvider mechanism for custom editor tabs

---

## UI Style Guide & Interaction Gestures

These rules were established while aligning the plugin with native IntelliJ tool windows (Git, Problems, Project, Services, TODO). **All new tabs and panels must follow them.**

### 1. Icons

- **Only use `com.intellij.icons.AllIcons`.** Do not ship custom SVG icons unless a Debricked-branded asset is genuinely required (product logo only).
- Standard mappings used today:

  | Purpose | Icon |
  |---------|------|
  | Refresh findings / repositories | `AllIcons.Actions.Refresh` |
  | View options (columns/sort/group) | `AllIcons.Actions.Show` |
  | Settings | `AllIcons.General.Settings` |
  | Severity/state colouring | `JBColor`-based renderers, not icons |

- Never hand-composite two icons to fake a combined affordance (e.g. eye + dropdown). If a control opens a popup, use an `ActionGroup` with `isPopup() = true`; the platform draws the dropdown arrow correctly.

### 2. Toolbars & Actions

- Tab-local actions live in a **slim vertical `ActionToolbar`** on the left edge of the tab content (Git tool window pattern), created via
  `ActionManager.getInstance().createActionToolbar(place, DefaultActionGroup(...), true)`.
- Avoid multiple visible refresh buttons. Header refresh = repositories; sidebar refresh = the active tab's data.
- Every action must set `text` **and** `description` in `update()` so tooltips/hover text appear.
- `displayTextInToolbar()` should return `false` for icon-only toolbar actions.

### 3. View Options Popup (Columns / Sort / Group)

- Presentation-level options are grouped into **one popup `ActionGroup`**, not a row of controls.
- Use `Separator.create("<Section>")` to create labelled sections:
  `Columns` → `Sort By` → `Group By`.
- Use `ToggleAction` for entries so IntelliJ renders checkmarks automatically. Never draw check glyphs manually.
- Radio-like behaviour (Sort By / Group By) is implemented by ignoring `state == false` in `setSelected`.
- Do not add options that duplicate each other or that add no value (a severity "Show" section was removed for this reason).
- Disable the popup (`isEnabled = false`) when there are no rows.

### 4. Selectors (Repository / Branch)

- Use a compact action showing the **current value** plus a searchable popup, not an editable/large `JComboBox`.
- For potentially huge lists (repositories in a large org): show a shortlist plus a `Search all repositories…` entry that opens a searchable popup. Long term: server-side paging/search.
- Selectors live in the shared header above the tabs, not in the tool window title bar (custom title-bar components proved unreliable).
- Never auto-refresh on reselection of the already-selected value.

### 5. Search / Filter Fields

- Use `com.intellij.ui.SearchTextField` (inline magnifier + clear button), **not** a `JTextField` with a `Search:` label.
- Set placeholder via `textEditor.emptyText.text`, worded like the Debricked web UI (e.g. "Search by name or dependency").
- Cap the width (~360px preferred, ~260px minimum) and use a flexible spacer so the field does **not** stretch across the whole row.
- Filtering is real-time via a `DocumentListener`.

### 6. Tables

- `JBTable` with `rowHeight = 24`, `fillsViewportHeight = true`, single-row selection.
- Master-detail layout using `JSplitPane` with `resizeWeight ≈ 0.62`.
- Renderers must be theme-aware (`JBColor`); never hardcode hex colours.
- Missing values render as `-`, never as `null`, `0`, or a fabricated value.
- Grouping inserts non-selectable header rows rendered distinctly; it must actually group, not just re-sort.

### 7. Spacing, Colour & Theming

- Use `JBUI.Borders.*` and `JBUI.size(...)` for all insets/sizes so HiDPI scaling works.
- Use `JBColor` for every colour (light/dark theme parity).
- Secondary text (status, counts) uses `JBColor.GRAY`.
- Avoid custom borders around controls; rely on platform defaults.

### 8. Loading & State Gestures

- Show `Loading…` status text rather than blanking the table when data is being fetched for the **same** context.
- Preserve currently visible findings during a same-context refresh to avoid flicker.
- Defer findings loads until repository **and** branch context exist; ignore duplicate reselections.
- Only the active tab loads on startup; other tabs load lazily on first selection.

---

## Minimum Information Contract (Summary & Details per Phase)

Each phase must deliver at least the following fields. "Summary" = the table/list row; "Details" = the right-hand detail pane.

### Phase 1 — Vulnerabilities

| Summary (table) | Details pane |
|-----------------|--------------|
| Name (CVE/identifier) | Identifier + title/description |
| Introduced (discovered date) | Severity + CVSS score and version (CVSS2/CVSS3) |
| CVSS (score, severity-coloured) | Affected dependency (name, version, ecosystem) |
| Dependencies (affected packages) | Fixed/remediation version if known |
| Review Status | Review status |
| Reachable Path *(optional column)* | Reachability analysis + message |
| Exploited (CISA) *(optional column)* | CISA-KEV exploited flag |
| | Link to the finding in the Debricked UI |

### Phase 5 — Dashboard

| Summary | Details |
|---------|---------|
| Vulnerability counts by severity | Drill-through navigation into the Vulnerabilities tab |
| Dependency count (direct/transitive) | Navigation into the Dependencies tab |
| License policy violations/warnings count | Navigation into the Licenses tab |
| Last scan timestamp + branch/commit | Scan source (branch scanned vs default-branch fallback) |

### Phase 3 — Dependencies

| Summary (table) | Details pane |
|-----------------|--------------|
| Package name | Name, version, ecosystem |
| Version | Direct vs transitive |
| Ecosystem (Maven, npm, …) | Declaring manifest file(s) |
| License | License(s) + policy status |
| Vulnerability count | Linked vulnerabilities (navigate to Phase 1 rows) |
| Direct/Transitive flag | Dependency path (transitive chain) |

### Phase 4 — Licenses

| Summary (table) | Details pane |
|-----------------|--------------|
| License name / SPDX id | Full license name, SPDX id, family |
| Policy status (violation/warning/ok) | Policy rule that triggered the status |
| Package count | List of packages under this license |
| | Reference links (license text, policy docs) |

### Phase 6 — Advanced Rich Details

Nested detail tabs must cover, at minimum:

- **Overview** — description, severity, CVSS vector breakdown (base/temporal where available), publication & discovery dates
- **Dependency Path** — how the vulnerable package was introduced (direct vs transitive chain), and the manifest file responsible
- **Exploitability** — CISA-KEV status, reachability analysis result and evidence/message, EPSS if available
- **Review** — review/triage status, who/when if exposed by the API
- **Remediation** — fixed version, upgrade guidance, link to the affected manifest, link to the Debricked UI
- **References** — CVE/NVD/GHSA/vendor advisory links

---

## Debricked API Field Mapping (Vulnerabilities)

Verified against live `get-vulnerabilities` responses. **The payload is not uniform across records — do not assume flat scalar fields.**

| Model field | API source | Notes |
|-------------|------------|-------|
| `name` | `name` / CVE identifier | |
| `cvssScore` | `cvss.text` (object) or flat numeric variants | ⚠️ Commonly nested: `cvss: { text: 9.8, type: "critical" }` |
| severity | `cvss.type` when present, else derived from score | 9.0–10 Critical, 7.0–8.9 High, 4.0–6.9 Medium, else Low |
| `cvss2Score` / `cvss3Score` | CVSS2/CVSS3 variants | Only CVSS3 maps to Critical/High/Medium; not all CVEs have CVSS3 |
| `reviewStatus` | `vulnerabilityStatus` | e.g. `unexamined`; normalised for display (underscores → spaces, title case) |
| `introducedAt` | `discovered` | |
| `reachablePath` | `reachabilityAnalysis` + `reachAnalysisMessage` | Heuristic mapping → `Reachable` / `Potentially reachable` / `Not reachable` / `Unknown` |
| `exploited` | `cisaKevExploited` | Blank currently treated as false; revisit if API distinguishes blank from unknown |
| dependencies | dependency list; `shortName` may already include the ecosystem, e.g. `(Maven)` | Do not append the ecosystem twice |

**Reference case used for verification**: repository `fortify-presales/IWA-Java` (id `131545`), `CVE-2026-43512` → `cvss.text = 9.8`, `vulnerabilityStatus = unexamined`. Earlier the CVSS column showed `-` because the parser only read top-level numeric CVSS fields.

---

## Configuration & Build

### Build System
- **Gradle** with IntelliJ Gradle plugin
- **Gradle wrapper** included (`gradlew`, `gradlew.bat`, `gradle/wrapper/`)
- **Kotlin** 1.9.21
- **Target SDK**: IntelliJ IDEA IC 2023.2+

### Build Command
```bash
.\gradlew.bat build
```

### Run/Test Command
```bash
.\gradlew.bat runIde 2>$null
```
(Note: Suppress stderr to avoid PowerShell noise from IntelliJ startup WARNs)

### Plugin Descriptor
**File**: `src/main/resources/META-INF/plugin.xml`

Registers:
- App services: `DebrickedSettingsManager`, `DebrickedApiClient`
- Project service: `DebrickedPluginManager`
- Tool window: `Debricked` (right anchor, teal shield icon)
- Settings page: `Tools > Debricked`
- Actions: `RefreshFindings`, `RunLocalScan`

---

## Testing & Validation

### Why Unit Tests Weren't Included Initially

Phase 1 prioritized a **working MVP** through rapid iteration (build → test in runIde → fix). Adding unit test infrastructure would have slowed initial development. Now that the core features are stable, Phase 2 introduces automated tests for critical business logic.

**Current Status**: Manual testing only; IntelliJ test fixtures not yet configured.

### Unit Tests (Phase 2 Priority)

Unit tests cover **logic, not UI**. Swing and IntelliJ platform UI is too tightly coupled to IDE lifecycle; those rely on manual testing.

#### Test Infrastructure Setup

Add to `build.gradle.kts`:
```gradle
dependencies {
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.mockito:mockito-core:5.2.0'
    testImplementation 'com.google.code.gson:gson:2.10.1'
}

test {
    useJUnit()
}
```

Then enable tests in the build:
```bash
# Build with tests
.\gradlew.bat build

# Build without tests (current approach)
.\gradlew.bat build -x test
```

#### High-Priority Unit Tests

**1. `DebrickedApiClient` — JSON Parsing & Pagination**

Test nested JSON handling, pagination loop, JWT caching, error cases:

```kotlin
class DebrickedApiClientTest {
    @Test
    fun testParseVulnerability_NestedJson() {
        // Debricked API wraps "vulnerability_name" in a nested object
        val json = """{"data":[{"vulnerability_name":{"id":"CVE-2024-1234"}}]}"""
        val findings = apiClient.parseVulnerabilitiesResponse(json)
        assertEquals("CVE-2024-1234", findings[0].name)
    }
    
    @Test
    fun testParseVulnerability_FlatString() {
        // But sometimes it's a plain string—both must work
        val json = """{"data":[{"vulnerability_name":"CVE-2024-1234"}]}"""
        val findings = apiClient.parseVulnerabilitiesResponse(json)
        assertEquals("CVE-2024-1234", findings[0].name)
    }
    
    @Test
    fun testPaginationLoop() {
        // Mock: page 1 returns 100 items, page 2 returns 50 items (EOF)
        // Verify loop stops and all 150 items returned
        val allFindings = apiClient.getVulnerabilities(repoId=123)
        assertEquals(150, allFindings.size)
        // Verify pagination stopped at page 2 (didn't fetch page 3)
        verify(mockHttp, times(2)).get(contains("page="))
    }
    
    @Test
    fun testJwtCaching_NoRedundantCalls() {
        // First auth: HTTP call → cache JWT
        apiClient.getJwt(token="token-a")
        // Second call: return cached JWT (no HTTP)
        apiClient.getJwt(token="token-a")
        verify(mockHttp, times(1)).post("/api/login_refresh")
    }
    
    @Test
    fun testJwtCacheInvalidation_OnNewToken() {
        // Auth with token A → cache JWT-A
        apiClient.getJwt(token="token-a")
        // Auth with token B → cache JWT-B (invalidate A)
        apiClient.getJwt(token="token-b")
        // Verify both calls made (cache was invalidated)
        verify(mockHttp, times(2)).post("/api/login_refresh")
    }
    
    @Test
    fun testMalformedJsonThrowsException() {
        val bad = """{"data":[invalid json]}"""
        assertThrows<JsonSyntaxException> {
            apiClient.parseVulnerabilitiesResponse(bad)
        }
    }
}
```

**2. `DebrickedSettingsManager` — Persistence**

Test that settings survive IDE restart:

```kotlin
class DebrickedSettingsManagerTest {
    @Test
    fun testSettingsPersistence() {
        val settings = DebrickedSettingsManager.instance
        settings.serverUrl = "https://custom-debricked.com/api"
        settings.repositoryId = 999
        
        // Simulate IDE restart: reload from disk
        val reloaded = DebrickedSettingsManager.instance
        assertEquals("https://custom-debricked.com/api", reloaded.serverUrl)
        assertEquals(999, reloaded.repositoryId)
    }
    
    @Test
    fun testDefaultServerUrl() {
        val settings = DebrickedSettingsManager.instance
        // Should default to Debricked SaaS
        assertTrue(settings.serverUrl.contains("debricked.com"))
    }
}
```

**3. `DebrickedCredentialStore` — In-Memory Cache**

Test that PasswordSafe is not called redundantly (Windows deadlock prevention):

```kotlin
class DebrickedCredentialStoreTest {
    @Test
    fun testInMemoryCacheAvoidsDuplicatePasswordSafeCalls() {
        val store = DebrickedCredentialStore()
        store.loadFromStorage()  // Load once
        
        // Access token twice
        val token1 = store.accessToken
        val token2 = store.accessToken
        
        // Verify PasswordSafe.getPassword() called only once (cached)
        verify(mockPasswordSafe, times(1)).getPassword(...)
    }
    
    @Test
    fun testSetterUpdatesBothMemoryAndPasswordSafe() {
        val store = DebrickedCredentialStore()
        store.accessToken = "new-token"
        
        // In-memory field updated immediately
        assertEquals("new-token", store.accessToken)
        
        // PasswordSafe updated (on EDT)
        verify(mockPasswordSafe, times(1)).setPassword(...)
    }
}
```

**4. Domain Models**

Test `Severity` enum and `VulnerabilityFinding`:

```kotlin
class VulnerabilityFindingTest {
    @Test
    fun testSeverityComparison() {
        val critical = VulnerabilityFinding(severity=Severity.CRITICAL, ...)
        val high = VulnerabilityFinding(severity=Severity.HIGH, ...)
        assertTrue(critical > high)  // CRITICAL > HIGH
    }
    
    @Test
    fun testFixedVersionDisplay() {
        val finding = VulnerabilityFinding(
            packageName="log4j-core",
            version="2.14.0",
            fixedVersion="2.17.2"
        )
        assertEquals("2.17.2", finding.fixedVersion)
    }
}
```

#### Running Tests

```bash
# Run all tests
.\gradlew.bat test

# Run specific test class
.\gradlew.bat test --tests DebrickedApiClientTest

# Run with verbose output
.\gradlew.bat test --info
```

#### Test Coverage Targets (Phase 2)

| Component | Target | Reason |
|-----------|--------|--------|
| `DebrickedApiClient` | 80%+ | JSON parsing & pagination are error-prone |
| `DebrickedSettingsManager` | 90%+ | Persistence bugs are hard to debug |
| `DebrickedCredentialStore` | 90%+ | Windows deadlock prevention is critical |
| `Domain Models` | 80%+ | Sorting/filtering depends on correctness |
| `DebrickedPluginManager` | 40%+ | Mostly orchestration; integration testing preferred |
| **UI Components** | **Skip** | Manual testing preferred (see below) |

**Why UI Is Excluded**: Swing and IntelliJ platform UI require complex fixture setup and mocking. Manual testing is faster and more reliable. Consider regression testing later if needed.

### Manual Testing Checklist

#### Authentication
- [ ] Enter access token in Settings, click Verify Connection → ✓ "Connection verified"
- [ ] Enter username/password, click Verify Connection → ✓ "Connection verified"
- [ ] Invalid credentials → ✓ "Connection failed: ..."
- [ ] Credentials persist across IDE restarts

#### Repository Selection
- [ ] Click Refresh Repositories → loads list in dropdown
- [ ] Change dropdown selection → immediately refreshes findings
- [ ] Selection persists across IDE restarts
- [ ] Repo shown as "name [id]" format

#### Branch Selection
- [ ] Branch dropdown shows list of branches for selected repository
- [ ] Select different branch → findings refresh immediately
- [ ] Selection persists per repository
- [ ] Shows branch name in header

#### Findings Display (Flat Table, Sortable by Branch)
- [ ] Findings load automatically when branch is selected
- [ ] "Loading…" spinner shows during fetch
- [ ] If selected branch has no scan, falls back to default branch (no error)
- [ ] Table displays: Name (CVE), Introduced, CVSS, Dependencies, Review Status (default columns)
- [ ] CVSS score has colored badge (red 9-10, orange 7-8.9, yellow 4-6.9, blue 0-3.9)
- [ ] CVSS is populated for CVEs that have a score in the Debricked UI (e.g. CVE-2026-43512 → 9.8)
- [ ] Review Status is populated (e.g. "Unexamined")
- [ ] View options popup shows Columns / Sort By / Group By with checkmarks
- [ ] Toggling a column shows/hides it immediately; Reachable Path and Exploited start hidden
- [ ] Group By inserts section header rows (not just re-sorting)
- [ ] Search field shows the magnifier, placeholder text and a clear button
- [ ] Search field does not stretch across the full window width
- [ ] Sidebar actions show hover tooltips ("Refresh findings", "View options")
- [ ] Search box filters by CVE name or dependency name (real-time)

#### Error Handling
- [ ] Network error → shows friendly message in status bar
- [ ] Credentials removed from settings → tool window shows "Not Connected" prompt
- [ ] Disconnected repository → prompts to select new one
- [ ] No scan for branch → shows default branch findings
- [ ] No scan for default branch → shows "No findings available" message

---

## Decisions & Rationale

| Decision | Rationale |
|----------|-----------|
| Move repo selection to tool window | Eliminates settings dialog context switch; repo selection is a runtime choice, not configuration |
| Implement repository bar as integrated element | Matches Fortify on Demand UX; single-line layout saves space |
| Use MessageBus for settings changes | Reliable, scalable pattern; works for multi-project scenarios; avoids ProjectManager lookup ambiguity |
| In-memory credential cache | Avoids PasswordSafe deadlock on Windows when called from background threads |
| Paginate with `rowsPerPage=100` | Default (25) was too small; 100 balances speed vs. memory; loop until `size < rowsPerPage` |
| Thread pool instead of GlobalScope | IntelliJ environment does not reliably execute `GlobalScope.launch` with blocking HTTP; thread pool respects IDE's scheduling |
| Theme-aware colors (JBColor) | Automatic light/dark theme support; maintainable color scheme |
| `LOADING` state | Immediately clears old findings so user sees "Loading…" rather than stale results during branch/repo change |
| **Tabbed architecture (Phase 1+)** | Separates distinct workflows (vulnerabilities, dependencies, licenses); each tab can evolve independently; matches common IntelliJ patterns |
| Vulnerabilities as default tab | MVP focuses on security; users expect vulnerabilities first; Dashboard later once mature |
| Lazy-load tabs | Keep tool window fast; load only active tab; other tabs load on-demand |
| Cache by (repositoryId, branch) | Fast switching between repos/branches without re-fetching; invalidate when context changes |
| Real-time search/filter | Faster than modal dialog filters; users can instantly narrow to CVEs matching name or dependency |
| **Cache TTL (30 min default)** | Prevents API rate limiting while keeping data reasonably fresh; users can force refresh via button |
| **Lazy-load all tabs** | Only active tab fetches on startup; other tabs load on-demand; saves ~480 API calls per 8-hr session |
| **Track scan timestamps** | Compare Debricked API's latest scan time with cached data to auto-detect new commits; refresh only if newer scan exists |
| **Force-refresh active tab only** | "Refresh" button refreshes active tab + invalidates cache; switching repos/branches invalidates all caches for that repo |
| **Standard `AllIcons` only** | Custom SVGs drift from theme/HiDPI behaviour and never match platform affordances; removed all bespoke icon assets |
| **Popup `ActionGroup` instead of composited icons** | The platform renders the dropdown arrow and checkmarks correctly; hand-layered icons never aligned properly |
| **Slim vertical action sidebar** | Matches the Git tool window; keeps the row above the table dedicated to filtering and avoids duplicate refresh buttons |
| **Columns/Sort/Group in one popup** | Mirrors Problems / Project / Services / TODO tool windows; users already know the gesture |
| **Group By creates header rows** | Users expect grouping to fold items into sections; a secondary sort key was indistinguishable from Sort By |
| **Reachable Path & Exploited hidden by default** | Keeps the default table readable; both are opt-in signals for deeper triage |
| **Removed severity "Show" section** | Duplicated column sorting/filtering without adding value |
| **`SearchTextField` with capped width** | Native inline search look; full-width fields do not match IntelliJ tool window filters |
| **Parse `cvss.text` / `cvss.type`** | Live payloads nest CVSS as an object; assuming a flat numeric field caused empty CVSS cells |
| **Map review status from `vulnerabilityStatus`** | The actual API field name; earlier assumptions left the Review Status column blank |
| **Custom `AbstractTableModel` over `TableRowSorter`** | Required to support real grouping rows alongside sorting |

---

## Known Limitations & Future Work

**Phase 1 (Vulnerabilities Tab)**:
1. Branch-level findings only (no commit-specific logic)
2. Simple fallback: if selected branch has no scan → show default branch
3. Reachable Path is a heuristic mapping of `reachabilityAnalysis` / `reachAnalysisMessage`; refine when the API semantics are documented
4. `cisaKevExploited` blank is treated as "No"; blank vs unknown is not distinguished
5. Column visibility / sort / group choices are not yet persisted across IDE restarts
6. Repository search is client-side; very large orgs still need server-side paging/search

**Phase 6+ (Commit-Level Investigation)**:
5. Commit ID selector (defer to Phase 5 or later)
6. Exact commit match vs. branch fallback logic
7. Dependency path visualization per commit
8. Reachability analysis (if available in API)

**Phase 3+ (Future Tabs)**:
9. Dependencies Tab: Not yet implemented; requires new API endpoints
10. Licenses Tab: Not yet implemented; requires license API
11. Dashboard Tab: Not yet implemented; delivered in Phase 5
12. Nested deep detail tabs: advanced enhancement in Phase 6
13. Editor Tab Reports: Double-click vulnerability to open full report (Phase 6)

**General Future Work**:
14. Local CLI scans (Phase 2 planned, not yet implemented end-to-end)
15. Transitive dependency path visualization
16. Quick fixes / dependency upgrade suggestions
17. Module mapping in IntelliJ project

---

## Tabbed Architecture Implementation Guide

---

## Caching & Rate Limiting Strategy

### Problem Statement

The Debricked API can be rate-limited if:
1. User switches tabs frequently (each tab triggers a fetch)
2. Multiple projects open in IDE (each project's tool window fetches independently)
3. User manually clicks "Refresh" repeatedly
4. Cache never expires (stale data forever)

### Solution: Smart TTL + Commit Detection Cache

**Key Principles**:
1. **Cache by `repositoryId:branch:tabType`** — isolated per-tab state
2. **TTL-based staleness** — auto-invalidate after 30 min (configurable)
3. **Commit detection** — compare API's latest scan timestamp with cached data
4. **Explicit refresh** — "Refresh" button forces re-fetch only active tab
5. **Lazy-load** — don't fetch data user never views

### Cache Lifecycle

| Event | Action | Rate Limit Impact |
|-------|--------|-------------------|
| User opens tool window | Check cache, load active tab if fresh | 1 API call (if stale) |
| User switches branch | Invalidate all caches for that repo:branch | 1 API call for active tab |
| User switches tab | Load tab from cache if fresh, fetch if stale | 1 API call (if tab stale) |
| User clicks Refresh | Force re-fetch active tab only | 1 API call (guaranteed) |
| Timer fires (30 min) | Mark cache stale (don't delete) | 0 API calls (next access triggers fetch) |
| IDE session lasts 8 hrs | Cache prevents ~480 redundant calls | Huge savings |

### Implementation in Phase 1

Phase 1 DataCache must support:
- [x] TTL tracking (`fetchedAt` timestamp)
- [x] Stale detection (`isStale(ttlMs)` method)
- [x] Repository/branch invalidation (`invalidateRepository()`)
- [x] Smart getOrLoad with `forceRefresh` flag
- [ ] Scan timestamp comparison (Phase 1b, when API is queried)
- [ ] Git HEAD tracking (Phase 5+, optional)

### Rate Limit Best Practices

**DO**:
- ✅ Cache by `repositoryId:branch:tabType`
- ✅ Use TTL (default 30 min per cache entry)
- ✅ Force-refresh only on explicit user action
- ✅ Lazy-load tabs (don't fetch if tab never opened)
- ✅ Invalidate all repo data on branch change (only 1 API call for new branch)

**DON'T**:
- ❌ Fetch all tabs on startup (only fetch active tab)
- ❌ Refetch every second (TTL prevents this)
- ❌ Clear entire cache on minor config change (only invalidate affected repo:branch)
- ❌ Auto-refresh in background (causes cumulative API load)

### Example Rate Limit Scenario

**8-hour IDE session, no caching**:
- 4 tabs × 2 repos × 60 tab switches = 480 API calls
- Hits rate limit (if limit is <500/hr)

**Same session, with smart caching**:
- Initial load: 1 API call (active tab)
- Tab switches: 0 calls (all cached, fresh)
- Branch change: 1 API call (active tab)
- 30-min cache expiry: ~16 calls (lazy-reload only if tab accessed)
- Total: ~17 API calls (95% reduction!)

---

### Refactoring Current UI for Tabbed Layout

**Current State (Phase 1, evolving)**:
- Single tool window with findings tree
- Repository bar at top
- Settings accessible

**Target State (Phase 1, complete)**:
- JTabbedPane with 4 tabs (Dashboard, Vulnerabilities, Dependencies, Licenses)
- Shared header (repo/branch selector) above tabs
- Vulnerabilities tab as default
- Other tabs lazy-load

### Refactoring Steps

**Step 1: Create Tab Container**
```kotlin
class DebrickedToolWindowContent : JPanel() {
    private val tabbedPane = JTabbedPane()
    
    init {
        // Build shared header (repo + branch selector)
        val headerPanel = buildHeaderPanel()
        
        // Add tabs
        tabbedPane.addTab("Vulnerabilities", VulnerabilitiesTabPanel())
        tabbedPane.addTab("Dashboard", DashboardTabPanel()) 
        tabbedPane.addTab("Dependencies", DependenciesTabPanel())
        tabbedPane.addTab("Licenses", LicensesTabPanel())
        
        layout = BorderLayout()
        add(headerPanel, BorderLayout.NORTH)
        add(tabbedPane, BorderLayout.CENTER)
    }
}
```

**Step 2: Implement Tab Providers**
```kotlin
// Each tab has its own data provider & state
interface TabProvider {
    fun loadData(repositoryId: String, branch: String)
    fun invalidate()  // Called when repo/branch changes
    fun getPanel(): JPanel
}

class VulnerabilitiesTabProvider : TabProvider {
    // Lazy-load vulnerabilities via DebrickedApiClient
    // Cache by (repositoryId, branch)
    // Update when repo/branch changes
}

class DashboardTabProvider : TabProvider {
    // Aggregate counts from cached vulnerability/dependency/license data
    // Lazy-load when tab is first selected
}
```

**Step 3: Add Cache Layer with TTL & Rate Limit Protection**

The DataCache must:
1. **Cache by repository:branch:tabType** (e.g., `"123:main:vulnerabilities"`)
2. **Track last-sync timestamp** (when data was fetched from API)
3. **Support smart invalidation**:
   - On repo/branch change → clear all data for that repo/branch
   - On "Refresh" button → force re-fetch only active tab
   - Auto-invalidate stale data (configurable TTL, default 30 min)
   - Detect new Debricked scans → invalidate if API scan is newer than cached data

```kotlin
data class CachedData(
    val data: Any,
    val fetchedAt: Long = System.currentTimeMillis(),
    val lastScanTimestamp: Long = 0  // From Debricked API
) {
    fun isStale(ttlMs: Long = 30 * 60 * 1000): Boolean =
        System.currentTimeMillis() - fetchedAt > ttlMs
}

class DataCache {
    private val cache = mutableMapOf<String, CachedData>()
    private val lock = Object()
    
    fun cacheKey(repositoryId: String, branch: String, type: String): String =
        "$repositoryId:$branch:$type"
    
    fun getOrLoad(
        key: String,
        forceRefresh: Boolean = false,
        loader: () -> Any
    ): Any = synchronized(lock) {
        val cached = cache[key]
        
        // Return cached if fresh and not forced refresh
        if (!forceRefresh && cached != null && !cached.isStale()) {
            return cached.data
        }
        
        // Fetch new data
        val newData = loader()
        cache[key] = CachedData(newData)
        return newData
    }
    
    fun invalidateIfStale(key: String): Boolean = synchronized(lock) {
        val cached = cache[key]
        if (cached != null && cached.isStale()) {
            cache.remove(key)
            return true  // Was stale and removed
        }
        return false  // Still fresh
    }
    
    fun invalidateRepository(repositoryId: String, branch: String? = null) = 
        synchronized(lock) {
            if (branch != null) {
                cache.keys.removeAll { it.startsWith("$repositoryId:$branch:") }
            } else {
                cache.keys.removeAll { it.startsWith("$repositoryId:") }
            }
        }
    
    fun clearAll() = synchronized(lock) { cache.clear() }
}
```

**Step 3b: Detect New Debricked Scans (Commit Detection)**

To refresh only when new commits are pushed to Debricked:

1. **Poll API for latest scan timestamp**:
   - Query `/api/{version}/open/repositories/{id}/scans` endpoint (if available)
   - Compare `latestScanTime` from API with cached `lastScanTimestamp`
   - If API has newer scan → invalidate local cache and re-fetch

2. **Git integration (optional, Phase 5+)**:
   - Detect local Git branch HEAD change via `GitRepositoryManager`
   - If local HEAD != cached HEAD → invalidate and refresh
   - Store Git HEAD in cache metadata

3. **User-driven refresh**:
   - "↺ Refresh" button in header → force-refresh active tab (bypass cache)
   - "⟳ Full Refresh" (future) → clear all caches and reload all tabs

**Example: Smart refresh logic**:
```kotlin
fun shouldRefreshFromApi(
    cachedData: CachedData?,
    apiLatestScanTime: Long
): Boolean {
    // No cache → fetch
    if (cachedData == null) return true
    
    // Cache is stale → fetch
    if (cachedData.isStale()) return true
    
    // API has newer scan → fetch (new commits pushed to Debricked)
    if (apiLatestScanTime > cachedData.lastScanTimestamp) return true
    
    // Otherwise, use cache
    return false
}
```

**Step 4: Handle Tab Switching**
```kotlin
tabbedPane.addChangeListener { event ->
    val selectedTab = tabbedPane.selectedIndex
    val provider = tabProviders[selectedTab]
    
    // Load data if not already loaded
    if (provider.needsLoad(currentRepository, currentBranch)) {
        provider.loadData(currentRepository, currentBranch)
    }
}
```

**Step 5: Propagate Repository/Branch Changes**
```kotlin
// In shared header's repo/branch selector:
repositoryCombo.addActionListener {
    val newRepo = repositoryCombo.selectedItem
    invalidateAllTabCaches(newRepo.id)
    
    // Refresh active tab
    val activeProvider = tabProviders[tabbedPane.selectedIndex]
    activeProvider.loadData(newRepo.id, selectedBranch)
}
```

### Tab Load Strategy (Lazy Loading)

**When user opens tool window**:
1. Load repository & branch context
2. Load only the active tab (Vulnerabilities)
3. Other tabs load on-demand when selected

**Example for Vulnerabilities tab**:
```kotlin
class VulnerabilitiesTabPanel : JPanel(), TabProvider {
    override fun loadData(repositoryId: String, branch: String) {
        // Check cache first
        val cacheKey = cache.cacheKey(repositoryId, branch, "vulnerabilities")
        val findings = cache.getOrLoad(cacheKey) {
            // Fetch from API only if not cached
            apiClient.getVulnerabilities(repositoryId, branch)
        }
        
        // Update table with findings
        updateTable(findings)
    }
}
```

### Cache Invalidation Strategy

**Invalidate when**:
- User changes repository
- User changes branch
- User clicks "Refresh All" button
- Settings changed (credentials, API URL)

**Preserve when**:
- User switches between tabs (keep in-memory cache)
- Same tab loaded multiple times (use cached data)

---

## Deployment & Distribution

The plugin is built as a JAR artifact in `build/distributions/Debricked-IntelliJ-Plugin-*.zip`.

To publish:
1. Increment version in `build.gradle.kts`
2. Run `./gradlew.bat build`
3. Upload to JetBrains Marketplace (once approved)
4. GitHub Releases for manual installation

---

## Support & Resources

- **Debricked API Docs**: https://docs.debricked.com/tools-and-integrations/debricked-apis
- **IntelliJ Platform SDK**: https://plugins.jetbrains.com/docs/intellij/
- **JetBrains UI Guidelines**: https://jetbrains.design/
