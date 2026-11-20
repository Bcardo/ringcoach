# Daily decision panel: first #59 delivery

Source implementation, not an installed feature or completed ordinary-use acceptance. Parent roadmap: #58. This adapts Oura's contextual Today pattern into the existing four-tab app. The owner's installed #65 build remains unchanged while this draft is reviewed.

## Today

The first card presents the first saved current-date coaching note as the focus, the next at most two notes as supporting reasons, and the first training suggestion as the proposed next step. These are attributed model suggestions, not newly verified measurements or a new deterministic readiness assessment. No additional model request, classifier, prompt/schema change, persisted summary, plan edit, or recognition replay is introduced. Longer text is visually limited with ellipses in the compact view; expandable notes and training suggestions retain all original text. Dietary advice remains in the existing intake card.

Only `DailyCoaching` for the requested date and a card with that same date are eligible. A later-generated backdated card cannot displace today's advice. Pending updates retain the prior card with an explicit warning that it does not include the new input; the existing date-specific status/retry remains. With no card, the panel says advice is unavailable rather than inventing a decision. A button opens the existing Training tab, whose complete saved plan/latest confirmed performance table is unchanged.

This first delivery does not restructure model output into verified focus/reason/action fields or independently resolve semantic errors in the existing prose. It does not add separate fatigue extraction or infer today's training day from physiology. Quality checks for resolved historical questions, current reported feelings and proportionality remain part of broader #59 acceptance. Future structured output changes must be assessed separately from this presentation-only change and #65 observation.

## Night records

The second card reads the existing local Ring snapshot for the current wake date and timezone. It refreshes every 30 seconds only while Today is composed and the app is foregrounded. Read failures, loading and no eligible records have separate states; a failed read does not become zero or successful collection. It schedules no work and requests no permissions. Dated reception is not accepted as uninterrupted collection or numerical reference validation.

- Each segment must have a matching wake date, valid increasing timestamps, an end date matching the requested timezone/date, and an end no later than now. Stale/malformed/future records are excluded. A changed date/timezone clears the presentation state.
- A single segment shows its recorded interval and available heart-rate mean. Multiple/overlapping segments stay separate in the drill-down, with no summed sleep time or averaged means across sources. Intervals are explicitly labeled recording spans, not actual time asleep.
- Heart-rate means require positive sample counts, finite plausible values and an existing `bpm` unit. Prefer the minute series; use the separate protocol summary only if that series is unusable, never blend them. The label does not claim resting heart rate.
- HRV window presence is shown in detail with pending same-night validation; its numeric values/units are not promoted to vendor daily HRV or a recovery score. Temperature calibration and oxygen limitations remain explicit in detail. These gaps mean Bluetooth reception is not equivalent to Oura's complete input set.
- No personal-baseline comparison, percentile, alert threshold, health classification, sleep debt or composite score is computed. Those depend on #35/#60 and the separate coverage/reference work in #28/#64.

## Validation and review

Normal app build, 112 JVM tests (six new date/source/overlap/missingness cases) and zero lint errors pass. The final timestamp-only UI change was built/linted again; the pure data gate tests were unchanged. No live provider call was needed.

A debug-only `PanelPreviewActivity` uses entirely synthetic inputs. It is disabled in normal debug builds, absent from release, and enabled only with `-PpanelPreview=true`, which changes the application ID to `com.bcardo.ringcoach.panelpreview`. The activity opens no database, credential, collector, permission flow or job. Preview source exists to reproduce visual checks, not as a product route. Never install this preview artifact as the owner package.

Synthetic previews were visually checked at local 360/720 dp widths, 1.4 font scale, pending/missing states, expanded advice/night details and scrolling. These are layout simulations on the phone, not actual hardware-fold acceptance. The preview training link opens a synthetic stub; the production callback targets the existing Training tab. No real-card UI acceptance is claimed. Screenshots stay private; no UI text/readings/screenshots are posted to GitHub.

After preview removal, the owner's main APK hash and accessibility configuration were unchanged, with legacy service binding still present. Owner databases, permissions and collection settings were not accessed or altered by the preview activity. No production APK replacement, real-app instrumentation/force-stop, fresh-night acceptance or ordinary-use reliability claim. Keep #59 open for reviewed integration/install and the remaining semantic/usability acceptance; keep #58/#35/#60/#28/#64 and #52/#56/#57 scoped independently.

Reference verified 2026-11-20: [Oura's contextual Today and detailed Vitals organization](https://support.ouraring.com/hc/en-us/articles/360058599753-How-to-Use-the-Oura-App), [personal readiness contributors](https://support.ouraring.com/hc/en-us/articles/360057791533-Readiness-Contributors). This borrows the interface pattern, not Oura's score or proprietary methods.
