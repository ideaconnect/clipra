"""Tests of budgets.py's timeline verdict (#242), its reading of the engine tests' log lines and
whole runs of main() with the device and Gradle faked (#267): python -m pytest scripts/perf"""

import json
import sys

import pytest

import budgets


def editor_json(sdk, cpu, overrun=None, runs=None):
    """A benchmarkData.json as Macrobenchmark writes it for EditorBenchmark.scrubAndPlay."""
    sampled = {'frameDurationCpuMs': {'P50': 7.8, 'P90': cpu, 'P95': 21.7, 'P99': 31.3}}
    if runs is not None:
        sampled['frameDurationCpuMs']['runs'] = runs
    if overrun is not None:
        sampled['frameOverrunMs'] = {'P50': -9.0, 'P90': overrun, 'P95': 2.0, 'P99': 9.0}
    return {
        'context': {'build': {'model': 'SM-G960F', 'version': {'codename': 'REL', 'sdk': sdk}}},
        'benchmarks': [{
            'name': 'scrubAndPlay',
            'className': 'tech.idct.clipra.benchmark.EditorBenchmark',
            'metrics': {'frameCount': {'minimum': 4.0, 'maximum': 6.0, 'median': 5.0,
                                       'runs': [4.0, 6.0]}},
            'sampledMetrics': sampled,
        }],
    }


def read_editor(tmp_path, data):
    """benchmark_numbers() on an output directory that holds [data], as a phone run leaves it."""
    device = tmp_path / 'benchmark/connected/SM-G960F - 10'
    device.mkdir(parents=True)
    (device / 'tech.idct.clipra.benchmark-benchmarkData.json').write_text(
        json.dumps(data), encoding='utf-8')
    return budgets.benchmark_numbers(tmp_path)['EditorBenchmark.scrubAndPlay']


# Ten frames over two iterations: two of them at 16 ms of CPU or more, one of those exactly 16.
RUNS = [[45.9, 7.7, 10.8, 5.7, 9.1], [16.0, 5.2, 8.3, 6.7, 15.9]]


def test_android_10_without_overrun_is_judged_by_the_cpu_time(tmp_path):
    editor = read_editor(tmp_path, editor_json(sdk=29, cpu=15.4, runs=RUNS))

    measured, result = budgets.timeline_cells(editor, api=29)

    assert 'frameOverrunMs' not in editor
    assert result == 'holds'
    assert measured == ('CPU P90 15.4 ms (no overrun metric before Android 12); '
                        '2 of 10 frames (20.0%) at 16 ms CPU or more')


def test_android_10_over_16_ms_of_cpu_misses_the_budget(tmp_path):
    editor = read_editor(tmp_path, editor_json(sdk=29, cpu=18.4, runs=RUNS))

    measured, result = budgets.timeline_cells(editor, api=29)

    assert result == 'MISSED'
    assert measured is not None
    assert measured.startswith('CPU P90 18.4 ms (no overrun metric before Android 12)')


def test_android_11_is_judged_the_same_way():
    editor = editor_json(sdk=30, cpu=16.0)['benchmarks'][0]['sampledMetrics']

    assert budgets.timeline_cells(editor, api=30) == (
        'CPU P90 16.0 ms (no overrun metric before Android 12)', 'MISSED')


@pytest.mark.parametrize('overrun, cpu, result', [
    (-2.0, 12.0, 'holds'),
    (0.0, 15.9, 'holds'),
    (0.5, 12.0, 'MISSED'),
    (-2.0, 16.0, 'MISSED'),
])
def test_from_android_12_both_numbers_judge(tmp_path, overrun, cpu, result):
    editor = read_editor(tmp_path, editor_json(sdk=31, cpu=cpu, overrun=overrun))

    assert budgets.timeline_cells(editor, api=31) == (
        f'overrun P90 {overrun:.1f} ms, CPU P90 {cpu:.1f} ms', result)


def test_missing_overrun_on_android_12_is_not_blamed_on_the_version():
    editor = editor_json(sdk=33, cpu=12.0)['benchmarks'][0]['sampledMetrics']

    assert budgets.timeline_cells(editor, api=33) == (
        'CPU P90 12.0 ms (no overrun metric in this run)', 'holds')


def test_overrun_without_cpu_time_is_shown_but_not_judged():
    editor = {'frameOverrunMs': {'P90': 3.0}}

    assert budgets.timeline_cells(editor, api=34) == ('overrun P90 3.0 ms', 'not measured')


def test_no_editor_benchmark_is_not_measured():
    assert budgets.timeline_cells(None, api=29) == (None, 'not measured')
    assert budgets.timeline_cells({}, api=29) == (None, 'not measured')


def test_slow_frames_count_every_iteration():
    assert budgets.slow_frames({'frameDurationCpuMs': {'P90': 15.4, 'runs': RUNS}}) == (2, 10)
    assert budgets.slow_frames({'frameDurationCpuMs': {'P90': 15.4, 'runs': [[1.0, 2.0]]}}) == (
        0, 2)


def test_slow_frames_need_the_per_frame_times():
    assert budgets.slow_frames({'frameDurationCpuMs': {'P90': 15.4}}) is None
    assert budgets.slow_frames({'frameDurationCpuMs': {'P90': 15.4, 'runs': []}}) is None
    assert budgets.slow_frames(None) is None


# BudgetTest's lines as logcat shows them: the three layers' preview and export, then the
# reversed picture in picture (#229) and the person cutout (#49), whose numbers are not theirs.
BUDGET_LOG = '\n'.join(f'11-08 20:31:07.512  9417  9433 I Budget  : {line}' for line in [
    'export 1080x1920 30 fps, 42 s, 3 layers (gray.mp4): 61.2 s, 0.69x real time',
    'preview 540x960, 3 layers, 2 effects, run 1 of 3: 29.1 fps shown, 0.99x real time; '
    '29.4 canvases drawn a second, a draw 4.6 ms on average, 5.8 ms P90',
    'preview 540x960, 3 layers, 2 effects, run 2 of 3: 27.9 fps shown, 0.97x real time; '
    '28.0 canvases drawn a second, a draw 4.9 ms on average, 6.1 ms P90',
    'preview 540x960, 3 layers, 2 effects, run 3 of 3: 28.6 fps shown, 1.00x real time; '
    '28.8 canvases drawn a second, a draw 4.7 ms on average, 5.9 ms P90',
    'preview 540x960, 3 layers, 2 effects, lowest of 3 runs: 27.9 fps shown, 0.97x real time',
    'reversed picture in picture, preview forwards: 12.0 fps (0.40x real time)',
    'cutout masks of 1920x1080 media, 4.0 s long: made in 9.6 s, 2.4x its length',
    'cutout off, run 1 of 3: 12.5 fps (0.42x real time); '
    '12.6 canvases drawn a second, a draw 25.0 ms on average, 33.1 ms P90',
    'cutout with the layer below, run 1 of 3: 11.0 fps (0.37x real time); '
    '11.2 canvases drawn a second, a draw 31.0 ms on average, 40.2 ms P90',
    'cutout with the background blurred, lowest of 3 runs: 10.5 fps (0.35x real time)',
])


def test_only_the_three_layers_lines_count_for_the_preview_and_export_budgets():
    numbers = budgets.engine_numbers(BUDGET_LOG)

    assert numbers['preview'] == (27.9, 0.97, [29.1, 27.9, 28.6])
    assert numbers['export'] == (61.2, 0.69)


# A Galaxy S9 on Android 10 and an Android 10 emulator, as device_row() describes them.
PHONE = {'device': 'samsung SM-G960F', 'soc': 'Samsung Exynos 9810', 'ram': '3.5 GB',
         'android': '10 (API 29)', 'api': 29, 'emulator': False}
EMULATOR = {**PHONE, 'device': 'Google Android SDK built for x86_64', 'soc': 'ranchu',
            'emulator': True}

# What EditorBenchmark logs under the BudgetProject tag (benchmark/.../Journeys.kt).
PROJECT_LOG = {
    'Budget': '11-08 20:12:40.118  8812  8830 I BudgetProject: '
              'Measuring the project named Budget',
    'text card': '11-08 20:12:40.118  8812  8830 W BudgetProject: '
                 'No project named Budget on Home: measuring a new text card project',
}

STARTUP_JSON = {'benchmarks': [{
    'name': 'coldStartWithProfile',
    'className': 'tech.idct.clipra.benchmark.StartupBenchmark',
    'metrics': {'timeToInitialDisplayMs': {'minimum': 790.2, 'maximum': 845.0, 'median': 812.4}},
}]}

TIMELINE_ROW = ('| Timeline scroll and scrub, 6 layers | '
                'P90 overrun at or below 0 ms, CPU under 16 ms |')


def run_main(monkeypatch, tmp_path, row, project, *flags):
    """main() with [flags] on the device [row], with Gradle and adb faked: EditorBenchmark
    measures [project] at 12.5 ms of CPU per frame (P90), StartupBenchmark gives STARTUP_JSON and
    the engine tests log BUDGET_LOG. Returns the report and the Gradle calls (task, classes,
    extra arguments)."""
    output = tmp_path / 'benchmark-output'
    calls = []

    def gradle(serial, task, classes, extra):
        calls.append((task, classes, extra))
        # Every benchmark run leaves its JSON in the same place, as the connected task does.
        data = {budgets.EDITOR_CLASS: editor_json(sdk=row['api'], cpu=12.5),
                budgets.STARTUP_CLASS: STARTUP_JSON}.get(classes[0])
        if data:
            output.mkdir(parents=True, exist_ok=True)
            (output / 'tech.idct.clipra.benchmark-benchmarkData.json').write_text(
                json.dumps(data), encoding='utf-8')
        return True

    def adb(serial, *args, check=True):
        if args[:2] == ('logcat', '-d'):
            if f'{budgets.BUDGET_PROJECT_TAG}:I' in args:
                return PROJECT_LOG[project]
            return BUDGET_LOG
        if args == ('shell', 'getprop', 'ro.build.version.sdk'):
            return f"{row['api']}\n"
        return ''

    monkeypatch.setattr(budgets, 'ROOT', tmp_path)
    monkeypatch.setattr(budgets, 'BENCHMARK_OUTPUT', output)
    monkeypatch.setattr(budgets, 'pick_device', lambda serial: 'R58M1234ABC')
    monkeypatch.setattr(budgets, 'device_row', lambda serial: row)
    monkeypatch.setattr(budgets, 'thermal_status', lambda serial: 1)
    monkeypatch.setattr(budgets, 'adb', adb)
    monkeypatch.setattr(budgets, 'gradle', gradle)
    monkeypatch.setattr(sys, 'argv', ['budgets.py', *flags])

    budgets.main()

    reports = list((tmp_path / 'build/perf').glob('*.md'))
    assert len(reports) == 1
    return reports[0].read_text(encoding='utf-8'), calls


def test_a_phone_run_writes_the_report_on_the_budget_project(monkeypatch, tmp_path, capsys):
    report, calls = run_main(monkeypatch, tmp_path, PHONE, 'Budget')

    timeline = f'{TIMELINE_ROW} CPU P90 12.5 ms (no overrun metric before Android 12) | holds |'
    assert timeline in report
    assert '| Cold start to Home | under 1.5 s | 812 ms median | holds |' in report
    assert ('| Preview, 1080p, 3 layers, 2 effects | at least 27 fps, 0.95x real time | '
            '27.9 fps, 0.97x (lowest of 3 runs: 29.1, 27.9, 28.6 fps) | holds |') in report
    assert ('| Export 1080p30, 42 s, 3 layers | under 120 s | 61.2 s (0.69x real time) | '
            'holds |') in report
    assert '- EditorBenchmark: on the Budget project\n' in report
    assert '- StartupBenchmark reinstalled the app' in report
    # The editor first, and on a phone it has to find the Budget project (#241).
    assert [classes for _, classes, _ in calls] == [
        [budgets.EDITOR_CLASS], [budgets.STARTUP_CLASS], budgets.ENGINE_CLASSES]
    assert budgets.REQUIRE_BUDGET in calls[0][2]
    printed = capsys.readouterr().out
    assert timeline in printed
    assert f"Report: {tmp_path / 'build/perf'}" in printed


def test_a_measured_text_card_is_not_judged_as_the_budget_project(monkeypatch, tmp_path):
    report, calls = run_main(monkeypatch, tmp_path, EMULATOR, 'text card',
                             '--emulator', '--skip-startup', '--skip-engine')

    assert (f'{TIMELINE_ROW} CPU P90 12.5 ms (no overrun metric before Android 12) | '
            'not the Budget project |') in report
    assert '- EditorBenchmark: on a new text card project, not the Budget project' in report
    assert '**Emulator: these numbers do not count.**' in report
    assert [classes for _, classes, _ in calls] == [[budgets.EDITOR_CLASS]]
    assert budgets.REQUIRE_BUDGET not in calls[0][2]


def test_a_run_without_the_benchmarks_still_writes_the_report(monkeypatch, tmp_path):
    report, calls = run_main(monkeypatch, tmp_path, PHONE, None,
                             '--skip-benchmark', '--skip-engine')

    assert f'{TIMELINE_ROW} - | not measured |' in report
    assert 'EditorBenchmark' not in report
    assert calls == []
