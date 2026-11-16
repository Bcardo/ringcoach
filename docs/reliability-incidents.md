# Processing reliability

Use [#52](https://github.com/Bcardo/ringcoach/issues/52) as the parent for submissions that fail to reach a saved coach response. Link each distinct confirmed cause rather than treating all “stuck” reports as one network problem.

| Cause | Tracking | Implementation / acceptance |
| --- | --- | --- |
| Wrong-date response or unrelated failure message | [#56](https://github.com/Bcardo/ringcoach/issues/56) | Date-specific response/status selection; regression tests cover a newer backdated response and an unrelated old failure. |
| Request exceeds byte budget | [#56](https://github.com/Bcardo/ringcoach/issues/56) | Lossless heart-rate row encoding, no downsampling or budget increase; exact timestamps, values and membership tested. One existing pending update recovered. |
| Feedback read contends with background database writer | [#56](https://github.com/Bcardo/ringcoach/issues/56) | Single SELECT read snapshot; reserved-writer regression passes. |
| Follow-up question restarts full-day workout recognition and rejected output blocks coaching | [#57](https://github.com/Bcardo/ringcoach/issues/57) | Preserve confirmed workout progress on append; strict validation with one corrective attempt, specific reasons and persistent bounded failure metadata. Ordinary-use recurrence acceptance remains open. |

For every recurrence, record the stage, specific failure code, reproduction class, fix, regression check, installed/source status and recovery outcome in its issue. Separate reproduced observations from suspected causes. A successful manual retry or synthetic test does not prove subsequent ordinary submissions are reliable. Do not close the parent while an end-to-end acceptance item remains unverified.

The app retains at most 100 processing failure/repair events in app-private `processing-failures.db`. Fields are timestamp, processing stage, fixed error code, attempt number, outcome, model and prompt version. The log never stores Journal text, UI text, model responses, health values, sample arrays, photos, credentials or device/submission identifiers. No log content is automatically uploaded.

Workout recognition permits one corrective model response for invalid source evidence/shape. Network/authentication failures follow the worker's existing bounded network policy; they do not trigger the validation repair loop. Invalid facts are never saved. A second rejected response stays blocked with its validation reason; reopening the app preserves that reason rather than replacing it with `extraction_incomplete` or starting an endless retry loop.

Appending a question keeps the previous confirmed workout rows visible if their quoted evidence remains in the source. An accepted new result replaces rows atomically. Explicitly replacing/deleting the original source invalidates those old rows. The extractor receives the confirmed rows and cannot silently omit their exercises in a follow-up result; an evidence-backed corrected/skipped record can replace an exercise's prior fact.

Longer-term architectural acceptance includes incremental workout updates so a question does not require recognizing the full accumulated workout text. The current repair is bounded mitigation, not a claim that this architectural dependency has been eliminated.
