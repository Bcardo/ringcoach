# Incremental workout follow-ups

Milestone for #57 under the ongoing reliability tracker #52. The scope is removing full-day workout re-recognition from new Journal submissions after a confirmed state exists. It does not claim that every model/network/scheduling failure has been solved.

## Processing contract

Each new incremental submission queues a workout turn with the immutable submission text. The model receives that new text, confirmed structured records/clarifications with stable references, and exercise names/day/number mappings. It does not receive the accumulated mixed training Journal as recognition input or baseline capacities to copy.

The response is an explicit delta:

- `add`: a genuinely new actual training fragment.
- `replace` / `remove`: a source-backed correction/retraction of selected confirmed record IDs.
- `resolve` / `dismiss`: a source-backed clarification/retraction of selected unresolved fragments.
- Empty changes: questions, meal updates and future plans leave confirmed workout facts intact.

Every operation quotes the new input. Completed/partial capacity must quote that evidence, exactly reuse a targeted confirmed capacity, or quote an explicitly targeted unresolved fragment. Values are not normalized or converted. Skipped exercises need no invented completed capacity. Unknown/reused targets and unsupported performance fail closed. One corrective response is permitted; network retry remains separate and bounded. The same original submission is retried rather than appended again.

## Persistence and migration

Schema 14 adds `workout_turns` and `daily_workout_state`. Migration from schema 13 does not rewrite original journals, nutrition, workout history, settings or advice and does not rerun the old food/photo migration.

A settled legacy date bootstraps its confirmed records once when the next incremental submission arrives. Unfinished legacy recognition is not marked complete or silently adopted; it remains on the existing recovery path until settled. Direct replacement of original training text supersedes pending deltas and invalidates the derived state.

Turns commit in source order with a compare-and-set check on the prior confirmed turn. State, per-turn result, displayed exercise rows and dated extraction status commit together. Duplicate completion, interrupted work, stale results and newer submissions do not double-apply a change. A later pending turn leaves already confirmed progress available. New facts retain submission references and the correcting/clarifying evidence in coaching context.

No synthetic owner Journal entries, extra daily logging form or second coaching pipeline is introduced. Nutrition still processes its new turn; coaching runs after the necessary fact conversions finish. Raw HR/photo retention and accessibility permissions are unchanged.

## Validation and remaining acceptance

- Unit tests cover question no-op, new-only request shape, additional sets, targeted correction/retraction, prior clarification, unknown/duplicate targets, unsupported evidence, repeat reports and bounded repair.
- Disposable database tests cover bootstrap/reopen, rapid turns, stale results, source replacement, failed-turn retry, schema 13 migration and unresolved legacy failure.
- Existing background, supplement feedback and dated coaching tests exercise the integrated pipeline.
- An explicit live-model test uses synthetic facts in a disposable DB for a question, correction, additional set, future plan and retraction. It never writes synthetic owner submissions.
- Ordinary owner submissions, leaving/reopening the app, provider errors, source changes and future distinct failure classes remain continuing acceptance under #57/#52. A passing synthetic/live fixture is not closure of broad reliability.
