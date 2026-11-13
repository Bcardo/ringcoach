# Proposed signal-context comparison

Status: proposed experiment, not a completed benchmark or a new production two-stage pipeline. The current implementation is the provisional hybrid: deterministic statistics/coverage plus relevant exact sleep intervals and timestamped HR samples in the same coaching request as current daily facts. Actual source data is sampled Health Connect HR and sleep stages, not raw Ring PPG or beat-to-beat sensor signals.

## Variants

| Arm | Signal input to final coach | Calls per generated result |
| --- | --- | --- |
| A: direct detail | Same filtered HR/stage evidence with source/date/coverage, plus identical daily facts and saved plans | One |
| B: signal summary first | Separate signal-only LLM returns a bounded structured evidence summary; final coach gets that summary plus identical daily facts/plans, without original samples | Two initially; one when a valid summary is reused |
| C: hybrid | Deterministic numeric statistics/coverage plus the same exact relevant HR/stage evidence and daily facts/plans | One |

An additional deterministic-statistics-only ablation can test whether timestamped detail adds value beyond known numeric features. Do not accidentally give one arm fresher data, extra history or better meal/workout extraction.

For B, the signal summarizer gets the same signal observations, source boundaries, comparison window and missingness as A/C, but no meals or coaching preferences. It outputs facts, evidence references and uncertainty, not a training/nutrition decision. Its generated interpretation must not become a confirmed user fact. Score both the intermediate summary and the final advice: this distinguishes summary loss from final-coach mistakes. A structured summary may be cached for repeated same-night meals only if keyed by source record revisions, target date, signal coverage and summarizer/prompt versions; revised source data must invalidate it. Any real cache must retain derived facts only, never raw HR traces.

## Controlled pilot

Start with 12 synthetic cases with numeric ground truth, run each arm three times, and freeze cases/prompts before scoring held-out results. Use the same final coach model/configuration, common final instructions, daily facts, response schema and output budget. Keep the summarizer model/configuration fixed initially; changing it is a separate experiment. Randomize arm order and blind labels for human review. Repeated calls measure variability, not new independent nights.

Cases should cover complete ordinary nights, missing current-day HRV with stale `available` sync, sparse/irregular HR sampling, stage fragmentation, overlapping/conflicting sources, offset/DST changes, artifacts/outliers, corrected records and identical aggregate values with different timing patterns. Pair each with realistic daily inputs: new lunch, corrected intake, reported workout, unknown supplement dose and an explicit user training constraint/stop condition. Include counterfactual pairs changing only the daily input or only one supported signal feature. Do not label the expected answer as a diagnosis or assume one universally correct workout adjustment.

## Scorecard

1. **Evidence fidelity:** exact date/source attribution, valid/missing counts, supported numeric facts and units. Count invented measurements, stale-as-current claims, lost uncertainty and duplicate-source aggregation separately. Check B's summary before grading its final response.
2. **Use of latest daily facts:** honors corrected food/workouts and actual-vs-planned supplements; when a known nutrition target applies, remaining amounts must match confirmed totals and uncertainty. No duplicate calories and no old advice presented as new consumption.
3. **Constraint adherence:** follows the supplied routine, RPE cap, excluded movements and explicit stop conditions. Do not reward a confident invented regimen, causal claim or forced plan change.
4. **Decision usefulness:** blinded owner ratings for relevance, actionability, appropriate uncertainty and unnecessary advice churn. Use human review; an LLM judge is supplementary and must cite its evidence. Fluent wording is not a factual accuracy measure.
5. **Operational cost:** all calls' provider-reported input/output tokens when available, actual serialized bytes, end-to-end median/p95 latency, failures/repairs/retries and cost from an explicitly recorded rate card or billing data. Count the signal-summary call and actual cache-hit rate for B. Bytes do not substitute for tokens or dollars.
6. **Privacy/retention:** zero raw HR sample arrays in saved cards, retry files, logs or backups. Inspectable source/period/omission manifest in every arm.

Report paired per-case results and severe failures before any aggregate preference score. Treat repeated runs of one case as a cluster; if giving uncertainty intervals, resample cases/days rather than pretending all repeated model outputs are independent. The pilot is for finding failure modes; close results need more distinct held-out cases.

Define gates before running: unsupported measurements, source/date substitution, ignored explicit stop constraints and raw-trace persistence are unacceptable failures. Among variants meeting the gates, prefer the simpler/faster/cheaper one unless extra complexity produces a repeatable, useful improvement. Similar advice is not proof of failure, and more advice changes are not automatically better.

## Personal-data phase

After synthetic failures are resolved, a separately authorized paired shadow evaluation can use identical current-night evidence and daily inputs across multiple available nights. Experimental outputs stay outside confirmed facts/plans and do not automatically change the user's training. Record only derived observations/manifests for real data; do not create a long-term raw-trace experiment archive. Fix collection/freshness problems first: a summary architecture cannot recover HRV that was never collected.

These tests establish factual/reasoning reliability and usefulness, not clinical effectiveness. A short architecture comparison cannot establish improved recovery or causal supplement/training effects.

Long-context research motivates measuring evidence use rather than assuming that fitting a payload means the model uses it well: [Lost in the Middle](https://arxiv.org/abs/2307.03172) and [RULER](https://arxiv.org/abs/2404.06654). Those results are not direct evaluations of RingCoach or the deployed Qwen model.
