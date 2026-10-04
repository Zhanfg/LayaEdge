# Power policy

Energy is a first-class benchmark, not a side effect of latency tuning.

## Default behavior

1. Do not initialize the inference backend until the first decision request.
2. Keep one warm instance only while requests are arriving.
3. Release heavyweight model state after an idle grace period.
4. Coalesce compatible requests arriving in a short window when batching saves work.
5. Cap concurrency to avoid turning a small model into a sustained thermal workload.
6. Prefer the backend with the best measured energy-per-decision under the current workload, not simply the lowest single-shot latency.

## States

- `COLD` — no model resident
- `WARM` — model resident and ready
- `BUSY` — inference in progress
- `COOLDOWN` — short grace window before unload

## Metrics

Every backend experiment should record at least:

- cold-start latency
- warm latency (p50 / p95)
- peak RSS
- steady resident memory
- energy per decision where measurable
- sustained throughput
- device temperature before/after
- throttling behavior

The first reliable target is correctness + repeatable measurement. Backend-specific tuning comes after that baseline.
