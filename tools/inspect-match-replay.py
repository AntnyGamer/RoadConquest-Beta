#!/usr/bin/env python3
"""Inspect synthetic emulator fixes through the production matcher; never ship probes."""
import argparse
import json
import os
from pathlib import Path
import subprocess


PROBE = r'''
package com.roadconquest.app.matching

import com.roadconquest.app.data.TrackPoint
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class RecordedDriveProbeTest {
    @Test fun inspectRecordedWindows() {
        val output = File(System.getenv("MATCH_PROBE_OUT"))
        val fixture = JSONArray(File(System.getenv("MATCH_PROBE_FIXTURE")).readText())
        val all = (0 until fixture.length()).map { index ->
            val p = fixture.getJSONObject(index)
            TrackPoint(p.getLong("id"), p.getDouble("latitude"), p.getDouble("longitude"),
                p.getDouble("accuracy_m").toFloat(), p.getDouble("speed_mps").toFloat(),
                p.getDouble("bearing_deg").toFloat(), p.getLong("timestamp_ms"),
                p.getInt("matched") != 0)
        }.associateBy { it.id }
        val cases = JSONArray(File(output, "cases.json").readText())
        val results = JSONArray()
        for (index in 0 until cases.length()) {
            val scenario = cases.getJSONObject(index)
            val label = scenario.getString("label")
            System.setProperty("match.probe.case", label)
            val ids = scenario.getJSONArray("ids")
            val points = (0 until ids.length()).map { all.getValue(ids.getLong(it)) }
            val summary = JSONObject().put("label", label).put("ids", ids)
            try {
                val result = OsrmMatcher().match(points)
                summary.put("null_result", result == null)
                if (result != null) {
                    summary.put("resolved", JSONObject(result.matchedPointConfidences
                        .mapKeys { it.key.toString() }))
                    summary.put("roads", JSONArray(result.roads.map { road ->
                        JSONObject().put("name", road.name)
                            .put("coordinates", JSONArray(road.coordinatesJson))
                            .put("confidence", road.confidence)
                    }))
                }
            } catch (error: Exception) {
                summary.put("error", error.toString())
            }
            results.put(summary)
            File(output, "results.json").writeText(results.toString(2))
            println("MATCH_PROBE $summary")
            Thread.sleep(1_200L)
        }
        assertTrue("No live OSRM response was captured", output.listFiles().orEmpty()
            .any { it.name.startsWith("raw-") })
    }
}
'''


def cases_for(points):
    cases = []
    # The two unresolved turn islands and other pending fixes from the real APK replay.
    for label, start, end in [
        ("start", 1, 1), ("sparse", 55, 55), ("middle", 63, 63),
        ("turn13", 82, 83), ("approach15", 93, 93), ("exit15", 96, 96),
        ("turn17", 116, 117), ("finish", 120, 120),
    ]:
        for context, before, after in [("current", 2, 2), ("balanced", 4, 4),
                                       ("incoming", 8, 2)]:
            lo, hi = max(1, start - before), min(len(points), end + after)
            while hi - lo + 1 > 10:
                if context == "balanced" and hi - end > start - lo:
                    hi -= 1
                else:
                    lo += 1
            cases.append({"label": f"{label}-{context}", "ids": list(range(lo, hi + 1))})
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    fixture = root / "tools/fixtures/philadelphia-recorded-beta20.json"
    points = json.loads(fixture.read_text())
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "cases.json").write_text(json.dumps(cases_for(points), indent=2))
    matcher = root / "app/src/main/java/com/roadconquest/app/matching/OsrmMatcher.kt"
    test = root / "app/src/test/java/com/roadconquest/app/matching/RecordedDriveProbeTest.kt"
    original = matcher.read_text()
    if test.exists():
        raise RuntimeError("Refusing to replace an existing probe")
    patched = original.replace(
        "            parse(body, points)",
        '            java.io.File(System.getenv("MATCH_PROBE_OUT"), '
        '"raw-${System.getProperty(\"match.probe.case\")}.json").writeText(body)\n'
        "            parse(body, points)", 1)
    patched = patched.replace(
        "                            rejectedLeg = true",
        '                            java.io.File(System.getenv("MATCH_PROBE_OUT"), '
        '"rejected-${System.getProperty(\"match.probe.case\")}.txt").appendText('\
        '"${points[trace[l].input].id}->${points[trace[l + 1].input].id}: '
        '${_.message}\\n")\n                            rejectedLeg = true', 1)
    # Name the formerly ignored exception only inside this disposable checkout.
    patched = patched.replace("catch (_: IllegalArgumentException)",
                              "catch (probeError: IllegalArgumentException)", 1)
    patched = patched.replace("${_.message}", "${probeError.message}")
    if patched == original or "raw-${" not in patched or "rejected-${" not in patched:
        raise RuntimeError("Matcher probe injection no longer matches source")
    env = dict(os.environ, MATCH_PROBE_OUT=str(args.out.resolve()),
               MATCH_PROBE_FIXTURE=str(fixture))
    try:
        matcher.write_text(patched)
        test.write_text(PROBE)
        subprocess.run(["./gradlew", ":app:testDebugUnitTest", "--tests",
                        "com.roadconquest.app.matching.RecordedDriveProbeTest",
                        "--no-daemon", "--console=plain"], cwd=root, env=env, check=True)
    finally:
        matcher.write_text(original)
        test.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
