# Benchmark plan

Performance claims in LayaEdge should be reproducible on real phones.

## Suites

### Latency
Cold and warm inference, p50/p95, with fixed input sets.

### Memory
Peak RSS, steady-state RSS, load/unload behavior, and repeated-call leakage.

### Energy
Compare energy per decision and energy over sustained request bursts.

### Thermal
Run long enough to observe throttling instead of reporting only short benchmark peaks.

## Output

Benchmark results should include:

- device/build
- model manifest hash
- runtime/backend version
- power mode
- input suite revision
- raw measurements
- summary statistics

Do not compare backend numbers collected under different thermal or power conditions as if they were equivalent.
