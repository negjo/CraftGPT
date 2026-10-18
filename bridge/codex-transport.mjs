// Transport adapted from Angais/minedit v1.11.1 (MIT); see LICENSE.minedit.
import {spawn} from 'node:child_process';
import readline from 'node:readline';
import {EventEmitter} from 'node:events';
const CODEX_BIN=process.env.CODEX_BIN || 'codex';
const RPC_TIMEOUT_MS=30000;
class BridgeError extends Error {
  constructor(status, message, details = null) {
    super(message);
    this.status = status;
    this.details = details;
  }
}

class CodexAppServerSession {
  constructor() {
    this.nextId = 1;
    this.pending = new Map();
    this.events = new EventEmitter();
    this.dynamicToolHandlers = new Map();
    this.stderr = "";
    this.closed = false;

    this.proc = spawn(CODEX_BIN, ["app-server"], {
      windowsHide: true,
      stdio: ["pipe", "pipe", "pipe"],
    });

    this.proc.once("error", (error) => {
      this.failAll(new BridgeError(502, `Could not start Codex app-server with '${CODEX_BIN}': ${error.message}`));
    });

    this.proc.once("exit", (code, signal) => {
      this.closed = true;
      this.failAll(new BridgeError(502, `Codex app-server exited unexpectedly (${signal || code}). ${this.stderr}`.trim()));
    });

    this.proc.stderr.on("data", (chunk) => {
      this.stderr = (this.stderr + chunk.toString()).slice(-4000);
    });

    this.rl = readline.createInterface({ input: this.proc.stdout });
    this.rl.on("line", (line) => this.handleLine(line));
  }

  async initialize() {
    await this.request("initialize", {
      clientInfo: {
        name: "craftgpt_bridge",
        title: "CraftGPT Codex Bridge",
        version: "0.1.0",
      },
      capabilities: {
        experimentalApi: true,
      },
    }, RPC_TIMEOUT_MS);
    this.notify("initialized", {});
  }

  handleLine(line) {
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      this.stderr = (this.stderr + "\nNon-JSON stdout: " + line).slice(-4000);
      return;
    }

    if (Object.prototype.hasOwnProperty.call(msg, "id") && Object.prototype.hasOwnProperty.call(msg, "method")) {
      this.handleServerRequest(msg);
      return;
    }

    if (Object.prototype.hasOwnProperty.call(msg, "id")) {
      const pending = this.pending.get(msg.id);
      if (!pending) {
        return;
      }
      this.pending.delete(msg.id);
      clearTimeout(pending.timeout);
      if (msg.error) {
        pending.reject(new BridgeError(502, `${pending.method} failed: ${msg.error.message || "Codex app-server error"}`, msg.error));
      } else {
        pending.resolve(msg.result);
      }
      return;
    }

    if (msg.method) {
      this.events.emit("notification", msg);
    }
  }

  handleServerRequest(msg) {
    if (msg.method === "item/tool/call") {
      this.handleDynamicToolCall(msg);
      return;
    }
    if (msg.method === "item/commandExecution/requestApproval" || msg.method === "item/fileChange/requestApproval") {
      this.send({ id: msg.id, result: { decision: "cancel" } });
      return;
    }
    if (msg.method === "tool/requestUserInput" || msg.method === "item/tool/requestUserInput") {
      this.send({ id: msg.id, result: { answers: {} } });
      return;
    }
    this.send({
      id: msg.id,
      error: {
        code: -32601,
        message: `Minedit bridge does not handle Codex server request '${msg.method}'.`,
      },
    });
  }

  registerDynamicToolHandler(threadId, handler) {
    this.dynamicToolHandlers.set(threadId, handler);
  }

  unregisterDynamicToolHandler(threadId) {
    this.dynamicToolHandlers.delete(threadId);
  }

  handleDynamicToolCall(msg) {
    const params = msg.params || {};
    const handler = this.dynamicToolHandlers.get(params.threadId);
    if (!handler) {
      this.send({
        id: msg.id,
        result: {
          success: false,
          contentItems: [{ type: "inputText", text: "No Minedit dynamic tool handler is registered for this thread." }],
        },
      });
      return;
    }

    Promise.resolve(handler(params))
      .then((result) => {
        this.send({ id: msg.id, result });
      })
      .catch((error) => {
        this.send({
          id: msg.id,
          result: {
            success: false,
            contentItems: [{ type: "inputText", text: `Minedit tool error: ${error.message || String(error)}` }],
          },
        });
      });
  }

  request(method, params = {}, timeoutMs = RPC_TIMEOUT_MS) {
    if (this.closed) {
      return Promise.reject(new BridgeError(502, "Codex app-server is not running."));
    }
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new BridgeError(504, `${method} timed out after ${Math.round(timeoutMs / 1000)}s.`));
      }, timeoutMs);
      this.pending.set(id, { method, resolve, reject, timeout });
      this.send({ method, id, params });
    });
  }

  notify(method, params = {}) {
    this.send({ method, params });
  }

  send(message) {
    this.proc.stdin.write(`${JSON.stringify(message)}\n`);
  }

  waitForNotification(predicate, timeoutMs) {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.events.off("notification", listener);
        reject(new BridgeError(504, `Codex turn timed out after ${Math.round(timeoutMs / 1000)}s.`));
      }, timeoutMs);
      const listener = (msg) => {
        if (!predicate(msg)) {
          return;
        }
        clearTimeout(timeout);
        this.events.off("notification", listener);
        resolve(msg);
      };
      this.events.on("notification", listener);
    });
  }

  failAll(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timeout);
      pending.reject(error);
    }
    this.pending.clear();
    this.events.emit("notification", { method: "bridge/error", params: { error: error.message } });
  }

  close() {
    this.closed = true;
    this.failAll(new BridgeError(499, 'Codex session closed.'));
    this.rl.close();
    if (!this.proc.killed) {
      this.proc.kill("SIGTERM");
    }
  }
}

function normalizeCodexModel(model) {
  const trimmed = String(model || "").trim();
  return trimmed.startsWith("openai/") ? trimmed.slice("openai/".length) : trimmed;
}

function effortList(model) {
  return (model?.supportedReasoningEfforts || [])
    .map((entry) => entry?.reasoningEffort)
    .filter(Boolean);
}

function findModel(models, requested) {
  const normalized = normalizeCodexModel(requested);
  return models.find((model) => model.id === normalized || model.model === normalized || model.displayName === requested);
}

function extractAgentText(turn, fallbackByItemId) {
  const items = turn?.items || [];
  const agentMessages = items.filter((item) => item?.type === "agentMessage" && typeof item.text === "string");
  const finalMessage = [...agentMessages].reverse().find((item) => item.phase === "final_answer");
  if (finalMessage) {
    return finalMessage.text;
  }
  if (agentMessages.length > 0) {
    return agentMessages[agentMessages.length - 1].text;
  }
  const fallback = [...fallbackByItemId.values()].join("").trim();
  return fallback || "";
}

async function withSession(fn) {
  const session = new CodexAppServerSession();
  try {
    await session.initialize();
    return await fn(session);
  } finally {
    session.close();
  }
}

async function readAccount(session) {
  return await session.request("account/read", { refreshToken: false }, RPC_TIMEOUT_MS);
}

async function listModels(session) {
  const result = await session.request("model/list", { limit: 200, includeHidden: true }, RPC_TIMEOUT_MS);
  return result?.data || [];
}


export {BridgeError, CodexAppServerSession, findModel, effortList, extractAgentText, readAccount, listModels};
