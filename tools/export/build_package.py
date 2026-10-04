#!/usr/bin/env python3
import argparse
import gc
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

import onnx
import torch
from huggingface_hub import HfApi, snapshot_download
from laya.agent import Agent
from onnxruntime.quantization import QuantType, quantize_dynamic


def export_onnx(model_id: str, output: Path) -> None:
    print(f"Loading Laya checkpoint: {model_id}")
    agent = Agent(model_id, compile=False, device="cpu")
    agent.model.eval()

    batch, seq_len, num_markers = 2, 17, 3
    inputs = (
        torch.randint(0, 100, (batch, seq_len), dtype=torch.long),
        torch.ones((batch, seq_len), dtype=torch.long),
        torch.tensor([[1, 5, 9]] * batch, dtype=torch.long),
        torch.ones((batch, num_markers), dtype=torch.bool),
        torch.zeros(batch, dtype=torch.long),
    )
    dynamic_axes = {
        "input_ids": {0: "batch_size", 1: "seq_len"},
        "attention_mask": {0: "batch_size", 1: "seq_len"},
        "marker_pos": {0: "batch_size", 1: "num_markers"},
        "marker_mask": {0: "batch_size", 1: "num_markers"},
        "qtype": {0: "batch_size"},
        "logits": {0: "batch_size", 1: "num_markers"},
        "act_logits": {0: "batch_size"},
    }

    output.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        agent.model,
        inputs,
        str(output),
        export_params=True,
        opset_version=18,
        do_constant_folding=True,
        input_names=[
            "input_ids",
            "attention_mask",
            "marker_pos",
            "marker_mask",
            "qtype",
        ],
        output_names=["logits", "act_logits"],
        dynamic_axes=dynamic_axes,
    )
    del agent
    gc.collect()


def quantize_int8(source: Path, output: Path) -> None:
    print("Quantizing MatMul weights to per-tensor INT8")
    model = onnx.load(str(source))
    del model.graph.value_info[:]
    quantize_dynamic(
        model_input=model,
        model_output=str(output),
        op_types_to_quantize=["MatMul"],
        weight_type=QuantType.QInt8,
        per_channel=False,
    )


def normalize_tokenizer_config(path: Path) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    changed = False
    if data.get("tokenizer_class") in (None, "TokenizersBackend"):
        data["tokenizer_class"] = "PreTrainedTokenizerFast"
        data.pop("backend", None)
        data.pop("is_local", None)
        changed = True
    if isinstance(data.get("extra_special_tokens"), list):
        data["extra_special_tokens"] = {
            f"extra_{index}": token
            for index, token in enumerate(data["extra_special_tokens"])
        }
        changed = True
    if changed:
        path.write_text(
            json.dumps(data, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )


def copy_onnx_with_external_data(source: Path, package_dir: Path) -> None:
    shutil.copy2(source, package_dir / "model.onnx")

    # torch.onnx may externalize large initializers. The location is stored inside
    # the ONNX graph, so preserve the sidecar filename next to the renamed graph.
    for sidecar in sorted(source.parent.glob(source.name + ".data*")):
        shutil.copy2(sidecar, package_dir / sidecar.name)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def prepare_package(
    package_dir: Path,
    model_path: Path,
    snapshot: Path,
    model_id: str,
    source_revision: str,
    variant: str,
    quantization: dict | None,
) -> Path:
    if package_dir.exists():
        shutil.rmtree(package_dir)
    package_dir.mkdir(parents=True)

    copy_onnx_with_external_data(model_path, package_dir)
    shutil.copy2(snapshot / "rl_agent_config.json", package_dir / "rl_agent_config.json")
    shutil.copytree(snapshot / "tokenizer", package_dir / "tokenizer")
    normalize_tokenizer_config(package_dir / "tokenizer" / "tokenizer_config.json")

    files = {}
    for path in sorted(package_dir.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(package_dir).as_posix()
        files[relative] = {
            "size_bytes": path.stat().st_size,
            "sha256": sha256(path),
        }

    manifest = {
        "schema_version": 1,
        "model_id": model_id,
        "source_revision": source_revision,
        "variant": variant,
        "format": "onnx",
        "onnx_opset": 18,
        "quantization": quantization,
        "files": files,
    }
    (package_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    archive = package_dir.with_suffix(".zip")
    if archive.exists():
        archive.unlink()
    with zipfile.ZipFile(
        archive,
        mode="w",
        compression=zipfile.ZIP_STORED,
        allowZip64=True,
    ) as bundle:
        for path in sorted(package_dir.rglob("*")):
            if path.is_file():
                bundle.write(path, path.relative_to(package_dir).as_posix())

    print(
        json.dumps(
            {
                "package": str(package_dir),
                "archive": str(archive),
                "archive_size": archive.stat().st_size,
                "variant": variant,
            },
            indent=2,
        )
    )
    return archive


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--model",
        default="convaiinnovations/laya-multilingual",
    )
    parser.add_argument("--out", type=Path, default=Path("dist"))
    parser.add_argument("--skip-int8", action="store_true")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    work = args.out / ".work"
    work.mkdir(parents=True, exist_ok=True)

    source_revision = HfApi().model_info(args.model).sha
    snapshot = Path(
        snapshot_download(
            args.model,
            revision=source_revision,
            allow_patterns=["rl_agent_config.json", "tokenizer/*"],
        )
    )

    fp32_graph = work / "laya.fp32.onnx"
    export_onnx(args.model, fp32_graph)
    prepare_package(
        args.out / "laya-multilingual-fp32",
        fp32_graph,
        snapshot,
        args.model,
        source_revision,
        "fp32",
        None,
    )

    if not args.skip_int8:
        int8_graph = work / "laya.int8.onnx"
        quantize_int8(fp32_graph, int8_graph)
        prepare_package(
            args.out / "laya-multilingual-int8",
            int8_graph,
            snapshot,
            args.model,
            source_revision,
            "int8-dynamic-per-tensor",
            {
                "weight_type": "qint8",
                "mode": "dynamic",
                "op_types": ["MatMul"],
                "per_channel": False,
                "accuracy_status": "experimental",
            },
        )


if __name__ == "__main__":
    main()
