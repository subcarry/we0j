# We0J 需求规格说明书（SRS）

- **文档版本**：v1.0
- **对标项目**：[We0 Code](https://github.com/Mashiro2000/We0Code)（Python 3.12，145,637 LOC）
- **实现语言**：Java 21 LTS（虚拟线程） + Spring Boot 3.4+
- **交付形态**：单进程可执行 Jar（CLI）+ 内嵌 Web 控制台
- **配套文档**：`02-详细设计文档-DDD.md`

---

## 目录

1. [产品定位与目标](#1-产品定位与目标)
2. [用户角色与运行环境](#2-用户角色与运行环境)
3. [核心用例](#3-核心用例)
4. [功能需求](#4-功能需求)
5. [非功能需求](#5-非功能需求)
6. [数据需求](#6-数据需求)
7. [外部接口需求](#7-外部接口需求)
8. [验收标准（DoD）](#8-验收标准dod)
9. [里程碑与迭代计划](#9-里程碑与迭代计划)
10. [风险登记册](#10-风险登记册)
11. [需求追踪矩阵](#11-需求追踪矩阵)

---

## 1. 产品定位与目标

### 1.1 产品定位

We0J 是一个**面向真实软件工程任务的 Coding Agent 运行时**：用户在终端或浏览器里用自然语言下达任务，Agent 自主读取代码、检索、编辑文件、执行命令、验证结果，直到任务完成或用户中断；全过程可流式观测、可授权管控、可断点恢复、可回滚。

### 1.2 项目目标（可度量）

| 编号 | 目标 | 度量方式 |
|---|---|---|
| G-01 | 功能对等：复现原项目核心 MVP 的 17 个内置工具与双循环内核 | 需求追踪矩阵 100% 覆盖 P0 项 |
| G-02 | 自举闭环：We0J 能用自己开发自己（用 We0J 修 We0J 的 bug） | 至少完成 5 个真实自举 commit |
| G-03 | 流式体验：首 token 到屏延迟 ≤ 300ms（网络除外） | CLI/Web 打点统计 P95 |
| G-04 | 长任务韧性：进程重启后可恢复未完成会话并续跑 | 杀进程 → 重启 → resume 测试用例通过 |
| G-05 | 上下文经济性：Anthropic 场景提示词缓存命中率 ≥ 90% | usage 的 `cache_read/total` 统计 |
| G-06 | 架构改进：消除原项目 core↔we0 的双向循环依赖 | 依赖检查脚本（ArchUnit）零违规 |

### 1.3 非目标（Non-Goals）

- 不做多用户/多租户服务端（单进程单用户）
- 不做 IM 陪伴 Agent（Telegram/语音/图片/音乐/情绪系统全部剔除）
- 不做 Team 多 Agent 协作（保留接口，不实现）
- 不做 ACP 协议（保留 JSON-RPC 基础设施，不实现 ACP 语义）
- 不做全屏 TUI（降级为行式 CLI + Web 控制台）
- 不复刻 litellm 的"全 Provider 兼容层"，只支持 Anthropic / OpenAI / OpenAI 兼容 / Gemini 四类

---

## 2. 用户角色与运行环境

### 2.1 角色

| 角色 | 描述 | 关注点 |
|---|---|---|
| **开发者（主用户）** | 在本地仓库中使用 We0J 完成编码任务 | 交互流畅、结果可信、可回滚 |
| **审批者** | 同一人的"授权态"：对敏感操作（写文件、执行命令）做 allow/deny 决策 | 权限提示清晰、可批量 always |
| **运维/观察者** | 通过 Web 控制台观察会话、任务、用量 | 状态可视、可干预（中断/停止任务） |

### 2.2 运行环境

| 项 | 要求 |
|---|---|
| JDK | Java 21 LTS（`--enable-preview` 不使用） |
| OS | Windows 10+ / macOS 13+ / Linux（glibc 2.31+） |
| 外部二进制 | `git` ≥ 2.30（必需）；`ripgrep`（可选，缺失时降级为 Java NIO 实现） |
| 存储 | 用户目录 `~/.we0j/` 可写；单会话 DB < 50MB |
| 网络 | 可访问模型 Provider API；支持 HTTP(S) 代理与 SOCKS 代理 |
| 内存 | 堆 ≥ 512MB（虚拟线程栈不占堆） |

### 2.3 目录约定

```
~/.we0j/
├── settings.json                    用户级全局配置
├── skills/                          全局 Skill
├── commands/                        全局自定义 slash command（markdown）
├── agents/                          全局 agent 人格定义（markdown）
├── providers.json                   provider/model 基础设施配置（独立存储，不进 profile）
├── projects/<projectHash>/
│   ├── runtime.db                   SQLite 会话库（每项目独立）
│   ├── snapshot/                    shadow git 目录（--git-dir）
│   ├── tool_output/                 工具输出落盘
│   │   ├── shell/<shellId>.output
│   │   └── agents/<agentId>.output  （JSONL）
│   ├── sessions/<sessionId>/
│   │   ├── todos.json
│   │   ├── tasks.json
│   │   └── crons.json
│   └── logs/
<project>/.we0j/settings.json         项目级配置（覆盖用户级）
```

---

## 3. 核心用例

### UC-01 首次启动与配置

**主流程**
1. 用户执行 `we0j`（无参数）
2. 系统检测 `~/.we0j/settings.json` 不存在 → 从内置模板完整创建
3. 系统检测 `providers.json` 不存在 → 创建含 `anthropic`(enabled) / `openai`(disabled) / `gemini`(disabled) 的模板
4. 系统检测 `common.mcpServers` 为空 → 从默认模板补齐全部内置工具配置
5. 进入 CLI REPL，打印 logo、当前模型、工作目录、权限模式
6. 用户执行 `/provider` → 系统提示在 Web 控制台完成填写，并自动打开浏览器 `http://localhost:8787/#/provider`

**备选流程**
- 2a. 配置已存在 → 保留原配置，仅在 `mcpServers` 为空时补齐（**不得覆盖用户已有配置**）

**验收**：重复启动不丢配置；缺 API key 时给出可操作的错误提示而非堆栈。

### UC-02 提出编码任务并完成

**主流程**
1. 用户在 REPL 输入 `把 UserService 里的同步调用改成虚拟线程并发`，回车
2. 系统创建 `UserMessage`，落库，Bus 发 `message.updated`
3. 系统进入 Agent Loop：构建系统提示词 → 解析可用工具 → 注入 reminder → 请求模型
4. 模型流式返回 reasoning → CLI 以暗色实时打印；返回 text → 正常打印
5. 模型请求 `Grep(UserService)` → 权限规则匹配为 `allow` → 直接执行 → 结果作为 `role:tool` 回灌
6. 模型请求 `Read(...)` → 执行
7. 模型请求 `Edit(path, old, new)` → 权限为 `ask` → CLI 弹出授权提示，展示 unified diff
8. 用户选 `always` → 系统将 `edit: <path pattern>` 写入 session 运行时规则并持久化到项目 settings，本次及后续同类请求自动放行
9. 工具执行完毕，写入文件，触发 LSP 诊断（若启用），结果回灌模型
10. 模型输出总结文本，`finish_reason=stop`，Loop 退出
11. CLI 打印本轮 token/成本用量，恢复输入态

**验收**：全过程流式无卡顿；任意步骤按 `Esc` 可中断且状态一致；重启后 `/resume` 能看到完整历史。

### UC-03 中断与恢复

**主流程**
1. 长任务执行中，用户按 `Esc`
2. 系统置位 `AbortSignal` → 取消进行中的 HTTP 流 → 杀死运行中的子进程树 → 取消排队工具
3. 系统删除本轮未完成的 Part，写入 `[Request interrupted by user]` 文本 Part，`AssistantMessage.timeCompleted` 落库
4. 用户 `Ctrl+C` 退出进程
5. 用户重新执行 `we0j --resume <sessionId>`（或 REPL 内 `/resume`）
6. 系统从 DB 重建消息历史、Todo、Task、已激活的延迟工具、运行时权限规则
7. 若检测到上次有未完成的后台任务 → 提示"是否继续"

**验收**：中断不产生半截 JSON 落库；resume 后继续对话，模型能理解上文被中断。

### UC-04 长会话上下文压缩

**主流程**
1. 会话累积至 `usage.total >= contextWindow - buffer`
2. 系统在本轮 `finish-step` 后判定需压缩，返回 `compact`
3. 系统规划保留尾部（近 N 条消息），对前缀做媒体清洗（剥离图片/附件、截断超长工具输出）
4. 系统用隐藏的 compaction agent（受限子会话，仅文本输出）生成结构化摘要
5. 若摘要请求本身 prompt too long → 按 API round 分组截头重试
6. 系统写入 `CompactionPart`（挂在合成 UserMessage 上）作为压缩边界
7. 压缩后恢复：重新注入 plan 文件、最近编辑文件列表、已调用 skill、task 状态、已发现的延迟工具
8. Loop `continue`，下一轮 `filter_compacted()` 只取边界之后的消息

**验收**：压缩后会话可继续；连续失败 3 次触发熔断并明确告知用户；压缩后 token 显著下降。

### UC-05 后台子 Agent

**主流程**
1. 模型调用 `Agent(subagent_type="explore", prompt="找出所有调用 X 的地方", run_in_background=true)`
2. 系统解析 agent 人格 → 创建子 Session（`parent_id` = 当前 session）
3. 系统给子会话设置工具 overlay：屏蔽 `Agent` / `TeamCreate` / `TeamDelete`；注入更严格的运行时权限规则
4. 系统在后台虚拟线程启动子 Loop，输出以 JSONL 追加落盘到 `tool_output/agents/<id>.output`
5. 父会话立即拿到 `agentId`，继续自己的轮次
6. 子 Agent 完成 → 生成 `TaskNotification` → `push_or_resume(parentSessionId)`：
   - 父会话**忙** → 入队，父 Loop 在下一轮中段 drain，作为 `<task-notification>` reminder 注入
   - 父会话**闲** → 写入合成 `UserMessage` 落库 + 唤醒父 Loop
7. 用户可用 `TaskOutput(task_id, block=true)` 阻塞等待，或 `TaskStop` 终止

**验收**：子 Agent 的权限请求能冒泡到父会话 UI；并发上限（10）生效；停止后子进程与流全部释放。

### UC-06 代码回滚

**主流程**
1. 用户 `/rewind` → Web 控制台列出可回滚锚点（每个 UserMessage 及其 diff 摘要）
2. 用户选择锚点 + 模式：`仅对话` 或 `对话与代码`
3. `仅对话`：系统标记 revert 边界（messageId/partId），后续读取时过滤，`cleanup` 时物理删除
4. `对话与代码`：系统先 `Snapshot.track()` 保存当前 tree hash 到 `revert.snapshot`（供撤销回滚），再对每个变更文件 `git checkout <hash> -- <file>`；文件不在快照中则删除
5. 用户可 `unrevert` → 系统用保存的 hash `restore` 恢复代码

**验收**：回滚后工作区与快照一致；unrevert 可完全还原；回滚不影响未涉及的文件。

### UC-07 Plan 模式

1. 用户 `/mode plan` 或模型调用 `EnterPlanMode`
2. 系统切换 agent 人格为 `plan`，注入 plan 模式上下文，工具集收敛为只读（Read/Grep/Glob/LSP）
3. 模型产出计划 → 调用 `ExitPlanMode` → 系统请求用户审批（权限 `plan_exit`）
4. 用户批准 → 切回 `build` 人格 → **Loop 主动 break 重启**，重读历史以加载新人格与工具集

### UC-08 Web 控制台观察与干预

1. 用户浏览器打开 `http://localhost:8787`
2. 建立 SSE 连接 `GET /api/sessions/{id}/events`
3. 实时看到消息流、Part 增量、工具卡片状态流转、用量更新
4. 面板：`/model` 切模型、`/provider` 配密钥、`/config` 改配置、`/tasks` 看后台任务并停止、`/resume` 选会话、`/rewind` 回滚、`/status` 看状态、`/mcp` 看工具
5. 权限与提问弹窗在 Web 上冒泡，用户点击回复 → `POST /api/permissions/{id}/reply`

---

## 4. 功能需求

> 优先级：**P0** = MVP 必须；**P1** = MVP 应有；**P2** = 预留接口后续实现。
> 每条需求含：描述、详细规则、验收标准（AC）。

### 4.1 FR-01 会话管理

#### FR-011 会话创建 `P0`
- **描述**：为指定工作目录创建一个新会话。
- **规则**：
  - `id` = ULID（时间有序、26 字符、可排序），不用 UUIDv4（便于按时间扫描）
  - 必填字段：`id, projectId, directory, title, version, timeCreated, timeUpdated`
  - `title` 初始为 `"New Session - <yyyy-MM-dd HH:mm>"`；首轮结束后异步生成语义标题（用 `fast` 档模型）
  - `projectId` = 工作目录规范化绝对路径的 SHA-256 前 16 位
  - `parent_id` 为空表示主会话；非空表示子 Agent 会话
  - `is_incognito` = true 的会话不进 `/resume` 列表（用于压缩、标题生成、记忆提取等内部子会话）
- **AC**：并发创建 100 个会话无 id 冲突；DB 中 `session` 表字段完整。

#### FR-012 会话列表与检索 `P0`
- **规则**：按 `projectId` 过滤，`timeUpdated` 倒序，分页（默认 50）；排除 `is_incognito=true`；支持按标题模糊搜索。
- **AC**：`/resume` 面板 200 个会话内首屏渲染 < 100ms。

#### FR-013 会话加载与恢复 `P0`
- **规则**：
  - 从 DB 读取全部 Message + Part，按 `timeCreated` 及插入序还原
  - 恢复项：Todo 列表、TaskV2 列表、已激活的延迟工具集合、session 运行时权限规则、当前 agent 人格、当前权限模式、pending revert
  - 若目标会话**正在运行**（同进程内）→ 不新建 Loop，而是 attach 到既有 Loop 的完成 Future（对齐原项目 `_wait_for_active_session`）
  - 若检测到上次存在未完成后台任务 → 生成提示但不自动重跑
- **AC**：kill -9 后重启，resume 能还原全部可见状态；不产生重复 Loop。

#### FR-014 会话状态机 `P0`
- **状态**：`Idle` / `Busy` / `Retry(attempt, delayMs, reason)` / `Compacting` / `Cancelled`
- **规则**：状态变更必须发 Bus 事件 `session.updated`；同一会话同时只允许一个 Loop（互斥由 `SessionRegistry` 保证）。
- **AC**：并发对同一会话提交两次 prompt，第二次 attach 而非并行执行。

#### FR-015 会话删除与归档 `P1`
- **规则**：删除 = 级联删 message/part 行 + 删会话目录文件；归档 = 置 `timeArchived`，从默认列表隐藏。

#### FR-016 会话派生（fork） `P1`
- **规则**：从指定 messageId 复制历史到新会话，新会话 `parent_id` 为空但 `metadata.forkedFrom` 记录来源。

---

### 4.2 FR-02 Agent Loop 内核

#### FR-021 双循环结构 `P0`
- **描述**：实现"外层历史驱动循环 + 内层单轮流式循环"。
- **规则**（严格对齐原项目语义）：
  - 外层每轮顺序：`置 Busy` → `检查 abort` → `读历史(stream_messages)` → `filter_compacted` → `extract_messages/markers` → `退出判定` → `压缩任务处理` → `溢出检查` → `上下文构建` → `建 AssistantMessage + TurnProcessor` → `processor.process()` → `工具子循环`
  - **状态无内存缓存**：每轮的 `last_user` / `last_assistant` / `last_finished` / `step` 全部从 DB 历史重新推导（这是原项目最关键的设计——保证 resume/revert/压缩后状态自洽）
  - 内层返回值三态：`CONTINUE`（有工具调用需继续）/ `STOP`（终止）/ `COMPACT`（需压缩后重来）
- **AC**：单元测试覆盖：正常完成、工具循环 3 轮、中途 abort、压缩触发、模式切换重启。

#### FR-022 退出条件 `P0`
- **规则**，满足任一即 break：
  1. `_hasCompletedReplyForLastUser()` == true：最后一条 UserMessage 之后已存在 `timeCompleted != null` 且 `error == null` 的 AssistantMessage
  2. 内层返回 `STOP`（模型错误、权限 deny 且 `continueLoopOnDeny=false`）
  3. 模式切换需重启（`_shouldRestartAfterModeSwitch`）→ break 后由外层调用方重新进入
  4. 队列中有新的用户消息 → break 以重启外层（保证新输入被纳入历史推导）
  5. `abort` 已置位
  6. 达到 `loop.maxSteps` 配置（默认 200；原项目注册但未强制执行，**Java 版强制执行并提示用户**）
- **AC**：不得出现无限循环；达到 maxSteps 时写入明确的用户可见提示 Part。

#### FR-023 Step 语义与快照锚点 `P0`
- **规则**：
  - 每个 step 开始时写 `StepStartPart{snapshot: <treeHash>}`，结束时写 `StepFinishPart{snapshot, cost, tokens}`
  - tree hash 由 `SnapshotService.track()` 产出（`git add -A` + `write-tree`，**不 commit**）
  - `step` 计数在同一次 prompt 内单调递增，跨 prompt 由历史推导重置
- **AC**：任意两个 step 之间可精确 diff 出文件变更集。

#### FR-024 中断（Abort）传播 `P0`
- **规则**：中断必须级联到以下全部位置，且**不得留下半截状态**：
  - 进行中的模型 SSE 流（取消 HTTP call）
  - 重试退避 sleep（立即唤醒）
  - 每个工具的 `AbortSignal`（子级信号）
  - 运行中的子进程（杀进程树）
  - 等待权限/提问回复的 Future（以 `AbortedException` 完成）
  - 后台子 Agent（取消其 Loop）
- **中断后清理**：删除本轮所有未完成 Part（`tool` 状态为 pending/running 的、`text`/`reasoning` 未闭合的），写入单个 `TextPart("[Request interrupted by user]")`，`AssistantMessage.timeCompleted = now`，`error = MessageAbortedError`
- **AC**：在流式输出、工具执行、权限等待、子进程运行四种时机分别按 Esc，DB 状态均自洽；无僵尸进程（`ProcessHandle.descendants()` 为空）。

#### FR-025 并发工具执行 `P0`
- **规则**：
  - 同一 step 内模型返回的多个 tool_calls **并发执行**（虚拟线程 per task）
  - 单个工具异常**不影响**其他工具（对齐 `gather(return_exceptions=True)`）：异常被捕获转为 `ToolStateError`，错误文本作为 `role:tool` 内容回灌模型
  - 执行完成后按 `tool_call_id` 顺序追加结果，保证回灌消息顺序与请求顺序一致
  - **工具在流结束后才执行**，不做"边流边执行"（P2 优化项，见 FR-026）
- **AC**：3 个各耗时 2s 的独立工具，总耗时 ≈ 2s 而非 6s；一个抛异常其余正常返回。

#### FR-026 工具参数就绪即执行 `P2`（预留）
- **描述**：原 We0 SDK 的优化——某 tool_call 的 arguments JSON 完整解析成功后即可提前执行，不等整条流结束。
- **MVP 处理**：仅定义接口 `EarlyToolDispatcher`，默认实现为 no-op（等流结束）。

#### FR-027 泳道（Lane）与门控 `P0`
- **规则**：
  - `RuntimeLane` 枚举：`MAIN` / `SIDE_LLM` / `SIDE_AGENT`
  - 通过 `ThreadLocal<RuntimeLane>` 传递（虚拟线程下每线程独立，安全）；启动子任务时**显式传递**，不依赖 inheritable
  - 门控规则：自动压缩、微压缩、标题生成、记忆提取等"会话级副作用决策"**仅 `MAIN` 泳道可触发**（`RuntimeGate.mainAgentOnlyDecision()`），避免子 Agent 压缩父会话上下文
- **AC**：子 Agent 长会话不会触发父会话压缩。

#### FR-028 排队输入与 mid-turn 注入 `P1`
- **规则**：Loop 运行中用户再次提交输入 → 入 `queuedInputs` 队列；Loop 在工具子循环的间隙 drain，作为补充 user content 注入（原项目 `prompt.py:1656`），而不是打断当前轮。
- **AC**：连发 3 条消息，全部被处理且不丢失顺序。

---

### 4.3 FR-03 模型 Provider 与流式

#### FR-031 Provider 抽象 `P0`
- **描述**：统一 Anthropic Messages API、OpenAI Chat Completions、OpenAI Responses API、OpenAI 兼容网关、Gemini。
- **规则**：
  - **自研 SSE 解析与事件映射，不使用任何 Agent 框架的封装**（LangChain4j / Spring AI 均禁止用于此层）
  - 统一输出 16 种 `StreamEvent`（见 FR-032）
  - Provider 特定的额外字段保留在 `providerMetadata: Map<String,Object>` 中，不丢失
- **AC**：同一份上层 Loop 代码，切换 Anthropic/OpenAI/Gemini 无需任何改动。

#### FR-032 流式事件归一化 `P0`
- **事件集**（严格对齐原项目 `llm.py:704-830`）：

| 事件 | 字段 | 语义 |
|---|---|---|
| `start` | — | 流开始 |
| `start-step` | — | 一个 step 开始 |
| `reasoning-start` | `id, providerMetadata` | 思考块开始 |
| `reasoning-delta` | `id, text, providerMetadata` | 思考增量 |
| `reasoning-end` | `id, providerMetadata` | 思考块结束 |
| `text-start` | `id, providerMetadata` | 正文块开始 |
| `text-delta` | `id, text, providerMetadata` | 正文增量 |
| `text-end` | `id, providerMetadata` | 正文块结束 |
| `tool-input-start` | `id, toolName, toolCallId, providerMetadata` | 工具调用开始 |
| `tool-input-delta` | `id, delta, providerMetadata` | 工具参数 JSON 片段 |
| `tool-input-end` | `id, providerMetadata` | 工具参数结束 |
| `tool-call` | `toolCallId, toolName, input(Map), providerMetadata` | 参数解析完成，可执行 |
| `tool-result` | `toolCallId, toolName, input, output` | 由外层 Loop 注入（非模型流） |
| `tool-error` | `toolCallId, toolName, input, error` | 由外层 Loop 注入 |
| `finish-step` | `finishReason, usage(Map), providerMetadata` | step 结束 |
| `finish` | `finishReason, totalUsage(Map)` | 整流结束 |
| `error` | `error(Throwable)` | 流错误 |

- **Anthropic 映射规则**：
  - `message_start` → `start` + `start-step`，usage 取 `input_tokens` / `cache_creation_input_tokens` / `cache_read_input_tokens`
  - `content_block_start`：`type=thinking` → `reasoning-start`；`type=text` → `text-start`；`type=tool_use` → `tool-input-start`（`toolCallId = content_block.id`）
  - `content_block_delta`：`thinking_delta` → `reasoning-delta`；`text_delta` → `text-delta`；`input_json_delta.partial_json` → `tool-input-delta`；`signature_delta` → 累积到 thinking 签名（用于 redacted_thinking 回传校验）
  - `content_block_stop` → 对应 `-end` 事件
  - `message_delta` → 累积 `usage.output_tokens`，`stop_reason` → `finish-step`
  - `message_stop` → `finish`
  - `error` → `error`；`ping` → 忽略
- **OpenAI Chat Completions 映射规则**：
  - `delta.reasoning_content`（或 `delta.reasoning`）→ `reasoning-delta`
  - `delta.content` → `text-delta`
  - `delta.tool_calls[i]`：以 `index` 为键累积；首次出现 `id` → `tool-input-start`；`function.name` 累积工具名；`function.arguments` 片段 → `tool-input-delta`
  - `finish_reason == "tool_calls"` → 对每个累积 buffer 执行 `objectMapper.readValue(...)` → 成功发 `tool-call`，失败抛 `MalformedToolArgumentsException`（带原始串，便于排障）
  - `finish_reason == "stop" / "length" / "content_filter"` → `finish-step`
  - 尾 chunk 的 `usage`（需请求带 `stream_options.include_usage=true`）→ `finish(totalUsage)`
  - `data: [DONE]` → 流终止
- **AC**：针对三家 Provider 各录制一份真实 SSE 报文做 fixture，回放测试断言事件序列完全一致。

#### FR-033 请求组装与消息归一化 `P0`
- **规则**：
  - 历史 → Provider 消息格式转换：Part 序列折叠为 content blocks（`text`/`reasoning`/`tool_use`/`tool_result`）
  - **Anthropic 专属清洗**：剔除空 text/reasoning block；`tool_call_id` 正则清洗 `[^a-zA-Z0-9_-]` → `_`
  - **OpenAI 专属**：`role:tool` 消息必须紧跟在含对应 `tool_calls` 的 assistant 之后，否则报 400 → 转换器需做配对校验与修复
  - 系统提示词作为 `List<String>` 多块传递（Anthropic 支持 system 数组，用于缓存打点）
  - `drop_params` 语义：Provider 不支持的参数（如 Anthropic 无 `frequency_penalty`）自动丢弃并记录 WARN，不报错
- **AC**：构造含 5 种 Part 类型的历史，三家 Provider 请求体均通过 schema 校验。

#### FR-034 提示词缓存 `P0`
- **规则**：
  - 策略枚举 `CacheStrategy { DEFAULT, OFF, LAST_USER_ONLY }`
  - `DEFAULT`：在**前 2 个 system block** 与**末 2 条非 system 消息**的最后一个可缓存 content block 上打标记
  - `LAST_USER_ONLY`：仅在 system + 最后一条 user 上打标记。**用途**：压缩、标题生成等旁路调用复用主会话缓存前缀，避免击穿缓存
  - Anthropic 标记：`{"cache_control":{"type":"ephemeral"}}`；Bedrock 变体：消息级 `{"cache_point":{"type":"default"}}`
  - OpenAI / Gemini：无需显式标记（自动缓存），跳过
  - 命中核算：从 usage 多路取值 `cache_read_input_tokens` / `prompt_tokens_details.cached_tokens` / `cache_creation_input_tokens`，按 provider 优先级 first-present 归一到 `Tokens.cache.read/write`
  - `adjustedInput = promptTokens - cacheRead - cacheWrite`
  - 冷启动判定：`cacheWrite / total > 0.5` → 视为 cold（用于统计与告警）
- **AC**：连续 3 轮对话，第 2、3 轮 `cache.read > 0` 且命中率 ≥ 90%；旁路调用不导致主缓存失效。

#### FR-035 成本计算 `P1`
- **规则**：`BigDecimal` 精确计算，单价按"每百万 token"配置；输入/输出/缓存读/缓存写四档单价；上下文 > 200K 时切换 `experimentalOver200K` 价档；结果保留 6 位小数。
- **AC**：与 Provider 账单误差 < 1%。

#### FR-036 重试策略 `P0`
- **规则**：
  - 指数退避：`delay = min(maxDelay, baseDelay * 2^(attempt-1))`，`baseDelay=2000ms`，`maxDelay=30000ms`
  - **优先解析响应头**：`retry-after-ms` > `retry-after`（支持秒数与 HTTP-date 两种格式）；若 header 给出的值 > maxDelay，**采用 header 值**（尊重服务端）
  - 可重试异常：HTTP 429 / 500 / 502 / 503 / 504 / `IOException`（连接重置、超时）/ `ServiceUnavailable`
  - **不可重试**：`ContextOverflowError`（转压缩流程）、401/403（鉴权）、400（请求非法）、`MalformedToolArgumentsException`
  - 特殊错误码识别：`insufficient_quota`（提示充值）、`too_many_requests`、`invalid_prompt`
  - 最大尝试次数：5（可配）
  - **空流重试**：模型返回空流（无任何 event）时单独重试，`EMPTY_STREAM_MAX_RETRIES=3`，间隔 500ms
  - 重试期间状态置 `Retry(attempt, delayMs, reason)` 并发 Bus 事件供 UI 展示倒计时
  - 重试的 sleep 必须响应 abort
- **副作用安全**：重试只发生在**模型请求层**，工具执行之后不重试模型（避免重复副作用）；工具自身的重试由各工具决定，写类工具（Write/Edit/Bash）**默认不重试**
- **AC**：模拟 429 带 `retry-after: 3`，实际等待 ≈3s 后成功；模拟持续 500 达 5 次后终止并给出清晰错误。

#### FR-037 上下文溢出识别 `P0`
- **规则**：维护溢出错误特征库（≥13 条正则 + marker 表），覆盖各家 "prompt is too long" / "context_length_exceeded" / "maximum context length" 等文案；命中即抛 `ContextOverflowError`（携带原始 response body 供排障）。
- **AC**：三家 Provider 的溢出报文均被正确分类为 `ContextOverflowError` 而非通用 `APIError`。

#### FR-038 Token 计数与上下文窗口 `P0`
- **规则**：
  - **真实值优先**：以 Provider usage 为准
  - **本地估算兜底**：使用 `jtokkit`（`o200k_base` 编码）精确计数；无法识别的模型退化为 `text.length()/4` 粗估
  - 上下文窗口解析顺序：显式 override → 内置模型信息表（`max_input_tokens` / `max_tokens`）→ 保守默认 32K
  - 内置模型信息表需带 LRU 缓存（4096 条）
  - `maxOutputTokens` 同源解析
- **AC**：估算值与 Provider 真实 usage 偏差 < 10%。

#### FR-039 模型档位（Tier） `P1`
- **规则**：`fast` / `pro` / `max` 三档，内部任务按性质选档：标题生成、记忆提取、压缩摘要 → `fast`；主对话 → `default`；子 Agent 可按人格指定。
- **onMissing 策略**：`error`（默认，配置的模型不可用直接报错）/ `fallback`（回退到 default）。

#### FR-040 Reasoning / Thinking 控制 `P1`
- **规则**：
  - Anthropic：`thinking: {type:"enabled", budget_tokens:N}`，并按需附加 beta 头（`interleaved-thinking-2025-05-14` 等）
  - OpenAI：`reasoning_effort: minimal|low|medium|high`
  - 可见性配置 `reasoningVisibility: show|hide`，`hide` 时仍持久化但不推送 UI
  - thinking block 必须在后续请求中**原样回传**（含 signature），否则 Anthropic 报错
- **AC**：开启 thinking 的多轮工具调用不报 400。

---

### 4.4 FR-04 上下文工程

#### FR-041 系统提示词装配 `P0`
- **规则**：
  - 分块装配，块顺序固定（顺序决定缓存前缀稳定性）：
    1. **核心人格块**：按模型家族选择（`CORE_ANTHROPIC` / `CORE_CODEX` / `CORE_GEMINI` / `CORE_BEAST`），匹配规则为 `modelId` 子串
    2. **环境信息块**：OS、架构、shell、工作目录、是否 git 仓库、当前分支、日期时间、项目根
    3. **Agent 人格块**：当前 agent（build/plan/explore/自定义）的 markdown 正文
    4. **团队块**（MVP 恒为空，保留位）
    5. **语言偏好块**：`common.language` 配置
    6. **记忆机制块**（MVP 为文件记忆说明，可空）
  - **块级缓存**：环境块按 `(workdir, gitBranch, dateHour)` 做 key 缓存；核心人格块进程级缓存；**缓存不得破坏前缀稳定性**（同一会话内块内容与顺序必须一致）
  - 支持显式缓存点声明：每块可标 `cacheBreakpoint: true`
- **AC**：同一会话连续 5 轮，system block 字节完全一致（除环境块的小时级变化）。

#### FR-042 Reminder 注入 `P0`
- **描述**：将运行时动态信息以**合成 TextPart 挂在 user message 上**的方式注入，而非塞进 system prompt（避免击穿缓存）。
- **注入类型与顺序**（对齐 `loop_helper.py:1782`）：

| # | source 标识 | 内容 | 持久化 |
|---|---|---|---|
| 0 | `agents_md` | 项目 `AGENTS.md` 内容锚点 | ✅ 持久 |
| 1 | `memory_prefix` | 长期记忆前缀（MVP 可为空） | ✅ 持久 |
| 1a | `available_deferred_tools` | `<available-deferred-tools>` 延迟工具名清单 | 内存态（每轮重建） |
| 1b | `mcp_instructions` | 外部 MCP server 的 instructions（`<system-reminder>` 包裹） | 内存态 |
| 2 | `background_notification` | `<task-notification>` 后台任务完成通知 | 内存态（drain 后消费） |
| 3 | `skills` | `<system-reminder>` 可用 skill 的 name + description 清单 | 内存态（热更新刷新） |
| 4 | `followup_wrapper` | "user sent another message" 追加输入包装 | 内存态 |
| 5 | `plan_mode_switch` | plan/build 模式切换说明（由 `insertReminders` 处理） | ✅ 持久 |
| 6 | `teammate_context` | 团队上下文 + 邮箱（MVP 恒空，保留位） | 内存态 |

- **去重规则**：每个 source 仅保留最新一条（`latestSyntheticTextForSource`）；持久化类型写库（`Session.updatePart`），内存态类型仅本轮附加
- **AC**：同一 source 不重复注入；压缩后 reminder 能正确重建。

#### FR-043 Plan/Build 模式切换上下文 `P1`
- **规则**：模式切换时注入说明性 reminder；切换后 Loop 主动 break 重启以重读历史、重载人格与工具集。

#### FR-044 Context Contributor 扩展点 `P0`（架构改进）
- **描述**：将 reminder 注入抽象为 `ContextContributor` SPI，按 `order()` 排序，每个 contributor 声明 `source()`、`persistence()`、`appliesTo(lane, mode)`。
- **动机**：原项目 `inject_system_reminders()` 是一个 400+ 行的巨型函数且混入 IM 语义；Java 版拆分后可插拔、可测试、无 IM 耦合。
- **AC**：新增一类 reminder 只需新增一个 `@Component`，不改核心代码。

---

### 4.5 FR-05 上下文压缩

#### FR-051 溢出判定 `P0`
- **规则**：
  - `isOverflow(usage, card)`：`usage.total >= card.contextWindow - min(COMPACTION_BUFFER, card.maxOutput) - autoBufferTokens`
  - `COMPACTION_BUFFER` 默认 8000；`autoBufferTokens` 可由环境变量/配置覆盖
  - `isInputOverflow(baseline, payload)`：以历史追踪的 input token 为基线 + 本次待发送 payload 的估算值
  - 判定时机：① `finish-step` 之后（`_needsCompaction` → 返回 `COMPACT`）；② 请求发送之前（`_shouldCompactBeforeRequest`）；③ 工具结果追加之后（`_shouldCompactAfterToolResults`）
- **AC**：三个时机均能触发；不在 `SIDE_*` 泳道触发。

#### FR-052 经典摘要压缩 `P0`
- **规则**（步骤严格有序）：
  1. **尾部保留规划** `PreservedTailPlanner`：从最新消息倒序累加 token，直到达到 `tailBudget`（默认为上下文窗口的 20%），该点之前为待摘要前缀，之后为保留尾部；**必须以完整 API round 为边界切分**（不得把 assistant(tool_calls) 与其 tool results 拆开）
  2. **媒体清洗** `HistorySanitizer`：剥离 `FilePart`（图片/附件）、把超长 `ToolStateCompleted.output` 截断为前 500 + 后 500 字符并标注 `[truncated]`
  3. **摘要生成**：创建一个 `is_incognito=true` 的子会话，使用受限 agent（工具集为空、仅文本输出），system 为压缩专用提示词，user 为清洗后的前缀历史；模型档位 `fast`
  4. **摘要提示词要求产出结构化章节**：任务目标、已完成工作、关键决策、当前文件状态、待办事项、重要上下文（不得丢失路径、标识符、错误信息原文）
  5. **prompt too long 重试** `RetryPlanner.truncateHead()`：按 API round 分组，从最早的组开始整组丢弃，每次丢弃后重试，最多 3 次
  6. **写边界**：创建合成 `UserMessage`，挂 `CompactionPart{prompt, metadata: CompactionSummaryMetadata}`；`metadata` 记录 `preservedTail.messageIds`、`preservedSegment`、`preCompactDiscoveredTools`、`messagesSummarized`、`truePostCompactTokenCount`
  7. **压缩后恢复** `PostCompactionRestore`：生成 diff 并注入以下内容为新的 reminder：plan 文件内容、最近编辑的文件清单、已调用的 skill 清单、task/todo 当前状态、已发现的延迟工具（恢复激活状态）
  8. 后续所有历史读取经 `filterCompacted()`：只返回最后一个 `CompactionPart` 之后的消息（含该合成消息本身）
- **AC**：压缩后会话可继续对话且模型知道之前在做什么；压缩后 token 下降 ≥ 50%；边界切分不产生孤儿 tool_result。

#### FR-053 反应式溢出恢复 `P0`
- **规则**：捕获 `ContextOverflowError` → 删除本轮失败的 AssistantMessage 及其 Part → 调度压缩 → `continue` 外层循环
- **熔断器** `ChainGuard`：以 `chainKey`（sessionId + 触发原因）去重；`MAX_CONSECUTIVE_FAILURES = 3` 后停止自动压缩，转为向用户显式报错并建议手动 `/compact`
- **AC**：不会陷入"压缩→溢出→压缩"死循环。

#### FR-054 时间维微压缩（工具结果裁剪） `P1`
- **规则**：
  - 触发：距上次活动的空闲间隔 > `gapThresholdMinutes`（默认 10）
  - 动作：将较早的 `ToolStateCompleted` 标记 `time.compacted = now`，其 `output` 替换为占位符 `[tool output compacted; original saved at <path>]`，保留最近 N 条（默认 5）不动
  - 原始输出必须已落盘可回溯
  - 记录 `MicrocompactBoundaryMetadata{trigger:"auto", preTokens, tokensSaved, compactedToolIds, clearedAttachmentIds}`
- **AC**：裁剪不破坏 tool_call ↔ tool_result 配对；模型仍能通过 Read 找回原文。

#### FR-055 手动压缩 `P0`
- **规则**：`/compact [指令]` slash command；用户可附加指令引导摘要重点（如"重点保留数据库 schema 相关的讨论"）。

---

### 4.6 FR-06 工具体系

#### FR-061 工具清单 `P0`

| # | 工具名 | 权限名 | 说明 | MVP |
|---|---|---|---|---|
| 1 | `Read` | `read` | 读文件（支持 offset/limit、图片转 base64、PDF） | P0 |
| 2 | `Write` | `write` | 写文件（自动建父目录、读后写校验） | P0 |
| 3 | `Edit` | `edit` | 精确替换编辑（9 级策略链、唯一性校验、diff） | P0 |
| 4 | `Bash` | `bash` | 执行 shell（超时、后台、流式、落盘、杀进程树） | P0 |
| 5 | `Glob` | `glob` | 文件名模式查找（mtime 倒序，上限 100） | P0 |
| 6 | `Grep` | `grep` | 内容搜索（ripgrep，上限 100 匹配） | P0 |
| 7 | `TodoWrite` / `TodoRead` | `todowrite`/`todoread` | 待办清单读写 | P0 |
| 8 | `TaskCreate`/`TaskGet`/`TaskUpdate`/`TaskList` | `task` | 结构化任务（含 owner/blocks/blockedBy） | P0 |
| 9 | `TaskOutput` | `task_output` | 查看后台任务输出（可阻塞等待） | P0 |
| 10 | `TaskStop` | `task_stop` | 停止后台任务 | P0 |
| 11 | `AskUserQuestion` | `question` | 向用户结构化提问（1-4 问 × 2-4 选项） | P0 |
| 12 | `Agent` | `agent` | 启动子 Agent（前台/后台） | P0 |
| 13 | `EnterPlanMode`/`ExitPlanMode` | `plan_enter`/`plan_exit` | 计划模式进出 | P0 |
| 14 | `EnterWorktree`/`ExitWorktree` | `worktree` | Git worktree 隔离 | P1 |
| 15 | `SKILL` | `skill` | 按需加载 skill 正文 | P0 |
| 16 | `ToolSearch` | `tool_search` | 延迟工具发现与激活 | P1 |
| 17 | `WebFetch` / `WebSearch` | `web_fetch`/`web_search` | 网页抓取/搜索（需配置搜索服务 key） | P1 |
| 18 | `LSP` | `lsp` | 代码智能（definition/references/hover/symbol/calls） | P2 |
| 19 | `CronCreate`/`CronDelete`/`CronList` | `cron` | 会话级定时任务 | P2 |
| 20 | `TeamCreate`/`TeamDelete`/`SendMessage` | `team`/`send_message` | 团队协作 | ❌ |
| 21 | `Recall`/`OperateMemory` | `recall`/`operate_memory` | 向量语义记忆 | ❌ |

#### FR-062 工具契约 `P0`
- **规则**：
  - 定义 = `name` + `description` + `inputSchema`（JSON Schema，从 Java record 自动生成）+ `deferLoading` + `sources`（可见渠道：`cli`/`web`）+ `annotations`（`audience`：`assistant`/`user`，用于区分"给模型看的状态文本"与"给用户看的 diff"）
  - 返回 = `ToolResult{content: List<ContentBlock>, structuredContent: Map}`；`ContentBlock` 支持 `TextContent` / `ImageContent`(base64+mediaType) / `EmbeddedResource`
  - `structuredContent` 落到 `ToolStateCompleted.metadata`，供 UI 富渲染（diff 视图、搜索结果表格、agent 进度）
  - 异常语义：`ToolException`（业务错误，回灌模型）/ `PermissionDeniedException`（拒绝，按配置决定是否终止 Loop）/ `AbortedException`（中断，不视为模型错误）
  - **所有工具输出统一截断**：`MAX_TRUNCATE_LINES = 2000`、`MAX_TRUNCATE_BYTES = 50KB`；超限则截断 + 全文落盘 + 在结果尾部追加提示 `Full output saved to <path>. Use Grep/Read on the saved file.`
- **AC**：任何工具的输出都不会超过 50KB 进入上下文；落盘文件可用 Read 取回。

#### FR-063 工具注册与可见性 `P0`
- **规则**：
  - 内置工具以 Spring `@Component` + `@We0Tool(name=...)` 注册，`ToolRegistry` 启动时收集
  - 配置驱动：`common.mcpServers` 中每个条目对应一个"逻辑 server"（`builtin-read` / `builtin-edit` / …），可 `enabled: false` 关闭
  - 工具名可带命名空间前缀（挂载外部 MCP 时为 `mcp__<server>_<tool>`）
  - 过滤器：`ToolFilterConfig{allowed, rejected}`，支持 `regex:` 前缀
  - **会话级 overlay** `SessionToolOverlay`：可屏蔽（shadow）或追加工具，可挂 `canUseTool` 拦截器。子 Agent 用此屏蔽 `Agent`/`TeamCreate`/`TeamDelete`
  - 渠道过滤 `sources`：CLI 会话不可见仅 Web 工具，反之亦然
- **AC**：子 Agent 调用 `Agent` 工具得到"工具不可用"错误而非递归。

#### FR-064 外部 MCP Server 接入 `P2`
- **规则**：支持 `stdio` / `sse` / `streamable-http` 三种 transport；启动时异步连接（不阻塞主流程）；连接状态 `connected`/`connecting`/`disabled`/`failed` 可视化；`listTools` 超时 5s；server 的 `instructions` 作为 reminder 注入；工具通过 `McpToolAdapter` 适配为内置 `Tool` 接口。

#### FR-065 延迟工具加载（Deferred Loading） `P1`
- **规则**：
  - 配置：每个 server 可设 `lazy: true` 或 `lazy: ["toolA","toolB"]`；几乎所有内置工具默认 `lazy: true`
  - 门控：仅当模型 `features` 含 `defer_loading` 时启用（Anthropic / OpenAI 家族）
  - 传输编码 `DeferredToolSearchCodec`：
    - `anthropic`：ToolSearch 结果转为原生 `[{"type":"tool_reference","tool_name":...}]` content block
    - `openai`：**仅 Responses API 模式**，ToolSearch 以 `{"type":"tool_search","execution":"client"}` 工具声明下发；结果以自定义 `we0j_tool_search_output` block 内嵌完整 schema
    - `none`：不支持的模型 → 全量工具直接下发（不启用延迟）
  - 未激活工具的 schema **不进请求**，仅把工具名清单通过 `<available-deferred-tools>` reminder 告知模型
  - `ToolSearch(query)` 语义：支持 `select:A,B` 精确选择、关键词检索、`+required` 必含词；打分排序；**单次最多激活 3 个**
  - 激活状态持久化在 `ToolPart.state.metadata["tool_references"]`；resume 与压缩后必须能重建（`ToolActivationManager.restore()`、`CompactionSummaryMetadata.preCompactDiscoveredTools`）
  - `ToolSearch` 自身永不延迟
- **AC**：30 个工具场景下首轮请求 tool schema 体积下降 ≥ 70%；模型能通过 ToolSearch 成功激活并使用 Edit。

---

### 4.7 FR-07 具体工具行为

#### FR-071 `Read` `P0`
- 参数：`path`(必填)、`offset`(行号，1-based)、`limit`(默认 2000 行)
- 规则：
  - 文本文件：截断至 2000 行或 50KB（先到为准），输出带行号前缀
  - 图片（jpg/png/gif/webp/bmp）：返回 `ImageContent`（base64），超大图先降采样至长边 ≤ 1568px
  - PDF：提取文本（`pdfbox`）
  - **副作用**：登记读时间戳 `FileTimeRegistry.stampRead(path)`（编辑安全链路的锚点）；预热 LSP（`waitForDiagnostics=false`）
  - 目录 → 报错并提示用 Glob
  - 不存在 → 明确错误文本（供模型纠正）
- **AC**：读取后被外部修改的文件，再 Edit 会触发 staleness 报错。

#### FR-072 `Write` `P0`
- 参数：`path`、`content`
- 规则：文件已存在 → `assertRead(path)` 强制先读；自动创建父目录；权限询问（外部路径必询）；原子写（临时文件 + `ATOMIC_MOVE`）；写后登记读时间戳；写后触发 LSP 诊断（本文件 + 最多 5 个关联文件）
- **AC**：并发 Write 同一文件不产生内容交错。

#### FR-073 `Edit` `P0`
- 参数：`path`、`oldText`、`newText`、`replaceAll`(默认 false)
- **完整流程（顺序不可变）**：
  1. 获取 per-path 锁（`FileTimeRegistry.withLock`）
  2. `assertRead(path)`：从未读过 → 报错"必须先 Read"；文件 mtime 晚于登记时间（50ms 容差）→ 报错"文件已被外部修改，请重新 Read"
  3. 读取当前全文
  4. `oldText` 为空 → 语义为"仅当文件为空时创建"
  5. **9 级替换策略链**，逐级降级尝试，命中即停：

| 级别 | 策略 | 匹配规则 |
|---|---|---|
| 1 | `SIMPLE` | 精确 `indexOf` |
| 2 | `LINE_TRIMMED` | 每行 `trim()` 后比对 |
| 3 | `BLOCK_ANCHOR` | 按块切分 + Levenshtein 相似度，阈值 0.0（严格）→ 0.3（宽松）两轮 |
| 4 | `WHITESPACE_NORMALIZED` | 连续空白折叠为单空格后比对 |
| 5 | `INDENTATION_FLEXIBLE` | 忽略公共缩进差异 |
| 6 | `ESCAPE_NORMALIZED` | 转义序列归一（`\\n` ↔ 换行等） |
| 7 | `TRIMMED_BOUNDARY` | 首尾空行裁剪后比对 |
| 8 | `CONTEXT_AWARE` | 结合上下文行扩展匹配窗口 |
| 9 | `MULTI_OCCURRENCE` | 多处命中：`replaceAll=true` 则全替换，否则报错并列出全部命中的行号区间 |

  6. **唯一性校验**：命中 > 1 且 `replaceAll=false` → 报错，错误信息必须包含所有命中位置的行号，并提示"请扩大 oldText 上下文使其唯一"
  7. 生成 unified diff（`java-diff-utils`），做 dedent 修剪
  8. 权限询问（外部路径 / 配置为 ask）：请求 metadata 携带 diff，供 UI 展示
  9. 写文件（原子写）
  10. **重读磁盘实际字节，重新生成 diff**（保证展示给用户的是真实落盘结果，而非内存计算结果）
  11. 登记读时间戳
  12. `LspService.touchFile(path, waitForDiagnostics=true)` → 附加最多 20 条 severity=ERROR 的诊断到工具输出
- **AC**：
  - 未 Read 直接 Edit → 明确报错
  - 外部修改后 Edit → staleness 报错
  - `oldText` 命中 3 处且未 `replaceAll` → 报错含 3 个行号
  - 缩进不一致但语义相同 → 策略链降级后成功
  - 编辑后引入语法错误 → 输出含 LSP 诊断

#### FR-074 `Bash` `P0`
- 参数：`command`(必填)、`description`、`timeout`(秒，默认 120，必须 > 0)、`cwd`、`runInBackground`(默认 false)
- **权限 pattern 生成**：
  - 解析命令为独立子命令节点（原项目用 tree-sitter-bash；**MVP 用自研 `BashCommandParser`**：按 `&&`、`||`、`;`、`|` 切分，处理引号与转义，忽略子 shell）
  - 每个子命令按 **ARITY 表**取最长匹配前缀生成 pattern（如 `git` arity=1 → pattern `git commit`；`npm` arity=2 → `npm run build`）
  - 对 `cd`/`rm`/`cp`/`mv`/`mkdir`/`touch`/`chmod`/`chown`/`cat` 解析其路径参数并 realpath 规范化，用于外部目录判定
- **执行**：
  - Unix：`/bin/bash -c <cmd>`；Windows：`cmd.exe /c <cmd>`（可配置为 PowerShell）
  - 环境变量合并：系统 env + `shell.env` hook 贡献 + 会话级 env
  - `redirectErrorStream(true)` 合并 stdout/stderr
  - 新进程组（Unix `setsid`；Windows `CREATE_NEW_PROCESS_GROUP`）以便整树杀死
  - 8192 字节块流式读取，**同时**累积到内存 List 与逐块 append 到落盘文件（`tool_output/<callId>.txt`）
- **超时与中断**：三方竞速 `process.waitFor(timeout)` / `abortSignal.await()`；触发即 `killTree()`（`ProcessHandle.descendants()` 先子后父 `destroyForcibly()`，2s 宽限后强杀）
- **结果组装**：`buildShellResult` 附加说明标注（超时、非零退出码、被信号终止）；统一走 50KB/2000 行截断
- **后台模式**：立即返回 `shellId`（格式 `bash_{epochMs}_{uuid8}`）+ 输出文件路径；由 `ShellManager` 托管（信号量上限 10）；完成时经 `NotificationService.pushOrResume(parentSessionId, TaskNotification)` 通知
- **ripgrep 退出码语义**（Grep 用）：`0` = 有匹配；`1` = 无匹配（正常，返回"No files found"）；`>=2` = 错误（有输出时容忍）
- **AC**：`sleep 300` 带 timeout=2 → 2s 后终止且无残留进程；后台任务完成能唤醒父会话；输出 > 50KB 时截断且落盘完整。

#### FR-075 `Grep` / `Glob` `P0`
- **Grep**：参数 `pattern`、`path`、`include`(glob)、`ignoreCase`、`regex`(默认 true)、`maxResults`
  - 优先调用 ripgrep：`rg -nH --hidden --follow --no-messages --field-match-separator=| --regexp <P> [--glob <include>] <path>`
  - 解析 `path|line|text`；按文件 mtime 倒序分组；`MATCH_LIMIT = 100`；单行截断至 2000 字符
  - 输出格式：按文件分组，`<path>:` 后跟 `Line N: <text>`
  - ripgrep 缺失 → 降级为 Java NIO `Files.walk` + `Pattern` 匹配（性能较差但可用），并在结果中标注降级
- **Glob**：参数 `pattern`、`path`
  - 绝对 glob 规范化为 `(pattern, baseDir)` 对
  - `rg --files --glob <P>` 于 baseDir 执行；对每个结果 stat 取 mtime；上限 100，最新优先；输出纯路径列表
  - 降级：`FileSystems.getDefault().getPathMatcher("glob:...")` + `Files.walk`
- **权限**：仅当搜索根**在项目外**时请求 `directory_tree(path)` pattern 授权（项目内直接放行以减少打扰）
- **AC**：10 万文件仓库 Grep 响应 < 3s（有 ripgrep）；结果不超 100 条。

#### FR-076 `TodoWrite` / `TodoRead` `P0`
- 数据模型：`TodoItem{content, status: pending|in_progress|completed, activeForm}`
- 规则：整表覆盖写入；文件持久化 `sessions/<id>/todos.json`；每轮刷新注入上下文；状态变更发 Bus 事件供 UI 侧栏渲染
- 约束：同一时刻至多一个 `in_progress`（校验并警告）

#### FR-077 Task 系统 `P0`
- 数据模型 `TaskV2`：`id, subject, description, activeForm, owner, status(pending|in_progress|completed|deleted), blocks[], blockedBy[], metadata(Map), timeCreated, timeUpdated`
- 规则：
  - 文件持久化 `sessions/<id>/tasks.json`；**团队会话用 team 名作为 list id，否则用 sessionId**（MVP 恒为 sessionId）
  - `blocks` / `blockedBy` 双向维护（加一边自动补另一边）
  - **依赖是建议性的，运行时不强制阻塞**；门控靠提示词（"claim 前检查 blockedBy 为空"）—— 此为原项目语义，需在文档中明确说明理由（避免死锁、保留 Agent 自主判断）
  - `owner` 支持认领；`deleted` 状态物理移除
  - 完成任务在空闲时自动清理
  - `TaskList` 返回摘要（id/subject/status/owner/blockedBy）；`TaskGet` 返回全量含 comments
- **AC**：并发更新同一 task 文件不丢写（文件锁 + 读改写）。

#### FR-078 `AskUserQuestion` `P0`
- 参数：`questions: List<QuestionInfo>`，`size ∈ [1,4]`
- `QuestionInfo{question, header(≤12 字符), options(2-4 个 QuestionOption), multiSelect(默认 false)}`
- `QuestionOption{label(≤60 字符), description, preview?}`
- **校验**（Bean Validation + 手动）：questions 1-4；options 2-4；header ≤ 12；label ≤ 60；**禁止模型自行编写 "Other" / "Type something." 等保留标签**（运行时拒绝）
- **流程**：`QuestionService.ask()` → 创建 `CompletableFuture<List<List<String>>>` 入 pending map → 发 Bus `question.asked`（payload 携带 `tool{messageId, callId}` 供 UI 挂到工具卡片）→ 虚拟线程阻塞等待
- **回复**：`reply(requestId, answers)` 或 `reject(requestId)` → `QuestionRejectedException`
- **CLI 形态**：终端渲染编号选项列表 + 自由输入行；Web 形态：卡片式单选/多选 + preview 侧栏 + 自由输入
- **结果文本**：`User has answered your questions: "Q1"="A1", "Q2"="A2"`；`structuredContent` 保留完整 questions + answers
- **AC**：4 问 × 4 选项 × multiSelect 场景正确；用户 reject 时模型收到明确拒绝信号。

#### FR-079 `Agent`（子 Agent） `P0`
- 参数：`subagentType`、`prompt`、`description`(3-5 词)、`runInBackground`(默认 false)、`model`(档位或具体模型)、`maxTurns`、`name`/`teamName`（MVP 拒绝）
- **规则**：
  - 从 `AgentRegistry` 解析人格（内置 `build`/`plan`/`explore` + `~/.we0j/agents/*.md` + `<project>/.we0j/agents/*.md`，项目级覆盖全局）
  - 创建子 Session（`parent_id` = 当前 sessionId）
  - 模型选择：显式 `model` → 人格默认 → 父会话最后使用的模型
  - 设置 `SessionToolOverlay`：屏蔽 `Agent`（防递归）；注入更严格运行时权限规则
  - Lane 设为 `SIDE_AGENT`
  - **后台模式**：`BackgroundAgentManager.register()` + `start()`（信号量上限 10），输出 JSONL 落盘（每行 `{"type":"message"|"part",...}`），统计工具调用次数与 token 用于进度展示；立即返回 `agentId`
  - **前台模式**：同步等待子 Loop 完成，同时监听父会话 abort（`fgWatchAbort`）以镜像中断
  - 完成回调 → `NotificationService.pushOrResume(parentSessionId, TaskNotification)`
  - 取消：`agentManager.cancel(id)` 同时取消虚拟线程任务与子 Session 的 Loop
- **AC**：后台子 Agent 完成后父会话能收到通知并读取结果；子 Agent 无法再启动子 Agent；并发 11 个时第 11 个排队。

#### FR-080 `SKILL` `P0`
- **Skill 磁盘格式**：`<skillDir>/SKILL.md`，YAML frontmatter → `SkillCard{name, description, license, compatibility, allowedTools, metadata, location}`
- **发现**：`SkillScanner` 扫描分层目录（全局 `~/.we0j/skills/` + 项目 `<project>/.we0j/skills/`），**项目级覆盖同名全局**，尊重 disable 列表
- **热加载**：`SkillWatcher` 基于 `java.nio.file.WatchService`（注意 macOS 需轮询兜底），变更 → 置位事件 → Loop 在 reminder 注入点 drain 并刷新清单
- **渐进式披露**：默认只把 `name + description` 作为 reminder 注入上下文；**正文不进 system prompt**
- **调用**：`SKILL(name, args)` 工具 → 读取 SKILL.md 正文 → 展开 `{{WE0J_*}}` 路径占位符 → 以 `<skill name="..." allowed_tools="..." args="...">正文</skill>` 包裹作为**工具结果**返回（即正文以 tool output 身份进入上下文）
- **AC**：新增 skill 目录后无需重启即出现在清单中；调用后正文进入上下文且模型能遵循。

#### FR-081 `EnterPlanMode` / `ExitPlanMode` `P0`
- 规则：进入 → 切换人格为 `plan`，工具集收敛为只读，注入 plan reminder；退出 → 请求 `plan_exit` 权限（用户审批计划）→ 批准后切回 `build` → Loop break 重启
- **AC**：plan 模式下模型无法调用 Write/Edit/Bash（schema 不下发 + 执行层双拦截）。

#### FR-082 `EnterWorktree` / `ExitWorktree` `P1`
- 规则：`git worktree add` 到 `.we0j/worktrees/<branch>`，切换会话 workdir；退出时可选保留/删除；无变更自动清理
- **AC**：worktree 内编辑不影响主工作区。

#### FR-083 `ToolSearch` `P1`
见 FR-065。

#### FR-084 `WebFetch` / `WebSearch` `P1`
- 规则：依赖外部搜索/抓取服务（配置 `common.services.search.apiKey`）；抓取结果转 markdown（`jsoup` + 自研正文提取）；可作为"以指定 prompt 回答"的模式（`mode: answer`）
- **AC**：未配置 key 时给出清晰提示而非 500。

#### FR-085 `LSP` `P2`
- Operations：`definition` / `references` / `hover` / `documentSymbol` / `workspaceSymbol` / `implementation` / `incomingCalls` / `outgoingCalls`
- 坐标：对外 1-based，转 LSP 0-based
- 隐藏子工具 `LSP_DIAGNOSTICS` / `LSP_HOVER` 不暴露给模型（通过 filter rejected），仅供 Edit/Write 内部调用
- Server 注册表：pyright / ty / typescript / vue / eslint / oxlint / gopls / jdtls / bash-language-server / dockerfile，共 10 个
- 启动三级查找：`which` → 包管理器解析 → 自动安装
- 项目根探测 `nearestRoot()`：按锁文件（`package.json`/`pyproject.toml`/`go.mod`/`pom.xml`）向上查找
- 管理器按 `key = root + serverId` 懒启动、去重、失败记入 `broken` 集合不再重试

---

### 4.8 FR-08 权限系统

#### FR-081 权限模型 `P0`
- `PermissionName`（28 种，见 FR-061 表 + 行为级）：
  - 工具级：`read, write, edit, bash, glob, grep, lsp, skill, agent, task, task_output, task_stop, todowrite, todoread, question, cron, web_fetch, web_search, team, send_message`
  - 行为级：`external_directory, doom_loop, plan_enter, plan_exit, worktree, tool_search, recall, operate_memory`
  - 通配：`*`
- `PermissionRule{permission, pattern, action}`；`Action ∈ {ALLOW, DENY, ASK}`
- `Ruleset = List<PermissionRule>`
- **合并顺序**（后者覆盖前者）：① 用户级 settings → ② 项目级 settings → ③ agent 人格定义中的 rules → ④ session 运行时规则（`always` 回复产生的）
- **匹配算法**：**last-match-wins**（遍历合并后的 ruleset，最后一条命中的规则生效）
- **通配符语义**：`*` → `.*`，`?` → `.`；Windows 下大小写不敏感；路径分隔符统一为 `/` 后匹配

#### FR-082 Pattern 生成规则 `P0`
| 场景 | pattern 形态 | 示例 |
|---|---|---|
| 文件操作 | 相对项目根的路径 | `src/main/java/com/x/UserService.java` |
| 目录操作 | `directoryTree(path)` | `src/main/java/**` |
| Bash | 命令前缀 + arity | `git commit`、`npm run build` |
| 外部路径 | `external_directory` + 绝对路径 | `/etc/hosts` |
| 通用兜底 | 工具名 | `Edit` |

- **项目内短路**：`PermissionPatternResolver.isProjectPath(path)` 为真时，不生成 `external_directory` pattern（减少打扰）

#### FR-083 检查时机 `P0`
- **主检查点在工具内部**（`ctx.gate().ask(...)`），在真正执行副作用**之前**
- 外部 MCP 工具在 `ToolExecutor` 层做通用检查（Track 2）
- `SessionToolOverlay.canUseTool` 拦截器可在不隐藏 schema 的情况下 deny 或改写 input

#### FR-084 询问与回复流程 `P0`
1. `evaluate()` 逐个 pattern 求值
2. 任一 `DENY` → 立即抛 `PermissionDeniedException`
3. 全部 `ALLOW` → 直接放行
4. 存在 `ASK` → 构造 `PermissionRequest{id, sessionId, permission, patterns, metadata, message, always, tool{messageId, callId}}`
5. 创建 `CompletableFuture<ReplyDecision>` 存入 `pending` map（按 sessionId 分区）
6. 发 Bus 事件 `permission.asked`（经 `PermissionScopeResolver` 定向到 lead/父会话，使子 Agent 的请求冒泡到主 UI）
7. **工具所在虚拟线程 `future.get()` 阻塞**（零平台线程成本）
8. UI 回复 → `reply(requestId, Reply, userMessage?)`：
   - `ONCE` → complete future；若带 `userMessage`，附加为 `<permission_feedback>` 到工具输出
   - `ALWAYS` → 将 `request.always` 中的 patterns 转为 `ALLOW` 规则，写入 **session 运行时规则**（内存）并持久化到项目 settings.json；同时自动 resolve 所有匹配的 pending 请求
   - `REJECT` → 抛 `PermissionRejectedException`，并**级联拒绝该 session 全部 pending 请求**
9. Loop 收到 reject：默认终止本轮（`STOP`），除非 `continueLoopOnDeny=true`（则把拒绝原因回灌模型让其调整）

#### FR-085 权限模式 `P0`
- `permissionMode ∈ {ASK, ALLOW_ONCE, BYPASS, REJECT}`（会话级，可运行时切换）
  - `ASK`：按规则集走
  - `ALLOW_ONCE`：所有 ask 自动 once 放行（本次会话）
  - `BYPASS`：全部放行（危险，UI 需醒目警示）
  - `REJECT`：全部拒绝（只读会话）
- CLI 快捷键循环切换；Web 面板可设
- **AC**：BYPASS 模式下 CLI 有持续可见的红色警示条。

#### FR-086 Doom Loop 检测 `P1`
- 规则：同一工具 + 相同 input 连续调用 ≥ N 次（默认 5）→ 触发 `doom_loop` 权限询问，提示模型可能陷入循环
- **AC**：构造重复调用场景能触发。

---

### 4.9 FR-09 持久化

#### FR-091 存储选型 `P0`
- SQLite（`xerial/sqlite-jdbc`）+ Spring Data JPA（Hibernate 6）+ Flyway 迁移
- 每项目一个 DB 文件：`~/.we0j/projects/<projectId>/runtime.db`
- PRAGMA：`journal_mode=WAL`、`synchronous=NORMAL`、`busy_timeout=5000`、`cache_size=-64000`、`foreign_keys=ON`

#### FR-092 表结构（薄壳 + JSON blob） `P0`
**保留原项目的关键设计决策**：`message` 与 `part` 表只存索引列 + 一个 `data` JSON blob 列。
- **理由**：Part 类型频繁演进（原项目有 11 种），若逐字段建表则每次加类型都要 migration；JSON blob + 应用层 sealed interface 多态反序列化，schema 演进零成本，同时保留 `session_id`/`message_id`/`time_*` 索引列满足查询需求
- 三张表：`session`（强类型列）、`message`（薄壳）、`part`（薄壳）
- 其余状态走 **JSON 文件**：todos / tasks / crons / inbox / tool_output（与原项目一致）
- 权限运行时规则不落 DB，落项目 settings.json

#### FR-093 写入策略 `P0`
- Message/Part 使用 **upsert**（SQLite `ON CONFLICT(id) DO UPDATE`）
- Part 增量更新（流式 delta）：原项目每个 delta 都写库 —— **Java 版优化为节流批量写**（详见 NFR-02），内存态保持最新，DB 落盘按 100ms 或 4KB 阈值合并
- 所有写操作带 `SQLITE_BUSY` 重试（3 次，退避 50/150/450ms）

#### FR-094 Schema 迁移 `P0`
- Flyway 版本化迁移（**替代原项目的 `PRAGMA table_info` + `ALTER TABLE` 自愈 hack**，这是架构改进点）
- 启动时校验 `user_version`，不匹配则迁移；迁移前自动备份 DB 文件

---

### 4.10 FR-10 快照与回滚

#### FR-101 Shadow Git 快照 `P0`
- **实现方式**：独立 git 目录（`~/.we0j/projects/<id>/snapshot/`）+ `--work-tree=<项目根>`，**不污染用户仓库的 `.git`**
- 初始化：`git init`（`--separate-git-dir` 语义），配置 `core.autocrlf=false`、`core.longpaths=true`、`core.fsmonitor=false`；同步用户仓库的 `.git/info/exclude` 到 shadow 的 exclude
- `track()` = `git add -A` + `git write-tree` → 返回 **tree hash（不创建 commit）**
- `patch(hash)` = `git diff --name-only <hash>` → 变更文件列表
- `diffFull(h1, h2)` = `--name-status` + `--numstat` + `git show <hash>:<path>` 取 before/after 全文
- `restore(hash)` = `git read-tree <hash>` + `git checkout-index -a -f`
- `revert(patches, hash)` = 逐文件 `git checkout <hash> -- <file>`；文件不在该快照中 → 删除该文件
- **实现选型**：MVP 用 `ProcessBuilder` 直接调 git CLI（忠实还原原实现、风险最低）；接口 `SnapshotService` 抽象，后续可提供 JGit 实现（注意 JGit 对独立 git-dir + work-tree 组合支持有限，且 `write-tree` 需走 `DirCache.writeTree(ObjectInserter)`）
- 调用时机：每个 step 的 start / finish

#### FR-102 回滚双模式 `P0`
- `mode = CONVERSATION`：
  - 仅在 `session.revert` JSON 字段记录边界 `{messageId, partId, mode, timeCreated}`
  - 读取历史时过滤边界之后的消息（软删除，可撤销）
  - `cleanup()` 时物理删除被回滚的 message/part 行
- `mode = BOTH`：
  1. 先 `Snapshot.track()` 保存**当前**tree hash 到 `revert.snapshot`（供 `unrevert` 恢复）
  2. 计算从目标锚点到当前的变更文件集
  3. `Snapshot.revert(patches, targetHash)` 回滚代码
  4. 记录对话边界（同 CONVERSATION）
- `unrevert()`：用 `revert.snapshot` 执行 `restore()` 恢复代码 + 清除对话边界标记

#### FR-103 变更摘要 `P1`
- 规则：每个 UserMessage 完成后，异步计算本轮 diff 摘要 `UserSummary{title, body, diffs: List<FileDiff{path, status: added|deleted|modified, additions, deletions}>}`，写入 `session.summary_*` 列，供 `/rewind` 面板展示
- **AC**：`/rewind` 列表每项显示"改了哪些文件、+N/-M"。

#### FR-104 快照性能 `P0`
- 规则：`track()` 必须尊重 `.gitignore` 与 shadow exclude；`node_modules`/`.venv`/`target`/`build` 等默认排除；单次 `track()` P95 < 500ms（10 万文件仓库）
- **AC**：大仓库不因快照卡住 Loop。

---

### 4.11 FR-11 事件总线

#### FR-111 事件类型 `P0`

| 事件 | payload 关键字段 | 发布者 | 订阅者 |
|---|---|---|---|
| `session.updated` | sessionId, status, title | SessionService | CLI, Web, ACP |
| `session.compacted` | sessionId, metadata | CompactionService | CLI, Web |
| `session.diff` | sessionId, diffs | SnapshotService | Web |
| `session.error` | sessionId, error | Loop | CLI, Web |
| `message.updated` | sessionId, messageId, message | Session | CLI, Web, 持久化 |
| `message.part.updated` | sessionId, messageId, partId, part | Session | CLI, Web |
| `message.part.delta` | sessionId, messageId, partId, field, delta | TurnProcessor | CLI, Web |
| `message.part.removed` | sessionId, messageId, partId | Session | CLI, Web |
| `permission.asked` | PermissionAskedPayload | PermissionService | CLI, Web |
| `permission.replied` | sessionId, requestId, reply | PermissionService | CLI, Web |
| `question.asked` | QuestionAskedPayload | QuestionService | CLI, Web |
| `question.replied` / `.rejected` | sessionId, requestId, answers | QuestionService | CLI, Web |
| `agent.runtime.mode_changed` | sessionId, from, to | AgentRegistry | CLI, Web |
| `task.updated` | sessionId, taskId, task | TaskService | Web |
| `todo.updated` | sessionId, todos | TodoService | CLI, Web |
| `notification.pushed` | sessionId, notification | NotificationService | CLI, Web |

#### FR-112 分发语义 `P0`
- **类型化订阅**：`subscribe(Class<E>, Consumer<E>)` + 通配 `subscribeAll(Consumer<BusEvent>)`
- **顺序保证**：同一 `sessionId` 的事件**严格有序**分发（按 session 分片的单线程虚拟执行器）；无 sessionId 的全局事件走共享执行器
- **隔离性**：慢订阅者不得阻塞 Loop —— 分发在独立虚拟线程；订阅者异常被捕获记录，不影响其他订阅者
- **背压**：SSE 订阅者队列有界（1000），溢出时丢弃 `message.part.delta` 类高频事件并标记"事件已省略"，**不得丢弃** `permission.asked` / `question.asked` / `message.updated` 等关键事件
- **AC**：压测 10k 事件/秒，顺序不乱、Loop 不被拖慢、关键事件零丢失。

---

### 4.12 FR-12 CLI

#### FR-121 命令结构 `P0`
```
we0j                              进入 REPL（默认工作目录 = cwd）
we0j --resume <sessionId|last>    恢复指定/最近会话
we0j --workdir <path>             指定工作目录
we0j --model <provider/model>     指定初始模型
we0j --permission-mode <mode>     指定权限模式
we0j -p "<prompt>"                一次性非交互执行（headless，适合脚本/CI）
we0j session list|show|delete     会话管理
we0j task list|add|show|update|done|delete    任务管理
we0j skill list|search|install|update|uninstall|sync   Skill 管理
we0j config get|set|path          配置
we0j provider list|enable|disable 供应商
we0j doctor                       环境自检（git/ripgrep/JDK/网络/密钥）
we0j web                          仅启动 Web 控制台（不进 REPL）
we0j acp                          （P2 占位）
```

#### FR-122 Slash Commands `P0`
`/compact [指令]`、`/exit`( `/quit`)、`/help`( `/h`,`/?`)、`/mode`、`/model`、`/new`、`/rename`、`/resume`、`/rewind`、`/tasks`、`/mcp`、`/provider`、`/theme`、`/thinking`、`/permission-mode`、`/reasoning-visibility`、`/usage-updates`、`/init`（生成项目 AGENTS.md）、`/todos`、`/config`、`/status`、`/usage`

- 另支持**用户自定义 markdown command**：`~/.we0j/commands/*.md` 与 `<project>/.we0j/commands/*.md`，frontmatter 定义 `description`/`argument-hint`，正文为提示词模板，支持 `$ARGUMENTS` 与 `{{WE0J_*}}` 占位符
- Skill 也可注册为 slash command

#### FR-123 REPL 交互 `P0`
- 基于 JLine 3：多行输入（`\` 续行 / Shift+Enter）、历史记录（内存 + 持久化 `~/.we0j/history`）、`@` 文件补全（模糊匹配，走 ripgrep `--files` 或 `git ls-files`）、`#L10-20` 行区间选择器、slash command 补全
- 流式渲染：reasoning 暗色（可折叠）、text 正常色、工具卡片按状态着色（running 闪烁 / completed 绿 / error 红）
- Markdown 渲染：`commonmark-java` 解析 → `AttributedString` + ANSI 彩色；代码块语法高亮（CLI 侧降级为单色 + 边框，复杂高亮交给 Web）
- 按键：
  - `Enter` 提交；`Esc` 级联中断（关闭弹窗 → 清空输入 → 中断当前轮）
  - `Ctrl+C` 级联（关闭面板 → 清空输入 → 双击退出）
  - `Ctrl+L` 清屏；`Ctrl+R` 搜索历史；`↑`/`↓` 历史
  - `Ctrl+X Ctrl+K` 双击杀死后台任务
- 图片粘贴：`Ctrl+V` 检测剪贴板图片（Windows 走 PowerShell `Get-Clipboard -Format Image`，macOS `osascript`，Linux `xclip`），落临时文件后作为 `FilePart` 附件
- 大段粘贴折叠：> 800 字符自动折叠为 `#text<id>` 引用（`PastedTextReferenceCodec`），提交时展开

#### FR-124 权限/提问的 CLI 形态 `P0`
- 权限：行内提示 `[Allow] (y)es once / (a)lways / (n)o / (s)how diff`，展示待执行命令或 diff
- 提问：编号列表逐问渲染，支持多选（逗号分隔）与自由输入
- **若 Web 控制台已连接**，两端同时可回复，先到先得（`CompletableFuture.complete` 幂等）

#### FR-125 Headless 模式 `P1`
- `we0j -p "<prompt>" --output-format text|json|stream-json`
- `stream-json`：逐行输出 JSON 事件（用于 CI/管道集成）
- `--permission-mode bypass` 或 `--allowedTools "Read,Grep"` 白名单，无人值守
- **AC**：CI 中可跑通"给一个 issue 描述，产出 patch"。

---

### 4.13 FR-13 Web 控制台

#### FR-131 服务形态 `P0`
- Spring Boot 内嵌 Tomcat + 虚拟线程（`spring.threads.virtual.enabled=true`）
- 默认 `127.0.0.1:8787`，**仅绑定 loopback**（安全）
- 首次启动生成随机 token，CLI 打印带 token 的 URL；所有 API 校验 `Authorization: Bearer <token>`
- 静态资源内嵌（前端构建产物打进 jar）

#### FR-132 实时通道 `P0`
- `GET /api/sessions/{id}/events` → `text/event-stream`（SSE，`SseEmitter`，超时 0 = 永不）
- SSE 事件名 = Bus 事件名（点分），data = payload JSON
- 断线自动重连（`EventSource` + `Last-Event-ID`，服务端按 seq 重放最近 200 条）

#### FR-133 页面与面板 `P0`
| 路由 | 功能 |
|---|---|
| `/` | 会话主视图：消息流、工具卡片、输入框、状态栏、侧栏（todo/task） |
| `#/model` | 模型选择器（按 provider 分组，显示 context window / 特性标签） |
| `#/provider` | 供应商管理：启用/禁用、apiKey、apiBase、模型列表增删改、连通性测试 |
| `#/config` | 通用配置：语言、模型档位、reasoning、运行时选项、权限默认值 |
| `#/resume` | 会话列表（分页 + 搜索 + diff 摘要） |
| `#/rewind` | 回滚：锚点时间线 + 模式选择 + 变更预览 |
| `#/tasks` | 后台任务：agent/shell 列表、状态、输出实时尾随、停止按钮 |
| `#/todos` | Todo 看板 |
| `#/mcp` | 工具与 MCP server 状态：已加载工具清单、lazy 状态、连接状态、手动重连 |
| `#/status` | 运行状态：会话状态、lane、token 用量、成本、上下文占用进度条、缓存命中率 |
| `#/theme` | 主题（亮/暗 + 强调色） |
| `#/permission` | 权限规则编辑器（可视化 ruleset，支持增删改 + last-match-wins 预览） |

#### FR-134 交互能力 `P0`
- 提交 prompt、中断、切换模型/模式/权限模式
- 权限与提问弹窗（支持 preview 侧栏渲染 markdown）
- 消息流虚拟滚动（万级 Part 不卡）
- 工具卡片差异化渲染：Edit → diff 视图（并排/统一切换）；Grep → 结果表格 + 跳转；Bash → 终端风格输出 + ANSI 解析；Read → 带行号代码块 + 语法高亮；Agent → 进度 + 输出尾随
- 用量图表：每轮 token 堆叠柱（input/output/cacheRead/cacheWrite）

#### FR-135 CLI ↔ Web 一致性 `P0`
- 两端共享同一 Bus 与同一 Loop；任一端提交的输入、回复的权限，另一端实时同步
- **AC**：CLI 提问、Web 回答，Loop 正常继续。

---

### 4.14 FR-14 配置系统

#### FR-141 分层与合并 `P0`
- 层级：用户级 `~/.we0j/settings.json` → 项目级 `<project>/.we0j/settings.json`（深合并，项目级覆盖）
- **基础设施配置只允许用户级/项目级**：`common.providers`、`common.services`、`common.mcpServers`、`common.lspServers`、`common.plugins`
- 配置树三段：
  - `common.*`：模型、供应商、权限、MCP 工具、LSP、插件、共享服务
  - `code.*`：工作目录、运行时、默认 agent、快照、提示建议
  - `web.*`：Web 控制台端口、token、主题
- **无效字段拒绝**：`code.mcpServers` / `web.mcpServers` 非法，启动时明确报错指出正确位置（对齐原项目行为）

#### FR-142 配置结构 `P0`
```jsonc
{
  "common": {
    "language": "zh-CN",
    "chat": {
      "default":  { "provider": "anthropic", "model": "claude-sonnet-4-5" },
      "tiers": {
        "fast": { "provider": "anthropic", "model": "claude-haiku-4-5" },
        "pro":  { "provider": "anthropic", "model": "claude-sonnet-4-5" },
        "max":  { "provider": "anthropic", "model": "claude-opus-4-1" }
      },
      "reasoning": { "enabled": true, "budgetTokens": 8000 },
      "onMissing": "error"
    },
    "providers": {
      "anthropic": {
        "enabled": true, "apiKey": "", "apiBase": "",
        "models": [
          "claude-haiku-4-5",
          { "id": "claude-sonnet-4-5", "features": ["defer_loading"],
            "contextWindow": 200000, "maxOutput": 8192,
            "pricing": { "input": 3.0, "output": 15.0, "cacheRead": 0.3, "cacheWrite": 3.75 } }
        ]
      }
    },
    "permission": { "*": "allow", "Read": "ask", "Bash": "ask", "Write": "ask", "Edit": "ask" },
    "mcpServers": {
      "builtin-read":  { "type": "builtin", "module": "read",  "enabled": true, "lazy": true },
      "builtin-edit":  { "type": "builtin", "module": "edit",  "enabled": true, "lazy": true,
                         "toolFilters": { "rejected": ["LSP_DIAGNOSTICS"] } },
      "context7":      { "type": "stdio", "command": "npx",
                         "args": ["-y","@upstash/context7-mcp"], "enabled": false, "lazy": true }
    },
    "services": { "search": { "apiKey": "" } },
    "loop": { "maxSteps": 200 }
  },
  "code": {
    "paths": { "workdir": null },
    "agent": { "defaultAgent": "build" },
    "runtime": { "snapshot": true, "promptSuggestions": true, "lane": "MAIN" },
    "compaction": { "buffer": 8000, "tailBudgetRatio": 0.2, "gapThresholdMinutes": 10,
                    "keepRecentToolResults": 5, "maxConsecutiveFailures": 3 }
  },
  "web": { "port": 8787, "host": "127.0.0.1", "token": "<auto>", "theme": "dark" }
}
```

#### FR-143 热加载 `P0`
- 规则：以 `path::lastModified` 作为缓存键，文件变更即失效重载；提供显式 `refresh()`；`ModelCardManager` 支持 `reloadFromConfig()`
- **不得**因热加载导致进行中的 Loop 状态错乱（模型切换在下一轮生效）
- **AC**：改 settings.json 后 1s 内 `/status` 显示新值。

#### FR-144 校验与错误提示 `P0`
- Bean Validation（`jakarta.validation`）+ 自定义校验器
- 启动时全量校验，失败输出**可操作的**错误（文件路径 + JSON Pointer + 期望类型 + 修复建议），而非堆栈

---

### 4.15 FR-15 后台任务与通知

#### FR-151 后台任务注册表 `P0`
- 统一 `BackgroundTaskManager` 管理两类任务：`BackgroundAgent`（子 Agent）与 `BackgroundShell`（后台命令）
- 字段：`id, type, sessionId, parentSessionId, status(queued|running|completed|failed|cancelled), timeCreated, timeCompleted, outputFile, summary`
- 并发上限：agent 10、shell 10（信号量）
- 状态变更发 Bus `task.updated`

#### FR-152 输出落盘 `P0`
- Shell：纯文本逐行 append，flush per line，10MB 上限后写截断标记
- Agent：JSONL，每行 `{"type":"message"|"part", "data":{...}}`，便于增量尾随
- `TaskOutput(taskId, block, timeoutMs)`：`block=true` 时用虚拟线程等待完成 Future + 读文件；返回带 `<retrieval_status>` 标记的结构化文本

#### FR-153 完成通知回流 `P0`
- `NotificationService.pushOrResume(targetSessionId, TaskNotification)`：
  - 目标会话 **Busy** → 入 `BlockingQueue`，Loop 在工具子循环间隙 drain，渲染为 `<task-notification>` reminder 注入（**不写库**）
  - 目标会话 **Idle** → 写入合成 `UserMessage`（落库）+ 唤醒 Loop（`resumeExisting=true`）
- `TaskNotification` 含 `taskType`(`background_agent`|`background_shell`)、`outputFile`、摘要、token/工具调用统计
- `TaskNotificationCodec`：解析 `<task-notification>` 标签还原为结构化对象供 UI 富渲染
- **AC**：Idle 会话收到通知后自动继续；Busy 会话不打断当前轮。

#### FR-154 任务停止 `P0`
- `TaskStop(taskId)`：先查 agent 注册表，再查 shell 注册表；取消虚拟线程 + `SessionPrompt.cancel(childSessionId)` + 杀进程树
- **AC**：停止后 5s 内无残留进程与线程。

#### FR-155 Cron 定时任务 `P2`
- 预留 `ScheduledJobService` 接口；实现用 Quartz `CronExpression` 解析 + 每会话单线程 tick（1s）；跳过 Busy 会话；job-id hash 抖动；持久化 `sessions/<id>/crons.json`

---

### 4.16 FR-16 可观测性

#### FR-161 结构化日志 `P0`
- SLF4J + Logback，JSON 格式（生产）/ 彩色格式（开发）
- MDC 字段：`sessionId`、`messageId`、`lane`、`step`、`toolName`、`provider`、`model`
- **敏感信息脱敏**：apiKey、Authorization header、消息正文（可配 `log.redactContent=true`）
- 日志文件轮转：`~/.we0j/projects/<id>/logs/`，单文件 10MB，保留 5 个

#### FR-162 请求追踪 `P1`
- 每次模型请求生成 `requestId`，记录：模型、消息数、估算/真实 token、耗时、TTFT（首 token 时间）、重试次数、缓存命中、成本
- 落 `~/.we0j/projects/<id>/logs/llm-trace.jsonl`
- 预留 OpenTelemetry / Langfuse 导出接口（`ObservabilityExporter` SPI）

#### FR-163 `/status` 面板数据 `P0`
- 会话状态、当前 lane、step 计数、上下文占用（used/window 百分比 + 进度条）、累计 token（分档）、累计成本、缓存命中率、活跃工具数/延迟工具数、后台任务数、快照数

#### FR-164 健康自检 `P1`
- `we0j doctor`：JDK 版本、虚拟线程可用、git 版本与可执行性、ripgrep 存在性、DB 可写、Provider 连通性（发一个 1-token 请求）、端口占用、磁盘空间

---

## 5. 非功能需求

### NFR-01 并发与线程模型 `P0`
- 所有阻塞 IO（模型 SSE、子进程、DB、文件、权限等待）运行在**虚拟线程**上，采用 thread-per-task 模型
- **禁止**在虚拟线程中使用 `synchronized` 包裹阻塞调用（会导致 carrier pinning）；统一用 `ReentrantLock` / `Semaphore` / `CompletableFuture`
- 平台线程池仅用于：CPU 密集（JSON 解析、diff 计算、token 计数）—— 固定大小 = `Runtime.availableProcessors()`，有界队列 + `CallerRunsPolicy`
- 每会话一个 Loop 虚拟线程；每工具调用一个虚拟线程；每 SSE 连接一个虚拟线程
- **验证**：JFR + `-Djdk.tracePinnedThreads=full` 在压测下零 pinning 告警

### NFR-02 性能
| 指标 | 目标 | 测量 |
|---|---|---|
| 进程冷启动到 REPL 可输入 | ≤ 2.5s | Spring 懒初始化 + CDS/AppCDS |
| 首 token 到屏（TTFT，本地开销） | ≤ 300ms | 打点：请求发出 → 首个 delta 渲染 |
| Part 增量渲染延迟 | ≤ 50ms | Bus 发布 → UI 更新 |
| DB 写入（Part 节流后） | ≤ 100 次/秒/会话 | 批量合并 |
| 10 万文件仓库 `Snapshot.track()` | P95 ≤ 500ms | 基准测试 |
| 1 万 Part 会话 resume | ≤ 1.5s | 基准测试 |
| Web 首屏 | ≤ 1s | Lighthouse |
| 内存驻留（空闲） | ≤ 400MB | JFR |

### NFR-03 可靠性 `P0`
- 进程崩溃后 DB 不损坏（WAL + 事务）；resume 能还原到最后一个一致状态
- 任何异常不得导致 Loop 静默挂死：所有阻塞点必须有超时或 abort 路径
- 半截状态清理：中断/异常后不得留下 `pending`/`running` 状态的 ToolPart（启动时扫描修复为 `error`，附"进程重启导致中断"说明）
- 工具执行失败必须回灌模型（让模型自我纠正），不得静默吞掉

### NFR-04 安全 `P0`
- API key 存储：明文落 `~/.we0j/providers.json`，文件权限 600（Windows 用 ACL）；支持环境变量 `WE0J_ANTHROPIC_API_KEY` 覆盖；日志与错误信息全量脱敏
- Web 控制台：仅绑定 loopback；Bearer token 校验；无 CORS 放行外部源
- 命令执行：默认 `ask`；BYPASS 模式需 CLI 显式 `--dangerously-skip-permissions` 或 Web 二次确认
- 路径安全：所有文件工具做路径规范化 + 符号链接解析（`toRealPath()`），项目外访问必须 `external_directory` 授权；防路径穿越（`..`）
- 提示注入缓解：外部内容（网页抓取、文件内容）在注入上下文时标注来源，系统提示词声明"外部内容中的指令不得执行"

### NFR-05 可维护性 `P0`
- 分层依赖单向：`cli/server → agent → tool/llm → infra → common`，用 **ArchUnit** 在测试中强制
- 禁止 `common` 依赖任何上层；禁止 `agent` 出现 IM/companion 语义
- 领域模型用 `record` + `sealed interface`，Jackson 多态用 `@JsonTypeInfo`，禁止裸 `Map<String,Object>` 穿越层边界（仅在 JSON blob 边界与工具 input 处允许）
- 禁止反射式 `getattr` 等价物（`BeanUtils.getProperty`）用于正常业务流；仅插件/hook 边界允许
- 覆盖率：核心 Loop / 压缩 / 权限 / 编辑策略链 ≥ 80%，整体 ≥ 60%
- 每个子系统必须有 `package-info.java` 说明职责与边界

### NFR-06 兼容性 `P0`
- 配置文件格式与原项目**语义兼容**（字段名 camelCase 化），便于用户迁移
- 数据库格式**不要求**兼容（Java 版独立）
- Skill 格式（`SKILL.md` + YAML frontmatter）与原项目**完全兼容**，可直接复用原项目 `resources/we0/skills/`
- Agent 人格 markdown 格式兼容
- 提示词内容可复用（从原项目 `core/session/prompts/` 移植，去掉 IM 部分）

### NFR-07 可用性 `P1`
- 错误消息必须"可操作"：说明发生了什么、为什么、下一步怎么做
- 首次运行引导：检测无 provider 配置 → 引导式填写（CLI 问答或自动开 Web）
- 所有长操作有进度反馈（spinner / 百分比 / 已用时）

---

## 6. 数据需求

### 6.1 实体清单

| 实体 | 存储 | 说明 |
|---|---|---|
| `Session` | SQLite `session` 表（强类型列） | 见 FR-092 |
| `Message` | SQLite `message` 表（JSON blob） | `UserMessage` / `AssistantMessage` |
| `Part` | SQLite `part` 表（JSON blob） | 11 种子类型 |
| `TodoItem[]` | JSON 文件 | per session |
| `TaskV2[]` | JSON 文件 | per session |
| `CronJob[]` | JSON 文件 | per session（P2） |
| `InboxMessage[]` | JSON 文件 | per team（P2/不做） |
| `ToolOutput` | 文本/JSONL 文件 | per call / per background task |
| `PermissionRule[]`（运行时） | 项目 settings.json | session 级 always 规则 |
| `Settings` | JSON 文件 × 2 层 | 用户级 + 项目级 |
| `ProviderConfig` | JSON 文件 | 独立存储，不进 profile |
| `SkillCard[]` | 内存（磁盘扫描） | 热加载 |
| `AgentInfo[]` | 内存（markdown 扫描） | 人格注册表 |
| `Snapshot` | shadow git objects | tree hash 记在 Part |

### 6.2 Part 子类型字段规格（完整）

```
PartBase: { id, messageId, sessionId, type }

TextPart        type="text"        text, synthetic?, ignored?, displayOnly?, time{start,end?}, metadata{source?}
ReasoningPart   type="reasoning"   text, metadata{signature?}, time{start,end?}
ToolPart        type="tool"        callId, toolName, state(见下), metadata?
FilePart        type="file"        filename, displayRefId?, source(FileSource|SymbolSource|ResourceSource),
                                   url?, mime?, width?, height?
StepStartPart   type="step-start"  snapshot?(treeHash)
StepFinishPart  type="step-finish" snapshot?, cost, tokens{total?,input,output,reasoning,cache{read,write}}
SnapshotPart    type="snapshot"    (标记)
PatchPart       type="patch"       files: List<String>
AgentPart       type="agent"       source{agentId?, sessionId?}
CompactionPart  type="compaction"  prompt?, metadata: CompactionSummaryMetadata{
                                     preservedTail{messageIds}, preservedSegment{messageIds},
                                     preCompactDiscoveredTools[], messagesSummarized,
                                     truePostCompactTokenCount }
RetryPart       type="retry"       error: MessageError, time{created}

ToolState (discriminated by status):
  pending    { input:Map, raw:String }
  running    { input:Map, title?, metadata?, time{start} }
  completed  { input:Map, output:String, title, metadata:Map, time{start,end,compacted?}, attachments?:List<FilePart> }
  error      { input:Map, error:String, metadata?, time{start,end} }

MessageError (discriminated by name):
  MessageOutputLengthError | MessageAbortedError | StructuredOutputError | ProviderAuthError
  | APIError{statusCode?, isRetryable, responseHeaders?, responseBody?, metadata?}
  | ContextOverflowError{responseBody?} | UnknownError

UserMessage  { id, sessionId, role="user", time{created}, system?, tools?:Map<String,Bool>, variant?,
               format?(Text|JsonSchema{schema,retryCount}), summary?:UserSummary{title,body,diffs[]},
               mode:AgentMode, source?:ChannelSource, userId? }
AssistantMessage { id, sessionId, role="assistant", time{created,completed?}, error?, cost, tokens,
                   finish?, summary?:Bool, structured?, variant?, metadata? }

FileDiff { path, status: added|deleted|modified, additions?, deletions? }
```

### 6.3 数据保留
- 会话默认永久保留；`/exit` 时不清理
- `tool_output` 文件按会话保留，提供 `we0j gc --older-than 30d` 清理命令
- 日志按 NFR-161 轮转

---

## 7. 外部接口需求

### 7.1 模型 Provider API
| Provider | 端点 | 认证 | 备注 |
|---|---|---|---|
| Anthropic | `POST {apiBase}/v1/messages` | `x-api-key` + `anthropic-version: 2023-06-01` | 需 `stream:true`；thinking 需 beta 头 |
| OpenAI Chat | `POST {apiBase}/v1/chat/completions` | `Authorization: Bearer` | `stream:true` + `stream_options.include_usage:true` |
| OpenAI Responses | `POST {apiBase}/v1/responses` | 同上 | 延迟工具 `tool_search` 仅此模式支持 |
| OpenAI 兼容 | 同 Chat | 同上 | `apiBase` 可指向网关（DeepSeek / 通义 / vLLM 等） |
| Gemini | `POST .../v1beta/models/{model}:streamGenerateContent` | `x-goog-api-key` | `alt=sse` |

- 统一 HTTP 客户端：OkHttp 4（连接池、HTTP/2、超时、代理、拦截器）
- 超时：connect 10s、read 300s（流式长连接）、write 30s
- 代理：支持 `HTTPS_PROXY` / `ALL_PROXY`（含 SOCKS）

### 7.2 MCP 协议（P2）
- 版本 `2025-06-18`；transport：stdio（JSON-RPC over stdin/stdout）、SSE、streamable-http
- 使用 `io.modelcontextprotocol.sdk:mcp` 官方 Java SDK

### 7.3 LSP 协议（P2）
- JSON-RPC 2.0 over stdio，`Content-Length` 帧；使用 `org.eclipse.lsp4j`

### 7.4 外部二进制
| 二进制 | 用途 | 缺失降级 |
|---|---|---|
| `git` | 快照、worktree、diff | **必需**，缺失则禁用快照并告警 |
| `rg` (ripgrep) | Grep/Glob/@ 文件补全 | 降级 Java NIO 实现（慢） |
| `bash`/`cmd` | Bash 工具 | 必需 |
| PowerShell / osascript / xclip | 剪贴板图片 | 缺失则禁用图片粘贴 |

### 7.5 Web API（详见设计文档第 8 章）
REST + SSE，全部 `/api/**`，Bearer token 认证。

---

## 8. 验收标准（DoD）

### 8.1 功能验收（端到端场景）

| # | 场景 | 通过标准 |
|---|---|---|
| A-01 | 首次启动 | 自动创建全部配置文件；`doctor` 全绿 |
| A-02 | 纯对话 | 三家 Provider 各跑通；reasoning 与 text 分别渲染；用量正确 |
| A-03 | 读代码回答问题 | Grep → Read → 回答；无写操作时不触发权限 |
| A-04 | 改代码 | Read → Edit（含唯一性冲突、缩进不一致、外部修改三种异常分支）→ diff 展示 → LSP 诊断 |
| A-05 | 跑命令 | Bash 前台（超时终止）+ 后台（完成通知回流） |
| A-06 | 权限 always | 一次 always 后同类操作不再询问；规则落项目 settings |
| A-07 | AskUserQuestion | 4 问多选 + preview；CLI 与 Web 双端均可回复 |
| A-08 | 中断 | 四种时机 Esc；DB 自洽；无僵尸进程 |
| A-09 | resume | kill -9 后重启恢复；模型理解上文 |
| A-10 | 压缩 | 手工触发 + 自动触发 + 溢出反应式恢复 + 熔断 |
| A-11 | 快照回滚 | conversation / both 两模式 + unrevert |
| A-12 | Plan 模式 | 进入后只读；退出需审批；Loop 重启生效 |
| A-13 | Skill | 新增目录热加载；SKILL 调用后正文进上下文 |
| A-14 | 子 Agent | 前台 + 后台；输出落盘；TaskOutput 阻塞读取；TaskStop |
| A-15 | Todo/Task | 写入、状态流转、侧栏渲染、依赖双向维护 |
| A-16 | 延迟工具 | ToolSearch 激活；resume 后激活态恢复 |
| A-17 | 自举 | 用 We0J 修复 We0J 自身一个真实 bug 并提交 |
| A-18 | Headless CI | `we0j -p` + `stream-json` 在 CI 跑通 |
| A-19 | Web 全面板 | 12 个面板全部可用；SSE 断线重连不丢关键事件 |
| A-20 | 缓存命中 | 连续 5 轮，命中率 ≥ 90% |

### 8.2 质量门禁
- `mvn verify` 全绿：编译 + 单测 + ArchUnit 分层检查 + SpotBugs + Checkstyle
- 覆盖率达标（NFR-05）
- `-Djdk.tracePinnedThreads=full` 压测零 pinning
- SSE 压测 10k 事件/秒无乱序、无关键事件丢失
- 三家 Provider 的 SSE fixture 回放测试全绿
- 内存泄漏：连续 100 轮对话后堆稳定（JFR 对比）

---

## 9. 里程碑与迭代计划

> 假设单人投入，每周 5 天。总计 **14 周**。每个里程碑结束必须有可运行产物 + 演示脚本。

### M0 工程骨架（第 1 周）
- Maven 多模块骨架、parent pom、依赖版本锁定（BOM）
- `we0j-common`：领域模型（Message/Part/Tokens/StreamEvent 的 sealed interface + record 全套）、异常体系、常量、ID 生成（ULID）
- `we0j-infra`：分层配置加载 + 校验 + 热加载、目录约定 `PathResolver`、日志配置、Flyway + JPA + SQLite 三张表、Bus 骨架
- ArchUnit 分层规则测试
- **产出**：`we0j doctor` 可运行；配置读写可运行；DB 建表成功

### M1 Provider 层与最小 Loop（第 2-3 周）
- OkHttp 封装、SSE 解析器（`data:` 累积、`[DONE]`、多行 data、心跳）
- `AnthropicProvider` / `OpenAiChatProvider` / `OpenAiResponsesProvider` / `GeminiProvider` 四实现 + 事件归一化
- `ModelCardManager` / `ProviderRegistry` / 上下文窗口与定价表
- 重试策略（含 retry-after 解析）、错误分类器（溢出识别 13 条规则）
- 提示词缓存打点、usage 归一、成本计算
- `AbortSignal` + `EventStream` 拉流抽象
- 最小 `AgentLoop`（无工具）：user → stream → persist parts → 完成
- **SSE fixture 回放测试**（三家各 3 组真实报文）
- **产出**：`we0j -p "hello"` 能流式输出并落库

### M2 工具体系与权限（第 4-5 周）
- `Tool` SPI、`ToolRegistry`、JSON Schema 生成（victools）、`ToolContext`、`ToolResult`
- `ToolResolver`（可见性、overlay、渠道过滤、lazy 计算）
- `ToolExecutor`（并发执行、异常转 error state、统一截断、落盘）
- `PermissionService`（ruleset 合并、last-match-wins、wildcard 匹配、pattern 生成、arity 表、ask/reply 阻塞流程、四种模式、级联 reject）
- `QuestionService`
- `FileTimeRegistry`（读时间戳、staleness、striped lock）
- 工具实现：`Read` / `Write` / `Edit`（9 级策略链 + diff + 唯一性）/ `Bash`（解析器 + 执行 + 杀树 + 后台）/ `Grep` / `Glob`（ripgrep + NIO 降级）
- 外层 Loop 的工具子循环接通
- **产出**：能完成"读-改-跑"完整编码任务，权限全流程可用

### M3 上下文工程与压缩（第 6-7 周）
- `SystemPromptAssembler`（分块、家族选择、缓存稳定性）
- `ContextContributor` SPI + 9 类 reminder 实现 + 去重
- `HistoryToModelMessages` 转换器（Part 折叠、三家格式、配对校验、清洗）
- token 计数（jtokkit）、溢出判定三时机
- `PreservedTailPlanner` / `HistorySanitizer` / `CompactionRetryPlanner` / `PostCompactionRestore`
- 经典压缩 + 反应式恢复 + 熔断器 + 时间维微压缩
- `filterCompacted` 历史过滤
- **产出**：长会话（>50 轮）可持续，压缩后可继续

### M4 快照与回滚（第 8 周）
- `SnapshotService`（shadow git init / track / patch / diffFull / restore / revert）
- Step 快照锚点接入
- `SessionRevert`（conversation / both / unrevert / cleanup）
- `UserSummary` diff 摘要异步计算
- `/rewind` 数据接口
- **产出**：改坏代码可一键回滚

### M5 CLI（第 9-10 周）
- picocli 命令树（12 个顶层命令）
- JLine 3 REPL：多行、历史、`@` 补全、slash 补全、`#L` 行选择
- 流式渲染器：reasoning/text/工具卡片、ANSI、markdown（commonmark-java）、spinner
- 按键绑定与 Esc/Ctrl+C 级联
- 权限/提问的 CLI 交互
- 剪贴板图片、大段粘贴折叠
- 自定义 markdown command 加载与模板渲染
- Headless `-p` + `--output-format stream-json`
- **产出**：CLI 可日常使用

### M6 Web 控制台（第 11-12 周）
- Spring Boot 3 + 虚拟线程 + SSE（`SseEmitter`，Last-Event-ID 重放）
- REST API 全量（详见设计文档第 8 章）
- 前端（Vue 3 + Vite + TypeScript + Pinia + TailwindCSS）：主视图 + 12 个面板
- 工具卡片差异化渲染（diff 视图 / 终端输出 / 结果表格 / 代码块高亮）
- 虚拟滚动、用量图表
- Token 认证 + loopback 绑定
- CLI ↔ Web 双端联动
- **产出**：浏览器完整可用

### M7 Skills / 后台任务 / Todo / Task / Plan（第 13 周）
- `SkillScanner` + `SkillWatcher`（WatchService + 轮询兜底）+ `SKILL` 工具 + 渐进式披露
- `AgentRegistry`（markdown 人格解析）+ `Agent` 工具（前台/后台）
- `BackgroundTaskManager` + `ShellManager` + JSONL 落盘 + `TaskOutput` / `TaskStop`
- `NotificationService.pushOrResume`（忙/闲双路径）
- `TodoService` + `TaskService`（TaskV2 全套）
- Plan 模式（进出、审批、Loop 重启）
- **产出**：子 Agent、后台命令、计划模式全部可用

### M8 收尾与打磨（第 14 周）
- `ToolSearch` 延迟加载（Anthropic + OpenAI Responses 双编码）+ 激活态持久化与恢复
- Doom loop 检测、Worktree 隔离、WebFetch/WebSearch
- 可观测性：LLM trace、`/status` 面板、OTel exporter SPI
- 性能调优：AppCDS、懒初始化、Part 写节流、启动 < 2.5s
- 自举验证（A-17）、全量验收（A-01~A-20）、文档
- **产出**：v0.1.0 发布

### 后续（不在 MVP）
- P2：LSP4J 客户端、Cron（Quartz）、外部 MCP Server、插件 Hooks（SPI + 拦截器链）
- P3：ACP 协议、Team 多 Agent、向量记忆、JGit 替换 git CLI、StructuredTaskScope（Java 25）

---

## 10. 风险登记册

| ID | 风险 | 概率 | 影响 | 缓解措施 |
|---|---|---|---|---|
| R-01 | **SSE 事件归一化边界情况多**（各家 delta 格式、tool_call 分片、thinking signature、usage 位置不一致） | 高 | 高 | M1 优先做；录制真实 fixture 回放测试；保留 `providerMetadata` 不丢信息；逐 Provider 独立实现不强行抽象 |
| R-02 | **虚拟线程 pinning**（第三方库内 `synchronized` 阻塞，如 SQLite JDBC、OkHttp） | 高 | 中 | `-Djdk.tracePinnedThreads=full` 常态化压测；DB 访问收敛到平台线程池或换用支持 Loom 的驱动；OkHttp 已适配 Loom |
| R-03 | **SQLite 并发写争用**（流式 Part 高频写） | 高 | 中 | Part 写节流（100ms/4KB 合并）；WAL + busy_timeout；BUSY 重试；必要时单写入线程串行化 |
| R-04 | **9 级替换策略链的相似度算法调参** | 中 | 高 | 从原项目移植测试用例；Levenshtein 阈值先取 0.0/0.3 两轮；策略链命中情况打点统计 |
| R-05 | **shadow git 在 Windows 的路径/换行/长路径问题** | 高 | 中 | `core.autocrlf=false`、`core.longpaths=true`；用 `ProcessBuilder` 调 git CLI 规避 JGit 差异；Windows 专项测试 |
| R-06 | **压缩摘要质量不足导致上下文丢失** | 中 | 高 | 摘要提示词强制结构化章节；`PostCompactionRestore` 补关键状态；保留尾部 20%；提供 `/undo-compact`（P2） |
| R-07 | **CLI 流式渲染体验不及 prompt_toolkit** | 高 | 中 | 明确降级预期：CLI 只做行式流式，复杂交互引导到 Web；用 JLine `Display` 做局部重绘减少闪烁 |
| R-08 | **Web 控制台工作量超估**（12 个面板） | 中 | 中 | 面板分优先级：主视图 + model/provider/config/tasks/rewind 为 P0，其余 P1；前端用现成组件库 |
| R-09 | **原项目提示词移植的许可与体量** | 中 | 中 | 提示词自行重写（保留结构不抄文本）；分块设计便于替换 |
| R-10 | **单人 14 周排期风险** | 高 | 中 | 每个里程碑独立可交付；M7/M8 内容可裁剪；优先保证 A-01~A-11 核心链路 |
| R-11 | **中断清理不彻底导致状态污染** | 中 | 高 | 启动时扫描修复 pending/running Part；FR-024 四时机专项测试；所有阻塞点强制超时 |
| R-12 | **延迟工具激活态在 resume/压缩后丢失** | 中 | 中 | 激活态双写（ToolPart metadata + CompactionSummaryMetadata）；启动 `restore()`；专项测试 |

---

## 11. 需求追踪矩阵

| 需求 | 设计章节 | 里程碑 | 验收用例 |
|---|---|---|---|
| FR-01 会话管理 | DDD §5.1, §7 | M0/M1 | A-01, A-09 |
| FR-02 Agent Loop | DDD §5.2, §5.14 | M1/M2 | A-02~A-08 |
| FR-03 Provider 与流式 | DDD §5.3 | M1 | A-02, A-20 |
| FR-04 上下文工程 | DDD §5.4 | M3 | A-03, A-20 |
| FR-05 压缩 | DDD §5.5 | M3 | A-10 |
| FR-06 工具体系 | DDD §5.6 | M2 | A-03~A-05 |
| FR-07 具体工具 | DDD §5.7 | M2/M7/M8 | A-03~A-05, A-13~A-16 |
| FR-08 权限 | DDD §5.8 | M2 | A-06 |
| FR-09 持久化 | DDD §5.9, §7 | M0 | A-09 |
| FR-10 快照回滚 | DDD §5.10 | M4 | A-11 |
| FR-11 事件总线 | DDD §5.11 | M0 | A-19 |
| FR-12 CLI | DDD §5.12 | M5 | A-01~A-18 |
| FR-13 Web 控制台 | DDD §5.13, §8 | M6 | A-19 |
| FR-14 配置 | DDD §5.15 | M0 | A-01 |
| FR-15 后台任务 | DDD §5.16 | M7 | A-14 |
| FR-16 可观测性 | DDD §5.17 | M8 | A-19 |
| NFR-01 并发模型 | DDD §5.14, §9.1 | 全程 | 质量门禁 |
| NFR-02 性能 | DDD §9.2 | M8 | 质量门禁 |
| NFR-05 可维护性 | DDD §4, §9.3 | 全程 | ArchUnit |
