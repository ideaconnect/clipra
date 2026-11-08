-- What the display got from the app in one EditorBenchmark iteration, for Android 10 and 11,
-- which report no frameOverrunMs (#242, #244). Run it on a Perfetto trace that Macrobenchmark
-- recorded (benchmark/build/outputs/connected_android_test_additional_output/):
--
--     trace_processor_shell -q scripts/perf/display-frames.sql TRACE.perfetto-trace
--
-- trace_processor_shell comes with Perfetto (https://perfetto.dev/docs/quickstart/trace-analysis).
--
-- Between the start of the app's first frame and the end of its last one it counts the vsyncs of
-- a 60 Hz display, the app's frames, and SurfaceFlinger's compositions (handleMessageRefresh,
-- the name on Android 10 and 11). SurfaceFlinger composes only when a layer has something new,
-- so while the timeline moves a composition per vsync is 60 frames a second. "held" counts the
-- times a composition came 1.5 vsyncs or more after the one before (a picture shown for two
-- vsyncs or more), and "missed" the vsyncs that showed no new picture that way. Gaps of 200 ms
-- or more are the journey waiting for the screen to settle, not frames missed, and are counted
-- apart ("waits").
--
-- The second result is each CPU's mean clock over the trace (from the cpufreq counters): on
-- phones such as the Galaxy S9 the frames' CPU time follows it, and it drops when the app has
-- less to do (#244).
WITH app AS (SELECT upid, pid FROM process WHERE name = 'tech.idct.clipra'),
main_thread AS (SELECT utid FROM thread JOIN app USING (upid) WHERE thread.tid = app.pid),
frames AS (
  SELECT s.ts, s.dur FROM slice s JOIN thread_track tt ON s.track_id = tt.id
  WHERE tt.utid IN (SELECT utid FROM main_thread) AND s.name LIKE 'Choreographer#doFrame%'
),
journey AS (SELECT MIN(ts) AS t0, MAX(ts + dur) AS t1 FROM frames),
sf AS (SELECT upid, pid FROM process WHERE name = '/system/bin/surfaceflinger'),
sf_main AS (SELECT utid FROM thread JOIN sf USING (upid) WHERE thread.tid = sf.pid),
compositions AS (
  SELECT s.ts, LEAD(s.ts) OVER (ORDER BY s.ts) AS next_ts FROM slice s
  JOIN thread_track tt ON s.track_id = tt.id
  WHERE tt.utid IN (SELECT utid FROM sf_main) AND s.name = 'handleMessageRefresh'
),
inside AS (
  SELECT c.ts, c.next_ts - c.ts AS gap FROM compositions c, journey j
  WHERE c.ts BETWEEN j.t0 AND j.t1 AND c.next_ts <= j.t1
)
SELECT
  ROUND((j.t1 - j.t0) / 1e9, 2) AS seconds,
  CAST(ROUND((j.t1 - j.t0) / 16.667e6) AS INT) AS vsyncs,
  (SELECT COUNT(*) FROM frames) AS app_frames,
  (SELECT COUNT(*) FROM inside) AS compositions,
  (SELECT COUNT(*) FROM inside WHERE gap >= 25e6 AND gap < 200e6) AS held,
  (SELECT IFNULL(CAST(SUM(ROUND(gap / 16.667e6) - 1) AS INT), 0) FROM inside
    WHERE gap >= 25e6 AND gap < 200e6) AS missed,
  (SELECT COUNT(*) FROM inside WHERE gap >= 200e6) AS waits
FROM journey j;

WITH f AS (
  SELECT ct.cpu, c.ts, c.value, LEAD(c.ts) OVER (PARTITION BY ct.cpu ORDER BY c.ts) AS next_ts
  FROM counter c JOIN cpu_counter_track ct ON c.track_id = ct.id
  WHERE ct.name = 'cpufreq'
)
SELECT cpu, CAST(ROUND(SUM(value * (next_ts - ts)) / SUM(next_ts - ts) / 1000) AS INT) AS mean_mhz
FROM f WHERE next_ts IS NOT NULL GROUP BY cpu ORDER BY cpu;
