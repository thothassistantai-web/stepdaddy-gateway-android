# Compact Player Focus UX

The bottom-panel **Player** tab uses a two-mode focus model so D-pad Up/Down can scroll the dashboard while video plays in the background.

## Browse mode (default)

- Video keeps playing; the preview surface does **not** consume D-pad Up/Down.
- Focus path: tab bar → **Ch −** → **Play** → **Ch +** → **Fullscreen** (row below the video).
- Press **Ch −** / **Ch +** (OK) to change channel.
- Optional: focus the video preview or **Controls** chip and press **OK** to enter control mode.
- Hardware **CHANNEL_UP** / **CHANNEL_DOWN** always change channel while the Player tab is visible.

## Player control mode

- Enter: **OK** on the video preview or **Controls** chip (top-right).
- Semi-transparent overlay on the video: Ch −, Play/Pause, Ch +, Fullscreen.
- **D-pad Up/Down** change channel (when focus is inside the player tab content).
- **Back** exits control mode and restores browse behavior (page scroll works again).
- First entry shows a one-time toast: “Press Back to exit player controls”.

## Unchanged

- **History** tab: **OK** on a row still tunes that channel and switches to Player.
- **Fullscreen** button / overlay action still opens `PlayerFullscreenActivity` (Back exits fullscreen there).

## Key files

- `CompactPlayerController.kt` — browse vs control-mode key handling
- `DashboardBottomPanel.kt` — overlay UI, focus chain, activity key dispatch
- `include_main_bottom_panel.xml` — control row and video container
- `include_player_control_overlay.xml` — overlay layout
