# Coaching rule reconciliation

This document addresses [issue #6](https://github.com/Bcardo/ringcoach/issues/6) after completion of the Fold7 export and desktop analysis prototype. The current analysis script produces observations and data gaps. These defaults describe future coaching behavior and supersede the conflicting RULE-01, RULE-02, TC-02, and TC-04 wording in the original specification. The owner delegated the initial values and requested editable prompts.

## Decisions

| Item | Default behavior | Status |
| --- | --- | --- |
| RULE-02 effort cap | Use RPE 7.5 consistently in the rule, prompt, and TC-02. The owner can adjust this value in the coaching policy. | Default selected |
| Simultaneous RULE-01 and RULE-02 | Preserve RULE-02's loading restriction and effort cap when both predicates match. Meal timing cannot cancel a restriction from another matching rule. | Default selected |
| RULE-01 attribution | Report a late meal and delayed heart-rate minimum as observed associations. A matching rule supplies context for reviewing the session. It cannot establish digestive thermogenesis as the cause. | Default selected |
| RULE-02 attribution | Describe the measured HRV and overnight heart-rate changes and the configured rule that matched. The historical flag name CNS_DRAIN_CRITICAL cannot establish central nervous system fatigue. | Default selected |
| Missing recovery data | Mark a rule unavailable when a required input or its applicable baseline is missing. Preserve the known measurements and report which input prevented evaluation. | Default selected |
| Suggested plan changes | Show proposed changes for review before applying them. Use confirmed training history and equipment increments when specifying loads. | Default selected |

## Predicate definitions

RULE-01 uses dinner, sleep-start, and heart-rate-minimum timestamps with offsets. It matches when the dinner-to-sleep interval is at least zero and less than 2.5 hours and the measured minimum occurs more than 4 hours after sleep starts. A dinner logged after sleep starts does not satisfy that comparison.

RULE-02 uses a documented nightly RMSSD measurement and its applicable personal baseline, plus an overnight heart-rate minimum compared with a baseline calculated using the same method. It matches when the HRV Z-score is at most -1.5 and the minimum is at least 4 bpm above baseline. The original trigger thresholds are retained as editable defaults.

Baseline window, minimum valid-night count, source compatibility, sampling sufficiency, and freshness must be defined before implementing these predicates. Values from an export require verified measurement definitions and units before becoming rule inputs.

## Prompt behavior

The future coaching prompt receives locally evaluated rules, coverage, provenance, and applicable constraints. Each observation references the supplied measurements or confirmed journal entry. Missing inputs remain visible. The model must preserve restrictions from simultaneous matching rules.

For the current prototype, the prompt continues to describe measured facts and coverage. HRV-based rule evaluation remains unavailable for the tested Health Connect export. The overnight Bluetooth investigation in issue #9 will determine whether fresh Ring HRV can become an automatic input.

## Acceptance examples

Examples below assume compatible sources, adequate sampling, fresh data, and valid baselines wherever values are supplied. They exercise configured product behavior.

| Case | Inputs | Expected behavior |
| --- | --- | --- |
| TC-02 consistency | HRV Z-score -1.8 and overnight minimum 6 bpm above baseline | Match RULE-02, block micro-loading, and use the default RPE cap of 7.5 consistently in the rule and prompt. |
| RULE-02 boundary | HRV Z-score -1.5 and overnight minimum 4 bpm above baseline | Match RULE-02. |
| RULE-02 requires both inputs | HRV Z-score -1.49 and overnight minimum 4 bpm above baseline | RULE-02 does not match. |
| TC-04 meal timing | Dinner at 22:15, sleep starts at 23:30, minimum at 05:00 next day, HRV Z-score -1.2 | Match RULE-01. Report the timing association and leave training changes to other available inputs and rules. |
| Simultaneous rules | TC-04 timing, HRV Z-score -1.8, and overnight minimum 6 bpm above baseline | Match both rules. Retain RULE-02 restrictions while reporting the meal timing context. |
| Missing HRV | Sleep and heart rate available, RMSSD missing | Mark RULE-02 unavailable and identify missing RMSSD. Preserve the sleep and heart-rate observations. |
| Invalid baseline | Current RMSSD available, baseline missing or HRV variance zero | Mark the HRV Z-score and RULE-02 unavailable. |
| Meal recorded after sleep start | Dinner timestamp later than the sleep-start timestamp | RULE-01 does not match. |
| Partial workout data | Recovery rules match but the prior confirmed load or equipment increment is missing | Show applicable constraints and the missing training input. Do not invent an exact load. |

## Completion

The default RPE cap is 7.5. The editable Chinese coaching policy defines the trigger thresholds, simultaneous-rule precedence, missing-data behavior, and allowed claims. The acceptance examples above replace the conflicting original cases. Baseline computation and a runtime coaching engine remain future implementation work. [Coaching policy](../prompts/coaching-policy.md)

## Customize prompts

The desktop analysis loads the summary prompt on each run. Edit that UTF-8 file to change wording or output preferences, or pass `--prompt-file` with a personal copy. Both Qwen and Codex use the selected file. The response must retain `observations` and `limitations` as string arrays, with 1 to 3 observations and 1 to 2 limitations. [Summary prompt](../prompts/telemetry-summary.txt)

The coaching policy is a template for the future coaching feature. Its RPE cap and trigger thresholds are editable. Changing it does not yet evaluate recovery rules in the current desktop summary.
