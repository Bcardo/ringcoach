# Context-budget ordinary-use trial (#65)

## Baseline

The trial uses the installed `coach-v16-lossless-context` application from production commit `951cac0` (PR #66). The hard cap remains 131,072 serialized bytes; the soft target is 114,688 bytes. The existing mixed-update feedback was recovered once without rerunning completed recognition or changing original facts. Its new request was 113,626 bytes versus 153,235 with the same selected context unpacked. That intervention is the baseline, not an ordinary-use acceptance result.

The continuation adds tests/documentation only. It does not change the installed app or context-selection policy during the trial. Canonical facts, thirty-day recovery coverage, separate sources and every fresh target-night sample remain under the existing contract. New history reductions, trace-to-summary substitutions and purpose-based selection changes stay separate work under #65.

## Additional quality checks

The first comparison covered three synthetic cases in both ordinary and packed formats: known intake/workout facts, a corrected workout load, and unknown intake. Four further paired cases check estimated partial-day intake, planned versus completed exercise, missing vendor daily HRV alongside experimental Ring windows, and conflicting vendor daily records. All fourteen explicitly opted-in provider calls passed the corresponding checks. Tests use synthetic facts, save no advice cards, read no owner facts and emit fixed case/format/pass markers rather than responses. The saved credential is used only for the provider connection.

These are finite semantic spot checks of the current lossless policy, not a general quality guarantee or validation of a future information-reducing policy. Exact reconstruction, missing/null distinctions, source/session separation, safe byte accounting and retry/restart regressions remain separate deterministic checks.

## Ordinary use

Use the app as usual. No special entries, repeated same-day tests, screenshot workflow, manual exports or forced scheduler runs are needed. The app already retains bounded privacy-safe preparation/failure metadata. No automatic monitoring upload or scheduled review is added.

An initial review can use roughly one week of normal use with at least five new submissions across several days, including mixed intake/training and ordinary follow-ups when they naturally occur. This is a provisional review checkpoint rather than a proven reliability threshold. If normal traffic is lower, extend observation instead of fabricating test entries.

Observe normal app-away/reopen behavior and any naturally occurring interruption/restart. Do not deliberately force-stop the owner's package or change accessibility, Bluetooth, permissions or battery settings for this trial. Android scheduling/network constraints can delay work; a queued/waiting state alone does not establish a size failure.

## Review evidence

For new submissions, inspect privately whether recognition finished, confirmed intake/workout state remains visible, the matching dated feedback card saved, and the queue/error state is honest. Record only aggregate counts, fixed stage/error codes, size/headroom ranges, installed policy/version and any manual intervention publicly. Manual/diagnostic requests, the original recovery and synthetic calls do not count as new ordinary-use successes.

The size ledger contains at most 100 timestamped reports. Per-section escaped byte counts plus structure bytes reconcile to user-content bytes; system and envelope complete the actual request total. Inspect the failing report before considering context-policy changes. Unknown fields aggregate to `other`; logs exclude text, readings, responses, sample arrays, photos, credentials, identifiers and hashes. No raw request/trace replay is available from saved cards.

An initial successful checkpoint needs the observed new submissions to reach their matching saved feedback without size blockers, re-entry, duplicate counting, fact loss or manual recovery. Report actual traffic/coverage and any unobserved scenarios. One quiet week, a successful retry, or synthetic cases alone does not prove all future requests fit or close broader background/restart reliability.

## If another update appears stuck

Keep the existing submission. Note its date and whether intake totals, the workout performance table, or the advice failed to update; these are different stages. Retry once through the normal date-specific action if appropriate, recording that intervention. Avoid re-entering the same meal/workout as a workaround.

Investigation should first inspect the persisted stage/code, completed recognition/facts, matching feedback/queue state and the safe size report. Compare packed/unpacked totals and the largest fixed sections. A `context_too_large` guard is before provider POST; it does not establish a failed workout extraction or model rejection. A stale view needs independent query/display evidence.

Track recurrence/recovery in #56 under #52, link any different confirmed cause, and keep optimization work in #65. Record source/installed status, reproduction class, fix, regression and recovery separately from suspected causes. No public Journal/UI/model text, values, arrays, photos, identifiers or credentials. See [reliability incidents](reliability-incidents.md).

## After the checkpoint

Review actual headroom and quality evidence before choosing another optimization. Remaining alternatives include request-purpose relevance tiers and target-date vendor detail with explicit historical coverage; any information reduction needs a versioned contract, omission reasons and separate comparisons. Keep #65/#56/#52 open until their respective remaining acceptance is satisfied. The trial is not authorization to silently change the context policy midway.
