# jade-vllm-connection

## Objective

Leave the JADE ↔ vLLM plumbing ready so the project owner can implement the agents themselves:
a vLLM server profile that runs well on the new laptop (RTX 5060 Laptop GPU, 8 GB VRAM) and a
small Java client plus a JADE behaviour that agents use to query it.

## Problem and why

- `docker-compose.yml` was copied from an older project: sized for an RTX 3060 (6 GB) and
  documented against files that do not exist here (`docs/uso/*`, `scripts/llm-server.sh`, `AppConfig`).
- The project has no code that talks to the LLM; the owner wants to write the agents, not the transport.

## Scope

In: `docker-compose.yml`, `pom.xml` (one JSON dependency), new `Llm` Java package, short usage README.
Out: agent logic, prompts, MAPE-K loop, market rules (owner implements them). The owner's uncommitted
`Agent/` → `Agentes/` move is not touched and not committed by this feature.

## Constraints

- Local only: no model download (use what the `llm-mas-market-hf-cache` volume already holds), no push/PR.
- Java identifiers and comments in neutral Spanish, matching the existing `Agentes` package; compose/pom comments in English.
- TDD: off (source: no project/session configuration; `pom.xml` has no test framework or runner). Functional checks instead.
- Delivery: local work-unit commits on `feat/jade-vllm-connection`. Forecast was ~380 authored lines; actual is ~700 (T1 185, T2 ~520: the client grew with Javadoc and error handling). No PR is planned by this feature, so no chain strategy was chosen; push/PR/merge are the owner's decision.

## Evidence gathered (2026-10-08)

- `vllm/vllm-openai:v0.29.0` ships torch 2.13.0+cu130 with `sm_120`; `torch.cuda` sees "RTX 5060 Laptop GPU", capability (12, 0), 6.78/7.61 GiB free with the desktop running.
- Unmodified compose on this GPU: healthy, serves `Qwen/Qwen3.5-0.8B` + alias `market-llm`; VRAM 6842/8151 MiB (4.06 GiB KV cache, 46x concurrency: oversized); 81 tok/s single, 244 tok/s aggregate x5; `structured_outputs.regex` works; no `<think>` output.
- Cold start: engine init 120.85 s (Triton kernel compile + FlashInfer autotune). Same container restarted: 15.44 s, healthy in 38 s. The caches (`/root/.triton`, `/root/.cache/vllm`) are lost on every `down`/`up` because they are not on a volume.

## Tasks

- [x] **T1 — vLLM profile for the RTX 5060 (8 GB)**. Route: inline (one file, needs live GPU trials).
  - Persist JIT caches in named volumes; size the KV cache explicitly instead of taking 80 % of VRAM; raise context to 8192; decide `--enforce-eager` from a measured trial; rewrite comments for this project/machine.
  - Acceptance: `down` + `up` reaches healthy in well under the 150 s cold start; VRAM footprint leaves headroom for the desktop; chat + regex-constrained requests succeed under 5 concurrent requests.
  - Checks: `docker compose up -d --wait`, `/v1/models`, throughput probe (single + x5 + regex), `nvidia-smi`.
- [x] **T2 — Java client + JADE behaviour**. Route: delegated writer (2+ non-trivial files).
  - `Llm.ClienteLlm` (OpenAI-compatible chat completions over `java.net.http`, sync + async, optional regex-constrained output, config from environment), `Llm.ConsultaLlmBehaviour` (non-blocking: the agent keeps running its other behaviours while the LLM answers; callbacks run on the agent thread), `Llm.AgentePruebaLlm` (smoke agent), Jackson dependency, `README.md` usage section.
  - Acceptance: an agent gets an LLM reply through the behaviour while a second behaviour keeps ticking; errors (server down) reach `alFallar` instead of hanging the agent.
  - Checks: `mvn -o -q compile`; live smoke with the demo agent against the T1 server, and once with the server unreachable.

## Progress and evidence

### T1 (done, 2026-10-08) — measured on the RTX 5060 Laptop GPU, vLLM v0.29.0, Qwen3.5-0.8B

| Profile | `down`+`up` to healthy | Engine init | 1 request | 5 concurrent | GPU memory in use (incl. desktop) |
| --- | --- | --- | --- | --- | --- |
| Inherited (eager, util 0.8, no cache volumes) | 156 s | 120.9 s | 81 tok/s | 244 tok/s | 6842 MiB |
| Final (graphs, util 0.5, 3 cache volumes), warm | 36 s | 4.3 s | 190 tok/s | 607 tok/s | 4683 MiB |

- Cache volumes: `/root/.nv` (NVIDIA compute cache, 341 MB) + `/root/.triton` (54 MB) + `/root/.cache/vllm`. With only the last two, warm start was still 111 s; `/root/.nv` is the one that removes the FlashInfer autotune and warmup cost. First start after creating the volumes is still cold (86 s in graphs mode).
- `--kv-cache-memory-bytes` alone does not work: v0.29.0 still validates `--gpu-memory-utilization` (default 0.92 = 7.0 GiB) against free memory and refuses to start, so the single knob kept is `--gpu-memory-utilization 0.5` (KV cache 117,294 tokens = 14.3x at 8192).
- Dropping `--enforce-eager` costs 0.04 GiB of CUDA graph memory and gives ~2.3x throughput.
- Stress: 3 rounds x 8 concurrent requests, ~5.7k prompt tokens each, half regex-constrained: 24/24 HTTP 200, GPU memory stable at 4827 MiB, no engine errors. `structured_outputs.regex` returns conforming text (`Buy_Book{29}`). No `<think>` block in replies.
- Not done: no larger model was tried (Qwen3.5-2B/4B are not downloaded; downloading needs the owner's approval).

- Commit: `6cb7046`. Review assessment (`--base-ref main --committed-only`, owner's untracked files excluded): medium (`configuration_change`), not due (`under_budget`).

### T2 (done, 2026-10-08) — delegated writer, parent readback + spot check

- Files: `pom.xml` (+Jackson 2.18.2), `src/main/java/Llm/{ClienteLlm,ConsultaLlmBehaviour,LlmException,AgentePruebaLlm}.java`, `README.md`.
- `mvn -o -q compile`: exit 0.
- Live smoke `mvn -o -q compile exec:java -Dexec.args="-agents prueba:Llm.AgentePruebaLlm"`: exit 0, returns by itself; 9 ticker lines before the first reply (agent not blocked), free-text reply, regex reply `RECHAZAR`.
- Server unreachable (`LLM_BASE_URL=http://127.0.0.1:9/v1`): `alFallar` receives `ConnectException`, platform shuts down, exit 0.
- Writer's extra check: unknown model + API key -> `HTTP 404` surfaced through `alFallar`.
- Parent fixes after readback: smoke command now includes `compile`; one missing accent in an error message.
- Deviation accepted: the smoke ticker runs every 20 ms (not 200 ms) because the warm server answers in 50-140 ms.
- Not verified: request timeout against a hung server, an invalid regex, `ClienteLlm.disponible()` (compiles, never called in a run).

- Commit: `c0fdda9`. Review assessment over `main..c0fdda9`: high (`process_boundary` on `AgentePruebaLlm.java`), due. The returned preflight STATUS asked for an `intended_untracked_selection` input (the owner's untracked `.atl/` and `Agentes/` files); its exact JSON format is not documented by the tool and one attempt was refused (`invalid_request`), so no native review transaction was started. Fallback per the off path for a high tier: independent verifier.

### Independent verification and correction (2026-10-08)

- Independent read-only verifier: `pass-with-findings`. Live evidence: timeout honoured in 2.0 s (sync and async, also with a stalled body); invalid regex -> `HTTP 400` as `LlmException`; `disponible()` true/false without throwing; regex replies 20/20 matching; callbacks exactly once and on the agent thread under ~450 ACL wake-ups; 4500/4500 sequential queries with 1 ms ACL pings, 0 double callbacks, 0 hangs; 16 threads and 5 agents on one shared client; agent deleted while pending raises nothing.
- Six low-severity findings, all corrected in one pass: a regex reply truncated by `max_tokens` is now an error instead of a non-matching prefix; `IllegalArgumentException` text (which can quote the API key header) is no longer copied into the error message; blocking `completar` now wraps runtime failures as `LlmException` like the async path; Javadoc states that a throwing callback ends the agent (standard JADE); the smoke agent shuts down when an `LLM_*` number is invalid instead of hanging; thread wording in two comments.
- Checks after the correction: `mvn -o -q compile` exit 0; smoke exit 0 (14 ticker lines, both replies); `LLM_MAX_TOKENS=2` -> "se cortó por max_tokens" via `alFallar`, exit 0; `LLM_TIMEOUT_SECONDS=abc` -> error and exit 0 (was a hang); key with a newline -> error without the key text, exit 0.
- Not verified: connect timeout against a non-routable address; a truly cold start with all three cache volumes empty in graphs mode (86 s was measured with the driver/Triton caches already warm).

## Next step

Owner: implement the agents on top of `Llm.ConsultaLlmBehaviour`. Optional: try a larger model now that 8 GB allows it (needs a download, so the owner's approval).
