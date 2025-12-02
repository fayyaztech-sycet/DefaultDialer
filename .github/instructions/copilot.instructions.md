Project instructions — developer testing & CI guidance
=====================================================

This file contains short, practical instructions to build and test the project locally (and what changed recently).

Summary of recent changes
-------------------------
- Dark-first Material3 theme implemented for Compose (lime-accent primary, dark backgrounds and surfaces).
- Contact list and Call history screens now support server-side filtering, search (debounced), pagination (LIMIT/OFFSET fallback), deletion with confirmation, and permission flows.
- Dialer screen now includes a TopAppBar and uses Material theme tokens for FAB, surfaces, and background.
- Resource colors updated (res/values/colors.xml) and Compose theme tokens updated under app/src/main/java/.../ui/theme.

Why this matters
-----------------
- We rely primarily on a Compose theme (Material3) but we also added resource color tokens for view-based components. When changing resources or theme-related code, re-run resource merging and assemble to catch issues early.

Local build & verification commands (recommended)
-----------------------------------------------
Run these from the repository root using bash.

- Compile only Kotlin sources (fast):
	```bash
	./gradlew :app:compileDebugKotlin -q
	```

- Full resource processing (merge resources) — helpful after changes to res/values/*.xml:
	```bash
	./gradlew :app:processDebugResources -q
	```

- Full app assemble (recommended to verify a full build):
	```bash
	./gradlew assembleDebug -q
	```

- Run unit tests (if present):
	```bash
	./gradlew testDebugUnitTest -q
	```

Quick manual verification checklist
---------------------------------
1. Build completes successfully (use `assembleDebug`).
2. Launch on emulator / test device (if available) and confirm these screens behave correctly:
	 - History: Filters (All, Missed, Answered, Incoming), Search, Pagination, per-item delete, confirmation dialog.
	 - Contacts: Search + filters, pagination, per-contact delete confirmation.
	 - Dialer: App bar present, background and FAB match theme colors.
3. Verify the app requests runtime permissions where required (READ/WRITE call log, READ/WRITE contacts, CALL_PHONE) and gracefully handles denials.

Notes / troubleshooting
-----------------------
- If a resource-linking error occurs (missing attr/style), verify the `com.google.android.material:material` dependency is present in `app/build.gradle.kts`. We added that to support Material3 DayNight theme attributes.
- If you intentionally changed platform resource themes (res/values/themes.xml), re-run `processDebugResources` to surface missing attributes.
- Compose uses Kotlin-based theming (`ui/theme/Theme.kt`) — prefer updating that for Compose UI. Resource-based themes are for view-system components or when you need platform-level theming.

If anything in this file is out-of-date after further changes, please update it to reflect new run scripts or required checks.

Example quick run (recommended):
```bash
./gradlew assembleDebug -q && echo "assembleDebug passed"
```

