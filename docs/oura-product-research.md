# Oura reference: recovery decisions beyond trend charts

Research date: 2026-11-15. Product proposal; corresponding roadmap issues have now been created/linked at the owner's request. App behavior, collection and settings are unchanged by this research/tracking work.

## Implementation tracking

- [#58 — Recovery decisions and personal insights](https://github.com/Bcardo/ringcoach/issues/58): parent and priority order; supersedes chart expansion as the next main product milestone in #29.
- [#59 — Daily decision card](https://github.com/Bcardo/ringcoach/issues/59): first visible delivery; retain the training plan/latest progress.
- [#35 — Verified personal comparisons](https://github.com/Bcardo/ringcoach/issues/35): existing baseline issue extended for contributor cards and deterministic LLM inputs.
- [#60 — Sleep-target shortfall](https://github.com/Bcardo/ringcoach/issues/60): actual sleep duration, explicit target and coverage method required.
- [#37 — Journal event context](https://github.com/Bcardo/ringcoach/issues/37) and [#39 — Personal associations](https://github.com/Bcardo/ringcoach/issues/39): reuse existing issues and one Journal workflow.
- [#38 — Periodic/repeated-pattern review](https://github.com/Bcardo/ringcoach/issues/38): isolated versus sustained compatible changes, with daytime resilience limitations.
- [#41 — UI integration](https://github.com/Bcardo/ringcoach/issues/41): concise Today, visible training plan and evidence drill-down.

The first #59 presentation milestone is now implemented in source: [daily decision and night-record panels](daily-decision-panel.md). It is not installed on the owner's app; broader #59 acceptance and the remaining roadmap stay open. Reliability remains tracked independently in #52/#57.

The owner's goal is timely interpretation of body signals and useful coaching connected to the saved training plan. Samsung Health already supplies general trend browsing. Recreating a broad metrics dashboard has lower priority than making each recovery observation relevant to a decision.

## Documented Oura patterns and RingCoach applicability

| Oura pattern | Publicly documented behavior | Proposed adaptation |
| --- | --- | --- |
| Contextual Today | Surfaces timely insights and actions; detailed measurements and longer perspectives live in other views. | One daily focus, up to two relevant reasons, and a proposed action tied to the user's actual plan. Details expand on request. |
| Readiness contributors | Evaluates several sleep/activity/physiology inputs relative to personal patterns; exposes individual contributors. | Show what changed relative to a compatible personal baseline and which changes support today's recommendation. Do not clone the proprietary score or turn an experimental HRV window into vendor daily HRV. |
| Sleep Debt | Estimates accumulated shortfall against estimated sleep need, with recent days weighted more. | First implement a clearly labeled sleep-target shortfall using validated time-asleep and a user-selected target. Session span alone is insufficient; personal sleep need is not automatically established by average duration. |
| Discovery Hub | Tests associations between repeated tags and biometric differences after sufficient observations; its documentation explicitly notes other explanations. | Reuse dated Journal facts for event overlays and later cautious within-person comparisons. Distinguish reported event time from submission time; do not infer meal timing. Show counts and comparison coverage, with no causal claims. |
| Resilience / stress context | Combines daytime stress, daytime recovery and overnight recovery over multiple days. | A later multi-day recovery summary can distinguish an isolated dip from a sustained shift. Current overnight evidence cannot reproduce Oura's full daytime stress/recovery feature. |
| Symptom Radar | Highlights coordinated departures in several signals and exposes the contributing changes. | Defer illness/strain classification until source definitions, sampling, calibration and compatible baselines are validated. Earlier versions can show measured departures without disease labels. |

These descriptions concern the documented product behavior. They do not establish where Oura executes computation or provide a complete implementation of its algorithms.

## Recommended RingCoach sequence

1. **Decision-focused daily card:** today's body-signal context + actual training progress + reported fatigue/soreness. Keep the existing exercise plan table visible. Show a proposed adjustment and its short reasons; don't automatically edit saved plans or cut workload after every low reading. This can build on existing context without adding a composite score.
2. **Verified contributors:** a deterministic evidence layer computes dated comparisons, compatible baselines and data adequacy before asking the LLM to explain them. Missing coverage remains unknown. It can report limited confidence without exposing protocol diagnostics on the main screen.
3. **Sleep-target shortfall and event context:** use validated actual sleep intervals/stages and explicit event times. Start with observations; establish enough repeated comparable events before publishing habit associations.
4. **Repeated-pattern insights:** once reliable nights accumulate, identify persistent compatible departures and assess whether nights following rest or particular training patterns differ. Account for simultaneous habits and selection bias; do not label these causal effects.

The highest-value differentiator is connecting physiology to the owner's actual exercise plan, performance, diet and self-reported state. Let the LLM explain verified observations and answer questions; avoid delegating date matching, arithmetic, baseline construction or evidence availability to prose generation.

## Data gates

- Passive Bluetooth HRV is still labeled experimental and lacks fresh-night production reliability/same-night reference acceptance (#28/#64; discovery #9 is closed). Preserve separate definitions for windowed RMSSD/SDNN and Samsung daily sleeping HRV.
- A sampled overnight HR minimum is not a continuously observed settling point or vendor resting HR. Do not present it as an Oura Recovery Index equivalent.
- Bluetooth sleep span is not time asleep; temperature calibration and raw oxygen/R-R interpretation remain unresolved.
- Multi-day classification needs sufficient distinct compatible nights. Daytime stress/resilience also needs reliable daytime signals; six decoded stress records do not establish that capability.
- Meal, supplement or workout associations require actual dated facts and enough observations. A generated explanation is not evidence of causation.

## Primary sources

- [Oura app layout and contextual Today](https://support.ouraring.com/hc/en-us/articles/360058599753-How-to-Use-the-Oura-App)
- [Readiness contributors and personal comparisons](https://support.ouraring.com/hc/en-us/articles/360057791533-Readiness-Contributors)
- [Sleep Debt](https://support.ouraring.com/hc/en-us/articles/46233324892691-Sleep-Debt)
- [Discovery Hub](https://support.ouraring.com/hc/en-us/articles/30697762898323-Discovery-Hub)
- [Resilience](https://support.ouraring.com/hc/en-us/articles/25358829055251-Resilience)
- [Stress Management Hub](https://support.ouraring.com/hc/en-us/articles/45981884263571-Stress-Management-Hub)
- [Symptom Radar](https://support.ouraring.com/hc/en-us/articles/35593651188115-Symptom-Radar)
