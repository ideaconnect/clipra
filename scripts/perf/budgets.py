"""Measures the performance budgets on one connected phone (#77) and writes a report for #77.

    python scripts/perf/budgets.py [--clip clip.mp4] [--serial SERIAL]

Runs what docs/release/performance.md describes, one device at a time:
- EditorBenchmark, then StartupBenchmark (the app's benchmark build): timeline frames, cold
  start. On a phone the editor must find the Budget project (budgetProject=required), and the
  report names the project it measured (#241);
- BudgetTest, TranscriberTest and VoiceCleanupTest (core:engine): preview, export, Whisper base
  and DeepFilterNet 3, with the peak memory of the test process and the thermal status before
  and after.

The report goes to build/perf/<model>-<date>.md: the device row, the commit, and every budget
with its measurement and whether it holds, ready to paste into #77. --clip pushes a minute of
the phone's own 1080p30 video for the export and preview budgets (else the bundled clips loop).
On an emulator, add --emulator: the numbers then only show that the measurements run.
Android 10 and 11 have no frame overrun metric, so there the timeline budget is judged by the
CPU time per frame alone, and the row says so.

The app stays installed after the run, so the welcome and the Budget project stay too. On
Android 13 and older, StartupBenchmark reinstalls the app (Macrobenchmark resets compilation
that way there unless the phone is rooted), which deletes the Budget project: --skip-startup
measures the editor again without it.

Needs adb on the PATH (or ANDROID_HOME set), JAVA_HOME as for the build, and the phone prepared
as the runbook says (charged, at room temperature, the Budget project made once).
Its tests: python -m pytest scripts/perf
"""

import argparse
import datetime
import functools
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
ENGINE_TEST_PACKAGE = 'tech.idct.clipra.core.engine.test'
ENGINE_CLASSES = [
    'tech.idct.clipra.core.engine.budget.BudgetTest',
    'tech.idct.clipra.core.engine.media.TranscriberTest',
    'tech.idct.clipra.core.engine.media.VoiceCleanupTest',
]
BENCHMARK_TASK = ':benchmark:connectedBenchmarkAndroidTest'
# The editor first: on Android 13 and older StartupBenchmark reinstalls the app, and the Budget
# project goes with its data (#241).
EDITOR_CLASS = 'tech.idct.clipra.benchmark.EditorBenchmark'
STARTUP_CLASS = 'tech.idct.clipra.benchmark.StartupBenchmark'
# The connected test task uninstalls the app at the end unless told otherwise (#241).
KEEP_APKS = '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
# EditorBenchmark fails without a Budget project instead of measuring a text card (#241).
REQUIRE_BUDGET = '-Pandroid.testInstrumentationRunnerArguments.budgetProject=required'
BUDGET_PROJECT_TAG = 'BudgetProject'
BENCHMARK_OUTPUT = ROOT / 'benchmark/build/outputs/connected_android_test_additional_output'
# The timeline budget: no frame over its deadline at P90, and under 16 ms of CPU per frame.
FRAME_CPU_MS = 16
# Macrobenchmark reports frameOverrunMs only from Android 12, where SurfaceFlinger keeps each
# frame's deadline; on Android 10 and 11 FrameTimingMetric gives frameDurationCpuMs alone (#242).
OVERRUN_API = 31


# Looked up on first use, so the tests can import this file on a machine without adb.
@functools.cache
def adb_path():
    found = shutil.which('adb')
    if found:
        return found
    home = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if home:
        candidate = Path(home) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
        if candidate.exists():
            return str(candidate)
    sys.exit('adb not found: put platform-tools on the PATH or set ANDROID_HOME')


def adb(serial, *args, check=True):
    result = subprocess.run([adb_path(), '-s', serial, *args], capture_output=True, text=True,
                            encoding='utf-8', errors='replace')
    if check and result.returncode != 0:
        sys.exit(f'adb {" ".join(args)} failed: {result.stderr.strip()}')
    return result.stdout


def pick_device(serial):
    lines = subprocess.run([adb_path(), 'devices'], capture_output=True,
                           text=True).stdout.splitlines()
    devices = [line.split()[0] for line in lines[1:] if line.strip().endswith('device')]
    if serial:
        if serial not in devices:
            sys.exit(f'{serial} is not connected: {devices}')
        return serial
    if len(devices) != 1:
        sys.exit(f'Connect exactly one device, or pass --serial (connected: {devices})')
    return devices[0]


def prop(serial, name):
    return adb(serial, 'shell', 'getprop', name).strip()


def device_row(serial):
    """What the runbook's device table asks for."""
    meminfo = adb(serial, 'shell', 'cat', '/proc/meminfo')
    total_kb = int(re.search(r'MemTotal:\s+(\d+)', meminfo).group(1))
    soc = ' '.join(p for p in (prop(serial, 'ro.soc.manufacturer'), prop(serial, 'ro.soc.model')) if p)
    api = prop(serial, 'ro.build.version.sdk')
    return {
        'device': f"{prop(serial, 'ro.product.manufacturer')} {prop(serial, 'ro.product.model')}",
        'soc': soc or prop(serial, 'ro.board.platform') or 'unknown',
        'ram': f'{total_kb / 1024 / 1024:.1f} GB',
        'android': f"{prop(serial, 'ro.build.version.release')} (API {api})",
        'api': int(api) if api.isdigit() else None,
        'emulator': prop(serial, 'ro.kernel.qemu') == '1' or prop(serial, 'ro.boot.qemu') == '1',
    }


def thermal_status(serial):
    out = adb(serial, 'shell', 'dumpsys', 'thermalservice', check=False)
    found = re.search(r'Thermal Status:\s*(\d+)', out)
    return int(found.group(1)) if found else None


def gradle(serial, task, classes, extra):
    env = dict(os.environ, ANDROID_SERIAL=serial)
    args = [GRADLE, task, '--console=plain',
            f'-Pandroid.testInstrumentationRunnerArguments.class={",".join(classes)}', *extra]
    print('$', ' '.join(args[1:]), flush=True)
    result = subprocess.run(args, cwd=ROOT, env=env)
    return result.returncode == 0


class PeakMemory(threading.Thread):
    """Samples the total PSS of [package] every few seconds while the tests run."""

    def __init__(self, serial, package):
        super().__init__(daemon=True)
        self.serial, self.package = serial, package
        self.peak_kb = 0
        self.stopped = threading.Event()

    def run(self):
        while not self.stopped.wait(5):
            out = adb(self.serial, 'shell', 'dumpsys', 'meminfo', self.package, check=False)
            found = re.search(r'TOTAL PSS:\s*(\d+)', out) or re.search(r'TOTAL\s+(\d+)', out)
            if found:
                self.peak_kb = max(self.peak_kb, int(found.group(1)))


def benchmark_numbers(root=None):
    """Median start and the editor's frame percentiles from the benchmark JSON files."""
    numbers = {}
    for path in Path(root or BENCHMARK_OUTPUT).rglob('*benchmarkData.json'):
        data = json.loads(path.read_text(encoding='utf-8'))
        for bench in data.get('benchmarks', []):
            name = f"{bench.get('className', '').rsplit('.', 1)[-1]}.{bench.get('name')}"
            metrics = {**bench.get('metrics', {}), **bench.get('sampledMetrics', {})}
            numbers[name] = metrics
    return numbers


def verdict(value, ok):
    if value is None:
        return 'not measured'
    return 'holds' if ok(value) else 'MISSED'


def slow_frames(editor):
    """(frames with FRAME_CPU_MS or more of CPU, all frames) over every iteration, from the
    per-frame times Macrobenchmark keeps in frameDurationCpuMs' runs on every Android version;
    None when the JSON has no such list."""
    runs = (editor or {}).get('frameDurationCpuMs', {}).get('runs') or []
    frames = [ms for run in runs for ms in (run if isinstance(run, list) else [run])]
    if not frames:
        return None
    return sum(1 for ms in frames if ms >= FRAME_CPU_MS), len(frames)


def timeline_cells(editor, api):
    """The Measured and Result cells of the timeline budget from EditorBenchmark's metrics.

    Both P90s judge the row when they are there. Without frameOverrunMs (Android 10 and 11,
    #242) the CPU P90 alone does, and the row says so. Whatever was measured is shown.
    """
    editor = editor or {}
    overrun = editor.get('frameOverrunMs', {}).get('P90')
    cpu = editor.get('frameDurationCpuMs', {}).get('P90')
    parts = []
    if overrun is not None:
        parts.append(f'overrun P90 {overrun:.1f} ms')
    if cpu is not None:
        parts.append(f'CPU P90 {cpu:.1f} ms')
    measured = ', '.join(parts)
    if cpu is not None and overrun is None:
        why = 'before Android 12' if api is not None and api < OVERRUN_API else 'in this run'
        measured += f' (no overrun metric {why})'
    slow = slow_frames(editor)
    if slow:
        share = (f'{slow[0]} of {slow[1]} frames ({slow[0] / slow[1]:.1%}) '
                 f'at {FRAME_CPU_MS} ms CPU or more')
        measured = f'{measured}; {share}' if measured else share
    if cpu is None:
        return measured or None, 'not measured'
    if overrun is None:
        return measured, verdict(cpu, lambda v: v < FRAME_CPU_MS)
    return measured, verdict((overrun, cpu), lambda v: v[0] <= 0 and v[1] < FRAME_CPU_MS)


# The engine tests log their numbers with a decimal point (Locale.ROOT, #198). Builds before
# that used the phone's locale, so a decimal comma ("67,2 s") is read as well.
NUMBER = r'(\d+(?:[.,]\d+)?)'


def number(text):
    return float(text.replace(',', '.'))


def engine_numbers(logs):
    """Preview (fps, pace and every run's fps), export (seconds, pace), Whisper seconds and the
    slowest DeepFilterNet seconds from the engine tests' log lines; None for each one not found.

    The preview test measures several runs and logs each (#243); the budget counts the lowest
    fps and the lowest pace among them, so a lucky run can't pass it. Builds before that logged
    one run."""
    runs = [(number(fps), number(pace)) for fps, pace in
            re.findall(rf'preview (?:(?!lowest)[^:\n])*: {NUMBER} fps shown, {NUMBER}x real time',
                       logs)]
    export = re.search(rf'export .*?: {NUMBER} s, {NUMBER}x real time', logs)
    whisper = re.search(rf'Base: \d+ words in {NUMBER} s', logs)
    cleanup = [number(s) for s in
               re.findall(rf'DeepFilterNet \S+: .*?, {NUMBER} s for 10 s', logs)]
    return {
        'preview': (min(f for f, _ in runs), min(p for _, p in runs),
                    [f for f, _ in runs]) if runs else None,
        'export': (number(export.group(1)), number(export.group(2))) if export else None,
        'whisper': number(whisper.group(1)) if whisper else None,
        'cleanup': max(cleanup) if cleanup else None,
    }


def preview_measured(preview):
    """The preview row's measurement: the lowest run, and every run when there are several."""
    fps, pace, runs = preview
    text = f'{fps:.1f} fps, {pace:.2f}x'
    if len(runs) > 1:
        text += f' (lowest of {len(runs)} runs: {", ".join(f"{f:.1f}" for f in runs)} fps)'
    return text


def editor_project(log):
    """Which project EditorBenchmark measured, from its BudgetProject log lines (#241): 'Budget',
    'text card', 'missing' (none, and it was required) or None when nothing was logged."""
    if 'required' in log:
        return 'missing'
    if 'text card' in log:
        return 'text card'
    if 'Measuring the project named Budget' in log:
        return 'Budget'
    return None


PROJECT_WORDS = {
    'Budget': 'on the Budget project',
    'text card': 'on a new text card project, not the Budget project',
    'missing': 'no Budget project on Home',
    None: 'project not logged',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--serial', help='the device, when more than one is connected')
    parser.add_argument('--clip', help="a minute of the phone's own 1080p30 video")
    parser.add_argument('--emulator', action='store_true', help='allow an emulator')
    parser.add_argument('--skip-benchmark', action='store_true')
    parser.add_argument('--skip-startup', action='store_true',
                        help='of the benchmarks only the editor, which keeps the Budget project '
                             'on Android 13 and older')
    parser.add_argument('--skip-engine', action='store_true')
    options = parser.parse_args()

    serial = pick_device(options.serial)
    row = device_row(serial)
    if row['emulator'] and not options.emulator:
        sys.exit('This is an emulator: its numbers do not count (pass --emulator to run anyway)')
    commit = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], cwd=ROOT,
                            capture_output=True, text=True).stdout.strip()
    print(f"{row['device']} · {row['soc']} · {row['ram']} · Android {row['android']} · {commit}")

    if options.clip:
        adb(serial, 'push', options.clip, '/data/local/tmp/clipra-budget.mp4')
    clip = 'the phone\'s own clip' if options.clip else 'the bundled test clips'
    thermal_before = thermal_status(serial)
    extra = ['-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors='
             'EMULATOR'] if row['emulator'] else []

    benches, ran_benchmark, project, reinstalled = {}, False, None, False
    if not options.skip_benchmark:
        shutil.rmtree(BENCHMARK_OUTPUT, ignore_errors=True)
        adb(serial, 'logcat', '-c', check=False)
        # A phone has to have the Budget project; an emulator may measure a text card.
        required = [] if row['emulator'] else [REQUIRE_BUDGET]
        ran_benchmark = gradle(serial, BENCHMARK_TASK, [EDITOR_CLASS],
                               [KEEP_APKS, *required, *extra])
        benches.update(benchmark_numbers())
        project = editor_project(adb(serial, 'logcat', '-d', '-s', f'{BUDGET_PROJECT_TAG}:I',
                                     check=False))
        print(f'EditorBenchmark: {PROJECT_WORDS[project]}', flush=True)
        if not options.skip_startup:
            # Macrobenchmark resets the compilation state by reinstalling the app there (#241).
            reinstalled = int(prop(serial, 'ro.build.version.sdk') or 0) < 34
            if reinstalled:
                print('Android 13 or older: StartupBenchmark reinstalls the app, which deletes '
                      'the Budget project unless the phone is rooted', flush=True)
            ran_startup = gradle(serial, BENCHMARK_TASK, [STARTUP_CLASS], [KEEP_APKS, *extra])
            ran_benchmark = ran_benchmark and ran_startup
            benches.update(benchmark_numbers())

    logs, peak = '', None
    if not options.skip_engine:
        adb(serial, 'logcat', '-c', check=False)
        memory = PeakMemory(serial, ENGINE_TEST_PACKAGE)
        memory.start()
        # Without budgets=check every test logs its numbers, met or not; the report judges them.
        gradle(serial, ':core:engine:connectedDebugAndroidTest', ENGINE_CLASSES, [])
        memory.stopped.set()
        peak = memory.peak_kb or None
        logs = adb(serial, 'logcat', '-d', '-s', 'Budget:I', 'Transcriber:I', 'VoiceCleanup:I',
                   check=False)
    thermal_after = thermal_status(serial)

    # The budget counts with the baseline profile, as the app is installed from Play.
    start = benches.get('StartupBenchmark.coldStartWithProfile')
    start_ms = start['timeToInitialDisplayMs'].get('median') if start else None
    editor = benches.get('EditorBenchmark.scrubAndPlay')
    timeline_measured, timeline_result = timeline_cells(editor, row['api'])
    engine = engine_numbers(logs)
    preview, export = engine['preview'], engine['export']
    whisper, cleanup_s = engine['whisper'], engine['cleanup']
    timeline = None if cpu is None else (overrun, cpu)
    # frameOverrunMs needs Android 12 (API 31): before that the CPU time alone decides.
    timeline_result = verdict(timeline, lambda v: (v[0] is None or v[0] <= 0) and v[1] < 16)
    if timeline is not None and project == 'text card':
        timeline_result = 'not the Budget project'
    if cpu is None:
        timeline_measured = None
    elif overrun is None:
        timeline_measured = f'CPU P90 {cpu:.1f} ms (no overrun before Android 12)'
    else:
        timeline_measured = f'overrun P90 {overrun:.1f} ms, CPU P90 {cpu:.1f} ms'

    rows = [
        ('Cold start to Home', 'under 1.5 s',
         f'{start_ms:.0f} ms median' if start_ms is not None else None,
         verdict(start_ms, lambda v: v < 1500)),
        ('Timeline scroll and scrub, 6 layers', 'P90 overrun at or below 0 ms, CPU under 16 ms',
         timeline_measured, timeline_result),
        ('Preview, 1080p, 3 layers, 2 effects', 'at least 27 fps, 0.95x real time',
         preview_measured(preview) if preview else None,
         verdict(preview, lambda v: v[0] >= 27 and v[1] >= 0.95)),
        ('Export 1080p30, 42 s, 3 layers', 'under 120 s',
         f'{export[0]:.1f} s ({export[1]:.2f}x real time)' if export else None,
         verdict(export, lambda v: v[0] < 120)),
        ('Whisper base, 42 s of speech', 'under 20 s',
         f'{whisper:.1f} s' if whisper is not None else None,
         verdict(whisper, lambda v: v < 20)),
        ('DeepFilterNet 3, 10 s of sound', 'faster than real time (under 10 s)',
         f'{cleanup_s:.1f} s, slowest noise' if cleanup_s is not None else None,
         verdict(cleanup_s, lambda v: v < 10)),
    ]

    today = datetime.date.today().isoformat()
    table = [f'| {name} | {target} | {measured or "-"} | {result} |'
             for name, target, measured, result in rows]
    lines = [
        f"## {row['device']}, {today}",
        '',
        '| Tier | Device | SoC | RAM | Android | Results |',
        '|---|---|---|---|---|---|',
        f"| (fill in) | {row['device']} | {row['soc']} | {row['ram']} | {row['android']} | below |",
        '',
        f'Commit `{commit}`; export and preview on {clip}.'
        + (' **Emulator: these numbers do not count.**' if row['emulator'] else ''),
        '',
        '| Budget | Target | Measured | Result |',
        '|---|---|---|---|',
        *table,
        '',
        f"- Peak memory of the engine tests (TOTAL PSS): "
        f"{f'{peak / 1024:.0f} MB' if peak else 'not measured'}",
        f'- Thermal status before and after: {thermal_before} -> {thermal_after}'
        + (' (3 or more is a finding)' if (thermal_after or 0) >= 3 else ''),
    ]
    if not options.skip_benchmark and not ran_benchmark:
        lines.append('- The benchmark run failed; see the Gradle output.')
    if not options.skip_benchmark:
        lines.append(f'- EditorBenchmark: {PROJECT_WORDS[project]}'
                     + (' (make it as docs/release/performance.md, section 3, says)'
                        if project == 'missing' else ''))
    if reinstalled:
        lines.append('- StartupBenchmark reinstalled the app (Android 13 and older, unless '
                     'rooted), so the Budget project is gone: make it again before the editor '
                     'is measured next (runs with --skip-startup keep it)')
    if logs.strip():
        lines += ['', '<details><summary>Log lines</summary>', '', '```', logs.strip(), '```',
                  '', '</details>']

    out = ROOT / 'build/perf'
    out.mkdir(parents=True, exist_ok=True)
    name = re.sub(r'[^A-Za-z0-9]+', '-', row['device']).strip('-').lower()
    report = out / f'{name}-{today}.md'
    report.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print('\n'.join(table))
    print(f'Report: {report}')


if __name__ == '__main__':
    main()
