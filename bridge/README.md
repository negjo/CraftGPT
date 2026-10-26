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

## Selecting a model

Choose a preset per build (all stages of that build use the same model):

| Preset | Model | Reasoning | Intended use |
| --- | --- | --- | --- |
| `codex` | `gpt-6.1-sol` | `high` | Existing baseline; quality and usage balance |
| `codex-astra` | `gpt-6-astra` | `high` | Try first when build quality matters most |
| `codex-astra-xhigh` | `gpt-6-astra` | `xhigh` | Compare extra reasoning on complex builds; allow more time |
| `codex-luna` | `gpt-6-luna` | `high` | Efficient experiments; compare quality before relying on it |

Example: `/craftgpt generate codex-astra Build a medieval castle`.
After editing an installed configuration, run `/craftgpt reload`. No bridge restart or plugin rebuild is required for model/preset changes. Existing configs are not overwritten by a plugin build, so merge these presets into `plugins/CraftGPT/config.yml` separately.

To add another option, copy a Codex preset, give its alias a name without dots, and change `payload`'s `model` and `reasoning.effort`. The bridge forwards both fields and validates them against the signed-in account's `model/list`; unavailable models or efforts fail explicitly. The model ID itself retains its dots. `/health` reports the bridge's fallback defaults, not the selected preset or last request's model. Generation log entries report the actual model and effort.

These options were confirmed in this account's Codex catalog, including image input. Availability can change. Extra reasoning may increase latency and usage; it does not guarantee better architecture or remove the bridge timeout. OpenAI's general model guidance: https://developers.openai.com/api/docs/models . Account-specific model discovery: https://learn.chatgpt.com/docs/app-server#list-models-modellist .
## Image planning and structured output

A controlled GPT-6.1 Sol/high comparison reproduced `max_output_tokens` failures specifically on requests combining local preview images with native `turn/start.outputSchema`. The same small planning task succeeded without images, and succeeded with images when given the schema as prompt text instead. The failed image probe reported only 266 output tokens (229 reasoning), not 128,000. Those usage events do not reveal every internal service step, so they do not establish the hidden backend limit or its exact cause.

The bridge now passes native `outputSchema` for text-only requests. For image requests it includes the complete schema in the prompt instead, parses the JSON, and validates types, required/extra fields, enums, patterns, lengths, array limits, numeric bounds, and anyOf locally. Unsupported schema keywords are rejected before inference. Malformed or nonconforming JSON returns an explicit retryable error; no incomplete output is accepted. This keeps terrain images without relying on the failing native path. It also covers image critique and repair requests.

Request logs now record a request ID, stage/schema, requested model/effort, image count, elapsed time, input text/schema sizes, and reported input/output/reasoning usage where available. They do not log prompt text, images, or credentials. Usage can remain unavailable on early errors. `/health` exposes `imageStructuredOutput: prompt-validated` when this version is loaded.

On the local server, bridge scripts are separate from the plugin JAR. Fully exit using the server console's `stop`, then launch the normal batch file to reload bridge changes. Ctrl+C restarts only Java and can retain the old bridge. Rebuilding the plugin is unnecessary for this bridge-only change.

Administrative tool sessions are documented in [docs/admin-mode.md](../docs/admin-mode.md). /craftgpt admin <codex preset> <instruction> uses authenticated job endpoints and plugin-hosted file/console tools; it does not relax generation request permissions.
