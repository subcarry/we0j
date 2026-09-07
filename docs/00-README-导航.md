# We0J —— We0 Code 的 Java 复现工程文档集

> 目标：以 **Java 21 虚拟线程 + Spring Boot 3** 复现 [We0 Code](https://github.com/Mashiro2000/We0Code) 的核心 MVP，
> UI 形态降级为 **CLI（picocli + JLine 3）+ 本地 Web 控制台（SSE）**。

## 文档索引

| 文档 | 内容 | 用途 |
|---|---|---|
| `00-README-导航.md` | 本文档：项目概述、原项目架构速览、范围界定、术语表 | 入口 |
| `01-需求文档-SRS.md` | 功能需求（FR-001~FR-090）、非功能需求、用例、数据需求、验收标准、里程碑 | 定义"做什么" |
| `02-详细设计文档-DDD.md` | 技术选型映射、模块划分、领域模型、每个子系统的接口签名 + 实现逻辑 + 关键代码、DDL、API、时序 | 定义"怎么做" |

## 一、原项目（Python）架构速览

分析基准：`github.com/Mashiro2000/We0Code`，Python 3.12，约 **145,637 行**、488 个 `.py` 文件。

### 1.1 代码分布

| 模块 | 行数 | 职责 |
|---|---|---|
| `we0_agent/core/` | 82,552 | 运行时编排与领域服务（Agent Loop、Session、工具、权限、LSP、快照、后台、Team） |
| `we0_agent/tui/` | 49,111 | prompt_toolkit 全屏 TUI（对标 Claude Code） |
| `we0_agent/cli/` | 5,287 | click 命令行入口（~30 个子命令） |
| `we0_agent/acp/` | 4,892 | Agent Client Protocol（stdio JSON-RPC，对接 Zed 等编辑器） |
| `we0_agent/common/` | 3,638 | 领域实体、配置、常量、异常、共享 client |

### 1.2 核心执行路径（必须吃透）

```
we0_agent/core/session/
├── session.py          会话生命周期与顶层协调（stream_messages / update_part / set_revert）
├── prompt.py           ★ SessionPrompt.loop() —— 真正的外层 Agent Loop（prompt.py:1088）
├── processor.py        ★ SessionProcessor.process() —— 内层单轮流式处理（processor.py:113）
├── llm.py              ★ 模型交互层：stream() / full_stream() 事件归一化（llm.py:704-830）
├── compaction.py       上下文压缩（3061 行，三种策略）
├── tool_resolver.py    工具解析与执行闭环
├── tool_activation.py  延迟工具激活状态机
├── deferred_tool_search.py  ToolSearch 的 anthropic/openai 双传输编码
├── context_builder.py + context_builders/  系统提示词与上下文装配
├── helper/loop_helper.py  ★ inject_system_reminders()（:1782）等 2000+ 行循环辅助
├── revert.py           回滚（conversation / both 两种模式）
├── cron.py             手写 5 字段 cron 解析 + next-match 分钟步进
├── task.py / todo.py / status.py / thinking_state.py
└── message.py          ★ Message / Part 领域模型（550 行，JSON blob 持久化载体）
```

> **重要认知纠偏**：`core/agent/agent.py` 不是 Agent Loop，它是 **AgentRegistry**（解析 markdown 定义的 `build`/`plan`/`explore` 等 agent 人格）。真正的循环在 `core/session/prompt.py::SessionPrompt.loop()`。

### 1.3 双循环结构

```
外层循环 SessionPrompt.loop()  (prompt.py:1144 `while True`)
  每轮 = 一次"assistant 请求周期"，状态完全由 DB 历史推导
  ├─ 1. SessionStatus = Busy；检查 abort
  ├─ 2. Session.stream_messages() → filter_compacted()（丢弃压缩边界前的消息）
  ├─ 3. extract_messages / extract_markers → last_user / last_assistant / last_finished / tasks
  ├─ 4. 退出判定 _has_completed_reply_for_last_user()  ← 主退出条件
  ├─ 5. 压缩任务处理 SessionCompaction.process() → continue / break
  ├─ 6. 溢出检查 → 调度压缩 → continue
  ├─ 7. 上下文构建：insert_reminders → inject_system_reminders → ToolResolver.resolve
  │      → ContextBuilder.build_system → messages_to_model → 请求前溢出复检
  ├─ 8. 建 AssistantMessage + SessionProcessor → processor.process(streamInput)  【内层循环】
  └─ 9. 工具子循环 (prompt.py:1527 `while processor.tool_calls and result=="continue"`)
         ├─ asyncio.gather 并发执行本批全部工具调用  (prompt.py:1556)
         ├─ 追加 assistant(tool_calls) + role:"tool" 结果到内存 model_messages
         ├─ step += 1
         └─ 新建 SessionProcessor / AssistantMessage → 再次 process()

内层循环 SessionProcessor.process()  (processor.py:113)
  = 一次 `async for event in llm.full_stream(...)` 全量消费
  返回 "compact" | "stop" | "continue"
```

### 1.4 关键子系统能力清单（复现范围判据）

| 子系统 | Python 实现要点 | MVP |
|---|---|---|
| **流式事件归一化** | litellm 统一 delta → 16 种 AI-SDK 风格事件（`EventStart`…`EventError`） | ✅ 必做（自研，不用 litellm 等价物） |
| **提示词缓存** | `transform.py:179 apply_caching`，Anthropic `cache_control: ephemeral` 打在 system + 末 2 条消息 | ✅ |
| **上下文压缩** | 经典摘要 / 反应式溢出恢复 / 时间维微压缩（tool result 裁剪），熔断器 `MAX_CONSECUTIVE_FAILURES=3` | ✅ |
| **Reminder 注入** | `inject_system_reminders()` 8 类合成 TextPart，带 `metadata.source` 去重 | ✅ |
| **事件总线** | `BusEventDef[T]` 泛型注册表 + 通配符 `*`，TUI/ACP/持久化/插件四方订阅 | ✅ |
| **持久化** | SQLite(WAL) + SQLModel，3 张表，message/part 为 **JSON blob 薄壳表** | ✅ 保留该设计 |
| **快照回滚** | shadow git（独立 `--git-dir` + `--work-tree`），`write-tree` 不 commit，hash 记在 StepStart/StepFinish Part | ✅ |
| **回滚双模式** | `conversation`（只标记边界）/ `both`（先 track 当前 hash 供 unrevert，再 revert 代码） | ✅ |
| **权限系统** | `PermissionName`(28 种) × wildcard pattern × `allow/deny/ask`，**last-match-wins**，回复 `once/always/reject`，工具协程 await Future 阻塞 | ✅ |
| **AskUserQuestion** | 1-4 问 × 2-4 选项 × multiSelect × preview，Bus 事件冒泡到 UI | ✅ |
| **编辑安全链路** | FileTime 读时间戳 + 50ms 容差 staleness 校验 + per-path 锁 + 9 级替换策略链 + diff + LSP 后验证 | ✅ |
| **Bash** | tree-sitter-bash 解析命令前缀生成权限 pattern；`create_subprocess_shell`；8192 chunk 流式 + 落盘；超时/abort → psutil 杀进程树；后台模式 | ✅（tree-sitter 降级为前缀解析器） |
| **Grep/Glob** | 调用内置 ripgrep 二进制，`MATCH_LIMIT=100`，按 mtime 倒序 | ✅ |
| **延迟工具加载** | `ToolSearch` + `tool_reference`，`defer_loading` 模型特性门控，Anthropic 原生块 / OpenAI responses 内嵌 schema 双编码 | ✅ |
| **Skills** | `SKILL.md` + YAML frontmatter → SkillCard，渐进式披露（仅 name+description 进上下文），watchfiles 热加载，`Skill` 工具按需返回正文 | ✅ |
| **后台子 Agent** | `Agent` 工具建子 session（`parent_id`），工具 overlay 屏蔽 Agent/Team，JSONL 落盘输出，完成通知 `push_or_resume`（忙→队列注入 reminder，闲→合成 UserMessage 唤醒 loop） | ✅ |
| **后台 Shell** | `ShellManager`（信号量上限 10），id `bash_{ms}_{uuid8}`，逐行落盘，10MB 上限 | ✅ |
| **Todo / Task** | TodoWrite/TodoRead；TaskV2（status/owner/blocks/blockedBy，文件持久化，依赖为**建议性**非强制） | ✅ |
| **Plan 模式** | EnterPlanMode/ExitPlanMode，模式切换触发 loop 重启重读历史 | ✅ |
| **LSP** | 手写 stdio JSON-RPC 2.0（`Content-Length` 帧），10 个内置 server，8 种 operation，写后诊断回填 | ⭕ P2（用 LSP4J） |
| **Cron** | 手写 5 字段解析 + 分钟步进 next-match（≤527040 次迭代），job-id hash 抖动，3 天老化 | ⭕ P2（用 Quartz CronExpression） |
| **Team 多 Agent** | lead + teammates 扁平名册，mailbox 文件协议，常驻 inbox-drain 循环 | ❌ 暂不做 |
| **插件 Hooks** | 12 个 hook 点（`chat.params`/`tool.execute.before`/…），in-process Python | ⭕ P2（Java SPI + 拦截器链） |
| **ACP 协议** | stdio JSON-RPC，session/new·load·prompt·update、request_permission、plan updates | ❌ 暂不做 |
| **TUI 全屏** | prompt_toolkit `Application` + 布局树 + Rich/Pygments + 快照轮询渲染（0.25s busy / 0.5s idle） | ❌ 降级为 CLI + Web |
| **Memory（sqlite-vec）** | 向量语义记忆 Recall/OperateMemory | ❌ 暂不做 |
| **Telegram companion（core/we0/）** | IM 陪伴 Agent | ❌ 不复现 |

### 1.5 必须切断的耦合（原项目的"泄漏边界"）

原 `core/` 对 `core/we0/`（IM companion）存在约 11 处反向依赖：
`session/prompt.py`（MemoryCleanup/提取/onboarding）、`session/context_builder.py`（`IMContextBuilder`）、`context_builders/base.py`、`session/compaction.py`、`mcp/builtins/bash.py`、`mcp/builtins/memory.py`（sqlite-vec `MemoryService`）、`project/bootstrap.py`、`prompts/register.py`、`entities/we0_tasks.py`。

**Java 版设计原则**：这些能力一律抽象为可插拔接口（`ContextContributor` / `MemoryProvider` / `TurnLifecycleListener`），coding-agent 内核不得出现任何 IM 语义。这是 Java 版相对原版的**架构改进点**，也是面试可讲的"我识别并修正了原设计的循环依赖"。

## 二、术语表

| 术语 | 定义 |
|---|---|
| **Session（会话）** | 一次连续的 Agent 交互上下文，对应 DB `session` 表一行，持有独立消息历史、工具 overlay、权限运行时规则 |
| **Message（消息）** | `UserMessage` 或 `AssistantMessage`，是 Part 的容器 |
| **Part（消息片段）** | 消息的最小语义单元：`text` / `reasoning` / `tool` / `step-start` / `step-finish` / `patch` / `file` / `compaction` / `retry` / `agent` / `snapshot`。**持久化与流式更新的最小粒度** |
| **Turn（轮次）** | 一次用户输入触发的完整处理周期，可能包含多个 step |
| **Step（步）** | 一次模型请求 + 其产出的工具调用批次。`step-start` / `step-finish` Part 标记边界并记录 git tree hash |
| **Loop（Agent Loop）** | 外层历史驱动循环 + 内层流式消费循环的双循环结构 |
| **Reminder（系统提醒）** | 注入到用户消息上的合成 `TextPart`，`metadata.source` 标识来源，用于承载 AGENTS.md 锚点、延迟工具清单、后台通知、skill 清单、团队上下文等 |
| **Compaction（压缩）** | 上下文瘦身。三策略：经典摘要压缩、反应式溢出恢复、时间维工具结果微压缩 |
| **Snapshot（快照）** | shadow git 的 tree hash，非 commit。记录在 StepStart/StepFinish Part 上 |
| **Rewind / Revert（回滚）** | `conversation` 模式仅回退对话；`both` 模式同时回退代码 |
| **Lane（运行时泳道）** | `MAIN` / `SIDE_LLM` / `SIDE_AGENT`，用于门控"仅主 Agent 可做"的决策（如自动压缩） |
| **Deferred Tool（延迟工具）** | schema 不随请求下发的 lazy 工具，需模型先调 `ToolSearch` 激活 |
| **Gate（门控）** | 权限/提问的阻塞点。工具在虚拟线程上阻塞等待 UI 回复 |
| **AbortSignal（中断信号）** | 级联取消句柄，等价于 Python 的 `asyncio.Event` |

## 三、范围与约束总纲

### In Scope（MVP，P0/P1）
Agent 双循环内核、多 Provider 流式归一化、提示词缓存、上下文压缩三策略、17 个内置工具、权限系统、AskUserQuestion、编辑安全链路、Session 持久化与 resume、快照与双模式回滚、Todo/Plan、Skills（含热加载）、后台子 Agent、后台 Shell、事件总线、分层配置、CLI、Web 控制台（SSE）。

### Out of Scope（明确不做，写进需求文档避免范围蔓延）
Telegram companion 全部能力、Team 多 Agent 协作、ACP 协议、sqlite-vec 语义记忆、全屏 TUI、多租户、云端部署、Windows 之外平台的深度适配（保证 Linux/macOS 可跑但不做专项优化）。

### Deferred（P2，架构预留接口但不实现）
LSP 客户端（预留 `CodeIntelligence` 接口）、Cron（预留 `ScheduledJobService` 接口）、插件 Hooks（预留 `HookChain` 接口）、外部 MCP Server（预留 `McpClientTransport` 接口）。

### 硬约束
- Java 21 LTS，Spring Boot 3.4+，Maven 多模块
- **禁止引入 LangChain4j / Spring AI 作为 Agent Loop 内核**（这是项目的核心价值点，必须自研；可在文档中说明为何不用）
- 零外部服务依赖：单机可跑，SQLite 文件库，git/ripgrep 二进制随包分发或走系统 PATH
- 所有阻塞 IO 跑在虚拟线程上，禁用 `synchronized` 包裹长阻塞（pinning），改用 `ReentrantLock`
