# Dashboard UI Refinement — FUSA Comparison

**Device:** FUSA2541006925  
**Date:** 2025-06-18  
**Screenshots:** `before.png` (pre-refinement baseline), `after.png` (post-refinement on FUSA)

## Approach chosen

**Option A — state-aware toggle + Restart** (preferred in spec)

- Single **Start/Stop toggle** button: green **START** with play icon when stopped, red **STOP** with stop icon when running.
- Separate equal-width **Restart** button (blue), enabled only while the service is active.
- Short labels: Start, Restart, Stop — no "Start Server" wording.

## Visual adjustments (mockup alignment)

| Area | Before | After |
|------|--------|-------|
| Card padding / gaps | 16dp / 12dp margins | 18dp padding, 14dp section gaps |
| Stat cards | 22sp values, 12sp labels | 24sp bold values, 11sp muted labels, 72dp min height, 28dp icons |
| Server / Management row | 1.15 : 0.85 weight | 1.2 : 0.8 — server controls slightly wider |
| Server buttons | 3 separate Start / Restart / Stop | 2 equal-width toggle + Restart with icons |
| Playlist / EPG actions | wrap-content buttons | equal-weight row buttons (40dp height) |
| Footer | metrics only | divider above bar, scroll-to-top button (right), tighter dot sizing |
| Focus | minimal TV affordance | teal focus ring drawable + 1.04× scale on focus |

## D-pad focus map

Stat cards are **non-focusable** (skipped). Flow wraps header ↔ footer.

```
Header gear (buttonHeaderSettings)
  ↓
Server toggle (buttonToggleServer) ↔ Restart (buttonRestart)
  ↓
Settings (buttonSettings) ↔ Install Apps (buttonInstallApps)
  ↓
Toggles: Auto Start ↔ Launch TiviMate ↔ Start on Boot ↔ Keep Gateway Alive
  ↓
Playlist: Copy ↔ Open ↔ QR  |  Launch ↔ Install
  ↓
EPG: Copy ↔ Open
  ↓
Footer scroll-top (buttonFooterScrollTop) → wraps up to Header gear
```

`android:nextFocusUp/Down/Left/Right` wired on all interactive controls. Sidebar health panel is display-only (no focus trap). ScrollView uses `descendantFocusability="afterDescendants"`.

## Logic changes

- Toggle click: start if stopped, stop if running.
- Restart: stop → 1.5s delay → start (unchanged).
- Button state syncs with `ServerService.isServiceActive`; health poll detects service state transitions and calls `updateStatus()`.
