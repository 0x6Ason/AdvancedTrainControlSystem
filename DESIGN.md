---
name: AdvancedTrainControlSystem
description: "A restrained railway control desk where inspectable evidence passes deliberately into operational authority."
colors:
  control-ground: "#eef1f5"
  control-paper: "#ffffff"
  alternate-control-paper: "#f6f7f9"
  operating-ink: "#11182a"
  muted-ink: "#596276"
  faint-ink: "#667085"
  muted-rule: "#d8dde6"
  strong-rule: "#aeb6c5"
  signal-blue: "#173f9f"
  deep-signal-blue: "#0d286d"
  signal-blue-wash: "#e8edfb"
  operational-green: "#066747"
  operational-green-wash: "#e2f3ec"
  pending-amber: "#8c4a00"
  pending-amber-wash: "#fff0d6"
  operational-red: "#b4232d"
  map-ground: "#f7f9fc"
  night-control-ground: "#0c111c"
  night-control-paper: "#131a28"
  night-alternate-control-paper: "#182131"
  night-operating-ink: "#f1f4fa"
  night-muted-ink: "#aab3c4"
  night-faint-ink: "#7e899d"
  night-muted-rule: "#293448"
  night-strong-rule: "#46536a"
  night-signal-blue: "#7594ed"
  night-deep-signal-blue: "#a6b9f4"
  night-signal-blue-wash: "#202d4d"
  night-operational-green: "#4ac69b"
  night-operational-green-wash: "#17372f"
  night-pending-amber: "#f1b45d"
  night-pending-amber-wash: "#3a2c17"
  night-operational-red: "#ff7d85"
  night-map-ground: "#080d16"
typography:
  display:
    fontFamily: "Georgia, 'Times New Roman', serif"
    fontSize: "27px"
    fontWeight: 700
    lineHeight: 1
    letterSpacing: "-0.025em"
  headline:
    fontFamily: "Georgia, 'Times New Roman', serif"
    fontSize: "15px"
    fontWeight: 700
    letterSpacing: "-0.015em"
  title:
    fontFamily: "Consolas, 'SFMono-Regular', monospace"
    fontSize: "12px"
    fontWeight: 700
  body:
    fontFamily: "'Trebuchet MS', 'Lucida Sans Unicode', sans-serif"
    fontSize: "13px"
    fontWeight: 400
  label:
    fontFamily: "'Trebuchet MS', 'Lucida Sans Unicode', sans-serif"
    fontSize: "9px"
    fontWeight: 800
    letterSpacing: "0.04em"
  data:
    fontFamily: "Consolas, 'SFMono-Regular', monospace"
    fontSize: "9px"
    fontWeight: 700
rounded:
  structural: "0"
  control: "3px"
  roundel: "50%"
  state-pill: "999px"
spacing:
  pill-inline: "6px"
  dense-gap: "7px"
  field-inline: "9px"
  panel-inset: "12px"
  masthead-inline: "18px"
  login-inline: "28px"
components:
  button-primary:
    backgroundColor: "{colors.signal-blue}"
    textColor: "{colors.control-paper}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "6px 11px"
    height: "32px"
  button-secondary:
    backgroundColor: "{colors.control-paper}"
    textColor: "{colors.operating-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "6px 11px"
    height: "32px"
  input-standard:
    backgroundColor: "{colors.control-paper}"
    textColor: "{colors.operating-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "7px 9px"
    height: "34px"
  navigation-tab:
    backgroundColor: "{colors.control-paper}"
    textColor: "{colors.muted-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.structural}"
    padding: "0 13px"
    height: "47px"
  state-pending:
    backgroundColor: "{colors.pending-amber-wash}"
    textColor: "{colors.pending-amber}"
    typography: "{typography.data}"
    rounded: "{rounded.state-pill}"
    padding: "3px 6px"
  state-included:
    backgroundColor: "{colors.operational-green-wash}"
    textColor: "{colors.operational-green}"
    typography: "{typography.data}"
    rounded: "{rounded.state-pill}"
    padding: "3px 6px"
  state-ignored:
    backgroundColor: "{colors.alternate-control-paper}"
    textColor: "{colors.muted-ink}"
    typography: "{typography.data}"
    rounded: "{rounded.state-pill}"
    padding: "3px 6px"
  candidate-notice:
    backgroundColor: "{colors.pending-amber-wash}"
    textColor: "{colors.muted-ink}"
    rounded: "{rounded.structural}"
    padding: "9px 12px"
  evidence-table:
    backgroundColor: "{colors.control-paper}"
    textColor: "{colors.operating-ink}"
    rounded: "{rounded.structural}"
    width: "100%"
  inspector-panel:
    backgroundColor: "{colors.control-paper}"
    textColor: "{colors.operating-ink}"
    rounded: "{rounded.structural}"
    width: "290px"
---

# Design System: AdvancedTrainControlSystem

## Overview

**Creative North Star: "The Restrained Railway Control Desk"**

The Restrained Railway Control Desk treats administration as an inspectable operating instrument: restrained, precise, operational, and evidence-led. Its light default surface resembles control paper held inside a disciplined rail console, with Signal Blue reserved for deliberate action and Pending Amber reserved for evidence that has not yet crossed into authority.

The design serves a dense existing Operate surface. Thin rules, compact labels, tabular evidence, fixed registers, and a persistent inspector let an operator verify source material, preview one authority record, and make one audited decision without losing context. The optional night theme remaps the same semantic roles; it does not create a separate visual personality. The world rejects generic AI-dashboard styling, decorative gradients, and floating card fields in favor of credible railway administration.

**Key Characteristics:**

- Dense three-zone operating desk with persistent context.
- Evidence and authority remain visually and procedurally distinct.
- Precise Signal Blue actions and Pending Amber review states.
- Flat structural surfaces separated by thin rules.
- Tabular numerals and monospaced evidence where exactness matters.

## Colors

The palette is a light control-paper field with scarce Signal Blue, explicit Pending Amber, and operational green/red; the night theme remaps every semantic role without changing its meaning.

### Primary

- **Signal Blue** (`#173f9f`): deliberate actions, active navigation, focus, selection, roundel geometry, and rail-map emphasis.
- **Deep Signal Blue** (`#0d286d`): primary-button hover and emphasized authority values.
- **Signal Blue Wash** (`#e8edfb`): selected and hovered evidence rows without obscuring data.

### Secondary

- **Pending Amber** (`#8c4a00`): pending evidence, review debt, warnings, and non-authoritative states.
- **Pending Amber Wash** (`#fff0d6`): quiet warning bands, pending pills, and review notices.

### Tertiary

- **Operational Green** (`#066747`) and **Operational Green Wash** (`#e2f3ec`): online, included, and successful states.
- **Operational Red** (`#b4232d`): failures, stale records, validation messages, and destructive operational exceptions.

### Neutral

- **Control Ground** (`#eef1f5`), **Control Paper** (`#ffffff`), **Alternate Control Paper** (`#f6f7f9`), and **Map Ground** (`#f7f9fc`): the nested desk, work surfaces, alternate rows, and map field.
- **Operating Ink** (`#11182a`), **Muted Ink** (`#596276`), and **Faint Ink** (`#667085`): primary information, supporting context, and inactive or placeholder content.
- **Muted Rule** (`#d8dde6`) and **Strong Rule** (`#aeb6c5`): ordinary dividers and major structural boundaries.

### Night Theme Overrides

- **Night Control Ground / Paper / Alternate Paper / Map Ground** (`#0c111c`, `#131a28`, `#182131`, `#080d16`) preserve the same nested-surface order.
- **Night Operating / Muted / Faint Ink** (`#f1f4fa`, `#aab3c4`, `#7e899d`) and **Night Muted / Strong Rules** (`#293448`, `#46536a`) retain information hierarchy.
- **Night Signal / Deep Signal / Signal Wash** (`#7594ed`, `#a6b9f4`, `#202d4d`), **Night Operational Green / Green Wash** (`#4ac69b`, `#17372f`), **Night Pending Amber / Amber Wash** (`#f1b45d`, `#3a2c17`), and **Night Operational Red** (`#ff7d85`) preserve semantic state contrast.

**The Signal Is Scarce Rule.** Signal Blue marks action, focus, active navigation, or selected evidence; it never becomes broad decoration.

**The Amber Means Pending Rule.** Pending Amber marks review debt, non-authoritative evidence, and warnings; it never implies success.

## Typography

**Display Font:** Georgia (with Times New Roman and serif fallbacks)

**Body Font:** Trebuchet MS (with Lucida Sans Unicode and sans-serif fallbacks)

**Label/Mono Font:** Consolas (with SFMono-Regular and monospace fallbacks)

**Character:** Georgia gives the brand and major workspace headings institutional authority, Trebuchet keeps dense controls direct and legible, and Consolas makes identifiers, coordinates, clocks, counts, and preserved evidence mechanically precise.

### Hierarchy

- **Display** (700, `27px`, `1`, `-0.025em`): the ATCS wordmark; the login treatment may expand the same face to `32px`.
- **Headline** (700, `15px`, `-0.015em`): major workspace titles inside the toolbar.
- **Title** (700, `12px`): inspected-object headings and compact operational titles.
- **Body** (400, `13px`): the inherited interface baseline and editable field content; dense explanatory copy may step down to `9px`–`10px` where the implementation already does so.
- **Label** (800, `9px`, `0.04em`, uppercase): field labels, buttons, window titles, and compact control language; table headers step down to `8px` with `0.06em` tracking.
- **Data** (700, typically `9px`): identifiers, coordinates, clocks, counts, statuses, and numeric registers, always with tabular numerals where values align.

**The Evidence Is Tabular Rule.** Use monospace for IDs, coordinates, clocks, counts, schema/status registers, and preserved evidence; use Trebuchet for controls and explanation; reserve Georgia for the brand and major workspace titles.

## Layout

The application is a full-height operating frame with fixed bands for masthead (`66px`), module navigation (`47px`), system status (`30px`), flexible work area, and footer (`25px`). The main console is a three-zone grid: left register and filters (`210px`), a flexible workspace with a `500px` minimum, and a persistent inspector (`290px`). The workspace holds dense tables, a map canvas, or split editors rather than independent dashboard cards.

Spacing follows the observed compact rhythm: `6px` pill insets, recurring `7px` gaps and cells, `9px` field/form rhythm, `12px` panel insets, `18px` masthead insets, and `28px` login-panel insets. Data stays close to its labels; larger blank zones are reserved for maps, empty states, and review focus.

At `1100px`, the console narrows to `175px / 1fr / 245px`, module tabs compress, and the fare workbench stacks. At `800px`, the application keeps a `760px` minimum operating canvas and preserves an in-flow `145px / minmax(350px, 1fr) / 255px` register–workspace–inspector grid. The workspace and inspector scroll independently, the candidate table exposes its full `780px` ledger through horizontal scrolling, and the candidate toolbar and safety notice wrap without masking controls. Masthead cells disappear, but the inspector never overlays evidence or pagination. This is a desktop administration surface under constraint, not a card-stacked mobile product.

**The Desk Stays Dense Rule.** Preserve the register–workspace–inspector relationship and keep evidence visible beside decisions; do not inflate operational controls into consumer-dashboard cards.

## Elevation & Depth

The system is flat and structural. Depth comes from nested paper tones, `1px` rules, sticky table headers, and persistent spatial ownership; ordinary buttons, fields, tables, panels, navigation, and the constrained in-flow inspector have no shadow. A shadow is reserved for the secure login surface where it must detach from the desk.

### Shadow Vocabulary

- **Secure Login Lift** (`box-shadow: 0 24px 64px rgba(15, 24, 46, .16)`): isolates the authentication panel over the control ground.

**The Flat-by-Default Rule.** Surfaces remain flat and rule-bound; add a shadow only when the secure login panel must detach from its underlying plane.

## Shapes

The form language is rectilinear and infrastructural. Workspaces, ledgers, notices, tables, panels, and structural containers stay square (`0` radius) and are separated by thin `1px` rules; standard controls use a restrained `3px` radius. Status pills alone use a full `999px` capsule, while the ATCS roundel uses true circular geometry (`50%`) as a brand signal rather than a general component shape. Active module tabs use a precise `3px` bottom rule, and the login panel uses a `4px` Signal Blue top bar.

**The Structure Uses Lines Rule.** Build hierarchy with aligned edges and rules; rounded geometry belongs only to controls, state pills, and the roundel.

## Components

Components are precise and restrained: compact enough for operational density, explicit in state, and visually subordinate to the evidence they control.

### Buttons

- **Shape:** restrained control corners (`3px`) with a `32px` minimum height; compact controls use `28px`.
- **Primary:** Control Paper text on Signal Blue with `6px 11px` padding; hover deepens to Deep Signal Blue.
- **Hover / Focus:** secondary buttons move to Signal Blue Wash with Signal Blue border and Deep Signal Blue text; all controls use a `2px` Signal Blue focus outline, and active press translates down `1px`.
- **Secondary / Muted:** secondary buttons are Control Paper with a Strong Rule border; muted buttons use Alternate Control Paper and Muted Ink. Disabled controls use Faint Ink and no active cursor.

### Chips

- **Style:** compact monospaced state pills (`3px 6px`, `999px` radius) pair a semantic ink with its wash.
- **State:** Pending uses amber, Included uses operational green, Ignored uses muted neutrals, and Stale uses operational red on a restrained red tint.

### Cards / Containers

- **Corner Style:** square and aligned (`0` radius); containers behave like desk bays, not floating cards.
- **Background:** Control Paper for primary work, Alternate Control Paper for headers and subordinate regions, and Map Ground for the canvas.
- **Shadow Strategy:** none at rest; only the secure login panel uses the documented shadow.
- **Border:** Muted Rules divide records; Strong Rules establish rail, workspace, and inspector boundaries.
- **Internal Padding:** dense `7px`–`12px` insets, with `18px`–`28px` reserved for masthead and login framing.

### Inputs / Fields

- **Style:** Control Paper field, Operating Ink, Strong Rule border, restrained `3px` radius, `34px` minimum height, and `7px 9px` padding.
- **Focus:** a `2px` Signal Blue inner-offset outline plus Signal Blue border; hover shifts the border to Muted Ink.
- **Error / Disabled:** errors use Operational Red; disabled authority fields lower opacity to `0.62` while preserving their structure.

### Navigation

Module tabs are `47px` high, horizontally scrollable, uppercase, compact, and icon-led. Default tabs use Muted Ink; hover uses Alternate Control Paper and Operating Ink; the active tab uses Deep Signal Blue with a `3px` Signal Blue bottom rule. At constrained widths, tab width and type size compress rather than becoming tiles.

### Candidate Adoption Gate

The signature component is a ledger and persistent inspector acting as one review instrument. A Pending Amber notice distinguishes evidence from authority, the selected row keeps its tabular context, the inspector preserves source text and inference evidence, and authority fields remain disabled until the operator chooses inclusion. The decision requires a preview, explicit confirmation, and a single audited save; bulk inclusion is intentionally absent.

**The Gate Must Be Inspectable Rule.** Keep preserved evidence, authority preview, and the explicit operator decision in one visible review path; never style an inferred candidate as already authoritative.

## Do's and Don'ts

### Do:

- **Do** preserve the dense register–workspace–inspector frame on operational surfaces.
- **Do** reserve Signal Blue for deliberate action, focus, selection, and active navigation.
- **Do** use Pending Amber whenever evidence still requires an operator decision.
- **Do** keep identifiers, coordinates, clocks, counts, and evidence in tabular monospace.
- **Do** remap semantic roles consistently when implementing the optional night theme.

### Don't:

- **Don't** use floating card grids, decorative gradients, or generic AI-dashboard ornament.
- **Don't** add broad shadows to buttons, tables, notices, or ordinary panels.
- **Don't** round structural containers; keep the `3px` radius for controls and full capsules for state pills.
- **Don't** let a sign, rail observation, confidence score, or suggested role look authoritative before the inspectable gate is completed.
- **Don't** trade operational density for oversized type, spacious marketing rhythm, or mobile-card stacking.
