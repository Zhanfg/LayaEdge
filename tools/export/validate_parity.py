#!/usr/bin/env python3
import argparse
import gc
import json
import math
from pathlib import Path

from laya import Agent
from laya.onnx_agent import ONNXAgent


CASES = [
    {
        "name": "zh-routing",
        "state": "用户说订单被重复扣款，希望尽快退款。",
        "questions": {
            "route": {
                "type": "choice",
                "instructions": "选择最合适的处理队列",
                "criteria": {
                    "billing": "账单、扣款或退款",
                    "technical": "技术故障",
                    "sales": "售前咨询",
                },
            }
        },
    },
    {
        "name": "zh-noul",
        "state": "应用在五分钟内连续崩溃了三次。",
        "questions": {
            "attention": {
                "type": "noul",
                "instructions": "现在是否需要进行诊断处理？",
                "criteria": {
                    "false": "不需要立即处理",
                    "true": "需要尽快处理",
                },
            }
        },
    },
    {
        "name": "en-choice",
        "state": "The service is healthy but a background cache refresh failed once.",
        "questions": {
            "action": {
                "type": "choice",
                "instructions": "Choose the next action.",
                "criteria": {
                    "ignore": "No action is required",
                    "retry": "Retry the background work",
                    "escalate": "Escalate to an operator",
                },
            }
        },
    },
    {
        "name": "score",
        "state": "The operation timed out twice but succeeded on the third retry.",
        "questions": {
            "severity": {
                "type": "score",
                "instructions": "Rate operational severity.",
                "criteria": ["low", "moderate", "high", "critical"],
            }
        },
    },
]


def answer_signature(answer):
    kind = answer["type"]
    if kind == "choice":
        return answer["choice"]
    if kind == "score":
        return float(answer["score"])
    return float(answer["noul"])


def run_agent(agent):
    return [
        agent.system_one(case["state"], case["questions"])
        for case in CASES
    ]


def compare(reference, candidate, strict):
    records = []
    hard_failures = []
    top_agree = 0

    for case, ref_result, got_result in zip(CASES, reference, candidate):
        case_records = {}
        for qid in case["questions"]:
            ref = ref_result["answers"][qid]
            got = got_result["answers"][qid]
            kind = ref["type"]
            same_type = kind == got["type"]

            if kind == "choice":
                agreement = same_type and ref["choice"] == got["choice"]
                delta = max(
                    abs(float(ref["probabilities"][key]) - float(got["probabilities"][key]))
                    for key in ref["probabilities"]
                )
            elif kind == "score":
                agreement = same_type and abs(float(ref["score"]) - float(got["score"])) <= (
                    0.03 if strict else 0.35
                )
                delta = abs(float(ref["score"]) - float(got["score"]))
            else:
                ref_p = float(ref["noul"])
                got_p = float(got["noul"])
                agreement = same_type and ((ref_p >= 0.5) == (got_p >= 0.5))
                delta = abs(ref_p - got_p)

            finite = math.isfinite(delta)
            if agreement:
                top_agree += 1
            if not same_type or not finite:
                hard_failures.append(f"{case['name']}:{qid}: invalid output")
            if strict and (not agreement or delta > 0.03):
                hard_failures.append(
                    f"{case['name']}:{qid}: strict parity failed, delta={delta:.6f}"
                )

            case_records[qid] = {
                "type": kind,
                "reference": answer_signature(ref),
                "candidate": answer_signature(got),
                "agreement": agreement,
                "delta": delta,
            }

        records.append({"case": case["name"], "questions": case_records})

    total = sum(len(case["questions"]) for case in CASES)
    return {
        "strict": strict,
        "agreement": top_agree / total if total else 1.0,
        "records": records,
        "failures": hard_failures,
    }


def validate(model_id, graph, strict):
    reference_agent = Agent(model_id, compile=False, device="cpu")
    reference = run_agent(reference_agent)
    del reference_agent
    gc.collect()

    onnx_agent = ONNXAgent(model_id, onnx_path=str(graph))
    candidate = run_agent(onnx_agent)
    del onnx_agent
    gc.collect()

    return compare(reference, candidate, strict)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="convaiinnovations/laya-multilingual")
    parser.add_argument("--fp32", type=Path, required=True)
    parser.add_argument("--int8", type=Path)
    parser.add_argument("--int4", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    report = {
        "model": args.model,
        "fp32": validate(args.model, args.fp32, strict=True),
    }
    if args.int8:
        report["int8"] = validate(args.model, args.int8, strict=False)
    if args.int4:
        report["int4"] = validate(args.model, args.int4, strict=False)

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, indent=2, ensure_ascii=False))

    failures = report["fp32"]["failures"]
    if failures:
        raise SystemExit("FP32 ONNX parity validation failed: " + "; ".join(failures))
    if "int8" in report and report["int8"]["failures"]:
        raise SystemExit(
            "INT8 produced structurally invalid output: "
            + "; ".join(report["int8"]["failures"])
        )
    if "int4" in report and report["int4"]["failures"]:
        raise SystemExit(
            "INT4 produced structurally invalid output: "
            + "; ".join(report["int4"]["failures"])
        )


if __name__ == "__main__":
    main()
