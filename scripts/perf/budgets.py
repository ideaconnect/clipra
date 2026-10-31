"""Measures the performance budgets on one connected phone (#77) and writes a report for #77.

    python scripts/perf/budgets.py [--clip clip.mp4] [--serial SERIAL]

Runs what docs/release/performance.md describes, one device at a time:
- StartupBenchmark and EditorBenchmark (the app's benchmark build): cold start, timeline frames;
- BudgetTest, TranscriberTest and VoiceCleanupTest (core:engine): preview, export, Whisper base
  and DeepFilterNet 3, with the peak memory of the test process and the thermal status before
  and after.

The report goes to build/perf/<model>-<date>.md: the device row, the commit, and every budget
with its measurement and whether it holds, ready to paste into #77. --clip pushes a minute of
the phone's own 1080p30 video for the export and preview budgets (else the bundled clips loop).
On an emulator, add --emulator: the numbers then only show that the measurements run.

Needs adb on the PATH (or ANDROID_HOME set), JAVA_HOME as for the build, and the phone prepared
as the runbook says (charged, at room temperature, the Budget project made once).
"""

import argparse
import datetime
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
BENCHMARK_CLASSES = [
    'tech.idct.clipra.benchmark.StartupBenchmark',
    'tech.idct.clipra.benchmark.EditorBenchmark',
]
BENCHMARK_OUTPUT = ROOT / 'benchmark/build/outputs/connected_android_test_additional_output'


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


ADB = adb_path()


def adb(serial, *args, check=True):
    result = subprocess.run([ADB, '-s', serial, *args], capture_output=True, text=True,
                            encoding='utf-8', errors='replace')
    if check and result.returncode != 0:
        sys.exit(f'adb {" ".join(args)} failed: {result.stderr.strip()}')
    return result.stdout


def pick_device(serial):
    lines = subprocess.run([ADB, 'devices'], capture_output=True, text=True).stdout.splitlines()
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
    return {
        'device': f"{prop(serial, 'ro.product.manufacturer')} {prop(serial, 'ro.product.model')}",
        'soc': soc or prop(serial, 'ro.board.platform') or 'unknown',
        'ram': f'{total_kb / 1024 / 1024:.1f} GB',
        'android': f"{prop(serial, 'ro.build.version.release')} (API {prop(serial, 'ro.build.version.sdk')})",
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


def benchmark_numbers():
    """Median start and the editor's frame percentiles from the benchmark JSON files."""
    numbers = {}
    for path in BENCHMARK_OUTPUT.rglob('*benchmarkData.json'):
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


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--serial', help='the device, when more than one is connected')
    parser.add_argument('--clip', help="a minute of the phone's own 1080p30 video")
    parser.add_argument('--emulator', action='store_true', help='allow an emulator')
    parser.add_argument('--skip-benchmark', action='store_true')
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

    benches, ran_benchmark = {}, False
    if not options.skip_benchmark:
        shutil.rmtree(BENCHMARK_OUTPUT, ignore_errors=True)
        ran_benchmark = gradle(serial, ':benchmark:connectedBenchmarkAndroidTest',
                               BENCHMARK_CLASSES, extra)
        benches = benchmark_numbers()

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
    overrun = editor.get('frameOverrunMs', {}).get('P90') if editor else None
    cpu = editor.get('frameDurationCpuMs', {}).get('P90') if editor else None
    preview = re.search(r'preview .*?: ([\d.]+) fps shown, ([\d.]+)x real time', logs)
    export = re.search(r'export .*?: ([\d.]+) s, ([\d.]+)x real time', logs)
    whisper = re.search(r'Base: \d+ words in ([\d.]+) s', logs)
    cleanup = [float(s) for s in re.findall(r'DeepFilterNet \S+: .*?, ([\d.]+) s for 10 s', logs)]
    cleanup_s = max(cleanup) if cleanup else None

    rows = [
        ('Cold start to Home', 'under 1.5 s',
         f'{start_ms:.0f} ms median' if start_ms is not None else None,
         verdict(start_ms, lambda v: v < 1500)),
        ('Timeline scroll and scrub, 6 layers', 'P90 overrun at or below 0 ms, CPU under 16 ms',
         f'overrun P90 {overrun:.1f} ms, CPU P90 {cpu:.1f} ms' if overrun is not None and
         cpu is not None else None,
         verdict(None if overrun is None or cpu is None else (overrun, cpu),
                 lambda v: v[0] <= 0 and v[1] < 16)),
        ('Preview, 1080p, 3 layers, 2 effects', 'at least 27 fps, 0.95x real time',
         f'{preview.group(1)} fps, {preview.group(2)}x' if preview else None,
         verdict(None if not preview else (float(preview.group(1)), float(preview.group(2))),
                 lambda v: v[0] >= 27 and v[1] >= 0.95)),
        ('Export 1080p30, 42 s, 3 layers', 'under 60 s',
         f'{export.group(1)} s ({export.group(2)}x real time)' if export else None,
         verdict(float(export.group(1)) if export else None, lambda v: v < 60)),
        ('Whisper base, 42 s of speech', 'under 20 s',
         f'{whisper.group(1)} s' if whisper else None,
         verdict(float(whisper.group(1)) if whisper else None, lambda v: v < 20)),
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
