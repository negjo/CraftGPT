# CraftGPT Codex bridge

From the repository root, start the bridge in your own console:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Start-CodexBridge.ps1
```

Alternatively, with Node.js 18+ and a signed-in Codex CLI on PATH:

```sh
node bridge/craftgpt-codex-bridge.mjs
```

Start your Paper server separately when ready. Use a WorldEdit selection and `/craftgpt generate codex YOUR_DESCRIPTION`. The bridge does not launch Minecraft. On existing servers, merge the `codex` preset and `request_timeout_seconds: 900` from `src/main/resources/config.yml` into the installed plugin configuration; a rebuilt plugin does not overwrite an existing config.

The bridge listens only on `127.0.0.1:8766` and implements the Responses API subset used by CraftGPT: instructions, text, JSON schemas, and inline preview images. It translates these into ephemeral Codex app-server threads and returns Responses-compatible JSON. Planning, generation, critique, and refinement each retain their original schema and prompt. Each request uses a fresh ephemeral thread. Preview images are written into request-specific temporary directories and removed afterward. Prompts and API keys are not logged.

The supplied `codex` preset uses `gpt-6.1-sol` with high reasoning effort, matching our Minedit configuration. The existing Codex login is used; `api_key: codex-local` is a placeholder, not an API credential. Inference uses your Codex account and usage allowance, not your local GPU. The Ollama `local` preset remains available; its quality-model alias must already exist in Ollama.

`GET /health` reports model, effort and active requests. The default per-request timeout is 14 minutes, below the 15-minute CraftGPT timeout. Override `CRAFTGPT_BRIDGE_PORT` and `CRAFTGPT_CODEX_TIMEOUT_MS` before launching if needed. Requests are limited to 32 MiB. Streaming, API tools, and previous-response IDs are rejected. Sampling parameters and `max_output_tokens` are not forwarded: the app-server interface has no equivalent per-turn controls. Upstream incomplete responses are returned as retryable HTTP 502 errors for CraftGPT's retry handling.

Run `node bridge/test-bridge.mjs` for offline text/image conversion and HTTP tests. They start an ephemeral local HTTP listener, make no Codex requests, and do not start Minecraft. Maven regression tests cover the migrated schema, renderer, repair prompt, and optional castle route. Earlier live checks generated a hut that passed CraftGPT's compiler and received an image-based critique using the same Codex model; no live model calls are part of the offline tests.

Transport adapted from Angais/minedit v1.11.1 (MIT); see `LICENSE.minedit`.
Official app-server documentation: https://learn.chatgpt.com/docs/app-server
Preset aliases use hyphens rather than dots (for example, gpt-5-6 selects API model gpt-5.6). Bukkit interprets dots in configuration keys as nested paths, which otherwise produces a broken gpt-5 preset. Existing server configurations need the same alias changes; rebuilding alone does not replace them.
