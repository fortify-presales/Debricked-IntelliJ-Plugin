# Debricked IntelliJ Plugin

An IntelliJ IDEA plugin that brings **Debricked / Fortify Software Composition Analysis (SCA)** results into the IDE so developers can review open-source vulnerability findings without leaving their project.

## Status

This project is currently an **early implementation / MVP** with a working tabbed tool window.

What exists today:
- Debricked authentication via IntelliJ settings
- Secure credential storage using IntelliJ `PasswordSafe`
- Repository and branch loading/selection in the Debricked tool window
- Remote vulnerability retrieval from the Debricked API with server-driven pagination, sort, and search
- Vulnerabilities tab with flat sortable table and enriched card-based details pane
- Dashboard/Vulnerabilities/Dependencies/Licenses tab layout with shared header
- Configurable default startup tab (Dashboard by default)
- Local file navigation from vulnerability "Introduced through" cards
- Findings refresh and review-status workflow in the Vulnerabilities tab

What is planned next:
- Dashboard summary widgets and navigation actions
- Dependencies and licenses data tabs (currently placeholders)
- Smarter caching and refresh behaviour

## Goals

The plugin is intended to:
- surface Debricked vulnerability findings directly in IntelliJ
- help developers understand affected dependencies
- reduce context switching between the IDE and the Debricked web UI
- provide a foundation for future dependency and license views

## Current Feature Set

### Authentication
- Supports Debricked authentication from IntelliJ settings
- Stores sensitive credentials in IntelliJ `PasswordSafe`
- Keeps short-lived session/JWT data in memory rather than persisting it

### Tool Window
- Adds a **Debricked** tool window to IntelliJ
- Uses a shared header for repository and branch selection (searchable selectors)
- Orders tabs as Dashboard, Vulnerabilities, Dependencies, Licenses
- Uses IntelliJ `ContentManager` tab registration with `TabProvider` abstractions
- Lets users choose the default startup tab in settings
- Supports findings refresh from inside the Vulnerabilities tab

### Vulnerabilities Tab
- Fetches vulnerability findings from the Debricked API with paginated query requests
- Displays findings in a flat table with configurable columns and grouping/sort options
- Supports real-time search by vulnerability/dependency with debounced querying
- Renders enriched details cards for Actions, CISA KEV, Introduced through, Suggested fixes, and References
- Shows advisory source cards (CWE, GitHub, NVD), reachability analysis, and review status actions
- Opens local files from relevant finding file references when available

## Planned UI Direction

The current and near-term UX is a Debricked tool window with:
- a shared header for repository and branch selection
- tabs ordered as **Dashboard**, **Vulnerabilities**, **Dependencies**, and **Licenses**
- active implementation in **Vulnerabilities** and placeholder panels for the other tabs
- a configurable default startup tab (Dashboard by default)
- lazy-loading so only active tab data is fetched
- caching to reduce unnecessary API traffic and avoid rate-limit pressure

### Architecture Highlights

- Tab-based tool window architecture using IntelliJ `ContentManager`
- `TabProvider` pattern with `VulnerabilitiesTabProvider` (active) and `PassiveTabProvider` (placeholder tabs)
- Shared context header component for repository/branch selection across all tabs
- Per-context cache strategy keyed by repository/branch/tab query state
- Ongoing extraction of reusable/common UI from `TabbedToolWindow.kt` into focused modules

The detailed phased design lives in [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).

## Requirements

- IntelliJ IDEA **2023.2+**
- Java **17**
- A Debricked account and API access

## Build

```powershell
.\gradlew.bat build
```

## Run in a Development IDE

```powershell
.\gradlew.bat runIde
```

## Project Structure

```text
src/main/kotlin/com/debricked/intellijplugin/
  api/          Debricked API integration
  core/         Project-level orchestration and caching
  domain/       Shared models and state
  settings/     IntelliJ settings and credential handling
  ui/
    common/     Reusable shared UI components
    vulnerability/ Vulnerability table model, renderers, and formatting helpers
    dependency/ Dependency models/table UI components
    TabbedToolWindow.kt  Main tool window orchestration
    TabProvider.kt       Tab provider interfaces/implementations
  actions/      IntelliJ actions
```

## Configuration

At a high level, the plugin needs:
- Debricked API URL
- authentication details
- a selected Debricked repository

Sensitive values should never be committed to source control.

## Documentation

- [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) - phased implementation plan and target architecture
- [`etc/debricked-api.json`](etc/debricked-api.json) - Debricked API reference used by the project
- [`etc/debricked-tabbed-view-guidance.md`](etc/debricked-tabbed-view-guidance.md) - reference for the planned tabbed UX

## Notes

- This repository is under active development.
- The tabbed architecture is in place and continues to evolve as Dashboard, Dependencies, and Licenses move from placeholder to full implementations.
