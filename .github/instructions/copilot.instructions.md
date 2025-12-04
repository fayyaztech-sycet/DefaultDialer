Project instructions — developer testing & CI guidance
=====================================================

This file contains short, practical instructions to build and test the project locally (and what changed recently).

Summary of recent changes
-------------------------
- Dark-first Material3 theme implemented for Compose (lime-accent primary, dark backgrounds and surfaces).
- Contact list and Call history screens now support server-side filtering, search (debounced), pagination (LIMIT/OFFSET fallback), deletion with confirmation, and permission flows.
- Dialer screen now includes a TopAppBar and uses Material theme tokens for FAB, surfaces, and background.
- Resource colors updated (res/values/colors.xml) and Compose theme tokens updated under app/src/main/java/.../ui/theme.

Project Structure & Key Components
----------------------------------
### Core Architecture
- **Tech Stack**: Kotlin, Jetpack Compose (Material3), MVVM.
- **Min SDK**: 24 | **Target SDK**: 36.

### Critical Files
- **Manifest** (`app/src/main/AndroidManifest.xml`): Defines `DefaultInCallService` (Telecom interaction), `CallScreenActivity` (In-Call UI), and permissions.
- **Main Entry**: `MainActivity.kt` (Dialer, History, Contacts tabs).
- **In-Call Service**: `services/DefaultInCallService.kt`. This is the brain of the dialer, handling call states (`onCallAdded`, `onCallRemoved`) and interacting with the Telecom framework.
- **In-Call UI**: `ui/call/CallScreenActivity.kt`. The screen user sees during a call.
- **Call Screening**: `services/CallScreeningService.kt`.

### Data Flow
- **Calls**: Managed by `DefaultInCallService`. State changes are propagated to UI components.
- **UI**: Built with Compose. Theming is handled in `ui/theme/`.

### Coding Standards
- **UI**: Prefer Jetpack Compose for all new features.
- **Theming**: Use Material3 tokens. Update `ui/theme/Theme.kt` for global changes.
- **Async**: Use Kotlin Coroutines and Flow.

Critical Code Paths & Functionality
-----------------------------------
### 1. Call Management (`DefaultInCallService.kt`)
- **Lifecycle**: `onCallAdded` and `onCallRemoved` are the entry/exit points for Telecom calls. `currentCall` is a static reference used by UI.
- **Audio Routing**:
    - `muteCall(Boolean)`: Tries `Call.setMuted` via reflection (for API compatibility) then falls back to `AudioManager.isMicrophoneMute`.
    - `setSpeaker(Boolean)`: Forces `AudioManager.mode = MODE_IN_COMMUNICATION` before setting `isSpeakerphoneOn` to ensure reliable routing on OEMs (Samsung/Xiaomi).
- **Launching UI**: `launchCallScreen` uses specific flags (`NEW_TASK | CLEAR_TOP | NO_ANIMATION`) to prevent freezes on lock screens (Android 14+).

### 2. In-Call UI (`CallScreenActivity.kt`)
- **Audio Focus**: `requestAudioFocusIfNeeded()` attempts `AUDIOFOCUS_GAIN_TRANSIENT` first, falling back to `AUDIOFOCUS_GAIN` if denied. This is crucial for speakerphone to work.
- **Speaker Toggle**: `setSpeakerphoneOn(Boolean)` implements a robust sequence:
    1. Request Audio Focus.
    2. Set Mode to `MODE_IN_COMMUNICATION`.
    3. Stop Bluetooth SCO if active (force routing to speaker).
    4. Set `isSpeakerphoneOn`.
    5. Fallback to `MODE_IN_CALL` if the state doesn't stick.
- **State Updates**: Uses `DisposableEffect` to register a `Call.Callback`. Updates `callState`, `elapsedTime`, and `phoneNumber` (re-resolving from `schemeSpecificPart` or Intent extras).

### 3. Navigation (`MainActivity.kt`)
- **Dialer Overlay**: The Dialer is not a separate activity but a full-screen overlay (`DialerScreen`) toggled via `showDialer` state in `MainActivity`. This allows quick access from the FAB.

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

whatever need to debug add to logs ill share logs to you from adb

