# Copilot Instructions: Debricked IntelliJ Plugin

## Project Overview

This is an IntelliJ IDEA plugin that integrates **Debricked** (open-source vulnerability scanning) directly into the IDE. Developers can authenticate with Debricked, select a repository, and view vulnerability findings organized by severity in a tool window.

**Status**: Phase 1 complete (tabbed architecture + vulnerabilities experience + persistence + resiliency). Phase 2 (local scan orchestration) is planned.

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

## Current Architecture Snapshot

The active UI architecture is already tabbed and provider-based:

- Tool window shell: `DebrickedTabbedToolWindowContent` in `src/main/kotlin/com/debricked/intellijplugin/ui/TabbedToolWindow.kt`
- Tab provider contracts: `src/main/kotlin/com/debricked/intellijplugin/ui/TabProvider.kt`
  - `VulnerabilitiesTabProvider` is active
  - `PassiveTabProvider` is used for Dashboard/Dependencies/Licenses placeholders
- Shared header controls: `src/main/kotlin/com/debricked/intellijplugin/ui/common/ToolWindowContextHeader.kt`
- Vulnerability table/model/renderers:
  - `src/main/kotlin/com/debricked/intellijplugin/ui/vulnerability/VulnerabilityTableModel.kt`
  - `src/main/kotlin/com/debricked/intellijplugin/ui/vulnerability/VulnerabilityRenderers.kt`
  - `src/main/kotlin/com/debricked/intellijplugin/ui/vulnerability/VulnerabilityFormatting.kt`

Current behavior to preserve:

1. Only the active tab loads data; unimplemented tabs stay passive placeholders.
2. Vulnerabilities data refresh is deduped during startup/context setup.
3. Vulnerability table state persists (columns, sort, group, search, rows per page).
4. Vulnerability details split-pane divider position persists.
5. Timeout/connect failures keep stale results visible and publish timeout state.

## When Implementing Features

### Adding a new setting (e.g., branch preference per repository)

1. Add field to `DebrickedSettingsManager` + `@State` persistence
2. Add input in `DebrickedSettingsConfigurable` UI
3. Publish `DebrickedSettingsNotifier.TOPIC` in `apply()`
4. Subscribe in `DebrickedPluginManager.init()` if refresh is needed
5. Read value in `DebrickedApiClient` when calling Debricked API

**Example**: See Phase 2 branch selection in [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md#phase-2)

### Adding a new UI panel (e.g., details side-pane)

1. Prefer creating UI classes under `src/main/kotlin/.../ui/common` or `src/main/kotlin/.../ui/vulnerability`
2. Use `GridBagLayout` for alignment or `BorderLayout` for sectioning
3. Apply theme-aware colors: `JBColor(lightColor, darkColor)`
4. Register updates via the tool window/provider flow (`DebrickedTabbedToolWindowContent` + `TabProvider`)
5. Test in `./gradlew.bat runIde`

**Example**: See the vulnerabilities implementation in `TabbedToolWindow.kt` and extracted classes in `ui/common` + `ui/vulnerability`

### Adding API pagination or new endpoint

1. Add method to `DebrickedApiClient` (handle JWT auth, error responses)
2. Follow server-driven query state (search/page/rows/sort/order) and cache by query key
3. Parse nested JSON responses carefully (Debricked wraps fields in objects)
4. Cache JWT in `DebrickedApiClient.cachedJwt` to avoid re-auth on every call
5. Test locally with real credentials via `./gradlew.bat runIde`

**Example**: See `getVulnerabilitiesPage()` and detail endpoints in `DebrickedApiClient.kt`

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

### Adding Unit Tests

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
| `TabbedToolWindow.kt` | Tool window orchestration + vulnerabilities panel/details | Tab wiring, load/invalidation flow, view-state behavior |
| `TabProvider.kt` | Tab provider contracts and implementations | Adding active providers for future tabs |
| `ui/common/ToolWindowContextHeader.kt` | Shared repository/branch header actions and selectors | Context selector behavior, MRU/search interactions |
| `ui/vulnerability/VulnerabilityTableModel.kt` | Vulnerability rows/group headers/sort/filter shaping | Table/group behavior changes |
| `ui/vulnerability/VulnerabilityRenderers.kt` | Table renderers/icons/style | Column rendering and visual signals |
| `DebrickedCredentialStore.kt` | Credential cache (memory + PasswordSafe) | Fixing auth issues, adding new credential types |
| `Models.kt` | Domain models and findings states | Extending findings/domain state safely |
| `IMPLEMENTATION_PLAN.md` | Phases, architecture, decisions | Source of truth for current phase scope |

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
- Implement Dependencies/Licenses active data loading before their planned phases
- Break startup refresh dedupe by forcing repeated loads in tab-selection events
- Replace query-key caching with eager full-list loading

✅ **Do**:
- Use `ApplicationManager.executeOnPooledThread { }` for background work
- Use in-memory cache for credentials; load once on EDT
- Subscribe to MessageBus topics for settings changes
- Test all changes with `./gradlew.bat runIde` before committing
- Include `ModalityState.any()` on all `invokeLater` calls
- Verify with real Debricked API (test locally first)
- Keep non-active tabs as placeholders until their planned implementation phase
- Preserve query-driven vulnerability refresh + caching behavior
- Keep timeout/connect handling non-destructive (stale results stay visible)
- Add focused logic tests for cache/settings/manager behavior when changing refresh logic

---

## Questions or Issues?

- **Architecture/Design**: See [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md#decisions--rationale)
- **API Behavior**: Check [Debricked API Docs](https://docs.debricked.com/tools-and-integrations/debricked-apis)
- **IntelliJ Platform**: Refer to [JetBrains Plugin SDK](https://plugins.jetbrains.com/docs/intellij/)
- **Gradle Build**: Check [`build.gradle.kts`](./build.gradle.kts) and run `./gradlew.bat build --info`

---

**Last Updated**: Phase 1 complete, aligned with current tabbed/provider architecture
