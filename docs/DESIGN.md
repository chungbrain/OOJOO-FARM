# OOJOO-FARM Design System

This file is the contract for Android master, Android farmer/slave, and prototype-derived screens. It codifies the existing Material3 cartoon visual language; it is not a redesign and does not authorize decorative motion or web-specific styling in Android.

## 1. Atmosphere & Identity

OOJOO-FARM feels like a bright farm notebook turned into a Material3 app: warm cream paper, white sticker cards, thick ink outlines, hard offset shadows, extra-rounded shapes, bold labels, and playful green/teal identity colors. The signature is the cartoon panel system: every important surface reads as a hand-cut card with an ink border and a small physical offset shadow, while actions feel like chunky pressable buttons.

Master screens use the green family. Farmer/slave screens use the teal family. Both share the same warm neutral paper, ink, card, line, state, shape, elevation, spacing, typography, and accessibility rules.

## 2. Color

All Android colors must resolve through `OojooTheme` values or a documented exception below. New Task 4-15 UI must not introduce raw hex values in components; extend this table first if a real product state cannot map to an existing token.

### Named Palette

| Role | Token | Value | Source |
| --- | --- | --- | --- |
| Master primary | `master.primary.green` | `#4CAF50` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:36` |
| Master primary dark | `master.primary.green.dark` | `#2E7D32` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:37` |
| Master primary light | `master.primary.green.light` | `#A5D6A7` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:38` |
| Master primary background | `master.primary.green.bg` | `#E8F5E9` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:39` |
| Master bright lime | `accent.lime` | `#C6FF00` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:40` |
| Farmer primary | `farmer.primary.teal` | `#26A69A` | `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:20` |
| Farmer primary dark | `farmer.primary.teal.dark` | `#00695C` | `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:21` |
| Farmer primary light | `farmer.primary.teal.light` | `#80CBC4` | `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:22` |
| Farmer primary background | `farmer.primary.teal.bg` | `#E0F2F1` | `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:23` |
| Accent amber | `accent.amber` | `#FFC107` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:43` |
| Accent orange | `accent.orange` | `#FF6F00` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:44` |
| Error red | `status.error.red` | `#FF5252` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:45` |
| Accent blue | `accent.blue` | `#42A5F5` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:46` |
| Accent sky | `accent.sky` | `#4FC3F7` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:47` |
| Accent purple | `accent.purple` | `#AB47BC` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:48` |
| Accent pink | `accent.pink` | `#FF80AB` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:49` |
| Ink text and border | `neutral.ink` | `#2D3436` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:52` |
| Secondary ink | `neutral.ink.2` | `#4A4A4A` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:53` |
| Muted text | `neutral.muted` | `#7C7C7C` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:54` |
| More muted text | `neutral.muted.2` | `#A0A0A0` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:55` |
| Divider line | `neutral.line` | `#E0E0E0` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:56` |
| Subtle divider line | `neutral.line.2` | `#F0F0F0` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:57` |
| Warm app background | `surface.background.warm` | `#FFF8E1` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:58` |
| Card surface | `surface.card` | `#FFFFFF` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:59` |
| Sticky note surface | `surface.note.yellow` | `#FFFDE7` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:60` |

### Gradient Tokens

| Token | Value | Source |
| --- | --- | --- |
| `gradient.master.primary` | `#66BB6A -> #2E7D32` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:64` |
| `gradient.farmer.primary` | `#26A69A -> #004D40` | `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:33` |
| `gradient.weather` | `#4FC3F7 -> #1976D2` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:63` |
| `gradient.camera` | `#81C784 -> #2E7D32` | `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:34` |
| `gradient.sun` | `#FFD54F -> #FF9800` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:65` |
| `gradient.lime` | `#C6FF00 -> #2E7D32` | `prototype/app-emulator_v2.html:35` |
| `gradient.candy` | `#FF80AB -> #AB47BC` | `prototype/app-emulator_v2.html:37` |

### Color Rules

- Master identity surfaces use `master.primary.green`, `master.primary.green.dark`, `master.primary.green.light`, and `gradient.master.primary`.
- Farmer identity surfaces use `farmer.primary.teal`, `farmer.primary.teal.dark`, `farmer.primary.teal.light`, and `gradient.farmer.primary`.
- `neutral.ink` is both primary text and the cartoon outline. It must remain high-contrast and must not be replaced by low-contrast gray borders.
- Empty states use `surface.note.yellow`, `neutral.muted`, and an existing accent chip if needed.
- Error states use `status.error.red` on `surface.card` or `surface.note.yellow`; do not place red text directly on green/teal gradients.
- Loading states use the owning identity gradient plus `neutral.line.2` tracks.
- Disabled states use `neutral.line` surfaces and `neutral.muted` text, matching `disabledContainerColor = OojooTheme.Line` and `disabledContentColor = OojooTheme.Muted`.

## 3. Typography

The Android implementation uses Material3 typography with cartoon weight overrides. Keep the platform font stack; do not import decorative typefaces into Android. Prototype-only font hints such as `Comic Sans MS` are aesthetic references, not Android dependencies.

| Level | Size/Style | Weight | Line height | Source/use |
| --- | --- | --- | --- | --- |
| App bar title | `18.sp` | `FontWeight.Black` | Material3 default | `CartoonAppBar`, `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:290` |
| Screen/card title | `20.sp` or `titleLarge` | `FontWeight.Black` | Material3 default | Theme editor preview title, `ThemeEditorScreen.kt:53`; `titleLarge` override at `Theme.kt:104` |
| Section heading | `18.sp` | `FontWeight.Bold` | Material3 default | `ThemeEditorScreen.kt:62`, `ThemeEditorScreen.kt:119` |
| Button label | `15.sp` | `FontWeight.ExtraBold` | Material3 default | `GradientButton`/`OutlineButton`, `Theme.kt:182`, `Theme.kt:202` |
| Field placeholder | Material3 field text | `FontWeight.Bold` | Material3 default | `OojooField`, `Theme.kt:217` |
| Chip label | `12.sp` | `FontWeight.ExtraBold` | Material3 default | `OojooChip`, `Theme.kt:248-249` |
| Body | `14.sp` to Material3 body | Regular or medium | At least `1.4x` for Latin | Theme editor body, `ThemeEditorScreen.kt:55` |
| Metadata/helper | `12.sp` to `13.sp` | Regular/medium | `18.sp` minimum for long Korean helper text | Farmer settings helper, `SettingsScreen.kt:70-72`; restart helper, `ThemeEditorScreen.kt:147` |
| Prototype hero/stat | `28px`, `22px`, `17px`, `15px` | `700-900` | `1.0-1.5` | `prototype/app-emulator_v2.html:99-104` |

### CJK/Latin Line-Height And Label Expansion

- CJK text uses at least `1.45x` line height for body/helper copy and must never rely on clipped Material defaults inside compact rows.
- Latin labels may use Material3 defaults when single-line; mixed Korean/English labels must allow expansion to two lines before truncation.
- Labels in settings, endpoint editing, picker rows, and export status must use full strings; no fixed-width assumptions based on English.
- For `360dp`, prefer stacked label/value layouts when Korean text or long endpoint URLs would crowd controls.
- For `600dp`, labels may sit beside controls only if CJK and Latin strings both fit without ellipsis.
- Letter spacing stays `0` for body, labels, and buttons. The existing `headlineLarge` `letterSpacing = 8.sp` is reserved for code-style displays only and must not be reused for prose.

## 4. Spacing & Layout

### Shape, Stroke, And Spacing Tokens

| Token | Value | Source |
| --- | --- | --- |
| `shape.card.radius` | `cornerRadius: Int = 24`; `RoundedCornerShape(24.dp)` default | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:26`, `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:35` |
| `shape.button.radius` | `RoundedCornerShape(18.dp)` default; master computes `cornerRadius * 0.8` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:70`, `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:36` |
| `shape.field.radius` | `RoundedCornerShape(18.dp)` default; master computes `cornerRadius * 0.8` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:71`, `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:37` |
| `shape.small.radius` | `RoundedCornerShape(12.dp)` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:72` |
| `shape.pill.radius` | `RoundedCornerShape(50)` / prototype `999px` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:69`, `prototype/app-emulator_v2.html:26` |
| `stroke.cartoon` | `borderWidth: Int = 2`; `BorderStroke(2.dp, Ink)` default | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:28`, `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:39` |
| `space.screen.padding` | `20.dp` master editor, `16.dp` farmer settings | `ThemeEditorScreen.kt:48`, `SettingsScreen.kt:41` |
| `space.section.gap` | `24.dp` major sections, `16.dp` controls, `12.dp` farmer setting rows | `ThemeEditorScreen.kt:49`, `ThemeEditorScreen.kt:61`, `SettingsScreen.kt:41` |
| `space.card.padding` | `16.dp` Android card, `18px` prototype card | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:153`, `prototype/app-emulator_v2.html:108` |
| `space.button.padding` | `PaddingValues(vertical = 15.dp, horizontal = 18.dp)` | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:175`, `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:74` |
| `space.inline.gap` | `8.dp` icon/radio-to-label gap | `ThemeEditorScreen.kt:138` |
| `size.appbar.height` | `64.dp` plus status bar padding | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:276-278` |
| `size.touch.min` | `48.dp` minimum target, `64.dp` preferred app-bar/back targets | Material3 baseline and `CartoonAppBar` height |

### 360dp Layout

- Treat `360dp` as the minimum phone contract. Screens use one column, `16.dp` to `20.dp` horizontal padding, full-width buttons/fields, and vertical rhythm from `space.section.gap`.
- Photo timeline rows stack thumbnail, metadata, state chip, and action buttons vertically when labels expand.
- Photo grid uses two columns only when each tile can keep a stable square thumbnail and a minimum `48.dp` action target; otherwise it falls back to one column.
- Endpoint editor keeps server URL field full-width with reconnect button below it; do not squeeze URL text into a narrow row.
- Clip export progress uses a full-width progress bar and single primary action per row.

### 600dp Layout

- Treat `600dp` as the expanded compact tablet contract. Keep the cartoon card language, but allow two-column content inside a single page gutter.
- Settings IA may use a two-column grid of independent `SettingsSection` cards when each card has enough room for CJK labels.
- Photo grid may use three columns with stable square thumbnails; fullscreen viewer controls remain edge-aligned and touch-safe.
- Server endpoint editor may place reconnect/save actions in a trailing column only if the URL field keeps at least half the width.
- Clip export preview may sit beside progress/share controls; preview must not be nested inside another card.

## 5. Components & States

Every reusable component must provide default, pressed, focused, disabled, loading, empty, and error handling where applicable. Existing Android components stay the source of truth.

### OojooCard

- Structure: Material3 `Card` with `surface.card`, `shape.card.radius`, `stroke.cartoon`, `shadow.cartoon.default`, and `space.card.padding`.
- Source: `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:138-153`.
- default: white surface, ink border, hard offset shadow.
- pressed: translate/scale subtly and reduce to `shadow.cartoon.sm`; mirror prototype `.card.click:active` from `prototype/app-emulator_v2.html:110`.
- focused: add a non-layout-changing `master.primary.green.light` or `farmer.primary.teal.light` focus ring outside the ink border.
- disabled: `neutral.line` fill with `neutral.muted` text, no click shadow.
- loading: preserve card size; show progress/skeleton using `neutral.line.2` and owning identity accent.
- empty: use `surface.note.yellow` or plain card with muted explanatory text.
- error: use `status.error.red` text/chip on white/yellow surface, preserving the ink border.

### GradientButton

- Structure: Material3 `Button`, transparent container, background gradient, `shape.button.radius`, ink border, and shadow.
- Source: `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:158-182`; farmer equivalent at `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:70-77`.
- default: `gradient.master.primary` for master, `gradient.farmer.primary` for farmer, white `15.sp` extra-bold label.
- pressed: move down by the shadow offset and reduce shadow depth; do not animate layout.
- focused: visible ring using `master.primary.green.light` or `farmer.primary.teal.light`.
- disabled: use `neutral.line` background and `neutral.muted` label exactly as current button colors do.
- loading: keep label area stable and show a small inline Material progress indicator plus accessible status text.
- empty: not used as an empty surface; provide an empty-state card with a primary action instead.
- error: destructive confirmation actions use `OutlineButton` with `status.error.red`, not a red filled gradient unless added as a token.

### OutlineButton

- Structure: Material3 `OutlinedButton` with `surface.card`, `stroke.cartoon`, identity or error text color, and `shadow.cartoon.sm`.
- Source: `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:187-202`; farmer equivalent at `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:81-82`.
- States mirror `GradientButton`, but default color is `master.primary.green.dark` or `farmer.primary.teal.dark`.

### OojooField

- Structure: Material3 `OutlinedTextField`, `shape.field.radius`, white container, ink border, bold muted placeholder.
- Source: `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:207-227`; farmer equivalent at `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/Theme.kt:86-92`.
- default: white field with ink outline.
- focused: keep ink outline and add identity focus affordance without changing layout.
- disabled: `neutral.line.2` container, `neutral.muted` text, no hidden labels.
- loading: endpoint fields may show reconnect progress in the trailing area if touch target remains `48.dp`.
- empty: placeholder uses `neutral.muted`; required empty fields show helper text.
- error: helper text and supporting icon use `status.error.red`; border can be red only if contrast passes.

### OojooChip

- Structure: pill surface, ink or line border, bold `12.sp` label.
- Source: `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:232-251`; prototype category chips at `prototype/app-emulator_v2.html:122-125`.
- default: white surface, muted label, subtle border.
- pressed: scale no smaller than `0.93` and restore within `150ms`.
- focused: identity ring outside the pill.
- disabled: `neutral.line.2` surface and `neutral.muted.2` label.
- loading: use stable-width placeholder chip.
- empty: not used.
- error: red chip may use `status.error.red` text on `surface.card`.

### CartoonAppBar

- Structure: full-width Material3-compatible top surface, status bar padding, `64.dp` content height, identity background, white bold title/action text.
- Source: `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:262-294`; farmer settings currently uses a teal `TopAppBar` at `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/SettingsScreen.kt:40`.
- default: master green or farmer teal; no blurred shadow.
- pressed: app-bar icon/text buttons use Material ripple and preserve `48.dp` target.
- focused: focus order starts with back/navigation, then title context, then actions.
- disabled/loading/empty/error: title remains stable; place state inside content, not in the app bar except for global offline indicators.

### ControlSlider

- Structure: label/value row plus Material3 `Slider`, used for UI radius, shadow, and border customization.
- Source: `android/master/app/src/main/java/com/oojoo/farm/master/ui/ThemeEditorScreen.kt:65-98` and function start at `ThemeEditorScreen.kt:155`.
- default: value reflects persisted preference.
- pressed/focused: thumb and track use identity color and Material focus semantics.
- disabled: muted track and label.
- loading: not used.
- empty/error: invalid ranges are developer errors; do not expose invalid values to users.

### SettingsSection

- Structure: `OojooCard` or unframed vertical group with section title, fields, switches, dividers, helper text, and one primary save/reconnect action.
- Source: theme editor settings layout `android/master/app/src/main/java/com/oojoo/farm/master/ui/ThemeEditorScreen.kt:60-149`; farmer settings layout `android/slave/app/src/main/java/com/oojoo/farm/slave/ui/SettingsScreen.kt:41-105`.
- Settings IA order: identity/account if present, language, server endpoint editor/reconnect, capture/region, hardware, appearance, pairing/reset/destructive actions.
- default: stacked on 360dp, optional two-column cards on 600dp.
- pressed/focused: each control has natural focus order from top to bottom, left to right on 600dp.
- disabled/loading/empty/error: section-level messages appear below the relevant control, not as blocking page text unless the whole section is unavailable.

### LanguagePicker

- Structure: radio rows for system, Korean, and English; label text comes from locale strings.
- Source: `android/master/app/src/main/java/com/oojoo/farm/master/ui/ThemeEditorScreen.kt:117-148`.
- Required visible label: "Korean/English language picker".
- default: selected radio uses `master.primary.green` or farmer equivalent.
- pressed/focused: row and radio both activate the same choice with one accessibility label.
- disabled: only during locale persistence; keep selected value readable.
- loading: not needed except when locale resources are loading.
- empty/error: if locale cannot persist, show `status.error.red` helper text and keep previous selection.

### EndpointEditor

- Structure: label, `OojooField` URL input, connection status chip/dot, reconnect button, save/retry feedback.
- Source reference: prototype server address controls at `prototype/app-emulator_v2.html:470-478` and `prototype/app-emulator_v2.html:872-879`.
- Required visible concept: "server endpoint editor" and "reconnect".
- default: full-width field on 360dp; field plus trailing actions allowed on 600dp.
- pressed/focused: reconnect/save buttons follow button contracts; URL field exposes keyboard focus clearly.
- disabled: disabled while reconnect is in flight.
- loading: inline progress on reconnect action and stable status text.
- empty: show placeholder URL and helper copy.
- error: invalid URL or connection failure uses `status.error.red`, keeps entered value, and offers retry.

### PhotoTimeline

- Structure: chronological list of photo captures, grouped by date/time, with thumbnail, crop/device metadata, status chip, and actions.
- Token mapping: card uses `OojooCard`; thumbnail border uses `stroke.cartoon`; capture status uses `accent.sky`, `accent.amber`, `status.error.red`, or identity tokens.
- 360dp: single column rows; actions wrap below metadata.
- 600dp: metadata and actions may sit in a second column.
- default/pressed/focused/disabled/loading/empty/error: timeline rows are pressable cards; loading preserves thumbnail size; empty uses a note card; error rows show red chip and retry.

### PhotoGrid

- Structure: square thumbnail tiles with ink border, hard shadow, optional selected chip, and stable action target.
- Token mapping: grid gaps use `space.section.gap` or `12.dp`; tiles use `shape.card.radius` and `stroke.cartoon`.
- 360dp: one or two columns depending on text expansion; 600dp: up to three columns.
- default/pressed/focused/disabled/loading/empty/error: selected state uses identity ring; loading uses stable square placeholders; empty uses `surface.note.yellow`.

### FullscreenViewer

- Structure: full-screen image viewer with dark scrim exception, close/back action, previous/next controls, metadata drawer, and share/export actions.
- Documented exception: fullscreen scrim may use `neutral.ink` with alpha because the existing palette has no dark overlay token; add a formal overlay token before using a new hex.
- Touch targets: all controls are at least `48.dp`; close/back is first in focus order.
- Motion: viewer enter/exit uses opacity and transform only, `200-300ms`; reduced motion disables zoom/slide.
- default/pressed/focused/disabled/loading/empty/error: image load failure shows card-style error panel, not raw system text.

### ClipExport

- Structure: source selection, preview, progress, completion/share actions, and retry/error panel.
- Required visible concepts: "clip export progress", "clip export preview", and "share".
- Token mapping: progress track uses `neutral.line.2`; progress fill uses owning identity gradient; preview surface uses `OojooCard`; share action uses `GradientButton`.
- 360dp: progress, preview, and actions stack vertically.
- 600dp: preview can sit beside progress/actions without nested cards.
- default/pressed/focused/disabled/loading/empty/error: loading progress announces percent; empty explains no eligible photos; errors use `status.error.red` and preserve retry.

## 6. Motion & Interaction

Motion stays playful but functional. Preserve cartoon style; no unrelated screen redesign/decorative motion.

| Token | Value | Source/use |
| --- | --- | --- |
| `motion.micro.press` | `80-150ms`, transform/opacity only | Prototype buttons/cards use `.08s` to `.12s`, `prototype/app-emulator_v2.html:108-142` |
| `motion.standard.panel` | `200-300ms`, transform/opacity only | Toast and screen transitions, `prototype/app-emulator_v2.html:68`, `prototype/app-emulator_v2.html:204` |
| `motion.bouncy.semantic` | `cubic-bezier(.34,1.56,.64,1)` only for stateful thumb/tab/progress movement | Prototype tab/toggle/gauge, `prototype/app-emulator_v2.html:85`, `prototype/app-emulator_v2.html:166`, `prototype/app-emulator_v2.html:183` |
| `motion.progress` | width/progress indicator driven by real task progress | Clip export and reconnect loading |

Rules:

- Pressed controls may translate/scale and reduce shadow; they must return immediately and not reflow siblings.
- Focused controls must be visible with keyboard/D-pad navigation.
- Loading motion must communicate real work: reconnecting, exporting, fetching, or loading media.
- Decorative loops are not allowed except existing semantic indicators such as recording/blink, scan, pulse, or progress.
- Respect reduced motion by disabling bounce/slide and keeping opacity-only feedback where necessary.

## 7. Depth & Surface

Depth strategy is mixed cartoon: thick ink borders plus hard offset shadows, with no blur. Android elevation shadows may be used only when they preserve this hand-cut card feel.

| Token | Value | Source |
| --- | --- | --- |
| `shadowOffset: Int = 4` | default hard offset | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:27` |
| `shadow.cartoon.default` | `4.dp` Android / `4px 4px 0 rgba(0,0,0,.1)` prototype | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:79`, `prototype/app-emulator_v2.html:22` |
| `shadow.cartoon.sm` | `2.dp` Android / `2px 2px 0 rgba(0,0,0,.08)` prototype | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:80`, `prototype/app-emulator_v2.html:21` |
| `shadow.cartoon.lg` | `6.dp` Android / `6px 6px 0 rgba(0,0,0,.12)` prototype | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:81`, `prototype/app-emulator_v2.html:23` |
| `shadow.cartoon.pop` | `0 8px 0 rgba(0,0,0,.15)` prototype | `prototype/app-emulator_v2.html:24` |
| `border.cartoon` | `2.dp` Android / `2px solid var(--ink)` prototype thin | `android/master/app/src/main/java/com/oojoo/farm/master/ui/Theme.kt:75-76`, `prototype/app-emulator_v2.html:29` |
| `border.cartoon.strong` | `3px solid var(--ink)` prototype only | `prototype/app-emulator_v2.html:28` |

Rules:

- Do not use blurred glassmorphism, gradient orbs, or decorative background blobs.
- Cards, image tiles, export previews, settings sections, and dialog panels use ink outlines and hard shadows.
- Avoid cards inside cards. A `PhotoGrid` tile can sit inside an unframed layout or page band, not inside another decorative card unless it is a modal/dialog.
- Fullscreen media is the only surface that may drop the warm paper background, using a documented dark overlay exception.

## 8. Task 4-15 Value Mapping

This section maps every new value anticipated in Tasks 4-15 to an existing token or an explicit documented exception. Implementations must update this file first if a value is missing.

| Planned area | Values allowed | Token or exception |
| --- | --- | --- |
| Settings IA | section gaps, cards, dividers, save/reset buttons | `space.section.gap`, `OojooCard`, `neutral.line`, `GradientButton`, `OutlineButton` |
| Korean/English language picker | radio selected color, label typography, restart helper | `master.primary.green` / `farmer.primary.teal`, section typography, `neutral.muted` |
| Server endpoint editor/reconnect | URL field, reconnect progress, success/error status | `OojooField`, `EndpointEditor`, `gradient.master.primary` / `gradient.farmer.primary`, `status.error.red`, `neutral.muted` |
| Photo timeline | row card, thumbnail, status chips, capture metadata | `PhotoTimeline`, `OojooCard`, `stroke.cartoon`, `accent.sky`, `accent.amber`, `status.error.red`, `neutral.muted` |
| Photo grid | thumbnail tile radius, border, selected state, gaps | `PhotoGrid`, `shape.card.radius`, `stroke.cartoon`, `shadow.cartoon.sm`, `space.section.gap` |
| Fullscreen viewer | dark scrim, close/share controls, metadata drawer | `FullscreenViewer`; exception: `neutral.ink` alpha overlay until a formal overlay token is added |
| Clip export progress | progress track/fill, percentage label, disabled share | `ClipExport`, `neutral.line.2`, identity gradient, `neutral.muted`, `GradientButton` |
| Clip export preview | preview card, video/image frame, selected capture | `OojooCard`, `shape.card.radius`, `stroke.cartoon`, `shadow.cartoon.default` |
| Share sheet/action | primary share, retry, disabled while exporting | `GradientButton`, `OutlineButton`, disabled state tokens |
| Loading state | stable placeholders and inline progress | `neutral.line.2`, identity gradient, stable dimensions from component contract |
| Empty state | explanatory note surface and one action | `surface.note.yellow`, `neutral.muted`, `GradientButton` |
| Error state | inline message, retry, destructive confirmation | `status.error.red`, `OutlineButton`, `AlertDialog` using cartoon card rules |

Explicit documented exceptions:

- Fullscreen viewer scrim may use `neutral.ink` with alpha because media inspection needs contrast beyond the warm paper surface.
- Prototype browser chrome values such as `#1a1a2e`, `#16213e`, `#0f172a`, `#334155`, `#94A3B8`, `#cbd5e1`, `#e2e8f0`, `#64748b`, and `#475569` are not Android product tokens. They may remain in prototype tooling only.
- Prototype emoji placeholders are not production icon tokens. Android production icons must use Material iconography or existing vector assets with content descriptions.

## 9. Accessibility

- Contrast: `neutral.ink` on `surface.background.warm`, `surface.card`, and `surface.note.yellow` is the default readable pairing. White text belongs on identity gradients only when contrast is verified.
- Touch targets: every button, picker row, thumbnail action, fullscreen viewer control, reconnect action, and share action has a minimum `48.dp` target.
- content descriptions: all icon-only actions, photo thumbnails, fullscreen controls, export previews, share buttons, reconnect status indicators, and hardware status indicators need meaningful content descriptions.
- focus order: top app bar navigation first, then primary content in reading order, then secondary actions, then destructive/reset actions. On `600dp`, order follows visual left-to-right and top-to-bottom grouping.
- Do not encode state by color alone. Pair chips or colors with text labels for connected, reconnecting, failed, exporting, empty, and disabled states.
- Dialogs move focus to title, then message, then confirm/dismiss actions; dismiss returns focus to the invoking control.
- Text expansion: CJK and Latin text must fit without overlapping controls; long server URLs may scroll inside fields but labels and helper text must wrap.
