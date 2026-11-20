# Synthetic screen for issue #50

This test-only harness starts the controlled comparison before the personal 14-day pilot. It does not implement an everyday two-stage coaching path, change the installed app, read owner facts, save cards, or start a trial clock. It uses only the saved provider credential and synthetic fixtures. Normal #65 observation continues separately.

## Frozen initial screen

`SignalComparisonTest` defines nine development cases: ordinary, missing current vendor HRV with a prior-day value, sparse HR sampling, conflicting overlapping sources, fragmented partial stages, explicit UTC offset, equal-average early versus late HR elevation, and a corrected workout. The fixture date and references are synthetic. Both arms receive identical daily facts, saved constraints and signal evidence. No recognition is rerun. A fixed seeded shuffle varies arm order reproducibly; this is not owner blinding or a held-out evaluation.

- Direct: samples, intervals, statistics, source/date records and limitations go to the coach.
- Summary first: a signal-only LLM creates `signal-summary-screen-v1`, preserving observations/statistics/limitations, summarizing interval coverage and describing HR timing. The final coach receives that summary instead of the samples/intervals, with the same daily context.

Both final calls use the existing `QwenClient.request`, current coach prompt/model, JSON response schema, thinking disabled and 2,000 output tokens. The summarizer uses the same model, separate fixed instructions/schema, thinking disabled and 2,000 output tokens. No retries, repairs or summary reuse occur in this first screen. A complete screen makes 27 calls: nine direct and nine summary-plus-final pairs. Failed summary fidelity blocks that experimental final call and is counted as a failure, never repaired silently.

## Checks and measurements

The deterministic fixture test checks that the fidelity gate accepts an oracle and rejects altered date/value, extra trace fields and invalid timing. The live gate checks every intermediate observation's date/source/reference/value/null/unit/status, exact statistics and explicit limitations, computed interval count/awake count/observed duration/start/end with offsets, temporal pattern, bounded size and absence of raw trace fields.

Final-response checks are limited numeric/wording spot checks: latest protein and actual workout load/repetitions, RPE cap, dizziness stop-condition mention and explicit missingness when applicable. Mentioning the right words does not establish full constraint compliance or decision usefulness. Unsupported new claims, detailed proportionality, excluded-movement handling and semantic information loss still need broader factual/human review. No automated preference winner is produced.

Fixed-category reports contain case/stage labels, pass/fail, actual serialized request bytes, provider-reported prompt/completion/total tokens when present, per-call latency and successful pipeline elapsed time. Every attempted call is counted, including summary calls and failures. Failure codes exclude raw exceptions/responses. No request/response, HR sample array, owner text, readings, identifiers or credentials are logged or persisted. Token counts are not dollar costs; no rate card is assumed. Reuse is explicitly zero, so this screen cannot establish whole-day cached costs.

These small development fixtures screen fidelity and operational feasibility; they are not representative long-night byte workloads, repeated variability measurements, distinct observed nights or proof that summary-first fixes sending failures. Freeze a new version and use held-out/longer fixtures before drawing an architecture conclusion. Preserve every failed attempt when revising tests; do not cherry-pick a passing rerun.

## Initial result: 2026-11-20

One run completed all nine cases / 27 calls with zero transport/decode failures, nine passing summary-fidelity checks and eighteen passing final-response spot checks. No retries, repairs, cache reuse, owner facts or saved advice cards. The fixture/gate check, test APK build and lint passed. A later numeric-only usage-field logging hardening was rebuilt and the deterministic check repeated; model requests and scoring were unchanged, and the live comparison was not repeated.

| Whole-path measurement over nine cases | Direct | Summary first, including summarizer |
| --- | ---: | ---: |
| Provider calls | 9 | 18 |
| Serialized request bytes, summed across all calls | 145,418 | 175,210 |
| Provider input tokens | 30,646 | 34,416 |
| Provider output tokens | 1,950 | 5,549 |
| Provider total tokens | 32,596 | 39,965 |
| Median successful pipeline time | 4,407 ms | 9,931 ms |

The summary reduced the final coach requests only slightly (143,426 bytes combined versus 145,418 direct). Including summary creation increased total request bytes by about 20.5% and total tokens by about 22.6%; median pipeline time was about 2.25 times direct. This shows overhead on these tiny inputs, not dollar costs, statistically established latency differences or evidence against summary reuse on a larger night. No preference ratings or independent normal-use nights were collected.

Keep the installed #65 behavior stable. Next, freeze held-out cases with realistic long/sparse night streams and stronger factual/constraint checks, assess summary information loss and repeat variability, then measure realistic reuse/invalidation and whole-day operation. Do not start the personal pilot or select a production winner from this screen.

## Running

Build the test APK and install only the test package. Preserve/check the main APK and accessibility configuration. Use `scripts/run_android_checks.ps1 -Classes 'com.bcardo.ringcoach.SignalComparisonTest#fixturesAndFidelityGate'` first. Provider calls require the explicit `live_signal_screen=true` instrumentation argument for `SignalComparisonTest#liveSyntheticScreen`. Always use the no-restart wrapper; never fall back to force-stop instrumentation or enable/toggle services or permissions. This harness does not use UiAutomation.

Keep aggregate run evidence and actual source/prompt versions with #50. Failed synthetic gates mean more development, not readiness for owner data. The 14-day personal pilot still requires source freshness checks, predefined daily pair selection, blinded owner assessment, retention checks, appropriate operational cost/reuse measurement and normal submission reliability. See [the agreed design](signal-context-benchmark.md) and [the separate #65 trial](context-budget-trial.md).
