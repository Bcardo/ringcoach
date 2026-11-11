"""Summarize a reviewed RingCoach export with Codex or Qwen."""

import argparse
from datetime import datetime, timedelta, timezone
import json
import math
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


MAX_EXPORT_BYTES = 1024 * 1024
METRICS = {
    "sleep": "睡眠",
    "heart_rate": "心率",
    "hrv_rmssd": "心率变异性（RMSSD）",
    "skin_temperature": "皮肤温度",
}
GAP_LABELS = {
    "not_selected": "未纳入本次导出",
    "empty": "所选时间段内无记录",
    "no_permission": "未授权读取",
    "unsupported": "当前设备或版本不支持读取",
    "error": "读取失败",
}
STAGES = {"awake", "sleeping", "out_of_bed", "light", "deep", "rem", "awake_in_bed", "unknown"}
UTC_TIME = re.compile(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z")
RESPONSE_FIELDS = ("observations", "limitations")
SCHEMA = {
    "type": "object",
    "properties": {field: {"type": "array", "items": {"type": "string"}} for field in RESPONSE_FIELDS},
    "required": list(RESPONSE_FIELDS),
    "additionalProperties": False,
}
PROMPT = """请用简体中文概述 RingCoach 的数据。仅使用下方的汇总事实，无需调用任何工具。
observations 写 1 至 3 条简短观察。数值、单位和样本范围须与事实一致。
limitations 写 1 至 2 条简短限制。不可推断诊断、疾病、恢复程度或训练建议。
样本心率的最小值和平均值仅代表所含样本。会话长度不等于实际睡眠时长。
不得用普通心率推算 HRV，也不得将温度差值当作绝对温度。不要为缺失数据补值。
"""


def timestamp(value):
    match = UTC_TIME.fullmatch(value) if isinstance(value, str) else None
    if match is None:
        raise ValueError("Expected an ISO 8601 UTC timestamp.")
    seconds, fraction = match.groups()
    micros = (fraction or "").ljust(6, "0")[:6]
    return datetime.fromisoformat(f"{seconds}.{micros}").replace(tzinfo=timezone.utc)


def number(value):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError("Expected a finite measurement.")
    return value


def numeric_summary(values):
    if not values:
        return {"sample_count": 0}
    return {
        "sample_count": len(values),
        "min": round(min(values), 2),
        "max": round(max(values), 2),
        "mean": round(statistics.fmean(values), 2),
    }


def interval(record, lower, upper, *, allow_point=False):
    start, end = timestamp(record["start"]), timestamp(record["end"])
    if not lower <= start <= end <= upper or (start == end and not allow_point):
        raise ValueError("Record interval is outside its declared time window.")
    return start, end


def series(records, window, collection, field, *, allow_point=False):
    values = []
    for record in records:
        start, end = interval(record, *window, allow_point=allow_point)
        for sample in record[collection]:
            if not start <= timestamp(sample["time"]) <= end:
                raise ValueError("A sample is outside its record interval.")
            values.append(number(sample[field]))
    return numeric_summary(values)


def metric_facts(metric, records, window):
    if metric == "heart_rate":
        return {"beats_per_minute": series(records, window, "samples", "beats_per_minute", allow_point=True)}
    if metric == "skin_temperature":
        return {
            "delta_celsius": series(records, window, "deltas", "delta_celsius"),
            "records_with_baseline": sum(record["baseline_celsius"] is not None for record in records),
        }
    if metric == "hrv_rmssd":
        values = []
        for record in records:
            if not window[0] <= timestamp(record["time"]) < window[1]:
                raise ValueError("An RMSSD measurement is outside the export window.")
            values.append(number(record["rmssd_milliseconds"]))
        return {"rmssd_milliseconds": numeric_summary(values)}

    stage_counts = {}
    session_minutes = []
    for record in records:
        start, end = interval(record, *window)
        session_minutes.append((end - start).total_seconds() / 60)
        for stage in record["stages"]:
            interval(stage, start, end)
            label = stage["stage"]
            if label not in STAGES:
                raise ValueError("Unknown sleep stage label.")
            stage_counts[label] = stage_counts.get(label, 0) + 1
    return {"session_length_minutes": numeric_summary(session_minutes), "stage_counts": stage_counts}


def summarize(path):
    with path.open("rb") as source:
        content = source.read(MAX_EXPORT_BYTES + 1)
    if len(content) > MAX_EXPORT_BYTES:
        raise ValueError("The export exceeds the RingCoach 1 MiB limit.")
    document = json.loads(content)
    if type(document["schema_version"]) is not int or document["schema_version"] != 1:
        raise ValueError("RingCoach export schema version 1 is required.")
    window = tuple(timestamp(document["window"][key]) for key in ("start", "end"))
    duration = window[1] - window[0]
    if not timedelta(0) < duration <= timedelta(days=7):
        raise ValueError("The export window must be positive and no longer than seven days.")
    exported = document["metrics"]
    if not isinstance(exported, dict) or exported.keys() - METRICS.keys():
        raise ValueError("Unexpected metric in the export.")

    facts = {}
    for metric in METRICS:
        if metric not in exported:
            facts[metric] = {"status": "not_selected"}
            continue
        entry = exported[metric]
        status, records = entry["status"], entry["records"]
        if status not in ("available", "empty", "no_permission", "unsupported", "error"):
            raise ValueError("Unexpected metric status in the export.")
        if not isinstance(records, list) or bool(records) != (status == "available"):
            raise ValueError("The record list disagrees with its availability status.")
        facts[metric] = {"status": status, "record_count": len(records)}
        if records:
            facts[metric].update(metric_facts(metric, records, window))
    if not any(metric["status"] == "available" for metric in facts.values()):
        raise ValueError("The export does not contain any available records.")
    return {"window_days": round(duration.total_seconds() / 86400, 3), "metrics": facts}


def codex_environment(api_key):
    # Keep credentials and endpoint overrides from unrelated tools out of this process.
    allowed = {"path", "pathext", "systemroot", "windir", "comspec", "programfiles",
               "programdata", "userprofile", "appdata", "localappdata", "tmp", "temp",
               "lang", "lc_all", "home"}
    env = {key: value for key, value in os.environ.items() if key.lower() in allowed}
    env["CODEX_API_KEY"] = api_key
    return env


def read_codex_result(events):
    allowed_items = {"reasoning", "agent_message"}
    messages = []
    completed = False
    for line in events.splitlines():
        event = json.loads(line)
        kind = event["type"]
        if kind in {"item.started", "item.updated", "item.completed"}:
            item = event["item"]
            if item["type"] not in allowed_items:
                raise ValueError("Codex attempted an unexpected action.")
            if kind == "item.completed" and item["type"] == "agent_message":
                messages.append(item["text"])
        if kind == "turn.completed":
            completed = True
    if not completed or not messages:
        raise ValueError("Codex did not complete the analysis.")
    return validate_answer(messages[-1])


def validate_answer(content):
    answer = json.loads(content)
    if not isinstance(answer, dict) or answer.keys() != set(RESPONSE_FIELDS):
        raise ValueError("The model returned an unexpected response shape.")
    for field, limit in zip(RESPONSE_FIELDS, (3, 2)):
        lines = answer[field]
        if not isinstance(lines, list) or not 1 <= len(lines) <= limit or any(
            not isinstance(line, str) or not line.strip() or "\n" in line or "\r" in line for line in lines
        ):
            raise ValueError("The model returned an invalid summary section.")
    return answer


def codex_failure(result):
    # Return fixed messages. Codex diagnostics can include a credential or request data.
    diagnostics = (result.stderr + "\n" + result.stdout).lower()
    categories = (
        (r"could not find home directory|failed to resolve codex_home",
         "Codex could not find the Windows user profile."),
        (r"windows sandbox setup|sandbox setup failed|sandbox.*bootstrap",
         "Codex could not initialize the Windows sandbox."),
        (r"unexpected argument|unrecognized (?:option|feature)|unknown feature",
         "The installed Codex CLI rejected a command option."),
        (r"invalid_api_key|incorrect api key|\b401\s+unauthorized|status(?: code)?[: ]+401\b",
         "OpenAI rejected the API credentials (401)."),
        (r"insufficient_quota|quota exceeded|exceeded your current quota|billing_hard_limit|"
         r"credit_balance_exhausted|organization_usage_limit_exceeded|"
         r"(?:organization|project)_spend_limit_exceeded",
         "OpenAI API quota exceeded. Check the API organization's credits and spend limits."),
        (r"model_not_found|does not have access to (?:the )?model|model .* (?:does not exist|is not supported)",
         "The requested model is unavailable to the OpenAI API project."),
        (r"\b429\s+too many requests|rate_limit_exceeded|status(?: code)?[: ]+429\b",
         "OpenAI rate-limited the request (429)."),
        (r"\b403\s+forbidden|status(?: code)?[: ]+403\b",
         "OpenAI denied the request (403). Check the API key's project and permissions."),
        (r"certificate verify|dns error|failed to connect|connection refused|error sending request|connection timed out",
         "Codex could not complete the network connection to OpenAI."),
    )
    for pattern, message in categories:
        if re.search(pattern, diagnostics):
            return f"Codex exited with status {result.returncode}. {message}"
    return f"Codex exited with status {result.returncode}. No recognized error category."


def codex_diagnostic(result, api_key):
    messages = []
    for line in result.stdout.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if not isinstance(event, dict):
            continue
        if event.get("type") == "turn.failed" and isinstance(event.get("error"), dict):
            messages.append(event["error"].get("message"))
        elif event.get("type") == "error":
            messages.append(event.get("message"))
    messages = [message for message in messages if isinstance(message, str) and message.strip()]
    if not messages:
        messages = [line for line in result.stderr.splitlines()
                    if re.search(r"\b(error|failed|failure|panic)\b", line, re.IGNORECASE)]
    if not messages:
        return "No error message was returned by Codex."
    diagnostic = "\n".join(dict.fromkeys(messages[-4:])).replace(api_key, "[redacted]")
    diagnostic = re.sub(r"(?i)\b(?:sk-[a-z0-9_-]+|bearer[ \t]+[^\s,\"']+)", "[redacted]", diagnostic)
    return diagnostic[:2000]


def analyze(facts, cli, model, api_key, *, diagnose=False):
    with tempfile.TemporaryDirectory(prefix="ringcoach-analysis-") as work:
        schema = Path(work, "response-schema.json")
        schema.write_text(json.dumps(SCHEMA), encoding="utf-8")
        options = {"cwd": work, "env": codex_environment(api_key), "capture_output": True,
                   "encoding": "utf-8", "errors": "replace", "timeout": 180}
        version = subprocess.run([cli, "--version"], **options)
        if version.returncode or not re.fullmatch(r"codex-cli [\w.\-+]+", version.stdout.strip()):
            raise ValueError("Could not verify the Codex CLI version.")
        result = subprocess.run([
            cli, "--ask-for-approval", "never", "exec", "--ignore-user-config", "--ephemeral",
            "--skip-git-repo-check", "--sandbox", "read-only", "--model", model,
            "-c", "web_search='disabled'", "-c", "model_reasoning_effort='low'",
            "--disable", "shell_tool", "--disable", "unified_exec", "--disable", "plugins",
            "--disable", "multi_agent", "--disable", "multi_agent_v2", "--disable", "skill_search",
            "--json", "--output-schema", str(schema), "-",
        ], input=PROMPT + "\n" + json.dumps(facts, ensure_ascii=False), **options)
        if result.returncode:
            message = codex_failure(result)
            if diagnose:
                message += "\n" + codex_diagnostic(result, api_key)
            raise ValueError(message)
        return version.stdout.strip(), read_codex_result(result.stdout)


def analyze_qwen(facts, model, api_key):
    host = "token-plan.maas.qianwenaiapi.com" if api_key.startswith("sk-sp-") else "maas.qianwenaiapi.com"
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": PROMPT},
            {"role": "user", "content": json.dumps(facts, ensure_ascii=False)},
        ],
        "enable_thinking": False,
        "max_tokens": 512,
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "ringcoach_report", "strict": True, "schema": SCHEMA},
        },
    }
    request = Request(
        f"https://{host}/compatible-mode/v1/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    request.add_unredirected_header("Authorization", f"Bearer {api_key}")
    try:
        with urlopen(request, timeout=45) as response:
            result = json.loads(response.read(65537))
    except HTTPError as error:
        raise ValueError(f"Qwen rejected the request (HTTP {error.code}).") from None
    except (URLError, TimeoutError):
        raise ValueError("Could not connect to the Qianwen API.") from None

    try:
        choice = result["choices"][0]
        content = choice["message"]["content"]
    except (KeyError, IndexError, TypeError):
        raise ValueError("Qwen returned an unexpected API response.") from None
    if choice.get("finish_reason") != "stop":
        raise ValueError("Qwen did not finish generating the report.")
    return validate_answer(content)


def print_report(version, model, facts, answer):
    print(f"{version} | {model}")
    for heading, lines in (
        ("观察", answer["observations"]),
        ("数据缺口", [f"{METRICS[key]}：{GAP_LABELS[metric['status']]}"
                      for key, metric in facts["metrics"].items() if metric["status"] in GAP_LABELS]),
        ("局限", answer["limitations"]),
    ):
        print(f"\n{heading}")
        for line in lines or ["所选指标均有记录。"]:
            print(f"- {line}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", type=Path, help="Path to the reviewed RingCoach JSON file.")
    parser.add_argument("--facts-only", action="store_true", help="Show the derived facts locally without contacting a provider.")
    parser.add_argument("--diagnose", action="store_true", help="Show redacted CLI error details using the bundled synthetic fixture.")
    parser.add_argument("--provider", choices=("codex", "qwen"), default="codex", help="Analysis provider.")
    parser.add_argument("--model", help="Override the provider's default model.")
    parser.add_argument("--codex", default="codex", help="Codex executable name or absolute path.")
    args = parser.parse_args()
    try:
        fixture = Path(__file__).resolve().parents[1] / "app/src/test/resources/synthetic-telemetry.json"
        if args.diagnose and (args.provider != "codex" or args.export.resolve() != fixture):
            raise ValueError("Use --diagnose with Codex and the bundled synthetic telemetry fixture.")
        facts = summarize(args.export)
        if args.facts_only:
            print(json.dumps(facts, ensure_ascii=False, indent=2))
            return 0
        model = args.model or ("qwen3.8-flash" if args.provider == "qwen" else "gpt-5.4-mini")
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]*", model):
            raise ValueError("The model name contains unsupported characters.")
        if args.provider == "qwen":
            api_key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
            if not api_key:
                raise ValueError("Set DASHSCOPE_API_KEY in this shell before running Qwen analysis.")
            print_report("Qianwen API", model, facts, analyze_qwen(facts, model, api_key))
            return 0
        api_key = os.environ.get("CODEX_API_KEY") or os.environ.get("OPENAI_API_KEY")
        if not api_key or not api_key.strip():
            raise ValueError("Set CODEX_API_KEY or OPENAI_API_KEY in this shell before running the analysis.")
        cli = shutil.which(args.codex)
        if cli is None:
            raise ValueError("Codex CLI was not found. Install it or pass its executable path with --codex.")
        version, answer = analyze(facts, os.path.abspath(cli), model, api_key, diagnose=args.diagnose)
        print_report(version, model, facts, answer)
        return 0
    except (KeyError, TypeError, OverflowError, json.JSONDecodeError):
        print("Invalid RingCoach data or malformed model output.", file=sys.stderr)
    except (OSError, subprocess.TimeoutExpired):
        print("Could not read the export or complete the analysis.", file=sys.stderr)
    except ValueError as error:
        print(error, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
