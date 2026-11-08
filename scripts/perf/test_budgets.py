"""Tests of budgets.py's timeline verdict (#242) and its reading of the engine tests' log lines:
python -m pytest scripts/perf"""

import json

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


# BudgetTest's lines as logcat shows them: the three layers' preview and export, the reversed
# layer's export and picture in picture (#229) and the person cutout (#49). The reversed export
# comes first, so the three layers' export would be read from it if its line matched.
def logcat(lines):
    return '\n'.join(f'11-08 20:31:07.512  9417  9433 I Budget  : {line}' for line in lines)


BUDGET_LOG = logcat([
    'reversed middle layer, export 1080x1920 30 fps, 42 s: 90.4 s (0.46x real time)',
    'export 1080x1920 30 fps, 42 s, 3 layers (gray.mp4): 61.2 s, 0.69x real time',
    'preview 540x960, 3 layers, 2 effects, run 1 of 3: 29.1 fps shown, 0.99x real time; '
    '29.4 canvases drawn a second, a draw 4.6 ms on average, 5.8 ms P90',
    'preview 540x960, 3 layers, 2 effects, run 2 of 3: 27.9 fps shown, 0.97x real time; '
    '28.0 canvases drawn a second, a draw 4.9 ms on average, 6.1 ms P90',
    'preview 540x960, 3 layers, 2 effects, run 3 of 3: 28.6 fps shown, 1.00x real time; '
    '28.8 canvases drawn a second, a draw 4.7 ms on average, 5.9 ms P90',
    'preview 540x960, 3 layers, 2 effects, lowest of 3 runs: 27.9 fps shown, 0.97x real time',
    'reversed picture in picture, preview forwards: 28.7 fps (0.99x real time)',
    'reversed picture in picture, preview read back to front: 12.9 fps (0.43x real time)',
    'reversed picture in picture, copies made in 3.4 s, 0.8 MB',
    'reversed picture in picture, preview from its copies, run 1 of 3: 28.4 fps (1.00x real time)',
    'reversed picture in picture, preview from its copies, run 2 of 3: 26.3 fps (0.99x real time)',
    'reversed picture in picture, preview from its copies, run 3 of 3: 27.8 fps (0.97x real time)',
    'reversed picture in picture, preview from its copies, lowest of 3 runs: '
    '26.3 fps (0.97x real time)',
    'cutout masks of 1920x1080 media, 4.0 s long: made in 9.6 s, 2.4x its length',
    'cutout off, run 1 of 3: 27.7 fps (1.00x real time); '
    '27.9 canvases drawn a second, a draw 6.2 ms on average, 8.1 ms P90',
    'cutout off, lowest of 3 runs: 25.6 fps (1.00x real time)',
    'cutout with the layer below, run 1 of 3: 28.4 fps (1.00x real time); '
    '28.6 canvases drawn a second, a draw 9.0 ms on average, 11.2 ms P90',
    'cutout with the layer below, lowest of 3 runs: 28.1 fps (1.00x real time)',
    'cutout with the background blurred, run 1 of 3: 27.6 fps (1.00x real time); '
    '27.8 canvases drawn a second, a draw 10.4 ms on average, 12.9 ms P90',
    'cutout with the background blurred, lowest of 3 runs: 26.9 fps (0.98x real time)',
])

PREVIEW_TARGET = 'at least 27 fps, 0.95x real time'
REVERSED_PREVIEW = 'Preview, reversed picture in picture (#229)'
REVERSED_EXPORT = 'Export 1080p30, 42 s, 3 layers, one reversed (#229)'
LAYER_BELOW = 'Cutout, 1080p clip over the layer below (#49)'
BLURRED = 'Cutout, 1080p clip, background blurred (#49)'


def rows_of(logs):
    return budgets.reversed_and_cutout_rows(budgets.engine_numbers(logs))


def test_the_reversed_layer_and_the_cutout_are_read_from_their_own_lines():
    numbers = budgets.engine_numbers(BUDGET_LOG)

    # The lowest of the runs from its copies, not the lowest line, the forwards or the back to
    # front one.
    assert numbers['reversed preview'] == (26.3, 0.97, [28.4, 26.3, 27.8])
    assert numbers['reversed forwards'] == (28.7, 0.99)
    assert numbers['reversed export'] == (90.4, 0.46)
    assert numbers['cutout'] == {
        'off': (25.6, 1.0, 3),
        'with the layer below': (28.1, 1.0, 3),
        'with the background blurred': (26.9, 0.98, 3),
    }


def test_their_rows_are_judged_as_budget_test_judges_them():
    assert rows_of(BUDGET_LOG) == [
        (REVERSED_PREVIEW, PREVIEW_TARGET,
         '26.3 fps, 0.97x (lowest of 3 runs: 28.4, 26.3, 27.8 fps); forwards 28.7 fps', 'MISSED'),
        (REVERSED_EXPORT, 'under 120 s', '90.4 s (0.46x real time)', 'holds'),
        (LAYER_BELOW, PREVIEW_TARGET, '28.1 fps, 1.00x (lowest of 3 runs); uncut 25.6 fps',
         'holds'),
        (BLURRED, PREVIEW_TARGET, '26.9 fps, 0.98x (lowest of 3 runs); uncut 25.6 fps', 'MISSED'),
    ]


@pytest.mark.parametrize('fps, pace, result', [
    (27.0, 0.95, 'holds'),
    (26.9, 1.00, 'MISSED'),
    (29.0, 0.94, 'MISSED'),
])
def test_the_previews_need_27_fps_and_095x_real_time(fps, pace, result):
    logs = logcat([
        f'reversed picture in picture, preview from its copies, run 1 of 3: '
        f'{fps} fps ({pace:.2f}x real time)',
        f'cutout with the layer below, lowest of 3 runs: {fps} fps ({pace:.2f}x real time)',
        f'cutout with the background blurred, lowest of 3 runs: {fps} fps ({pace:.2f}x real time)',
    ])

    assert [result for name, _, _, result in rows_of(logs) if name != REVERSED_EXPORT] == [
        result, result, result]


@pytest.mark.parametrize('seconds, result', [(119.9, 'holds'), (120.0, 'MISSED')])
def test_the_reversed_export_needs_under_120_seconds(seconds, result):
    logs = logcat([f'reversed middle layer, export 1080x1920 30 fps, 42 s: {seconds} s '
                   f'({42 / seconds:.2f}x real time)'])

    assert rows_of(logs)[1] == (REVERSED_EXPORT, 'under 120 s',
                                f'{seconds:.1f} s ({42 / seconds:.2f}x real time)', result)


def test_a_build_before_270_logged_one_preview_from_the_copies():
    logs = logcat([
        'reversed picture in picture, preview from its copies: 27.8 fps (1,00x real time)',
        'cutout with the layer below, lowest of 3 runs: 28.1 fps (1.00x real time)',
    ])

    rows = rows_of(logs)

    # No forwards line and no uncut lowest line: the measurement goes without them.
    assert rows[0] == (REVERSED_PREVIEW, PREVIEW_TARGET, '27.8 fps, 1.00x', 'holds')
    assert rows[2] == (LAYER_BELOW, PREVIEW_TARGET, '28.1 fps, 1.00x (lowest of 3 runs)', 'holds')


def test_without_their_lines_they_are_not_measured():
    # The forwards line alone, as a run that stopped before the copies were made leaves it.
    logs = logcat(['reversed picture in picture, preview forwards: 28.7 fps (0.99x real time)'])

    assert rows_of(logs) == [
        (REVERSED_PREVIEW, PREVIEW_TARGET, None, 'not measured'),
        (REVERSED_EXPORT, 'under 120 s', None, 'not measured'),
        (LAYER_BELOW, PREVIEW_TARGET, None, 'not measured'),
        (BLURRED, PREVIEW_TARGET, None, 'not measured'),
    ]
    assert rows_of('') == rows_of(logs)


def test_only_the_three_layers_lines_count_for_the_preview_and_export_budgets():
    numbers = budgets.engine_numbers(BUDGET_LOG)

    assert numbers['preview'] == (27.9, 0.97, [29.1, 27.9, 28.6])
    assert numbers['export'] == (61.2, 0.69)
