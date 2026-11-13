# Proposed signal-context comparison

Status: proposed experiment, not a completed benchmark or a new production two-stage pipeline. The current implementation is the provisional hybrid: deterministic statistics/coverage plus relevant exact sleep intervals and timestamped HR samples in the same coaching request as current daily facts. Actual source data is sampled Health Connect HR and sleep stages, not raw Ring PPG or beat-to-beat sensor signals.

## Exactly two methods

The agreed design in [issue #50](https://github.com/Bcardo/ringcoach/issues/50) supersedes the earlier three-arm proposal. There is no third raw-only arm or statistics-only ablation in this pilot.

| Method | Signal input to final coach | Calls per generated result |
| --- | --- | --- |
| One-stage hybrid | Relevant filtered timestamped HR/sleep-stage detail plus deterministic statistics, source/date/coverage and identical daily facts/plans | One |
| Two-stage | A signal-only LLM summarizes the same evidence/statistics/history; final coach gets that structured summary plus identical daily facts/plans, without original samples | Two initially; one when a valid summary is reused |

Do not give either method fresher data, additional history or separately re-extracted meals/workouts. The summarizer receives the same signal observations, source boundaries, comparison window and missingness, but no meals or coaching preferences. It returns facts, evidence references, gaps and uncertainty, not a training/nutrition decision. Its generated interpretation must not become a confirmed user fact. Inspect it separately to distinguish information loss from final-coach mistakes. Any summary cache must be keyed to target date, source/input revisions, coverage and summarizer/model/prompt versions; invalidate it when evidence changes. Retain permitted derived facts only, never raw HR traces.

## Controlled pilot

Resolve controlled synthetic failures before starting the personal-data pilot. Freeze cases/prompts before scoring held-out results. Use the same final coach model/configuration, common final instructions, daily facts, response schema and output budget. Keep the summarizer model/configuration fixed initially; changing it is a separate experiment. Randomize arm order and blind labels for human review. Repeated calls measure variability, not new independent nights.

Cases should cover complete ordinary nights, missing current-day HRV with stale `available` sync, sparse/irregular HR sampling, stage fragmentation, overlapping/conflicting sources, offset/DST changes, artifacts/outliers, corrected records and identical aggregate values with different timing patterns. Pair each with realistic daily inputs: new lunch, corrected intake, reported workout, unknown supplement dose and an explicit user training constraint/stop condition. Include counterfactual pairs changing only the daily input or only one supported signal feature. Do not label the expected answer as a diagnosis or assume one universally correct workout adjustment.

## Scorecard

1. **Evidence fidelity:** exact date/source attribution, valid/missing counts, supported numeric facts and units. Count invented measurements, stale-as-current claims, lost uncertainty and duplicate-source aggregation separately. Check the intermediate signal summary before grading its final response.
2. **Use of latest daily facts:** honors corrected food/workouts and actual-vs-planned supplements; when a known nutrition target applies, remaining amounts must match confirmed totals and uncertainty. No duplicate calories and no old advice presented as new consumption.
3. **Constraint adherence:** follows the supplied routine, RPE cap, excluded movements and explicit stop conditions. Do not reward a confident invented regimen, causal claim or forced plan change.
4. **Decision usefulness:** blinded owner ratings for relevance, actionability, appropriate uncertainty and unnecessary advice churn. Use human review; an LLM judge is supplementary and must cite its evidence. Fluent wording is not a factual accuracy measure.
5. **Operational cost:** all calls' provider-reported input/output tokens when available, actual serialized bytes, end-to-end median/p95 latency, failures/repairs/retries and cost from an explicitly recorded rate card or billing data. Count the signal-summary call and actual summary-cache reuse rate. Bytes do not substitute for tokens or dollars.
6. **Privacy/retention:** zero raw HR sample arrays in saved cards, retry files, logs or backups. Inspectable source/period/omission manifest in every arm.

Report paired per-case results and severe failures before any aggregate preference score. Treat repeated runs of one case as a cluster; if giving uncertainty intervals, resample cases/days rather than pretending all repeated model outputs are independent. The pilot is for finding failure modes; close results need more distinct held-out cases.

Define gates before running: unsupported measurements, source/date substitution, ignored explicit stop constraints and raw-trace persistence are unacceptable failures. Among variants meeting the gates, prefer the simpler/faster/cheaper one unless extra complexity produces a repeatable, useful improvement. Similar advice is not proof of failure, and more advice changes are not automatically better.

## Fourteen-day paired assessment

Status: planned, not implemented or started. The separate initial three-night collection check in [#28](https://github.com/Bcardo/ringcoach/issues/28) does not start this comparison or establish long-term collection reliability.

Start the 14-day clock only when both paths pass readiness checks and collection/freshness behavior is verified. Record the actual start/end. Pair both methods on the same immutable submission evidence in memory, using the owner's usual 2–3 dated Journal submissions per day without an extra logging workflow.

Offer one brief blinded comparison per day: first response better / second response better / tie, with an optional reason. Randomize display order, conceal method identity and predefine which daily pair is assessed. Record failed pairs, skipped assessments and source gaps; never cherry-pick outputs. Keep experimental responses outside confirmed facts and the saved plan.

Recovery-informed training explanations are the priority; intake is relatively stable. Explain relevant sleep/recovery concerns and their combination with recent completed training and the latest input. For an isolated mildly poor night, flag the concern and leave adjustment to the owner's feelings. The owner asks for a training adjustment when feeling unwell or wanting a change. Similar appropriate advice is acceptable; frequent plan cuts are not a success metric. Preserve explicit training constraints and stop conditions.

Review paired results per day after 14 days, counting distinct nights/days. Report factual failures, blind wins/losses/ties and whole-day operational cost, including all summary calls. Cluster any uncertainty calculation by day. Missing days or close/inconclusive results may justify an extension rather than an automatic winner. Save only permitted summaries/statistics, source/period/count/omission manifests, hashes, versions and ratings; HR samples remain transient through both paths.

These tests establish factual/reasoning reliability and usefulness, not clinical effectiveness. A short architecture comparison cannot establish improved recovery or causal supplement/training effects.

Long-context research motivates measuring evidence use rather than assuming that fitting a payload means the model uses it well: [Lost in the Middle](https://arxiv.org/abs/2307.03172) and [RULER](https://arxiv.org/abs/2404.06654). Those results are not direct evaluations of RingCoach or the deployed Qwen model.
