---
target: ATCS administration UI
total_score: 13
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 5
timestamp: 2026-08-08T09-08-37Z
slug: src-main-resources-web-index-html
---
# ATCS Administration UI Critique

Method: dual-agent (A: `ui_design_assessment` + `ui_design_assessment_source` · B: `ui_detector_assessment`)

## Design Health Score

The design assessor originally reported issue severity (higher was worse). The scores below are converted to Impeccable's required health scale, where 0 is unusable and 4 is excellent.

| # | Heuristic | Score | Key issue |
|---|---|---:|---|
| 1 | Visibility of System Status | 2/4 | Status bands and toasts exist, but saves have no durable in-progress/saved/failed state. |
| 2 | Match System / Real World | 2/4 | Railway language is strong, but enums, record IDs, JSON, and minor currency units leak implementation detail. |
| 3 | User Control and Freedom | 1/4 | No undo, cancel, dirty-state guard, draft restoration, or conflict comparison. |
| 4 | Consistency and Standards | 2/4 | The visual system is coherent, but forms, terminology, and persistent evidence chrome do not follow task context. |
| 5 | Error Prevention | 1/4 | Raw JSON and unconditional fare/route fields allow avoidable mistakes. |
| 6 | Recognition Rather Than Recall | 1/4 | Operators must remember IDs, coordinate meaning, policy effects, and JSON schemas. |
| 7 | Flexibility and Efficiency | 1/4 | No bulk decisions, saved filters, sorting, shortcuts, or accessible keyboard map workflow. |
| 8 | Aesthetic and Minimalist Design | 1/4 | The visual language is credible, but tiny type and simultaneous editors create a high noise floor. |
| 9 | Error Recovery | 1/4 | Errors are transient global toasts; conflicts can refresh away the operator's work. |
| 10 | Help and Documentation | 1/4 | Empty-state hints exist, but no glossary, workflow guidance, policy help, or structured hardware assistance. |
| **Total** |  | **13/40** | **Poor — major UX redesign required** |

## Design Specificity Verdict

The interface looks and sounds purpose-built for ATCS: preserved sign text, rail topology, berth tolerance, route calls, gate modes, audit state, and the restrained railway-console styling are specific and coherent. Behaviorally, however, it remains a generic register-plus-form console. Domain decisions are exposed as raw IDs, enums, coordinates, and JSON instead of guided railway workflows.

The deterministic scan returned zero findings for `index.html` (exit 0, `[]`). That clean result means no bundled markup rule fired; it does not certify dynamic accessibility or workflow quality. The browser pass confirmed the live light-theme application loaded correctly at 1280×720 with six modules, a three-column map workspace, labelled navigation, status strips, and no console errors. Mutable detector injection was unavailable, so no user-visible overlay exists.

## Overall Impression

This is a credible visual prototype wrapped around the wrong interaction architecture. The biggest opportunity is to make the evidence-to-authority lifecycle the organizing principle: observe, review, model, validate, publish, operate.

## What's Working

- The product has a clear visual point of view: restrained color, dense registers, strong railway terminology, sticky data tables, map controls, and useful light/dark themes.
- Safety intent is visible: preserved source text, explicit include/ignore decisions, audit records, optimistic versions, empty states, and warnings against inference.
- The map/table/inspector pattern is a useful foundation for a focused Evidence workbench.

## Priority Issues

### P1 — Evidence and approved Network are conceptually mixed

**Why it matters:** the primary "Network Map" is actually raw physical evidence, while evidence filters and the symbol inspector persist across unrelated modules. Operators cannot tell what is observed, approved, draft, or published.

**Fix:** reorganize the product into Evidence, Network, Operations, Passenger information, Fares, and System health. Give observed evidence, draft models, and published authority distinct visual states and an explicit reviewed promotion workflow.

**Suggested command:** `$impeccable shape`

### P1 — Mutation recovery is too weak for safety-critical administration

**Why it matters:** transient toasts, automatic form resets, and conflict refreshes can hide failures or discard work.

**Fix:** add durable saving/saved/failed states, inline validation, dirty-form guards, persisted drafts, conflict diffs, and audited corrective/revert actions.

**Suggested command:** `$impeccable harden`

### P1 — Workbenches overload the operator

**Why it matters:** Fares exposes gates, zones, and rules simultaneously; Lines combines identity and route construction; permanent sidebars compete with the active task.

**Fix:** use contextual master-detail navigation, one active editor at a time, progressive disclosure, and task summaries for dependent records.

**Suggested command:** `$impeccable distill`

### P1 — Accessibility prevents complete non-pointer operation

**Why it matters:** the focusable map canvas has no keyboard handlers, micro-action buttons lack accessible names, audit detail relies on a native title tooltip, status can be color-dependent, and frequent text is only 8–10px.

**Fix:** add keyboard map controls and a synchronized accessible list, labelled buttons, openable audit details, non-color state cues, live regions, and a larger minimum type scale.

**Suggested command:** `$impeccable audit`

### P1 — Domain rules are not encoded into the forms

**Why it matters:** all fare operands remain visible regardless of rule type, amounts use minor units, gate hardware is raw JSON, and route-policy consequences are unexplained.

**Fix:** introduce rule-specific conditional builders, currency-aware inputs, structured gate adapters, inline dependency checks, validation summaries, and pre-publish simulations.

**Suggested command:** `$impeccable shape`

## Persona Red Flags

**Alex (power operator):** no sorting, bulk evidence decisions, saved filters, shortcuts, or fast keyboard path; manual record IDs and repeated refreshes turn a review queue into one-item-at-a-time clerical work.

**Sam (keyboard/screen-reader operator):** the canvas is pointer-only, small movement/removal controls are not named, audit detail is tooltip-only, transient toasts are not a reliable announced state, and tiny text undermines low-vision use.

**Jordan (new operator):** raw evidence and approved Network appear in the same conceptual space; `CONTINUE_AS`, `NON_SERVICE`, `UNASSIGNED`, minor units, and hardware JSON have no contextual explanation or guided next step.

## Minor Observations

- The map-world list is derived from symbol worlds, so a rail-bearing world with no signs can become unreachable even though signless rails must remain visible.
- The default `world` and `HKD` fallbacks contradict the portable-configuration requirement.
- Color-coded toast success/failure needs text/icon semantics and an `aria-live` region.
- The clean detector result should be retained as a baseline, not interpreted as WCAG conformance.

## Questions to Consider

- What exact, audited event promotes an evidence revision into a draft Network object, and what validation makes it publishable?
- What should an operator see first when a new rail scan differs from the currently published network?
- Which changes require one-person publication, peer approval, simulation evidence, or an emergency rollback path?
