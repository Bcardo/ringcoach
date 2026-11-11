import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import analyze_telemetry as telemetry


FIXTURE = Path(__file__).resolve().parents[1] / "app/src/test/resources/synthetic-telemetry.json"


class AnalyzeTelemetryTest(unittest.TestCase):
    def test_synthetic_facts_keep_units_and_missingness(self):
        facts = telemetry.summarize(FIXTURE)
        metrics = facts["metrics"]
        self.assertEqual(facts["window_days"], 7)
        self.assertEqual(metrics["sleep"]["stage_counts"], {"deep": 1})
        self.assertEqual(metrics["sleep"]["session_length_minutes"]["mean"], 60)
        self.assertEqual(metrics["heart_rate"]["beats_per_minute"]["mean"], 62)
        self.assertEqual(metrics["hrv_rmssd"], {"status": "empty", "record_count": 0})
        self.assertEqual(metrics["skin_temperature"]["delta_celsius"]["min"], -0.4)
        self.assertEqual(metrics["skin_temperature"]["records_with_baseline"], 0)

    def test_rejects_a_sample_outside_the_export_window(self):
        document = json.loads(FIXTURE.read_text(encoding="utf-8"))
        document["metrics"]["heart_rate"]["records"][0]["samples"][0]["time"] = "2025-01-15T00:00:00Z"
        with tempfile.TemporaryDirectory() as work:
            export = Path(work, "export.json")
            export.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "outside its record interval"):
                telemetry.summarize(export)

    def test_point_in_time_heart_rate_records_are_valid(self):
        record = json.loads(FIXTURE.read_text(encoding="utf-8"))["metrics"]["heart_rate"]["records"][0]
        record["start"] = record["end"] = record["samples"][0]["time"]
        window = telemetry.timestamp("2025-01-01T00:00:00Z"), telemetry.timestamp("2025-01-08T00:00:00Z")
        facts = telemetry.metric_facts("heart_rate", [record], window)
        self.assertEqual(facts["beats_per_minute"]["sample_count"], 1)
        self.assertEqual(facts["beats_per_minute"]["mean"], 62)

    def test_codex_invocation_receives_only_derived_facts_and_process_key(self):
        answer = {"observations": ["心率样本为每分钟 62 次。"], "limitations": ["样本数量有限。"]}
        events = "\n".join(json.dumps(event) for event in (
            {"type": "item.completed", "item": {"type": "agent_message", "text": json.dumps(answer)}},
            {"type": "turn.completed"},
        ))
        facts = telemetry.summarize(FIXTURE)
        with patch.dict("os.environ", {"OPENAI_BASE_URL": "https://example.invalid", "AWS_SECRET_ACCESS_KEY": "test"}):
            with patch("subprocess.run", side_effect=[
                subprocess.CompletedProcess([], 0, "codex-cli 0.167.1\n", ""),
                subprocess.CompletedProcess([], 0, events, ""),
            ]) as run:
                version, response = telemetry.analyze(facts, "codex", "gpt-5.4-mini", "fake-test-key")
        self.assertEqual(version, "codex-cli 0.167.1")
        self.assertEqual(response, answer)
        invocation = run.call_args
        self.assertIn("--ephemeral", invocation.args[0])
        self.assertIn("shell_tool", invocation.args[0])
        self.assertNotEqual(Path(invocation.kwargs["cwd"]), FIXTURE.parent)
        self.assertNotIn("2025-01-02", invocation.kwargs["input"])
        self.assertNotIn("source_device_type", invocation.kwargs["input"])
        self.assertNotIn("fake-test-key", invocation.kwargs["input"])
        self.assertEqual(invocation.kwargs["env"]["CODEX_API_KEY"], "fake-test-key")
        self.assertNotIn("OPENAI_BASE_URL", invocation.kwargs["env"])
        self.assertNotIn("AWS_SECRET_ACCESS_KEY", invocation.kwargs["env"])

    def test_unexpected_tool_activity_is_rejected(self):
        event = {"type": "item.completed", "item": {"type": "command_execution"}}
        with self.assertRaisesRegex(ValueError, "unexpected action"):
            telemetry.read_codex_result(json.dumps(event))

    def test_codex_failure_reports_category_without_raw_diagnostics(self):
        cases = [
            ("", '{"type":"turn.failed","error":{"message":"401 Unauthorized, key: SECRET"}}',
             "credentials"),
            ("API response: insufficient_quota. Request: PRIVATE", "", "quota"),
            ("", '{"type":"error","message":"Quota exceeded. Check your plan and billing details."}',
             "quota exceeded"),
            ("429 Too Many Requests: project_spend_limit_exceeded", "", "quota exceeded"),
            ("", '{"type":"error","message":"model_not_found PRIVATE"}', "model"),
            ("Unexpected output: SECRET", "", "No recognized"),
        ]
        for stderr, stdout, category in cases:
            with self.subTest(category=category):
                result = subprocess.CompletedProcess([], 1, stdout, stderr)
                message = telemetry.codex_failure(result)
                self.assertIn(category, message)
                self.assertNotIn("SECRET", message)
                self.assertNotIn("PRIVATE", message)

    def test_diagnostic_redacts_credentials_and_prefers_failure_events(self):
        event = {"type": "turn.failed", "error": {
            "message": "Request failed for TEST_KEY and Bearer OTHER_TOKEN and sk-test_123."
        }}
        result = subprocess.CompletedProcess([], 1, json.dumps(event), "error: unrelated log")
        diagnostic = telemetry.codex_diagnostic(result, "TEST_KEY")
        self.assertEqual(diagnostic, "Request failed for [redacted] and [redacted] and [redacted].")

    def test_qwen_routes_key_and_sends_only_derived_facts(self):
        facts = telemetry.summarize(FIXTURE)
        report = {"observations": ["有心率样本。"], "limitations": ["缺少 HRV。"]}
        response = MagicMock()
        response.__enter__.return_value.read.return_value = json.dumps({"choices": [{
            "finish_reason": "stop", "message": {"content": json.dumps(report)},
        }]}).encode()
        for key, host in (
            ("sk-ws-test", "maas.qianwenaiapi.com"),
            ("sk-sp-test", "token-plan.maas.qianwenaiapi.com"),
        ):
            with self.subTest(host=host), patch("analyze_telemetry.urlopen", return_value=response) as send:
                self.assertEqual(telemetry.analyze_qwen(facts, "qwen3.8-flash", key), report)
                request = send.call_args.args[0]
                self.assertEqual(request.full_url, f"https://{host}/compatible-mode/v1/chat/completions")
                self.assertEqual(request.unredirected_hdrs["Authorization"], f"Bearer {key}")
                payload = json.loads(request.data)
                self.assertEqual(payload["model"], "qwen3.8-flash")
                self.assertEqual(json.loads(payload["messages"][1]["content"]), facts)
                self.assertTrue(payload["response_format"]["json_schema"]["strict"])
                self.assertNotIn(key.encode(), request.data)
                self.assertNotIn(b"2025-01-02", request.data)


if __name__ == "__main__":
    unittest.main()
