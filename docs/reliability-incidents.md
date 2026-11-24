# Processing reliability

Use [#52](https://github.com/Bcardo/ringcoach/issues/52) as the parent for submissions that fail to reach a saved coach response. Link each distinct confirmed cause rather than treating all “stuck” reports as one network problem.

| Cause | Tracking | Implementation / acceptance |
| --- | --- | --- |
| Wrong-date response or unrelated failure message | [#56](https://github.com/Bcardo/ringcoach/issues/56) | Date-specific response/status selection; regression tests cover a newer backdated response and an unrelated old failure. |
| Request exceeds byte budget | [#56](https://github.com/Bcardo/ringcoach/issues/56), optimization [#65](https://github.com/Bcardo/ringcoach/issues/65) | Lossless plan/history/Ring/HR packing with a versioned outgoing format; fixed-category byte diagnostics also persist on failure. Hard cap unchanged, no sample/stage truncation. One mixed-update recurrence recovered without replaying completed recognition; continued ordinary-use acceptance remains open. |
| Feedback read contends with background database writer | [#56](https://github.com/Bcardo/ringcoach/issues/56) | Single SELECT read snapshot; reserved-writer regression passes. |
| Corrected food mass relabels a saved estimate without recalculating nutrients | [#77](https://github.com/Bcardo/ringcoach/issues/77), parent [#52](https://github.com/Bcardo/ringcoach/issues/52) | Nutrition prompt clarification and narrow `nutrition_portion_not_recalculated` validation with one corrective response. Original semantic failure threw no error. Explicit recovery preserved unrelated records; ordinary-use correction acceptance remains open. See [portion corrections](portion-corrections.md). |
| Follow-up question restarts full-day workout recognition and rejected output blocks coaching | [#57](https://github.com/Bcardo/ringcoach/issues/57) | Incremental new-input deltas after a confirmed state exists, retained confirmed progress, bounded repair and persistent metadata. Ordinary-use recurrence acceptance remains open. |

For every recurrence, record the stage, specific failure code, reproduction class, fix, regression check, installed/source status and recovery outcome in its issue. Separate reproduced observations from suspected causes. A successful manual retry or synthetic test does not prove subsequent ordinary submissions are reliable. Do not close the parent while an end-to-end acceptance item remains unverified.

The app retains at most 100 processing failure/repair events in app-private `processing-failures.db`. Fields are timestamp, processing stage, fixed error code, attempt number, outcome, model and prompt version. The log never stores Journal text, UI text, model responses, health values, sample arrays, photos, credentials or device/submission identifiers. No log content is automatically uploaded.

Workout recognition permits one corrective model response for invalid source evidence/shape. Network/authentication failures follow the worker's existing bounded network policy; they do not trigger the validation repair loop. Invalid facts are never saved. A second rejected response stays blocked with its validation reason; reopening the app preserves that reason rather than replacing it with `extraction_incomplete` or starting an endless retry loop.

Appending a question keeps the previous confirmed workout rows visible if their quoted evidence remains in the source. An accepted new result replaces rows atomically. Explicitly replacing/deleting the original source invalidates those old rows. The extractor receives the confirmed rows and cannot silently omit their exercises in a follow-up result; an evidence-backed corrected/skipped record can replace an exercise's prior fact.

The [incremental workout milestone](incremental-workout-followups.md) removes full accumulated-text recognition for new submissions once a confirmed state exists. Unfinished legacy recognition stays explicit on its existing recovery path. This finite implementation milestone is separate from ongoing ordinary-use reliability acceptance under #57/#52.

## Mixed-update request-budget recurrence

Read-only inspection found both recognizers complete and training facts committed/query-eligible while coaching remained blocked with `context_too_large`. Retry could rebuild under the same packing rules without addressing context growth. The older HR-only compaction fixed one earlier case, not all combined contexts. The original failed payload was not retained; an earlier successful morning card is not its measured substitute.

The #65 lossless milestone measured a fresh affected-date assembly and then recovered the existing queued item using the installed v16 prompt/packer. Completed intake/workout states and original owner records stayed unchanged; only one new matching advice card was saved. Synthetic retry/restart checks and a private preservation audit supplement that single manual recovery. Keep #56/#52/#65 open for subsequent ordinary mixed submissions, app-away/reopen/restart behavior and the remaining relevance-policy work. Do not treat this intervention or a replay as broad reliability acceptance.

Future request-size investigations should inspect the bounded size ledger first. Publish fixed stage/error codes, byte counts and source/installed/recovery status only; never copy request bodies or full manifests into operational logs/issues. A size failure is a request-packaging guard before provider POST, not proof of provider rejection or failed workout recognition. A stale view is a separate observation until display/query evidence establishes its cause.
