# Administrative mode

Use an existing Codex bridge preset:

```text
/craftgpt admin codex Change the Essentials teleport cooldown to 10 seconds and reload Essentials if supported.
/craftgpt admin codex-astra Inspect the recent server log and explain plugin startup errors.
/craftgpt stop
```

No WorldEdit selection is needed. `craftgpt.admin` defaults to operators and grants powerful server administration capabilities; grant it only to trusted administrators. Generation still uses `craftgpt.use`. Commands also work from the server console. Stop cancels tasks owned by that sender; it does not roll back changes already applied. Player disconnect and plugin shutdown cancel their admin work. Only one administrative agent runs at a time, separately from generation jobs.

The agent uses a fresh Codex thread with the built-in `:read-only` permission profile. Shell execution, apps, configured MCP servers, plugins, hooks and subagents are disabled for that session. All supported mutations go through the custom server tools, hosted by CraftGPT. This does not inherit permissions from a desktop project, and no separate project or RCON credential transfer is needed. The current installed Codex protocol uses the `permissions` thread field; older Codex versions without that field need updating. A custom restricted-read profile was rejected by this installation with `failed to load workspace requirements`; the implementation does not silently fall back to unrestricted writes.

## Available tools

- `server_list`: list up to 200 directory entries, using server-relative paths.
- `server_read_file`: read UTF-8 configuration (1 MiB maximum; returns up to 64,000 characters and a content hash), or the last 32 KiB of a log. Credential fields are redacted. JSON credentials that cannot be safely redacted cause the full JSON document to be hidden.
- `server_edit_file`: replace one unique literal occurrence, using the hash from the preceding read. Existing credential fields must remain unchanged. Validates YAML, JSON and Java properties syntax; other supported text formats receive size/encoding checks.
- `server_create_file`: create a new text configuration in an existing server directory. Set temporary=true for scratch/intermediate files and false for requested persistent outputs. Tracked temporary files are cleaned up when the task completes, fails, times out or is cancelled. No overwrite, binary installation, shell scripts, general deletion, or directory creation is exposed.
- `server_delete_temporary_file`: remove a temporary file created by the current task as soon as it is no longer needed. Existing files and persistent outputs cannot be deleted. Files changed externally or replaced by linked paths are preserved, with automatic cleanup failures logged for manual review.
- `server_command`: execute any registered command or alias through Paper's console on the server thread and capture synchronous text output. The agent cannot independently send arbitrary RCON requests. Commands that produce output asynchronously may only return their initial acknowledgement.

Paths are confined to the canonical server directory. Absolute paths, traversal, hidden metadata paths, external links/junctions, bridge files, credentials, backup/audit files, and CraftGPT's own policy config are protected as appropriate. Normal plugin configuration, server settings and logs remain accessible. Root `commands.yml` and CraftGPT's config are read-only to prevent changing the agent's own permissions or command routing. RCON credentials and server network binding fields cannot be changed.

The agent must create scratch files through the tracked file tool, rather than console commands. Cleanup preserves requested persistent outputs, audit logs and recovery backups. Automatic cleanup runs in the worker finalizer, so an abrupt process termination or machine crash can leave scratch files behind.

Edits validate before replacement, check the current content hash again, and back up originals under `plugins/CraftGPT/admin-backups/`. These backups contain original file contents and should be treated as private. Tool attempts are recorded in `plugins/CraftGPT/admin-audit.log` with actor, task ID, tool and target; prompts, file contents, model replies, and the bridge key are not written there. Console commands are powerful: trusted plugin commands retain whatever capabilities their implementations already have, including normal database or filesystem side effects. The file-tool path boundary does not sandbox the entire Paper process.

## Configuration

The `admin` section of `config.yml` controls `enabled`, loopback `bridge_url`, `timeout_seconds` (30–900), `max_tool_calls` (1–100). The preset endpoint must match that bridge's `/v1/responses` endpoint. No model requests or mutation commands are automatically retried. Mutation command attempts are not replayed within a task, and tool-result acknowledgement is idempotent.

All registered console commands and aliases are available, including vanilla commands, plugin commands and indirect execution. The legacy allowed_console_commands setting is ignored. The agent prefers a simple command over file edits (for example, minecraft:give for a one-off item); it edits files when a simple command cannot accomplish the required persistent change or when editing is substantially simpler than multiple complicated commands. Stop, restart and global reload require an explicit user request; they are no longer blocked by command-root checks.

A random local `plugins/CraftGPT/admin-bridge.key` is generated when the updated plugin enables. It authenticates `/admin/jobs` and related endpoints over loopback; never print, commit, or send this file to the model. No RCON password is read by this feature. The bridge discovers the installed server directory, or uses `CRAFTGPT_ADMIN_SERVER_ROOT` if explicitly set before launch.

## Installation and validation

Build the plugin normally to use the existing local deployment hook. Use `-Dcraftgpt.deploy.skip=true` for verification while the server runs. Bridge files are separate: install `admin-agent.mjs` and the updated `craftgpt-codex-bridge.mjs` / `codex-transport.mjs` alongside the existing bridge modules. Then manually stop and launch the server with its normal launcher to reload both the plugin and bridge; Ctrl+C alone may keep the previous bridge alive. The updated health response includes `adminSupported: true`.

Offline validation: Maven `verify`, `node bridge/test-bridge.mjs`, and `node bridge/test-admin.mjs`. File/console tests use temporary fixtures and a fake console sender; bridge tests use a fake Codex session. A live Codex smoke test exercised only a mocked directory listing against a temporary fixture. No real server command, configuration edit, start, stop, or restart was performed during these tests.

## Chat feedback

Admin chat uses the configured CraftGPT prefix and customizable messages.yml templates: blue task starts, muted file activity, amber commands/warnings, green completion, red errors, and light result text. Each command is shown in full immediately before dispatch on the server thread, including long item/component arguments; credentials remain redacted. Command text and model content are inserted as literal text, never parsed as formatting. No command is shown as executed merely because the model proposed it.

Tool validation and tool-call budget failures are contained within their admin task; they do not terminate the shared bridge. Connection failures and timeouts produce actionable chat messages, with the task ID and exception type logged in the server console.
