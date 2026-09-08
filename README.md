# We0J —— Java 版 Coding Agent 运行时

<p>
  <img src="https://img.shields.io/badge/Java-21%20LTS-blue" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.4-brightgreen" alt="Spring Boot 3" />
  <img src="https://img.shields.io/badge/tests-485%20green-success" alt="tests" />
  <img src="https://img.shields.io/badge/virtual_threads-loom%20enabled-purple" alt="loom" />
</p>

> 面向真实软件工程任务的 Coding Agent 运行时（对标 Claude Code 产品形态），**Java 21 虚拟线程 + Spring Boot 3.4** 实现。
> 自研双循环 Agent 内核与模型接入层，**不依赖 LangChain4j / Spring AI**。
> UI 形态：交互式 CLI（picocli + JLine 3）+ 本地 Web 控制台（SSE + Vue3）。

## 它能做什么

用自然语言下达任务，Agent 自主完成代码**检索、编辑、命令执行与验证**：

```
$ we0j -p "项目里有个 src/Notes.java。任务：用 Edit 把 PENDING 改成 DONE，然后用 Bash 执行 javac && java 验证"

  Step 1: Read("src/Notes.java")                        ← 真实读文件
  Step 2: Edit(oldText="PENDING", newText="DONE")       ← 9 级替换策略链命中
  Step 3: Bash("javac -d . src/Notes.java && java Notes") → status = DONE
  Step 4: 汇报 ✅
  [exit: COMPLETED_REPLY, steps=4, tokens=8416, exit 0]
```

全过程：流式观测、权限管控（ask/allow/deny + always 记忆）、断点恢复、shadow git 快照回滚。

## 核心能力（已实现）

| 能力 | 说明 |
|---|---|
| **双循环 Agent 内核** | 外层历史驱动 + 内层流式消费；轮次状态由持久化历史推导，resume/回滚后状态自洽 |
| **模型接入层（自研）** | 手写 SSE 解析，将 Anthropic / OpenAI Chat / Gemini 流式协议统一为 17 种内部事件；tool_call 分片重组、thinking 签名回传、usage 多路归一（含 usage-after-finish 网关坑位） |
| **提示词缓存** | Anthropic cache_control 打点 + 缓存稳定性工程约束（块序固定/时间小时级/动态信息走 reminder） |
| **上下文压缩** | 经典摘要（API round 分组 + 隐藏子会话）+ 溢出反应式恢复 + 时间维工具结果裁剪，ChainGuard 熔断防死循环 |
| **工具体系** | 17 个内置工具（Read/Write/Edit/Bash/Grep/Glob/Todo/Task/Agent/SKILL/Plan/Worktree/AskUserQuestion/ToolSearch…）；victools 自动生成 JSON Schema；虚拟线程并发执行 |
| **权限引擎** | last-match-wins 规则求值（28 类权限 × 通配符模式 × allow/deny/ask），敏感操作阻塞等待授权，ALWAYS 级联放行 / REJECT 级联拒绝，Doom Loop 检测 |
| **编辑安全链路** | 读后写校验（50ms 容差）+ per-path 锁 + 9 级替换策略链 + unified diff + LSP 诊断占位 |
| **快照与回滚** | shadow git（独立 git-dir，`write-tree` 不 commit）；仅对话回滚 / 代码+对话双回滚 / 撤销回滚 |
| **Skills** | SKILL.md + frontmatter，分层扫描（全局←项目覆盖），WatchService 热加载，渐进式披露 |
| **后台子 Agent** | 隔离上下文子会话（JSONL 落盘），完成通知忙入队/闲落库唤醒父会话 |
| **CLI** | JLine 3 REPL：流式渲染、12 个 slash 命令、/rewind、headless `-p` + `--output-format stream-json` |
| **Web 控制台** | Spring MVC + SSE（有界队列/critical 不丢/Last-Event-ID 重放）+ Vue3（CDN 免构建）：会话/流式消息/权限问卷弹窗/状态面板 |

## 快速开始

### 环境要求

- JDK 21 LTS（Temurin 验证过 21.0.12）
- Maven 3.9+（配置 aliyun 镜像更佳）
- git ≥ 2.30（快照/回滚依赖）；ripgrep（可选，Grep/Glob 加速）

### 构建与自检

```bash
git clone https://github.com/subcarry/we0j.git
cd we0j
mvn install -DskipTests          # 或 mvn test 跑全量 485 测试
mvn -pl we0j-cli dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.pathSeparator=";"

# 环境自检（JDK/git/rg/目录可写/磁盘）
java -cp "we0j-cli/target/classes;we0j-agent/target/classes;we0j-tool/target/classes;we0j-llm/target/classes;we0j-infra/target/classes;we0j-common/target/classes;$(cat we0j-cli/target/cp.txt)" com.we0j.cli.We0jCommand doctor
```

### 配置模型

首次运行自动创建 `~/.we0j/settings.json`。编辑 `common.providers` 加入你的 Provider
（OpenAI 兼容网关示例）：

```json
{
  "common": {
    "chat": { "default": { "provider": "my-gateway", "model": "glm-5.3-flash" } },
    "providers": {
      "my-gateway": {
        "enabled": true,
        "apiKey": "sk-...",
        "apiBase": "https://your-gateway/v1",
        "family": "openai-compatible",
        "models": ["glm-5.3-flash"]
      }
    }
  }
}
```

### 运行

```bash
CP="we0j-cli/target/classes;we0j-agent/target/classes;we0j-tool/target/classes;we0j-llm/target/classes;we0j-infra/target/classes;we0j-common/target/classes;$(cat we0j-cli/target/cp.txt)"

# 交互式 REPL（12 个 slash 命令：/new /compact /rewind /status /model …）
java -cp "$CP" com.we0j.cli.We0jCommand

# 一次性执行（headless）
java -cp "$CP" com.we0j.cli.We0jCommand -p "用一句话介绍你自己" --workdir <项目目录>

# Web 控制台（浏览器打开 http://127.0.0.1:8787，token 见 ~/.we0j/settings.json）
java -cp "$CP" com.we0j.server.We0jServerApplication --we0j.root=<项目目录>
```

> **Windows + Java 8 共存提示**：全局 `JAVA_HOME` 可保持公司 JDK 8 不变；
> 开发 We0J 时在终端执行 `D:\dev\we0j-env.cmd`（会话级切换 JDK 21，见设计文档 §4.4）。

## 架构总览

```
┌─ 接入层 ─────────────────────────────────────────────┐
│  we0j-cli (picocli+JLine3 REPL)   we0j-server (REST+SSE+Vue3) │
└──────────────────┬───────────────────────────────────┘
                   ▼ SessionFacade（唯一入口）
┌─ 运行时层 we0j-agent ────────────────────────────────┐
│  AgentLoop（外层历史驱动）→ TurnProcessor（内层流式）   │
│  SessionService/Cache/Registry │ CompactionService    │
│  SnapshotService(shadow git) │ RevertService │ Skills │
│  BackgroundTaskManager + NotificationService(忙/闲)   │
└──────────────────┬───────────────────────────────────┘
                   ▼
┌─ 能力层 ─────────────────────────────────────────────┐
│  we0j-tool: Tool SPI + 17 内置工具 + 权限引擎 + 提问    │
│  we0j-llm:  Provider SPI + SSE 归一化 + 重试 + 缓存    │
└──────────────────┬───────────────────────────────────┘
                   ▼
┌─ 基础设施 we0j-infra ────────────────────────────────┐
│  Bus(会话分片有序) │ Settings(分层热加载) │ SQLite(WAL) │
│  AbortSignal(级联取消) │ PartWriteThrottler │ FileLocks│
└──────────────────────────────────────────────────────┘
```

分层依赖单向（ArchUnit 测试强制）：`cli/server → agent → tool → llm → infra → common`，
common 零 Spring 依赖，agent 内核零 IM 语义，全仓禁 `synchronized`（虚拟线程 pinning）。

## 里程碑状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 工程骨架（7 模块 + 领域模型 + ArchUnit） | ✅ v0.0.1-m0 |
| M1 | Provider 层（SSE 归一化）+ 最小 Agent Loop | ✅ v0.0.2-m1 |
| M2 | 工具体系（17 内置）+ 权限引擎 + 9 级替换策略链 | ✅ v0.0.3-m2 |
| M3 | 上下文工程（Contributor SPI）+ 压缩三策略 | ✅ v0.0.4-m3m4 |
| M4 | 快照（shadow git）+ 双模式回滚 | ✅ v0.0.4-m3m4 |
| M5 | 交互 CLI（REPL + 12 slash 命令 + stream-json） | ✅ f3f4cdd |
| M6 | Web 控制台（REST + SSE + Vue3） | ✅ f2d8bb7 |
| M7 | Skills 热加载 + 后台子 Agent + 通知回流 | ✅ v0.0.5-m7 |
| M8 | 收尾：bug 闭环 + 自举验证 + 发布 | ✅ v0.0.6-m8 |

**当前：485 测试全绿（0 disabled），7 模块 SUCCESS。**

## 已知限制 / 待办

- Gemini 与 OpenAI Responses Provider 未实现（OpenAI-Chat 路径已覆盖主验收）
- LSP 代码智能为 no-op 占位（接口就绪，待 LSP4J 接入）
- Web 控制台部分面板为简化版（消息流虚拟滚动、用量图表待做）
- Team 多 Agent 协作、向量记忆：未纳入范围（接口预留）

## 文档

| 文档 | 说明 |
|---|---|
| [docs/00-README-导航.md](docs/00-README-导航.md) | 原项目架构速览、范围界定、术语表 |
| [docs/01-需求文档-SRS.md](docs/01-需求文档-SRS.md) | 92 条功能需求 + 非功能需求 + 20 条验收场景 |
| [docs/02-详细设计文档-DDD.md](docs/02-详细设计文档-DDD.md) | 技术选型映射、领域模型、DDL、API 协议、实施 Checklist |

## License

暂未定（待正式发布时确定）
