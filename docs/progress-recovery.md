# Progress extraction and app-switch recovery

An inconsistent model answer can keep a saved submission in `failed` nutrition state while workout extraction has already completed. Coaching deliberately waits for confirmed facts. Retrying a model's incorrect arithmetic can repeat the same failure; a saved input is not a lost input or a reason to create a second journal entry.

## Local food totals

Incremental extraction validates food IDs, source submissions, changes to old facts and supplement links before accepting anything. New or changed links must identify the supplement in the linked food description/portion, so a protein-powder event cannot accidentally link to another food in the same submission. Recognized caloric supplement names require a food link when newly recorded or changed. Existing unchanged legacy aliases remain readable without rewriting old facts or requiring released photos.

After those checks, the app sums the validated food-item nutrients with decimal arithmetic. A field with an unknown food component remains unknown, not zero; known fields still sum independently. Weight and reported expenditure are unchanged. The result records `totals_source=local_food_fact_sum_v1`, and the UI distinguishes computed sums from the uncertainty in estimated food portions/composition. Numeric agreement does not prove that model food recognition or portion estimation is accurate. Existing results without that metadata remain readable.

The app still rejects invalid provenance, unsupported corrections, duplicate IDs and missing/mismatched recognized caloric supplement links before summation. Model repair remains available for those fact/link/schema failures. Recognition or omission errors can still occur; local arithmetic is not a guarantee that every reported food was correctly understood.

## Foreground health reads

Health Connect background reads require additional authorization beyond the existing metric read permissions; see [Android's raw-data read documentation](https://developer.android.com/health-and-fitness/health-connect/read-data). RingCoach uses its visible Activity lifecycle for these reads instead of requesting extra permission in this fix.

Automatic coaching starts its preparation when RingCoach is resumed. If the app leaves and returns during a health read, a revision token invalidates that read and the app repeats it in the foreground. This prevents a background-related denied read from being saved as though the owner revoked all metric permissions. A real denial/error while visible remains explicit. The pending submission stays saved, and preparation continues when the owner returns. The UI can explain that it is waiting for a foreground health read.

Once valid health evidence is captured, the model request can continue while the process remains alive. This is not a new durable background worker or a claim that Android can never kill the process. Restart recovery continues to use the persisted submission/coaching queue; network/model failures can still require the existing retry action. Do not re-enter the same meal to retry it.

## Verification and owner recovery

Synthetic tests cover inconsistent model totals, the wrong-food supplement-link failure, unknown nutrients, unchanged prior facts/sources and legacy links. Coroutine tests cover background-start deferral, leaving and returning during a read, genuine visible denial and cancellation. The opt-in `ForegroundCoachingRecoveryTest` exercises the actual Activity/ViewModel and checks collected health evidence before sending the refreshed request.

`PendingNutritionRecoveryTest` is an explicit opt-in owner diagnostic/recovery tool, not part of ordinary test runs. It can validate a saved model response with the current checks and local totals, then apply it to the existing job without appending a submission. Diagnostic request copies remove transient photo content; credentials are never recorded. Personal responses, database snapshots and recovery evidence stay under private app storage / ignored desktop `private/`, outside GitHub. HR sample streams remain transient as described in [the coaching request contract](coaching-request-context.md).
