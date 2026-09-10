/* We0J Web Console — Vue3 Composition API, 免构建单文件应用 */
"use strict";
const { createApp, reactive, ref, computed, nextTick, onMounted } = Vue;

/* ---------------- token / fetch ---------------- */
const params = new URLSearchParams(location.search);
if (params.get("token")) {
  localStorage.setItem("we0j_token", params.get("token"));
  history.replaceState(null, "", location.pathname); // 隐藏地址栏 token
}
const token = () => localStorage.getItem("we0j_token") || "";

async function api(path, opts = {}) {
  const headers = Object.assign({ Authorization: "Bearer " + token() }, opts.headers || {});
  if (opts.json !== undefined) {
    headers["Content-Type"] = "application/json";
    opts = { ...opts, method: opts.method || "POST", body: JSON.stringify(opts.json) };
  }
  const res = await fetch(path, { ...opts, headers });
  if (res.status === 401) { // token 失效/缺失 → 回到登录遮罩
    localStorage.removeItem("we0j_token");
    state.needToken = true;
  }
  if (!res.ok) throw new Error(res.status + " " + (await res.text()).slice(0, 200));
  const ct = res.headers.get("content-type") || "";
  return ct.includes("json") ? res.json() : res.text();
}

/* ---------------- markdown-lite（先转义后包裹，安全 v-html） ---------------- */
function esc(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}
function mdLite(src) {
  const fences = [];
  let text = String(src == null ? "" : src).replace(/```[\w+-]*\n?([\s\S]*?)(```|$)/g, (_, code) => {
    fences.push('<pre class="code-block">' + esc(code.replace(/\n$/, "")) + "</pre>");
    return "\x00F" + (fences.length - 1) + "\x00";
  });
  text = esc(text)
    .replace(/`([^`\n]+)`/g, '<code class="inline-code">$1</code>')
    .replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\n/g, "<br>");
  return text.replace(/\x00F(\d+)\x00/g, (_, i) => fences[+i]);
}

/* ---------------- 全局状态 ---------------- */
const state = reactive({
  sessions: [], currentId: null, messages: [],
  status: "idle", statusDetail: null, rawStatus: "",
  model: "", models: [], permissionMode: "ASK", permissionModes: ["ASK", "ALLOW_ONCE", "BYPASS", "REJECT"],
  panel: null, panelData: {},
  permission: null, permissionNote: "", alwaysPicks: [],
  question: null, answers: [], customs: [],
  draft: "", sending: false, error: "", sseOn: false,
  needToken: false, tokenDraft: "", tokenBusy: false, tokenError: "",
  authStep: "token", providers: [], provSel: "", provModel: "", provKey: "", provBase: "", defaultRef: {},
  editId: null, editTitle: "",
});
const partIndex = new Map(); // partId -> { msg, idx }
let es = null, tmpSeq = 0, lastEventSeq = 0;

/* ---------------- 消息 / part 规范化 ---------------- */
function msgOf(id, role) {
  let m = state.messages.find((x) => x.id === id);
  if (!m) { m = { id, role: role || "assistant", parts: [], error: null }; state.messages.push(m); }
  return m;
}
function num(v) { return typeof v === "number" ? v : Number(v) || 0; }
function usageLine(cost, t) {
  t = t || {};
  const c = num(cost);
  return `in ${num(t.input)} · out ${num(t.output)} · total ${num(t.total)}` +
    (c ? ` · $${c.toFixed(4)}` : "");
}
function normalizePart(p) { // 服务端 Part JSON（@JsonTypeInfo: type 判别）→ 视图模型
  const base = { key: p.id || "p" + (++tmpSeq), type: p.type || "text" };
  switch (base.type) {
    case "text": return { ...base, kind: "text", text: p.text || "" };
    case "reasoning": return { ...base, kind: "reasoning", text: p.text || "" };
    case "tool": {
      const st = p.state || {};
      return { ...base, kind: "tool", toolName: p.toolName || st.toolName || "tool",
        status: st.status || "pending", title: st.title || "",
        input: st.input != null ? JSON.stringify(st.input, null, 2) : (st.raw || ""),
        output: st.output != null ? st.output : null, error: st.error != null ? st.error : null };
    }
    case "step-finish": return { ...base, kind: "step-finish", usage: usageLine(p.cost, p.tokens), cost: num(p.cost), tokens: p.tokens || {} };
    case "step-start": return { ...base, kind: null };
    case "compaction": return { ...base, kind: "compaction" };
    default: return { ...base, kind: "other" };
  }
}
function applyMessageUpdate(d) { // {message} 先行于 part 事件：建档 + 权威 role（修复持久消息误渲为 assistant）
  const m = d.message || d;
  if (!m || !m.id) return;
  const msg = msgOf(m.id, m.role || "assistant");
  if (m.role) msg.role = m.role;
  if (m.error) msg.error = m.error.message || (typeof m.error === "string" ? m.error : JSON.stringify(m.error));
}
function upsertPart(data) {
  const p = data.part || data;
  if (!p || !p.id) return;
  const view = normalizePart(p);
  if (view.kind === null || view.kind === undefined) return;
  const msg = msgOf(p.messageId);
  const at = msg.parts.findIndex((x) => x.key === view.key);
  if (at >= 0) msg.parts.splice(at, 1, view);
  else msg.parts.push(view);
  partIndex.set(p.id, { msg, key: view.key });
  // 乐观去重：持久化 user 文本到达 → 移除同文本的 tmp-* 乐观消息（双显修复）
  if (msg.role === "user" && view.kind === "text" && view.text) {
    const i = state.messages.findIndex((m) => m.id.startsWith("tmp-") && m.role === "user"
      && m.parts.some((x) => x.kind === "text" && x.text === view.text));
    if (i >= 0) state.messages.splice(i, 1);
  }
}
function applyDelta(data) { // {partId|id, messageId?, delta|text}
  const pid = data.partId || data.id;
  const chunk = data.delta != null ? data.delta : (data.text || "");
  const hit = partIndex.get(pid);
  if (hit) { const part = hit.msg.parts.find((x) => x.key === hit.key); if (part) part.text += chunk; return; }
  const msg = msgOf(data.messageId || "stream-" + (pid || "x"));
  const view = { key: pid || "p" + (++tmpSeq), type: data.type || "text", kind: data.type === "reasoning" ? "reasoning" : "text", text: chunk };
  msg.parts.push(view);
  if (pid) partIndex.set(pid, { msg, key: view.key });
}
function loadMessages(list) {
  const arr = Array.isArray(list) ? list : (list && list.items) || [];
  state.messages = []; partIndex.clear();
  for (const item of arr) {
    const m = item.message || item;
    const msg = msgOf(m.id, m.role || "assistant");
    if (m.error) msg.error = m.error.message || (typeof m.error === "string" ? m.error : JSON.stringify(m.error));
    msg.parts = (item.parts || []).map(normalizePart).filter((p) => p.kind);
    for (const p of (item.parts || [])) if (p.id) partIndex.set(p.id, { msg, key: p.id });
  }
}

/* ---------------- SSE ---------------- */
const SSE_EVENTS = ["session.updated", "message.updated", "message.part.updated",
  "message.part.delta", "permission.asked", "question.asked", "task.updated", "todo.updated"];
function disconnectSSE() { if (es) { es.close(); es = null; } state.sseOn = false; }
function connectSSE() {
  disconnectSSE();
  if (!state.currentId) return;
  es = new EventSource("/api/sessions/" + encodeURIComponent(state.currentId) +
    "/events?token=" + encodeURIComponent(token()));
  es.onopen = () => { state.sseOn = true; };
  es.onerror = () => { state.sseOn = false; }; // EventSource 自动重连并带 Last-Event-ID
  for (const name of SSE_EVENTS) {
    es.addEventListener(name, (ev) => {
      if (ev.lastEventId) lastEventSeq = Number(ev.lastEventId) || lastEventSeq;
      let d = {}; try { d = JSON.parse(ev.data); } catch (e) { return; }
      onEvent(name, d);
    });
  }
}
function onEvent(name, d) {
  switch (name) {
    case "message.updated": applyMessageUpdate(d); break;
    case "message.part.delta": applyDelta(d); break;
    case "message.part.updated": upsertPart(d); break;
    case "session.updated": applySessionUpdate(d); break;
    case "permission.asked":
      state.permission = d.request || d; state.permissionNote = ""; state.alwaysPicks = []; break;
    case "question.asked":
      state.question = d.request || d;
      state.answers = (state.question.questions || []).map((q) => (q.multiSelect ? [] : null));
      state.customs = (state.question.questions || []).map(() => ""); break;
    case "task.updated": case "todo.updated":
      if (state.panel === "tasks" || state.panel === "todos") loadPanel(state.panel); break;
  }
  nextTick(scrollBottom);
}
function applySessionUpdate(d) {
  const s = d.status != null ? d.status : (d.session && d.session.status);
  if (typeof s === "string") { state.status = s; state.statusDetail = null; }
  else if (s && typeof s === "object") { state.status = s.wire || "busy"; state.statusDetail = s; }
  const m = d.model || d.modelId || (d.session && (d.session.model || d.session.modelId));
  if (m) state.model = typeof m === "string" ? m : (m.id || m.modelId || m.name || state.model);
  if (d.permissionMode) state.permissionMode = d.permissionMode;
  if (d.title) { // 他端（CLI/其它窗口）重命名 → 同步本端列表
    const row = state.sessions.find((x) => x.id === (d.sessionId || state.currentId));
    if (row) row.title = d.title;
    else loadSessions();
  }
}

/* ---------------- 动作 ---------------- */
let scroller = null, inputEl = null;
function scrollBottom() { if (scroller) scroller.scrollTop = scroller.scrollHeight; }
function sid() { return encodeURIComponent(state.currentId); }
function fail(e) { state.error = String(e.message || e); setTimeout(() => (state.error = ""), 6000); }

/* ---------------- token 登录 ---------------- */
async function submitToken() {
  const t = state.tokenDraft.trim();
  if (!t || state.tokenBusy) return;
  state.tokenBusy = true; state.tokenError = "";
  localStorage.setItem("we0j_token", t);
  try {
    await api("/api/sessions");            // 验证接口：2xx 即 token 有效
    state.tokenDraft = ""; state.tokenBusy = false;
    await enterProviderStep();             // 步骤 2：API 厂家选择
  } catch (e) {
    localStorage.removeItem("we0j_token");
    state.tokenError = "token 无效：" + (e.message || e);
    state.needToken = true; state.authStep = "token";
  } finally { state.tokenBusy = false; }
}
/* ---- 登录步骤 2：厂家选择（加载失败不阻塞，直接放行） ---- */
async function enterProviderStep() {
  try {
    const [ps, dft] = await Promise.all([api("/api/providers"), api("/api/providers/default")]);
    state.providers = Array.isArray(ps) ? ps : [];
    state.defaultRef = dft || {};
    const hit = state.providers.find((p) => p.id === (dft && dft.provider));
    state.provSel = hit ? hit.id : "";
    syncProvModel();
    state.needToken = true; state.authStep = "provider";
  } catch (e) { finishAuth(); }
}
function selectedProvider() {
  return state.providers.find((p) => p.id === state.provSel) || null;
}
function syncProvModel() {
  const pr = selectedProvider();
  const ms = pr && pr.models ? pr.models : [];
  state.provModel = ms.includes(state.provModel) ? state.provModel : (ms[0] || "");
}
function pickProvider(p) {
  state.provSel = p.id; state.tokenError = "";
  syncProvModel();
}
async function applyProviderEnter() {
  if (!state.provSel) { finishAuth(); return; }
  state.tokenBusy = true; state.tokenError = "";
  try {
    const body = {};
    if (state.provKey.trim()) { body.apiKey = state.provKey.trim(); body.enabled = true; }
    if (state.provBase.trim()) body.apiBase = state.provBase.trim();
    if (Object.keys(body).length) {
      await api("/api/providers/" + encodeURIComponent(state.provSel), { method: "POST", json: body });
    }
    await api("/api/providers/default", { method: "POST",
      json: { provider: state.provSel, model: state.provModel || undefined } });
    localStorage.setItem("we0j_provider", state.provSel + "/" + state.provModel);
    finishAuth();
  } catch (e) {
    state.tokenError = "应用失败：" + (e.message || e);
    state.tokenBusy = false;
  }
}
function finishAuth() {
  state.needToken = false; state.authStep = "token"; state.tokenBusy = false; state.tokenError = "";
  loadSessions(); loadModels();
}

async function loadSessions() {
  try {
    const list = await api("/api/sessions");
    const rows = Array.isArray(list) ? list : (list.items || list.sessions || []);
    state.sessions = rows.map((s) => ({
      id: s.id || s.sessionId,
      title: s.title || s.name || (s.directory || s.workdir || "").split(/[\\/]/).pop() || (s.id || "").slice(0, 8),
      workdir: s.directory || s.workdir,
      status: s.status,
    }));
    if (!state.currentId && state.sessions.length) selectSession(state.sessions[0].id);
  } catch (e) { fail(e); }
}
async function selectSession(id) {
  if (!id) return;
  state.currentId = id; state.error = "";
  connectSSE();
  try { loadMessages(await api("/api/sessions/" + encodeURIComponent(id) + "/messages")); }
  catch (e) { fail(e); }
  refreshStatus();
  if (state.panel) loadPanel(state.panel);
}
function onPickSession() { selectSession(state.currentId); }
/* ---------------- 重命名会话 ---------------- */
function startRename(s) {
  state.editId = s.id; state.editTitle = s.title;
  nextTick(() => {
    const el = document.querySelector(".rename-input");
    if (el && state.editId === s.id) { el.focus(); el.select(); }
  });
}
function cancelRename() { state.editId = null; state.editTitle = ""; }
async function commitRename() {
  const id = state.editId;
  if (!id) return; // Enter 提交后 blur 会二次触发，此时已结束编辑
  const row = state.sessions.find((x) => x.id === id);
  const title = state.editTitle.trim();
  state.editId = null; state.editTitle = "";
  if (!row || !title || title === row.title) return;
  try {
    await api("/api/sessions/" + encodeURIComponent(id) + "/rename", { method: "POST", json: { title } });
    row.title = title; // 顶栏下拉框与列表同源，自动刷新
  } catch (e) { fail(e); }
}
async function newSession() {
  const workdir = window.prompt("工作目录 workdir（可空则用服务端默认）", "");
  if (workdir === null) return;
  try {
    const s = await api("/api/sessions", { method: "POST", json: { workdir: workdir || undefined } });
    await loadSessions();
    selectSession(s.id || s.sessionId);
  } catch (e) { fail(e); }
}
async function send() {
  const text = state.draft.trim();
  if (!text || !state.currentId || state.sending) return;
  state.draft = ""; state.sending = true;
  const msg = msgOf("tmp-" + (++tmpSeq), "user");
  msg.parts.push({ key: "tmp-p" + tmpSeq, type: "text", kind: "text", text });
  nextTick(scrollBottom);
  try { await api("/api/sessions/" + sid() + "/prompt", { json: { text } }); }
  catch (e) { fail(e); }
  finally { state.sending = false; if (inputEl) inputEl.focus(); }
}
async function cancel() {
  try { await api("/api/sessions/" + sid() + "/cancel", { method: "POST" }); } catch (e) { fail(e); }
}
function onKeydown(ev) {
  if (ev.key === "Enter" && !ev.shiftKey && !ev.isComposing) { ev.preventDefault(); send(); }
}
async function replyPermission(reply) {
  const req = state.permission; if (!req) return;
  state.permission = null;
  try {
    await api("/api/permissions/" + encodeURIComponent(req.id) + "/reply", {
      json: { sessionId: req.sessionId || state.currentId, reply,
        userMessage: state.permissionNote || undefined,
        always: reply === "always" && state.alwaysPicks.length ? state.alwaysPicks : undefined },
    });
  } catch (e) { fail(e); }
}
async function replyQuestion(answers) {
  const req = state.question; if (!req) return;
  state.question = null;
  const body = answers == null
    ? { sessionId: req.sessionId || state.currentId, answers: null, reject: true }
    : { sessionId: req.sessionId || state.currentId,
        answers: answers.map((a, i) => {
          const custom = (state.customs[i] || "").trim();
          if (custom) return req.questions[i].multiSelect
            ? custom.split(/[,，]/).map((s) => s.trim()).filter(Boolean) : custom;
          return a;
        }) };
  try { await api("/api/questions/" + encodeURIComponent(req.id) + "/reply", { json: body }); }
  catch (e) { fail(e); }
}

/* ---------------- 面板 / 顶栏 ---------------- */
const PANELS = { status: "状态", todos: "Todos", tasks: "Tasks", tools: "工具", skills: "Skills", model: "模型" };
function togglePanel(p) {
  state.panel = state.panel === p ? null : p;
  if (state.panel) loadPanel(p);
}
async function loadPanel(p) {
  if (!state.currentId) return;
  try {
    if (p === "status") await refreshStatus();
    else if (p === "todos") state.panelData.todos = await api("/api/sessions/" + sid() + "/todos");
    else if (p === "tasks") state.panelData.tasks = await api("/api/sessions/" + sid() + "/tasks");
    else if (p === "tools") state.panelData.tools = await api("/api/sessions/" + sid() + "/tools");
    else if (p === "skills") state.panelData.skills = await api("/api/skills?sessionId=" + sid());
  } catch (e) { state.panelData[p] = []; if (p !== "status") fail(e); }
}
async function refreshStatus() {
  if (!state.currentId) return;
  try {
    const d = await api("/api/sessions/" + sid() + "/status");
    state.rawStatus = JSON.stringify(d, null, 2);
    applySessionUpdate(d.status ? d : { status: d });
  } catch (e) { /* 状态接口失败不弹窗 */ }
}
async function loadModels() {
  try {
    const d = await api("/api/models");
    state.models = (Array.isArray(d) ? d : d.models || []).map((m) =>
      typeof m === "string" ? { id: m, name: m }
        : { id: m.qualifiedId || m.id || m.modelId,
            name: m.name || m.qualifiedId || (m.providerId ? m.providerId + "/" + m.model : m.model || m.id),
            provider: m.provider || m.providerId });
    if (!state.model && d.current) state.model = d.current.id || d.current;
  } catch (e) { fail(e); }
}
async function setModel() {
  if (!state.currentId || !state.model) return;
  try { await api("/api/sessions/" + sid() + "/model", { json: { model: state.model } }); }
  catch (e) { /* 端点未实现时静默 */ }
}
async function setPermissionMode() {
  if (!state.currentId) return;
  try { await api("/api/sessions/" + sid() + "/permission-mode", { json: { mode: state.permissionMode } }); }
  catch (e) { /* 端点未实现时静默 */ }
}

async function refreshSkills() {
  try {
    const r = await api("/api/skills/refresh?sessionId=" + sid(), { method: "POST" });
    state.panelData.skills = r.snapshot || r;
  } catch (e) { fail(e); }
}

/* ---------------- Vue 应用 ---------------- */
createApp({
  setup() {
    onMounted(() => {
      if (!token()) { state.needToken = true; return; } // 无 token → 弹登录遮罩，验证通过再拉数据
      loadSessions(); loadModels();
    });
    return {
      // 状态（reactive 直接展开）
      ...Vue.toRefs(state),
      panels: Object.keys(PANELS), panelLabels: PANELS,
      // computed
      selectedProvider: computed(() => selectedProvider()),
      needKeyInput: computed(() => {
        const pr = selectedProvider();
        return !!pr && !pr.apiKeyMasked;
      }),
      statusText: computed(() => {
        const map = { idle: "空闲", busy: "运行中", retry: "重试", compacting: "压缩中", cancelled: "已停止" };
        return map[state.status] || state.status;
      }),
      totals: computed(() => {
        let input = 0, output = 0, cost = 0;
        for (const m of state.messages) for (const p of m.parts) if (p.kind === "step-finish") {
          input += num(p.tokens.input); output += num(p.tokens.output); cost += p.cost;
        }
        return { input, output, cost: "$" + cost.toFixed(4) };
      }),
      // 动作
      md: mdLite, onPickSession, selectSession, newSession, send, cancel, onKeydown,
      replyPermission, replyQuestion, togglePanel, setModel, setPermissionMode, submitToken,
      startRename, commitRename, cancelRename, refreshSkills,
      pickProvider, applyProviderEnter, finishAuth,
    };
  },
  mounted() {
    scroller = this.$refs.scroller; inputEl = this.$refs.input;
    if (state.needToken && this.$refs.tokenInput) nextTick(() => this.$refs.tokenInput.focus());
    scrollBottom();
  },
}).mount("#app");
