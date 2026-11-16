#!/usr/bin/env python3
"""RAG evaluation harness (standard library only).

Runs every golden question through POST /api/compliance/ask, then reads the audit record
(GET /api/audit/{id}) to get the ranked context chunks, and computes:
  * Hit@1 / Hit@3 / Hit@5 – expected source document among the top-k context chunks
  * MRR                    – mean reciprocal rank of the first chunk from the expected source
  * Page accuracy          – a citation of the expected source with the expected page
  * Answer check           – answer contains the expected keyword(s)
  * Latency p50 / p95 and token usage

To build the comparison table of the report, restart compliance-service with different settings
(e.g. RERANK_PROVIDER=none, COMPRESSION_MODE=none, rag.query-expander.enabled=false) and run with
another --label. Results are appended to eval/results/summary.md.

  python eval/run_eval.py --base http://localhost --label full
"""
import argparse
import json
import os
import statistics
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def call(base, path, token=None, body=None):
    req = urllib.request.Request(base + path, method="POST" if body is not None else "GET")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(req, data=data, timeout=300) as resp:
        return json.loads(resp.read().decode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost")
    ap.add_argument("--label", default="full")
    ap.add_argument("--mode", default="FULL", choices=["FULL", "FAST"])
    ap.add_argument("--password", default=os.environ.get("SEED_DEFAULT_PASSWORD", "EduPulse#2026"))
    args = ap.parse_args()

    golden = json.load(open(os.path.join(HERE, "golden-set.json"), encoding="utf-8"))
    token = call(args.base, "/api/auth/login", body={"email": golden["user"], "password": args.password})["accessToken"]
    auditor = call(args.base, "/api/auth/login", body={"email": "auditor@acme.test", "password": args.password})["accessToken"]

    rows, latencies, prompt_tokens = [], [], []
    for q in golden["questions"]:
        t0 = time.time()
        try:
            r = call(args.base, "/api/compliance/ask", token, {"question": q["question"], "mode": args.mode})
        except Exception as e:  # noqa: BLE001
            rows.append({"id": q["id"], "error": str(e)})
            continue
        latencies.append(r["latencyMs"])
        if r.get("usage", {}).get("promptTokens"):
            prompt_tokens.append(r["usage"]["promptTokens"])
        audit = call(args.base, "/api/audit/" + r["auditId"], auditor)
        chunks = audit.get("chunks", [])
        ranks = [c["rank"] for c in chunks if c.get("source") == q["expectedSource"]]
        first = min(ranks) if ranks else None
        page_ok = any(c.get("documentName") == q["expectedSource"] and c.get("page") == q["expectedPage"]
                      for c in r.get("citations", []))
        answer_ok = all(k.lower() in (r.get("answer") or "").lower() for k in q.get("mustContain", []))
        rows.append({"id": q["id"], "outcome": r["outcome"], "first_rank": first, "page_ok": page_ok,
                     "answer_ok": answer_ok, "latency": r["latencyMs"], "secs": round(time.time() - t0, 2)})
        print(f'{q["id"]} {r["outcome"]:<11} rank={first} page_ok={page_ok} answer_ok={answer_ok} {r["latencyMs"]} ms')

    ok = [r for r in rows if "error" not in r]
    n = max(1, len(ok))
    hit = lambda k: sum(1 for r in ok if r["first_rank"] and r["first_rank"] <= k) / n  # noqa: E731
    mrr = sum(1 / r["first_rank"] for r in ok if r["first_rank"]) / n
    summary = {
        "label": args.label, "mode": args.mode, "questions": len(rows), "errors": len(rows) - len(ok),
        "hit@1": round(hit(1), 3), "hit@3": round(hit(3), 3), "hit@5": round(hit(5), 3), "mrr": round(mrr, 3),
        "page_accuracy": round(sum(r["page_ok"] for r in ok) / n, 3),
        "answer_accuracy": round(sum(r["answer_ok"] for r in ok) / n, 3),
        "latency_p50_ms": int(statistics.median(latencies)) if latencies else None,
        "latency_p95_ms": int(sorted(latencies)[int(0.95 * (len(latencies) - 1))]) if latencies else None,
        "avg_prompt_tokens": int(statistics.mean(prompt_tokens)) if prompt_tokens else None,
    }
    print(json.dumps(summary, indent=2))

    out_dir = os.path.join(HERE, "results")
    os.makedirs(out_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    json.dump({"summary": summary, "rows": rows}, open(os.path.join(out_dir, f"{args.label}-{stamp}.json"), "w"), indent=2)
    md = os.path.join(out_dir, "summary.md")
    new = not os.path.exists(md)
    with open(md, "a", encoding="utf-8") as f:
        if new:
            f.write("| config | mode | Hit@1 | Hit@3 | Hit@5 | MRR | page acc. | answer acc. | p50 ms | p95 ms | prompt tokens |\n")
            f.write("|---|---|---|---|---|---|---|---|---|---|---|\n")
        s = summary
        f.write(f'| {s["label"]} | {s["mode"]} | {s["hit@1"]} | {s["hit@3"]} | {s["hit@5"]} | {s["mrr"]} | {s["page_accuracy"]} | '
                f'{s["answer_accuracy"]} | {s["latency_p50_ms"]} | {s["latency_p95_ms"]} | {s["avg_prompt_tokens"]} |\n')
    print("appended to", md)


if __name__ == "__main__":
    main()
