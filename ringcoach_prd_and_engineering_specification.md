# RingCoach: Product Requirements Document (PRD) & Engineering Specification

> Historical planning artifact, archived under [#78](https://github.com/Bcardo/ringcoach/issues/78). This early proposal is superseded by the repository README, current prompts and focused `docs/` implementation records. Schemas, sample values, wireframes and physiological rules below are proposal examples, not owner measurements, current implemented behavior or validated guidance. Retained for planning provenance; do not implement or deploy this proposal merely because it is present.

**Project Codename:** `RingCoach`  
**Architecture:** Local-First Quantified Lifting & Biometric Feedback System  
**Target Platform:** Personal Android prototype on Samsung Galaxy Z Fold7 (Kotlin / Jetpack Compose)  
**Hardware Ingestion:** Samsung Galaxy Ring (via Android Health Connect)  
**MVP Analysis:** OpenAI Codex CLI on the owner's computer, using their own OpenAI API key  
**Database:** Deferred; SQLite (`ringcoach.db`) is a candidate for the later app  
**Language Topology:** English Infrastructure & Schema / Simplified Chinese (简体中文) User Interaction & Coach Card Output  

**Review status (2026-11-10):** Section 0 defines the agreed scope of the first prototype and the recommended implementation. Sections 1–9 and 11 describe the earlier full-app proposal and are retained for future planning. They do not define MVP requirements; in particular, the old Flutter layout and Gemini examples do not apply to the prototype.

## 0. First MVP: Galaxy Ring Data → Codex Analysis

**Purpose:** On the owner's Galaxy Z Fold7, verify which Galaxy Ring data can be read through Samsung Health and Health Connect, then analyze a real, user-reviewed sample with OpenAI Codex. This is a personal prototype installed locally. The target repository is public `Bcardo/ringcoach`, with English issues.

**Stack:** Native Kotlin, a small Jetpack Compose screen, and the AndroidX Health Connect client. Native Android is the recommended shortest path here because the main risk is Android record access and permissions, and a Flutter bridge would add another layer to verify. See [Android's Health Connect setup](https://developer.android.com/health-and-fitness/health-connect/get-started).

1. **Read on demand.** Request only relevant Health Connect read permissions and query a bounded recent window in the foreground. Check sleep sessions/stages, timestamped heart rate, HRV RMSSD, and skin temperature separately. Capture the Android and Samsung Health versions, source attribution, available date range, and sample counts. Record whether Galaxy Ring attribution can actually be established; a Samsung Health source alone may not identify the wearable. Distinguish unavailable record types, denied access, no records in the window, and read errors. Do not derive HRV from ordinary BPM samples or label an unavailable value as zero.
2. **Review and export locally.** Show a small summary of available data and a preview of a bounded JSON export. Preserve units, timestamps/offsets, and sufficient source/coverage metadata to interpret the data. Omit unrelated fields and identifiers; keep health exports out of Git and GitHub. The owner explicitly exports/transfers the sample; reading Health Connect does not send data to OpenAI.
3. **Run Codex on the computer.** Use [non-interactive `codex exec`](https://learn.chatgpt.com/docs/non-interactive-mode) with the owner's OpenAI API key and an isolated working directory. Provide only the reviewed payload, without repository access or extra tools needed for analysis. Keep credentials on the computer and out of the APK, files, and diagnostic output. Start with a synthetic fixture, then explicitly run a real export. Use a short Simplified Chinese summary of observations, data gaps, and limitations; verify it does not invent missing values or infer a diagnosis. Record the Codex CLI version and model used. A manual phone-to-computer handoff is sufficient for the first proof.

**Done when:** A locally installed build successfully reads actual records on the Galaxy Z Fold7, records a per-metric availability result, and a user-reviewed real export produces a Codex analysis. Verify at least one partial-data or denied-permission case. Save only redacted validation notes in GitHub.

**Deferred:** In-app cloud calls, automatic syncing, database/history, journaling and parsing, routine/meal baselines, profile and equipment settings, backup/restore, and full coaching cards or training prescriptions. Revisit coaching constraints in their own issue after the device proof.

| ID | Issue | Acceptance criteria | Depends on |
| :--- | :--- | :--- | :--- |
| RC-MVP | Track the Fold7 → Codex prototype | Link the four MVP issues; record the test result and unavailable metrics before closing. | RC-01–04 |
| RC-01 | Scaffold and install the native Android prototype | A minimal Kotlin/Compose debug build installs and opens on the Fold7; document setup and build commands. | — |
| RC-02 | Read and validate Galaxy Ring data on the Fold7 | Request foreground permissions; record coverage, provenance and availability for each candidate metric; test denied and partial access. | RC-01 |
| RC-03 | Preview and export a minimal local telemetry sample | Show which fields will be exported; write bounded JSON on explicit action; preserve timestamps, units, and missingness; validate with a synthetic fixture. | RC-02 |
| RC-04 | Analyze a reviewed export using Codex | Document a repeatable desktop run using the owner's key; verify synthetic and actual samples; report observations and data gaps without exposing raw records or credentials in GitHub. | RC-03 |
| RC-LATER | Reconcile coaching rules, prompt, and tests | Resolve the RULE-02/TC-02 RPE disagreement (7.5 vs 7.0), RULE-01 precedence, and what the prompt may claim from telemetry; add acceptance examples. No coaching thresholds are changed by this MVP. | After MVP |

---

## 1. Executive Summary & Core Value Proposition

`RingCoach` is a local-first mobile application designed for advanced strength trainees who:
1. Practice **periodized progressive overload** with micro-plates ($1.1\text{ lbs} / 2.2\text{ lbs}$ or $0.5\text{ kg} / 1.0\text{ kg}$).
2. Track **body recomposition** requiring strict macronutrient compliance ($P / C / F$ and total kcal).
3. Utilize a **smart ring (Samsung Galaxy Ring)** for nocturnal autonomic recovery telemetry (HRV RMSSD, RHR Nadir timing, skin temperature deviation, and sleep architecture).

### Core Pillars
* **Zero-Friction Diff Logging:** Base routines and meal baselines remain persistent in system context. Trainees only log variations, top-set completions, joint discomfort, and precise macronutrients in free-form Chinese text.
* **Deterministic Feature Processing:** Statistical evaluations (rolling 30-day HRV Z-scores, Nadir latency shifts, bedtime-to-meal delta) are executed strictly by deterministic local algorithms prior to LLM dispatch.
* **Local Data Sovereignty:** Zero mandatory cloud servers. Data resides in an on-device SQLite database. The system uses single-turn stateless LLM inferences via API.

---

## 2. System Architecture & Information Pipeline

```
┌─────────────────────────────────────────────────────────────┐
│                       Hardware & OS Layer                   │
│   Samsung Galaxy Ring ──(BLE)──► Samsung Health             │
│                                       │ (OS Sync)           │
│                                       ▼                     │
│                             Android Health Connect          │
└──────────────────────────────────────┬──────────────────────┘
                                       │ (androidx.health.connect)
┌──────────────────────────────────────▼──────────────────────┐
│                    RingCoach Mobile App                     │
│                                                             │
│   ┌──────────────────┐               ┌──────────────────┐   │
│   │ Telemetry Ingest │               │ Chinese Diff Log │   │
│   │ (Background/Init)│               │ (Free-form Text) │   │
│   └─────────┬────────┘               └─────────┬────────┘   │
│             │                                  │            │
│             ▼                                  ▼            │
│   ┌─────────────────────────────────────────────────────┐   │
│   │           Local Feature Engine (Dart/Kotlin)        │   │
│   │   - 30-Day Rolling HRV Baseline & Z-Score           │   │
│   │   - RHR Nadir Timestamp & Fall Slope                │   │
│   │   - Meal-to-Sleep Delta: Δt = SleepStart - Dinner   │   │
│   │   - Lightweight Schema Parser (Normalizes to EN)    │   │
│   └─────────────────────────┬───────────────────────────┘   │
│                             │                               │
│                             ▼                               │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                 Local Storage Engine                │   │
│   │                 SQLite (`ringcoach.db`)             │   │
│   └─────────────────────────┬───────────────────────────┘   │
│                             │ (Deterministic SQL Assembly)  │
│                             ▼                               │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                 Inference Dispatcher                │   │
│   │        Single-Turn Context Payload Assembler        │   │
│   └─────────────────────────┬───────────────────────────┘   │
└──────────────────────────────┼──────────────────────────────┘
                               │ (Single HTTP POST)
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                    LLM Inference Engine                     │
│         Gemini 1.5 Pro / Flash API (or Claude 3.5 Sonnet)   │
│                                                             │
│   - Context: Routine Markdown + Pre-computed Flags + DB Rows│
│   - Output: Strict Simplified Chinese (简体中文) Directives  │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
           Rendered Daily Coach Action Card & Archival
```

---

## 3. SQLite Database Schema (`ringcoach.db`)

All dates use ISO-8601 string format (`YYYY-MM-DD`). Time values use 24-hour military format (`HH:mm`).

```sql
-- 1. Daily Aggregated Records: Biometrics, Nutrition, and Coaching History
CREATE TABLE IF NOT EXISTS daily_records (
    date TEXT PRIMARY KEY,                       -- e.g. '2026-11-10'
    
    -- Raw Journal
    raw_input_text TEXT,                         -- Raw Chinese input from trainee
    
    -- Nutrition & Metabolic Quantities
    calories REAL,                               -- Total calories (kcal)
    protein_g REAL,                              -- Total protein (g)
    carbs_g REAL,                                -- Total carbohydrates (g)
    fat_g REAL,                                  -- Total fats (g)
    food_summary TEXT,                           -- Normalized food list: 'Beef, salmon, rice'
    dinner_time TEXT,                            -- '20:15'
    caffeine_mg INTEGER DEFAULT 0,               -- Total caffeine (mg)
    caffeine_cutoff TEXT,                        -- Cutoff time: '13:00'
    supplements TEXT,                            -- 'Creatine 5g, Magnesium Glycinate 400mg'
    
    -- Subjective Readiness & Orthopedic Feedback
    mental_stress INTEGER CHECK(mental_stress BETWEEN 1 AND 5), -- 1 (Low) to 5 (Exhausted)
    morning_readiness_feel TEXT,                 -- '精力充沛', '关节沉重', etc.
    discomfort_notes TEXT,                       -- Normalized: 'Right anterior delt tightness, mild clicking'
    
    -- Objective Ring Telemetry (via Health Connect)
    sleep_start_time TEXT,                       -- '23:45'
    sleep_end_time TEXT,                         -- '07:15'
    sleep_duration_minutes INTEGER,              -- 450
    deep_sleep_minutes INTEGER,                  -- 85
    rem_sleep_minutes INTEGER,                   -- 105
    rhr_nadir INTEGER,                           -- Lowest nocturnal heart rate: 48
    rhr_nadir_time TEXT,                         -- Timestamp of nadir: '02:15'
    hrv_rmssd_avg REAL,                          -- Nocturnal RMSSD mean: 62.4
    hrv_zscore REAL,                             -- Normalized against 30-day baseline
    skin_temp_deviation REAL,                    -- e.g. +0.15 (°C)
    
    -- Deterministic State Flags
    state_flags TEXT,                            -- Comma-separated: 'METABOLIC_BURDEN,CNS_FATIGUE'
    
    -- Coach Card Output
    coach_plan_md TEXT,                          -- Markdown coaching response
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. Structured Progressive Overload History
CREATE TABLE IF NOT EXISTS exercise_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    exercise_name TEXT NOT NULL,                 -- Normalized: 'barbell_bench_press', 'squat'
    set_order INTEGER NOT NULL,                  -- 1, 2, 3...
    weight_lbs REAL NOT NULL,                    -- 137.2 (direct micro-plate tracking)
    reps INTEGER NOT NULL,                       -- 7
    rpe REAL,                                    -- 9.5
    is_failed_or_assisted INTEGER DEFAULT 0,     -- 1 if failure/spotter intervened
    notes TEXT,                                  -- 'Stuck at sticking point, micro-loaded +1.1'
    FOREIGN KEY(date) REFERENCES daily_records(date) ON DELETE CASCADE
);

-- 3. Dynamic Routine Templates
CREATE TABLE IF NOT EXISTS routine_templates (
    id TEXT PRIMARY KEY,                         -- 'arnold_4day', 'ppl_3day'
    name TEXT NOT NULL,
    is_active INTEGER DEFAULT 1,
    schema_definition_md TEXT NOT NULL,          -- Markdown description of routine splits
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_exercise_history_name_date 
ON exercise_history(exercise_name, date DESC);
```

---

## 4. Local Feature Engine & Deterministic Rules

Before assembling prompts for the LLM, the local system evaluates deterministic mathematical parameters to prevent hallucinations:

### 1. 30-Day Rolling Baseline & Z-Score Computation
Query the last 30 valid days of `hrv_rmssd_avg` from `daily_records`:
$$\mu_{30} = \frac{1}{N}\sum_{i=1}^N \text{HRV}_i, \quad \sigma_{30} = \sqrt{\frac{1}{N-1}\sum_{i=1}^N (\text{HRV}_i - \mu_{30})^2}$$
$$Z_{\text{HRV}} = \frac{\text{HRV}_{\text{today}} - \mu_{30}}{\sigma_{30}}$$

### 2. State Machine Rule Matrix

| Rule ID | Operational Trigger | Computed State Flag | Injected Enforcement Directive |
| :--- | :--- | :--- | :--- |
| **RULE-01** | $\Delta t = \text{SleepStart} - \text{DinnerTime} < 2.5\text{h}$ AND $\text{NadirTime} > \text{SleepStart} + 4\text{h}$ | `METABOLIC_DIGESTION_DELAY` | Attribute elevated RHR to digestive thermogenesis. Do NOT reduce training volume. |
| **RULE-02** | $Z_{\text{HRV}} \le -1.5$ AND $\text{RHR}_{\text{nadir}} \ge \mu_{\text{RHR}, 30} + 4\text{ bpm}$ | `CNS_DRAIN_CRITICAL` | `BLOCK_MICRO_LOADING = true`. Cap RPE at $\le 7.5$. Prescribe active recovery option. |
| **RULE-03** | `discomfort_notes` matches `[tight, clicking, pinch, pain, sore]` on joints | `JOINT_DEVIATION_ALERT` | `TRIGGER_EXERCISE_SWAP = true`. Swap out aggravating biomechanical vectors. |
| **RULE-04** | $\text{Protein} < 1.8\text{ g/kg}$ on high-volume training day | `PROTEIN_DEFICIT_WARNING` | Mandate +30g protein replenishment; restrict auxiliary accessory volume. |

---

## 5. Bilingual Schema Mapping & Normalization (Parser)

The user logs entries in free-form Chinese. A local parser prompt normalizes inputs into standardized English schema values for SQLite ingestion.

### Normalization Dictionary Sample

* **Lifts (`exercise_name`):**
  * `卧推` / `平板杠铃卧推` $\rightarrow$ `barbell_bench_press`
  * `上斜哑铃推胸` $\rightarrow$ `incline_dumbbell_press`
  * `深蹲` $\rightarrow$ `barbell_back_squat`
  * `罗马尼亚硬拉` / `RDL` $\rightarrow$ `romanian_deadlift`
  * `分腿蹲` / `保加利亚分腿蹲` $\rightarrow$ `bulgarian_split_squat`
  * `引体向上` $\rightarrow$ `pull_ups`
  * `自由泳` $\rightarrow$ `freestyle_swimming`
* **Anatomy & Symptoms (`discomfort_notes`):**
  * `右肩前束发紧轻微弹响` $\rightarrow$ `right anterior delt tightness, mild clicking`
  * `左膝内侧酸` $\rightarrow$ `left medial knee soreness`
  * `下背紧张` $\rightarrow$ `lower back tightness`

---

## 6. Prompt Engineering Architecture

The prompt separates internal logic (English) from user deliverables (Simplified Chinese).

```markdown
# SYSTEM PROMPT

You are an elite strength conditioning coach, biomechanist, and sports dietitian. Your task is to analyze the trainee's multi-source biometric recovery data, progressive overload history, and dietary intake to prescribe today's exact training directives and nutritional adjustments.

### SYSTEM PROTOCOLS
1. ZERO PLATITUDES: Do not include polite pleasantries, greetings, or motivational filler. Go straight to the structured directives.
2. PRECISE NUMERICAL ADJUSTMENTS: Reference exact poundages (down to 1.1 or 2.2 lbs micro-plates), exact rep counts, and target RPEs.
3. BIOMECHANICAL PROTECTION: If joint discomfort is flagged, immediately replace aggravating movement vectors with joint-friendly alternatives (e.g., barbell press -> 30° neutral dumbbell press).
4. METABOLIC DISAMBIGUATION: If `METABOLIC_DIGESTION_DELAY` is present, do not interpret elevated nocturnal heart rate as central fatigue.

### ACTIVE BASE ROUTINE
{ROUTINE_TEMPLATE_MARKDOWN}

### DETERMINISTIC CONTEXT PAYLOAD
- Date: {CURRENT_DATE}
- Target Session: {TARGET_SPLIT}
- Pre-computed Engine Flags: {STATE_FLAGS}

【Biometric Recovery (Galaxy Ring Telemetry vs 30-Day Rolling Baseline)】
- Sleep Duration: {SLEEP_DURATION} (Deep: {DEEP_SLEEP_MIN}m, REM: {REM_SLEEP_MIN}m)
- Resting Heart Rate Nadir: {RHR_NADIR} bpm (Reached at: {RHR_NADIR_TIME})
- HRV RMSSD: {HRV_CURRENT} ms | 30-Day Rolling Z-Score: {HRV_ZSCORE}
- Skin Temperature Deviation: {SKIN_TEMP_DEV} °C

【Nutrition & Metabolic Context】
- Yesterday Intake: {CALORIES} kcal | P: {PROTEIN}g | C: {CARBS}g | F: {FAT}g
- Food Items: {FOOD_SUMMARY}
- Dinner Timestamp: {DINNER_TIME} (Bedtime Delta: {MEAL_BEDTIME_DELTA})
- Caffeine: {CAFFEINE_MG} mg (Cutoff: {CAFFEINE_CUTOFF}) | Supplements: {SUPPLEMENTS}

【Subjective & Orthopedic Feedback】
- Morning Readiness: {MORNING_READINESS} | Mental Stress: {MENTAL_STRESS}/5
- Joint/Muscle Complaints: {DISCOMFORT_NOTES}

【Exercise History (Last 3 Occurrences of Target Lifts)】
{EXERCISE_HISTORY_ROWS}

### OUTPUT LANGUAGE & FORMAT SPECIFICATION
The output MUST be written in professional, natural Simplified Chinese (简体中文). Maintain standard fitness acronyms (RPE, HRV, Nadir, Deload, Z-Score).

Format the output strictly into the following three sections:
## 1. 今日负荷与动作指令
(Itemized list with exact weights, target rep range, RPE cap, and explicit exercise substitutions if indicated)

## 2. 饮食与身体重组调整
(Specific carbohydrate, protein, and timing interventions based on last night's metabolic performance and today's session volume)

## 3. 生理归因简析
(Exactly two concise sentences explaining the physiological mechanism behind today's directive based on the telemetry)
```

---

## 7. UI/UX Wireframe & Information Hierarchy

```
┌───────────────────────────┐  ┌───────────────────────────┐  ┌───────────────────────────┐
│       1. HOME / DASH      │  │      2. DAILY JOURNAL     │  │      3. COACH CARD        │
├───────────────────────────┤  ├───────────────────────────┤  ├───────────────────────────┤
│ [2026-11-10]    胸背分化  │  │ ◄ 返回           保存 [✓] │  │ ◄ 返回             分享  │
│                           │  │                           │  │                           │
│ 生理恢复遥测 (Galaxy Ring)│  │ 自由流水账输入 (Diff Mode) │  │ 状态: ⚠️ 神经疲劳保护    │
│ ┌───────────────────────┐ │  │ ┌───────────────────────┐ │  │                           │
│ │ HRV Z-Score: -1.45    │ │  │ │胸背。                 │ │  │ ## 1. 今日负荷与动作指令  │
│ │ 最低心率:    53 bpm   │ │  │ │卧推 137.2磅 8,7 (RPE  │ │  │ - 平板杠铃卧推:          │
│ │ 最低点时间:  04:30    │ │  │ │  9.5最后借力);        │ │  │   维持 137.2 lbs。暂缓加 │
│ │ 睡眠时长:    7h 12m   │ │  │ │引体自重4x8;           │ │  │   微配重。锁定 3组x6-8次 │
│ └───────────────────────┘ │  │ │右肩前束发紧轻微弹响。 │ │  │ - 上斜推胸动作降阶:      │
│                           │  │ │三大营养素: 2150kcal,  │ │  │   替换为 30° 中立握哑铃  │
│ 引擎状态标记              │  │ │155P, 210C, 48F。      │ │  │   推胸，规避肩峰撞击。   │
│ [CNS_DRAIN] [METABOLIC]   │  │ │三文鱼意面。晚饭 20:45 │ │  │                           │
│                           │  │ └───────────────────────┘ │  │ ## 2. 饮食与重组调整      │
│ ┌───────────────────────┐ │  │                           │  │ - 训练前补充 40g 快速碳水 │
│ │ [⚡ 生成今日教练指令] │ │  │ 关节/不适部位:          │  │ - 今晚晚餐截止提前至 19:30│
│ └───────────────────────┘ │  │ [ 右肩前束            ] │  │                           │
│                           │  │                           │  │ ## 3. 生理归因简析        │
│ 近期主项基准:             │  │ 晨起体感:                 │  │ HRV Z-Score (-1.45) 结合  │
│ 卧推: 137.2 lbs x 7       │  │ [ 关节僵硬 / 沉重     ] │  │ 延迟的心率最低点，证实神经│
│ 深蹲: 225.0 lbs x 8       │  │                           │  │ 恢复不完全，今日宜稳不宜冲│
│                           │  │ 快捷标记:                 │  │                           │
│ ⚙ 设置        📋 历史记录 │  │ [✓] 肌酸  [✓] 130mg咖啡因 │  │                           │
└───────────────────────────┘  └───────────────────────────┘  └───────────────────────────┘
```

---

## 8. GitHub Repository Layout & File Blueprint

```text
ringcoach/
├── .github/
│   └── workflows/
│       └── flutter_ci.yml               # Automated analysis and unit testing
├── android/
│   └── app/src/main/
│       ├── AndroidManifest.xml          # Health Connect read permissions & intents
│       └── res/values/health_permissions.xml
├── assets/
│   └── default_routines/
│       ├── arnold_4day.md               # 4-Day Asynchronous Arnold Split baseline
│       └── ppl_3day.md                  # Push/Pull/Legs baseline
├── lib/
│   ├── main.dart                        # Dependency injection & app launch
│   ├── core/
│   │   ├── constants/
│   │   │   └── prompt_templates.dart    # System prompts & injection builders
│   │   ├── database/
│   │   │   ├── database_helper.dart     # SQLite open helper & schema migrations
│   │   │   └── daos/
│   │   │       ├── daily_record_dao.dart# SQLite CRUD operations for daily_records
│   │   │       └── exercise_history_dao.dart # Rolling queries for progressive overload
│   │   └── rules/
│   │       └── deterministic_engine.dart# Z-Score, Nadir shift, and Flag rules
│   ├── services/
│   │   ├── health_connect_service.dart  # Ingests Sleep, HR, HRV from Android OS
│   │   ├── parser_service.dart          # Normalizes Chinese free-text into English schema
│   │   └── llm_inference_service.dart   # Dispatches payloads to Google GenAI API
│   ├── models/
│   │   ├── daily_record.dart
│   │   ├── exercise_entry.dart
│   │   └── physiological_flags.dart
│   └── ui/
│       ├── views/
│       │   ├── dashboard_view.dart      # Primary dashboard & readiness metrics
│       │   ├── journal_entry_view.dart  # Free-form logging view
│       │   └── routine_editor_view.dart # Custom base routine editor
│       └── widgets/
│           ├── coaching_card_widget.dart# Formatted Markdown render card
│           └── metric_badge_widget.dart
├── test/
│   ├── deterministic_engine_test.dart   # Tests for Z-Score and rule trip triggers
│   └── mock_health_data.dart
├── .gitignore
├── pubspec.yaml                         # sqflite, health, google_generative_ai
└── README.md                            # Setup guide, ADB permissions, Architecture overview
```

---

## 9. Verification Test Matrix

| Case ID | Scenario | Input Data & Biometric State | Deterministic Engine Output | Expected LLM Output Behavior |
| :--- | :--- | :--- | :--- | :--- |
| **TC-01** | **Normal Overload Advancement** | Prev Bench: 137.2x8 (RPE 8). HRV Z-Score: +0.3. Dinner: 19:30. No pain. | No warning flags. `ALLOW_MICRO_LOAD = true`. | Approves +1.1 lbs advancement (138.3 lbs) for 3x6 reps. Maintains accessory routine. |
| **TC-02** | **Central Neural Fatigue Interception** | Prev Squat: 225x8 (RPE 9.5). HRV Z-Score: -1.80. Nadir: +6 bpm above baseline. | Flag: `CNS_DRAIN_CRITICAL`. `BLOCK_MICRO_LOADING = true`. | Caps RPE at $\le 7.0$. Lowers load by 10-15% or suggests active recovery swimming. |
| **TC-03** | **Orthopedic Injury Interception** | Incline Bench routine. Log: *"右肩前束出杠刺痛弹响"*. HRV: Normal. | Flag: `JOINT_DEVIATION_ALERT`. Target: `right_anterior_delt`. | Intercepts barbell pressing. Swaps to neutral-grip dumbbell press; forbids overhead extension. |
| **TC-04** | **Metabolic Distortion Disambiguation** | Dinner: 22:15 (bed at 23:30). RHR elevated (+8 bpm), Nadir delayed to 05:00. HRV: -1.2. | Flag: `METABOLIC_DIGESTION_DELAY`. Overrides pure CNS drain classification. | Explains elevated RHR via late digestion. Does not lower lifting weights; mandates earlier dinner cutoff. |
| **TC-05** | **Offline Hardware Fallback** | Ring uncharged/missing. Health Connect returns empty sets. Input present. | Flag: `BIOMETRICS_UNAVAILABLE`. Sets telemetry fields to `null`. | Formulates training advice strictly from recent load trajectory and subjective readiness score. |

---

## 10. Requirements Review & Decision Log

Reviewed on 2026-11-10. The decision table below records the original full-app review. Section 0 supersedes it for MVP scope: D-01 has an owner and audience, D-02 has a recommended stack, D-03 is an MVP validation task, and D-04/D-06 use desktop Codex and the owner's key for the prototype. D-05 and D-07–15 are deferred. Resolve full-app questions before publishing the corresponding implementation issues.

| ID | Unclear requirement or inconsistency | Proposed resolution / information needed | Status |
| :--- | :--- | :--- | :--- |
| D-01 | GitHub owner, repository visibility, issue language, and release audience. | Public `Bcardo/ringcoach`, English issues, personal Android MVP. | Confirmed |
| D-02 | Header permits Flutter or Kotlin/Compose; repository blueprint and CI assume Flutter. | Select one primary stack, then update architecture, dependencies, layout, and CI together. | Awaiting user |
| D-03 | A metric being supported by Health Connect does not establish that Samsung Health exports it from the user's Galaxy Ring. | Record phone/Android/Samsung Health versions and validate actual records for sleep stages, timestamped HR, HRV RMSSD, and temperature. Disable unsupported derived metrics rather than inventing values. | Awaiting user / device validation |
| D-04 | Gemini 1.5 and Claude 3.5 names are embedded throughout; client-library compatibility has not been established. | Choose a currently supported model and client for the selected stack during setup; make model ID configurable and document the verification date. Do not carry legacy model names forward as requirements. | Technical selection pending |
| D-05 | Section 5 says "local parser prompt," while the architecture also implies a lightweight local parser and a single cloud call. | Choose on-device parsing or separate cloud parsing and coaching calls. If cloud parsing is selected, define structured validation and an editable preview before committing records. | Awaiting user |
| D-06 | Local-first storage is conflated with cloud inference privacy and offline capability. | Specify which fields may leave the device, user-supplied API-key storage, and behavior on no network/key/quota. Distinguish ring-data absence from inability to call the model; retain journal/history access offline. | Awaiting user |
| D-07 | RULE-01 prohibits reducing volume, potentially conflicting with fatigue or pain restrictions; RULE-02 caps RPE at 7.5 while TC-02 uses 7.0. | Propose pain/recovery constraints taking precedence over meal timing and an RPE cap of 7.5 in both examples. Add simultaneous-trigger tests and application-side output validation. | Awaiting user |
| D-08 | Rule flags and prose imply confirmed digestive or central nervous system causes; sharp pain automatically leads to a prescribed substitution. | Propose describing uncertain associations explicitly, avoiding diagnostic claims, and stopping the aggravating movement for sharp pain rather than automatically prescribing another loaded movement. Reconcile prompt, flags, and wireframe wording. | Awaiting user |
| D-09 | Protein g/kg needs body weight, which is absent; fixed +30g does not necessarily close the deficit; "high-volume" is undefined. | Add body-weight history and configurable nutrition targets. Define training-volume classification and calculate the actual target gap locally. Confirm whether volume restrictions belong in this rule. | Awaiting user |
| D-10 | Routine and meal baselines are promised, but meal templates/profile data are absent; Markdown routines cannot reliably drive exact progression alone. | Add editable meal baselines and structured routine/session prescriptions alongside display Markdown. Version templates and do not count unconfirmed planned sets/meals as completed actuals. | Awaiting user |
| D-11 | "Last 30 valid days" could mean a calendar window or 30 observations. Cold start, missing HRV, zero variance, and RHR baseline are undefined. | Propose the preceding 30 calendar days excluding the current night, with at least 14 valid nights for each relevant baseline. Missing inputs or zero variance yield unavailable features and no rule evaluation requiring them. Define nocturnal HR minimum separately from a vendor resting-HR measurement. | Proposed; confirm with final rule design |
| D-12 | HH:mm alone cannot represent cross-midnight dinner/sleep/nadir intervals, travel, or daylight-saving changes. One daily row also mixes yesterday's intake and today's readiness. | Preserve full timestamps with offsets and timezone context for events; propose assigning the primary sleep to its wake date. Link previous-day nutrition explicitly. Define main sleep versus naps, HR sampling sufficiency, and nadir tie handling. | Proposed; confirm with final data design |
| D-13 | Increment units, total-versus-per-side loading, dumbbell conventions, progression criteria, and failed-set handling are unspecified. | Confirm equipment increments and units; use precise decimal/fixed-point load representation, explicit equipment conventions, and deterministic progression rules. Keep kg↔lb conversion distinct from rounded plate labels. | Awaiting user |
| D-14 | Repeated saves/syncs may duplicate exercise rows; mutable health records and coach-card regeneration lack provenance. | Define journal revisions, stable source IDs, deduplication/update/delete handling, atomic saves, migration versioning, and coach-card history with input/rule/prompt/model versions. Preserve unknown versus confirmed zero values. | Proposed engineering requirements |
| D-15 | Backup/restore, deletion, history screens, and boundaries of v1 are not specified, despite local-only data ownership. | Confirm v1 scope; suggest local export/import, complete local deletion, profile/settings, routine/meal editing, and history. Define backup contents and exclusion of API keys. | Awaiting user |

### External documentation checked

- [Samsung Health Connect FAQ](https://developer.samsung.com/health/health-connect-faq.html) documents synchronization of activity, heart rate, and sleep, but does not establish export of every Galaxy Ring metric assumed in this PRD. Device validation remains required.
- [Android Health Connect: Read raw data](https://developer.android.com/health-and-fitness/health-connect/read-data) documents background-read and history-access permissions. Foreground/manual refresh, permission denial/revocation, and historical access limits must be covered in implementation.
- [Gemini API deprecations](https://ai.google.dev/gemini-api/docs/deprecations) is the model lifecycle reference for selecting and maintaining a supported model. The final model/client selection remains open.

### Repository creation status

Target: public `Bcardo/ringcoach`, with English issues. The owner confirmed their local GitHub CLI is authenticated as `Bcardo`. This workspace cannot read the CLI configuration or connect directly to GitHub's API, so the repository and issue status have not been verified here. Section 0 lists the issues and Section 13 contains the research to attach to them.

## 11. Proposed User Stories & Tracking Structure

This is the draft full-app backlog, deferred until the prototype in Section 0 is validated. Publish these as implementation issues only after their scope is confirmed. Technical investigations should be labeled separately from user-facing stories.

| ID | Proposed story or enabling task | Minimum acceptance outcome | Dependencies |
| :--- | :--- | :--- | :--- |
| EN-01 | Validate the target phone's Galaxy Ring → Samsung Health → Health Connect data path. | Produce a per-metric availability/sampling/source matrix on the target device and document unavailable-metric behavior. | D-03 |
| EN-02 | Establish the selected Android app stack, database migration framework, and CI. | App builds; automated checks run; migration strategy and supported Android versions are documented. | D-02 |
| US-01 | As a trainee, configure my body weight, units, equipment increments, and nutrition targets. | Values persist; invalid values are rejected; targets distinguish unknown inputs from zero. | D-09, D-13; EN-02 |
| US-02 | As a trainee, grant/manage health access and refresh my readings. | Permission denial/revocation and partial results are handled; source and last-sync time are visible; repeated syncs are idempotent. | EN-01, EN-02 |
| US-03 | As a trainee, create and edit my baseline routine and session sequence. | Active routine, prescribed sets/reps, equipment, and session selection are explicit and persistent. | D-10; EN-02 |
| US-04 | As a trainee, maintain meal baselines and log only deviations. | Baseline versions and logged changes can be reviewed; unconfirmed meals are not silently recorded as eaten. | D-10, D-15; EN-02 |
| US-05 | As a trainee, enter Chinese training/nutrition/readiness notes and review parsed values. | Exercise aliases, units, set counts, discomfort, and nutrition are validated; ambiguous values can be corrected before save. | D-05; US-01, US-03 |
| US-06 | As a trainee, correct or append journal entries without duplicating completed sets. | Raw text and corrections remain traceable; saves are atomic; reopening/re-saving does not duplicate records. | D-14; US-05 |
| US-07 | As a trainee, view recovery metrics with clear data-quality indicators. | Cross-midnight sleep/nadir intervals are correct; unavailable/stale metrics and insufficient baselines are distinguishable. | D-11, D-12; US-02 |
| US-08 | As a trainee, receive consistent readiness constraints calculated on-device. | Boundary, missing-data, zero-variance, and overlapping-rule cases produce deterministic outcomes with explanations. | D-07, D-08, D-11; US-07 |
| US-09 | As a trainee, track progressive overload using equipment-compatible increments. | Recommendations respect confirmed actual sets, failed/assisted sets, selected units, available increments, and readiness constraints. | D-13; US-01, US-03, US-06, US-08 |
| US-10 | As a trainee, compare actual nutrition against my configured targets. | Actuals and targets remain distinct; target gaps are calculated locally using applicable body weight; missing intake is visible. | D-09; US-01, US-04, US-06 |
| US-11 | As a trainee, configure my AI credentials and understand what data is sent. | Credential storage and payload scope are defined; credentials/health records never enter repository files or diagnostic logs. | D-04, D-06; EN-02 |
| US-12 | As a trainee, generate and archive a Simplified Chinese coach card. | Three agreed sections render; output respects locally computed constraints; invalid output/failures have defined handling; card versions retain provenance. | D-07, D-08, D-14; US-08, US-09, US-10, US-11 |
| US-13 | As a trainee, keep logging and reviewing history when ring data or network access is unavailable. | Ring absence, no network, missing key, and API failure are tested separately; no fabricated telemetry or fresh AI card is displayed. | D-06; US-06, US-12 |
| US-14 | As a trainee, browse journal, lift, biometric, and coach-card history. | Historical records are accessible; editing source inputs marks derived results/cards as needing recalculation. | D-14, D-15; US-06, US-07, US-12 |
| US-15 | As a trainee, export/restore my local data and delete it when needed. | A restore round trip preserves approved data types; malformed/unsupported backups are rejected safely; API keys are excluded; deletion scope is explicit. | D-15; EN-02 |
| US-16 | As a trainee, use a dashboard linking journal, today's session, recovery, and coach-card generation. | Simplified Chinese UI reflects real data/state and provides clear empty, loading, and failure states. | US-03, US-06, US-07, US-12 |
| EN-03 | Verify the complete MVP against reconciled acceptance scenarios on the target device. | Cover TC-01–05 plus conflicting flags, cold start, midnight/DST, duplicate sync, editing, partial permissions, malformed AI output, and backup/restore if in scope. | All approved MVP stories |

Proposed epic groupings: Foundation & Data Access; Baselines & Journaling; Deterministic Coaching; AI Coach & Dashboard; History & Data Ownership. Priorities and milestones will be assigned after scope is confirmed.

## 12. Open-source Research Before Implementation

RingCoach combines a personal strength and nutrition log, a coach that uses recent training and recovery data, and a Samsung Galaxy Ring integration. The closest public projects cover different parts of that workflow. Their documentation establishes the approaches below. Actual data availability on the owner's Galaxy Z Fold7 still needs a device check.

| Project | How it works | Relevance to RingCoach |
| :--- | :--- | :--- |
| [openGym](https://github.com/DuarteSantos8/openGym) (AGPL-3.0) | Supports routines, logged sets and workout history, equipment settings, and optional AI coaching. Its [coach](https://github.com/DuarteSantos8/openGym/blob/main/docs/AI_COACH.md) receives bounded training context, validates proposed changes, and asks the user to apply them. Its self-hosted coach also documents a Codex CLI runtime. | Strongest reference for lifting, equipment-aware progression, and coach approval. The documented coach inputs do not establish a Galaxy Ring recovery feed. |
| [Hangry](https://github.com/sqrlwallet/hangry) (PolyForm Noncommercial) | Native Kotlin/Compose app that reads Health Connect, stores data in Room, computes recovery and nutrition views, and sends health context to an optional OpenRouter coach using the owner's key. | Closest architectural match for Android wellness and wearable-aware coaching. Its documented training features focus on workouts and cardio rather than fine-grained strength progression. The source is available under a noncommercial license. |
| [Skulpt](https://github.com/skulptapp/skulpt) (GPL-3.0) | React Native/Expo workout planner and set log with local SQLite, optional sync, and Health Connect integration. Its roadmap describes computing wearable insights locally and supplying derived results to agents. | Closest published roadmap to the broader RingCoach design. Recovery intelligence and agent tooling are roadmap items in its README. |
| [Health Connect Data Exporter](https://github.com/maksimaitov/health-connect-data-exporter) (MIT) | A read-only Android exporter with no Internet permission. Writes NDJSON by record type and a manifest with counts and per-type errors. Includes sleep stages, heart rate, RMSSD and source metadata when present. | Small reference for the device probe and export format. Its supported-record list does not include skin temperature. |
| [Health.md for Android](https://github.com/CodyBontecou/health-md-android) (AGPL-3.0) | Lets the owner select metrics, preview the output, and export local Markdown, JSON, NDJSON or CSV. Its raw snapshots preserve timestamps, offsets, source metadata and nested samples. | Closest reference for preview and selective export, and a candidate tool for inspecting the Fold7 before building RingCoach. Check each required metric separately. |
| [Google Health Monitor](https://github.com/vkorost/google-health-monitor/blob/main/docs/04-samsung-export.md) | Supplements Health Connect-derived data by parsing Samsung Health's personal export. Its importer documents separate provenance for Samsung nightly metrics such as sleeping HRV, temperature and sleep score. | Reference for a manual fallback if Health Connect lacks important fields. The documented results are specific to the author's export and do not establish RingCoach's Galaxy Ring coverage or HRV measurement equivalence. |

### Recommended first device check

1. On the Fold7, use [Health Connect's data browser](https://support.google.com/android/answer/12201872?hl=en-GB) to inspect several recent nights. Check whether Samsung Health wrote sleep sessions and stages, timestamped heart rate, HRV RMSSD, and skin temperature. [Samsung's synchronization FAQ](https://developer.samsung.com/health/health-connect-faq.html) lists sleep and heart rate but does not establish Ring-specific coverage for the other fields.
2. Use an existing local exporter for one reviewed sample. Capture record counts, sample cadence, writing app, and device attribution when present. Analyze a small subset with desktop Codex. Repeat with a few logged sets from an existing tracker or a manually prepared sample if evaluating the coaching concept as well as ingestion.
3. If important fields are missing, compare the same nights in Samsung Health's personal export. Samsung's [Health Data SDK sleep sample](https://developer.samsung.com/codelab/health/sleep-data.html) is another option for testing richer sleep data and device filters. [Samsung documents local developer-mode testing and partner registration for distribution](https://developer.samsung.com/health/data/guide/app-verification.html). Verify the available data type and units before using either route for a coaching rule.

Use the result to decide which Android work is needed. An existing exporter can prove the data-to-Codex path first. RingCoach's proposed value is the daily combination of recent lift performance, equipment-specific increments, a short journal of changes, and whatever overnight recovery data the Fold7 reliably exposes.

## 13. Research Notes for GitHub Issues

These notes reflect documented project behavior and selected source files. Ring data availability and suitability for coaching still require testing on the Fold7.

### [RC-02] Galaxy Ring data and candidate calculations

For the device probe, record source app, device attribution when available, timestamps and offsets, sample counts, cadence and gaps for each metric. Keep feature unavailable, permission denied, empty and error as distinct results. Android skin temperature can be a series of deltas with an optional reference temperature. Preserve both fields as provided. A Samsung Health writer does not by itself establish that a record came from the Ring. [Android skin temperature API](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/SkinTemperatureRecord) · [Samsung sleep sample and device filtering](https://developer.samsung.com/codelab/health/sleep-data.html)

Google Health Monitor joins Samsung sleep sessions linked by `combined_id`, suppresses overlapping imports, and computes temperature change against the median of up to 30 prior valid nights, with a seven-night minimum in its implementation. Its Samsung sleeping HRV comes from `shrv_value` in a vendor export. Keep that metric distinct from Health Connect RMSSD until its definition and units are verified. Hangry describes a 30-day personal baseline for HRV, resting heart rate and sleep. Its default treats missing HRV as favorable, so RingCoach should preserve missingness and show coverage. [Samsung import code](https://github.com/vkorost/google-health-monitor/blob/main/tools/samsung_import.py) · [Hangry recovery design](https://github.com/sqrlwallet/hangry#today)

Candidate follow-up for US-07/US-08: compute stage duration from valid intervals, compare measured nightly HR and HRV with their own prior-night baselines, and evaluate raw HR nadir alongside a rolling-median minimum if sample cadence supports it. Report the method, valid-night count and coverage. Compare median-based temperature trends with the PRD baseline approach using synthetic gaps, duplicate sources, sparse samples and cross-midnight nights. Label overnight minima separately from vendor resting HR. HRV requires a documented RMSSD measurement or suitable beat intervals. Keep readiness thresholds in RC-LATER.

### [RC-03] Selective export UX

Health.md lets the user select metrics and preview a local export. Health Connect Data Exporter uses per-type NDJSON and a manifest with counts and errors. For RingCoach, preview a bounded date range, the fields to be exported, provenance and gaps, then write the reviewed sample on explicit action. Include metric units and an export schema version so the desktop reader can interpret the file. [Health.md export UX](https://github.com/CodyBontecou/health-md-android) · [Health Connect Data Exporter](https://github.com/maksimaitov/health-connect-data-exporter)

### [RC-04] Prompt, data retrieval and model execution

openGym builds a bounded, allowlisted payload in application code and pairs it with task-specific instructions. A debrief includes the completed session, comparable prior sessions and derived context. The application validates the response and allows one repair round. User-driven chat follow-ups and repair calls serve different purposes. openGym's separate MCP bridge exposes workout and progression queries to external AI clients and reuses the same calculation code as the UI. Its documentation also describes an optional paired workout write tool. Skulpt puts local calculation tools and agent access on its roadmap. The reviewed paths give us patterns for application-owned retrieval and computation, with no evidence that subagents are required. [Payload code](https://github.com/DuarteSantos8/openGym/blob/main/api/coach/core/payload.js) · [Coach flow](https://github.com/DuarteSantos8/openGym/blob/main/docs/AI_COACH.md) · [MCP tools](https://github.com/DuarteSantos8/openGym/tree/main/mcp) · [Skulpt roadmap](https://github.com/skulptapp/skulpt#roadmap)

For the MVP, build one versioned, reviewed snapshot with date range, metric values, units, coverage and provenance. Send it with a task prompt that treats the data and any journal text as inputs. Ask for structured observations, the input evidence for each, and data gaps. Validate those fields and retain the prompt version, model and export version with the local result. A later training review can add a bounded set of recent confirmed workouts and locally computed progression values. Consider read-only MCP for follow-up questions that require new date ranges or exercise queries.

### [RC-LATER] Fitness input and coaching output

openGym opens a routine with prior weights prefilled and lets the user record actual sets, reps and effort. It offers a post-workout debrief and in-session prompts for situations like limited time or unavailable equipment. Proposed changes appear as cards with reasons and are applied after user review. Hangry's chat accepts text, voice and photos and presents confirmable actions for logging. Its separate meal photo flow logs an estimate and offers an edit shortcut. [openGym training UX](https://github.com/DuarteSantos8/openGym#features) · [openGym coach UX](https://github.com/DuarteSantos8/openGym/blob/main/docs/AI_COACH.md) · [Hangry logging and Dash](https://github.com/sqrlwallet/hangry#features)

For US-05/US-09, prefill the routine and last confirmed loads, with explicit confirmation of actuals. Offer a short Chinese note for changes, and preview the parsed sets, units and readiness before saving. For US-12/US-16, prototype a short coach card showing the proposed set or load change, supporting training and recovery evidence, and any coverage gap. Keep the proposal editable and require an explicit apply action for future session changes. Validate against the equipment increments and agreed coaching limits in application code. Include cases for identical inputs with missing recovery data, completed sets, and unsupported load increments.
