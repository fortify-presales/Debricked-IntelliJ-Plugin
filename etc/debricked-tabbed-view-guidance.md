# Debricked IntelliJ Plugin Tabbed Tool Window Guidance

## Purpose

This document describes a recommended tabbed layout for the Debricked IntelliJ plugin tool window. It covers how to structure separate views for vulnerabilities, dependencies, licenses, and an optional dashboard while keeping repository and branch selection consistent across the plugin.

The goal is to provide a clean, IntelliJ-native experience that separates different types of Software Composition Analysis data without overwhelming the user.

## Current Implementation Snapshot (2026-08-19)

This document is guidance for target UX. Current implementation status in this repository:

- Vulnerabilities tab is the active, implemented tab.
- Dashboard, Dependencies, and Licenses are visible as placeholder tabs.
- Tabs are registered through IntelliJ `ContentManager` with a shared repository/branch header.
- Default startup tab is configurable; current default is Dashboard.

---

# Recommended High-Level Layout

```text
Debricked
┌─────────────────────────────────────────────────────────────┐
│ Dashboard | Vulnerabilities | Dependencies | Licenses       │
├─────────────────────────────────────────────────────────────┤
│ Repository: [payment-service ▼] [↻ Refresh]                │
│ Branch:     [main            ▼]                            │
├─────────────────────────────────────────────────────────────┤
│ Content for selected tab                                   │
└─────────────────────────────────────────────────────────────┘
```

## Key Recommendation

Use top-level tabs inside the Debricked tool window for distinct types of information:

```text
Dashboard
Vulnerabilities
Dependencies
Licenses
```

Repository and branch selectors should be shared across all tabs.

Changing the repository or branch should update the currently selected tab and invalidate or refresh cached data for the other tabs as needed.

---

# Why Use Tabs?

A tabbed layout is recommended because vulnerabilities, dependencies, and licenses represent related but distinct workflows.

## Benefits

- Keeps the UI clean and focused.
- Avoids mixing unrelated data in one large view.
- Allows each tab to evolve independently.
- Supports different filtering and sorting models per tab.
- Makes the plugin easier to extend later.
- Matches common IntelliJ tool window patterns.

## User Mental Model

Users are likely to think about Debricked data in separate categories:

```text
What vulnerabilities do I have?
What dependencies are in this project?
Do I have license policy issues?
What is the overall health of this repository?
```

Tabs map naturally to these questions.

---

# Shared Header Area

```text
Repository: [payment-service ▼] [↻ Refresh]
Branch:     [main            ▼]
```

## Purpose

The shared header controls the context for all tabs.

## Behaviour

The repository and branch selectors should:

1. Load automatically when the tool window opens.
2. Restore the previously selected repository for the current IntelliJ project.
3. Restore the previously selected branch for the selected repository.
4. Refresh the active tab when repository or branch changes.
5. Preserve cached data for inactive tabs where appropriate.
6. Fall back to a loading state when data is stale or missing.

## Refresh Button

The refresh button should reload data for the current context.

Recommended behaviour:

```text
Refresh selected tab by default
```

Optional dropdown behaviour:

```text
Refresh ▼
├─ Refresh Current Tab
├─ Refresh Vulnerabilities
├─ Refresh Dependencies
├─ Refresh Licenses
└─ Refresh All
```

---

# Tab 1: Dashboard

```text
Dashboard
```

## Purpose

The Dashboard tab provides a high-level summary of the repository and branch security posture.

This is useful as a landing page, especially when the user first opens the Debricked tool window.

## Recommended Content

```text
Repository: payment-service
Branch: main

Vulnerabilities
Critical: 2
High:     5
Medium:   12
Low:      8

Dependencies
Total dependencies: 421
Outdated dependencies: 37
Vulnerable dependencies: 9

Licenses
Approved licenses: 66
Policy warnings:   2
Policy violations: 1

Last scan
2026-08-11 10:42
```

## Recommended Actions

```text
[View Vulnerabilities]
[View Dependencies]
[View License Issues]
[Open in Debricked]
```

## Behaviour

The Dashboard should:

- load summary data quickly;
- show counts and status indicators;
- allow navigation to the detailed tabs;
- show last scan or sync timestamp;
- show a clear empty state if no scan data exists.

## Empty State

```text
No Debricked scan data found for this repository and branch.

[Refresh]
[Open in Debricked]
```

---

# Tab 2: Vulnerabilities

```text
Vulnerabilities
```

## Purpose

The Vulnerabilities tab is the primary security findings view.

It should show CVEs, severities, affected packages, fixed versions, dependency paths, and remediation guidance.

## Recommended Layout

```text
Vulnerabilities
┌─────────────────────────────────────────────────────────────┐
│ Severity: [All ▼]  Search: [____________________]           │
├───────────────────────┬─────────────────────────────────────┤
│ Findings (19)         │ Vulnerability Details               │
├───────────────────────┼─────────────────────────────────────┤
│ 🔴 Critical (2)       │ CVE-2025-1234                       │
│   ▶ Log4j             │ Severity: Critical                  │
│   ▶ Commons-IO        │ CVSS: 9.8                           │
│                       │ Affected package: log4j:2.14.0      │
│ 🟠 High (5)           │ Fixed version: 2.17.2               │
│   ▶ Jackson           │                                     │
│   ▶ Spring Core       │ Remediation                         │
│                       │ Upgrade to 2.17.2 or later.         │
└───────────────────────┴─────────────────────────────────────┘
```

## Recommended Controls

```text
Severity filter
Search box
Findings tree
Details pane
Open Dependency button
Open in Debricked button
```

## Behaviour

The Vulnerabilities tab should:

1. Load findings for the selected repository and branch.
2. Support grouping (severity or other fields) as a view option.
3. Allow filtering by severity.
4. Allow local search across package names, CVEs, CWEs, and advisory titles.
5. Display details when a finding is selected.
6. Open a full vulnerability report in an editor tab on double-click.

Current implementation note: findings are shown in a flat table with configurable sort/group options.

## Recommended Double-Click Behaviour

```text
Double-click finding
    ↓
Open full vulnerability report in IntelliJ editor tab
```

Example editor tab title:

```text
CVE-2025-1234 - Log4j
```

---

# Tab 3: Dependencies

```text
Dependencies
```

## Purpose

The Dependencies tab lists the components detected in the selected repository and branch.

This is useful even when dependencies are not vulnerable because it gives developers visibility into the software bill of materials.

## Recommended Layout

```text
Dependencies
┌─────────────────────────────────────────────────────────────┐
│ Ecosystem: [All ▼]  Search: [____________________]          │
├───────────────────────┬─────────────────────────────────────┤
│ Dependencies (421)    │ Dependency Details                  │
├───────────────────────┼─────────────────────────────────────┤
│ spring-boot  3.3.2    │ Package: spring-boot                │
│ log4j        2.14.0   │ Version: 3.3.2                      │
│ jackson      2.16.1   │ Ecosystem: Maven                    │
│ guava        33.0     │ Scope: Direct                       │
│                       │                                     │
│                       │ Known Vulnerabilities: 0            │
│                       │ License: Apache-2.0                 │
│                       │                                     │
│                       │ [Open Dependency] [Open in Debricked]│
└───────────────────────┴─────────────────────────────────────┘
```

## Recommended Controls

```text
Ecosystem filter
Search box
Dependency list or tree
Dependency details pane
Open Dependency button
Open in Debricked button
```

## Dependency List Fields

Each dependency row should show:

```text
Package name
Version
Ecosystem
Direct or transitive status
Vulnerability count
License
```

## Example Rows

```text
spring-boot       3.3.2    Maven   Direct      0 vulnerabilities   Apache-2.0
log4j             2.14.0   Maven   Transitive  3 vulnerabilities   Apache-2.0
jackson-databind  2.16.1   Maven   Direct      1 vulnerability     Apache-2.0
```

## Behaviour

The Dependencies tab should:

1. Load all dependencies for the selected repository and branch.
2. Allow filtering by ecosystem.
3. Allow searching by package name and version.
4. Show whether a dependency is direct or transitive.
5. Show whether the dependency has known vulnerabilities.
6. Show the license for each dependency if available.
7. Navigate to the dependency declaration where possible.

## Useful Detail Sections

```text
Overview
Dependency Path
Known Vulnerabilities
License
Usage Locations
```

---

# Tab 4: Licenses

```text
Licenses
```

## Purpose

The Licenses tab shows license usage and license policy issues for the selected repository and branch.

This is important for open source governance and compliance workflows.

## Recommended Layout

```text
Licenses
┌─────────────────────────────────────────────────────────────┐
│ Policy Status: [All ▼]  Search: [____________________]      │
├───────────────────────┬─────────────────────────────────────┤
│ License Summary       │ License Details                     │
├───────────────────────┼─────────────────────────────────────┤
│ ✅ Apache-2.0 (45)    │ License: GPL-3.0                    │
│ ✅ MIT (21)           │ Policy status: Violation            │
│ ⚠ GPL-3.0 (2)         │                                     │
│ ⚠ AGPL-3.0 (1)        │ Packages                            │
│                       │ - library-a                         │
│                       │ - library-b                         │
│                       │                                     │
│                       │ Reason                              │
│                       │ GPL-3.0 is not approved by policy.  │
│                       │                                     │
│                       │ [View Policy] [Open in Debricked]   │
└───────────────────────┴─────────────────────────────────────┘
```

## Recommended Controls

```text
Policy status filter
Search box
License summary tree
License details pane
View Policy button
Open in Debricked button
```

## Recommended Policy Status Values

```text
All
Approved
Warning
Violation
Unknown
```

## Behaviour

The Licenses tab should:

1. Load detected licenses for the selected repository and branch.
2. Group packages by license.
3. Highlight policy warnings and violations.
4. Show affected packages for each license.
5. Allow the user to open the relevant policy or Debricked page.
6. Support search by license name, package name, and policy status.

## Example License Groups

```text
✅ Apache-2.0 (45 packages)
✅ MIT (21 packages)
⚠ GPL-3.0 (2 packages)
⛔ AGPL-3.0 (1 package)
```

---

# Optional Nested Tabs Inside Details Panels

For richer details, each tab can also use secondary tabs inside the right-hand details pane.

## Vulnerability Details Tabs

```text
Overview | Dependency Path | References | Remediation
```

## Dependency Details Tabs

```text
Overview | Dependency Path | Vulnerabilities | License
```

## License Details Tabs

```text
Overview | Packages | Policy | References
```

## Recommendation

Use nested detail tabs only when the information becomes too large for a single details pane.

For the first implementation, keep details simple and add nested tabs later.

---

# Recommended Implementation Order

## Phase 1: Vulnerabilities Tab

Build the main value proposition first.

Implement:

```text
Shared repository selector
Shared branch selector
Refresh button
Vulnerabilities tab
Findings tree
Basic vulnerability details pane
```

## Phase 2: Local Scan

Add local scan orchestration and refresh workflows.

Implement:

```text
Run local scan action
Preflight checks (CLI/auth/config)
Scan progress and completion state
Refresh vulnerabilities after successful scan
```

## Phase 3: Dependencies Tab

Add a summary view.

Implement:

```text
Vulnerability counts
Dependency counts
License issue counts
Last scan timestamp
Navigation buttons to detailed tabs
```

## Phase 4: Licenses Tab

Add license governance support.

Implement:

```text
License summary
Policy status filter
License details pane
Package list by license
View Policy action
```

## Phase 5: Dashboard Tab

Add a summary landing view.

Implement:

```text
Vulnerability counts
Dependency counts
License issue counts
Last scan timestamp
Navigation buttons to detailed tabs
```

## Phase 6: Rich Detail Tabs

Add nested details for more advanced investigation.

Implement:

```text
Vulnerability detail tabs
Dependency detail tabs
License detail tabs
Editor tab reports
```

---

# Data Loading Strategy

## Recommended Approach

Use lazy loading per tab.

```text
Open tool window
    ↓
Load repository and branch context
    ↓
Load active tab only
    ↓
Load other tabs when selected
```

## Why Lazy Loading?

Lazy loading is preferred because:

- it keeps the tool window fast to open;
- it avoids unnecessary API calls;
- it reduces pressure on the Debricked API;
- it avoids loading license and dependency data when the user only wants vulnerabilities.

## Cache Behaviour

Cache data per repository and branch.

Example cache keys:

```text
repositoryId + branch + vulnerabilities
repositoryId + branch + dependencies
repositoryId + branch + licenses
repositoryId + branch + dashboard
```

When repository or branch changes, cached data for the previous context should not be shown as current data.

---

# State Persistence

## Global State

Store authentication and service configuration globally:

```text
API URL
API token
Organisation
Plugin defaults
```

API tokens should be stored securely using IntelliJ credential storage.

## Project State

Store project-specific selections:

```text
selectedRepositoryId
selectedRepositoryName
selectedBranchByRepository
lastSelectedTab
```

## Session State

Store transient UI state during the current IDE session:

```text
expandedGroups
searchTextByTab
filterByTab
selectedItemByTab
splitPanePositionByTab
```

---

# Error Handling

## Shared Error Behaviour

All tabs should use consistent error handling.

Example:

```text
Unable to load vulnerabilities.

Reason:
Connection timeout

[Retry]
```

## Authentication Error

```text
Debricked authentication failed.

Please check your API token in Settings.

[Open Settings]
```

## No Repository Selected

```text
Select a Debricked repository to view results.

[Select Repository]
```

## No Branch Selected

```text
Select a branch to view results.
```

## No Data Available

```text
No data found for this repository and branch.

[Refresh]
[Open in Debricked]
```

---

# Recommended Default Tab

There are two good options.

## Option 1: Dashboard as Default

Use Dashboard as the default if you want a broad landing view.

Best when the plugin supports vulnerabilities, dependencies, and licenses from early versions.

## Option 2: Vulnerabilities as Default

Use Vulnerabilities as the default if the MVP is primarily focused on security findings.

Best for early versions of the plugin.

## Recommendation

Use a configurable default tab and tune it to user/team workflow.

Current project default:

```text
Dashboard
```

If the workflow is vulnerability-first, switch the default tab to:

```text
Vulnerabilities
```


---

# Recommended Final UX

```text
Debricked
┌─────────────────────────────────────────────────────────────┐
│ Dashboard | Vulnerabilities | Dependencies | Licenses       │
├─────────────────────────────────────────────────────────────┤
│ Repository: [payment-service ▼] [↻ Refresh]                │
│ Branch:     [main            ▼]                            │
├─────────────────────────────────────────────────────────────┤
│ Selected tab content                                       │
└─────────────────────────────────────────────────────────────┘
```

## Summary

The tabbed Debricked tool window should:

- keep repository and branch selection shared;
- separate vulnerabilities, dependencies, and licenses into distinct tabs;
- lazy-load tab content;
- cache data by repository and branch;
- persist the last selected tab per project;
- use master-detail layouts within each tab;
- support filters and search appropriate to each view;
- keep deep investigation available through editor tabs or nested detail tabs.

This gives the plugin a clean, scalable structure that can start simple and grow into a full Debricked IDE experience.
