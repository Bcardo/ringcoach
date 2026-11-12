# Recovery history view

Issue #32 adds a visible first slice on top of archive #44 / PR #45. This is intentionally placed in the existing History tab; the broader navigation review remains #41.

| Destination | Purpose in this slice |
| --- | --- |
| Today | Existing current recovery/intake and latest guidance |
| Training | Existing full training plan and latest adjustments |
| Journal | Sole dated progress composer and retry workflow |
| History | Recovery trends, source/date inspection, saved dated journals and advice |
| Settings | Existing configuration and diagnostics |

Flow: History → choose 7/30 days → inspect either chart → tap a date or use the shared date selector → read both daily measurements and the selected day's journal/advice below. The selected day's existing record card moves first and opens; it is not copied into a second journal browser. Older record/advice browsing remains available. Date-specific advice is queried independently of the initial 20-card page.

On narrow displays, charts stack. At 640 dp of available content width, they appear side by side. Both share the same dates and selection. Loading clears the prior range; an error offers a local read retry. No values yields an explicit empty chart, with gaps and per-date absence states. Source-read failures preserve archived readings and display a warning. Daily detail identifies today versus historical dates and reports verification time separately from measurement date. Current-day readings not verified today are marked. The archive query and history browsing do not call the model or initiate a new source sync.

The graph uses canonical archive values. Duplicate SDK/screen Energy Scores count once, SDK precision is preferred, and conflicting values remain gaps. Only adjacent compatible source/definition points connect. Numeric display rounds without altering stored values. Date selection is also available through labeled standard controls rather than relying solely on chart touch gestures. Range/date survive activity recreation; new foreground recovery snapshots reload the local view.

No additional tab, meal button, export button, score formula, source permission or expanded model context is introduced. The system Back action retains the existing return-to-Today behavior. The UI does not claim 30 days of collection merely because a 30-day range is selected.
