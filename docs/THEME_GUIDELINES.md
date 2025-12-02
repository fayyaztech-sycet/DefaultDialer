App theme and usage guidelines

Overview
--------
This project uses a dark-first design with a high-contrast lime-green accent pulled from the brand/logo. The aim is a modern "digital"/tech feel with readable, accessible text on dark backgrounds.

Palette (primary values)
-------------------------
- Primary (Accent): Lime Green — #9FE633 (use for primary action accents: FAB, switches, progress)
- On Primary (text/icons on primary): #121212 (dark text for contrast on the lime accent)
- Background (App background): #121212 (main app surface)
- Surface/Card: #1D1D1D (elevated surfaces, cards, dialogs)
- Surface Variant: #2A2A2A (slightly lighter surfaces when you need separation)
- Primary text (on dark): #E0E0E0 (main content text and headings)
- Secondary text and hints: #888888 (subtle secondary labels and hint text)
- Divider / subtle separators: #2E2E2E

Principles and examples
-----------------------
- Use MaterialTheme.colorScheme.primary for the app accent (lime). Use it for:
  - Floating action buttons (FAB)
  - Primary action Buttons
  - Switches / toggles when active
  - Progress indicators (tinted)

- Use MaterialTheme.colorScheme.background for screen backgrounds and primary canvas.

- Use MaterialTheme.colorScheme.surface and surfaceVariant for cards, sheets, and other elevated components.

- Text color:
  - Use MaterialTheme.colorScheme.onBackground (or onSurface) for primary text.
  - Use MaterialTheme.colorScheme.secondary for secondary text, hints and labels.

- On-color contrast:
  - On primary elements (for example, a FAB placed on the lime color) we use MaterialTheme.colorScheme.onPrimary (set to dark #121212) to keep contrast high.

Quick code examples
-------------------
- Floating Action Button

    FloatingActionButton(
        onClick = { /* action */ },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) { /* icon content */ }

- Card surface

    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) { /* content */ }

- Text

    Text(text = "Title", color = MaterialTheme.colorScheme.onBackground)
    Text(text = "Hint / secondary", color = MaterialTheme.colorScheme.secondary)

Accessibility and testing
-------------------------
- Test contrast for critical interactive states (primary buttons, icons) against WCAG contrast guidelines.
- Use onPrimary/onBackground values for labels placed over the accent or dark backgrounds respectively.

When to use accent sparingly
---------------------------
- The accent color is bright and intended for high-signal UI elements. Keep its usage limited to avoid visual noise — primary actions, highlights, and small UI details (focus states).

Notes
-----
- Some Android APIs will override or adapt colors when dynamic colors are enabled. The app defaults to the custom palette above for both dynamic and non-dynamic scenarios.

If you'd like a variant for the light theme or additional tokens (elevation shades, semantic color names), I can add those next.
