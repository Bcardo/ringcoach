# Daily default breakfast

A user-authorized standing breakfast can be saved in Settings instructions. A recipe mentioned as a reference does not enable automatic intake. An explicit rule includes an effective date, recipe and all four nutrient values; its personal contents stay in the user's saved settings, not application source.

The rule is a readable block in the existing Settings instructions:

```text
[每日默认早餐]
生效日期：YYYY-MM-DD
内容：your recipe and portions
蛋白质g：number
碳水g：number
脂肪g：number
热量kcal：number
[/每日默认早餐]
```

Editing or removing this block changes/disables the rule after Save. Settings displays the parsed rule. Duplicate, incomplete or invalid blocks cannot be saved. The effective date prevents retroactively adding breakfast to old history or imported nutrition totals.

## Counting and daily exceptions

RingCoach calculates the default contribution once per eligible date, even without a daily journal submission. Both the nutrition table and all coaching request paths use the same read-time calculation. The table labels it **按固定规则默认计入；未逐日确认**, and the request includes the rule, counting mode and contribution. It is an assumption explicitly authorized by the user, not a fabricated daily report, food-recognition event or supplement dose.

The meal recognizer receives the saved diet reference and active rule. Each recognized date keeps a `breakfast_override` with quoted evidence, source references and relevant actual food IDs:

- No breakfast change: retain the date's existing state; otherwise default to the standing rule.
- “固定早餐 / 早餐照旧”: confirm the recipe once. Do not also emit a second copy of it as a food fact.
- “早餐没吃”: skip the default for that date.
- “早餐换成…”: use the recorded replacement meal and omit the default.
- Changed portions or additions: materialize the complete adjusted breakfast, use those actual food facts, and omit the default.
- Already-recorded breakfast or a reported whole-day total: do not add breakfast again. This state survives later additional food reports.
- Ambiguous old aggregate or pending/failed recognition: do not manufacture a combined total; display the unresolved state.

An exception affects the selected journal date only. It does not change future days' standing rule. Explicit long-term changes must be saved in Settings. Unknown nutrients remain unknown, even if the default breakfast's own contribution is known.

## History and source boundaries

The default is projected from saved raw facts; it is not inserted into `daily_food_state`, `nutrition_turns` or `progress_submissions`. In particular, the existing legacy-to-incremental seeding path reads raw nutrition without the projection, and the seed boundary rejects projected results. Re-reading, restarting or confirming the same meal cannot accumulate extra copies.

When enabling a rule over existing same-day food records, the one-time `BreakfastReconciliation` classifier identifies whether breakfast was already counted. It changes only the dated interpretation metadata, keeps food facts and raw nutrient totals unchanged, validates quoted source evidence and food references, and uses a compare-and-set transaction to avoid overwriting a concurrent update. If uncertain, it leaves the default unapplied until the ambiguity is resolved.

Coaching receives already-adjusted totals and an explicit assumption/confirmation label. It must not add breakfast again or repeatedly ask whether the user ate the standing breakfast; any numeric remaining allowance is conditional on the default assumption. Saved advice retains the rule and counting evidence used at the time, rather than rewriting older cards.

## Verification

JVM tests cover explicit opt-in, effective dates, once-only projection, confirmation, skipped/replaced/adjusted meals, unknown nutrients, whole-day totals followed by extra food, evidence/source validation and recipe delivery to the extractor. Isolated phone tests cover persistence, no invented journal events, the confirmed-history boundary and reconciliation without changing existing foods. Owner activation and real-model checks are opt-in; recipe files and model outputs remain app-private/local, never test assets.

Use `scripts/run_android_checks.ps1` for phone tests. It preserves the accessibility setting and uses `--no-restart`; no force-stop or permission toggles are needed for breakfast setup.
