# MemFail investigation (SPEC R8-A1)

## Goal

Determine whether the MemFail benchmark (referenced as arXiv:2605.26667 in the
project brief) publishes a reusable dataset or harness we can integrate, before
building our own scenario suite.

## Result: original suite not integrated - self-authored suite used

- The reference points at a benchmark for agent-memory failure modes
  (contradiction updates, stale recall, provenance loss). No verified public
  dataset or reference implementation could be confirmed and integrated in this
  build environment (outbound fetching is sandboxed and no released MemFail
  artifact was located and vendored).
- Per the R8-A1 fallback ("불가면 사유 기록 후 자체 스위트로 진행"), we proceed
  with a self-authored MemFail-style suite under `bench/scenarios/`.

## Design carried over from the MemFail framing

The self-authored suite mirrors the failure-mode categories the benchmark
targets, with the counts fixed by SPEC R8-A2:

| Category | Failure mode probed | Count |
|---|---|---|
| contradiction | newer fact must supersede the older contradicting one | 10 |
| correction | explicit correction must retract the target and win | 8 |
| decay | stale fact must age out of active recall | 6 |
| provenance | recalled fact must retain a traceable source utterance | 3 |
| budget | retrieval must respect the token budget | 3 |

Scenarios use deterministic pass/fail judging (expected id/keyword presence and
absence, hit counts, token budget), so the suite runs and scores without an
LLM judge. Free-text LLM judging is reserved for the real-key production run.

## Reproducibility

`bench-report.md` records the embedding model, LLM provider, and run timestamp.
The default deterministic run uses the fake providers, so the suite is
repeatable offline; a real-key run swaps in the Claude and embedding providers
via environment variables (see README).
