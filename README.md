# LayaEdge

Low-power on-device Laya decision runtime for Android.

LayaEdge is an Android-focused runtime for using Laya as a small local decision layer. The goal is not to turn Laya into a chat model; it is to make typed decisions cheaply enough that apps, agents, automation, and local services can call it without keeping a large generative model hot.

## Goals

- keep the model off the hot path when no decision is needed
- minimize memory residency, wakeups, and thermal load
- support pluggable CPU / NNAPI / QNN-style backends
- keep model files outside the APK
- expose one stable typed decision API to callers
- benchmark latency, memory, energy, and sustained thermal behavior

The first model target is the multilingual Laya checkpoint. Model conversion and quantization artifacts are intentionally not committed to Git.

## Layout

- `runtime/` — platform-independent decision API and runtime policy
- `model/` — model manifests and compatibility metadata
- `benchmark/` — latency, memory, power, and thermal measurements
- `tools/` — export, quantization, pruning, and packaging utilities
- `docs/` — architecture and power policy notes

Development happens on `dev`.
