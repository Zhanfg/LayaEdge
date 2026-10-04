# Architecture

LayaEdge is split into four layers so backend-specific optimization does not leak into callers.

## 1. Decision API

Callers submit a `DecisionRequest` containing state, typed questions, and execution hints. The API returns typed answers plus runtime metadata.

The public API must not expose ONNX Runtime, NNAPI, QNN, or model-specific tensor names.

## 2. Runtime policy

`PowerPolicy` decides when a model may be loaded, how long it stays resident, whether requests may be coalesced, and which backend is eligible.

The default policy should favor short bursts and aggressive idle release over permanently pinning hundreds of megabytes of weights in RAM.

## 3. Inference backend

`InferenceBackend` is a narrow interface implemented by concrete engines. Initial work should start with a portable CPU backend, then compare Android NNAPI and Qualcomm-specific acceleration only after correctness is stable.

Backend selection is evidence-based: the fastest backend is not automatically the lowest-energy backend.

## 4. Model package

Model binaries are downloaded separately from the APK. A small manifest records format, quantization, tokenizer compatibility, hashes, and minimum runtime capabilities.

Large generated artifacts are not committed to this repository.

## Decision path

```
caller
  -> DecisionEngine
  -> PowerPolicy
  -> BackendSelector
  -> InferenceBackend
  -> typed result
```

## Non-goals for the first stage

- chat/text generation
- permanent foreground service
- always-on NPU reservation
- embedding model weights directly inside the APK
