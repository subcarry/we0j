# We0J 详细设计文档（DDD）

- **文档版本**：v1.0
- **配套**：`01-需求文档-SRS.md`（需求编号 FR-xxx / NFR-xx 在本文中被引用）
- **实现语言**：Java 21 LTS（虚拟线程）+ Spring Boot 3.4+
- **阅读前提**：先读 `00-README-导航.md` 第 1 章（原项目架构速览）

---

## 目录

1. [技术选型总表](#1-技术选型总表)
2. [总体架构](#2-总体架构)
3. [领域模型设计](#3-领域模型设计)
4. [基础设施层设计](#4-基础设施层设计)
5. [核心子系统详细设计](#5-核心子系统详细设计)
6. [关键流程时序](#6-关键流程时序)
7. [数据库设计（完整 DDL）](#7-数据库设计完整-ddl)
8. [Web API 与 SSE 协议](#8-web-api-与-sse-协议)
9. [关键技术难点专章](#9-关键技术难点专章)
10. [测试策略](#10-测试策略)
11. [工程规范](#11-工程规范)
12. [实施 Checklist](#12-实施-checklist)

---

## 1. 技术选型总表

### 1.1 逐项映射（Python → Java）

| 能力 | 原项目（Python） | We0J（Java） | 选型理由 | 被否决的备选 |
|---|---|---|---|---|
| **语言/运行时** | Python 3.12 + asyncio 单事件循环 | **Java 21 LTS + 虚拟线程（Loom）** | thread-per-task 模型让"阻塞式代码 + 海量并发"两得；无需 async 染色，调用栈完整可调试 | Kotlin 协程（团队熟悉度）；Reactor/WebFlux（CLI 场景调试成本高、栈不可读） |
| **DI / 生命周期** | 手写单例 + `Instance.state()` + ContextVar | **Spring Boot 3.4 `@Configuration` + `@Component`** | 工具/Contributor/Hook 天然是插件式 bean 集合；配置绑定 `@ConfigurationProperties` 省事 | Guice（生态弱）；纯手写（重复造轮子） |
| **领域模型校验** | pydantic `BaseModel` + `Field` | **Java `record` + `sealed interface` + Jackson 2.17 + jakarta.validation（Hibernate Validator）** | record 不可变、模式匹配友好；sealed 精确表达 Part/ToolState/MessageError 的判别联合 | Lombok `@Value`（record 已足够）；Kotlin data class |
| **JSON 多态** | pydantic `Field(discriminator="status")` | **`@JsonTypeInfo(use=NAME, property="type")` + `@JsonSubTypes`** | 与 Part 的 `type` 判别字段天然对应 | Gson TypeAdapter（手写量大） |
| **JSON Schema 生成** | FastMCP 从函数签名 + `pydantic.Field` 推导 | **`com.github.victools:jsonschema-generator` 4.35 + `jackson-module`** | 从 record 自动生成 draft 2020-12 schema，支持 `@Schema` 描述、必填、范围约束 | 手写 schema（易漂移） |
| **模型接入 / 流式** | `litellm.acompletion` 统一 100+ Provider | **自研 `ModelProvider` SPI + OkHttp 4 + 手写 SSE 解析**（`AnthropicProvider` / `OpenAiChatProvider` / `OpenAiResponsesProvider` / `GeminiProvider`） | ★**项目核心价值点**：litellm 把归一化黑盒化了，自研才能讲清"模型事件归一化"。只支持 4 类端点，工作量可控且质量更高 | LangChain4j（黑盒、事件粒度不够细、无法精确控制 cache_control）；Spring AI（同上，且抽象偏 RAG）；官方 SDK（Anthropic/OpenAI Java SDK 对流式 tool_call 分片暴露不足） |
| **HTTP 客户端** | httpx[socks] | **OkHttp 4.12** | 已适配 Loom（无 pinning）；连接池、HTTP/2、拦截器、SOCKS 代理齐全；`ResponseBody.source()` 逐行阻塞读天然适配虚拟线程 | JDK HttpClient（流式 API 别扭、代理配置弱）；Apache HttpClient 5（重）；WebClient（需 Reactor） |
| **工具协议** | FastMCP 3.4（in-process `FastMCP()` + `@mcp.tool` 装饰器 + mount/namespace） | **自研 `Tool` SPI + `ToolRegistry`**（in-process）；**外部 MCP 用 `io.modelcontextprotocol.sdk:mcp` 0.10**（P2） | 内置工具走进程内接口，零序列化开销、可直接拿 `ToolContext`；外部 MCP 才需要协议层 | 全部走 MCP Java SDK（进程内自调用绕一圈 JSON-RPC，性能与调试都差） |
| **TUI** | prompt_toolkit 全屏 `Application` + 布局树（49,111 行） | **降级：picocli 6 + JLine 3.25（行式流式 CLI）** | Java 无全屏 TUI 等价物；硬做投入产出比极低。行式流式 + Web 补复杂面板是务实解 | Lanterna（全屏但生态停滞、组件需全自研）；JLine `Display` 全屏（可行但工作量 ≈ 重写半个 prompt_toolkit） |
| **复杂面板 UI** | TUI 内 float 模态 | **Web 控制台：Spring MVC + SSE(`SseEmitter`) + Vue 3 + Vite 5 + TypeScript + Pinia + TailwindCSS 3** | MVC 阻塞 + 虚拟线程即可（`spring.threads.virtual.enabled=true`），不必上 WebFlux；浏览器天然富渲染（diff、高亮、图表） | React（等价，按熟悉度选）；Thymeleaf 服务端渲染（实时性差）；WebSocket（SSE 更简单、单向足够，回复走 REST） |
| **Markdown / 高亮** | Rich + markdown-it + Pygments | CLI：**commonmark-java 0.22 + jansi 2.4**（轻量着色）；Web：**marked + highlight.js + shiki**（前端渲染） | 渲染责任下沉到浏览器，CLI 只需可读 | flexmark（更全但重） |
| **ORM / DB** | SQLModel + aiosqlite + `create_async_engine`；无版本化迁移（`PRAGMA table_info` 自愈） | **Spring Data JPA（Hibernate 6.6）+ `xerial/sqlite-jdbc` 3.46 + Flyway 10** | Flyway 版本化迁移是**对原项目的架构改进**；Hibernate 对 JSON blob 列用 `@JdbcTypeCode(SqlTypes.JSON)` 或直接 `String` + 应用层序列化 | MyBatis-Plus（团队熟，但 JSON blob 多态反序列化要手写更多）；jOOQ（SQLite 支持一般）；H2（不贴近原项目且文件更大） |
| **JSON 文件状态存储** | `*StorageState` 类（todos/tasks/crons/inbox） | **`JsonFileStore<T>` 泛型组件 + `FileLock`（`FileChannel.tryLock`）** | 保留原设计（这些状态是"文档型"，进 DB 反而割裂）；文件锁解决并发读改写丢写 | 全进 SQLite（需 migration，收益低） |
| **git 操作** | `subprocess` 调 git CLI（独立 `--git-dir` + `--work-tree`） | **`ProcessBuilder` 调 git CLI**，`SnapshotService` 接口抽象 | 忠实还原原实现；shadow git-dir + work-tree 组合在 JGit 里坑多（`write-tree` 需手工 `DirCache.writeTree`，checkout-index 无直接等价） | JGit 6.10（作为 P3 优化项，减少外部依赖） |
| **ripgrep** | 打包内置 rg 二进制 | **系统 PATH 探测 + 随 jar 分发（`resources/bin/`）+ Java NIO 降级** | 保持性能；降级保证可用性 | 纯 Java 正则遍历（10 万文件太慢） |
| **Bash 命令解析** | `tree_sitter_bash` AST | **自研 `BashCommandParser`（递归下降 + 引号/转义状态机）**；P3 可换 `jtreesitter` | MVP 只需切分子命令 + 提取前缀 + 解析路径参数，不需要完整 AST；自研 300 行可控 | jtreesitter（需 native lib，打包复杂）；ANTLR bash 语法（无现成可靠语法） |
| **进程树杀死** | `psutil` | **`ProcessHandle.descendants()` + `destroyForcibly()`** | JDK 9+ 原生，跨平台 | 调 `taskkill /T` / `pkill -P`（平台分裂） |
| **diff 生成** | `difflib.unified_diff` | **`io.github.java-diff-utils:java-diff-utils` 4.12** | 成熟、支持 unified/上下文行号 | 自研 Myers（无必要） |
| **相似度（策略链）** | Levenshtein | **`commons-text` `LevenshteinDistance` + `JaroWinklerSimilarity`** | 现成、可调阈值 | 自研 DP |
| **Token 计数** | 无本地 tokenizer，`len(text)//4` 粗估 + provider usage | **`com.knuddels:jtokkit` 1.1（`o200k_base` / `cl100k_base`）** + provider usage 校准 | **对原项目的改进**：请求前可精确预判溢出，而非等 400 | 纯 `length()/4`（作为未知模型兜底保留） |
| **YAML frontmatter** | `strictyaml` / 自研 parser | **`snakeyaml` 2.2**（Skill / Agent / Command 的 frontmatter） | 标准 | Jackson YAML（等价） |
| **文件监听（Skill 热加载）** | `watchfiles` | **`java.nio.file.WatchService` + 2s 轮询兜底（macOS/网络盘）** | JDK 原生；macOS 的 `WatchService` 基于轮询，需自己补去抖 | commons-io `FileAlterationMonitor`（纯轮询，可作兜底实现） |
| **日志** | loguru | **SLF4J 2 + Logback 1.5**（JSON encoder：`logstash-logback-encoder`） | 标准；MDC 支持 lane/session 维度 | Log4j2（等价，性能更好但配置繁） |
| **CLI 解析** | click 8 + 自研 `LazyGroup` | **picocli 4.7**（原生支持懒加载子命令 `ISubcommands`、彩色输出、补全脚本生成） | 功能对齐 click | JCommander（弱）；Spring Shell（重，且与 REPL 模型冲突） |
| **REPL 输入** | prompt_toolkit `TextArea` + Completer + History | **JLine 3：`LineReaderBuilder` + `Completer` + `FileHistory` + `AttributedString`** | 补全、历史、多行、 ANSI 齐全 | 自研 raw mode 读取 |
| **JSON-RPC（LSP/ACP，P2）** | 手写 stdio 帧（`Content-Length`） | **`org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc` 0.23** | 现成帧处理 + 双向请求 + 取消 | 手写（LSP4J 已足够轻） |
| **定时任务（Cron，P2）** | 手写 5 字段解析 + 分钟步进 | **Quartz `CronExpression`（仅用其解析类，不用调度器）** + 每会话虚拟线程 tick | Quartz 的 `CronExpression` 是工业级 7 字段解析；调度器太重不用 | Spring `@Scheduled`（不支持动态 per-session cron）；cron-utils（可选替代） |
| **成本计算** | `Decimal` | **`BigDecimal`**（`RoundingMode.HALF_UP`, scale=6） | 精确 | double（误差） |
| **ID 生成** | uuid | **ULID**（`com.github.f4b6a3:ulid-creator` 5.2） | 时间有序 → SQLite 主键索引局部性好、可按时间扫描 | UUIDv7（等价可选）；UUIDv4（无序，索引差） |
| **架构守护** | 自研 `scripts/check_imports.py` | **ArchUnit 1.3** | 测试即门禁，强制分层与"无 IM 语义"约束 | 手工 review |
| **取消信号** | `asyncio.Event` | **自研 `AbortSignal`（volatile + CompletableFuture + 级联子信号）** | 见 §4.4 | `Thread.interrupt()`（单独用不够：无法级联、无法注册清理动作）；Reactor `Disposable`（不用 Reactor） |
| **并发聚合** | `asyncio.gather(return_exceptions=True)` | **`CompletableFuture` + `Executors.newVirtualThreadPerTaskExecutor()`** | Java 21 GA 稳定路径 | `StructuredTaskScope`（Java 21 为 preview，**不使用**；Java 25 正式后可切换，见 §9.1） |
| **上下文传递** | `contextvars.ContextVar`（Lane） | **`ThreadLocal<RuntimeLane>` + 显式传递到子任务** | 虚拟线程无池化复用，ThreadLocal 安全；但**不能用 InheritableThreadLocal**（虚拟线程默认不继承） | ScopedValue（Java 21 preview，不用） |

### 1.2 依赖清单（parent pom `<dependencyManagement>`）

```xml
<properties>
  <java.version>21</java.version>
  <spring-boot.version>3.4.1</spring-boot.version>
  <jackson.version>2.17.2</jackson.version>
  <okhttp.version>4.12.0</okhttp.version>
  <sqlite-jdbc.version>3.46.1.3</sqlite-jdbc.version>
  <hibernate.version>6.6.2.Final</hibernate.version>
  <flyway.version>10.20.1</flyway.version>
  <picocli.version>4.7.6</picocli.version>
  <jline.version>3.25.1</jline.version>
  <jtokkit.version>1.1.0</jtokkit.version>
  <diffutils.version>4.12</diffutils.version>
  <commonmark.version>0.22.0</commonmark.version>
  <victools.version>4.35.0</victools.version>
  <ulid.version>5.2.3</ulid.version>
  <archunit.version>1.3.0</archunit.version>
  <commons-text.version>1.12.0</commons-text.version>
  <snakeyaml.version>2.2</snakeyaml.version>
  <jsoup.version>1.18.1</jsoup.version>
  <pdfbox.version>3.0.3</pdfbox.version>
  <lsp4j.version>0.23.1</lsp4j.version>       <!-- P2 -->
  <mcp-sdk.version>0.10.0</mcp-sdk.version>   <!-- P2 -->
  <quartz.version>2.3.2</quartz.version>      <!-- P2, 仅 CronExpression -->
</properties>
```

**明确禁止引入**：`langchain4j-*`、`spring-ai-*`、`reactor-core`（除 Spring Boot 传递依赖外不主动使用）、`projectlombok`（用 record）。

### 1.3 Maven 模块结构

```
we0j-parent (pom)
├── we0j-common      领域模型、异常、常量、工具类、ID 生成        （零 Spring 依赖）
├── we0j-infra       配置分层、存储(JPA/Flyway)、事件总线、日志、路径解析、JSON 文件存储、AbortSignal
├── we0j-llm         Provider SPI、SSE 解析、事件归一化、重试、缓存打点、token 计数、定价
├── we0j-tool        Tool SPI、Registry、Resolver、Executor、权限、Question、内置工具实现
├── we0j-agent       Session、AgentLoop、上下文构建、压缩、快照回滚、Skills、Agent 人格、后台任务、通知
├── we0j-cli         picocli + JLine REPL + 流式渲染器（可执行 jar，依赖 server 可选）
└── we0j-server      Spring Boot 启动器、REST、SSE、静态 Web 资源
```

**依赖方向（ArchUnit 强制）**：
```
cli ─┐
     ├─→ agent ─→ tool ─→ llm ─→ infra ─→ common
server ┘              └────────────┘
```
- `common` 不依赖任何模块，不依赖 Spring
- `agent` 不得出现任何 IM/companion 语义（ArchUnit 规则：`noClasses().that().resideInAPackage("..agent..").should().dependOnClassesThat().haveNameMatching(".*(Telegram|Companion|Persona).*")`）
- `cli` 与 `server` 互不依赖；`server` 可选依赖 `cli`（`we0j web` 子命令）

---

## 2. 总体架构

### 2.1 分层视图

```
┌──────────────────────────────────────────────────────────────────────┐
│  接入层  Presentation                                                 │
│  ┌────────────────────────┐   ┌──────────────────────────────────┐   │
│  │ we0j-cli               │   │ we0j-server                      │   │
│  │ picocli 命令树          │   │ Spring MVC REST + SseEmitter     │   │
│  │ JLine REPL + 流式渲染   │   │ Vue3 SPA（内嵌静态资源）          │   │
│  │ CLI 权限/提问交互       │   │ 12 个控制面板                     │   │
│  └───────────┬────────────┘   └───────────────┬──────────────────┘   │
└──────────────┼────────────────────────────────┼──────────────────────┘
               │  两者都只是 Bus 的订阅者 + SessionFacade 的调用者      
               ▼                                ▼                      
┌──────────────────────────────────────────────────────────────────────┐
│  会话门面  SessionFacade（唯一入口，CLI/Web 共用）                     │
│  prompt() / cancel() / resume() / rewind() / setMode() / setModel()   │
└───────────────────────────────┬──────────────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│  运行时层  we0j-agent                                                 │
│  ┌─────────────────────────────────────────────────────────────┐     │
│  │ AgentLoop（外层）  ──  SessionRegistry（每会话互斥）          │     │
│  │   └─ TurnProcessor（内层）                                    │     │
│  │        ├─ ContextAssembler（SystemPrompt + Contributors）     │     │
│  │        ├─ HistoryConverter（Part → provider messages）        │     │
│  │        ├─ ToolResolver（可见工具集 + lazy 计算）              │     │
│  │        └─ ModelClient.stream()                                │     │
│  ├─────────────────────────────────────────────────────────────┤     │
│  │ CompactionService │ SnapshotService │ RevertService           │     │
│  │ SkillService      │ AgentRegistry   │ BackgroundTaskManager   │     │
│  │ NotificationService │ TodoService   │ TaskService             │     │
│  └─────────────────────────────────────────────────────────────┘     │
└───────────────────────────────┬──────────────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│  能力层                                                               │
│  ┌────────────────────────┐  ┌──────────────────────────────────┐    │
│  │ we0j-tool              │  │ we0j-llm                         │    │
│  │ ToolRegistry           │  │ ProviderRegistry                 │    │
│  │ ToolExecutor（并发）    │  │ ModelCardManager                 │    │
│  │ PermissionService      │  │ SseParser + 4×Provider           │    │
│  │ QuestionService        │  │ EventNormalizer（16 事件）        │    │
│  │ 17 个内置 Tool         │  │ RetryPolicy / ErrorClassifier    │    │
│  │ FileTimeRegistry       │  │ CacheMarker / TokenCounter       │    │
│  └────────────────────────┘  └──────────────────────────────────┘    │
└───────────────────────────────┬──────────────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│  基础设施层  we0j-infra                                               │
│  Bus（按 session 分片有序分发） │ SettingsStore（分层+mtime热加载）    │
│  JPA Repository + Flyway + SQLite(WAL) │ JsonFileStore │ PathResolver │
│  AbortSignal │ RuntimeLane(ThreadLocal) │ Logback+MDC                 │
└───────────────────────────────┬──────────────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│  领域层  we0j-common                                                  │
│  Message / Part / ToolState / Tokens / StreamEvent / PermissionRule   │
│  QuestionInfo / SkillCard / AgentInfo / TaskV2 / TodoItem / 异常体系   │
└──────────────────────────────────────────────────────────────────────┘

外部依赖：Anthropic/OpenAI/Gemini API │ git CLI │ ripgrep │ bash/cmd │ 文件系统
```

### 2.2 完整包结构

```
com.we0j
├── common
│   ├── domain
│   │   ├── message        Message, UserMessage, AssistantMessage, Role, AgentMode, ChannelSource
│   │   ├── part           Part(sealed), TextPart, ReasoningPart, ToolPart, ToolState(sealed),
│   │   │                  StepStartPart, StepFinishPart, PatchPart, FilePart, CompactionPart,
│   │   │                  RetryPart, AgentPart, SnapshotPart, Tokens, CacheTokens, FileDiff,
│   │   │                  UserSummary, MessageError(sealed)
│   │   ├── event          StreamEvent(sealed) × 16
│   │   ├── permission     PermissionName, Action, Reply, PermissionRule, PermissionRequest,
│   │   │                  PermissionToolRef, PermissionMode
│   │   ├── question       QuestionInfo, QuestionOption, QuestionRequest, Answer
│   │   ├── task           TaskV2, TaskStatus, TodoItem, TodoStatus
│   │   ├── skill          SkillCard, SkillMetadata
│   │   ├── agent          AgentInfo, AgentKind
│   │   └── notification   TaskNotification, TaskType
│   ├── exception          We0jException, ToolException, PermissionDeniedException,
│   │                      PermissionRejectedException, QuestionRejectedException,
│   │                      AbortedException, InferenceAbortedException, ContextOverflowException,
│   │                      ModelException, MalformedToolArgumentsException, StaleFileException,
│   │                      ConfigValidationException, SnapshotException
│   ├── constant           ToolNames, PermissionNames, BusTopics, Limits, Defaults
│   └── util               Ulids, Jsons(ObjectMapper 单例), Wildcards, Texts, ByteSizes
├── infra
│   ├── config             SettingsStore, LayeredConfigMerger, Settings(record 树),
│   │                      ProviderConfigStore, ConfigValidator, ConfigCacheKey
│   ├── path               PathResolver, ProjectId, DirectoryLayout
│   ├── bus                Bus, BusEvent(sealed), Subscription, SessionShardedDispatcher
│   ├── persistence        entity(SessionRow, MessageRow, PartRow), repo(×3),
│   │                      SessionRepositoryImpl(JPA), PartWriteThrottler, SqliteBusyRetry
│   ├── filestore          JsonFileStore<T>, FileLocks, FileTimeRegistry, AtomicFileWriter
│   ├── concurrency        AbortSignal, AbortScope, VirtualThreadExecutors, RuntimeLane,
│   │                      RuntimeLaneRegistry, RuntimeGate, CpuBoundExecutor
│   └── log                LogConfig, MdcKeys, RedactingConverter
├── llm
│   ├── spi                ModelProvider, ChatRequest, EventStream, ModelCard, ProviderCard,
│   │                      ModelFeatures, CacheStrategy, TokenUsage, Pricing
│   ├── registry           ProviderRegistry, ModelCardManager, ModelInfoTable(LRU 4096)
│   ├── http               OkHttpClientFactory, ProxyResolver, SseParser, SseFrame
│   ├── provider
│   │   ├── anthropic      AnthropicProvider, AnthropicRequestBuilder, AnthropicEventMapper,
│   │   │                  AnthropicMessageConverter, ThinkingSignatureStore
│   │   ├── openai         OpenAiChatProvider, OpenAiResponsesProvider, OpenAiEventMapper,
│   │   │                  ToolCallAccumulator, OpenAiMessageConverter
│   │   └── gemini         GeminiProvider, GeminiEventMapper
│   ├── transform          MessageNormalizer, CacheMarkerApplier, ParamDropper
│   ├── resilience         RetryPolicy, RetryScheduler, ErrorClassifier, OverflowPatterns,
│   │                      EmptyStreamGuard
│   └── token              TokenCounter(jtokkit), ContextWindowResolver, CostCalculator
├── tool
│   ├── spi                Tool, ToolDefinition, ToolInput, ToolResult, ContentBlock(sealed),
│   │                      ToolContext, ToolAnnotations, We0Tool(注解)
│   ├── registry           ToolRegistry, ToolSchemaGenerator(victools), SessionToolOverlay,
│   │                      ToolFilterConfig, ToolVisibility
│   ├── resolve            ToolResolver, ToolActivationManager, DeferredToolSearchCodec,
│   │                      LazyToolCalculator
│   ├── exec               ToolExecutor, ToolBatchRunner, OutputTruncator, ToolOutputStorage
│   ├── permission         PermissionService, RulesetMerger, WildcardMatcher,
│   │                      PermissionPatternResolver, BashArityTable, PermissionScopeResolver,
│   │                      PendingPermissionStore, DoomLoopDetector
│   ├── question           QuestionService, PendingQuestionStore
│   ├── builtin
│   │   ├── file           ReadTool, WriteTool, EditTool, ReplacerChain(+9 策略), DiffRenderer
│   │   ├── shell          BashTool, BashCommandParser, ShellExecutor, ProcessTreeKiller,
│   │   │                  ShellResultBuilder
│   │   ├── search         GrepTool, GlobTool, RipgrepClient, NioSearchFallback
│   │   ├── task           TodoWriteTool, TodoReadTool, TaskCreateTool, TaskGetTool,
│   │   │                  TaskUpdateTool, TaskListTool, TaskOutputTool, TaskStopTool
│   │   ├── agent          AgentTool, SkillTool, ToolSearchTool
│   │   ├── mode           EnterPlanModeTool, ExitPlanModeTool, EnterWorktreeTool, ExitWorktreeTool
│   │   ├── question       AskUserQuestionTool
│   │   └── web            WebFetchTool, WebSearchTool
│   └── mcp                McpClientTransport(spi), StdioMcpClient, McpToolAdapter   [P2]
├── agent
│   ├── session            SessionService, SessionRegistry, SessionEntry, SessionStatus(sealed),
│   │                      SessionFacade, MessageStore, PartStore, HistoryReader,
│   │                      CompactedHistoryFilter
│   ├── loop               AgentLoop, TurnProcessor, TurnResult(enum), StepContext,
│   │                      LoopExitReason(enum), QueuedInputDrainer, MaxStepsGuard
│   ├── context            SystemPromptAssembler, PromptBlock, PromptBlockCache,
│   │                      ContextContributor(spi), ContributorRegistry, ReminderInjector,
│   │                      HistoryConverter, EnvInfoRenderer, contributors/(9 个实现)
│   ├── compaction         CompactionService, OverflowDetector, PreservedTailPlanner,
│   │                      HistorySanitizer, CompactionPromptBuilder, RetryPlanner,
│   │                      PostCompactionRestore, MicroCompactor, ChainGuard(熔断器)
│   ├── snapshot           SnapshotService(spi), GitCliSnapshotService, ShadowGitInitializer,
│   │                      TreeHash, FilePatch
│   ├── revert             RevertService, RevertMode, RevertRecord, SummaryDiffCalculator
│   ├── skill              SkillScanner, SkillWatcher, SkillStore, SkillTemplateExpander
│   ├── agentdef           AgentRegistry, AgentMarkdownParser, AgentPersona
│   ├── background         BackgroundTaskManager, BackgroundAgentRunner, AgentOutputWriter,
│   │                      ShellManager, BackgroundShellRunner
│   ├── notify             NotificationService, SessionDispatcher, TaskNotificationCodec
│   ├── todo               TodoService, TaskService, TaskStore
│   ├── worktree           WorktreeService
│   ├── observability      LlmTracer, UsageAggregator, ObservabilityExporter(spi)
│   └── hook               HookChain(spi), HookPoint(enum), HookRegistry            [P2]
├── cli
│   ├── We0jCommand(根), repl/ReplRunner, repl/StreamingRenderer, repl/ToolCardRenderer,
│   │   repl/MarkdownCliRenderer, repl/CliPermissionPrompt, repl/CliQuestionPrompt,
│   │   completer/SlashCommandCompleter, completer/FileMentionCompleter,
│   │   completer/LineRangeCompleter, keys/KeyBindingDispatcher, clipboard/ClipboardImageGrabber,
│   │   paste/PastedTextReferenceCodec, cmd/(12 个子命令), headless/HeadlessRunner
└── server
    ├── We0jServerApplication, config/(SecurityConfig, VirtualThreadConfig, WebConfig),
    │   api/(SessionController, PromptController, PermissionController, QuestionController,
    │        ModelController, SettingsController, TaskController, TodoController, ToolController,
    │        RewindController, StatusController, EventStreamController),
    │   sse/(SseSubscriber, SseEventSerializer, EventReplayBuffer),
    │   dto/(请求响应 record), auth/(TokenFilter, TokenGenerator)
    └── resources/static/  (Vue 构建产物)
```

---

## 3. 领域模型设计

> 全部位于 `we0j-common`，**零 Spring 依赖**，纯 record + sealed interface。
> Jackson 配置：`PropertyNamingStrategies.LOWER_CAMEL_CASE`、`FAIL_ON_UNKNOWN_PROPERTIES=false`、`NON_NULL` 包含策略。
> **注意**：为了与原项目的 JSON blob 兼容（Skill/Agent markdown 复用），Part 的 `type` 判别值使用**kebab-case 字面量**（`"step-start"`），通过 `@JsonSubTypes.Type(name=...)` 显式指定，不受命名策略影响。

### 3.1 Message

```java
package com.we0j.common.domain.message;

public enum Role { USER, ASSISTANT }

public enum AgentMode { CODE, IM, ACP }          // MVP 只用 CODE

public enum ChannelSource { CLI, WEB, HEADLESS, SUBAGENT, NOTIFICATION }

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "role")
@JsonSubTypes({
    @JsonSubTypes.Type(value = UserMessage.class,      name = "user"),
    @JsonSubTypes.Type(value = AssistantMessage.class, name = "assistant")
})
public sealed interface Message permits UserMessage, AssistantMessage {
    String id();
    String sessionId();
    Role role();
    Instant timeCreated();
}

public record UserMessage(
        String id,
        String sessionId,
        @JsonIgnore Role role,                    // 恒为 USER，序列化时由 @JsonTypeInfo 写 "user"
        TimeCreated time,
        String system,                            // 可选：本轮强制覆盖的 system
        Map<String, Boolean> tools,               // 可选：本轮工具开关覆盖
        String variant,
        OutputFormat format,                      // null | TextFormat | JsonSchemaFormat
        UserSummary summary,                      // 本轮 diff 摘要（异步回填）
        AgentMode mode,
        ChannelSource source,
        String userId,
        Map<String, Object> metadata
) implements Message {
    public UserMessage {
        role = Role.USER;
        mode = mode == null ? AgentMode.CODE : mode;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
    @Override public Instant timeCreated() { return time.created(); }
}

public record AssistantMessage(
        String id,
        String sessionId,
        @JsonIgnore Role role,
        TimeCreatedCompleted time,
        MessageError error,
        BigDecimal cost,
        Tokens tokens,
        String finish,                            // stop | tool_calls | length | content_filter
        Boolean summary,                          // true = 这是一条压缩摘要消息
        Object structured,
        String variant,
        Map<String, Object> metadata
) implements Message {
    public AssistantMessage {
        role = Role.ASSISTANT;
        cost = cost == null ? BigDecimal.ZERO : cost;
        tokens = tokens == null ? Tokens.empty() : tokens;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
    @Override public Instant timeCreated() { return time.created(); }
    public boolean isCompleted() { return time != null && time.completed() != null; }
    public boolean isCompletedSuccessfully() { return isCompleted() && error == null; }
}

public record TimeCreated(Instant created) {}
public record TimeCreatedCompleted(Instant created, Instant completed) {}
public record TimeStart(Instant start, Instant end) {}
public record TimeStartOnly(Instant start) {}
public record TimeRange(Instant start, Instant end) {}
public record TimeRangeCompacted(Instant start, Instant end, Instant compacted) {}

public sealed interface OutputFormat permits OutputFormat.Text, OutputFormat.JsonSchema {}
public record TextFormat() implements OutputFormat {}   // 注意：嵌套命名按 sealed permits 声明
public record JsonSchemaFormat(Map<String,Object> schema, int retryCount) implements OutputFormat {}
```

> **实现注意**：`role` 字段在 record 里是组件，但序列化由 `@JsonTypeInfo` 负责写。为避免 record 组件与类型判别冲突，实际实现推荐把 `role()` 做成 `default` 方法而非 record 组件：
> ```java
> public sealed interface Message permits UserMessage, AssistantMessage {
>     String id(); String sessionId(); Instant timeCreated();
>     Role role();
> }
> public record UserMessage(...) implements Message {
>     @Override public Role role() { return Role.USER; }   // 不作为 record 组件
> }
> ```
> 这样 JSON 里 `role` 由 Jackson 从 getter 写出，`@JsonTypeInfo` 可省略（改用 `@JsonTypeName`）。**采用后者**，更干净。

### 3.2 Part（核心多态模型）

```java
package com.we0j.common.domain.part;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextPart.class,        name = "text"),
    @JsonSubTypes.Type(value = ReasoningPart.class,   name = "reasoning"),
    @JsonSubTypes.Type(value = ToolPart.class,        name = "tool"),
    @JsonSubTypes.Type(value = FilePart.class,        name = "file"),
    @JsonSubTypes.Type(value = StepStartPart.class,   name = "step-start"),
    @JsonSubTypes.Type(value = StepFinishPart.class,  name = "step-finish"),
    @JsonSubTypes.Type(value = SnapshotPart.class,    name = "snapshot"),
    @JsonSubTypes.Type(value = PatchPart.class,       name = "patch"),
    @JsonSubTypes.Type(value = AgentPart.class,       name = "agent"),
    @JsonSubTypes.Type(value = CompactionPart.class,  name = "compaction"),
    @JsonSubTypes.Type(value = RetryPart.class,       name = "retry")
})
public sealed interface Part
        permits TextPart, ReasoningPart, ToolPart, FilePart, StepStartPart, StepFinishPart,
                SnapshotPart, PatchPart, AgentPart, CompactionPart, RetryPart {
    String id();
    String messageId();
    String sessionId();
    String type();               // 与 @JsonSubTypes name 一致，便于日志与 Bus

    /** 该 Part 是否代表一个"已终结"的语义单元（用于中断清理判定） */
    default boolean isTerminal() { return true; }
}

public record TextPart(
        String id, String messageId, String sessionId,
        String text,
        Boolean synthetic,        // true = 系统合成的 reminder，非用户真实输入
        Boolean ignored,          // true = 不参与模型请求
        Boolean displayOnly,      // true = 仅 UI 展示
        TimeStart time,
        Map<String, Object> metadata   // 关键：metadata.source 用于 reminder 去重
) implements Part {
    @Override public String type() { return "text"; }
    public String source() {
        return metadata == null ? null : (String) metadata.get("source");
    }
    public boolean isSyntheticReminder() { return Boolean.TRUE.equals(synthetic) && source() != null; }
}

public record ReasoningPart(
        String id, String messageId, String sessionId,
        String text,
        Map<String, Object> metadata,   // metadata.signature = Anthropic thinking signature
        TimeStart time
) implements Part {
    @Override public String type() { return "reasoning"; }
    public String signature() {
        return metadata == null ? null : (String) metadata.get("signature");
    }
}

public record ToolPart(
        String id, String messageId, String sessionId,
        String callId,          // 模型给的 tool_call_id
        String toolName,
        ToolState state,
        Map<String, Object> metadata
) implements Part {
    @Override public String type() { return "tool"; }
    @Override public boolean isTerminal() {
        return state instanceof ToolState.Completed || state instanceof ToolState.Error;
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ToolState.Pending.class,   name = "pending"),
    @JsonSubTypes.Type(value = ToolState.Running.class,   name = "running"),
    @JsonSubTypes.Type(value = ToolState.Completed.class, name = "completed"),
    @JsonSubTypes.Type(value = ToolState.Error.class,     name = "error")
})
public sealed interface ToolState {
    String status();
    Map<String, Object> input();

    record Pending(Map<String,Object> input, String raw) implements ToolState {
        @Override public String status() { return "pending"; }
    }
    record Running(Map<String,Object> input, String title, Map<String,Object> metadata,
                   TimeStartOnly time) implements ToolState {
        @Override public String status() { return "running"; }
    }
    record Completed(Map<String,Object> input, String output, String title,
                     Map<String,Object> metadata, TimeRangeCompacted time,
                     List<FilePart> attachments) implements ToolState {
        @Override public String status() { return "completed"; }
        /** 是否被时间维微压缩裁剪过 */
        public boolean isCompacted() { return time != null && time.compacted() != null; }
    }
    record Error(Map<String,Object> input, String error, Map<String,Object> metadata,
                 TimeRange time) implements ToolState {
        @Override public String status() { return "error"; }
    }
}

public record StepStartPart(String id, String messageId, String sessionId,
                            String snapshot) implements Part {
    @Override public String type() { return "step-start"; }
}

public record StepFinishPart(String id, String messageId, String sessionId,
                             String snapshot, BigDecimal cost, Tokens tokens) implements Part {
    @Override public String type() { return "step-finish"; }
}

public record Tokens(Integer total, int input, int output, int reasoning, CacheTokens cache) {
    public static Tokens empty() { return new Tokens(null, 0, 0, 0, new CacheTokens(0, 0)); }
    /** 扣除缓存读写后的"真实新增输入" */
    public int adjustedInput() { return Math.max(0, input - cache.read() - cache.write()); }
    public boolean isCacheCold() {
        int t = total == null ? input : total;
        return t > 0 && cache.write() * 2 > t;      // write/total > 0.5
    }
    public int visibleTotal() {
        return (total == null ? input + output : total) - cache.read();
    }
    public Tokens plus(Tokens o) { /* 逐字段相加，total 取非空和 */ }
}
public record CacheTokens(int read, int write) {}

public record FilePart(String id, String messageId, String sessionId,
                       String filename, Integer displayRefId, FileSource source,
                       String url, String mime, Integer width, Integer height) implements Part {
    @Override public String type() { return "file"; }
}
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = FileSource.File.class,     name = "file"),
    @JsonSubTypes.Type(value = FileSource.Symbol.class,   name = "symbol"),
    @JsonSubTypes.Type(value = FileSource.Resource.class, name = "resource")
})
public sealed interface FileSource {
    record File(String path, Long lineStart, Long lineEnd) implements FileSource {}
    record Symbol(String path, String symbol, LspRange range) implements FileSource {}
    record Resource(String uri, String text) implements FileSource {}
}
public record LspRange(Position start, Position end) {}
public record Position(int line, int character) {}

public record PatchPart(String id, String messageId, String sessionId,
                        List<String> files) implements Part {
    @Override public String type() { return "patch"; }
}
public record SnapshotPart(String id, String messageId, String sessionId) implements Part {
    @Override public String type() { return "snapshot"; }
}
public record AgentPart(String id, String messageId, String sessionId,
                        AgentPartSource source) implements Part {
    @Override public String type() { return "agent"; }
}
public record AgentPartSource(String agentId, String sessionId, String subagentType) {}

public record CompactionPart(String id, String messageId, String sessionId,
                             String prompt, CompactionSummaryMetadata metadata) implements Part {
    @Override public String type() { return "compaction"; }
}
public record CompactionSummaryMetadata(
        CompactionPreservedTail preservedTail,
        CompactionPreservedSegment preservedSegment,
        List<String> preCompactDiscoveredTools,
        Integer messagesSummarized,
        Integer truePostCompactTokenCount,
        PostCompactTaskStatusMetadata postCompactTaskStatus
) {}
public record CompactionPreservedTail(List<String> messageIds) {}
public record CompactionPreservedSegment(List<String> messageIds) {}
public record PostCompactTaskStatusMetadata(String source, List<String> taskIds) {}

public record RetryPart(String id, String messageId, String sessionId,
                        MessageError error, TimeCreated time) implements Part {
    @Override public String type() { return "retry"; }
}

public record FileDiff(String path, FileDiffStatus status, int additions, int deletions) {}
public enum FileDiffStatus { ADDED, DELETED, MODIFIED }
public record UserSummary(String title, String body, List<FileDiff> diffs) {}

public record MicrocompactBoundaryMetadata(
        String trigger, int preTokens, int tokensSaved,
        List<String> compactedToolIds, List<String> clearedAttachmentIds) {}
```

### 3.3 MessageError

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "name")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MessageError.OutputLength.class, name = "MessageOutputLengthError"),
    @JsonSubTypes.Type(value = MessageError.Aborted.class,      name = "MessageAbortedError"),
    @JsonSubTypes.Type(value = MessageError.StructuredOutput.class, name = "StructuredOutputError"),
    @JsonSubTypes.Type(value = MessageError.Auth.class,         name = "ProviderAuthError"),
    @JsonSubTypes.Type(value = MessageError.Api.class,          name = "APIError"),
    @JsonSubTypes.Type(value = MessageError.ContextOverflow.class, name = "ContextOverflowError"),
    @JsonSubTypes.Type(value = MessageError.Unknown.class,      name = "UnknownError")
})
public sealed interface MessageError {
    String name();
    String message();

    record OutputLength(String message) implements MessageError {
        @Override public String name() { return "MessageOutputLengthError"; } }
    record Aborted(String message) implements MessageError {
        @Override public String name() { return "MessageAbortedError"; } }
    record StructuredOutput(String message) implements MessageError {
        @Override public String name() { return "StructuredOutputError"; } }
    record Auth(String message) implements MessageError {
        @Override public String name() { return "ProviderAuthError"; } }
    record Api(String message, Integer statusCode, boolean isRetryable,
               Map<String,String> responseHeaders, String responseBody,
               Map<String,String> metadata) implements MessageError {
        @Override public String name() { return "APIError"; } }
    record ContextOverflow(String message, String responseBody) implements MessageError {
        @Override public String name() { return "ContextOverflowError"; } }
    record Unknown(String message, String stackTrace) implements MessageError {
        @Override public String name() { return "UnknownError"; } }

    static MessageError from(Throwable t) { /* 见 §5.3.6 ErrorClassifier */ }
}
```

### 3.4 StreamEvent（16 种，FR-032）

```java
package com.we0j.common.domain.event;

public sealed interface StreamEvent {
    String type();

    record Start() implements StreamEvent { public String type(){return "start";} }
    record StartStep() implements StreamEvent { public String type(){return "start-step";} }

    record ReasoningStart(String id, Map<String,Object> providerMetadata) implements StreamEvent {
        public String type(){return "reasoning-start";} }
    record ReasoningDelta(String id, String text, Map<String,Object> providerMetadata) implements StreamEvent {
        public String type(){return "reasoning-delta";} }
    record ReasoningEnd(String id, Map<String,Object> providerMetadata) implements StreamEvent {
        public String type(){return "reasoning-end";} }

    record TextStart(String id, Map<String,Object> providerMetadata) implements StreamEvent {
        public String type(){return "text-start";} }
    record TextDelta(String id, String text, Map<String,Object> providerMetadata) implements StreamEvent {
        public String type(){return "text-delta";} }
    record TextEnd(String id, Map<String,Object> providerMetadata) implements StreamEvent {
        public String type(){return "text-end";} }

    record ToolInputStart(String id, String toolName, String toolCallId,
                          Map<String,Object> providerMetadata) implements StreamEvent {
        public String type(){return "tool-input-start";} }
    record ToolInputDelta(String id, String delta, Map<String,Object> providerMetadata) implements StreamEvent {
        public String type(){return "tool-input-delta";} }
    record ToolInputEnd(String id, Map<String,Object> providerMetadata) implements StreamEvent {
        public String type(){return "tool-input-end";} }

    record ToolCall(String toolCallId, String toolName, Map<String,Object> input,
                    Map<String,Object> providerMetadata) implements StreamEvent {
        public String type(){return "tool-call";} }

    /** 由外层 Loop 注入（非模型流产出），用于统一 Part 更新语义 */
    record ToolResult(String toolCallId, String toolName, Map<String,Object> input,
                      ToolOutput output) implements StreamEvent {
        public String type(){return "tool-result";} }
    record ToolError(String toolCallId, String toolName, Map<String,Object> input,
                     String error) implements StreamEvent {
        public String type(){return "tool-error";} }

    record FinishStep(String finishReason, TokenUsage usage,
                      Map<String,Object> providerMetadata) implements StreamEvent {
        public String type(){return "finish-step";} }
    record Finish(String finishReason, TokenUsage totalUsage) implements StreamEvent {
        public String type(){return "finish";} }
    record Error(Throwable error) implements StreamEvent {
        public String type(){return "error";} }
}

/** 原始 usage map 的类型化封装，兼容三家字段名差异 */
public record TokenUsage(
        Integer promptTokens, Integer completionTokens, Integer totalTokens,
        Integer reasoningTokens,
        Integer cacheReadInputTokens, Integer cacheCreationInputTokens,
        Map<String,Object> raw
) {
    public Tokens toTokens() {
        int in  = orZero(promptTokens);
        int out = orZero(completionTokens);
        int cr  = orZero(cacheReadInputTokens);
        int cw  = orZero(cacheCreationInputTokens);
        Integer total = totalTokens != null ? totalTokens : (in + out);
        return new Tokens(total, in, out, orZero(reasoningTokens), new CacheTokens(cr, cw));
    }
    private static int orZero(Integer i){ return i == null ? 0 : i; }
}
```

**Usage 多路取值规则**（`TokenUsageFactory`，对齐原项目 `first_present` 语义）：

| 目标字段 | 取值优先级 |
|---|---|
| `promptTokens` | `usage.prompt_tokens` → `usage.input_tokens`(Anthropic) → `usage.promptTokenCount`(Gemini) |
| `completionTokens` | `usage.completion_tokens` → `usage.output_tokens` → `usage.candidatesTokenCount` |
| `cacheReadInputTokens` | `usage.prompt_tokens_details.cached_tokens` → `usage.cache_read_input_tokens` → `providerMetadata.anthropic.usage.cache_read_input_tokens` |
| `cacheCreationInputTokens` | `usage.cache_creation_input_tokens` → `providerMetadata.bedrock...` |
| `reasoningTokens` | `usage.completion_tokens_details.reasoning_tokens` → `usage.reasoning_tokens` |
| `totalTokens` | `usage.total_tokens` → 计算得出 |

### 3.5 权限 / 提问

```java
public enum PermissionName {
    READ, WRITE, EDIT, BASH, GLOB, GREP, LSP, SKILL, AGENT, TASK, TASK_OUTPUT, TASK_STOP,
    TODOWRITE, TODOREAD, QUESTION, CRON, WEB_FETCH, WEB_SEARCH, TEAM, SEND_MESSAGE,
    EXTERNAL_DIRECTORY, DOOM_LOOP, PLAN_ENTER, PLAN_EXIT, WORKTREE, TOOL_SEARCH,
    RECALL, OPERATE_MEMORY, ALL;

    @JsonValue public String wire() { return name().toLowerCase(Locale.ROOT); }   // "web_fetch"
    @JsonCreator public static PermissionName of(String s) { /* 反查 */ }
}
public enum Action { ALLOW, DENY, ASK }
public enum Reply  { ONCE, ALWAYS, REJECT }
public enum PermissionMode { ASK, ALLOW_ONCE, BYPASS, REJECT }

public record PermissionRule(PermissionName permission, String pattern, Action action) {}
public record PermissionToolRef(String messageId, String callId) {}

public record PermissionRequest(
        String id, String sessionId, PermissionName permission,
        List<String> patterns, Map<String,Object> metadata,
        String message, List<String> always, PermissionToolRef tool
) {}
/** ONCE 回复可携带用户附加说明，作为 <permission_feedback> 追加到工具输出 */
public record ReplyDecision(Reply reply, String userMessage) {}

public record QuestionOption(
        @Size(max = 60) String label,
        @NotBlank String description,
        String preview) {}

public record QuestionInfo(
        @NotBlank String question,
        @Size(max = 12) String header,
        @Size(min = 2, max = 4) List<QuestionOption> options,
        Boolean multiSelect) {
    /** 运行时拒绝模型自行编写保留标签（FR-078） */
    private static final Set<String> RESERVED = Set.of("other", "type something.", "type something");
    public void validateNotReserved() {
        for (var o : options)
            if (RESERVED.contains(o.label().trim().toLowerCase(Locale.ROOT)))
                throw new ToolException("Reserved option label is not allowed: " + o.label());
    }
}
public record QuestionRequest(String id, String sessionId, List<QuestionInfo> questions,
                              Map<String,Object> metadata, QuestionToolRef tool) {}
public record QuestionToolRef(String messageId, String callId) {}
```

### 3.6 任务 / Skill / Agent

```java
public enum TaskStatus { PENDING, IN_PROGRESS, COMPLETED, DELETED }

public record TaskV2(
        String id, String subject, String description, String activeForm, String owner,
        TaskStatus status, List<String> blocks, List<String> blockedBy,
        Map<String,Object> metadata, Instant timeCreated, Instant timeUpdated,
        List<TaskComment> comments) {
    public boolean isClaimable() {
        return status == TaskStatus.PENDING && (blockedBy == null || blockedBy.isEmpty());
    }
}
public record TaskComment(String id, String author, String body, Instant time) {}

public enum TodoStatus { PENDING, IN_PROGRESS, COMPLETED }
public record TodoItem(String content, TodoStatus status, String activeForm) {}

public record SkillCard(
        String name, String description, String license, String compatibility,
        List<String> allowedTools, Map<String,Object> metadata, Path location, String body) {
    /** 渐进式披露：仅 name + description 进上下文 */
    public String toSystemReminder() {
        return "<skill name=\"%s\">%s</skill>".formatted(name, description);
    }
}

public record AgentInfo(
        String name, String description, String prompt, List<String> tools,
        List<PermissionRule> permissionRules, String modelTier, Integer maxSteps,
        AgentKind kind, Path source) {}
public enum AgentKind { BUILTIN, GLOBAL, PROJECT }
```

---

## 4. 基础设施层设计

### 4.1 AbortSignal —— asyncio.Event 的 Java 等价物（★ 关键）

**设计目标**：一个可级联、可注册清理动作、可阻塞等待、可从任意线程触发的取消句柄。这是把 Python `asyncio.Event` 语义搬到虚拟线程世界的核心构件。

```java
package com.we0j.infra.concurrency;

/**
 * 取消信号。语义等价于 Python 的 asyncio.Event，但：
 *  - 支持级联（父 abort 自动 abort 所有子）
 *  - 支持注册清理动作（取消 HTTP call、杀进程树、complete pending future）
 *  - await() 在虚拟线程上阻塞，零平台线程成本
 * 线程安全：所有方法可从任意线程调用。
 */
public final class AbortSignal {

    private volatile boolean aborted;
    private volatile Throwable reason;
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final List<Runnable> cleanups = new CopyOnWriteArrayList<>();
    private final List<AbortSignal> children = new CopyOnWriteArrayList<>();
    private final AbortSignal parent;

    private AbortSignal(AbortSignal parent) { this.parent = parent; }

    public static AbortSignal create() { return new AbortSignal(null); }

    /** 创建级联子信号：父 abort → 子 abort；子 abort 不影响父 */
    public AbortSignal child() {
        AbortSignal c = new AbortSignal(this);
        if (this.aborted) c.abort(this.reason);       // 父已 abort，子立即 abort
        else children.add(c);
        return c;
    }

    public void abort() { abort(new AbortedException("aborted by user")); }

    public void abort(Throwable reason) {
        if (aborted) return;                          // 幂等
        aborted = true;
        this.reason = reason;
        // 1) 先执行清理动作（取消 IO / 杀进程），顺序与注册顺序相反（后注册先清理）
        List<Runnable> snapshot = new ArrayList<>(cleanups);
        Collections.reverse(snapshot);
        for (Runnable r : snapshot) {
            try { r.run(); } catch (Exception e) { /* 记录但不中断其余清理 */ }
        }
        // 2) 唤醒所有 await() 的虚拟线程
        done.completeExceptionally(reason instanceof Exception ex ? ex : new AbortedException("aborted"));
        // 3) 级联子信号
        for (AbortSignal c : children) c.abort(reason);
    }

    public boolean isAborted() { return aborted; }
    public Optional<Throwable> reason() { return Optional.ofNullable(reason); }

    /** 已 abort 则抛 AbortedException。在每个循环边界、每次 IO 前调用 */
    public void throwIfAborted() {
        if (aborted) throw wrap(reason);
    }

    /** 阻塞直到 abort。用于"竞速"场景（如 waitFor(timeout) vs abort） */
    public void await() {
        try { done.get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AbortedException("interrupted"); }
        catch (ExecutionException e) { /* 正常路径：abort 触发 */ }
    }

    /** 注册清理动作。若已 abort 则立即执行 */
    public void onCancel(Runnable cleanup) {
        if (aborted) cleanup.run();
        else cleanups.add(cleanup);
    }

    /** 返回一个在 abort 时以 AbortedException 完成的 Future（用于与业务 Future 竞速） */
    public CompletableFuture<Void> asFuture() { return done; }

    private static RuntimeException wrap(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        return new AbortedException("aborted", t);
    }
}
```

**使用范式**：

```java
// ① 会话级信号：SessionRegistry 持有，用户 Esc → abort()
AbortSignal sessionAbort = entry.abortSignal();

// ② 每轮派生子信号（轮次结束自动失效，互不污染）
try (AbortScope turn = AbortScope.of(sessionAbort)) {
    processor.process(input, turn.signal());
}

// ③ 每个工具派生子信号 → 单个工具失败不 abort 整轮
AbortSignal toolAbort = turn.signal().child();

// ④ HTTP 流注册清理
signal.onCancel(() -> call.cancel());

// ⑤ 子进程注册清理
signal.onCancel(() -> ProcessTreeKiller.kill(process));

// ⑥ 权限等待注册清理
CompletableFuture<ReplyDecision> f = new CompletableFuture<>();
signal.onCancel(() -> f.completeExceptionally(new AbortedException("permission wait aborted")));
```

**为什么不用 `Thread.interrupt()`**：interrupt 只能作用于单线程，无法级联到"我派生的 HTTP call、子进程、等待中的 Future"；且 OkHttp 的阻塞读不响应 interrupt（响应的是 `Call.cancel()`）。`AbortSignal` 的 `onCancel` 回调机制正是为此。二者可组合：`onCancel(() -> workerThread.interrupt())`。

### 4.2 虚拟线程执行器与 Lane

```java
package com.we0j.infra.concurrency;

public enum RuntimeLane { MAIN, SIDE_LLM, SIDE_AGENT }

/** 虚拟线程下 ThreadLocal 安全（无池化复用）。禁止用 InheritableThreadLocal。 */
public final class RuntimeLaneRegistry {
    private static final ThreadLocal<RuntimeLane> CURRENT = ThreadLocal.withInitial(() -> RuntimeLane.MAIN);

    public static RuntimeLane current() { return CURRENT.get(); }
    public static void set(RuntimeLane lane) { CURRENT.set(lane); }
    public static void clear() { CURRENT.remove(); }

    /** 在指定 lane 下执行（用于启动子任务时显式传递，不依赖继承） */
    public static <T> T callAs(RuntimeLane lane, Supplier<T> action) {
        RuntimeLane prev = CURRENT.get();
        CURRENT.set(lane);
        try { return action.get(); } finally { CURRENT.set(prev); }
    }
    public static void runAs(RuntimeLane lane, Runnable action) {
        callAs(lane, () -> { action.run(); return null; });
    }
}

/** 门控：仅主泳道可做会话级副作用决策 */
public final class RuntimeGate {
    public static boolean mainAgentOnlyDecision() { return RuntimeLaneRegistry.current() == RuntimeLane.MAIN; }
    public static void requireMain(String what) {
        if (!mainAgentOnlyDecision())
            throw new IllegalStateException(what + " is only allowed on MAIN lane, current=" 
                                            + RuntimeLaneRegistry.current());
    }
}

public final class VirtualThreadExecutors {
    /** IO 密集：模型流、工具执行、子进程、DB、SSE。每任务一虚拟线程，不池化 */
    public static final ExecutorService IO = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("we0j-io-", 0).factory());

    /** 有序串行：按 key 分片，保证同 key 事件顺序（Bus / DB 写） */
    public static ExecutorService shardedSerial(int shards, String namePrefix) {
        return new ShardedSerialExecutor(shards, namePrefix);
    }

    public static ExecutorService io(String namePrefix) {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(namePrefix, 0).factory());
    }
}

/** CPU 密集：JSON 解析、diff、token 计数。平台线程 + 有界队列 + CallerRuns */
@Configuration
public class CpuBoundExecutorConfig {
    @Bean("cpuBoundExecutor")
    public ExecutorService cpuBoundExecutor() {
        int n = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(512),
                Thread.ofPlatform().name("we0j-cpu-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}

/** 分片串行执行器：hash(key) % shards → 固定单线程，保证同 key 严格有序 */
final class ShardedSerialExecutor implements ExecutorService {
    private final ExecutorService[] shards;
    ShardedSerialExecutor(int n, String prefix) {
        shards = new ExecutorService[n];
        for (int i = 0; i < n; i++)
            shards[i] = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name(prefix + i + "-", 0).factory());
    }
    public void execute(String key, Runnable r) {
        shards[Math.floorMod(key.hashCode(), shards.length)].execute(r);
    }
    // ... 其余 ExecutorService 方法委托到 shards[0]
}
```

### 4.3 事件总线（FR-111 / FR-112）

```java
package com.we0j.infra.bus;

/** 所有 Bus 事件的根。sessionId() 为 null 表示全局事件 */
public sealed interface BusEvent permits
        SessionUpdated, SessionCompacted, SessionDiff, SessionError,
        MessageUpdated, MessagePartUpdated, MessagePartDelta, MessagePartRemoved,
        PermissionAsked, PermissionReplied, QuestionAsked, QuestionReplied, QuestionRejected,
        AgentRuntimeModeChanged, TaskUpdated, TodoUpdated, NotificationPushed, ToolActivationChanged {

    /** 点分主题名，同时作为 SSE event name */
    String topic();
    /** 用于分片有序分发；null = 全局 */
    default String sessionId() { return null; }
    /** 单调递增序号，供 SSE Last-Event-ID 重放 */
    long seq();
    /** 关键事件在有界队列溢出时不得丢弃 */
    default boolean critical() { return false; }
}

public record MessagePartDelta(String sessionId, String messageId, String partId,
                               String field, String delta, long seq) implements BusEvent {
    @Override public String topic() { return "message.part.delta"; }
    @Override public boolean critical() { return false; }   // 高频，可丢
}
public record PermissionAsked(String sessionId, PermissionRequest request, long seq) implements BusEvent {
    @Override public String topic() { return "permission.asked"; }
    @Override public boolean critical() { return true; }    // 不可丢
}
// ... 其余事件同构

@Component
public final class Bus {

    private static final Logger log = LoggerFactory.getLogger(Bus.class);

    private final Map<Class<? extends BusEvent>, CopyOnWriteArrayList<Subscriber<Object>>> typed =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Subscriber<BusEvent>> wildcards = new CopyOnWriteArrayList<>();
    private final ShardedSerialExecutor sessionShards;     // 同 sessionId 严格有序
    private final ExecutorService globalExecutor;          // 全局事件
    private final AtomicLong seqGen = new AtomicLong();

    public Bus() {
        this.sessionShards = (ShardedSerialExecutor) VirtualThreadExecutors.shardedSerial(32, "we0j-bus-");
        this.globalExecutor = VirtualThreadExecutors.io("we0j-bus-g-");
    }

    public long nextSeq() { return seqGen.incrementAndGet(); }

    @SuppressWarnings("unchecked")
    public <E extends BusEvent> Subscription subscribe(Class<E> type, Consumer<E> handler) {
        Subscriber<Object> sub = new Subscriber<>((Class<BusEvent>) type, (Consumer<BusEvent>) handler);
        typed.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(sub);
        return () -> remove(type, sub);
    }

    public Subscription subscribeAll(Consumer<BusEvent> handler) {
        Subscriber<BusEvent> sub = new Subscriber<>(BusEvent.class, handler);
        wildcards.add(sub);
        return () -> wildcards.remove(sub);
    }

    /**
     * 发布。不阻塞调用方（Loop）：
     *  - 有 sessionId → 路由到该 session 的分片串行执行器（保证顺序）
     *  - 无 sessionId → 全局执行器
     * 订阅者异常被捕获，不影响其他订阅者。
     */
    public void publish(BusEvent event) {
        String sid = event.sessionId();
        Runnable dispatch = () -> dispatchNow(event);
        if (sid != null) sessionShards.execute(sid, dispatch);
        else globalExecutor.execute(dispatch);
    }

    /** 同步发布：仅用于必须"发布完成后才继续"的场景（如持久化 fan-out） */
    public void publishSync(BusEvent event) { dispatchNow(event); }

    private void dispatchNow(BusEvent event) {
        for (Subscriber<Object> s : subscribersOf(event.getClass())) {
            try { s.accept(event); }
            catch (Exception e) { log.error("bus subscriber failed topic={}", event.topic(), e); }
        }
        for (Subscriber<BusEvent> s : wildcards) {
            try { s.accept(event); }
            catch (Exception e) { log.error("bus wildcard subscriber failed topic={}", event.topic(), e); }
        }
    }

    /** 支持父类型订阅（订阅 BusEvent 收到全部；订阅 PartEvent 收到所有 part 事件） */
    private List<Subscriber<Object>> subscribersOf(Class<?> eventType) { /* 遍历 typed 中 isAssignableFrom 的键 */ }

    public record Subscriber<E>(Class<E> type, Consumer<E> handler) {
        void accept(BusEvent e) { handler.accept(type.cast(e)); }
    }
    @FunctionalInterface public interface Subscription { void unsubscribe(); }
}
```

**设计说明**
- **顺序保证**：原项目 `Bus.publish` 是 `await subscribers`（同步有序）。Java 版若同步分发，慢订阅者（SSE 网络写）会拖住 Loop。折中：**按 sessionId 分 32 片串行**——同会话严格有序，跨会话并行，Loop 不被阻塞。
- **持久化 fan-out 例外**：`Session.updatePart()` 内部的"写库 + 发事件"必须原子有序，走 `publishSync`。
- **背压**：SSE 订阅者自己持有有界队列（见 §5.15.3），Bus 只管投递到订阅者的 `accept`，订阅者内部 `offer` 失败时按 `critical()` 决定丢弃或阻塞。

### 4.4 分层配置（FR-14）

```java
package com.we0j.infra.config;

/** 不可变配置树。用 record 嵌套，Jackson 直接绑定 */
public record Settings(Common common, Code code, Web web) {

    public record Common(
            String language,
            Chat chat,
            Map<String, ProviderConfig> providers,
            Map<String, Action> permission,               // key = PermissionName.wire() 或 "*"
            Map<String, McpServerConfig> mcpServers,
            Map<String, LspServerConfig> lspServers,
            Map<String, ServiceConfig> services,
            ToolModelConfig toolModel,
            LoopConfig loop) {}

    public record Chat(ModelRef defaultModel, Map<String, ModelRef> tiers,
                       ReasoningConfig reasoning, OnMissing onMissing) {}
    public record ModelRef(String provider, String model) {}
    public enum OnMissing { ERROR, FALLBACK }
    public record ReasoningConfig(boolean enabled, Integer budgetTokens, String effort) {}

    public record ProviderConfig(boolean enabled, String apiKey, String apiBase,
                                 List<ModelEntry> models, String family, Map<String,String> headers) {}
    /** 模型可以是字符串，也可以是对象 → 自定义反序列化器统一为 ModelEntry */
    public record ModelEntry(String id, String mode, Set<ModelFeature> features,
                             String reasoningEffort, String verbosity,
                             Integer contextWindow, Integer maxOutput, Pricing pricing) {}
    public enum ModelFeature { DEFER_LOADING, THINKING, TOOL_SEARCH_NATIVE, VISION, JSON_SCHEMA_OUTPUT }
    public record Pricing(BigDecimal input, BigDecimal output,
                          BigDecimal cacheRead, BigDecimal cacheWrite,
                          BigDecimal experimentalOver200KInput) {}   // 单位：USD / 1M tokens

    public record McpServerConfig(String type, String module, String command, List<String> args,
                                  String url, Map<String,String> env, boolean enabled,
                                  LazySpec lazy, ToolFilterConfig toolFilters) {}
    /** lazy: true | false | ["toolA","toolB"] */
    public record LazySpec(boolean all, Set<String> names) {
        public boolean isLazy(String toolName) { return all || names.contains(toolName); }
    }
    public record ToolFilterConfig(List<String> allowed, List<String> rejected) {
        public boolean shouldInclude(String toolName) {
            if (allowed != null && !allowed.isEmpty() && !matches(allowed, toolName)) return false;
            return rejected == null || rejected.isEmpty() || !matches(rejected, toolName);
        }
        private static boolean matches(List<String> specs, String name) {
            return specs.stream().anyMatch(s -> s.startsWith("regex:")
                    ? Pattern.compile(s.substring(6)).matcher(name).find()
                    : Wildcards.match(s, name));
        }
    }

    public record LoopConfig(int maxSteps) {}
    public record ServiceConfig(String apiKey, String baseUrl) {}
    public record ToolModelConfig(ModelRef model) {}
    public record LspServerConfig(boolean enabled, String command, List<String> args) {}

    public record Code(Paths paths, Agent agent, Runtime runtime, Compaction compaction,
                       List<String> disabledSkills) {
        public record Paths(String workdir) {}
        public record Agent(String defaultAgent) {}
        public record Runtime(boolean snapshot, boolean promptSuggestions, boolean laneTracking) {}
        public record Compaction(int buffer, double tailBudgetRatio, int gapThresholdMinutes,
                                 int keepRecentToolResults, int maxConsecutiveFailures) {}
    }

    public record Web(int port, String host, String token, String theme, boolean autoOpen) {}

    public static Settings defaults() { /* 内置模板，等价于原项目 example.settings.json */ }
}
```

```java
@Component
public final class SettingsStore {

    private final PathResolver paths;
    private final ObjectMapper mapper;
    private final ConfigValidator validator;

    /** 缓存键 = 各层文件的 (path, lastModified, size) 组合。任一变化即 miss */
    private record CacheKey(List<String> parts) {}
    private volatile CacheKey lastKey;
    private volatile Settings cached;
    private final ReentrantLock lock = new ReentrantLock();     // 不用 synchronized（避免 pinning）

    /** 深合并：user ← project。字典递归合并，数组与标量整体替换（project 覆盖） */
    public Settings current(Path projectRoot) {
        CacheKey key = keyOf(projectRoot);
        Settings local = cached;
        if (local != null && key.equals(lastKey)) return local;
        lock.lock();
        try {
            if (cached != null && key.equals(lastKey)) return cached;
            JsonNode userNode    = readNode(paths.userSettings());            // ~/.we0j/settings.json
            JsonNode projectNode = readNode(projectRoot.resolve(".we0j/settings.json"));
            JsonNode merged = LayeredConfigMerger.deepMerge(userNode, projectNode);
            Settings s = mapper.treeToValue(merged, Settings.class);
            validator.validate(s, merged);          // 抛 ConfigValidationException（含 JSON Pointer）
            cached = s; lastKey = key;
            return s;
        } catch (IOException e) {
            throw new ConfigValidationException("failed to load settings", e);
        } finally { lock.unlock(); }
    }

    public Settings refresh(Path projectRoot) { cached = null; lastKey = null; return current(projectRoot); }

    private CacheKey keyOf(Path projectRoot) {
        return new CacheKey(Stream.of(paths.userSettings(), projectRoot.resolve(".we0j/settings.json"))
                .map(SettingsStore::fingerprint).toList());
    }
    private static String fingerprint(Path p) {
        try { return p + "::" + Files.getLastModifiedTime(p).toMillis() + "::" + Files.size(p); }
        catch (IOException e) { return p + "::missing"; }
    }
}
```

```java
@Component
public final class LayeredConfigMerger {
    /** 递归深合并：ObjectNode 逐键合并；其余类型 override 覆盖 base */
    public static JsonNode deepMerge(JsonNode base, JsonNode override) {
        if (base == null || base.isNull()) return override;
        if (override == null || override.isNull()) return base;
        if (base.isObject() && override.isObject()) {
            ObjectNode out = base.deepCopy();
            override.fields().forEachRemaining(e -> out.set(e.getKey(), deepMerge(base.get(e.getKey()), e.getValue())));
            return out;
        }
        return override.deepCopy();
    }
}
```

```java
@Component
public final class ConfigValidator {
    private final Validator beanValidator;      // jakarta.validation

    public void validate(Settings s, JsonNode raw) {
        // 1) Bean Validation
        Set<ConstraintViolation<Settings>> violations = beanValidator.validate(s);
        if (!violations.isEmpty()) throw ConfigValidationException.of(violations);

        // 2) 无效字段位置检测（FR-141）
        for (String bad : List.of("code.mcpServers", "web.mcpServers", "code.providers")) {
            if (at(raw, bad) != null)
                throw new ConfigValidationException(
                    "Invalid config location '%s'. Tool configuration must be under 'common.mcpServers'."
                        .formatted(bad));
        }

        // 3) 基础设施配置不得出现在 profile/非法层（此处只有两层，跳过）
        // 4) provider/model 引用完整性：chat.default 与 tiers 引用的 provider 必须存在且 enabled
        checkModelRef(s, s.common().chat().defaultModel(), "common.chat.default");
        s.common().chat().tiers().forEach((k, v) -> checkModelRef(s, v, "common.chat.tiers." + k));

        // 5) 权限规则 action 合法性、pattern 非空
        // 6) compaction 数值范围（buffer>0, tailBudgetRatio∈(0,0.5), maxConsecutiveFailures>=1）
    }
}
```

**首启初始化流程**（`BootstrapInitializer`，对齐 UC-01）：
```
1. DirectoryLayout.ensure()            创建 ~/.we0j 及全部子目录
2. 若 settings.json 不存在 → 从 classpath:/templates/settings.template.json 完整写出
3. 若 providers.json 不存在 → 写模板（anthropic enabled，openai/gemini disabled）
4. 读 settings；若 common.mcpServers 为空 → 从 templates/mcp-servers.template.json 补齐并回写
   （★ 不得覆盖用户已有的非空配置）
5. 文件权限收紧：POSIX 600；Windows 用 icacls 移除 Users 组
6. ConfigValidator 全量校验，失败 → 打印可操作错误 + 退出码 2
```

### 4.5 存储层（FR-09）

#### 4.5.1 JPA 实体

```java
package com.we0j.infra.persistence.entity;

@Entity @Table(name = "session",
    indexes = { @Index(name = "idx_session_project", columnList = "project_id"),
                @Index(name = "idx_session_parent",  columnList = "parent_id"),
                @Index(name = "idx_session_updated", columnList = "time_updated") },
    uniqueConstraints = @UniqueConstraint(name = "uq_session_project_name",
                                          columnNames = {"project_id", "name"}))
public class SessionRow {
    @Id                        private String id;
    @Column(name="project_id", nullable=false) private String projectId;
    @Column(name="parent_id")  private String parentId;
    @Column(name="name")       private String name;
    @Column(nullable=false)    private String directory;
    @Column(nullable=false)    private String title;
    @Column(nullable=false)    private String version;
    @Column(name="share_url")  private String shareUrl;
    @Column(name="summary_additions") private Integer summaryAdditions;
    @Column(name="summary_deletions") private Integer summaryDeletions;
    @Column(name="summary_files")     private Integer summaryFiles;
    @Lob @Column(name="summary_diffs") private String summaryDiffs;   // JSON: List<FileDiff>
    @Lob @Column(name="revert")        private String revert;         // JSON: RevertRecord
    @Column(name="time_created")   private Long timeCreated;          // epoch millis
    @Column(name="time_updated")   private Long timeUpdated;
    @Column(name="time_compacting")private Long timeCompacting;
    @Column(name="time_archived")  private Long timeArchived;
    @Column(name="is_incognito", nullable=false) private boolean incognito;
    @Lob @Column(name="runtime_state") private String runtimeState;   // JSON: 权限运行时规则/激活工具/agent人格/模式
    // getters/setters（JPA 需要，用 protected 无参构造 + 静态工厂）
}

@Entity @Table(name="message", indexes = {
    @Index(name="idx_message_session", columnList="session_id"),
    @Index(name="idx_message_session_created", columnList="session_id,time_created")})
public class MessageRow {
    @Id private String id;
    @Column(name="session_id", nullable=false) private String sessionId;
    @Column(name="role", nullable=false, length=16) private String role;   // 冗余索引列，便于按角色查
    @Lob @Column(name="data", nullable=false) private String data;         // JSON blob
    @Column(name="time_created") private Long timeCreated;
    @Column(name="time_updated") private Long timeUpdated;
}

@Entity @Table(name="part", indexes = {
    @Index(name="idx_part_message", columnList="message_id"),
    @Index(name="idx_part_session", columnList="session_id"),
    @Index(name="idx_part_session_created", columnList="session_id,time_created")})
public class PartRow {
    @Id private String id;
    @Column(name="message_id", nullable=false) private String messageId;
    @Column(name="session_id", nullable=false) private String sessionId;
    @Column(name="type", nullable=false, length=24) private String type;   // 冗余索引列
    @Lob @Column(name="data", nullable=false) private String data;         // JSON blob
    @Column(name="time_created") private Long timeCreated;
    @Column(name="time_updated") private Long timeUpdated;
}
```

> **架构改进**：相比原项目，`message` 增加 `role` 冗余列、`part` 增加 `type` 冗余列、`session` 增加 `runtime_state` 列。理由：
> - `role` / `type` 让"查所有 assistant 消息""查所有未完成 tool part"无需反序列化全部 blob
> - `runtime_state` 把原本散在内存的会话运行时状态（权限规则、已激活延迟工具、当前人格、权限模式）落库，**这是 FR-013 resume 完整性的前提**（原项目部分状态靠文件/内存，重启会丢）

#### 4.5.2 Repository 与 upsert

```java
public interface SessionRowRepository extends JpaRepository<SessionRow, String> {
    List<SessionRow> findByProjectIdAndIncognitoFalseOrderByTimeUpdatedDesc(String projectId, Pageable page);
    Optional<SessionRow> findByIdAndProjectId(String id, String projectId);
    @Query("select s from SessionRow s where s.projectId=:pid and s.incognito=false "
         + "and lower(s.title) like lower(concat('%',:kw,'%')) order by s.timeUpdated desc")
    List<SessionRow> search(@Param("pid") String projectId, @Param("kw") String keyword, Pageable page);
}

public interface MessageRowRepository extends JpaRepository<MessageRow, String> {
    List<MessageRow> findBySessionIdOrderByTimeCreatedAsc(String sessionId);
    void deleteBySessionId(String sessionId);
    long countBySessionId(String sessionId);
}

public interface PartRowRepository extends JpaRepository<PartRow, String> {
    List<PartRow> findBySessionIdOrderByTimeCreatedAsc(String sessionId);
    List<PartRow> findByMessageIdOrderByTimeCreatedAsc(String messageId);
    List<PartRow> findBySessionIdAndType(String sessionId, String type);
    void deleteByMessageIdIn(Collection<String> messageIds);
    void deleteBySessionId(String sessionId);
}

/** SQLite upsert：Hibernate 6 不直接支持 ON CONFLICT，用原生 SQL */
@Repository
@RequiredArgsConstructor
public class PartWriter {
    private final JdbcTemplate jdbc;
    private final SqliteBusyRetry retry;
    private final ObjectMapper mapper;

    private static final String UPSERT = """
        INSERT INTO part (id, message_id, session_id, type, data, time_created, time_updated)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            data = excluded.data,
            type = excluded.type,
            time_updated = excluded.time_updated
        """;

    public void upsert(Part part, Instant created) {
        retry.run(() -> jdbc.update(UPSERT,
                part.id(), part.messageId(), part.sessionId(), part.type(),
                Jsons.write(mapper, part),
                created.toEpochMilli(), Instant.now().toEpochMilli()));
    }

    public void upsertBatch(List<Part> parts, Instant created) {
        retry.run(() -> jdbc.batchUpdate(UPSERT, new BatchPreparedStatementSetter() { /* ... */ }));
    }
}

/** SQLITE_BUSY (errorCode=1 / SQLState  HY000 + message contains "SQLITE_BUSY") 重试 */
@Component
public class SqliteBusyRetry {
    private static final long[] DELAYS = {50, 150, 450};
    public void run(Runnable action) {
        for (int attempt = 0; ; attempt++) {
            try { action.run(); return; }
            catch (DataAccessException e) {
                if (attempt >= DELAYS.length || !isBusy(e)) throw e;
                sleep(DELAYS[attempt]);
            }
        }
    }
    private boolean isBusy(DataAccessException e) {
        String m = String.valueOf(e.getMostSpecificCause().getMessage()).toUpperCase(Locale.ROOT);
        return m.contains("SQLITE_BUSY") || m.contains("DATABASE IS LOCKED");
    }
}
```

#### 4.5.3 Part 写节流（NFR-02 / R-03）

原项目每个 delta 都写库；Java 版必须节流，否则 100 token/s × 多会话会打爆 SQLite。

```java
/**
 * 内存态权威 + DB 节流落盘。
 * 规则：同一 partId 的写请求合并；满足 (距上次落盘 >= 100ms) 或 (累积 delta >= 4KB) 或
 *      (Part 进入终态) 或 (轮次结束) 时才真正写库。
 * 终态 Part（ToolState.Completed/Error、text-end 后的 TextPart）立即刷。
 */
@Component
public final class PartWriteThrottler {

    private static final Duration INTERVAL = Duration.ofMillis(100);
    private static final int BYTE_THRESHOLD = 4 * 1024;

    private final PartWriter writer;
    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("we0j-part-flush").factory());

    @PostConstruct void start() { ticker.scheduleWithFixedDelay(this::flushDue, 100, 50, TimeUnit.MILLISECONDS); }

    public void submit(Part part, Instant created, boolean terminal) {
        Pending p = pending.compute(part.id(), (id, old) -> {
            Pending np = new Pending(part, created,
                    old == null ? 0 : old.accumulatedBytes + estimateDeltaSize(old.part, part),
                    old == null ? Instant.now() : old.firstSubmitTime);
            return np;
        });
        if (terminal || p.accumulatedBytes >= BYTE_THRESHOLD
                     || Duration.between(p.firstSubmitTime, Instant.now()).compareTo(INTERVAL) >= 0) {
            flush(part.id());
        }
    }

    /** 轮次结束 / 中断 / 关闭时调用：刷全部 */
    public void flushAll() { for (String id : Set.copyOf(pending.keySet())) flush(id); }

    public void flush(String partId) {
        Pending p = pending.remove(partId);
        if (p != null) writer.upsert(p.part, p.created);
    }

    private void flushDue() {
        Instant now = Instant.now();
        pending.forEach((id, p) -> {
            if (Duration.between(p.firstSubmitTime, now).compareTo(INTERVAL) >= 0) flush(id);
        });
    }

    private record Pending(Part part, Instant created, int accumulatedBytes, Instant firstSubmitTime) {}
}
```

**一致性保证**：任何"读历史"路径（`HistoryReader`）必须先 `flushAll()` 或从**内存态权威副本**读，避免读到过期 DB。设计上：
- `SessionStateCache`（进程内，per session）持有 `List<MessageWithParts>` 权威内存态
- Loop 与 Bus 都从 cache 读；DB 仅作持久化与跨进程 resume 源
- `resume` 时从 DB 装载到 cache

#### 4.5.4 Flyway 迁移

```
we0j-infra/src/main/resources/db/migration/
├── V1__baseline.sql          三张表 + 索引 + PRAGMA(user_version 由 Flyway 管)
├── V2__session_runtime.sql   （示例）增加 session.runtime_state
└── V3__part_type_index.sql   （示例）增加 part.type 索引
```
启动配置：
```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    validate-on-migrate: true
  datasource:
    url: jdbc:sqlite:${WE0J_DB_PATH}
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 8          # 虚拟线程下连接池仍需限流（SQLite 单写者）
      connection-timeout: 10000
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    hibernate.ddl-auto: validate    # ★ validate，绝不 update（迁移全权交 Flyway）
    open-in-view: false
```
> 需引入 `org.hibernate.orm:hibernate-community-dialects` 获得 `SQLiteDialect`。

**DB 初始化 PRAGMA**（通过 Hikari `connection-init-sql` 只能执行一条，改用 `BeanPostProcessor` 或启动时 `JdbcTemplate` 逐条执行）：
```java
@Component
@RequiredArgsConstructor
public class SqlitePragmaInitializer {
    private final JdbcTemplate jdbc;
    @PostConstruct
    public void init() {
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA synchronous=NORMAL");
        jdbc.execute("PRAGMA busy_timeout=5000");
        jdbc.execute("PRAGMA cache_size=-64000");
        jdbc.execute("PRAGMA foreign_keys=ON");
        jdbc.execute("PRAGMA temp_store=MEMORY");
        jdbc.execute("PRAGMA mmap_size=268435456");
    }
}
```

**迁移前自动备份**（FR-094）：`FlywayMigrationStrategy` 中先 `Files.copy(db, db.withSuffix(".bak-" + timestamp))`，保留最近 3 份。

### 4.6 JSON 文件存储

```java
/** todos/tasks/crons 等文档型状态。文件锁 + 读改写 + 原子替换 */
@Component
@RequiredArgsConstructor
public final class JsonFileStore {

    private final ObjectMapper mapper;
    private final FileLocks locks;

    public <T> T read(Path file, TypeReference<T> type, T fallback) {
        if (!Files.exists(file)) return fallback;
        try { return mapper.readValue(Files.readAllBytes(file), type); }
        catch (IOException e) {
            // 损坏文件不致命：备份后返回 fallback
            backupCorrupt(file, e);
            return fallback;
        }
    }

    /** 读-改-写，全程持文件锁，避免并发丢写（FR-077 AC） */
    public <T> T update(Path file, TypeReference<T> type, T fallback, UnaryOperator<T> mutator) {
        return locks.withLock(file, () -> {
            T current = read(file, type, fallback);
            T next = mutator.apply(current);
            writeAtomic(file, next);
            return next;
        });
    }

    public void writeAtomic(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + Thread.currentThread().threadId());
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private void backupCorrupt(Path file, Exception cause) { /* file → file.corrupt-<ts> + WARN 日志 */ }
}

/** 跨线程 + 跨进程文件锁。JVM 内用 striped ReentrantLock，跨进程用 FileChannel.tryLock */
@Component
public final class FileLocks {
    private final Striped<ReentrantLock> striped = Striped.lazyWeakLock(256);   // Guava Striped
    public <T> T withLock(Path file, Supplier<T> action) {
        ReentrantLock local = striped.get(file.toAbsolutePath().toString());
        local.lock();                                    // ReentrantLock 不 pin 虚拟线程
        try (FileChannel ch = FileChannel.open(lockFileFor(file),
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = acquireWithRetry(ch)) {
            return action.get();
        } catch (IOException e) { throw new UncheckedIOException(e); }
        finally { local.unlock(); }
    }
    private static Path lockFileFor(Path f) { return f.resolveSibling(f.getFileName() + ".lock"); }
    private FileLock acquireWithRetry(FileChannel ch) throws IOException {
        for (int i = 0; i < 50; i++) {
            FileLock l = ch.tryLock();
            if (l != null) return l;
            try { Thread.sleep(20); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IOException("interrupted while locking"); }
        }
        throw new IOException("timeout acquiring file lock: " + ch);
    }
}
```

### 4.7 FileTimeRegistry（编辑安全链路基石）

```java
/** per-session 的文件读时间戳登记 + staleness 校验 + per-path 锁（FR-073 步骤 1-2） */
@Component
public final class FileTimeRegistry {

    private static final Duration MTIME_TOLERANCE = Duration.ofMillis(50);

    /** sessionId → (realPath → readTime) */
    private final ConcurrentMap<String, ConcurrentMap<Path, Instant>> registry = new ConcurrentHashMap<>();
    private final Striped<ReentrantLock> pathLocks = Striped.lazyWeakLock(512);

    public void stampRead(String sessionId, Path path) {
        registry.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(canon(path), Instant.now());
    }

    /**
     * @throws StaleFileException 从未读过，或磁盘 mtime 晚于登记时间（超容差）
     */
    public void assertRead(String sessionId, Path path) {
        Path key = canon(path);
        Instant readAt = Optional.ofNullable(registry.get(sessionId)).map(m -> m.get(key)).orElse(null);
        if (readAt == null)
            throw new StaleFileException(
                "File has not been read yet. Call Read on '%s' before writing or editing.".formatted(path));
        Instant mtime = mtime(path);
        if (mtime.isAfter(readAt.plus(MTIME_TOLERANCE)))
            throw new StaleFileException(
                "File '%s' was modified externally after you read it (read at %s, mtime %s). "
              .formatted(path, readAt, mtime)
              + "Re-read the file to get its latest content before editing.");
    }

    public <T> T withLock(Path path, Supplier<T> action) {
        ReentrantLock lock = pathLocks.get(canon(path).toString());
        lock.lock();
        try { return action.get(); } finally { lock.unlock(); }
    }

    public void clearSession(String sessionId) { registry.remove(sessionId); }

    /** 规范化：绝对路径 + 解析符号链接 + 统一分隔符。用于锁 key 与登记 key，防同文件多 key */
    private static Path canon(Path p) {
        try { return p.toAbsolutePath().toRealPath(); }
        catch (IOException e) { return p.toAbsolutePath().normalize(); }   // 文件不存在时用 normalize
    }
    private static Instant mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toInstant(); }
        catch (IOException e) { throw new ToolException("cannot stat file: " + p, e); }
    }
}
```

---

## 5. 核心子系统详细设计

### 5.1 会话服务（FR-01）

```java
public sealed interface SessionStatus permits
        SessionStatus.Idle, SessionStatus.Busy, SessionStatus.Retry,
        SessionStatus.Compacting, SessionStatus.Cancelled {
    record Idle() implements SessionStatus {}
    record Busy(int step, String phase) implements SessionStatus {}     // phase: "context"|"streaming"|"tools"
    record Retry(int attempt, long delayMs, String reason) implements SessionStatus {}
    record Compacting(String strategy) implements SessionStatus {}
    record Cancelled() implements SessionStatus {}
    String wire();      // "idle" | "busy" | "retry" | "compacting" | "cancelled"
}

/** 进程内运行态注册表。保证"同一会话同时只有一个 Loop"（FR-014） */
@Component
public final class SessionRegistry {

    public record SessionEntry(
            String sessionId,
            AbortSignal abortSignal,
            CompletableFuture<LoopOutcome> completion,     // 供第二个调用者 attach
            BlockingQueue<UserInput> queuedInputs,         // FR-028 mid-turn 注入
            AtomicReference<SessionStatus> status,
            RuntimeLane lane,
            Instant startedAt,
            ConcurrentHashMap<String, CompletableFuture<ReplyDecision>> pendingPermissions,
            ConcurrentHashMap<String, CompletableFuture<List<List<String>>>> pendingQuestions) {}

    private final ConcurrentMap<String, SessionEntry> entries = new ConcurrentHashMap<>();

    /** 返回 empty 表示已有 Loop 在跑，调用方应 attach 到 entry.completion() */
    public Optional<SessionEntry> tryAcquire(String sessionId, Supplier<SessionEntry> factory) {
        AtomicBoolean created = new AtomicBoolean();
        SessionEntry e = entries.computeIfAbsent(sessionId, k -> { created.set(true); return factory.get(); });
        return created.get() ? Optional.of(e) : Optional.empty();
    }
    public Optional<SessionEntry> find(String sessionId) { return Optional.ofNullable(entries.get(sessionId)); }
    public void release(String sessionId) { entries.remove(sessionId); }
    public Collection<SessionEntry> all() { return entries.values(); }
}

@Service
@RequiredArgsConstructor
public final class SessionService {

    private final SessionRowRepository sessionRepo;
    private final MessageStore messageStore;
    private final PartStore partStore;
    private final SessionStateCache cache;
    private final Bus bus;
    private final SettingsStore settings;

    public Session create(CreateSessionCommand cmd) {
        String id = Ulids.next();
        String projectId = ProjectId.of(cmd.workdir());
        SessionRow row = SessionRow.builder()
                .id(id).projectId(projectId).parentId(cmd.parentId())
                .directory(cmd.workdir().toAbsolutePath().toString())
                .title("New Session - " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now()))
                .version(Version.current())
                .timeCreated(Instant.now().toEpochMilli()).timeUpdated(Instant.now().toEpochMilli())
                .incognito(cmd.incognito())
                .runtimeState(Jsons.write(RuntimeState.initial(cmd)))
                .build();
        sessionRepo.save(row);
        cache.init(id);
        bus.publish(new SessionUpdated(id, SessionStatus.Idle, row.getTitle(), bus.nextSeq()));
        return toDomain(row);
    }

    /** FR-013：完整恢复。返回内存态权威副本 */
    public RestoredSession restore(String sessionId) {
        SessionRow row = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("session not found: " + sessionId));
        List<MessageRow> msgRows = messageStore.findRows(sessionId);
        List<PartRow> partRows   = partStore.findRows(sessionId);

        // 1) blob 反序列化（Jackson 多态）
        List<Message> messages = msgRows.stream().map(messageStore::deserialize).toList();
        Map<String, List<Part>> partsByMessage = partRows.stream()
                .map(partStore::deserialize)
                .collect(groupingBy(Part::messageId,
                         collectingAndThen(toList(), l -> { /* 按 timeCreated + 插入序稳定排序 */ return l; })));

        // 2) 修复半截状态（NFR-03）：pending/running 的 ToolPart → Error("进程重启导致中断")
        partsByMessage.replaceAll((mid, parts) -> parts.stream().map(this::repairDangling).toList());

        // 3) 运行时状态：权限规则、已激活延迟工具、人格、权限模式
        RuntimeState rt = Jsons.read(row.getRuntimeState(), RuntimeState.class, RuntimeState::empty);

        // 4) 文件状态：todos / tasks
        List<TodoItem> todos = todoStore.load(sessionId);
        List<TaskV2>  tasks  = taskStore.load(sessionId);

        // 5) 装载内存权威副本
        cache.load(sessionId, messages, partsByMessage, rt);

        return new RestoredSession(toDomain(row), messages, partsByMessage, rt, todos, tasks);
    }

    private Part repairDangling(Part p) {
        if (p instanceof ToolPart tp && (tp.state() instanceof ToolState.Pending
                                      || tp.state() instanceof ToolState.Running)) {
            Instant now = Instant.now();
            return tp.withState(new ToolState.Error(tp.state().input(),
                    "Tool execution was interrupted by process restart.",
                    Map.of("repairedAtStartup", true),
                    new TimeRange(now, now)));
        }
        return p;
    }
}

/** RuntimeState：会话运行时状态（落 session.runtime_state JSON 列） */
public record RuntimeState(
        String agentName,                 // build / plan / explore / 自定义
        PermissionMode permissionMode,
        List<PermissionRule> runtimePermissionRules,   // always 回复产生
        Set<String> activatedDeferredTools,            // FR-065 恢复
        List<String> invokedSkills,                    // 压缩后恢复用
        String lastModelRef,                           // "anthropic/claude-sonnet-4-5"
        RevertRecord pendingRevert,
        Map<String, Object> extra) {
    public static RuntimeState initial(CreateSessionCommand cmd) { /* ... */ }
    public static RuntimeState empty() { /* ... */ }
}
```

**`SessionStateCache`（内存权威副本）**

```java
@Component
public final class SessionStateCache {
    private final ConcurrentMap<String, Entry> byId = new ConcurrentHashMap<>();

    public static final class Entry {
        final List<Message> messages = new CopyOnWriteArrayList<>();
        final ConcurrentMap<String, List<Part>> parts = new ConcurrentHashMap<>();   // messageId → parts（有序）
        final ConcurrentMap<String, Part> partIndex = new ConcurrentHashMap<>();     // partId → part（O(1) 更新）
        volatile RuntimeState runtimeState;
        final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    }

    public List<MessageWithParts> history(String sessionId) { /* 组装，读锁 */ }
    public Part updatePart(String sessionId, Part part) { /* 写锁：替换 partIndex + parts 列表中同 id 项 */ }
    public void appendDelta(String sessionId, String partId, String field, String delta) {
        // 高频路径：只改内存，不发 DB（DB 由 PartWriteThrottler 节流）
    }
    public void removePart(String sessionId, String partId) { /* 中断清理 */ }
}
```

---

### 5.2 AgentLoop —— 双循环内核（FR-02）★

#### 5.2.1 类结构

```java
package com.we0j.agent.loop;

public enum TurnResult { CONTINUE, STOP, COMPACT }
public enum LoopExitReason {
    COMPLETED_REPLY, STOP_SIGNAL, MODE_SWITCH_RESTART, QUEUED_INPUT_RESTART,
    ABORTED, MAX_STEPS, FATAL_ERROR
}
public record LoopOutcome(LoopExitReason reason, String sessionId, int steps,
                          Tokens tokens, BigDecimal cost, MessageError error) {}

/** 会话门面：CLI 与 Web 的唯一入口 */
@Service
@RequiredArgsConstructor
public final class SessionFacade {

    private final SessionService sessions;
    private final SessionRegistry registry;
    private final AgentLoopFactory loopFactory;
    private final ExecutorService io = VirtualThreadExecutors.IO;

    public record PromptInput(String sessionId, String text, List<FilePart> attachments,
                              ChannelSource source, String agentName, ModelRef modelOverride,
                              OutputFormat format, Map<String,Boolean> toolOverrides) {}

    /**
     * 提交用户输入。
     * - 若会话空闲：创建 UserMessage，启动 Loop 虚拟线程，返回 completion future
     * - 若会话忙：入 queuedInputs（FR-028），Loop 在工具子循环间隙 drain
     */
    public CompletableFuture<LoopOutcome> prompt(PromptInput input) {
        var entryOpt = registry.find(input.sessionId());
        if (entryOpt.isPresent()) {                       // 已有 Loop → attach / 排队
            SessionEntry entry = entryOpt.get();
            entry.queuedInputs().offer(new UserInput(input.text(), input.attachments(), input.source()));
            return entry.completion();
        }
        String userMessageId = sessions.appendUserMessage(input);     // 落库 + Bus
        SessionEntry entry = registry.tryAcquire(input.sessionId(),
                () -> newEntry(input.sessionId())).orElseThrow();     // 双检：并发提交只有一个胜出
        CompletableFuture<LoopOutcome> future = CompletableFuture.supplyAsync(
                () -> RuntimeLaneRegistry.callAs(RuntimeLane.MAIN,
                        () -> loopFactory.create(input.sessionId(), entry).run(false)),
                io);
        entry.completion().complete(...);                  // 实际实现：future 完成后回填 entry.completion
        return future;
    }

    public void cancel(String sessionId) {
        registry.find(sessionId).ifPresent(e -> e.abortSignal().abort());
    }

    /** 通知回流唤醒（FR-153）：会话空闲时注入合成 UserMessage 并启动 Loop */
    public CompletableFuture<LoopOutcome> resumeExisting(String sessionId, String syntheticText,
                                                         ChannelSource source) { /* ... */ }
}
```

#### 5.2.2 外层循环完整实现

```java
/**
 * 外层 Loop。对应原项目 SessionPrompt.loop()（prompt.py:1088-1700）。
 * ★ 核心不变式：所有状态每轮从 DB/内存权威副本重新推导，不做跨轮内存缓存。
 *   这是 resume / revert / compaction 后状态自洽的根本保证。
 */
@RequiredArgsConstructor
public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final String sessionId;
    private final SessionEntry entry;
    private final SessionService sessions;
    private final SessionStateCache cache;
    private final HistoryReader history;
    private final ContextAssembler context;
    private final CompactionService compaction;
    private final OverflowDetector overflow;
    private final TurnProcessorFactory turnFactory;
    private final ToolExecutor toolExecutor;
    private final SnapshotService snapshot;
    private final NotificationService notifications;
    private final TodoService todos;
    private final TaskService tasks;
    private final SkillService skills;
    private final ModelCardManager models;
    private final Bus bus;
    private final Settings settings;
    private final boolean resumeExisting;

    public LoopOutcome run(boolean resumeExisting) {
        AbortSignal abort = entry.abortSignal();
        int step = 0;
        Tokens accumulated = Tokens.empty();
        BigDecimal cost = BigDecimal.ZERO;
        LoopExitReason reason = LoopExitReason.COMPLETED_REPLY;

        try {
            if (resumeExisting) waitForActiveSession();          // attach 到既有 Loop

            // 消费 pending revert（FR-102：在 prompt() 入口消费）
            consumePendingRevert();

            OUTER:
            while (true) {
                abort.throwIfAborted();

                // ── 1. 置 Busy ────────────────────────────────────────────────
                setStatus(new SessionStatus.Busy(step, "context"));

                // ── 2. 读历史 + 过滤压缩边界 ─────────────────────────────────
                List<MessageWithParts> all = history.streamMessages(sessionId);
                List<MessageWithParts> msgs = CompactedHistoryFilter.apply(all);

                // ── 3. 抽取标记（每轮重新推导，无缓存）─────────────────────────
                LoopMarkers m = LoopMarkers.extract(msgs);
                // m.lastUser / m.lastAssistant / m.lastFinishedAssistant / m.compactionParts
                // m.pendingCompaction / m.activeTodos / m.activeTasks / m.modeSwitchPending

                // ── 4. 主退出判定 ─────────────────────────────────────────────
                if (hasCompletedReplyForLastUser(m)) { reason = LoopExitReason.COMPLETED_REPLY; break; }

                // ── 5. 队列输入 → 重启外层（FR-028）──────────────────────────
                if (!entry.queuedInputs().isEmpty()) { reason = LoopExitReason.QUEUED_INPUT_RESTART; break; }

                // ── 6. 压缩任务处理 ───────────────────────────────────────────
                if (m.pendingCompaction() != null) {
                    CompactionOutcome co = compaction.process(sessionId, m.pendingCompaction(), abort.child());
                    switch (co.action()) {
                        case CONTINUE -> { continue OUTER; }
                        case BREAK    -> { reason = LoopExitReason.STOP_SIGNAL; break OUTER; }
                        case NONE     -> { /* 落空，继续 */ }
                    }
                }

                // ── 7. 溢出预检 → 调度压缩 → continue ────────────────────────
                ModelCard card = models.resolve(currentModelRef(m));
                if (RuntimeGate.mainAgentOnlyDecision()
                        && overflow.shouldCompactBeforeRequest(sessionId, msgs, card)) {
                    compaction.schedule(sessionId, CompactionTrigger.PRE_REQUEST, abort.child());
                    continue OUTER;
                }

                // ── 8. 上下文构建 ─────────────────────────────────────────────
                setStatus(new SessionStatus.Busy(step, "context"));
                ContextBundle bundle = context.assemble(ContextAssembleCommand.builder()
                        .sessionId(sessionId)
                        .history(msgs)
                        .markers(m)
                        .modelCard(card)
                        .lane(RuntimeLaneRegistry.current())
                        .drainSkillUpdates(skills.consumePendingUpdates())        // 热加载 drain
                        .drainNotifications(notifications.drain(sessionId))        // 后台通知 drain
                        .drainQueuedInputs(drainQueuedInputs())                      // 队列输入 drain
                        .build());
                // bundle = { systemBlocks: List<PromptBlock>,
                //            modelMessages: List<ProviderMessage>,   ← 已含 reminder 合成 Part
                //            tools: List<ToolDefinition>,            ← 已过滤 lazy
                //            cacheStrategy, estimatedTokens }

                // ── 9. 请求前溢出复检 ─────────────────────────────────────────
                if (RuntimeGate.mainAgentOnlyDecision()
                        && overflow.isInputOverflow(bundle.estimatedTokens(), card)) {
                    compaction.schedule(sessionId, CompactionTrigger.PRE_REQUEST_OVERFLOW, abort.child());
                    continue OUTER;
                }

                // ── 10. 快照锚点：step-start ──────────────────────────────────
                String treeHash = settings.code().runtime().snapshot()
                        ? snapshot.track(sessionId).orElse(null) : null;

                AssistantMessage assistant = sessions.createAssistantMessage(sessionId, m);
                sessions.appendPart(assistant.id(), new StepStartPart(
                        Ulids.next(), assistant.id(), sessionId, treeHash));

                // ── 11. 内层循环 ──────────────────────────────────────────────
                setStatus(new SessionStatus.Busy(step, "streaming"));
                List<PendingToolCall> toolCalls = new ArrayList<>();
                TurnResult result;
                try (AbortScope turnScope = AbortScope.of(abort)) {
                    TurnProcessor processor = turnFactory.create(sessionId, assistant, card,
                                                                 turnScope.signal());
                    result = processor.process(TurnInput.of(bundle, step, treeHash), toolCalls);
                }

                accumulated = accumulated.plus(assistant.tokens());               // 重新从 cache 取最新
                cost = cost.add(assistant.cost() == null ? BigDecimal.ZERO : assistant.cost());
                step++;

                if (result == TurnResult.COMPACT) {
                    compaction.schedule(sessionId, CompactionTrigger.POST_FINISH_STEP, abort.child());
                    continue OUTER;
                }
                if (result == TurnResult.STOP) { reason = LoopExitReason.STOP_SIGNAL; break; }

                // ── 12. 模式切换需重启（FR-081）───────────────────────────────
                if (m.modeSwitchPending() || detectModeSwitch(assistant)) {
                    reason = LoopExitReason.MODE_SWITCH_RESTART; break;
                }

                // ── 13. maxSteps 强制门禁（FR-022，原项目未强制，本版强制）─────
                if (step >= settings.common().loop().maxSteps()) {
                    sessions.appendPart(assistant.id(), TextPart.system(
                            "[Reached max steps (%d). Stopping to avoid runaway loop. "
                          .formatted(step) + "Use /compact or start a new session to continue.]"));
                    reason = LoopExitReason.MAX_STEPS; break;
                }

                // ── 14. 工具子循环 ────────────────────────────────────────────
                if (toolCalls.isEmpty()) { reason = LoopExitReason.COMPLETED_REPLY; break; }

                setStatus(new SessionStatus.Busy(step, "tools"));
                ToolBatchOutcome batch = toolExecutor.executeBatch(
                        ToolBatchCommand.of(sessionId, assistant.id(), toolCalls, abort.child()));
                // executeBatch 内部：并发执行 + 状态流转 Part 更新 + Bus 发布 + 输出截断落盘
                // 返回：role=tool 的结果消息列表（已按 toolCalls 顺序对齐）

                if (batch.allDenied() && !settings.continueLoopOnDeny()) {
                    reason = LoopExitReason.STOP_SIGNAL; break;
                }

                // 工具结果追加后溢出检查（FR-051 时机③）
                if (RuntimeGate.mainAgentOnlyDecision()
                        && overflow.shouldCompactAfterToolResults(sessionId, batch, card)) {
                    compaction.schedule(sessionId, CompactionTrigger.POST_TOOL_RESULTS, abort.child());
                    continue OUTER;
                }

                // 时间维微压缩（FR-054）
                if (RuntimeGate.mainAgentOnlyDecision()) compaction.maybeMicrocompact(sessionId, card);

                // 回到步骤 1，下一轮从（已含工具结果的）历史重新推导 → 新 AssistantMessage + 新 TurnProcessor
            }

            // ── 收尾 ──────────────────────────────────────────────────────────
            if (reason == LoopExitReason.QUEUED_INPUT_RESTART || reason == LoopExitReason.MODE_SWITCH_RESTART) {
                return run(false);                                  // 递归重启（栈深可控：每次重启前已 break）
            }
            finalizeTurn(accumulated, cost, step);                   // 异步：标题生成、diff 摘要、todo 刷新
            return new LoopOutcome(reason, sessionId, step, accumulated, cost, null);

        } catch (AbortedException | InferenceAbortedException e) {
            cleanupAfterAbort();                                     // FR-024 清理
            return new LoopOutcome(LoopExitReason.ABORTED, sessionId, step, accumulated, cost,
                                   new MessageError.Aborted("interrupted by user"));
        } catch (Exception e) {
            log.error("agent loop fatal error session={}", sessionId, e);
            sessions.recordFatalError(sessionId, e);
            bus.publish(new SessionError(sessionId, MessageError.from(e), bus.nextSeq()));
            return new LoopOutcome(LoopExitReason.FATAL_ERROR, sessionId, step, accumulated, cost,
                                   MessageError.from(e));
        } finally {
            setStatus(new SessionStatus.Idle());
            partThrottler.flushAll();                                // ★ 落盘全部 pending part
            registry.release(sessionId);
            RuntimeLaneRegistry.clear();
        }
    }

    /**
     * FR-022 条件①：最后一条 user 之后是否已有成功完成的 assistant 回复。
     * 语义要点：
     *  - 只看在 lastUser 之后创建的 assistant
     *  - 必须 timeCompleted != null 且 error == null
     *  - 压缩产生的合成 assistant（summary=true）不算
     */
    private boolean hasCompletedReplyForLastUser(LoopMarkers m) {
        if (m.lastUser() == null) return true;                 // 没有 user 输入 → 无事可做
        return m.lastFinishedAssistant() != null
            && m.lastFinishedAssistant().timeCreated().isAfter(m.lastUser().timeCreated())
            && !Boolean.TRUE.equals(m.lastFinishedAssistant().summary());
    }

    private void cleanupAfterAbort() {
        // 1) 删除本轮未完成 Part：ToolPart(pending|running)、未闭合 text/reasoning
        // 2) 写入 [Request interrupted by user]
        // 3) AssistantMessage.timeCompleted = now, error = Aborted
        // 4) 落盘 + Bus message.part.removed / message.updated
        sessions.cleanupAbortedTurn(sessionId);
    }

    private void waitForActiveSession() { /* 轮询 registry 直到目标会话空闲，或 attach 其 completion */ }
    private void consumePendingRevert() { /* 读 RuntimeState.pendingRevert → RevertService.apply → 清空 */ }
    private List<UserInput> drainQueuedInputs() { /* 非阻塞 drainTo */ }
    private void setStatus(SessionStatus s) {
        entry.status().set(s);
        sessions.updateStatus(sessionId, s);
        bus.publish(new SessionUpdated(sessionId, s, null, bus.nextSeq()));
    }
}
```

#### 5.2.3 `LoopMarkers` —— 历史推导（无状态）

```java
/**
 * 每轮从历史重新推导的标记集合。对应原项目 loop_helper.extract_messages / extract_markers。
 * ★ 不持有任何跨轮状态；纯函数。
 */
public record LoopMarkers(
        UserMessage lastUser,
        AssistantMessage lastAssistant,
        AssistantMessage lastFinishedAssistant,
        List<CompactionPart> compactionParts,
        CompactionPart lastCompaction,
        CompactionRequest pendingCompaction,       // 由 CompactionPart.prompt == null 或 time_compacting 推断
        List<TodoItem> activeTodos,
        List<TaskV2> activeTasks,
        boolean modeSwitchPending,
        List<String> invokedSkills,
        Set<String> discoveredDeferredTools,       // 从历史 ToolPart.metadata.toolReferences 重建
        List<String> recentlyEditedFiles) {

    public static LoopMarkers extract(List<MessageWithParts> msgs) {
        UserMessage lastUser = null;
        AssistantMessage lastAssistant = null, lastFinished = null;
        List<CompactionPart> comps = new ArrayList<>();
        Set<String> discovered = new LinkedHashSet<>();
        List<String> invokedSkills = new ArrayList<>();
        List<String> editedFiles = new ArrayList<>();

        for (MessageWithParts mwp : msgs) {
            switch (mwp.message()) {
                case UserMessage u -> lastUser = u;
                case AssistantMessage a -> {
                    lastAssistant = a;
                    if (a.isCompletedSuccessfully()) lastFinished = a;
                }
            }
            for (Part p : mwp.parts()) {
                switch (p) {
                    case CompactionPart c -> comps.add(c);
                    case ToolPart t -> {
                        // 重建延迟工具激活态（FR-065 AC）
                        Object refs = t.state() instanceof ToolState.Completed cc ? cc.metadata().get("toolReferences") : null;
                        if (refs instanceof List<?> l) l.forEach(x -> discovered.add(String.valueOf(x)));
                        // 重建已调用 skill
                        if ("SKILL".equals(t.toolName()) && t.state() instanceof ToolState.Completed cc2) {
                            Object name = cc2.input().get("name");
                            if (name != null) invokedSkills.add(String.valueOf(name));
                        }
                        // 重建最近编辑文件
                        if (("Edit".equals(t.toolName()) || "Write".equals(t.toolName()))
                                && t.state() instanceof ToolState.Completed) {
                            Object path = t.state().input().get("path");
                            if (path != null) editedFiles.add(String.valueOf(path));
                        }
                    }
                    default -> { }
                }
            }
        }
        CompactionPart lastComp = comps.isEmpty() ? null : comps.get(comps.size() - 1);
        return new LoopMarkers(lastUser, lastAssistant, lastFinished, comps, lastComp,
                inferPendingCompaction(msgs, lastComp),
                TodoSnapshot.current(), TaskSnapshot.current(),
                detectModeSwitchPending(msgs),
                List.copyOf(distinctTail(invokedSkills, 20)),
                Set.copyOf(discovered),
                List.copyOf(distinctTail(editedFiles, 30)));
    }
}
```

#### 5.2.4 内层循环 `TurnProcessor`（FR-02 内层）

```java
/**
 * 内层循环 = 一次完整消费 ModelClient.stream() 的事件流。
 * 对应原项目 SessionProcessor.process()（processor.py:113）。
 * 职责：
 *  1) 逐事件把内容落到 Part（内存权威 + 节流写库 + Bus delta）
 *  2) 收集 tool_calls 到 outToolCalls
 *  3) 判定返回 CONTINUE / STOP / COMPACT
 *  4) 处理流错误与重试
 */
@RequiredArgsConstructor
public final class TurnProcessor {

    private final String sessionId;
    private final AssistantMessage assistant;
    private final ModelCard card;
    private final AbortSignal abort;
    private final ModelClient modelClient;
    private final SessionService sessions;
    private final SessionStateCache cache;
    private final Bus bus;
    private final RetryScheduler retry;
    private final OverflowDetector overflow;
    private final CompactionService compaction;
    private final LlmTracer tracer;

    /** 可变累积态（单线程消费，无需同步） */
    private final Map<String, StringBuilder> reasoningBuf = new LinkedHashMap<>();
    private final Map<String, StringBuilder> textBuf = new LinkedHashMap<>();
    private final Map<String, String> partIdByBlockId = new HashMap<>();
    private final ToolCallAccumulator toolAcc = new ToolCallAccumulator();
    private String currentReasoningPartId, currentTextPartId;
    private int textBlockSeq, reasoningBlockSeq;

    public TurnResult process(TurnInput in, List<PendingToolCall> outToolCalls) {
        long t0 = System.nanoTime();
        AtomicLong ttft = new AtomicLong(-1);
        int attempt = 0;

        while (true) {
            abort.throwIfAborted();
            try {
                ChatRequest req = ChatRequest.builder()
                        .model(card)
                        .system(in.bundle().systemBlocks())
                        .messages(in.bundle().modelMessages())
                        .tools(in.bundle().tools())
                        .cacheStrategy(in.bundle().cacheStrategy())
                        .reasoning(in.bundle().reasoningConfig())
                        .maxOutputTokens(card.maxOutput())
                        .build();

                try (EventStream stream = modelClient.openStream(req, abort)) {
                    for (StreamEvent ev : stream) {
                        abort.throwIfAborted();
                        if (ttft.get() < 0) ttft.set(System.nanoTime() - t0);
                        handle(ev, in, outToolCalls);
                    }
                }
                // 正常结束
                return finishTurn(in, outToolCalls, ttft.get(), t0);

            } catch (InferenceAbortedException e) {
                throw e;                                              // 中断直接上抛，由 Loop 清理
            } catch (ContextOverflowException e) {
                // FR-053：删除本轮失败消息 → 调度压缩 → 返回 COMPACT
                sessions.discardAssistantMessage(sessionId, assistant.id());
                compaction.schedule(sessionId, CompactionTrigger.REACTIVE_OVERFLOW, abort);
                return TurnResult.COMPACT;
            } catch (ModelException e) {
                if (!retry.isRetryable(e) || attempt >= retry.maxAttempts()) {
                    sessions.recordAssistantError(sessionId, assistant.id(), MessageError.from(e));
                    bus.publish(new SessionError(sessionId, MessageError.from(e), bus.nextSeq()));
                    return TurnResult.STOP;
                }
                attempt++;
                long delay = retry.computeDelay(attempt, e.responseHeaders());
                setStatus(new SessionStatus.Retry(attempt, delay, e.shortReason()));
                sessions.appendPart(assistant.id(), new RetryPart(Ulids.next(), assistant.id(),
                        sessionId, MessageError.from(e), new TimeCreated(Instant.now())));
                retry.sleep(delay, abort);                            // 响应 abort 的 sleep
                continue;
            } catch (EmptyStreamException e) {
                if (attempt >= EmptyStreamGuard.MAX_RETRIES) {        // 3
                    sessions.recordAssistantError(sessionId, assistant.id(), MessageError.from(e));
                    return TurnResult.STOP;
                }
                attempt++;
                retry.sleep(500, abort);
                continue;
            }
        }
    }

    // ── 事件处理：状态机 ──────────────────────────────────────────────────────
    private void handle(StreamEvent ev, TurnInput in, List<PendingToolCall> outToolCalls) {
        switch (ev) {
            case StreamEvent.Start e -> { /* no-op：AssistantMessage 已由 Loop 创建 */ }

            case StreamEvent.StartStep e -> { /* 一个 step 内的子步；MVP 与外层 step 一致，忽略 */ }

            case StreamEvent.ReasoningStart e -> {
                String partId = Ulids.next();
                currentReasoningPartId = partId;
                partIdByBlockId.put(e.id(), partId);
                reasoningBuf.put(e.id(), new StringBuilder());
                ReasoningPart p = new ReasoningPart(partId, assistant.id(), sessionId, "",
                        new HashMap<>(), new TimeStart(Instant.now(), null));
                sessions.appendPart(partId, p);                        // 内存 + 节流写 + Bus part.updated
            }
            case StreamEvent.ReasoningDelta e -> {
                StringBuilder sb = reasoningBuf.get(e.id());
                if (sb == null) return;                                // 缺 start 事件的容错
                sb.append(e.text());
                cache.appendDelta(sessionId, partIdByBlockId.get(e.id()), "text", e.text());
                bus.publish(new MessagePartDelta(sessionId, assistant.id(),
                        partIdByBlockId.get(e.id()), "text", e.text(), bus.nextSeq()));
            }
            case StreamEvent.ReasoningEnd e -> {
                String partId = partIdByBlockId.get(e.id());
                ReasoningPart p = new ReasoningPart(partId, assistant.id(), sessionId,
                        reasoningBuf.get(e.id()).toString(),
                        Map.of("signature", toolAcc.takeThinkingSignature(e.id())),  // Anthropic 需回传
                        new TimeStart(startTimeOf(partId), Instant.now()));
                sessions.updatePart(p, /*terminal*/ true);
                currentReasoningPartId = null;
            }

            case StreamEvent.TextStart e -> {
                String partId = Ulids.next();
                currentTextPartId = partId;
                partIdByBlockId.put(e.id(), partId);
                textBuf.put(e.id(), new StringBuilder());
                sessions.appendPart(partId, new TextPart(partId, assistant.id(), sessionId, "",
                        null, null, null, new TimeStart(Instant.now(), null), Map.of()));
            }
            case StreamEvent.TextDelta e -> {
                StringBuilder sb = textBuf.computeIfAbsent(e.id(), k -> new StringBuilder());
                sb.append(e.text());
                String partId = partIdByBlockId.get(e.id());
                cache.appendDelta(sessionId, partId, "text", e.text());
                bus.publish(new MessagePartDelta(sessionId, assistant.id(), partId, "text", e.text(), bus.nextSeq()));
            }
            case StreamEvent.TextEnd e -> {
                String partId = partIdByBlockId.get(e.id());
                sessions.updatePart(new TextPart(partId, assistant.id(), sessionId,
                        textBuf.get(e.id()).toString(), null, null, null,
                        new TimeStart(startTimeOf(partId), Instant.now()), Map.of()), true);
                currentTextPartId = null;
            }

            case StreamEvent.ToolInputStart e -> {
                toolAcc.begin(e.id(), e.toolCallId(), e.toolName());
                String partId = Ulids.next();
                partIdByBlockId.put(e.id(), partId);
                sessions.appendPart(partId, new ToolPart(partId, assistant.id(), sessionId,
                        e.toolCallId(), e.toolName(),
                        new ToolState.Pending(Map.of(), ""), Map.of()));
            }
            case StreamEvent.ToolInputDelta e -> {
                toolAcc.appendArguments(e.id(), e.delta());
                String partId = partIdByBlockId.get(e.id());
                ToolPart tp = (ToolPart) cache.part(partId);
                sessions.updatePart(tp.withState(new ToolState.Pending(Map.of(),
                        toolAcc.rawArguments(e.id()))), false);          // 非终态 → 节流写
            }
            case StreamEvent.ToolInputEnd e -> { /* 等 ToolCall 统一处理 */ }

            case StreamEvent.ToolCall e -> {
                String blockId = toolAcc.blockIdOfCall(e.toolCallId());
                String partId = partIdByBlockId.get(blockId);
                ToolPart tp = new ToolPart(partId, assistant.id(), sessionId,
                        e.toolCallId(), e.toolName(),
                        new ToolState.Pending(e.input(), toolAcc.rawArguments(blockId)),
                        Map.of());
                sessions.updatePart(tp, true);
                outToolCalls.add(new PendingToolCall(partId, e.toolCallId(), e.toolName(), e.input()));
            }

            case StreamEvent.FinishStep e -> {
                // usage 落地 + 溢出判定（FR-051 时机①）
                sessions.updateAssistantUsage(sessionId, assistant.id(), e.usage(), e.finishReason());
                if (RuntimeGate.mainAgentOnlyDecision() && overflow.needsCompactionAfterFinish(e.usage(), card)) {
                    pendingCompact = true;
                }
            }
            case StreamEvent.Finish e -> {
                sessions.finishAssistantMessage(sessionId, assistant.id(), e.totalUsage(), e.finishReason());
            }
            case StreamEvent.Error e -> { throw ModelException.wrap(e.error()); }

            case StreamEvent.ToolResult e, StreamEvent.ToolError e -> {
                // 由外层 Loop 注入，不在流内出现
            }
        }
    }

    private TurnResult finishTurn(TurnInput in, List<PendingToolCall> outToolCalls, long ttftNs, long t0) {
        tracer.record(TraceRecord.of(sessionId, assistant.id(), card, ttftNs,
                System.nanoTime() - t0, in.bundle().estimatedTokens(), outToolCalls.size()));
        if (pendingCompact) return TurnResult.COMPACT;
        if (!outToolCalls.isEmpty()) return TurnResult.CONTINUE;
        return hasFatalError() ? TurnResult.STOP : TurnResult.CONTINUE;
        // 注：无工具调用且无错误时返回 CONTINUE，由外层 Loop 的
        //     hasCompletedReplyForLastUser() 判定退出（保持与原项目一致的"历史驱动退出"语义）
    }
}
```

#### 5.2.5 工具批量并发执行（FR-025）

```java
@Component
@RequiredArgsConstructor
public final class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolResolver resolver;
    private final PermissionService permissions;
    private final OutputTruncator truncator;
    private final ToolOutputStorage outputStorage;
    private final SessionService sessions;
    private final Bus bus;
    private final HookChain hooks;                       // P2 前为 no-op 实现
    private final ExecutorService toolExecutor = VirtualThreadExecutors.io("we0j-tool-");

    public record PendingToolCall(String partId, String callId, String toolName, Map<String,Object> input) {}

    public record ToolBatchOutcome(List<ProviderMessage> toolResultMessages, int step,
                                   boolean allDenied, int succeeded, int failed, int denied) {}

    public ToolBatchOutcome executeBatch(ToolBatchCommand cmd) {
        List<PendingToolCall> calls = cmd.calls();

        // ★ 并发执行，单个异常不影响其他（对齐 gather(return_exceptions=True)）
        List<CompletableFuture<SingleOutcome>> futures = calls.stream()
                .map(c -> CompletableFuture.supplyAsync(
                        () -> RuntimeLaneRegistry.callAs(cmd.lane(), () -> runOne(cmd, c)),
                        toolExecutor))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        // ★ 按原 calls 顺序对齐结果（模型要求 tool_result 与 tool_call 顺序对应）
        List<SingleOutcome> outcomes = futures.stream().map(CompletableFuture::join).toList();

        List<ProviderMessage> results = new ArrayList<>(outcomes.size());
        int ok = 0, fail = 0, denied = 0;
        for (int i = 0; i < calls.size(); i++) {
            PendingToolCall c = calls.get(i);
            SingleOutcome o = outcomes.get(i);
            results.add(o.toProviderMessage(c.callId()));     // role=tool, content=<output|error text>
            switch (o.kind()) { case SUCCESS -> ok++; case ERROR -> fail++; case DENIED -> denied++; }
        }
        return new ToolBatchOutcome(results, cmd.step() + 1,
                denied == calls.size(), ok, fail, denied);
    }

    /** 单工具执行全流程。任何异常都在此收敛为 SingleOutcome，不外泄 */
    private SingleOutcome runOne(ToolBatchCommand cmd, PendingToolCall call) {
        Instant start = Instant.now();
        AbortSignal toolAbort = cmd.abort().child();          // ★ 子信号：单工具失败不 abort 整轮
        try {
            // 1) 解析工具（含 overlay / 可见性 / 激活态）
            Tool tool = resolver.resolveOne(cmd.sessionId(), call.toolName())
                    .orElseThrow(() -> new ToolException("Tool not available: " + call.toolName()
                        + ". Use ToolSearch to discover and activate it if it is a deferred tool."));

            // 2) hook: tool.execute.before（可改写 input 或直接拒绝）
            ToolInput input = hooks.beforeToolExecute(new BeforeToolExecute(
                    cmd.sessionId(), call.callId(), call.toolName(), call.input()));

            // 3) 状态 → Running，发 Bus
            sessions.updateToolState(cmd.sessionId(), call.partId(),
                    new ToolState.Running(input.raw(), deriveTitle(tool, input), Map.of(),
                                          new TimeStartOnly(start)));

            // 4) 构造 ToolContext（内含权限门、提问门、abort、落盘 sink）
            ToolContext ctx = ToolContext.builder()
                    .sessionId(cmd.sessionId()).messageId(cmd.messageId()).callId(call.callId())
                    .abort(toolAbort).workdir(cmd.workdir())
                    .gate(permissions.gateFor(cmd.sessionId(), call.partId(), call.callId()))
                    .questions(questions.gateFor(cmd.sessionId(), call.partId(), call.callId()))
                    .output(outputStorage.sinkFor(cmd.sessionId(), call.callId()))
                    .lane(cmd.lane())
                    .settings(cmd.settings())
                    .build();

            // 5) 执行（工具内部自行做权限询问）
            ToolResult raw = tool.execute(input, ctx);

            // 6) 统一截断 + 落盘（FR-062）
            ToolResult truncated = truncator.apply(raw, ctx.output().fullPath());

            // 7) hook: tool.execute.after
            ToolResult finalResult = hooks.afterToolExecute(new AfterToolExecute(
                    cmd.sessionId(), call.callId(), call.toolName(), input.raw(), truncated));

            // 8) 状态 → Completed
            Instant end = Instant.now();
            sessions.updateToolState(cmd.sessionId(), call.partId(),
                    new ToolState.Completed(input.raw(),
                            finalResult.textForAudience(Audience.ASSISTANT),
                            deriveTitle(tool, input),
                            mergeMetadata(finalResult.structuredContent(), ctx),
                            new TimeRangeCompacted(start, end, null),
                            finalResult.attachments()));

            return SingleOutcome.success(finalResult);

        } catch (PermissionDeniedException | PermissionRejectedException e) {
            Instant end = Instant.now();
            sessions.updateToolState(cmd.sessionId(), call.partId(),
                    new ToolState.Error(call.input(), e.userFacingMessage(),
                            Map.of("denied", true), new TimeRange(start, end)));
            return SingleOutcome.denied(e);

        } catch (AbortedException | InferenceAbortedException e) {
            // 中断：不写 Error 状态，由 Loop.cleanupAfterAbort() 统一清理
            throw e;

        } catch (Exception e) {
            Instant end = Instant.now();
            String msg = e instanceof ToolException te ? te.userFacingMessage()
                                                       : e.getClass().getSimpleName() + ": " + e.getMessage();
            sessions.updateToolState(cmd.sessionId(), call.partId(),
                    new ToolState.Error(call.input(), msg, Map.of("exception", e.getClass().getName()),
                            new TimeRange(start, end)));
            log.warn("tool failed session={} tool={}", cmd.sessionId(), call.toolName(), e);
            return SingleOutcome.error(msg);       // ★ 错误文本回灌模型，让其自我纠正
        }
    }

    /** 结果 → provider 的 role=tool 消息 */
    private record SingleOutcome(Kind kind, ToolResult result, String errorText) {
        enum Kind { SUCCESS, ERROR, DENIED }
        ProviderMessage toProviderMessage(String callId) {
            return switch (kind) {
                case SUCCESS -> ProviderMessage.toolResult(callId, result.content());
                case ERROR, DENIED -> ProviderMessage.toolResult(callId, List.of(TextContent.of(errorText)));
            };
        }
    }
}
```

**输出截断实现**（FR-062）：

```java
@Component
public final class OutputTruncator {
    public static final int MAX_LINES = 2000;
    public static final int MAX_BYTES = 50 * 1024;

    public ToolResult apply(ToolResult r, Path fullOutputPath) {
        String text = r.text();
        if (text == null) return r;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        boolean overBytes = bytes.length > MAX_BYTES;
        long lineCount = text.lines().count();
        boolean overLines = lineCount > MAX_LINES;
        if (!overBytes && !overLines) return r;

        // 全文落盘（若工具尚未落盘）
        if (!Files.exists(fullOutputPath)) writeQuietly(fullOutputPath, text);

        String head = truncate(text, overBytes, overLines);
        String notice = """

        [Output truncated: original %d lines / %d bytes exceeds limit of %d lines / %d KB.]
        Full output saved to: %s
        Use Grep or Read on the saved file to retrieve the parts you need.""".formatted(
                lineCount, bytes.length, MAX_LINES, MAX_BYTES / 1024, fullOutputPath);
        return r.withText(head + notice);
    }

    /** 优先按行截断（保留前 MAX_LINES 行），再按字节兜底 */
    private String truncate(String text, boolean overBytes, boolean overLines) {
        String out = text;
        if (overLines) out = out.lines().limit(MAX_LINES).collect(joining("\n"));
        if (overBytes || out.getBytes(UTF_8).length > MAX_BYTES) {
            byte[] b = out.getBytes(UTF_8);
            int cut = MAX_BYTES;
            while (cut > 0 && (b[cut] & 0xC0) == 0x80) cut--;      // 不切断 UTF-8 多字节序列
            out = new String(b, 0, cut, UTF_8);
        }
        return out;
    }
}
```

---

### 5.3 Provider 层（FR-03）★

#### 5.3.1 SPI

```java
package com.we0j.llm.spi;

public record ModelCard(
        String providerId, String id, String family, String apiKey, String apiBase,
        Set<ModelFeature> features, Integer contextWindowOverride, Integer maxOutputOverride,
        String reasoningEffort, String verbosity, Pricing pricing, Map<String,String> headers) {

    public String qualifiedId() { return providerId + "/" + id; }
    public boolean supports(ModelFeature f) { return features.contains(f); }
    public int contextWindow(ModelInfoTable table) {
        if (contextWindowOverride != null) return contextWindowOverride;
        return table.contextWindow(this).orElse(32_000);          // 保守默认
    }
    public int maxOutput(ModelInfoTable table) {
        if (maxOutputOverride != null) return maxOutputOverride;
        return table.maxOutput(this).orElse(4_096);
    }
}

/** 统一请求 */
public record ChatRequest(
        ModelCard model,
        List<PromptBlock> system,           // 多块，用于 Anthropic 缓存打点
        List<ProviderMessage> messages,
        List<ToolDefinition> tools,
        CacheStrategy cacheStrategy,
        ReasoningConfig reasoning,
        Integer maxOutputTokens,
        Double temperature,
        Map<String, Object> extra) {

    public static Builder builder() { return new Builder(); }
    // Builder 略
}

public enum CacheStrategy { DEFAULT, OFF, LAST_USER_ONLY }
public record ReasoningConfig(boolean enabled, Integer budgetTokens, String effort) {}

/** Provider 消息（中间表示，各 Provider 再转自己的 wire 格式） */
public sealed interface ProviderMessage permits
        ProviderMessage.User, ProviderMessage.Assistant, ProviderMessage.Tool {
    record User(List<ContentBlock> content, Map<String,Object> meta) implements ProviderMessage {}
    record Assistant(List<ContentBlock> content, List<ToolCallRef> toolCalls,
                     String reasoningSignature, Map<String,Object> meta) implements ProviderMessage {}
    record Tool(String toolCallId, List<ContentBlock> content, Map<String,Object> meta) implements ProviderMessage {}

    static User user(List<ContentBlock> c) { return new User(c, Map.of()); }
    static Tool toolResult(String callId, List<ContentBlock> c) { return new Tool(callId, c, Map.of()); }
}
public record ToolCallRef(String id, String name, Map<String,Object> input, String rawArguments) {}

public sealed interface ContentBlock permits
        ContentBlock.Text, ContentBlock.Thinking, ContentBlock.ToolUse, ContentBlock.ToolResult,
        ContentBlock.Image, ContentBlock.ToolReference, ContentBlock.Custom {
    record Text(String text, boolean cacheControl) implements ContentBlock {}
    record Thinking(String thinking, String signature, boolean cacheControl) implements ContentBlock {}
    record ToolUse(String id, String name, Map<String,Object> input) implements ContentBlock {}
    record ToolResult(String toolUseId, List<ContentBlock> content, boolean isError) implements ContentBlock {}
    record Image(String mediaType, String base64, String sourceType) implements ContentBlock {}
    /** Anthropic 原生延迟工具引用 */
    record ToolReference(String toolName) implements ContentBlock {}
    /** OpenAI Responses 的自定义输出块（内嵌已加载 schema） */
    record Custom(String type, Map<String,Object> payload) implements ContentBlock {}

    static Text of(String s) { return new Text(s, false); }
}

/** 拉流抽象：Iterable + AutoCloseable，虚拟线程上阻塞读 */
public interface EventStream extends Iterable<StreamEvent>, AutoCloseable {
    @Override void close();                       // 幂等；取消 HTTP call
    TokenUsage aggregatedUsage();                 // close 后可取
}

public interface ModelProvider {
    String id();                                  // "anthropic" | "openai" | "openai-responses" | "gemini"
    boolean supports(ModelCard card);
    EventStream openStream(ChatRequest request, AbortSignal abort);
}
```

**为什么用 `Iterable` 而非回调**：让 Loop 代码写成线性 `for (StreamEvent ev : stream)`，与 Python 的 `async for event in llm.full_stream(...)` 一一对应，可读性与可调试性（完整调用栈）最佳。虚拟线程让阻塞式 `hasNext()` 零成本。

#### 5.3.2 SSE 解析器

```java
/**
 * SSE 帧解析（W3C EventSource 语义）：
 *  - 以 \n\n / \r\n\r\n 分隔事件
 *  - 行首 "data:" 累积（多个 data 行用 \n 连接）
 *  - "event:" 设置事件名（Anthropic 用它传 message_start 等类型）
 *  - ":" 开头为注释/心跳，忽略
 *  - "data: [DONE]" 为终止哨兵（OpenAI）
 * 阻塞式：hasNext() 会阻塞直到有完整帧或流结束。虚拟线程友好。
 */
public final class SseParser implements AutoCloseable {

    public record SseFrame(String event, String data, String id) {}

    private final BufferedReader reader;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SseParser(ResponseBody body) {
        this.reader = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8), 16 * 1024);
    }

    /** @return null 表示流结束 */
    public SseFrame nextFrame() throws IOException {
        StringBuilder data = null;
        String event = null, id = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {                                  // 事件边界
                if (data != null) return new SseFrame(event, data.toString(), id);
                event = null; id = null;                           // 空事件（连续空行）重置
                continue;
            }
            if (line.startsWith(":")) continue;                    // 注释/心跳
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);  // 规范：去掉一个前导空格
            switch (field) {
                case "data"  -> data = (data == null) ? new StringBuilder(value)
                                                      : data.append('\n').append(value);
                case "event" -> event = value;
                case "id"    -> id = value;
                default      -> { /* retry / 未知字段忽略 */ }
            }
        }
        // 流结束但仍有未闭合帧 → 补发（部分 provider 不发末尾空行）
        if (data != null) return new SseFrame(event, data.toString(), id);
        return null;
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            try { reader.close(); } catch (IOException ignored) { }
        }
    }
}
```

#### 5.3.3 Anthropic Provider 完整实现

```java
@Component
@RequiredArgsConstructor
public final class AnthropicProvider implements ModelProvider {

    private static final String API_VERSION = "2023-06-01";
    private static final List<String> BETA_HEADERS = List.of(
            "interleaved-thinking-2025-05-14",
            "fine-grained-tool-streaming-2025-05-14",
            "prompt-caching-scope-2026-01-05");

    private final OkHttpClientFactory httpFactory;
    private final AnthropicMessageConverter converter;
    private final AnthropicEventMapper mapper;
    private final CacheMarkerApplier cacheMarker;

    @Override public String id() { return "anthropic"; }
    @Override public boolean supports(ModelCard c) { return "anthropic".equals(c.providerId()); }

    @Override
    public EventStream openStream(ChatRequest req, AbortSignal abort) {
        ObjectNode body = buildRequestBody(req);
        Request httpReq = new Request.Builder()
                .url(baseUrl(req.model()) + "/v1/messages")
                .post(RequestBody.create(Jsons.toBytes(body), MediaType.get("application/json")))
                .header("x-api-key", req.model().apiKey())
                .header("anthropic-version", API_VERSION)
                .header("anthropic-beta", String.join(",", BETA_HEADERS))
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .headers(Headers.of(req.model().headers()))
                .build();

        OkHttpClient client = httpFactory.clientFor(req.model());
        Call call = client.newCall(httpReq);
        abort.onCancel(call::cancel);                              // ★ 级联取消

        try {
            Response resp = call.execute();                        // 虚拟线程阻塞
            if (!resp.isSuccessful()) throw AnthropicErrors.parse(resp, body);   // 含溢出识别
            ResponseBody rb = resp.body();
            if (rb == null) throw new ModelException("empty response body");
            return new AnthropicEventStream(rb, call, mapper, abort, resp.headers());
        } catch (IOException e) {
            if (abort.isAborted()) throw new InferenceAbortedException("aborted", e);
            throw new ModelException("anthropic request failed", e, null);
        }
    }

    private ObjectNode buildRequestBody(ChatRequest req) {
        ObjectNode b = Jsons.obj();
        b.put("model", req.model().id());
        b.put("max_tokens", req.maxOutputTokens() != null ? req.maxOutputTokens()
                                                          : req.model().maxOutput(modelInfo));
        b.put("stream", true);
        if (req.temperature() != null) b.put("temperature", req.temperature());

        // system：数组形式，逐块，支持 cache_control
        ArrayNode sys = b.putArray("system");
        for (PromptBlock block : cacheMarker.markSystem(req.system(), req.cacheStrategy())) {
            ObjectNode n = sys.addObject();
            n.put("type", "text");
            n.put("text", block.text());
            if (block.cacheBreakpoint()) n.putObject("cache_control").put("type", "ephemeral");
        }

        // messages
        converter.writeMessages(b.putArray("messages"), req.messages(), req.cacheStrategy(), cacheMarker);

        // tools
        if (!req.tools().isEmpty()) {
            ArrayNode tools = b.putArray("tools");
            for (ToolDefinition t : req.tools()) {
                ObjectNode n = tools.addObject();
                n.put("name", t.name());
                n.put("description", t.description());
                n.set("input_schema", t.inputSchema());
                if (t.deferLoading()) n.put("defer_loading", true);   // ★ 延迟加载标记
            }
        }

        // thinking
        if (req.reasoning() != null && req.reasoning().enabled()) {
            ObjectNode th = b.putObject("thinking");
            th.put("type", "enabled");
            th.put("budget_tokens", req.reasoning().budgetTokens() != null
                                    ? req.reasoning().budgetTokens() : 8000);
            // ★ thinking 开启时 temperature 必须为 1（Anthropic 约束）
            b.put("temperature", 1);
        }
        return b;
    }
}
```

**Anthropic 事件映射器**（FR-032 精确规则）：

```java
@Component
public final class AnthropicEventMapper {

    private final ObjectMapper mapper;
    /** index → block 上下文（类型、tool_call_id、thinking signature 累积） */
    private final Map<Integer, BlockCtx> blocks = new HashMap<>();
    private TokenUsage.Builder usage = TokenUsage.builder();
    private String stopReason;

    private record BlockCtx(String type, String toolCallId, String toolName,
                            StringBuilder sig, StringBuilder partialJson) {}

    /** @return 0..n 个 StreamEvent（一个 SSE 帧可能产出多个事件） */
    public List<StreamEvent> map(SseFrame frame) {
        if (frame.data() == null || frame.data().isBlank()) return List.of();
        JsonNode n;
        try { n = mapper.readTree(frame.data()); }
        catch (IOException e) { return List.of(new StreamEvent.Error(
                new ModelException("malformed anthropic sse data: " + frame.data(), e))); }

        // Anthropic 同时提供顶层 "type" 与 SSE event 名，优先顶层 type
        String type = n.path("type").asText(frame.event());
        List<StreamEvent> out = new ArrayList<>(3);

        switch (type) {
            case "message_start" -> {
                JsonNode u = n.path("message").path("usage");
                usage.promptTokens(intOrNull(u, "input_tokens"))
                     .cacheCreationInputTokens(intOrNull(u, "cache_creation_input_tokens"))
                     .cacheReadInputTokens(intOrNull(u, "cache_read_input_tokens"))
                     .completionTokens(intOrNull(u, "output_tokens"));
                out.add(new StreamEvent.Start());
                out.add(new StreamEvent.StartStep());
            }
            case "content_block_start" -> {
                int index = n.path("index").asInt();
                JsonNode cb = n.path("content_block");
                String cbType = cb.path("type").asText();
                switch (cbType) {
                    case "thinking", "redacted_thinking" -> {
                        blocks.put(index, new BlockCtx("thinking", null, null,
                                new StringBuilder(), new StringBuilder()));
                        out.add(new StreamEvent.ReasoningStart(blockId(index),
                                Map.of("anthropicIndex", index, "thinkingType", cbType)));
                    }
                    case "text" -> {
                        blocks.put(index, new BlockCtx("text", null, null, null, null));
                        out.add(new StreamEvent.TextStart(blockId(index), Map.of("anthropicIndex", index)));
                    }
                    case "tool_use" -> {
                        String toolCallId = cb.path("id").asText();
                        String toolName = cb.path("name").asText();
                        blocks.put(index, new BlockCtx("tool_use", toolCallId, toolName,
                                null, new StringBuilder()));
                        out.add(new StreamEvent.ToolInputStart(blockId(index), toolName, toolCallId,
                                Map.of("anthropicIndex", index)));
                    }
                    default -> { /* server_tool_use 等：忽略但记录 DEBUG */ }
                }
            }
            case "content_block_delta" -> {
                int index = n.path("index").asInt();
                BlockCtx ctx = blocks.get(index);
                JsonNode d = n.path("delta");
                String dType = d.path("type").asText();
                if (ctx == null) return out;                        // 容错：缺 start
                switch (dType) {
                    case "thinking_delta" -> {
                        String t = d.path("thinking").asText("");
                        if (!t.isEmpty()) out.add(new StreamEvent.ReasoningDelta(blockId(index), t, null));
                    }
                    case "text_delta" -> {
                        String t = d.path("text").asText("");
                        if (!t.isEmpty()) out.add(new StreamEvent.TextDelta(blockId(index), t, null));
                    }
                    case "input_json_delta" -> {
                        String pj = d.path("partial_json").asText("");
                        ctx.partialJson().append(pj);
                        if (!pj.isEmpty()) out.add(new StreamEvent.ToolInputDelta(blockId(index), pj, null));
                    }
                    case "signature_delta" -> ctx.sig().append(d.path("signature").asText(""));
                    case "citations_delta" -> { /* MVP 忽略引用 */ }
                    default -> { }
                }
            }
            case "content_block_stop" -> {
                int index = n.path("index").asInt();
                BlockCtx ctx = blocks.remove(index);
                if (ctx == null) return out;
                switch (ctx.type()) {
                    case "thinking" -> out.add(new StreamEvent.ReasoningEnd(blockId(index),
                            Map.of("signature", ctx.sig().toString())));
                    case "text" -> out.add(new StreamEvent.TextEnd(blockId(index), null));
                    case "tool_use" -> {
                        out.add(new StreamEvent.ToolInputEnd(blockId(index), null));
                        Map<String,Object> input = parseArguments(ctx.partialJson().toString(), ctx.toolName());
                        out.add(new StreamEvent.ToolCall(ctx.toolCallId(), ctx.toolName(), input,
                                Map.of("rawArguments", ctx.partialJson().toString())));
                    }
                }
            }
            case "message_delta" -> {
                JsonNode d = n.path("delta");
                if (d.hasNonNull("stop_reason")) stopReason = d.get("stop_reason").asText();
                JsonNode u = n.path("usage");
                if (u.has("output_tokens")) usage.completionTokens(u.get("output_tokens").asInt());
                out.add(new StreamEvent.FinishStep(mapStopReason(stopReason), usage.build(), null));
            }
            case "message_stop" -> out.add(new StreamEvent.Finish(mapStopReason(stopReason), usage.build()));
            case "ping" -> { /* 心跳，忽略 */ }
            case "error" -> {
                JsonNode err = n.path("error");
                String et = err.path("type").asText("api_error");
                String msg = err.path("message").asText("");
                out.add(new StreamEvent.Error(AnthropicErrors.fromStreamError(et, msg, n.toString())));
                        // ★ fromStreamError 内部会做溢出识别 → ContextOverflowException
            }
            default -> { /* unknown event：DEBUG 日志，不中断流 */ }
        }
        return out;
    }

    /** Anthropic stop_reason → 统一 finish_reason */
    private String mapStopReason(String sr) {
        if (sr == null) return "stop";
        return switch (sr) {
            case "end_turn", "stop_sequence" -> "stop";
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            case "refusal" -> "content_filter";
            case "pause_turn", "model_context_window_exceeded" -> sr;
            default -> sr;
        };
    }

    private Map<String,Object> parseArguments(String raw, String toolName) {
        if (raw == null || raw.isBlank()) return Map.of();
        try { return mapper.readValue(raw, new TypeReference<Map<String,Object>>() {}); }
        catch (IOException e) {
            throw new MalformedToolArgumentsException(toolName, raw,
                    "Model produced malformed JSON arguments for tool '" + toolName + "': " + e.getMessage());
        }
    }

    private static String blockId(int index) { return "ab" + index; }    // 稳定的块 id
    public String takeThinkingSignature(int index) { /* 供 TurnProcessor 回传 */ }
}
```

#### 5.3.4 OpenAI Chat Completions Provider（tool_call 分片重组）

```java
@Component
@RequiredArgsConstructor
public final class OpenAiChatProvider implements ModelProvider {

    private final OkHttpClientFactory httpFactory;
    private final OpenAiMessageConverter converter;

    @Override public String id() { return "openai"; }
    @Override public boolean supports(ModelCard c) {
        return "openai".equals(c.providerId()) || c.family().startsWith("openai-compatible");
    }

    @Override
    public EventStream openStream(ChatRequest req, AbortSignal abort) {
        ObjectNode body = buildRequestBody(req);
        Request httpReq = new Request.Builder()
                .url(baseUrl(req.model()) + "/chat/completions")
                .post(RequestBody.create(Jsons.toBytes(body), MediaType.get("application/json")))
                .header("Authorization", "Bearer " + req.model().apiKey())
                .header("accept", "text/event-stream")
                .build();
        Call call = httpFactory.clientFor(req.model()).newCall(httpReq);
        abort.onCancel(call::cancel);
        try {
            Response resp = call.execute();
            if (!resp.isSuccessful()) throw OpenAiErrors.parse(resp, body);
            return new OpenAiEventStream(resp.body(), call, new OpenAiEventMapper(), abort, resp.headers());
        } catch (IOException e) {
            if (abort.isAborted()) throw new InferenceAbortedException("aborted", e);
            throw new ModelException("openai request failed", e, null);
        }
    }

    private ObjectNode buildRequestBody(ChatRequest req) {
        ObjectNode b = Jsons.obj();
        b.put("model", req.model().id());
        b.put("stream", true);
        b.putObject("stream_options").put("include_usage", true);      // ★ 必须，否则拿不到 usage
        if (req.maxOutputTokens() != null) b.put("max_tokens", req.maxOutputTokens());
        if (req.temperature() != null) b.put("temperature", req.temperature());
        if (req.reasoning() != null && req.reasoning().effort() != null)
            b.put("reasoning_effort", req.reasoning().effort());       // ParamDropper 会剔除不支持的

        converter.writeMessages(b.putArray("messages"), req.system(), req.messages(), req.cacheStrategy());

        if (!req.tools().isEmpty()) {
            ArrayNode tools = b.putArray("tools");
            for (ToolDefinition t : req.tools()) {
                if (t.deferLoading()) continue;   // ★ OpenAI Chat 不支持 defer_loading，延迟工具直接不下发
                ObjectNode n = tools.addObject();
                n.put("type", "function");
                ObjectNode fn = n.putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", t.inputSchema());
            }
            b.put("parallel_tool_calls", true);
            b.put("tool_choice", "auto");
        }
        return b;
    }
}

/** OpenAI delta 累积器：按 tool_calls[i].index 累积 id/name/arguments */
final class OpenAiEventMapper {

    private final ObjectMapper mapper = Jsons.mapper();
    /** key = tool_calls[i].index */
    private final Map<Integer, ToolCallBuf> toolBufs = new TreeMap<>();
    private final Map<Integer, String> blockIdByIndex = new HashMap<>();
    private final TokenUsage.Builder usage = TokenUsage.builder();
    private boolean usageEmitted, finishEmitted;
    private int textSeq, reasoningSeq;
    private String textBlockId, reasoningBlockId;
    private boolean textOpen, reasoningOpen;

    private static final class ToolCallBuf {
        String id, name; final StringBuilder args = new StringBuilder(); boolean started;
    }

    public List<StreamEvent> map(SseFrame frame) {
        if ("[DONE]".equals(frame.data().trim())) {
            List<StreamEvent> out = new ArrayList<>();
            if (usageEmitted && !finishEmitted) { /* finish 已在 finish_reason 时发出 */ }
            return out;                                    // 流终止由 EventStream 检测 [DONE] 处理
        }
        JsonNode chunk;
        try { chunk = mapper.readTree(frame.data()); }
        catch (IOException e) { return List.of(new StreamEvent.Error(new ModelException("malformed openai sse", e))); }

        List<StreamEvent> out = new ArrayList<>(4);

        // ── usage（可能在任意 chunk，通常在最后一个 choices 为空的 chunk）
        JsonNode u = chunk.path("usage");
        if (u.isObject() && !u.isEmpty()) {
            usage.promptTokens(intOrNull(u, "prompt_tokens"))
                 .completionTokens(intOrNull(u, "completion_tokens"))
                 .totalTokens(intOrNull(u, "total_tokens"));
            JsonNode pd = u.path("prompt_tokens_details");
            if (pd.isObject()) usage.cacheReadInputTokens(intOrNull(pd, "cached_tokens"));
            JsonNode cd = u.path("completion_tokens_details");
            if (cd.isObject()) usage.reasoningTokens(intOrNull(cd, "reasoning_tokens"));
            usageEmitted = true;
        }

        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return out;    // 纯 usage chunk
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");

        // ── reasoning：delta.reasoning_content（DeepSeek/通义等）或 delta.reasoning
        String reasoning = firstNonNull(textOf(delta, "reasoning_content"), textOf(delta, "reasoning"));
        if (reasoning != null && !reasoning.isEmpty()) {
            if (!reasoningOpen) {
                reasoningOpen = true;
                reasoningBlockId = "rb" + (reasoningSeq++);
                out.add(new StreamEvent.ReasoningStart(reasoningBlockId, null));
            }
            out.add(new StreamEvent.ReasoningDelta(reasoningBlockId, reasoning, null));
        }

        // ── text
        String content = textOf(delta, "content");
        if (content != null && !content.isEmpty()) {
            if (!textOpen) {
                textOpen = true;
                textBlockId = "tb" + (textSeq++);
                out.add(new StreamEvent.TextStart(textBlockId, null));
            }
            out.add(new StreamEvent.TextDelta(textBlockId, content, null));
        }

        // ── tool_calls 分片
        JsonNode tcs = delta.path("tool_calls");
        if (tcs.isArray()) {
            for (JsonNode tc : tcs) {
                int idx = tc.path("index").asInt(0);
                ToolCallBuf buf = toolBufs.computeIfAbsent(idx, k -> new ToolCallBuf());

                if (tc.hasNonNull("id") && buf.id == null) {
                    buf.id = tc.get("id").asText();
                    blockIdByIndex.put(idx, "tcb" + idx);
                }
                JsonNode fn = tc.path("function");
                if (fn.hasNonNull("name") && buf.name == null) buf.name = fn.get("name").asText();
                if (fn.hasNonNull("arguments")) buf.args.append(fn.get("arguments").asText());

                // 首次拿到 id + name → 发 ToolInputStart
                if (!buf.started && buf.id != null && buf.name != null) {
                    buf.started = true;
                    out.add(new StreamEvent.ToolInputStart(blockIdByIndex.get(idx), buf.name, buf.id,
                            Map.of("openaiIndex", idx)));
                }
                if (buf.started && fn.hasNonNull("arguments") && !fn.get("arguments").asText().isEmpty()) {
                    out.add(new StreamEvent.ToolInputDelta(blockIdByIndex.get(idx),
                            fn.get("arguments").asText(), null));
                }
            }
        }

        // ── finish_reason
        String finish = textOf(choice, "finish_reason");
        if (finish != null && !finishEmitted) {
            finishEmitted = true;
            // 先闭合未结束的块
            if (reasoningOpen) { out.add(new StreamEvent.ReasoningEnd(reasoningBlockId, null)); reasoningOpen = false; }
            if (textOpen)      { out.add(new StreamEvent.TextEnd(textBlockId, null));           textOpen = false; }

            // "tool_calls" → 解析全部累积 buffer 为 ToolCall
            if ("tool_calls".equals(finish)) {
                for (var e : toolBufs.entrySet()) {
                    int idx = e.getKey(); ToolCallBuf buf = e.getValue();
                    out.add(new StreamEvent.ToolInputEnd(blockIdByIndex.get(idx), null));
                    Map<String,Object> input = parseArgs(buf.args.toString(), buf.name);
                    out.add(new StreamEvent.ToolCall(buf.id, buf.name, input,
                            Map.of("rawArguments", buf.args.toString(), "openaiIndex", idx)));
                }
            }
            out.add(new StreamEvent.FinishStep(finish, usageEmitted ? usage.build() : null, null));
            out.add(new StreamEvent.Finish(finish, usage.build()));
        }
        return out;
    }

    private Map<String,Object> parseArgs(String raw, String toolName) {
        if (raw == null || raw.isBlank()) return Map.of();
        try { return mapper.readValue(raw, new TypeReference<>() {}); }
        catch (IOException e) { throw new MalformedToolArgumentsException(toolName, raw, e.getMessage()); }
    }
}
```

> **关键坑位（必须在实现时处理）**
> 1. 部分 OpenAI 兼容网关在 `finish_reason` **之后**才发 usage chunk（`choices: []`）→ `Finish` 事件需在 `[DONE]` 时补发（若尚未发出）。实现：`OpenAiEventStream` 在检测到 `[DONE]` 时调用 `mapper.flush()`，若 `!finishEmitted` 则补 `FinishStep + Finish`。
> 2. `tool_calls[i].index` 可能缺失（部分网关）→ 退化用数组下标。
> 3. `id` 与 `name` 可能分片到达（首片只有 id+name，后续只有 arguments），也可能首片就有全部 → 用 `buf.started` 标记只在 id+name 都齐时发 `ToolInputStart`。
> 4. `reasoning_content` 在部分网关是 `reasoning`，还有的是 `delta.reasoning_content` 嵌套 → 三路兜底。
> 5. 空 `delta.content: ""` 不得触发 `TextStart`（否则产生空 text block，Anthropic 回传会 400）。

#### 5.3.5 提示词缓存打点（FR-034）

```java
@Component
public final class CacheMarkerApplier {

    /**
     * DEFAULT 策略：前 2 个 system block + 末 2 条非 system 消息 的最后一个可缓存 block 打标记。
     * LAST_USER_ONLY 策略：system 全部 + 末 1 条 user 打标记（旁路调用复用主缓存前缀）。
     * OFF：不打任何标记。
     * 非 Anthropic Provider：直接返回原对象（OpenAI/Gemini 自动缓存）。
     */
    public List<PromptBlock> markSystem(List<PromptBlock> system, CacheStrategy strategy) {
        if (strategy == CacheStrategy.OFF || system.isEmpty()) return system;
        int markCount = 2;                                        // 前 2 块
        List<PromptBlock> out = new ArrayList<>(system.size());
        for (int i = 0; i < system.size(); i++) {
            PromptBlock b = system.get(i);
            boolean mark = i < Math.min(markCount, system.size()) || b.explicitCacheBreakpoint();
            out.add(mark ? b.withCacheBreakpoint(true) : b);
        }
        return out;
    }

    public List<ProviderMessage> markMessages(List<ProviderMessage> msgs, CacheStrategy strategy) {
        if (strategy == CacheStrategy.OFF) return msgs;
        List<ProviderMessage> out = new ArrayList<>(msgs);
        // 找出需要打标记的消息下标
        List<Integer> targets = new ArrayList<>();
        if (strategy == CacheStrategy.LAST_USER_ONLY) {
            lastIndexOfRole(out, Role.USER).ifPresent(targets::add);
        } else {                                                  // DEFAULT
            List<Integer> nonSystem = IntStream.range(0, out.size())
                    .filter(i -> out.get(i) instanceof ProviderMessage.User
                              || out.get(i) instanceof ProviderMessage.Assistant
                              || out.get(i) instanceof ProviderMessage.Tool)
                    .boxed().toList();
            targets.addAll(nonSystem.subList(Math.max(0, nonSystem.size() - 2), nonSystem.size()));
        }
        // ★ 只在最后一个 target 的最后一个 content block 打标记（Anthropic 最多 4 个 cache breakpoint）
        if (!targets.isEmpty()) {
            int idx = targets.get(targets.size() - 1);
            out.set(idx, withLastBlockCached(out.get(idx)));
        }
        return out;
    }

    private ProviderMessage withLastBlockCached(ProviderMessage m) {
        return switch (m) {
            case ProviderMessage.User u -> new ProviderMessage.User(markLast(u.content()), u.meta());
            case ProviderMessage.Assistant a -> new ProviderMessage.Assistant(markLast(a.content()),
                    a.toolCalls(), a.reasoningSignature(), a.meta());
            case ProviderMessage.Tool t -> new ProviderMessage.Tool(t.toolCallId(), markLast(t.content()), t.meta());
        };
    }
    private List<ContentBlock> markLast(List<ContentBlock> blocks) {
        if (blocks.isEmpty()) return blocks;
        List<ContentBlock> out = new ArrayList<>(blocks);
        int last = out.size() - 1;
        out.set(last, switch (out.get(last)) {
            case ContentBlock.Text t      -> new ContentBlock.Text(t.text(), true);
            case ContentBlock.Thinking t  -> new ContentBlock.Thinking(t.thinking(), t.signature(), true);
            case ContentBlock.ToolResult t-> t;                       // tool_result 不打标记（Anthropic 限制）
            case ContentBlock c           -> c;
        });
        return out;
    }
}
```

> **缓存稳定性铁律**：`system` 块的**内容与顺序在同一会话内必须字节一致**，否则每次请求都是 cache miss。因此：
> - 环境信息块的**时间只精确到小时**（`yyyy-MM-dd HH:00`），避免每分钟击穿
> - 动态信息（reminder）**一律不进 system**，走合成 user TextPart（FR-042）
> - 工具列表变化会击穿缓存 → 延迟工具激活后，**新激活的工具追加到 tools 数组末尾**，不打乱既有顺序

#### 5.3.6 错误分类与重试（FR-036 / FR-037）

```java
@Component
public final class ErrorClassifier {

    /** 13+ 条溢出特征（覆盖各家文案）。命中 → ContextOverflowException */
    private static final List<Pattern> OVERFLOW_PATTERNS = List.of(
        p("prompt is too long"),
        p("prompt too long"),
        p("context_length_exceeded"),
        p("maximum context length"),
        p("context window"),
        p("too many tokens"),
        p("input is too long"),
        p("request too large"),
        p("exceeds the model's maximum"),
        p("model_context_window_exceeded"),
        p("range of input length"),
        p("total number of tokens"),
        p("reduce the length"),
        p("context window full"));
    private static final Set<String> OVERFLOW_MARKERS = Set.of(
        "context_length_exceeded", "model_context_window_exceeded", "invalid_request_error.prompt_too_long");

    public boolean isContextOverflow(String body, String errorCode) {
        if (errorCode != null && OVERFLOW_MARKERS.contains(errorCode)) return true;
        if (body == null) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return OVERFLOW_PATTERNS.stream().anyMatch(pt -> pt.matcher(lower).find());
    }

    /** 特殊错误码 → 用户可操作提示 */
    public Optional<String> actionableHint(String errorCode, int status) {
        return switch (errorCode == null ? "" : errorCode) {
            case "insufficient_quota" -> Optional.of("Provider quota exhausted. Check your billing plan.");
            case "invalid_api_key", "authentication_error" ->
                    Optional.of("Invalid API key. Run `/provider` or set WE0J_<PROVIDER>_API_KEY.");
            case "too_many_requests" -> Optional.of("Rate limited. We0J will retry with backoff.");
            case "invalid_prompt", "invalid_request_error" ->
                    Optional.of("Provider rejected the request. Likely a malformed message sequence.");
            default -> Optional.empty();
        };
    }

    public MessageError toMessageError(Throwable t) {
        if (t instanceof ContextOverflowException co)
            return new MessageError.ContextOverflow(co.getMessage(), co.responseBody());
        if (t instanceof ModelException me)
            return new MessageError.Api(me.getMessage(), me.statusCode(), me.isRetryable(),
                    me.responseHeaders(), me.responseBody(), Map.of());
        if (t instanceof AbortedException) return new MessageError.Aborted("interrupted by user");
        return new MessageError.Unknown(String.valueOf(t.getMessage()), stackTraceOf(t));
    }
}

@Component
@RequiredArgsConstructor
public final class RetryScheduler {

    private static final long BASE_DELAY_MS = 2000;
    private static final long MAX_DELAY_MS  = 30_000;
    private static final int  MAX_ATTEMPTS  = 5;
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);

    public int maxAttempts() { return MAX_ATTEMPTS; }

    public boolean isRetryable(Throwable t) {
        if (t instanceof ContextOverflowException) return false;       // ★ 转压缩，不重试
        if (t instanceof MalformedToolArgumentsException) return false;
        if (t instanceof AbortedException) return false;
        if (t instanceof ModelException me) {
            Integer sc = me.statusCode();
            if (sc != null) return RETRYABLE_STATUS.contains(sc);
            return me.getCause() instanceof IOException;               // 连接层错误可重试
        }
        return t instanceof IOException;
    }

    /**
     * 退避计算。★ header 优先，且 header 值可超过 MAX_DELAY（尊重服务端）。
     */
    public long computeDelay(int attempt, Map<String,String> headers) {
        Optional<Long> fromHeader = parseRetryAfter(headers);
        if (fromHeader.isPresent()) return fromHeader.get();
        long exp = BASE_DELAY_MS * (1L << Math.min(attempt - 1, 10));
        return Math.min(MAX_DELAY_MS, exp);
    }

    private Optional<Long> parseRetryAfter(Map<String,String> h) {
        if (h == null) return Optional.empty();
        // 1) retry-after-ms（毫秒，优先级最高）
        String ms = firstKey(h, "retry-after-ms");
        if (ms != null) {
            try { return Optional.of((long) Double.parseDouble(ms.trim())); }
            catch (NumberFormatException ignored) { }
        }
        // 2) retry-after：秒数 或 HTTP-date
        String ra = firstKey(h, "retry-after");
        if (ra == null) return Optional.empty();
        ra = ra.trim();
        try { return Optional.of((long) (Double.parseDouble(ra) * 1000)); }
        catch (NumberFormatException ignored) { }
        try {
            ZonedDateTime when = ZonedDateTime.parse(ra, DateTimeFormatter.RFC_1123_DATE_TIME);
            long ms = Duration.between(Instant.now(), when.toInstant()).toMillis();
            return Optional.of(Math.max(0, ms));
        } catch (DateTimeParseException e) { return Optional.empty(); }
    }

    /** 响应 abort 的 sleep（FR-036） */
    public void sleep(long delayMs, AbortSignal abort) {
        if (delayMs <= 0) { abort.throwIfAborted(); return; }
        CompletableFuture<Void> sleeper = CompletableFuture.runAsync(() -> {
            try { Thread.sleep(delayMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, VirtualThreadExecutors.IO);
        try {
            CompletableFuture.anyOf(sleeper, abort.asFuture()).get();    // 竞速
        } catch (InterruptedException | ExecutionException e) { /* fallthrough */ }
        abort.throwIfAborted();
    }

    private static String firstKey(Map<String,String> h, String name) {
        for (var e : h.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }
}
```

#### 5.3.7 Token 计数与定价

```java
@Component
public final class TokenCounter {
    private final Encoding o200k = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.O200K_BASE);
    private final Encoding cl100k = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    public int count(String text, ModelCard card) {
        if (text == null || text.isEmpty()) return 0;
        Encoding e = selectEncoding(card);
        try { return e.countTokens(text); }
        catch (Exception ex) { return text.length() / 4; }         // 兜底粗估
    }

    public int countMessages(List<ProviderMessage> msgs, ModelCard card) {
        // 每条消息固定开销（Anthropic ≈ 3 token/msg，OpenAI ≈ 4 token/msg + 2/name）
        int overhead = "anthropic".equals(card.providerId()) ? 3 : 4;
        int total = 0;
        for (ProviderMessage m : msgs) {
            total += overhead;
            for (ContentBlock b : blocksOf(m)) total += countBlock(b, card);
        }
        return total;
    }
    private int countBlock(ContentBlock b, ModelCard card) {
        return switch (b) {
            case ContentBlock.Text t      -> count(t.text(), card);
            case ContentBlock.Thinking t  -> count(t.thinking(), card);
            case ContentBlock.ToolUse t   -> count(t.name(), card)
                                           + count(Jsons.write(t.input()), card) + 8;
            case ContentBlock.ToolResult t-> countMessages(List.of(ProviderMessage.user(t.content())), card);
            case ContentBlock.Image i     -> estimateImageTokens(i);      // (w*h)/750，上限 1600
            case ContentBlock c           -> count(Jsons.write(c), card);
        };
    }
    private Encoding selectEncoding(ModelCard c) {
        return c.id().startsWith("gpt-4o") || c.id().startsWith("o1") || c.id().startsWith("o3")
             ? o200k : cl100k;
    }
}

@Component
public final class CostCalculator {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final int SCALE = 6;

    public BigDecimal cost(Tokens t, ModelCard card) {
        Pricing p = card.pricing();
        if (p == null) return BigDecimal.ZERO;
        boolean over200k = t.total() != null && t.total() > 200_000;
        BigDecimal inputPrice = over200k && p.experimentalOver200KInput() != null
                ? p.experimentalOver200KInput() : p.input();
        return sum(
            price(t.adjustedInput(),      inputPrice),
            price(t.output(),             p.output()),
            price(t.cache().read(),       p.cacheRead()),
            price(t.cache().write(),      p.cacheWrite()));
    }
    private static BigDecimal price(int tokens, BigDecimal perMillion) {
        if (perMillion == null || tokens <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(tokens).multiply(perMillion)
                .divide(MILLION, SCALE, RoundingMode.HALF_UP);
    }
}
```

#### 5.3.8 ModelClient 门面

```java
/** Loop 只看这一个门面：解析模型 → 选 Provider → 应用 ParamDropper → 拉流 */
@Component
@RequiredArgsConstructor
public final class ModelClient {

    private final ProviderRegistry providers;
    private final ModelCardManager cards;
    private final ParamDropper paramDropper;
    private final LlmTracer tracer;

    public EventStream openStream(ChatRequest req, AbortSignal abort) {
        ModelProvider provider = providers.forCard(req.model())
                .orElseThrow(() -> new ModelException("no provider for model " + req.model().qualifiedId()));
        ChatRequest effective = paramDropper.apply(req, provider.id());   // 剔除不支持参数 + WARN
        tracer.beforeRequest(req, effective);
        return provider.openStream(effective, abort);
    }
}
```

---

### 5.4 上下文工程（FR-04）

#### 5.4.1 系统提示词装配

```java
/** 一个提示词块。cacheBreakpoint 决定是否打缓存标记 */
public record PromptBlock(String key, String text, int order,
                          boolean cacheBreakpoint, boolean volatileContent) {}

@Component
@RequiredArgsConstructor
public final class SystemPromptAssembler {

    private final PromptBlockCache cache;
    private final EnvInfoRenderer env;
    private final AgentRegistry agents;
    private final SettingsStore settings;

    /**
     * ★ 块顺序固定 —— 顺序即缓存前缀，任何变动都会击穿缓存。
     */
    public List<PromptBlock> assemble(AssembleCommand cmd) {
        List<PromptBlock> blocks = new ArrayList<>(6);
        int order = 0;

        // 1) 核心人格块（按模型家族选择）
        blocks.add(new PromptBlock("core", cache.getOrCompute("core:" + cmd.card().family(),
                () -> CorePrompts.select(cmd.card())), order++, true, false));

        // 2) 环境信息块（时间只到小时，保证缓存稳定）
        String envKey = "env:" + cmd.workdir() + ":" + cmd.gitBranch() + ":" + currentHour();
        blocks.add(new PromptBlock("env", cache.getOrCompute(envKey, () -> env.render(cmd)),
                order++, false, true));

        // 3) Agent 人格块
        AgentInfo agent = agents.resolve(cmd.agentName());
        if (agent != null && agent.prompt() != null && !agent.prompt().isBlank())
            blocks.add(new PromptBlock("agent", agent.prompt(), order++, false, false));

        // 4) 团队块（MVP 恒空，保留位以稳定顺序）
        blocks.add(new PromptBlock("team", "", order++, false, false));

        // 5) 语言偏好
        String lang = settings.current(cmd.projectRoot()).common().language();
        if (lang != null && !lang.isBlank())
            blocks.add(new PromptBlock("language",
                "Always respond in %s unless the user explicitly requests another language.".formatted(lang),
                order++, false, false));

        // 6) 记忆机制说明（MVP：文件记忆说明，可空）
        blocks.add(new PromptBlock("memory", "", order++, false, false));

        return blocks.stream().filter(b -> !b.text().isBlank()).toList();
    }

    private static String currentHour() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00"));
    }
}

/** 家族 → 核心提示词。匹配规则：modelId 子串（对齐原项目 _select_provider_prompt） */
public final class CorePrompts {
    public static String select(ModelCard card) {
        String id = card.id().toLowerCase(Locale.ROOT);
        if (id.contains("claude")) return load("/prompts/core-anthropic.md");
        if (id.contains("gpt") || id.contains("o1") || id.contains("o3")) return load("/prompts/core-codex.md");
        if (id.contains("gemini")) return load("/prompts/core-gemini.md");
        return load("/prompts/core-beast.md");                 // 通用兜底
    }
}
```

**`EnvInfoRenderer` 输出内容**：
```
<environment>
Working directory: /d/projects/we0j
Is git repository: true
Current branch: main
Platform: windows-amd64 (Windows 11 10.0)
Shell: cmd.exe
Java runtime: 21.0.5+11-LTS
Date: 2026-09-07 14:00
Project root markers: pom.xml, .git
Today's date is Sunday, September 7, 2026.
</environment>
```

#### 5.4.2 ContextContributor SPI（FR-044，架构改进）

```java
/**
 * 上下文贡献者。替代原项目 400 行的 inject_system_reminders() 巨型函数。
 * 每个实现是一个 Spring bean，按 order() 排序执行，产出 0..n 个 Reminder。
 */
public interface ContextContributor {
    /** 唯一标识，用于去重（对应 Part metadata.source） */
    String source();
    /** 排序值，越小越先注入 */
    int order();
    /** 是否持久化到 DB（true = 写入合成 TextPart 并落库；false = 仅本轮内存附加） */
    boolean persistent();
    /** 是否适用于当前上下文（lane / mode / 配置开关） */
    default boolean appliesTo(ContributeContext ctx) { return true; }
    /** 产出内容；返回 null 或空表示本轮不注入 */
    String render(ContributeContext ctx);
}

public record ContributeContext(
        String sessionId, Path projectRoot, RuntimeLane lane, String agentName,
        String permissionMode, LoopMarkers markers, List<MessageWithParts> history,
        List<SkillCard> skills, List<TaskNotification> notifications,
        List<UserInput> queuedInputs, List<String> deferredToolNames,
        List<McpServerInstructions> mcpInstructions, Settings settings) {}

@Component
public final class ReminderInjector {

    private final List<ContextContributor> contributors;      // Spring 自动按 @Order 排序注入
    private final SessionService sessions;

    public ReminderInjector(List<ContextContributor> contributors, SessionService sessions) {
        this.contributors = contributors.stream()
                .sorted(Comparator.comparingInt(ContextContributor::order)).toList();
        this.sessions = sessions;
    }

    /**
     * 执行注入。返回"附加到 modelMessages 的合成 Part"列表。
     * ★ 去重规则：每个 source 只保留最新一条；persistent 类型落库，非 persistent 仅本轮。
     * ★ 注入位置：挂到最后一条 UserMessage 上（不新建消息），保持 user/assistant 交替结构。
     */
    public List<TextPart> inject(ContributeContext ctx, UserMessage lastUser) {
        List<TextPart> out = new ArrayList<>();
        Set<String> alreadyPresent = existingSyntheticSources(ctx.history(), lastUser);

        for (ContextContributor c : contributors) {
            if (!c.appliesTo(ctx)) continue;
            String text = c.render(ctx);
            if (text == null || text.isBlank()) continue;

            // 去重：同 source 已有最新一条则替换（persistent）或跳过（non-persistent 每轮重建）
            if (c.persistent() && alreadyPresent.contains(c.source())) {
                sessions.replaceSyntheticText(ctx.sessionId(), lastUser.id(), c.source(), text);
                continue;
            }
            TextPart part = TextPart.synthetic(Ulids.next(), lastUser.id(), ctx.sessionId(),
                    text, c.source());
            if (c.persistent()) sessions.appendPart(part);
            out.add(part);
        }
        return out;
    }

    /** 取每个 source 的最新合成 TextPart 集合（对应原项目 latest_synthetic_text_for_source） */
    private Set<String> existingSyntheticSources(List<MessageWithParts> history, UserMessage lastUser) {
        Set<String> s = new HashSet<>();
        for (MessageWithParts mwp : history)
            for (Part p : mwp.parts())
                if (p instanceof TextPart t && t.isSyntheticReminder() && t.source() != null)
                    s.add(t.source());
        return s;
    }
}
```

**9 个 Contributor 实现**：

| 类名 | source | order | persistent | 内容 |
|---|---|---|---|---|
| `AgentsMdContributor` | `agents_md` | 10 | ✅ | 项目 `AGENTS.md`（截断至 8KB）包裹为 `<project-instructions>` |
| `MemoryPrefixContributor` | `memory_prefix` | 20 | ✅ | MVP 返回 null（保留位） |
| `DeferredToolsContributor` | `available_deferred_tools` | 30 | ❌ | `<available-deferred-tools>Read, Write, Edit, ...</available-deferred-tools>` + 使用 ToolSearch 的说明 |
| `McpInstructionsContributor` | `mcp_instructions` | 40 | ❌ | 各外部 MCP server 的 instructions，`<system-reminder>` 包裹 |
| `BackgroundNotificationContributor` | `background_notification` | 50 | ❌ | `<task-notification>` 列表（drain 后消费，一次性） |
| `SkillsContributor` | `skills` | 60 | ❌ | `<system-reminder>` + 各 skill 的 `toSystemReminder()` |
| `FollowUpInputContributor` | `followup_wrapper` | 70 | ❌ | `<system-reminder>The user sent another message while you were working: ...</system-reminder>` |
| `PlanModeContributor` | `plan_mode_switch` | 80 | ✅ | plan/build 模式切换说明 |
| `TeamContextContributor` | `teammate_context` | 90 | ❌ | MVP 返回 null（保留位） |

#### 5.4.3 历史 → Provider 消息转换（FR-033）

```java
@Component
@RequiredArgsConstructor
public final class HistoryConverter {

    private final MessageNormalizer normalizer;

    /**
     * 把内存态历史折叠为 Provider 消息序列。
     * ★ 关键约束：
     *   1) user/assistant 必须交替（Anthropic 硬要求）→ 连续同角色需合并
     *   2) 每个 assistant.tool_use 必须有配对的 tool_result（否则 400）
     *   3) 每个 tool_result 必须紧跟在含对应 tool_use 的 assistant 之后（OpenAI 硬要求）
     *   4) displayOnly / ignored 的 Part 不进请求
     *   5) 被微压缩的 ToolState.Completed 用占位符替代 output
     *   6) thinking block 必须原样回传（含 signature）
     */
    public List<ProviderMessage> convert(List<MessageWithParts> history, ModelCard card,
                                         List<TextPart> injectedReminders) {
        List<ProviderMessage> out = new ArrayList<>(history.size() * 2);
        Set<String> emittedToolResults = new HashSet<>();

        for (MessageWithParts mwp : history) {
            switch (mwp.message()) {
                case UserMessage u -> {
                    List<ContentBlock> blocks = new ArrayList<>();
                    for (Part p : mwp.parts()) {
                        switch (p) {
                            case TextPart t -> {
                                if (Boolean.TRUE.equals(t.ignored()) || Boolean.TRUE.equals(t.displayOnly())) break;
                                if (!t.text().isBlank()) blocks.add(ContentBlock.of(t.text()));
                            }
                            case FilePart f -> blocks.add(toImageOrTextBlock(f));
                            case ToolPart t -> {
                                // user 消息上的 tool part = 工具结果（部分 provider 语义）
                                if (t.state() instanceof ToolState.Completed c) {
                                    blocks.add(new ContentBlock.ToolResult(t.callId(),
                                            List.of(ContentBlock.of(compactAwareOutput(c))), false));
                                    emittedToolResults.add(t.callId());
                                } else if (t.state() instanceof ToolState.Error e) {
                                    blocks.add(new ContentBlock.ToolResult(t.callId(),
                                            List.of(ContentBlock.of(e.error())), true));
                                    emittedToolResults.add(t.callId());
                                }
                            }
                            default -> { /* step-start/finish、compaction 等不进请求 */ }
                        }
                    }
                    // 注入本轮 reminder（挂到最后一条 user）
                    if (mwp.message().id().equals(lastUserId(history)) && injectedReminders != null)
                        for (TextPart r : injectedReminders)
                            if (!r.text().isBlank()) blocks.add(ContentBlock.of(r.text()));

                    if (!blocks.isEmpty()) out.add(ProviderMessage.user(blocks));
                }
                case AssistantMessage a -> {
                    List<ContentBlock> blocks = new ArrayList<>();
                    List<ToolCallRef> calls = new ArrayList<>();
                    String thinkingSig = null;
                    for (Part p : mwp.parts()) {
                        switch (p) {
                            case ReasoningPart r -> {
                                if (r.text() == null || r.text().isBlank()) break;   // ★ 空块剔除
                                thinkingSig = r.signature();
                                if ("anthropic".equals(card.providerId()))
                                    blocks.add(new ContentBlock.Thinking(r.text(), r.signature(), false));
                                // 非 Anthropic：thinking 不回传（避免污染）
                            }
                            case TextPart t -> {
                                if (Boolean.TRUE.equals(t.synthetic()) && Boolean.TRUE.equals(t.displayOnly())) break;
                                if (t.text() == null || t.text().isBlank()) break;  // ★ 空块剔除
                                blocks.add(ContentBlock.of(t.text()));
                            }
                            case ToolPart t -> {
                                calls.add(new ToolCallRef(t.callId(), t.toolName(),
                                        t.state().input(), rawArgsOf(t)));
                                if ("anthropic".equals(card.providerId()))
                                    blocks.add(new ContentBlock.ToolUse(t.callId(), t.toolName(), t.state().input()));
                            }
                            default -> { }
                        }
                    }
                    if (!blocks.isEmpty() || !calls.isEmpty())
                        out.add(new ProviderMessage.Assistant(blocks, calls, thinkingSig, Map.of()));
                }
            }
        }

        // ── 配对修复：为孤儿 tool_use 补 tool_result（防 400）
        normalizer.repairOrphanToolCalls(out, emittedToolResults);
        // ── 交替修复：合并连续同角色消息
        normalizer.ensureAlternating(out, card);
        // ── Provider 专属清洗
        return normalizer.providerSpecific(out, card);
    }

    private String compactAwareOutput(ToolState.Completed c) {
        return c.isCompacted()
            ? "[tool output compacted to save context; original preserved on disk]"
            : c.output();
    }
}
```

```java
@Component
public final class MessageNormalizer {

    /** 孤儿 tool_use（无配对 tool_result）→ 补一条 "Tool execution was interrupted." 的 error result */
    public void repairOrphanToolCalls(List<ProviderMessage> msgs, Set<String> emitted) {
        for (int i = 0; i < msgs.size(); i++) {
            if (msgs.get(i) instanceof ProviderMessage.Assistant a) {
                for (ToolCallRef c : a.toolCalls()) {
                    if (emitted.contains(c.id())) continue;
                    // 在紧随其后插入 tool 消息
                    msgs.add(i + 1, ProviderMessage.toolResult(c.id(),
                            List.of(ContentBlock.of("[Tool execution was interrupted before completion.]"))));
                    emitted.add(c.id());
                }
            }
        }
    }

    /** 合并连续同角色（Anthropic 硬要求交替） */
    public void ensureAlternating(List<ProviderMessage> msgs, ModelCard card) {
        if (!"anthropic".equals(card.providerId())) return;
        List<ProviderMessage> out = new ArrayList<>(msgs.size());
        for (ProviderMessage m : msgs) {
            if (!out.isEmpty() && sameRole(out.get(out.size() - 1), m))
                out.set(out.size() - 1, merge(out.get(out.size() - 1), m));
            else out.add(m);
        }
        msgs.clear(); msgs.addAll(out);
    }

    public List<ProviderMessage> providerSpecific(List<ProviderMessage> msgs, ModelCard card) {
        return switch (card.providerId()) {
            case "anthropic" -> anthropicClean(msgs);
            case "openai", "openai-responses" -> openaiClean(msgs);
            default -> msgs;
        };
    }

    /** Anthropic：tool_call_id 只允许 [a-zA-Z0-9_-]，其余替换为 _ */
    private List<ProviderMessage> anthropicClean(List<ProviderMessage> msgs) {
        Pattern invalid = Pattern.compile("[^a-zA-Z0-9_-]");
        // 对每个 ToolCallRef.id 与 ToolResult.toolUseId 做清洗，并保证两侧一致（建立映射表）
        Map<String,String> idMap = new HashMap<>();
        // ... 遍历两轮：第一轮建映射，第二轮替换
        return msgs;
    }

    /** OpenAI：role=tool 必须紧跟含对应 tool_calls 的 assistant；否则重排或补占位 */
    private List<ProviderMessage> openaiClean(List<ProviderMessage> msgs) { /* ... */ return msgs; }
}
```

---

### 5.5 上下文压缩（FR-05）

#### 5.5.1 溢出检测

```java
@Component
@RequiredArgsConstructor
public final class OverflowDetector {

    private final TokenCounter counter;
    private final SettingsStore settings;

    /** FR-051：usage.total >= window - min(buffer, maxOutput) - autoBuffer */
    public boolean isOverflow(TokenUsage usage, ModelCard card) {
        if (usage == null || usage.totalTokens() == null) return false;
        int window = card.contextWindow(modelInfo);
        int buffer = settings.current(card).code().compaction().buffer();      // 默认 8000
        int reserved = Math.min(buffer, card.maxOutput(modelInfo));
        int autoBuffer = autoBufferTokens();                                    // 配置/环境变量覆盖
        return usage.totalTokens() >= window - reserved - autoBuffer;
    }

    /** 请求前：以追踪的 input 基线 + 本次 payload 估算 */
    public boolean isInputOverflow(int estimatedPayloadTokens, ModelCard card) {
        int window = card.contextWindow(modelInfo);
        int reserved = Math.min(settings.code().compaction().buffer(), card.maxOutput(modelInfo));
        return estimatedPayloadTokens >= window - reserved;
    }

    public boolean needsCompactionAfterFinish(TokenUsage usage, ModelCard card) { return isOverflow(usage, card); }
    public boolean shouldCompactBeforeRequest(String sid, List<MessageWithParts> h, ModelCard card) {
        return RuntimeGate.mainAgentOnlyDecision()
            && isInputOverflow(counter.countHistory(h, card), card);
    }
    public boolean shouldCompactAfterToolResults(String sid, ToolBatchOutcome b, ModelCard card) {
        return RuntimeGate.mainAgentOnlyDecision()
            && b.estimatedTotalTokens() != null && isOverflow(b.estimatedTotalTokens(), card);
    }
    private int autoBufferTokens() {
        String v = System.getenv("WE0J_AUTOCOMPACT_BUFFER_OVERRIDE");
        return v == null ? 0 : Integer.parseInt(v);
    }
}
```

#### 5.5.2 尾部保留规划

```java
/**
 * 决定"摘要哪部分、保留哪部分"。
 * ★ 铁律：切分点必须落在完整 API round 边界上，绝不能把 assistant(tool_use)
 *   与它的 tool_result 拆开 —— 否则 Anthropic/OpenAI 直接 400。
 */
@Component
@RequiredArgsConstructor
public final class PreservedTailPlanner {

    private final TokenCounter counter;

    public record Plan(List<MessageWithParts> toSummarize, List<MessageWithParts> preservedTail,
                       List<String> preservedMessageIds, int preservedTokens) {}

    public Plan plan(List<MessageWithParts> history, ModelCard card, Settings settings) {
        int window = card.contextWindow(modelInfo);
        int tailBudget = (int) (window * settings.code().compaction().tailBudgetRatio());   // 默认 0.2

        // 1) 按 API round 分组：一个 round = 一条 user + 其后所有 assistant/tool 消息
        List<List<MessageWithParts>> rounds = groupIntoRounds(history);

        // 2) 从最后一个 round 倒序累加，直到超 tailBudget
        List<MessageWithParts> tail = new ArrayList<>();
        int tokens = 0, cutIndex = rounds.size();
        for (int i = rounds.size() - 1; i >= 0; i--) {
            int roundTokens = counter.countRound(rounds.get(i), card);
            if (tokens + roundTokens > tailBudget && !tail.isEmpty()) { cutIndex = i + 1; break; }
            tokens += roundTokens;
            cutIndex = i;
        }

        // 3) 至少保留最后 1 个 round（即使超预算）——否则模型完全失忆
        if (cutIndex >= rounds.size()) cutIndex = Math.max(0, rounds.size() - 1);

        List<MessageWithParts> preserved = rounds.subList(cutIndex, rounds.size())
                .stream().flatMap(List::stream).toList();
        List<MessageWithParts> summarize = rounds.subList(0, cutIndex)
                .stream().flatMap(List::stream).toList();

        return new Plan(summarize, preserved,
                preserved.stream().map(m -> m.message().id()).toList(), tokens);
    }

    /** round 边界：遇到 UserMessage（非合成 reminder）开启新 round */
    private List<List<MessageWithParts>> groupIntoRounds(List<MessageWithParts> history) {
        List<List<MessageWithParts>> rounds = new ArrayList<>();
        List<MessageWithParts> cur = new ArrayList<>();
        for (MessageWithParts m : history) {
            boolean isNewRoundStart = m.message() instanceof UserMessage u
                    && !isSyntheticOnly(u, m.parts());
            if (isNewRoundStart && !cur.isEmpty()) { rounds.add(cur); cur = new ArrayList<>(); }
            cur.add(m);
        }
        if (!cur.isEmpty()) rounds.add(cur);
        return rounds;
    }
}
```

#### 5.5.3 历史清洗

```java
@Component
public final class HistorySanitizer {

    private static final int OUTPUT_HEAD = 500, OUTPUT_TAIL = 500;

    /**
     * 摘要前清洗，降低摘要请求本身的 token 量。
     *  - 剥离 FilePart（图片/附件）→ 替换为 "[image attachment omitted]"
     *  - 超长 tool output → head 500 + "\n...[truncated N chars]...\n" + tail 500
     *  - reasoning part 全部剔除（思考过程对摘要价值低、体积大）
     *  - 保留：文件路径、命令原文、错误信息原文（这些是摘要不能丢的关键事实）
     */
    public List<ProviderMessage> sanitize(List<MessageWithParts> toSummarize, ModelCard card) {
        List<MessageWithParts> cleaned = toSummarize.stream().map(this::cleanMessage).toList();
        return new HistoryConverter(...).convert(cleaned, card, List.of());
    }

    private MessageWithParts cleanMessage(MessageWithParts m) {
        List<Part> parts = m.parts().stream().flatMap(p -> switch (p) {
            case ReasoningPart r -> Stream.empty();                              // 剔除
            case FilePart f -> Stream.of(TextPart.plain("[attachment omitted: " + f.filename() + "]"));
            case ToolPart t -> Stream.of(shrinkToolOutput(t));
            default -> Stream.of(p);
        }).toList();
        return new MessageWithParts(m.message(), parts);
    }

    private Part shrinkToolOutput(ToolPart t) {
        if (!(t.state() instanceof ToolState.Completed c)) return t;
        String out = c.output();
        if (out == null || out.length() <= OUTPUT_HEAD + OUTPUT_TAIL + 100) return t;
        int omitted = out.length() - OUTPUT_HEAD - OUTPUT_TAIL;
        String shrunk = out.substring(0, OUTPUT_HEAD)
                + "\n...[truncated " + omitted + " chars]...\n"
                + out.substring(out.length() - OUTPUT_TAIL);
        return t.withState(c.withOutput(shrunk));
    }
}
```

#### 5.5.4 压缩服务主体

```java
@Service
@RequiredArgsConstructor
public final class CompactionService {

    private static final Logger log = LoggerFactory.getLogger(CompactionService.class);

    private final PreservedTailPlanner planner;
    private final HistorySanitizer sanitizer;
    private final CompactionPromptBuilder promptBuilder;
    private final RetryPlanner retryPlanner;
    private final PostCompactionRestore restore;
    private final MicroCompactor micro;
    private final ChainGuard guard;
    private final SessionService sessions;
    private final SessionFacade facade;                 // 用于起隐藏子会话
    private final ModelCardManager models;
    private final Bus bus;
    private final SettingsStore settings;

    public enum CompactionTrigger { PRE_REQUEST, PRE_REQUEST_OVERFLOW, POST_FINISH_STEP,
                                    POST_TOOL_RESULTS, REACTIVE_OVERFLOW, MANUAL }
    public enum CompactionAction { NONE, CONTINUE, BREAK }
    public record CompactionOutcome(CompactionAction action, String summary, int beforeTokens, int afterTokens) {}

    /** 调度（异步，不阻塞 Loop）：写 time_compacting 标记，Loop 下一轮 process() */
    public void schedule(String sessionId, CompactionTrigger trigger, AbortSignal abort) {
        if (!guard.tryAcquire(sessionId, trigger)) {
            log.warn("compaction circuit-broken session={} trigger={}", sessionId, trigger);
            sessions.appendSystemNote(sessionId,
                "Automatic compaction has failed %d times consecutively and is now disabled for this session. "
              .formatted(guard.maxFailures()) + "Try `/compact` manually or start a new session.");
            return;
        }
        sessions.markCompacting(sessionId);
    }

    /** 执行（Loop 步骤 6 调用） */
    public CompactionOutcome process(String sessionId, CompactionRequest req, AbortSignal abort) {
        Session session = sessions.get(sessionId);
        Settings s = settings.current(session.directory());
        ModelCard card = models.resolveTier(session, "fast");          // 用 fast 档降本

        try {
            List<MessageWithParts> history = CompactedHistoryFilter.apply(sessions.history(sessionId));

            // 1) 尾部保留规划
            var plan = planner.plan(history, card, s);
            if (plan.toSummarize().isEmpty()) { guard.release(sessionId); return new CompactionOutcome(NONE, null, 0, 0); }

            int beforeTokens = plan.preservedTokens() + estimate(plan.toSummarize(), card);

            // 2) 媒体清洗
            List<ProviderMessage> sanitized = sanitizer.sanitize(plan.toSummarize(), card);

            // 3) 摘要生成（隐藏子会话，工具集为空，仅文本输出）
            String summary = generateSummaryWithRetry(sessionId, sanitized, plan, card, s, abort);

            // 4) 写压缩边界：合成 UserMessage + CompactionPart
            int afterTokens = tokenCounter.count(summary, card) + plan.preservedTokens();
            CompactionSummaryMetadata meta = new CompactionSummaryMetadata(
                    new CompactionPreservedTail(plan.preservedMessageIds()),
                    null,
                    req.discoveredDeferredTools(),            // ★ 恢复延迟工具激活态
                    plan.toSummarize().size(),
                    afterTokens,
                    null);
            String boundaryMessageId = sessions.appendCompactionBoundary(sessionId, summary, meta);

            // 5) 压缩后恢复：注入 plan 文件、最近编辑文件、已调用 skill、task/todo 状态
            restore.apply(sessionId, boundaryMessageId, req, plan);

            sessions.clearCompacting(sessionId);
            guard.release(sessionId);
            bus.publish(new SessionCompacted(sessionId, meta, bus.nextSeq()));
            return new CompactionOutcome(CompactionAction.CONTINUE, summary, beforeTokens, afterTokens);

        } catch (AbortedException e) { sessions.clearCompacting(sessionId); throw e; }
        catch (Exception e) {
            log.error("compaction failed session={}", sessionId, e);
            guard.recordFailure(sessionId, req.trigger());
            sessions.clearCompacting(sessionId);
            return new CompactionOutcome(CompactionAction.BREAK, null, 0, 0);
        }
    }

    /**
     * prompt too long 重试（FR-052 步骤 5）：
     * 按 API round 分组，从最早的组整组丢弃，每次丢弃后重试，最多 3 次。
     */
    private String generateSummaryWithRetry(String sessionId, List<ProviderMessage> sanitized,
                                            var plan, ModelCard card, Settings s, AbortSignal abort) {
        List<ProviderMessage> payload = new ArrayList<>(sanitized);
        for (int attempt = 0; attempt <= RetryPlanner.MAX_HEAD_TRUNCATIONS; attempt++) {
            try {
                return facade.runHiddenSession(HiddenSessionCommand.builder()
                        .parentSessionId(sessionId)
                        .agentName("compaction")                    // 受限人格：无工具
                        .modelRef(tierRef(s, "fast"))
                        .system(promptBuilder.system())
                        .messages(prepend(payload, promptBuilder.userInstruction()))
                        .lane(RuntimeLane.SIDE_LLM)                 // ★ 不得触发父会话压缩
                        .incognito(true)
                        .build(), abort);
            } catch (ContextOverflowException e) {
                payload = retryPlanner.truncateHead(payload, card);   // 丢最早的 round
                if (payload.isEmpty()) throw new IllegalStateException("compaction payload exhausted", e);
                log.info("compaction retry after head truncation, attempt={}, remainingMessages={}",
                        attempt + 1, payload.size());
            }
        }
        throw new IllegalStateException("compaction failed after max head truncations");
    }

    /** 时间维微压缩（FR-054） */
    public void maybeMicrocompact(String sessionId, ModelCard card) { micro.maybeRun(sessionId, card); }

    public CompactionOutcome manualCompact(String sessionId, String userInstruction, AbortSignal abort) {
        // /compact [指令]：把 userInstruction 追加到摘要提示词
        return process(sessionId, CompactionRequest.manual(userInstruction, ...), abort);
    }
}
```

**摘要提示词设计**（`CompactionPromptBuilder`，强制结构化章节，FR-052 步骤 4）：

```
SYSTEM:
You are a conversation summarizer for a coding agent. Your output replaces the earlier
part of the conversation history, so anything you omit is permanently lost.

Produce a summary in EXACTLY these markdown sections:

## 1. Task Objective
What the user asked for. Quote the original request verbatim if short.

## 2. Completed Work
Concrete actions already taken: files read/created/modified (full paths), commands run
(exact command lines) and their outcomes, decisions made and their rationale.

## 3. Current State
Exact current state of the codebase and the task: what works, what is broken, what is
half-finished. Include file paths and symbol names.

## 4. Key Facts & Constraints
Verbatim error messages, API contracts, schema definitions, version numbers, environment
constraints, user preferences and prohibitions stated during the conversation.

## 5. Pending Work
What remains to be done, in priority order.

Rules:
- NEVER summarize away a file path, identifier, error message, or command line — keep them verbatim.
- NEVER invent work that did not happen.
- Be dense and factual. No preamble, no closing remarks.
- Output markdown only.

USER:
<conversation>
{sanitized history}
</conversation>

{optional user instruction: "Focus on ..."}

Summarize now.
```

#### 5.5.5 熔断器

```java
/** FR-053：防止"压缩→溢出→压缩"死循环 */
@Component
public final class ChainGuard {
    private final int maxFailures;
    /** chainKey = sessionId + trigger */
    private final ConcurrentMap<String, ChainState> states = new ConcurrentHashMap<>();

    public boolean tryAcquire(String sessionId, CompactionTrigger trigger) {
        String key = sessionId + ":" + trigger;
        ChainState st = states.computeIfAbsent(key, k -> new ChainState());
        if (st.consecutiveFailures.get() >= maxFailures) return false;
        // 同一 chainKey 去重：已有进行中的压缩则不重复触发
        return st.inProgress.compareAndSet(false, true);
    }
    public void recordFailure(String sessionId, CompactionTrigger trigger) {
        ChainState st = states.get(sessionId + ":" + trigger);
        if (st != null) { st.consecutiveFailures.incrementAndGet(); st.inProgress.set(false); }
    }
    public void release(String sessionId) {
        states.values().forEach(st -> st.inProgress.set(false));
        states.keySet().removeIf(k -> k.startsWith(sessionId + ":"));
        // 成功后清零该会话全部失败计数
    }
    public int maxFailures() { return maxFailures; }
    private static final class ChainState {
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicBoolean inProgress = new AtomicBoolean();
    }
}
```

#### 5.5.6 微压缩

```java
@Component
@RequiredArgsConstructor
public final class MicroCompactor {

    private final SessionService sessions;
    private final SettingsStore settings;
    private final TokenCounter counter;

    /**
     * 空闲间隔 > gapThresholdMinutes 时，把较早的已完成 ToolPart 输出替换为占位符，
     * 保留最近 keepRecentToolResults 条不动。原文必须已落盘（Bash/Read 等工具本身会落盘）。
     */
    public void maybeRun(String sessionId, ModelCard card) {
        Settings s = settings.current(...);
        int gapMinutes = s.code().compaction().gapThresholdMinutes();
        int keep = s.code().compaction().keepRecentToolResults();

        List<MessageWithParts> history = sessions.history(sessionId);
        Instant lastActivity = lastActivityOf(history);
        if (Duration.between(lastActivity, Instant.now()).toMinutes() < gapMinutes) return;

        // 收集全部 completed tool part（时间序）
        List<ToolPart> completed = history.stream()
                .flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof ToolPart t && t.state() instanceof ToolState.Completed)
                .map(p -> (ToolPart) p)
                .toList();
        if (completed.size() <= keep) return;

        List<ToolPart> candidates = completed.subList(0, completed.size() - keep);
        int preTokens = counter.countHistory(history, card);
        List<String> compactedIds = new ArrayList<>();
        int saved = 0;

        for (ToolPart tp : candidates) {
            ToolState.Completed c = (ToolState.Completed) tp.state();
            if (c.isCompacted()) continue;                       // 已裁剪，跳过
            int before = counter.count(c.output(), card);
            String placeholder = "[tool output compacted to save context — original %d chars, saved to %s]"
                    .formatted(c.output().length(), outputStorage.pathOf(sessionId, tp.callId()));
            int after = counter.count(placeholder, card);
            sessions.updateToolState(sessionId, tp.id(),
                    c.withOutput(placeholder)
                     .withTime(new TimeRangeCompacted(c.time().start(), c.time().end(), Instant.now())));
            compactedIds.add(tp.id());
            saved += Math.max(0, before - after);
        }
        if (!compactedIds.isEmpty()) {
            sessions.attachMetadata(sessionId, new MicrocompactBoundaryMetadata(
                    "auto", preTokens, saved, compactedIds, List.of()));
            log.info("microcompact session={} parts={} tokensSaved={}", sessionId, compactedIds.size(), saved);
        }
    }
}
```

#### 5.5.7 历史过滤

```java
/** 只返回最后一个 CompactionPart 之后的消息（含承载它的合成 UserMessage） */
public final class CompactedHistoryFilter {
    public static List<MessageWithParts> apply(List<MessageWithParts> all) {
        int boundary = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            boolean has = all.get(i).parts().stream().anyMatch(p -> p instanceof CompactionPart);
            if (has) { boundary = i; break; }
        }
        return boundary < 0 ? all : all.subList(boundary, all.size());
    }
}
```

---

### 5.6 工具体系（FR-06）

#### 5.6.1 SPI

```java
package com.we0j.tool.spi;

@Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
@Component                                     // ★ 元注解含 @Component，标注即成为 bean
public @interface We0Tool {
    String name();                             // 模型可见名，如 "Read"
    String description() default "";           // 为空则从 classpath:/tool-descriptions/<name>.md 加载
    PermissionName permission();
    boolean deferLoading() default true;       // 默认延迟加载（FR-065）
    ChannelSource[] sources() default { ChannelSource.CLI, ChannelSource.WEB, ChannelSource.SUBAGENT };
    Audience[] audience() default { Audience.ASSISTANT };
    int order() default 100;                   // 同名时优先级（overlay 用）
}

public interface Tool {
    ToolDefinition definition();
    ToolResult execute(ToolInput input, ToolContext ctx);
}

public record ToolDefinition(
        String name, String description, JsonNode inputSchema,
        boolean deferLoading, Set<ChannelSource> sources,
        PermissionName permission, ToolAnnotations annotations, String logicalServer) {

    public ToolDefinition withDeferLoading(boolean v) { /* copy */ }
}
public record ToolAnnotations(Set<Audience> audience, boolean readOnlyHint,
                              boolean destructiveHint, boolean idempotentHint, boolean openWorldHint) {}
public enum Audience { ASSISTANT, USER }
```

```java
/** 类型安全的入参访问。底层是 Map，但对外只暴露强类型访问器（禁止裸 Map 穿越边界） */
public record ToolInput(Map<String,Object> raw) {

    public String requireString(String key) {
        Object v = raw.get(key);
        if (v == null) throw new ToolException("Missing required parameter: " + key);
        if (!(v instanceof String s)) throw new ToolException("Parameter '%s' must be a string".formatted(key));
        if (s.isBlank()) throw new ToolException("Parameter '%s' must not be blank".formatted(key));
        return s;
    }
    public Optional<String> optString(String key) { /* ... */ }
    public int optInt(String key, int def) { /* 支持 Number 与数字字符串 */ }
    public boolean optBool(String key, boolean def) { /* ... */ }
    public Path requirePath(String key, Path workdir) {
        return PathSafety.resolve(requireString(key), workdir);       // 规范化 + 防穿越
    }
    public <T> List<T> requireList(String key, Class<T> elem) { /* ... */ }
    public String requireOneOf(String key, Set<String> allowed) { /* ... */ }
}

public record ToolResult(List<ContentBlock> content, Map<String,Object> structuredContent,
                         List<FilePart> attachments) {

    public static ToolResult text(String s) { return new ToolResult(List.of(ContentBlock.of(s)), Map.of(), List.of()); }
    public static ToolResult of(String assistantText, String userText, Map<String,Object> structured) {
        // audience 分离：ASSISTANT 看状态文本，USER 看 diff（FR-062）
        return new ToolResult(List.of(
                new AnnotatedText(assistantText, Audience.ASSISTANT),
                new AnnotatedText(userText, Audience.USER)), structured, List.of());
    }
    public String text() { return textForAudience(Audience.ASSISTANT); }
    public String textForAudience(Audience a) { /* 拼接该 audience 的块 */ }
    public ToolResult withText(String t) { /* ... */ }
}
```

```java
/** 工具执行上下文。工具通过它与运行时交互（权限、提问、中断、落盘） */
public record ToolContext(
        String sessionId, String messageId, String callId,
        AbortSignal abort, Path workdir, RuntimeLane lane, Settings settings,
        PermissionGate gate, QuestionGate questions, ToolOutputSink output,
        ModelCard model, SessionSnapshot session) {

    /** 便捷：请求权限。阻塞直到用户回复 */
    public void askPermission(PermissionName name, List<String> patterns, String message,
                              Map<String,Object> metadata, List<String> alwaysPatterns) {
        gate.ask(name, patterns, message, metadata, alwaysPatterns);
    }
    public void checkAborted() { abort.throwIfAborted(); }

    public static Builder builder() { return new Builder(); }
}

public interface ToolOutputSink {
    void append(String chunk);          // 流式落盘（Bash 用）
    void write(String full);            // 一次性落盘
    Path fullPath();
}
```

#### 5.6.2 JSON Schema 生成

```java
@Component
public final class ToolSchemaGenerator {

    private final SchemaGenerator generator;

    public ToolSchemaGenerator() {
        SchemaGeneratorConfigBuilder b = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
            .with(Option.PLAIN_DEFINITION_KEYS)
            .with(Option.NONSTATIC_NONFINAL_NONEMPTY_NONNONSPLITABLE_TYPES, false);
        // Jackson 注解驱动（@JsonPropertyDescription / @JsonProperty / @Schema）
        b.forFields().withDescriptionResolver(f -> {
            JsonPropertyDescription d = f.getAnnotationConsideringFieldAndGetter(JsonPropertyDescription.class);
            return d != null ? String.join(" ", d.value()) : null;
        });
        b.forFields().withRequiredCheck(f -> f.getAnnotationConsideringFieldAndGetter(NotNull.class) != null
                                          || !f.getType().isInstanceOf(Optional.class));
        this.generator = new SchemaGenerator(b.build());
    }

    /** 从入参 record 生成 schema。record 组件即属性，@NotNull/@Size/@Min 映射为约束 */
    public JsonNode generate(Class<?> inputRecord) {
        JsonNode schema = generator.generateSchema(inputRecord);
        // 工具 schema 顶层必须是 object，且 additionalProperties=false（部分 provider 硬要求）
        ((ObjectNode) schema).put("additionalProperties", false);
        return schema;
    }
}

// 用法：每个工具声明一个入参 record
public record ReadInput(
        @NotNull @JsonPropertyDescription("Absolute or project-relative path to the file to read.")
        String path,
        @JsonPropertyDescription("Line number to start reading from (1-indexed).")
        Integer offset,
        @Min(1) @Max(5000) @JsonPropertyDescription("Maximum number of lines to read. Default 2000.")
        Integer limit) {}
```

#### 5.6.3 ToolRegistry 与 Resolver

```java
@Component
public final class ToolRegistry {

    private final Map<String, Tool> byName;
    private final Map<String, ToolDefinition> definitions;

    /** Spring 注入所有 @We0Tool bean；同时校验重名 */
    public ToolRegistry(List<Tool> tools, ToolSchemaGenerator schemas, SettingsStore settings) {
        Map<String, Tool> m = new LinkedHashMap<>();
        for (Tool t : tools) {
            We0Tool ann = AnnotationUtils.findAnnotation(t.getClass(), We0Tool.class);
            if (ann == null) throw new IllegalStateException("Tool bean without @We0Tool: " + t.getClass());
            if (m.putIfAbsent(ann.name(), t) != null)
                throw new IllegalStateException("Duplicate tool name: " + ann.name());
        }
        this.byName = Map.copyOf(m);
        this.definitions = byName.entrySet().stream().collect(toUnmodifiableMap(Map.Entry::getKey,
                e -> buildDefinition(e.getValue(), ann(e.getValue()), schemas, settings)));
    }

    public Collection<ToolDefinition> all() { return definitions.values(); }
    public Optional<Tool> find(String name) { return Optional.ofNullable(byName.get(name)); }
    public Optional<ToolDefinition> definition(String name) { return Optional.ofNullable(definitions.get(name)); }
    public Set<String> names() { return byName.keySet(); }
}

@Component
@RequiredArgsConstructor
public final class ToolResolver {

    private final ToolRegistry registry;
    private final ToolActivationManager activation;
    private final SessionOverlayStore overlays;
    private final DeferredToolSearchCodec codec;
    private final McpManager mcp;                       // P2 前返回空

    public record ResolvedTools(List<ToolDefinition> definitions, ToolExecuteFunction execute) {}

    @FunctionalInterface
    public interface ToolExecuteFunction {
        ToolResult execute(String callId, String name, ToolInput input, ToolContext ctx);
    }

    /**
     * 解析本轮请求应下发的工具集（FR-063 + FR-065）。
     * 步骤：
     *  1) 全量 = registry.all() + mcp.mountedTools()
     *  2) 配置过滤：mcpServers.enabled / toolFilters.allowed|rejected
     *  3) 会话 overlay：shadowed 移除、added 追加、canUseTool 拦截器包装
     *  4) 渠道过滤：sources 不含当前 ChannelSource 的移除
     *  5) agent 人格过滤：AgentInfo.tools 非空时取交集
     *  6) 模式过滤：plan 模式只保留 readOnlyHint=true 的工具
     *  7) 延迟加载：若 card 支持 DEFER_LOADING → 未激活的 lazy 工具标 deferLoading=true 并从
     *     definitions 中移除（仅名字进 <available-deferred-tools> reminder）
     */
    public ResolvedTools resolve(ResolveCommand cmd) {
        List<ToolDefinition> all = new ArrayList<>(registry.all());
        all.addAll(mcp.mountedDefinitions(cmd.sessionId()));

        all = applyConfigFilters(all, cmd.settings());
        all = overlays.apply(cmd.sessionId(), all);
        all = filterBySource(all, cmd.source());
        all = filterByAgent(all, cmd.agentInfo());
        all = filterByMode(all, cmd.agentName());            // plan → 只读

        boolean supportsDefer = cmd.card().supports(ModelFeature.DEFER_LOADING);
        Set<String> activated = activation.activated(cmd.sessionId());
        List<ToolDefinition> emitted = new ArrayList<>();
        List<String> deferredNames = new ArrayList<>();

        for (ToolDefinition d : all) {
            boolean lazy = isLazy(d, cmd.settings());
            if (supportsDefer && lazy && !activated.contains(d.name())
                    && !ToolNames.TOOL_SEARCH.equals(d.name())) {
                deferredNames.add(d.name());               // 只告知名字，不下发 schema
            } else {
                emitted.add(supportsDefer && lazy && !activated.contains(d.name())
                        ? d : d.withDeferLoading(false));   // 已激活 → 去掉 defer 标记
            }
        }
        cmd.deferredNamesSink().accept(deferredNames);       // 交给 DeferredToolsContributor 渲染 reminder

        return new ResolvedTools(List.copyOf(emitted), buildExecuteFunction(cmd.sessionId()));
    }

    public Optional<Tool> resolveOne(String sessionId, String name) {
        // 执行期解析：即使工具是 lazy 未激活，只要模型给出了 call，也允许执行
        // （对齐原项目：激活只影响 schema 下发，不影响可执行性；但会记录一次激活）
        Optional<Tool> t = registry.find(name);
        t.ifPresent(x -> activation.recordImplicitActivation(sessionId, name));
        return t.or(() -> mcp.findTool(sessionId, name));
    }

    private ToolExecuteFunction buildExecuteFunction(String sessionId) {
        return (callId, name, input, ctx) -> {
            Tool tool = resolveOne(sessionId, name)
                    .orElseThrow(() -> new ToolException("Unknown tool: " + name));
            return tool.execute(input, ctx);
        };
    }
}
```

#### 5.6.4 延迟工具激活（FR-065）

```java
@Component
@RequiredArgsConstructor
public final class ToolActivationManager {

    private final SessionStateCache cache;
    private final SessionService sessions;

    public Set<String> activated(String sessionId) {
        return cache.runtimeState(sessionId).activatedDeferredTools();
    }

    /** ToolSearch 成功后调用：批量激活 */
    public List<String> activateMany(String sessionId, List<String> toolNames) {
        List<String> newly = new ArrayList<>();
        cache.updateRuntimeState(sessionId, rt -> {
            Set<String> set = new LinkedHashSet<>(rt.activatedDeferredTools());
            for (String n : toolNames) if (set.add(n)) newly.add(n);
            return rt.withActivatedDeferredTools(Set.copyOf(set));
        });
        sessions.persistRuntimeState(sessionId);
        return newly;
    }

    /** resume 时从历史 ToolPart.metadata.toolReferences 重建（在 LoopMarkers 中完成） */
    public void restore(String sessionId, Set<String> discovered) { activateMany(sessionId, List.copyOf(discovered)); }

    public void recordImplicitActivation(String sessionId, String name) { /* 同上，单个 */ }
}

/** ToolSearch 的双传输编码 */
@Component
public final class DeferredToolSearchCodec {

    public enum Transport { ANTHROPIC, OPENAI, NONE }

    public Transport transportForModel(ModelCard card) {
        if (!card.supports(ModelFeature.DEFER_LOADING)) return Transport.NONE;
        if ("anthropic".equals(card.providerId())) return Transport.ANTHROPIC;
        if ("openai".equals(card.providerId()) && card.supports(ModelFeature.TOOL_SEARCH_NATIVE))
            return Transport.OPENAI;                    // 仅 Responses API
        return Transport.NONE;
    }

    /** Anthropic：tool_result 内容为原生 tool_reference 块 */
    public List<ContentBlock> encodeResult(Transport t, List<String> activated, ToolRegistry registry) {
        return switch (t) {
            case ANTHROPIC -> activated.stream()
                    .map(n -> (ContentBlock) new ContentBlock.ToolReference(n)).toList();
            case OPENAI -> {
                // 内嵌完整 schema（Responses API 无原生 tool_reference）
                List<Map<String,Object>> schemas = activated.stream()
                        .map(registry::definition).flatMap(Optional::stream)
                        .map(d -> Map.<String,Object>of("name", d.name(),
                                "description", d.description(), "parameters", d.inputSchema()))
                        .toList();
                yield List.of(new ContentBlock.Custom("we0j_tool_search_output",
                        Map.of("loaded_tools", schemas)));
            }
            case NONE -> activated.stream()
                    .map(n -> (ContentBlock) ContentBlock.of("Tool activated: " + n)).toList();
        };
    }

    /** OpenAI Responses：ToolSearch 以特殊工具类型声明 */
    public Optional<ObjectNode> toolDeclarationOverride(ToolDefinition d, Transport t) {
        if (t == Transport.OPENAI && ToolNames.TOOL_SEARCH.equals(d.name())) {
            ObjectNode n = Jsons.obj();
            n.put("type", "tool_search");
            n.put("execution", "client");
            n.put("name", d.name());
            n.set("parameters", d.inputSchema());
            return Optional.of(n);
        }
        return Optional.empty();
    }
}

@We0Tool(name = ToolNames.TOOL_SEARCH, permission = PermissionName.TOOL_SEARCH, deferLoading = false)
@RequiredArgsConstructor
public final class ToolSearchTool implements Tool {

    private static final int MAX_ACTIVATIONS = 3;
    private final ToolRegistry registry;
    private final ToolActivationManager activation;
    private final DeferredToolSearchCodec codec;
    private final ToolResolver resolver;

    public record ToolSearchInput(
        @NotNull @JsonPropertyDescription("""
            Query to find deferred tools. Supports:
            - "select:Read,Edit" to activate exact tool names
            - keyword search, e.g. "file edit"
            - "+required" tokens that must appear, e.g. "bash +background"
            """)
        String query) {}

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String query = input.requireString("query");
        ModelCard card = ctx.model();
        var transport = codec.transportForModel(card);

        List<String> matched = parseAndSearch(query, ctx.sessionId());
        if (matched.size() > MAX_ACTIVATIONS) {
            matched = matched.subList(0, MAX_ACTIVATIONS);       // ★ 单次最多 3 个
        }
        List<String> newly = activation.activateMany(ctx.sessionId(), matched);

        List<ContentBlock> content = codec.encodeResult(transport, matched, registry);
        String summary = matched.isEmpty()
            ? "No tools matched query '%s'. Available deferred tools: %s"
                .formatted(query, String.join(", ", deferredNames(ctx)))
            : "Activated %d tool(s): %s. Their schemas are now available — call them directly."
                .formatted(matched.size(), String.join(", ", matched));

        return new ToolResult(concat(List.of(ContentBlock.of(summary)), content),
                Map.of("toolReferences", matched, "newlyActivated", newly), List.of());
    }

    /** 打分检索：select: 精确 > +required 必含 > 关键词命中数 > 名称前缀 > 描述命中 */
    private List<String> parseAndSearch(String query, String sessionId) { /* ... */ }
}
```

---

### 5.7 内置工具实现细节（FR-07）

#### 5.7.1 Edit 工具与 9 级替换策略链 ★

```java
@We0Tool(name = ToolNames.EDIT, permission = PermissionName.EDIT,
         audience = { Audience.ASSISTANT, Audience.USER })
@RequiredArgsConstructor
public final class EditTool implements Tool {

    private final FileTimeRegistry fileTime;
    private final ReplacerChain replacers;
    private final DiffRenderer diffs;
    private final PermissionGateFactory gates;
    private final CodeIntelligence lsp;              // P2 前为 NoopCodeIntelligence
    private final PathResolver paths;
    private final AtomicFileWriter writer;

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        Path path = input.requirePath("path", ctx.workdir());
        String oldText = input.optString("oldText").orElse("");
        String newText = input.requireString("newText");
        boolean replaceAll = input.optBool("replaceAll", false);

        // ── 步骤 1：per-path 锁（全流程持有）───────────────────────────────
        return fileTime.withLock(path, () -> {

            // ── 步骤 2：读后写校验（FR-073）────────────────────────────────
            fileTime.assertRead(ctx.sessionId(), path);

            // ── 步骤 3：读当前全文 ─────────────────────────────────────────
            String content = readText(path);

            // ── 步骤 4：oldText 为空 = 仅当文件为空时创建 ───────────────────
            if (oldText.isEmpty()) {
                if (!content.isEmpty())
                    throw new ToolException("File '%s' is not empty; oldText must be provided to edit it.".formatted(path));
                return doWrite(ctx, path, "", newText, /*isNewFile*/ true);
            }

            // ── 步骤 5：9 级策略链 ─────────────────────────────────────────
            ReplaceOutcome outcome = replacers.replace(content, oldText, newText, replaceAll);

            // ── 步骤 6：唯一性校验已在链内完成（MULTI_OCCURRENCE 分支）───────

            // ── 步骤 7：生成 diff（写前预览）───────────────────────────────
            String previewDiff = diffs.unified(path, content, outcome.newContent());

            // ── 步骤 8：权限询问（携带 diff 供 UI 展示）─────────────────────
            ctx.gate().ask(PermissionName.EDIT,
                    patternsFor(path, ctx.workdir()),
                    "Edit %s".formatted(paths.relative(path, ctx.workdir())),
                    Map.of("path", path.toString(), "diff", previewDiff,
                           "strategy", outcome.strategy().name(),
                           "additions", outcome.additions(), "deletions", outcome.deletions()),
                    List.of(paths.relative(path, ctx.workdir())));     // always 时保存的 pattern

            // ── 步骤 9：写文件（原子）──────────────────────────────────────
            return doWrite(ctx, path, content, outcome.newContent(), false);
        });
    }

    private ToolResult doWrite(ToolContext ctx, Path path, String before, String after, boolean isNew) {
        writer.writeAtomic(path, after);
        fileTime.stampRead(ctx.sessionId(), path);

        // ── 步骤 10：重读磁盘实际字节，重新生成 diff（FR-073 AC）─────────────
        String actual = readText(path);
        String realDiff = diffs.unified(path, before, actual);

        // ── 步骤 12：LSP 后验证 ────────────────────────────────────────────
        List<Diagnostic> diags = lsp.touchFile(path, /*waitForDiagnostics*/ true, Duration.ofSeconds(3));
        String diagText = diags.stream()
                .filter(d -> d.severity() == 1)                        // ERROR only
                .limit(20)
                .map(d -> "  %s:%d:%d %s".formatted(path.getFileName(), d.line(), d.character(), d.message()))
                .collect(joining("\n"));

        String assistantText = (isNew ? "Created " : "Updated ") + path
                + " (%d additions, %d deletions)".formatted(countAdd(realDiff), countDel(realDiff))
                + (diagText.isEmpty() ? "" : "\n\nNew diagnostics:\n" + diagText);

        // audience 分离：模型看状态文本，用户看 diff（FR-062）
        return ToolResult.of(assistantText, realDiff,
                Map.of("path", path.toString(), "diff", realDiff, "isNewFile", isNew,
                       "diagnostics", diags.size()));
    }

    /** pattern 生成：项目内用相对路径；项目外追加 external_directory */
    private List<String> patternsFor(Path path, Path workdir) {
        if (PathSafety.isInside(path, workdir)) return List.of(PathSafety.relative(path, workdir));
        return List.of(path.toAbsolutePath().toString());
    }
}
```

```java
/**
 * 9 级替换策略链（FR-073）。逐级降级，命中即停。
 * 每级返回 Optional<ReplaceOutcome>，empty 表示本级不适用。
 */
@Component
public final class ReplacerChain {

    public enum Strategy {
        SIMPLE, LINE_TRIMMED, BLOCK_ANCHOR_STRICT, BLOCK_ANCHOR_LOOSE,
        WHITESPACE_NORMALIZED, INDENTATION_FLEXIBLE, ESCAPE_NORMALIZED,
        TRIMMED_BOUNDARY, CONTEXT_AWARE, MULTI_OCCURRENCE
    }
    public record ReplaceOutcome(String newContent, Strategy strategy,
                                 int startIndex, int endIndex, int occurrences,
                                 int additions, int deletions) {}

    private final List<ReplaceStrategy> chain = List.of(
            new SimpleStrategy(),
            new LineTrimmedStrategy(),
            new BlockAnchorStrategy(0.0),          // 严格：完全相同的规范化块
            new BlockAnchorStrategy(0.3),          // 宽松：Levenshtein 距离比 ≤ 0.3
            new WhitespaceNormalizedStrategy(),
            new IndentationFlexibleStrategy(),
            new EscapeNormalizedStrategy(),
            new TrimmedBoundaryStrategy(),
            new ContextAwareStrategy(),
            new MultiOccurrenceStrategy());

    public ReplaceOutcome replace(String content, String oldText, String newText, boolean replaceAll) {
        // 先统计精确命中次数（唯一性判定的基准）
        int exactOccurrences = countOccurrences(content, oldText);
        if (exactOccurrences > 1 && !replaceAll)
            throw new ToolException(buildAmbiguousError(content, oldText, exactOccurrences));

        for (ReplaceStrategy s : chain) {
            Optional<ReplaceOutcome> r = s.tryReplace(content, oldText, newText, replaceAll);
            if (r.isPresent()) return r.get();
        }
        throw new ToolException("""
            No match found for the requested edit in the file.
            Requested oldText (%d chars) does not match any region, even after applying \
            %d fallback matching strategies (whitespace, indentation, escaping, block-similarity).

            Troubleshooting:
            1. Re-read the file — your copy may be stale or from a different revision.
            2. Copy oldText verbatim from the file, including exact indentation and blank lines.
            3. If the target appears multiple times, include surrounding context lines to make it unique.
            4. If you intend to replace all %d occurrences, set replaceAll=true.

            Requested oldText:
            ---
            %s
            ---""".formatted(oldText.length(), chain.size(), exactOccurrences, preview(oldText, 400)));
    }

    /** 多处命中错误：必须列出全部命中行号（FR-073 AC） */
    private String buildAmbiguousError(String content, String oldText, int n) {
        List<Integer> lines = new ArrayList<>();
        int idx = 0;
        while ((idx = content.indexOf(oldText, idx)) >= 0) {
            lines.add(lineNumberOf(content, idx) + 1);
            idx += oldText.length();
        }
        return """
            Found %d occurrences of oldText at lines %s.
            The edit is ambiguous. Either:
            - include more surrounding context in oldText to make it match exactly one location, or
            - set replaceAll=true to replace all %d occurrences."""
            .formatted(n, lines, n);
    }
}

interface ReplaceStrategy {
    Optional<ReplacerChain.ReplaceOutcome> tryReplace(String content, String oldText,
                                                      String newText, boolean replaceAll);
}

/** 级别 3/4：块锚点 + Levenshtein 相似度 */
final class BlockAnchorStrategy implements ReplaceStrategy {
    private final double maxDistanceRatio;         // 0.0 = 严格；0.3 = 宽松
    private final LevenshteinDistance distance = new LevenshteinDistance();

    BlockAnchorStrategy(double maxDistanceRatio) { this.maxDistanceRatio = maxDistanceRatio; }

    @Override
    public Optional<ReplaceOutcome> tryReplace(String content, String oldText, String newText, boolean all) {
        int oldLines = oldText.split("\n", -1).length;
        String[] contentLines = content.split("\n", -1);
        if (contentLines.length < oldLines) return Optional.empty();

        // 滑动窗口：取与 oldText 行数相同的每个窗口，比较规范化后的相似度
        String normOld = normalize(oldText);
        int bestStart = -1; double bestRatio = Double.MAX_VALUE;
        List<Integer> hits = new ArrayList<>();

        for (int i = 0; i + oldLines <= contentLines.length; i++) {
            String window = String.join("\n", Arrays.copyOfRange(contentLines, i, i + oldLines));
            String normWin = normalize(window);
            if (normWin.equals(normOld)) {
                hits.add(i);
                if (bestStart < 0) { bestStart = i; bestRatio = 0.0; }
                continue;
            }
            if (maxDistanceRatio <= 0.0) continue;
            int maxLen = Math.max(normWin.length(), normOld.length());
            if (maxLen == 0) continue;
            double ratio = (double) distance.apply(normWin, normOld) / maxLen;
            if (ratio <= maxDistanceRatio && ratio < bestRatio) { bestRatio = ratio; bestStart = i; }
        }
        if (bestStart < 0) return Optional.empty();
        if (hits.size() > 1 && !all) return Optional.empty();      // 交给 MULTI_OCCURRENCE 处理

        int charStart = offsetOfLine(contentLines, bestStart);
        int charEnd = charStart + String.join("\n",
                Arrays.copyOfRange(contentLines, bestStart, bestStart + oldLines)).length();
        // ★ 保留原窗口的缩进风格：用 oldText→newText 的"相对变换"应用到实际窗口
        String actualOld = content.substring(charStart, charEnd);
        String applied = applyRelativeTransform(actualOld, oldText, newText);
        String newContent = content.substring(0, charStart) + applied + content.substring(charEnd);
        return Optional.of(new ReplaceOutcome(newContent,
                maxDistanceRatio <= 0.0 ? Strategy.BLOCK_ANCHOR_STRICT : Strategy.BLOCK_ANCHOR_LOOSE,
                charStart, charEnd, 1, countLines("+" , applied), countLines("-", actualOld)));
    }
    /** 规范化：行 trim + 去空行 + 统一换行 */
    private String normalize(String s) { /* ... */ }
}

/** 级别 9：多处命中 */
final class MultiOccurrenceStrategy implements ReplaceStrategy {
    @Override
    public Optional<ReplaceOutcome> tryReplace(String content, String oldText, String newText, boolean all) {
        int n = countOccurrences(content, oldText);
        if (n == 0) return Optional.empty();
        if (n > 1 && !all) return Optional.empty();     // 交由 ReplacerChain 抛歧义错误
        String replaced = all ? content.replace(oldText, newText)
                              : replaceFirst(content, oldText, newText);
        return Optional.of(new ReplaceOutcome(replaced, Strategy.MULTI_OCCURRENCE,
                content.indexOf(oldText), content.indexOf(oldText) + oldText.length(), n,
                countAdditions(oldText, newText), countDeletions(oldText, newText)));
    }
}
```

```java
/** unified diff + dedent 修剪（对齐原项目 trim_diff） */
@Component
public final class DiffRenderer {
    private static final int CONTEXT_LINES = 3;

    public String unified(Path path, String before, String after) {
        List<String> a = Arrays.asList(before.split("\n", -1));
        List<String> b = Arrays.asList(after.split("\n", -1));
        Patch<String> patch = DiffUtils.diff(a, b);
        List<String> lines = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + path.getFileName(), "b/" + path.getFileName(), a, patch, CONTEXT_LINES);
        return trimDedent(String.join("\n", lines));
    }

    /** 去掉公共缩进，让 diff 在窄终端也可读 */
    private String trimDedent(String diff) {
        String[] lines = diff.split("\n");
        int minIndent = Integer.MAX_VALUE;
        for (String l : lines) {
            if (l.startsWith("+++") || l.startsWith("---") || l.startsWith("@@") || l.isBlank()) continue;
            String body = l.substring(1);                          // 去掉 +/-/空格 前缀
            int ind = body.length() - body.stripLeading().length();
            if (!body.isBlank()) minIndent = Math.min(minIndent, ind);
        }
        if (minIndent == Integer.MAX_VALUE || minIndent == 0) return diff;
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (l.startsWith("+++") || l.startsWith("---") || l.startsWith("@@") || l.isBlank()) sb.append(l);
            else sb.append(l.charAt(0)).append(l.substring(1).substring(Math.min(minIndent, l.length() - 1)));
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public int additions(String diff) { return (int) diff.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count(); }
    public int deletions(String diff) { return (int) diff.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).count(); }
}
```

#### 5.7.2 Bash 工具

```java
@We0Tool(name = ToolNames.BASH, permission = PermissionName.BASH)
@RequiredArgsConstructor
public final class BashTool implements Tool {

    private final BashCommandParser parser;
    private final BashArityTable arity;
    private final ShellExecutor executor;
    private final ShellManager shellManager;
    private final PermissionPatternResolver patterns;

    public record BashInput(
        @NotNull @JsonPropertyDescription("The shell command to execute.") String command,
        @JsonPropertyDescription("Short description of what this command does (5-10 words).") String description,
        @Min(1) @JsonPropertyDescription("Timeout in seconds. Default 120.") Integer timeout,
        @JsonPropertyDescription("Working directory. Defaults to the project root.") String cwd,
        @JsonPropertyDescription("Run in background and return a shell id immediately.") Boolean runInBackground) {}

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String command = input.requireString("command");
        int timeoutSec = input.optInt("timeout", 120);
        Path cwd = input.optString("cwd").map(p -> PathSafety.resolve(p, ctx.workdir())).orElse(ctx.workdir());
        boolean background = input.optBool("runInBackground", false);

        // ── 1) 解析命令 → 权限 pattern（FR-074）─────────────────────────────
        List<ParsedCommand> parsed = parser.parse(command);
        List<String> permPatterns = new ArrayList<>();
        List<String> alwaysPatterns = new ArrayList<>();
        boolean touchesExternal = false;

        for (ParsedCommand pc : parsed) {
            permPatterns.add(arity.prefixPattern(pc));          // "git commit" / "npm run build"
            alwaysPatterns.add(arity.prefixPattern(pc));
            if (pc.hasPathArguments()) {
                for (Path p : pc.pathArguments()) {
                    Path real = PathSafety.realpath(p, cwd);
                    if (!PathSafety.isInside(real, ctx.workdir())) {
                        touchesExternal = true;
                        permPatterns.add(PathSafety.directoryTreePattern(real));
                    }
                }
            }
        }

        // ── 2) 权限询问 ─────────────────────────────────────────────────────
        PermissionName name = touchesExternal ? PermissionName.EXTERNAL_DIRECTORY : PermissionName.BASH;
        ctx.gate().ask(name, permPatterns, "Run: " + summarize(command),
                Map.of("command", command, "cwd", cwd.toString(), "timeout", timeoutSec,
                       "description", input.optString("description").orElse(""),
                       "subcommands", parsed.stream().map(ParsedCommand::program).toList()),
                alwaysPatterns);
        ctx.checkAborted();

        // ── 3a) 后台模式 ────────────────────────────────────────────────────
        if (background) {
            BackgroundShell shell = shellManager.create(BackgroundShellCommand.builder()
                    .id("bash_" + System.currentTimeMillis() + "_" + shortUuid())
                    .sessionId(ctx.sessionId()).command(command).cwd(cwd)
                    .outputFile(ctx.output().shellPath())
                    .build());
            return ToolResult.text("""
                Started background shell.
                shell_id: %s
                output_file: %s
                Use TaskOutput(task_id="%s") to read output, TaskStop(task_id="%s") to terminate. \
                You will be notified automatically when it completes."""
                .formatted(shell.id(), shell.outputFile(), shell.id(), shell.id()));
        }

        // ── 3b) 前台模式 ────────────────────────────────────────────────────
        ShellOutcome out = executor.run(ShellCommand.builder()
                .command(command).cwd(cwd)
                .timeout(Duration.ofSeconds(timeoutSec))
                .abort(ctx.abort())
                .sink(ctx.output())                    // 流式落盘
                .env(mergeEnv(ctx))
                .build());

        return ToolResult.text(ShellResultBuilder.build(out));
    }

    private Map<String,String> mergeEnv(ToolContext ctx) {
        Map<String,String> env = new HashMap<>(System.getenv());
        env.putAll(hooks.shellEnv(ctx.sessionId()));               // shell.env hook（P2 前为空）
        env.put("WE0J_SESSION_ID", ctx.sessionId());
        env.put("WE0J_CALL_ID", ctx.callId());
        env.put("NO_COLOR", "1");                                  // 子进程不输出 ANSI，便于解析
        return env;
    }
}
```

```java
@Component
public final class ShellExecutor {

    private static final int CHUNK = 8192;

    public ShellOutcome run(ShellCommand cmd) {
        ProcessBuilder pb = new ProcessBuilder(shellArgs(cmd.command()))
                .directory(cmd.cwd().toFile())
                .redirectErrorStream(true);                        // 合并 stdout/stderr
        pb.environment().clear();
        pb.environment().putAll(cmd.env());

        Process process;
        try { process = pb.start(); }
        catch (IOException e) { throw new ToolException("failed to start shell: " + e.getMessage(), e); }

        // ★ 注册中断清理：杀进程树（FR-024）
        cmd.abort().onCancel(() -> ProcessTreeKiller.kill(process));

        StringBuilder buf = new StringBuilder();
        AtomicLong bytes = new AtomicLong();
        boolean truncatedInMemory = false;

        // 虚拟线程阻塞读，同时流式落盘
        try (InputStream is = process.getInputStream();
             OutputStream sink = Files.newOutputStream(cmd.sink().fullPath(),
                     CREATE, WRITE, TRUNCATE_EXISTING)) {
            byte[] chunk = new byte[CHUNK];
            int n;
            while ((n = is.read(chunk)) > 0) {
                cmd.abort().throwIfAborted();
                sink.write(chunk, 0, n);                           // 落盘（全量，不截断）
                sink.flush();
                bytes.addAndGet(n);
                if (buf.length() < OutputTruncator.MAX_BYTES) {
                    buf.append(new String(chunk, 0, n, StandardCharsets.UTF_8));   // 内存态受限
                } else truncatedInMemory = true;
            }
        } catch (IOException e) {
            if (cmd.abort().isAborted()) throw new InferenceAbortedException("aborted", e);
            throw new ToolException("shell io error: " + e.getMessage(), e);
        }

        // ★ 三方竞速：waitFor(timeout) vs abort
        boolean finished;
        try {
            finished = process.waitFor(cmd.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProcessTreeKiller.kill(process);
            throw new InferenceAbortedException("interrupted", e);
        }
        if (!finished) {
            ProcessTreeKiller.kill(process);
            return ShellOutcome.timeout(buf.toString(), bytes.get(), cmd.timeout(), cmd.sink().fullPath());
        }
        if (cmd.abort().isAborted()) {
            return ShellOutcome.aborted(buf.toString(), bytes.get(), process.exitValue(), cmd.sink().fullPath());
        }
        return ShellOutcome.completed(buf.toString(), bytes.get(), process.exitValue(),
                truncatedInMemory, cmd.sink().fullPath());
    }

    private List<String> shellArgs(String command) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String comspec = System.getenv().getOrDefault("ComSpec", "cmd.exe");
            return List.of(comspec, "/c", command);
        }
        String shell = System.getenv().getOrDefault("SHELL", "/bin/bash");
        return List.of(shell, "-c", command);
    }
}

/** JDK 原生进程树杀死（替代 psutil） */
public final class ProcessTreeKiller {
    public static void kill(Process p) {
        try {
            // ★ 先子后父，避免父进程重生子进程
            List<ProcessHandle> descendants = p.descendants().toList();
            for (ProcessHandle h : descendants) h.destroy();                 // SIGTERM
            p.destroy();
            // 2s 宽限
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                for (ProcessHandle h : descendants) h.destroyForcibly();     // SIGKILL
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        } catch (Exception ignored) { p.destroyForcibly(); }
    }
}
```

```java
/**
 * Bash 命令解析器（替代 tree-sitter-bash）。
 * 只需三件事：① 切分子命令 ② 提取程序名 + 前 N 个参数 ③ 提取路径参数。
 * 实现：字符级状态机，处理单引号/双引号/反斜杠转义/命令替换。
 */
@Component
public final class BashCommandParser {

    public record ParsedCommand(String program, List<String> args, List<Path> pathArguments,
                                String raw, boolean hasSubshell, boolean hasRedirect) {
        public boolean hasPathArguments() { return !pathArguments.isEmpty(); }
    }

    /** 切分符：&& || ; | & 与换行。不切分引号内与 $() 内的内容 */
    public List<ParsedCommand> parse(String command) {
        List<String> segments = splitSegments(command);
        return segments.stream().map(this::parseSegment).filter(Objects::nonNull).toList();
    }

    private List<String> splitSegments(String cmd) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;                       // $() 与 () 深度
        char quote = 0;                      // 当前引号字符，0 = 不在引号内
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && i + 1 < cmd.length()) cur.append(cmd.charAt(++i));
                else if (c == quote) quote = 0;
                continue;
            }
            switch (c) {
                case '\'', '"', '`' -> { quote = c; cur.append(c); }
                case '\\' -> { cur.append(c); if (i + 1 < cmd.length()) cur.append(cmd.charAt(++i)); }
                case '(' -> { depth++; cur.append(c); }
                case ')' -> { depth = Math.max(0, depth - 1); cur.append(c); }
                case ';', '\n' -> { if (depth == 0) { flush(out, cur); } else cur.append(c); }
                case '&' -> {
                    if (depth == 0 && nextIs(cmd, i, '&')) { i++; flush(out, cur); }
                    else if (depth == 0) flush(out, cur);          // 后台符
                    else cur.append(c);
                }
                case '|' -> {
                    if (depth == 0 && nextIs(cmd, i, '|')) { i++; flush(out, cur); }
                    else if (depth == 0) flush(out, cur);          // 管道：两侧都算子命令
                    else cur.append(c);
                }
                default -> cur.append(c);
            }
        }
        flush(out, cur);
        return out;
    }

    private ParsedCommand parseSegment(String seg) {
        String s = stripRedirectsAndEnv(seg).strip();
        if (s.isEmpty()) return null;
        List<String> tokens = tokenize(s);                   // 尊重引号的词法切分
        if (tokens.isEmpty()) return null;

        // 跳过 env 前缀（FOO=bar cmd）与 sudo / nohup / time
        int i = 0;
        while (i < tokens.size() && (tokens.get(i).contains("=") && !tokens.get(i).startsWith("-"))) i++;
        while (i < tokens.size() && WRAPPERS.contains(tokens.get(i))) i++;
        if (i >= tokens.size()) return null;

        String program = baseProgram(tokens.get(i));
        List<String> args = tokens.subList(i + 1, tokens.size());
        List<Path> paths = PATH_AWARE_PROGRAMS.contains(program) ? extractPaths(args) : List.of();
        return new ParsedCommand(program, args, paths, s, seg.contains("$("), seg.matches(".*[<>].*"));
    }

    private static final Set<String> WRAPPERS = Set.of("sudo", "nohup", "time", "nice", "env", "command", "exec");
    private static final Set<String> PATH_AWARE_PROGRAMS =
            Set.of("cd", "rm", "cp", "mv", "mkdir", "touch", "chmod", "chown", "cat", "ls", "rmdir", "ln");

    private List<Path> extractPaths(List<String> args) {
        return args.stream()
                .filter(a -> !a.startsWith("-"))
                .map(Path::of)
                .filter(Files::exists)                     // 只对存在的路径做 realpath（避免误判）
                .toList();
    }
}

/** ARITY 表：命令 → 生成权限 pattern 时保留的参数个数（最长匹配优先） */
@Component
public final class BashArityTable {

    /** 键为空格分隔的命令前缀，值为 arity（保留几个参数） */
    private static final Map<String, Integer> ARITY = new LinkedHashMap<>();
    static {
        ARITY.put("git", 1);                    // git commit / git push
        ARITY.put("npm", 2);                    // npm run build
        ARITY.put("pnpm", 2); ARITY.put("yarn", 2); ARITY.put("bun", 2);
        ARITY.put("mvn", 1); ARITY.put("gradle", 1); ARITY.put("./gradlew", 1); ARITY.put("mvnw", 1);
        ARITY.put("python", 1); ARITY.put("python3", 1); ARITY.put("uv", 2); ARITY.put("pip", 1);
        ARITY.put("docker", 2); ARITY.put("kubectl", 2);
        ARITY.put("rm", 0); ARITY.put("mv", 0); ARITY.put("cp", 0);
        ARITY.put("curl", 0); ARITY.put("wget", 0);
        ARITY.put("make", 1); ARITY.put("cargo", 2); ARITY.put("go", 2);
        ARITY.put("cd", 0); ARITY.put("ls", 0); ARITY.put("cat", 0);
    }

    /** 最长前缀匹配：先试 "npm run"（arity 2），再试 "npm"（arity 2），最后 program 本身 */
    public String prefixPattern(ParsedCommand pc) {
        List<String> parts = new ArrayList<>();
        parts.add(pc.program());
        parts.addAll(pc.args().stream().filter(a -> !a.startsWith("-")).limit(3).toList());

        for (int len = Math.min(parts.size(), 4); len >= 1; len--) {
            String key = String.join(" ", parts.subList(0, len));
            if (ARITY.containsKey(key)) {
                int arity = ARITY.get(key);
                List<String> kept = parts.subList(0, Math.min(parts.size(), 1 + arity));
                return String.join(" ", kept) + (parts.size() > kept.size() ? " *" : "");
            }
        }
        return pc.program() + " *";               // 未知命令 → 最宽松 pattern（仍走 ASK）
    }
}
```

#### 5.7.3 Grep / Glob

```java
@Component
@RequiredArgsConstructor
public final class RipgrepClient {

    private static final int MATCH_LIMIT = 100;
    private static final int LINE_TRUNCATE = 2000;
    private final PathResolver paths;

    public Optional<Path> locate() {
        // 1) 随包分发的二进制（resources/bin/rg-<os>-<arch>，解压到 ~/.we0j/bin/ 并 chmod +x）
        // 2) 系统 PATH
        return Optional.ofNullable(paths.bundledRipgrep()).filter(Files::isExecutable)
                .or(() -> Optional.ofNullable(whichOnPath("rg")));
    }

    public GrepResult grep(GrepParams p) {
        Path rg = locate().orElse(null);
        if (rg == null) return NioSearchFallback.grep(p);            // ★ 降级

        List<String> cmd = new ArrayList<>(List.of(
                rg.toString(), "-nH", "--hidden", "--follow", "--no-messages",
                "--field-match-separator", "|", "--regexp", p.pattern()));
        if (!p.regex()) cmd.addAll(List.of("--fixed-strings"));
        if (p.ignoreCase()) cmd.add("--smart-case");
        if (p.include() != null) cmd.addAll(List.of("--glob", p.include()));
        if (p.maxResults() > 0) cmd.addAll(List.of("--max-count", String.valueOf(p.maxResults())));
        cmd.add(p.path().toString());

        ProcessResult r = ProcessRunner.run(cmd, p.workdir(), Duration.ofSeconds(30));
        return switch (r.exitCode()) {
            case 0 -> parseMatches(r.stdout(), p);
            case 1 -> GrepResult.noMatches();                        // ★ 1 = 无匹配（正常）
            default -> r.stdout().isBlank()
                    ? GrepResult.error("ripgrep failed with exit code " + r.exitCode() + ": " + r.stderr())
                    : parseMatches(r.stdout(), p);                   // ≥2 但有输出 → 容忍
        };
    }

    /** 解析 "path|line|text"，按文件 mtime 倒序分组，限 100 匹配 */
    private GrepResult parseMatches(String stdout, GrepParams p) {
        Map<Path, List<Match>> byFile = new LinkedHashMap<>();
        int count = 0;
        for (String line : stdout.split("\n")) {
            if (count >= MATCH_LIMIT) break;
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) continue;
            Path file = Path.of(parts[0]);
            int lineNo;
            try { lineNo = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { continue; }
            String text = parts[2].length() > LINE_TRUNCATE ? parts[2].substring(0, LINE_TRUNCATE) + "…" : parts[2];
            byFile.computeIfAbsent(file, k -> new ArrayList<>()).add(new Match(lineNo, text));
            count++;
        }
        // 按 mtime 倒序（最近改动的文件优先，最可能是目标）
        List<FileGroup> groups = byFile.entrySet().stream()
                .sorted(comparingLong((Map.Entry<Path,List<Match>> e) -> mtimeOrZero(e.getKey())).reversed())
                .map(e -> new FileGroup(e.getKey(), e.getValue()))
                .toList();
        return new GrepResult(groups, count, count >= MATCH_LIMIT);
    }

    /** 格式化输出：按文件分组 */
    public String format(GrepResult r, Path workdir) {
        if (r.groups().isEmpty()) return "No files found";
        StringBuilder sb = new StringBuilder();
        for (FileGroup g : r.groups()) {
            sb.append(PathSafety.relative(g.path(), workdir)).append(":\n");
            for (Match m : g.matches())
                sb.append("  Line ").append(m.line()).append(": ").append(m.text()).append('\n');
            sb.append('\n');
        }
        if (r.limited()) sb.append("[Results limited to %d matches. Refine your pattern or path.]\n").formatted(MATCH_LIMIT);
        return sb.toString();
    }
}
```

```java
/** Glob：绝对 glob 规范化为 (pattern, baseDir) 对 */
@Component
public final class GlobResolver {

    public record NormalizedGlob(String pattern, Path baseDir) {}

    /**
     * 例：/d/proj/src/**\/*.java → baseDir=/d/proj/src, pattern=**\/*.java
     *     **\/*.ts               → baseDir=workdir,   pattern=**\/*.ts
     * 规则：从左侧找到第一个含通配符的路径段，其之前为 baseDir。
     */
    public NormalizedGlob normalize(String rawGlob, Path workdir) {
        Path abs = rawGlob.startsWith("/") || rawGlob.matches("^[A-Za-z]:.*")
                ? Path.of(rawGlob) : workdir.resolve(rawGlob);
        Path base = workdir;
        StringBuilder pattern = new StringBuilder();
        boolean seenWildcard = false;
        for (Path seg : abs.getRoot() == null ? abs : abs.relativize(abs.getRoot())) { /* noop */ }
        List<String> segs = new ArrayList<>();
        for (Path seg : abs) segs.add(seg.toString());
        List<String> baseSegs = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            String s = segs.get(i);
            if (!seenWildcard && !containsWildcard(s)) baseSegs.add(s);
            else { seenWildcard = true; if (pattern.length() > 0) pattern.append('/'); pattern.append(s); }
        }
        if (!baseSegs.isEmpty()) {
            String first = baseSegs.get(0);
            Path b = first.matches("^[A-Za-z]:$") || first.startsWith("/")
                    ? Path.of(first, *baseSegs.subList(1, baseSegs.size()).toArray(String[]::new))
                    : Path.of("/", String.join("/", baseSegs));
            base = b;
        }
        return new NormalizedGlob(pattern.length() == 0 ? "**" : pattern.toString(), base);
    }
    private static boolean containsWildcard(String s) { return s.contains("*") || s.contains("?") || s.contains("["); }
}
```

#### 5.7.4 Read 工具

```java
@We0Tool(name = ToolNames.READ, permission = PermissionName.READ)
@RequiredArgsConstructor
public final class ReadTool implements Tool {

    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_BYTES = 50 * 1024;
    private static final int MAX_LINE_CHARS = 2000;
    private static final Set<String> IMAGE_EXT = Set.of("jpg","jpeg","png","gif","webp","bmp");
    private static final int MAX_IMAGE_EDGE = 1568;

    private final FileTimeRegistry fileTime;
    private final CodeIntelligence lsp;

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        Path path = input.requirePath("path", ctx.workdir());

        // 项目外路径需 external_directory 授权（FR-082 项目内短路）
        if (!PathSafety.isInside(path, ctx.workdir()))
            ctx.gate().ask(PermissionName.EXTERNAL_DIRECTORY, List.of(path.toString()),
                    "Read file outside project: " + path, Map.of("path", path.toString()),
                    List.of(PathSafety.directoryTreePattern(path)));

        if (Files.isDirectory(path))
            throw new ToolException("'%s' is a directory, not a file. Use Glob to list files.".formatted(path));
        if (!Files.exists(path))
            throw new ToolException("File does not exist: %s. Use Glob to find files by name pattern.".formatted(path));

        String ext = extension(path);

        // ── 图片：返回 ImageContent（base64），超大先降采样 ──────────────────
        if (IMAGE_EXT.contains(ext)) {
            byte[] bytes = downsampleIfLarge(Files.readAllBytes(path), MAX_IMAGE_EDGE);
            fileTime.stampRead(ctx.sessionId(), path);
            return new ToolResult(List.of(new ContentBlock.Image("image/" + mimeOf(ext),
                    Base64.getEncoder().encodeToString(bytes), "base64")),
                    Map.of("path", path.toString(), "kind", "image", "bytes", bytes.length), List.of());
        }

        // ── PDF：pdfbox 提取文本 ────────────────────────────────────────────
        if ("pdf".equals(ext)) {
            String text = PdfTextExtractor.extract(path);
            fileTime.stampRead(ctx.sessionId(), path);
            return ToolResult.text(paginate(text, input, path));
        }

        // ── 文本：offset/limit + 行号 + 双阈值截断 ──────────────────────────
        int offset = Math.max(1, input.optInt("offset", 1));
        int limit = Math.min(input.optInt("limit", DEFAULT_LIMIT), 5000);

        List<String> lines;
        try (Stream<String> s = Files.lines(path, StandardCharsets.UTF_8)) {
            lines = s.skip(offset - 1L).limit(limit + 1L).toList();      // 多取 1 行判断是否还有后续
        } catch (MalformedInputException e) {
            throw new ToolException("'%s' appears to be a binary file and cannot be read as text.".formatted(path));
        } catch (IOException e) {
            throw new ToolException("Failed to read '%s': %s".formatted(path, e.getMessage()), e);
        }

        boolean moreAvailable = lines.size() > limit;
        if (moreAvailable) lines = lines.subList(0, limit);

        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        int shown = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).length() > MAX_LINE_CHARS
                    ? lines.get(i).substring(0, MAX_LINE_CHARS) + "…[truncated]" : lines.get(i);
            String rendered = "%6d\t%s%n".formatted(offset + i, line);
            bytes += rendered.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BYTES) {
                sb.append("\n[Truncated at 50KB. Use offset=%d to continue reading.]"
                        .formatted(offset + shown));
                break;
            }
            sb.append(rendered);
            shown++;
        }

        // ★ 副作用：登记读时间戳（编辑安全链路锚点）+ 预热 LSP
        fileTime.stampRead(ctx.sessionId(), path);
        lsp.warmUp(path);                                                // waitForDiagnostics=false

        long totalLines = countLines(path);
        String header = moreAvailable || offset > 1
                ? "[Showing lines %d-%d of %d in %s]\n".formatted(offset, offset + shown - 1, totalLines, path)
                : "";
        return new ToolResult(List.of(ContentBlock.of(header + sb)),
                Map.of("path", path.toString(), "offset", offset, "linesShown", shown,
                       "totalLines", totalLines, "truncated", moreAvailable), List.of());
    }
}
```

---

### 5.8 权限系统（FR-08）★

```java
@Service
@RequiredArgsConstructor
public final class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final RulesetMerger merger;
    private final WildcardMatcher wildcards;
    private final SessionRegistry registry;
    private final SessionService sessions;
    private final Bus bus;
    private final PermissionScopeResolver scope;
    private final DoomLoopDetector doomLoop;

    /**
     * 求值：last-match-wins（FR-081）。
     * 遍历合并后的 ruleset，记录最后一条命中的规则的 action。
     */
    public Action evaluate(PermissionName name, String pattern, RulesetContext ctx) {
        // 会话级模式短路
        PermissionMode mode = ctx.permissionMode();
        if (mode == PermissionMode.BYPASS) return Action.ALLOW;
        if (mode == PermissionMode.REJECT) return Action.DENY;

        Action result = null;
        for (PermissionRule rule : merger.merge(ctx)) {          // 已按 user→project→agent→runtime 排序
            if (rule.permission() != PermissionName.ALL && rule.permission() != name) continue;
            if (!wildcards.match(rule.pattern(), pattern)) continue;
            result = rule.action();                              // ★ 不 break：继续找后面的规则
        }
        if (result == null) result = Action.ASK;                 // 默认询问（安全侧）
        if (mode == PermissionMode.ALLOW_ONCE && result == Action.ASK) return Action.ALLOW;
        return result;
    }

    /**
     * 请求权限。阻塞直到用户回复或 abort（FR-084）。
     * ★ 运行在工具的虚拟线程上，阻塞零平台线程成本。
     */
    public ReplyDecision ask(PermissionRequest req, AbortSignal abort) {
        // 1) 先做规则求值（快路径）
        RulesetContext ctx = rulesetContextOf(req.sessionId());
        List<Action> actions = req.patterns().stream()
                .map(p -> evaluate(req.permission(), p, ctx)).toList();

        if (actions.contains(Action.DENY))
            throw new PermissionDeniedException(req.permission(), req.patterns(),
                    "Denied by permission rule. Adjust the rule via /permission or choose a different approach.");

        if (actions.stream().allMatch(a -> a == Action.ALLOW))
            return new ReplyDecision(Reply.ONCE, null);              // 快路径放行，不打扰用户

        // 2) doom loop 检测（FR-086）
        if (doomLoop.isRepeating(req)) {
            throw new PermissionDeniedException(PermissionName.DOOM_LOOP, req.patterns(),
                "Detected a repeated identical call (%d times). Breaking the loop. "
              .formatted(doomLoop.repeatCount(req)) + "Reconsider your approach instead of retrying.");
        }

        // 3) 需要询问 → 挂起等待
        SessionEntry entry = registry.find(req.sessionId())
                .orElseThrow(() -> new IllegalStateException("session not running: " + req.sessionId()));

        CompletableFuture<ReplyDecision> future = new CompletableFuture<>();
        entry.pendingPermissions().put(req.id(), future);
        // ★ abort 级联：中断时以 AbortedException 完成，避免虚拟线程永久挂起
        abort.onCancel(() -> future.completeExceptionally(new AbortedException("permission wait aborted")));

        try {
            // 4) 发 Bus 事件（经 scope 定向到 lead/父会话，实现子 Agent 冒泡）
            String targetSession = scope.resolveTargetSession(req.sessionId());
            bus.publish(new PermissionAsked(targetSession, req.withSessionId(targetSession), bus.nextSeq()));

            // 5) 阻塞等待（虚拟线程，零平台线程成本）
            ReplyDecision decision = future.get();
            handleDecision(req, decision);
            return decision;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AbortedException("interrupted while waiting for permission", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AbortedException ae) throw ae;
            if (e.getCause() instanceof PermissionRejectedException pre) throw pre;
            throw new IllegalStateException("permission wait failed", e);
        } finally {
            entry.pendingPermissions().remove(req.id());
        }
    }

    /** ALWAYS 回复：写运行时规则 + 持久化到项目 settings + 级联放行同类 pending（FR-084 步骤 8） */
    private void handleDecision(PermissionRequest req, ReplyDecision d) {
        if (d.reply() != Reply.ALWAYS) return;
        List<PermissionRule> newRules = req.always().stream()
                .map(p -> new PermissionRule(req.permission(), p, Action.ALLOW))
                .toList();
        if (newRules.isEmpty()) return;

        // 1) 写入 session 运行时规则（内存 + DB runtime_state）
        sessions.appendRuntimePermissionRules(req.sessionId(), newRules);

        // 2) 持久化到项目 settings.json 的 common.permission.<name> 映射
        settingsWriter.persistPermissionRules(req.sessionId(), newRules);

        // 3) 级联放行所有匹配的 pending 请求（用户不必逐个点）
        registry.find(req.sessionId()).ifPresent(entry ->
            entry.pendingPermissions().forEach((id, f) -> {
                if (id.equals(req.id())) return;
                // 只有当该 pending 的 permission + pattern 被新规则覆盖时才自动放行
                PendingPermissionMeta meta = entry.pendingMeta().get(id);
                if (meta != null && meta.permission() == req.permission()
                        && meta.patterns().stream().anyMatch(p -> newRules.stream()
                                .anyMatch(r -> wildcards.match(r.pattern(), p)))) {
                    f.complete(new ReplyDecision(Reply.ONCE, null));
                }
            }));
        log.info("permission always granted session={} permission={} patterns={}",
                req.sessionId(), req.permission(), req.always());
    }

    /** UI 回复入口（CLI 与 Web 共用）。complete 幂等 → 先到先得（FR-124） */
    public void reply(String sessionId, String requestId, Reply reply, String userMessage) {
        SessionEntry entry = registry.find(sessionId).orElseThrow();
        CompletableFuture<ReplyDecision> f = entry.pendingPermissions().get(requestId);
        if (f == null) { log.debug("permission reply for unknown/expired request {}", requestId); return; }

        if (reply == Reply.REJECT) {
            // ★ 级联拒绝该 session 全部 pending（FR-084 步骤 8）
            PermissionRejectedException ex = new PermissionRejectedException(requestId,
                    "User rejected this operation.");
            entry.pendingPermissions().forEach((id, fut) -> fut.completeExceptionally(ex));
            entry.pendingPermissions().clear();
        } else {
            f.complete(new ReplyDecision(reply, userMessage));
        }
        bus.publish(new PermissionReplied(sessionId, requestId, reply, bus.nextSeq()));
    }

    /** 列出当前挂起的请求（供 Web 面板与 CLI 重绘） */
    public List<PermissionRequest> pending(String sessionId) { /* 从 entry.pendingMeta() 组装 */ }

    public void setMode(String sessionId, PermissionMode mode) {
        sessions.updateRuntimeState(sessionId, rt -> rt.withPermissionMode(mode));
        bus.publish(new SessionUpdated(sessionId, null, null, bus.nextSeq()));
    }

    /** 工具侧的门控句柄（注入 ToolContext） */
    public PermissionGate gateFor(String sessionId, String partId, String callId) {
        return new PermissionGate() {
            @Override public void ask(PermissionName name, List<String> patterns, String message,
                                      Map<String,Object> metadata, List<String> always) {
                PermissionRequest req = new PermissionRequest(
                        Ulids.next(), sessionId, name, List.copyOf(patterns),
                        metadata == null ? Map.of() : Map.copyOf(metadata),
                        message, always == null ? List.of() : List.copyOf(always),
                        new PermissionToolRef(partId, callId));
                PermissionService.this.ask(req, currentAbort(sessionId));
            }
            @Override public Action check(PermissionName name, String pattern) {
                return evaluate(name, pattern, rulesetContextOf(sessionId));
            }
        };
    }
}

/** 通配符匹配（FR-081）：* → .*，? → .，Windows 大小写不敏感，分隔符归一 */
@Component
public final class WildcardMatcher {

    private static final boolean CASE_INSENSITIVE =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private final ConcurrentMap<String, Pattern> cache = new ConcurrentHashMap<>();

    public boolean match(String pattern, String value) {
        if (pattern == null || value == null) return false;
        String p = normalize(pattern), v = normalize(value);
        if (p.equals("*")) return true;
        if (p.equals(v)) return true;
        return cache.computeIfAbsent(p, WildcardMatcher::compile).matcher(v).matches();
    }

    private static String normalize(String s) { return s.replace('\\', '/'); }

    private static Pattern compile(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() + 16);
        sb.append('^');
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                // 正则元字符转义
                case '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        sb.append('$');
        int flags = CASE_INSENSITIVE ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        return Pattern.compile(sb.toString(), flags);
    }
}

/** 规则集合并：user → project → agent → runtime，后者覆盖前者（last-match-wins 的基础） */
@Component
@RequiredArgsConstructor
public final class RulesetMerger {

    private final SettingsStore settings;

    public List<PermissionRule> merge(RulesetContext ctx) {
        List<PermissionRule> out = new ArrayList<>(32);
        Settings s = ctx.settings();

        // 1) 用户级/项目级 common.permission（Map<String, Action> 或 Map<String, Map<String, Action>>）
        out.addAll(fromConfigMap(s.common().permission()));

        // 2) agent 人格定义的规则
        if (ctx.agentInfo() != null) out.addAll(ctx.agentInfo().permissionRules());

        // 3) session 运行时规则（always 回复产生）—— 最后，优先级最高
        out.addAll(ctx.runtimeRules());

        return List.copyOf(out);
    }

    /**
     * 配置形态兼容两种写法：
     *   简写  {"Bash": "ask"}                       → rule(BASH, "*", ASK)
     *   展开  {"bash": {"git *": "allow"}}          → rule(BASH, "git *", ALLOW)
     * ★ 展开写法必须在简写之后加入，才能 last-match-wins 生效
     */
    private List<PermissionRule> fromConfigMap(Map<String, Object> permission) {
        List<PermissionRule> simple = new ArrayList<>(), expanded = new ArrayList<>();
        permission.forEach((key, value) -> {
            PermissionName name = PermissionName.of(key);
            if (value instanceof String sv) {
                simple.add(new PermissionRule(name, "*", Action.valueOf(sv.toUpperCase(Locale.ROOT))));
            } else if (value instanceof Map<?,?> mv) {
                mv.forEach((p, a) -> expanded.add(new PermissionRule(name, String.valueOf(p),
                        Action.valueOf(String.valueOf(a).toUpperCase(Locale.ROOT)))));
            }
        });
        return concat(simple, expanded);      // 简写在前，展开在后
    }
}

/** 子 Agent 的权限请求冒泡到主会话（FR-079 AC） */
@Component
@RequiredArgsConstructor
public final class PermissionScopeResolver {
    private final SessionService sessions;
    /** 沿 parent_id 链向上找到最顶层的主会话；找不到则返回自身 */
    public String resolveTargetSession(String sessionId) {
        String cur = sessionId;
        for (int depth = 0; depth < 8; depth++) {          // 防环，最多 8 层
            String parent = sessions.get(cur).parentId();
            if (parent == null || parent.isBlank()) return cur;
            cur = parent;
        }
        return cur;
    }
}

/** FR-086：同工具 + 相同 input 连续重复检测 */
@Component
public final class DoomLoopDetector {
    private static final int THRESHOLD = 5;
    /** sessionId → (toolName + inputHash → 连续次数) */
    private final ConcurrentMap<String, Map<String, AtomicInteger>> counters = new ConcurrentHashMap<>();

    public boolean isRepeating(PermissionRequest req) {
        return count(req) >= THRESHOLD;
    }
    public int repeatCount(PermissionRequest req) { return count(req); }

    private int count(PermissionRequest req) {
        String key = req.permission().wire() + ":" + stableHash(req.patterns());
        Map<String, AtomicInteger> m = counters.computeIfAbsent(req.sessionId(), k -> new ConcurrentHashMap<>());
        return m.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }
    /** input 变化即重置该会话全部计数（说明模型在推进） */
    public void recordDistinct(String sessionId, PermissionName name, List<String> patterns) {
        counters.computeIfPresent(sessionId, (k, m) -> { m.clear(); return m; });
    }
    public void reset(String sessionId) { counters.remove(sessionId); }
}
```

---

### 5.9 提问服务（FR-078）

结构与权限服务同构，差异点：

```java
@Service
@RequiredArgsConstructor
public final class QuestionService {

    private final SessionRegistry registry;
    private final Bus bus;
    private final PermissionScopeResolver scope;

    public List<List<String>> ask(QuestionRequest req, AbortSignal abort) {
        // 1) 校验：questions 1-4、options 2-4、header ≤12、label ≤60、保留标签拒绝
        req.questions().forEach(QuestionInfo::validateNotReserved);
        if (req.questions().size() < 1 || req.questions().size() > 4)
            throw new ToolException("questions must contain 1 to 4 items, got " + req.questions().size());

        SessionEntry entry = registry.find(req.sessionId()).orElseThrow();
        CompletableFuture<List<List<String>>> f = new CompletableFuture<>();
        entry.pendingQuestions().put(req.id(), f);
        abort.onCancel(() -> f.completeExceptionally(new AbortedException("question wait aborted")));

        try {
            String target = scope.resolveTargetSession(req.sessionId());
            bus.publish(new QuestionAsked(target, req.withSessionId(target), bus.nextSeq()));
            return f.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof QuestionRejectedException qre) throw qre;
            if (e.getCause() instanceof AbortedException ae) throw ae;
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new AbortedException("interrupted", e);
        } finally {
            entry.pendingQuestions().remove(req.id());
        }
    }

    public void reply(String sessionId, String requestId, List<List<String>> answers) {
        registry.find(sessionId).ifPresent(e -> {
            CompletableFuture<List<List<String>>> f = e.pendingQuestions().get(requestId);
            if (f != null) f.complete(answers);                       // 幂等 → 先到先得
        });
        bus.publish(new QuestionReplied(sessionId, requestId, answers, bus.nextSeq()));
    }

    public void reject(String sessionId, String requestId) {
        registry.find(sessionId).ifPresent(e -> {
            CompletableFuture<List<List<String>>> f = e.pendingQuestions().get(requestId);
            if (f != null) f.completeExceptionally(new QuestionRejectedException(requestId));
        });
        bus.publish(new QuestionRejected(sessionId, requestId, bus.nextSeq()));
    }
}

@We0Tool(name = ToolNames.ASK_USER_QUESTION, permission = PermissionName.QUESTION)
@RequiredArgsConstructor
public final class AskUserQuestionTool implements Tool {

    private final QuestionService questions;

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        List<QuestionInfo> qs = input.requireList("questions", QuestionInfo.class);
        QuestionRequest req = new QuestionRequest(Ulids.next(), ctx.sessionId(), qs, Map.of(),
                new QuestionToolRef(ctx.messageId(), ctx.callId()));

        List<List<String>> answers;
        try {
            answers = ctx.questions().ask(req, ctx.abort());
        } catch (QuestionRejectedException e) {
            return ToolResult.text("The user dismissed the questionnaire without answering. "
                + "Proceed with your best judgment, or ask in plain text if the decision is blocking.");
        }

        // 结果文本（对齐原项目格式）
        StringBuilder sb = new StringBuilder("User has answered your questions: ");
        for (int i = 0; i < qs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(qs.get(i).question()).append("\"=\"")
              .append(String.join(" + ", answers.get(i))).append('"');
        }
        Map<String,Object> structured = new LinkedHashMap<>();
        structured.put("questions", qs);
        structured.put("answers", answers);
        return new ToolResult(List.of(ContentBlock.of(sb.toString())), structured, List.of());
    }
}
```

---

### 5.10 快照与回滚（FR-10）

#### 5.10.1 SnapshotService

```java
public interface SnapshotService {
    /** git add -A + write-tree，返回 tree hash（不创建 commit） */
    Optional<String> track(String sessionId);
    /** 相对某 tree hash 的变更文件列表 */
    List<String> patch(String sessionId, String treeHash);
    /** 两个 tree hash 之间的完整 diff（name-status + numstat + before/after 全文） */
    FullDiff diffFull(String sessionId, String hash1, String hash2);
    /** read-tree + checkout-index -a -f */
    void restore(String sessionId, String treeHash);
    /** 逐文件 checkout；文件不在快照中则删除 */
    void revert(String sessionId, List<String> files, String treeHash);
    /** 是否可用（git 缺失或初始化失败 → false，禁用快照但不阻断 Loop） */
    boolean available();
}

@Service
@RequiredArgsConstructor
public final class GitCliSnapshotService implements SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(GitCliSnapshotService.class);
    private static final List<String> DEFAULT_EXCLUDES = List.of(
            "node_modules/", ".venv/", "venv/", "__pycache__/", "target/", "build/",
            "dist/", ".gradle/", ".idea/", "*.class", "*.jar", ".DS_Store", "Thumbs.db");

    private final PathResolver paths;
    private final GitRunner git;
    private final ConcurrentMap<String, ShadowRepo> repos = new ConcurrentHashMap<>();
    private final Striped<ReentrantLock> locks = Striped.lazyWeakLock(32);

    private record ShadowRepo(Path gitDir, Path workTree, boolean initialized) {}

    /** 懒初始化 shadow repo：独立 --git-dir，不污染用户 .git */
    private ShadowRepo repo(String sessionId, Path workdir) {
        return repos.computeIfAbsent(sessionId, sid -> {
            Path gitDir = paths.snapshotDir(sid);
            ReentrantLock lock = locks.get(gitDir.toString());
            lock.lock();
            try {
                if (!Files.exists(gitDir.resolve("HEAD"))) {
                    Files.createDirectories(gitDir);
                    git.run(List.of("init", "--bare", gitDir.toString()), workdir, Duration.ofSeconds(30));
                    // ★ 关键配置：避免 Windows CRLF / 长路径 / fsmonitor 干扰
                    setConfig(gitDir, workdir, "core.autocrlf", "false");
                    setConfig(gitDir, workdir, "core.longpaths", "true");
                    setConfig(gitDir, workdir, "core.fsmonitor", "false");
                    setConfig(gitDir, workdir, "core.preloadindex", "true");
                    setConfig(gitDir, workdir, "core.untrackedCache", "true");
                    writeExcludeFile(gitDir, workdir);
                }
                return new ShadowRepo(gitDir, workdir, true);
            } catch (Exception e) {
                log.error("shadow git init failed, snapshots disabled for session {}", sid, e);
                return new ShadowRepo(gitDir, workdir, false);
            } finally { lock.unlock(); }
        });
    }

    /** 同步用户仓库的 .git/info/exclude + 默认排除项 */
    private void writeExcludeFile(Path gitDir, Path workdir) throws IOException {
        List<String> lines = new ArrayList<>(DEFAULT_EXCLUDES);
        Path userExclude = workdir.resolve(".git/info/exclude");
        if (Files.exists(userExclude)) lines.addAll(Files.readAllLines(userExclude));
        Path gitignore = workdir.resolve(".gitignore");
        if (Files.exists(gitignore)) lines.addAll(Files.readAllLines(gitignore));   // ★ 尊重 .gitignore
        Files.createDirectories(gitDir.resolve("info"));
        Files.write(gitDir.resolve("info/exclude"), lines);
    }

    @Override
    public Optional<String> track(String sessionId) {
        ShadowRepo r = repos.get(sessionId);
        if (r == null || !r.initialized()) return Optional.empty();
        ReentrantLock lock = locks.get(r.gitDir().toString());
        lock.lock();                                        // ★ ReentrantLock，不用 synchronized（防 pinning）
        try {
            git.run(base(r).stream().addAll(List.of("add", "-A", "--force", ".")).toList(),
                    r.workTree(), Duration.ofSeconds(60));
            String tree = git.run(base(r).stream().addAll(List.of("write-tree")).toList(),
                    r.workTree(), Duration.ofSeconds(30)).stdout().trim();
            return tree.isEmpty() ? Optional.empty() : Optional.of(tree);
        } catch (GitException e) {
            log.warn("snapshot track failed session={}: {}", sessionId, e.getMessage());
            return Optional.empty();                        // ★ 快照失败不阻断 Loop
        } finally { lock.unlock(); }
    }

    private List<String> base(ShadowRepo r) {
        return List.of("git", "--git-dir=" + r.gitDir(), "--work-tree=" + r.workTree() + File.separator);
    }
    private void setConfig(Path gitDir, Path workdir, String k, String v) {
        git.run(List.of("git", "--git-dir=" + gitDir, "--work-tree=" + workdir, "config", k, v),
                workdir, Duration.ofSeconds(10));
    }

    @Override
    public List<String> patch(String sessionId, String treeHash) {
        ShadowRepo r = repos.get(sessionId);
        if (r == null || treeHash == null) return List.of();
        return git.run(concat(base(r), List.of("diff", "--name-only", treeHash)),
                       r.workTree(), Duration.ofSeconds(30))
                .stdout().lines().filter(s -> !s.isBlank()).toList();
    }

    @Override
    public FullDiff diffFull(String sessionId, String h1, String h2) {
        ShadowRepo r = repos.get(sessionId);
        // 1) --name-status 拿状态
        List<String> nameStatus = git.run(concat(base(r), List.of("diff", "--name-status", h1, h2)),
                r.workTree(), D30).stdout().lines().toList();
        // 2) --numstat 拿增删行数
        List<String> numstat = git.run(concat(base(r), List.of("diff", "--numstat", h1, h2)),
                r.workTree(), D30).stdout().lines().toList();
        // 3) 逐文件 git show <hash>:<path> 取 before/after 全文
        Map<String, FileBeforeAfter> contents = new LinkedHashMap<>();
        for (String line : nameStatus) {
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            String status = parts[0], path = parts[parts.length - 1];
            String before = showOrNull(r, h1, path), after = showOrNull(r, h2, path);
            contents.put(path, new FileBeforeAfter(status.charAt(0), before, after));
        }
        return new FullDiff(parseNameStatus(nameStatus), parseNumstat(numstat), contents);
    }

    @Override
    public void restore(String sessionId, String treeHash) {
        ShadowRepo r = repos.get(sessionId);
        ReentrantLock lock = locks.get(r.gitDir().toString());
        lock.lock();
        try {
            git.run(concat(base(r), List.of("read-tree", treeHash)), r.workTree(), D60);
            git.run(concat(base(r), List.of("checkout-index", "-a", "-f")), r.workTree(), D120);
        } finally { lock.unlock(); }
    }

    @Override
    public void revert(String sessionId, List<String> files, String treeHash) {
        ShadowRepo r = repos.get(sessionId);
        ReentrantLock lock = locks.get(r.gitDir().toString());
        lock.lock();
        try {
            for (String f : files) {
                Path abs = r.workTree().resolve(f);
                if (existsInTree(r, treeHash, f)) {
                    git.run(concat(base(r), List.of("checkout", treeHash, "--", f)), r.workTree(), D30);
                } else {
                    // ★ 文件不在目标快照中 → 说明是本次新增的，应删除
                    Files.deleteIfExists(abs);
                }
            }
        } catch (IOException e) { throw new SnapshotException("revert failed", e); }
        finally { lock.unlock(); }
    }

    private boolean existsInTree(ShadowRepo r, String treeHash, String path) {
        try {
            git.run(concat(base(r), List.of("cat-file", "-e", treeHash + ":" + path)), r.workTree(), D10);
            return true;
        } catch (GitException e) { return false; }
    }
    private String showOrNull(ShadowRepo r, String hash, String path) {
        try { return git.run(concat(base(r), List.of("show", hash + ":" + path)), r.workTree(), D10).stdout(); }
        catch (GitException e) { return null; }
    }
}
```

#### 5.10.2 RevertService（FR-102）

```java
public enum RevertMode { CONVERSATION, BOTH }

public record RevertRecord(
        RevertMode mode,
        String targetMessageId,          // 回滚到哪条 UserMessage 之后（不含）
        String targetPartId,
        String snapshot,                 // BOTH 模式：回滚前 track() 的 tree hash，供 unrevert
        String targetSnapshot,           // 目标锚点的 tree hash
        List<String> revertedFiles,
        Instant timeCreated) {}

@Service
@RequiredArgsConstructor
public final class RevertService {

    private final SessionService sessions;
    private final SnapshotService snapshot;
    private final SessionStateCache cache;
    private final Bus bus;

    /** 列出可回滚锚点（供 /rewind 面板） */
    public List<RewindAnchor> listAnchors(String sessionId) {
        return sessions.history(sessionId).stream()
                .filter(m -> m.message() instanceof UserMessage u
                          && !isSyntheticOnly(u, m.parts())
                          && !Boolean.TRUE.equals(((UserMessage) m.message()).metadata().get("hidden")))
                .map(m -> {
                    UserMessage u = (UserMessage) m.message();
                    String treeHash = firstStepStartSnapshot(sessionId, u.id());
                    UserSummary sum = u.summary();
                    return new RewindAnchor(u.id(), u.timeCreated(), firstUserText(m.parts()),
                            treeHash, sum == null ? List.of() : sum.diffs(),
                            sum == null ? 0 : sum.diffs().size());
                })
                .toList();
    }

    /**
     * 执行回滚。
     * CONVERSATION：只标记边界（软删除，可撤销）
     * BOTH：先 track() 保存当前状态供 unrevert，再 revert 代码，最后标记对话边界
     */
    public RevertResult revert(String sessionId, String targetMessageId, RevertMode mode) {
        Session session = sessions.get(sessionId);
        String targetSnapshot = snapshotOfMessage(sessionId, targetMessageId);

        List<String> revertedFiles = List.of();
        String undoSnapshot = null;

        if (mode == RevertMode.BOTH) {
            if (targetSnapshot == null)
                throw new SnapshotException("No snapshot recorded for the target anchor; "
                        + "code rollback is unavailable. Use CONVERSATION mode instead.");
            // 1) ★ 先保存当前状态，供 unrevert
            undoSnapshot = snapshot.track(sessionId).orElse(null);
            // 2) 计算变更文件集
            revertedFiles = snapshot.patch(sessionId, targetSnapshot);
            // 3) 回滚代码
            snapshot.revert(sessionId, revertedFiles, targetSnapshot);
        }

        // 4) 标记对话边界
        RevertRecord record = new RevertRecord(mode, targetMessageId, null,
                undoSnapshot, targetSnapshot, revertedFiles, Instant.now());
        sessions.setRevert(sessionId, record);

        bus.publish(new SessionDiff(sessionId, revertedFiles, bus.nextSeq()));
        return new RevertRecord2Result(record, revertedFiles.size());
    }

    /** 撤销回滚（FR-102） */
    public void unrevert(String sessionId) {
        RevertRecord r = sessions.getRevert(sessionId);
        if (r == null) throw new IllegalStateException("no pending revert to undo");
        if (r.mode() == RevertMode.BOTH && r.snapshot() != null)
            snapshot.restore(sessionId, r.snapshot());           // 恢复代码
        sessions.clearRevert(sessionId);                          // 清除对话边界
        bus.publish(new SessionUpdated(sessionId, null, null, bus.nextSeq()));
    }

    /** 物理删除被回滚的消息（在下次 prompt 入口调用，对齐原项目 cleanup 时机） */
    public void cleanup(String sessionId) {
        RevertRecord r = sessions.getRevert(sessionId);
        if (r == null) return;
        List<String> toDelete = sessions.messagesAfter(sessionId, r.targetMessageId());
        if (!toDelete.isEmpty()) {
            sessions.deleteMessages(sessionId, toDelete);          // 级联删 part
            cache.evictMessages(sessionId, toDelete);
            bus.publish(new MessageRemoved(sessionId, toDelete, bus.nextSeq()));
        }
        sessions.clearRevert(sessionId);
    }
}
```

**历史读取时的边界过滤**（`HistoryReader`）：
```java
public List<MessageWithParts> streamMessages(String sessionId) {
    List<MessageWithParts> all = cache.history(sessionId);
    RevertRecord revert = cache.runtimeState(sessionId).pendingRevert();
    if (revert != null) {
        // 软删除：过滤掉 targetMessageId 之后（不含）的所有消息
        int idx = indexOfMessage(all, revert.targetMessageId());
        if (idx >= 0) all = all.subList(0, idx + 1);
    }
    return all;
}
```

---

### 5.11 Skills（FR-080）

```java
@Component
@RequiredArgsConstructor
public final class SkillScanner {

    private final PathResolver paths;
    private final YamlFrontmatterParser frontmatter;

    /**
     * 分层扫描：全局 → 项目。项目级同名覆盖全局。尊重 disable 列表。
     * 目录结构：<skillsDir>/<skillName>/SKILL.md
     */
    public List<SkillCard> scan(Path projectRoot, List<String> disabled) {
        Map<String, SkillCard> byName = new LinkedHashMap<>();
        Set<String> disabledSet = Set.copyOf(disabled);

        // 1) 全局（低优先级）
        for (SkillCard c : scanDir(paths.globalSkillsDir(), AgentKind.GLOBAL)) byName.put(c.name(), c);
        // 2) 项目（覆盖同名）
        for (SkillCard c : scanDir(projectRoot.resolve(".we0j/skills"), AgentKind.PROJECT))
            byName.put(c.name(), c);

        disabledSet.forEach(byName::remove);
        return List.copyOf(byName.values());
    }

    private List<SkillCard> scanDir(Path dir, AgentKind kind) {
        if (!Files.isDirectory(dir)) return List.of();
        List<SkillCard> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path skillDir : s.filter(Files::isDirectory).sorted().toList()) {
                Path md = skillDir.resolve("SKILL.md");
                if (!Files.isRegularFile(md)) continue;
                try {
                    String raw = Files.readString(md, StandardCharsets.UTF_8);
                    FrontmatterResult fr = frontmatter.parse(raw);
                    SkillCard card = new SkillCard(
                            fr.meta().getOrDefault("name", skillDir.getFileName().toString()),
                            fr.meta().getOrDefault("description", ""),
                            fr.meta().get("license"),
                            fr.meta().get("compatibility"),
                            parseAllowedTools(fr.meta().get("allowed-tools")),
                            Map.copyOf(fr.meta()),
                            skillDir,                                   // ★ location，供 {{WE0J_*}} 展开
                            fr.body());
                    if (card.description().isBlank()) {
                        log.warn("skill '{}' has no description; it will not be discoverable by the model",
                                card.name());
                    }
                    out.add(card);
                } catch (Exception e) {
                    log.warn("failed to parse skill {}: {}", md, e.getMessage());
                }
            }
        } catch (IOException e) { log.warn("cannot list skills dir {}: {}", dir, e.getMessage()); }
        return out;
    }
}

/** 热加载：WatchService + 轮询兜底（macOS/网络盘） */
@Component
@RequiredArgsConstructor
public final class SkillWatcher {

    private final SkillScanner scanner;
    private final SkillStore store;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private WatchService watcher;
    private volatile long lastPollScan;
    private static final long POLL_INTERVAL_MS = 2000;

    @PostConstruct
    public void start() {
        Thread.ofVirtual().name("we0j-skill-watcher").start(this::loop);
    }

    private void loop() {
        try {
            watcher = FileSystems.getDefault().newWatchService();
            registerAll(paths.globalSkillsDir());
            registerAll(currentProjectSkillsDir());
        } catch (IOException e) {
            log.warn("WatchService unavailable, falling back to polling only: {}", e.getMessage());
        }
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1) WatchService（非阻塞取，避免 macOS 上事件不可靠）
                if (watcher != null) {
                    WatchKey key = watcher.poll();
                    while (key != null) {
                        dirty.set(true);
                        key.pollEvents();
                        if (!key.reset()) break;
                        key = watcher.poll();
                    }
                }
                // 2) 轮询兜底：每 2s 比对目录 mtime 摘要
                if (System.currentTimeMillis() - lastPollScan > POLL_INTERVAL_MS) {
                    lastPollScan = System.currentTimeMillis();
                    if (!currentFingerprint().equals(lastFingerprint)) { dirty.set(true); lastFingerprint = currentFingerprint(); }
                }
                if (dirty.compareAndSet(true, false)) store.markPendingRescan();
                Thread.sleep(200);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (Exception e) { log.debug("skill watcher tick error", e); }
        }
    }

    /** Loop 在 reminder 注入点 drain（对齐原项目 skill_watcher.consume()） */
    public boolean consumePendingUpdates() { return store.consumePendingRescan(); }
}

@We0Tool(name = ToolNames.SKILL, permission = PermissionName.SKILL, deferLoading = false)
@RequiredArgsConstructor
public final class SkillTool implements Tool {

    private final SkillStore store;
    private final SkillTemplateExpander expander;

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String name = input.requireString("name");
        String args = input.optString("args").orElse("");
        SkillCard card = store.find(name)
                .orElseThrow(() -> new ToolException("Unknown skill '%s'. Available skills: %s"
                        .formatted(name, String.join(", ", store.names()))));

        ctx.gate().ask(PermissionName.SKILL, List.of(name), "Load skill: " + name,
                Map.of("skill", name, "location", card.location().toString()), List.of(name));

        // 展开 {{WE0J_*}} 路径占位符（相对 skill 目录解析为绝对路径）
        String body = expander.expand(card.body(), card.location(), ctx.workdir());

        // ★ 正文以工具结果身份进入上下文（渐进式披露的第二阶段）
        String wrapped = """
            <skill name="%s" allowed_tools="%s" args="%s">
            %s
            </skill>""".formatted(card.name(), String.join(",", card.allowedTools()), args, body);

        // 记录已调用（供压缩后恢复）
        ctx.session().recordSkillInvoked(card.name());
        return new ToolResult(List.of(ContentBlock.of(wrapped)),
                Map.of("skill", card.name(), "location", card.location().toString()), List.of());
    }
}
```

**`{{WE0J_*}}` 占位符表**：

| 占位符 | 展开为 |
|---|---|
| `{{WE0J_SKILL_DIR}}` | skill 所在目录绝对路径 |
| `{{WE0J_SKILL_FILE}}` | SKILL.md 绝对路径 |
| `{{WE0J_WORKDIR}}` | 当前项目根 |
| `{{WE0J_HOME}}` | `~/.we0j` |
| `{{WE0J_SESSION_ID}}` | 当前会话 id |
| `{{WE0J_PROJECT_DATA}}` | `~/.we0j/projects/<projectId>` |

**`SkillsContributor` 渲染的 reminder**（渐进式披露第一阶段）：
```xml
<system-reminder>
The following skills are available. Each is a specialized instruction set for a task type.
Only the name and description are shown here — call the SKILL tool with the skill name to
load its full instructions when (and only when) the current task matches.
Do not load skills speculatively.

<available-skills>
  <skill name="pi-goal-writer">Drafts and reviews strong /goal objectives...</skill>
  <skill name="mcp-scripting">Write mcpScript JavaScript for discovering...</skill>
</available-skills>
</system-reminder>
```

---

### 5.12 后台任务与通知回流（FR-15）

#### 5.12.1 统一任务管理器

```java
public enum BackgroundTaskType { AGENT, SHELL }
public enum BackgroundTaskStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

public record BackgroundTask(
        String id, BackgroundTaskType type, String sessionId, String parentSessionId,
        BackgroundTaskStatus status, Instant timeCreated, Instant timeCompleted,
        Path outputFile, String summary, int toolCallCount, long tokensUsed,
        String description) {}

@Component
@RequiredArgsConstructor
public final class BackgroundTaskManager {

    private static final int MAX_AGENTS = 10, MAX_SHELLS = 10;

    private final ConcurrentMap<String, Entry> tasks = new ConcurrentHashMap<>();
    private final Semaphore agentSemaphore = new Semaphore(MAX_AGENTS);      // ★ Semaphore 不 pin
    private final Semaphore shellSemaphore = new Semaphore(MAX_SHELLS);
    private final Bus bus;
    private final NotificationService notifications;
    private final SessionFacade facade;

    private static final class Entry {
        BackgroundTask task;
        Future<?> future;
        AbortSignal abort;
        CompletableFuture<BackgroundTask> completion = new CompletableFuture<>();
    }

    /** 注册并启动后台子 Agent（FR-079） */
    public BackgroundTask startAgent(StartAgentCommand cmd) {
        Entry entry = new Entry();
        String id = cmd.agentId();
        entry.task = new BackgroundTask(id, BackgroundTaskType.AGENT, cmd.childSessionId(),
                cmd.parentSessionId(), BackgroundTaskStatus.QUEUED, Instant.now(), null,
                cmd.outputFile(), null, 0, 0, cmd.description());
        entry.abort = cmd.parentAbort().child();
        tasks.put(id, entry);
        publish(entry.task);

        entry.future = VirtualThreadExecutors.IO.submit(() -> {
            try {
                agentSemaphore.acquire();                                  // 阻塞排队（虚拟线程友好）
                entry.task = entry.task.withStatus(BackgroundTaskStatus.RUNNING);
                publish(entry.task);

                LoopOutcome outcome = RuntimeLaneRegistry.callAs(RuntimeLane.SIDE_AGENT,
                        () -> facade.runChildSession(cmd, entry.abort));

                BackgroundTaskStatus st = switch (outcome.reason()) {
                    case COMPLETED_REPLY -> BackgroundTaskStatus.COMPLETED;
                    case ABORTED -> BackgroundTaskStatus.CANCELLED;
                    default -> BackgroundTaskStatus.FAILED;
                };
                entry.task = entry.task.withStatus(st)
                        .withTimeCompleted(Instant.now())
                        .withSummary(outcome.summary())
                        .withTokens(outcome.tokens().total());
                publish(entry.task);
                entry.completion.complete(entry.task);

                // ★ 完成通知回流（FR-153）
                notifications.pushOrResume(cmd.parentSessionId(), TaskNotification.ofAgent(entry.task, outcome));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelInternal(entry, "interrupted");
            } catch (Exception e) {
                entry.task = entry.task.withStatus(BackgroundTaskStatus.FAILED)
                        .withTimeCompleted(Instant.now()).withSummary(e.getMessage());
                publish(entry.task);
                entry.completion.completeExceptionally(e);
                notifications.pushOrResume(cmd.parentSessionId(), TaskNotification.failed(entry.task, e));
            } finally {
                agentSemaphore.release();
            }
            return null;
        });
        return entry.task;
    }

    /** 取消：同时取消虚拟线程任务与子 Session 的 Loop（FR-154） */
    public boolean cancel(String taskId) {
        Entry entry = tasks.get(taskId);
        if (entry == null) return false;
        entry.abort.abort();                                       // 级联到子 Loop / 子进程 / HTTP
        if (entry.future != null) entry.future.cancel(true);
        if (entry.task.type() == BackgroundTaskType.AGENT) facade.cancel(entry.task.sessionId());
        cancelInternal(entry, "cancelled by user");
        return true;
    }

    private void cancelInternal(Entry entry, String reason) {
        entry.task = entry.task.withStatus(BackgroundTaskStatus.CANCELLED)
                .withTimeCompleted(Instant.now()).withSummary(reason);
        publish(entry.task);
        entry.completion.complete(entry.task);
    }

    public Optional<BackgroundTask> find(String id) { return Optional.ofNullable(tasks.get(id)).map(e -> e.task); }
    public List<BackgroundTask> list(String sessionId) {
        return tasks.values().stream().map(e -> e.task)
                .filter(t -> sessionId.equals(t.parentSessionId()) || sessionId.equals(t.sessionId()))
                .sorted(comparing(BackgroundTask::timeCreated).reversed()).toList();
    }
    public CompletableFuture<BackgroundTask> completionOf(String id) {
        Entry e = tasks.get(id);
        return e == null ? CompletableFuture.failedFuture(new NotFoundException(id)) : e.completion;
    }
    private void publish(BackgroundTask t) { bus.publish(new TaskUpdated(t, bus.nextSeq())); }
}
```

#### 5.12.2 ShellManager

```java
@Component
@RequiredArgsConstructor
public final class ShellManager {

    private final ShellExecutor executor;
    private final BackgroundTaskManager tasks;
    private final Bus bus;

    public BackgroundShell create(BackgroundShellCommand cmd) {
        // 立即返回 id + 输出文件路径（不等待执行）
        BackgroundShell shell = new BackgroundShell(cmd.id(), cmd.sessionId(), cmd.outputFile(),
                cmd.command(), Instant.now());

        VirtualThreadExecutors.IO.submit(() -> {
            AtomicReference<Process> procRef = new AtomicReference<>();
            try {
                ShellOutcome out = executor.run(ShellCommand.builder()
                        .command(cmd.command()).cwd(cmd.cwd())
                        .timeout(cmd.timeout() == null ? Duration.ofHours(8) : cmd.timeout())
                        .abort(cmd.abort())
                        .sink(new FileToolOutputSink(cmd.outputFile()))
                        .env(cmd.env())
                        .processSink(procRef::set)                    // 暴露 Process 供杀树
                        .build());
                tasks.completeShell(cmd.id(), out);
            } catch (Exception e) {
                tasks.failShell(cmd.id(), e);
            }
        });
        return shell;
    }

    /** 输出尾随（TaskOutput / Web 面板实时跟随） */
    public String tail(String shellId, int maxBytes) {
        Path f = tasks.find(shellId).map(BackgroundTask::outputFile).orElseThrow();
        if (!Files.exists(f)) return "";
        try (SeekableByteChannel ch = Files.newByteChannel(f)) {
            long size = ch.size();
            long from = Math.max(0, size - maxBytes);
            ch.position(from);
            ByteBuffer buf = ByteBuffer.allocate((int) (size - from));
            while (buf.hasRemaining() && ch.read(buf) >= 0) { }
            return new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new ToolException("cannot read shell output: " + e.getMessage(), e); }
    }

    public boolean kill(String shellId) { return tasks.cancel(shellId); }
}
```

#### 5.12.3 通知回流（★ 忙/闲双路径，FR-153）

```java
/**
 * 通知投递策略（对齐原项目 BaseSessionDispatcher.push_or_resume）：
 *  - 目标会话 Busy  → 入队，由 Loop 在工具子循环间隙 drain，作为 <task-notification> reminder 注入（不落库）
 *  - 目标会话 Idle  → 写入合成 UserMessage（落库）+ 唤醒 Loop
 */
@Service
@RequiredArgsConstructor
public final class NotificationService {

    private final SessionRegistry registry;
    private final SessionService sessions;
    private final SessionFacade facade;
    private final Bus bus;
    /** sessionId → 待投递通知队列（Loop drain 后清空） */
    private final ConcurrentMap<String, BlockingQueue<TaskNotification>> queues = new ConcurrentHashMap<>();

    public void pushOrResume(String targetSessionId, TaskNotification notification) {
        Optional<SessionEntry> running = registry.find(targetSessionId);

        if (running.isPresent() && running.get().status().get() instanceof SessionStatus.Busy) {
            // ── 忙：入队 ─────────────────────────────────────────────────
            queue(targetSessionId).offer(notification);
            bus.publish(new NotificationPushed(targetSessionId, notification,
                    NotificationDelivery.QUEUED, bus.nextSeq()));
            log.debug("notification queued for busy session {} task={}", targetSessionId, notification.taskId());
            return;
        }

        // ── 闲：落库 + 唤醒 ──────────────────────────────────────────────
        String text = notification.toUserMessageText();
        sessions.appendSyntheticUserMessage(targetSessionId, text, ChannelSource.NOTIFICATION,
                Map.of("notificationTaskId", notification.taskId(),
                       "notificationType", notification.taskType().wire()));
        bus.publish(new NotificationPushed(targetSessionId, notification,
                NotificationDelivery.INJECTED, bus.nextSeq()));

        // 唤醒 Loop（resumeExisting=true：不新建 user turn，从历史推导后继续）
        VirtualThreadExecutors.IO.submit(() -> {
            try { facade.resumeExisting(targetSessionId, null, ChannelSource.NOTIFICATION); }
            catch (Exception e) { log.warn("failed to resume session {} for notification", targetSessionId, e); }
        });
    }

    /** Loop 步骤 8 之前 drain（一次性消费） */
    public List<TaskNotification> drain(String sessionId) {
        BlockingQueue<TaskNotification> q = queues.get(sessionId);
        if (q == null || q.isEmpty()) return List.of();
        List<TaskNotification> out = new ArrayList<>(q.size());
        q.drainTo(out);
        return List.copyOf(out);
    }

    private BlockingQueue<TaskNotification> queue(String sessionId) {
        return queues.computeIfAbsent(sessionId, k -> new LinkedBlockingQueue<>(256));
    }
}

/** 通知内容 → 注入文本 */
public record TaskNotification(
        String taskId, TaskType taskType, BackgroundTaskStatus status,
        String description, String outputFile, String summary,
        int toolCallCount, long tokensUsed, Instant timeCompleted) {

    public enum TaskType { BACKGROUND_AGENT, BACKGROUND_SHELL;
        public String wire() { return name().toLowerCase(Locale.ROOT); } }

    /**
     * 渲染为 <task-notification> 块（BackgroundNotificationContributor 使用）。
     * ★ 必须包含 outputFile —— 让模型知道去哪里读完整输出。
     */
    public String toSystemReminder() {
        return """
            <task-notification>
            <task_id>%s</task_id>
            <task_type>%s</task_type>
            <status>%s</status>
            <description>%s</description>
            <summary>%s</summary>
            <tool_calls>%d</tool_calls>
            <tokens>%d</tokens>
            <output_file>%s</output_file>
            </task-notification>
            A background task you started has finished. Read the output file with the Read tool \
            if you need details, then continue with your current work.""".formatted(
                taskId, taskType.wire(), status.name().toLowerCase(Locale.ROOT),
                description == null ? "" : description,
                summary == null ? "" : summary,
                toolCallCount, tokensUsed, outputFile);
    }

    /** Idle 路径：作为合成 UserMessage 的文本 */
    public String toUserMessageText() {
        return "<system-reminder>\n" + toSystemReminder() + "\n</system-reminder>";
    }

    public static TaskNotification ofAgent(BackgroundTask t, LoopOutcome o) { /* ... */ }
    public static TaskNotification failed(BackgroundTask t, Exception e) { /* ... */ }
}

/** 反解析 <task-notification> 块 → 结构化对象，供 UI 富渲染（对齐 TaskNotificationCodec） */
@Component
public final class TaskNotificationCodec {
    private static final Pattern BLOCK = Pattern.compile(
            "<task-notification>(.*?)</task-notification>", Pattern.DOTALL);

    public Optional<TaskNotification> decode(String text) {
        Matcher m = BLOCK.matcher(text);
        if (!m.find()) return Optional.empty();
        String body = m.group(1);
        return Optional.of(new TaskNotification(
                tag(body, "task_id"), TaskType.valueOf(tag(body, "task_type").toUpperCase(Locale.ROOT)),
                BackgroundTaskStatus.valueOf(tag(body, "status").toUpperCase(Locale.ROOT)),
                tag(body, "description"), Path.of(tag(body, "output_file")), tag(body, "summary"),
                Integer.parseInt(orZero(tag(body, "tool_calls"))), Long.parseLong(orZero(tag(body, "tokens"))),
                Instant.now()));
    }
    private static String tag(String body, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(body);
        return m.find() ? m.group(1).trim() : "";
    }
}
```

#### 5.12.4 Agent 工具与子会话输出落盘

```java
@We0Tool(name = ToolNames.AGENT, permission = PermissionName.AGENT)
@RequiredArgsConstructor
public final class AgentTool implements Tool {

    /** 子 Agent 禁止的工具（防递归 + 防越权） */
    private static final Set<String> CHILD_RESTRICTED = Set.of(
            ToolNames.AGENT, ToolNames.TEAM_CREATE, ToolNames.TEAM_DELETE);

    private final AgentRegistry agents;
    private final SessionService sessions;
    private final SessionFacade facade;
    private final BackgroundTaskManager background;
    private final OverlayStore overlays;
    private final ModelCardManager models;
    private final PathResolver paths;

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String subagentType = input.requireString("subagentType");
        String prompt = input.requireString("prompt");
        String description = input.optString("description").orElse(subagentType);
        boolean backgroundMode = input.optBool("runInBackground", false);
        String modelOverride = input.optString("model").orElse(null);
        Integer maxTurns = input.optString("maxTurns").map(Integer::parseInt).orElse(null);

        // MVP：拒绝 team 相关参数
        if (input.optString("name").isPresent() || input.optString("teamName").isPresent())
            throw new ToolException("Team collaboration is not available in this build. "
                + "Use runInBackground=true for parallel subagents instead.");

        // 1) 解析人格
        AgentInfo agent = agents.resolve(subagentType)
                .orElseThrow(() -> new ToolException("Unknown subagent_type '%s'. Available: %s"
                        .formatted(subagentType, String.join(", ", agents.names()))));

        // 2) 权限询问
        ctx.gate().ask(PermissionName.AGENT, List.of(subagentType),
                "Start %s subagent: %s".formatted(backgroundMode ? "background" : "foreground", description),
                Map.of("subagentType", subagentType, "description", description,
                       "background", backgroundMode, "promptPreview", Texts.truncate(prompt, 500)),
                List.of(subagentType));

        // 3) 创建子 Session
        String childId = sessions.create(CreateSessionCommand.builder()
                .workdir(ctx.workdir()).parentId(ctx.sessionId()).incognito(false)
                .agentName(agent.name()).build()).id();

        // 4) 工具 overlay：屏蔽 Agent/Team
        overlays.set(childId, SessionToolOverlay.builder()
                .shadowedToolNames(CHILD_RESTRICTED)
                .build());
        // 5) 更严格的运行时权限（子 Agent 不得再改权限、不得建定时任务）
        sessions.appendRuntimePermissionRules(childId, List.of(
                new PermissionRule(PermissionName.AGENT, "*", Action.DENY),
                new PermissionRule(PermissionName.TODOWRITE, "*", Action.DENY),
                new PermissionRule(PermissionName.CRON, "*", Action.DENY)));

        // 6) 模型选择：显式 > 人格 tier > 父会话最后使用
        ModelRef ref = modelOverride != null ? ModelRef.parse(modelOverride)
                : agent.modelTier() != null ? ctx.settings().tier(agent.modelTier())
                : ctx.session().lastModelRef();

        Path outputFile = paths.agentOutputFile(childId);

        // 7a) 后台模式
        if (backgroundMode) {
            BackgroundTask task = background.startAgent(StartAgentCommand.builder()
                    .agentId(childId).childSessionId(childId).parentSessionId(ctx.sessionId())
                    .agentInfo(agent).prompt(prompt).modelRef(ref).maxTurns(maxTurns)
                    .outputFile(outputFile).description(description)
                    .parentAbort(ctx.abort()).build());
            return ToolResult.text("""
                Started background agent.
                agent_id: %s
                description: %s
                output_file: %s

                It runs in an isolated context window and you will be notified automatically \
                when it completes. Meanwhile, continue with other work — do NOT poll or sleep.
                Use TaskOutput(task_id="%s", block=false) to peek at progress, \
                TaskStop(task_id="%s") to terminate."""
                .formatted(childId, description, outputFile, childId, childId));
        }

        // 7b) 前台模式：同步等待，同时镜像父会话 abort
        AgentOutputWriter writer = new AgentOutputWriter(outputFile, childId, bus);
        writer.start();
        try {
            LoopOutcome outcome = facade.runChildSession(ChildSessionCommand.builder()
                    .sessionId(childId).agentInfo(agent).prompt(prompt).modelRef(ref)
                    .maxTurns(maxTurns).lane(RuntimeLane.SIDE_AGENT)
                    .abort(ctx.abort().child()).build());
            String result = sessions.lastAssistantText(childId);
            return new ToolResult(List.of(ContentBlock.of("""
                Subagent '%s' finished (%s, %d steps, %d tokens).

                Result:
                %s

                Full transcript: %s""".formatted(subagentType, outcome.reason(), outcome.steps(),
                        outcome.tokens().total() == null ? 0 : outcome.tokens().total(),
                        result, outputFile))),
                    Map.of("agentId", childId, "status", outcome.reason().name(),
                           "steps", outcome.steps(), "outputFile", outputFile.toString()), List.of());
        } finally { writer.close(); }
    }
}

/** 子 Agent 输出 JSONL 落盘（供 TaskOutput 尾随与 Web 面板实时跟随） */
@RequiredArgsConstructor
public final class AgentOutputWriter implements AutoCloseable {

    private final Path file;
    private final String childSessionId;
    private final Bus bus;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(4096);
    private volatile boolean closed;
    private Subscription sub1, sub2;
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicLong tokens = new AtomicLong();

    public void start() {
        Files.createDirectories(file.getParent());
        // 订阅子会话的 message / part 事件
        sub1 = bus.subscribe(MessageUpdated.class, e -> {
            if (childSessionId.equals(e.sessionId())) offer(Jsons.write(Map.of("type", "message", "data", e.message())));
        });
        sub2 = bus.subscribe(MessagePartUpdated.class, e -> {
            if (!childSessionId.equals(e.sessionId())) return;
            offer(Jsons.write(Map.of("type", "part", "data", e.part())));
            if (e.part() instanceof ToolPart t && t.state() instanceof ToolState.Completed) toolCalls.incrementAndGet();
        });
        // 单写入虚拟线程：串行 append + flush
        Thread.ofVirtual().name("we0j-agent-output-" + childSessionId).start(() -> {
            try (BufferedWriter w = Files.newBufferedWriter(file, CREATE, WRITE, APPEND)) {
                while (!closed || !queue.isEmpty()) {
                    String line = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (line == null) continue;
                    w.write(line); w.newLine(); w.flush();
                    if (Files.size(file) > 10 * 1024 * 1024) {
                        w.write("{\"type\":\"truncated\",\"reason\":\"output exceeded 10MB\"}");
                        w.newLine(); break;
                    }
                }
            } catch (IOException | InterruptedException e) { /* 记录并退出 */ }
        });
    }
    private void offer(String line) { if (!queue.offer(line)) { /* 有界队列满：丢弃最旧的 part delta */ } }
    public int toolCalls() { return toolCalls.get(); }
    @Override public void close() {
        closed = true;
        if (sub1 != null) sub1.unsubscribe();
        if (sub2 != null) sub2.unsubscribe();
    }
}
```

---

### 5.13 Todo / Task 服务（FR-076 / FR-077）

```java
@Service
@RequiredArgsConstructor
public final class TodoService {

    private final JsonFileStore store;
    private final PathResolver paths;
    private final Bus bus;

    public List<TodoItem> load(String sessionId) {
        return store.read(paths.todosFile(sessionId), new TypeReference<>() {}, List.of());
    }

    /** 整表覆盖写入 + 校验"至多一个 in_progress" */
    public List<TodoItem> write(String sessionId, List<TodoItem> todos) {
        long inProgress = todos.stream().filter(t -> t.status() == TodoStatus.IN_PROGRESS).count();
        String warning = inProgress > 1
            ? "\n\nWarning: %d items are marked in_progress. Work on one item at a time."
                .formatted(inProgress) : "";

        List<TodoItem> saved = store.update(paths.todosFile(sessionId), new TypeReference<>() {}, List.of(),
                ignored -> List.copyOf(todos));
        bus.publish(new TodoUpdated(sessionId, saved, bus.nextSeq()));
        return saved;
    }
}

@We0Tool(name = ToolNames.TODO_WRITE, permission = PermissionName.TODOWRITE, deferLoading = false)
public final class TodoWriteTool implements Tool { /* 委托 TodoService */ }

@We0Tool(name = ToolNames.TODO_READ, permission = PermissionName.TODOREAD, deferLoading = false)
public final class TodoReadTool implements Tool { /* 委托 TodoService */ }
```

```java
@Service
@RequiredArgsConstructor
public final class TaskService {

    private final JsonFileStore store;
    private final PathResolver paths;
    private final Bus bus;

    public List<TaskV2> load(String sessionId) {
        return store.read(paths.tasksFile(sessionId), new TypeReference<>() {}, List.of());
    }

    public TaskV2 create(String sessionId, CreateTaskCommand cmd) {
        return store.update(paths.tasksFile(sessionId), TASKS_TYPE, List.of(), list -> {
            List<TaskV2> next = new ArrayList<>(list);
            TaskV2 t = new TaskV2(Ulids.next(), cmd.subject(), cmd.description(), cmd.activeForm(),
                    null, TaskStatus.PENDING, List.of(), List.of(),
                    cmd.metadata() == null ? Map.of() : cmd.metadata(),
                    Instant.now(), Instant.now(), List.of());
            next.add(t);
            publish(sessionId, next);
            return List.copyOf(next);
        }).stream().filter(t -> t.subject().equals(cmd.subject())).findFirst().orElseThrow();
    }

    /**
     * 更新。★ blocks / blockedBy 双向维护：
     *   A.addBlocks(B) → A.blocks += B 且 B.blockedBy += A
     *   A.addBlockedBy(B) → A.blockedBy += B 且 B.blocks += A
     * 依赖是建议性的，运行时不强制阻塞（FR-077）。
     */
    public TaskV2 update(String sessionId, String taskId, UpdateTaskCommand cmd) {
        List<TaskV2> result = store.update(paths.tasksFile(sessionId), TASKS_TYPE, List.of(), list -> {
            Map<String, TaskV2> byId = new LinkedHashMap<>();
            list.forEach(t -> byId.put(t.id(), t));
            TaskV2 target = byId.get(taskId);
            if (target == null) throw new ToolException("Task not found: " + taskId);

            TaskV2 updated = target;
            if (cmd.status() != null) {
                if (cmd.status() == TaskStatus.DELETED) byId.remove(taskId);
                else updated = updated.withStatus(cmd.status()).withTimeUpdated(Instant.now());
            }
            if (cmd.subject() != null) updated = updated.withSubject(cmd.subject());
            if (cmd.description() != null) updated = updated.withDescription(cmd.description());
            if (cmd.activeForm() != null) updated = updated.withActiveForm(cmd.activeForm());
            if (cmd.owner() != null) updated = updated.withOwner(cmd.owner());
            if (cmd.metadata() != null) {
                Map<String,Object> m = new LinkedHashMap<>(updated.metadata());
                cmd.metadata().forEach((k, v) -> { if (v == null) m.remove(k); else m.put(k, v); });
                updated = updated.withMetadata(Map.copyOf(m));
            }
            if (cmd.comment() != null) {
                updated = updated.withComments(concat(updated.comments(),
                        List.of(new TaskComment(Ulids.next(), cmd.commentAuthor(), cmd.comment(), Instant.now()))));
            }

            // 双向依赖维护
            if (cmd.addBlocks() != null) for (String otherId : cmd.addBlocks()) {
                TaskV2 other = byId.get(otherId);
                if (other == null) continue;
                updated = updated.withBlocks(union(updated.blocks(), List.of(otherId)));
                byId.put(otherId, other.withBlockedBy(union(other.blockedBy(), List.of(taskId))));
            }
            if (cmd.addBlockedBy() != null) for (String otherId : cmd.addBlockedBy()) {
                TaskV2 other = byId.get(otherId);
                if (other == null) continue;
                updated = updated.withBlockedBy(union(updated.blockedBy(), List.of(otherId)));
                byId.put(otherId, other.withBlocks(union(other.blocks(), List.of(taskId))));
            }

            byId.put(taskId, updated);
            List<TaskV2> next = List.copyOf(byId.values());
            publish(sessionId, next);
            return next;
        });
        return result.stream().filter(t -> t.id().equals(taskId)).findFirst().orElse(null);
    }

    /** TaskList 返回摘要（不含 description / comments，省 token） */
    public List<TaskSummary> list(String sessionId) {
        return load(sessionId).stream()
                .filter(t -> t.status() != TaskStatus.DELETED)
                .map(t -> new TaskSummary(t.id(), t.subject(), t.status(), t.owner(), openBlockedBy(t, sessionId)))
                .toList();
    }
    /** blockedBy 只返回仍处于未完成状态的依赖（对齐原项目语义） */
    private List<String> openBlockedBy(TaskV2 t, String sessionId) {
        Set<String> done = load(sessionId).stream()
                .filter(x -> x.status() == TaskStatus.COMPLETED || x.status() == TaskStatus.DELETED)
                .map(TaskV2::id).collect(toSet());
        return t.blockedBy().stream().filter(id -> !done.contains(id)).toList();
    }
    private void publish(String sessionId, List<TaskV2> tasks) {
        tasks.forEach(t -> bus.publish(new TaskUpdated(sessionId, t, bus.nextSeq())));
    }
}
```

**TaskOutput / TaskStop**：

```java
@We0Tool(name = ToolNames.TASK_OUTPUT, permission = PermissionName.TASK_OUTPUT)
@RequiredArgsConstructor
public final class TaskOutputTool implements Tool {

    private final BackgroundTaskManager tasks;
    private final ShellManager shells;

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String taskId = input.requireString("task_id");
        boolean block = input.optBool("block", true);
        int timeoutMs = input.optInt("timeout", 30_000);

        BackgroundTask task = tasks.find(taskId).orElseThrow(() ->
                new ToolException("Task not found: %s. Use TaskList to see available tasks.".formatted(taskId)));

        String status = task.status().name().toLowerCase(Locale.ROOT);
        if (block && task.status() == BackgroundTaskStatus.RUNNING) {
            try {
                task = tasks.completionOf(taskId).get(timeoutMs, TimeUnit.MILLISECONDS);
                status = task.status().name().toLowerCase(Locale.ROOT);
            } catch (TimeoutException e) { status = "running (wait timed out after " + timeoutMs + "ms)"; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AbortedException("interrupted"); }
            catch (ExecutionException e) { status = "failed: " + e.getCause().getMessage(); }
        }

        String output = task.type() == BackgroundTaskType.SHELL
                ? shells.tail(taskId, OutputTruncator.MAX_BYTES)
                : readJsonlTail(task.outputFile(), OutputTruncator.MAX_BYTES);

        String text = """
            <retrieval_status>
            <task_id>%s</task_id>
            <status>%s</status>
            <type>%s</type>
            <description>%s</description>
            <output_file>%s</output_file>
            </retrieval_status>

            <output>
            %s
            </output>""".formatted(taskId, status, task.type().wire(),
                    task.description() == null ? "" : task.description(), task.outputFile(), output);

        return new ToolResult(List.of(ContentBlock.of(text)),
                Map.of("taskId", taskId, "status", status, "type", task.type().wire()), List.of());
    }
}

@We0Tool(name = ToolNames.TASK_STOP, permission = PermissionName.TASK_STOP)
@RequiredArgsConstructor
public final class TaskStopTool implements Tool {
    private final BackgroundTaskManager tasks;
    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        String taskId = input.requireString("task_id");
        boolean ok = tasks.cancel(taskId);
        return ToolResult.text(ok
            ? "Task %s stopped. Its threads and child processes have been terminated.".formatted(taskId)
            : "Failed to stop task %s: not found or already finished.".formatted(taskId));
    }
}
```

---

### 5.14 Agent 人格注册表

```java
/**
 * 对齐原项目 core/agent/agent.py（AgentRegistry + AgentMarkdownParser）。
 * ★ 注意：这不是 Agent Loop，是人格定义注册表。
 * 磁盘格式：<agentsDir>/<name>.md，YAML frontmatter + 正文提示词。
 */
@Component
@RequiredArgsConstructor
public final class AgentRegistry {

    private final YamlFrontmatterParser frontmatter;
    private final PathResolver paths;
    private final Map<String, AgentInfo> builtin = new LinkedHashMap<>();

    /** 内置人格：build（默认执行）/ plan（只读规划）/ explore（快速检索）/ compaction（压缩专用） */
    @PostConstruct
    void initBuiltin() {
        builtin.put("build", new AgentInfo("build", "Default coding agent with full tool access.",
                load("/agents/build.md"), List.of(), List.of(), null, null, AgentKind.BUILTIN, null));
        builtin.put("plan", new AgentInfo("plan", "Read-only software architect for designing plans.",
                load("/agents/plan.md"), List.of("Read","Grep","Glob","LSP","TaskCreate","TaskUpdate","TaskList","AskUserQuestion"),
                List.of(new PermissionRule(PermissionName.WRITE, "*", Action.DENY),
                        new PermissionRule(PermissionName.EDIT, "*", Action.DENY),
                        new PermissionRule(PermissionName.BASH, "*", Action.DENY)),
                null, null, AgentKind.BUILTIN, null));
        builtin.put("explore", new AgentInfo("explore", "Fast read-only search agent for locating code.",
                load("/agents/explore.md"), List.of("Read","Grep","Glob","Bash"),
                List.of(new PermissionRule(PermissionName.WRITE, "*", Action.DENY),
                        new PermissionRule(PermissionName.EDIT, "*", Action.DENY)),
                "fast", null, AgentKind.BUILTIN, null));
        builtin.put("compaction", new AgentInfo("compaction", "Internal summarizer.",
                load("/agents/compaction.md"), List.of(),                     // ★ 无工具
                List.of(new PermissionRule(PermissionName.ALL, "*", Action.DENY)),
                "fast", 1, AgentKind.BUILTIN, null));
    }

    /** 分层解析：builtin ← global(~/.we0j/agents) ← project(<proj>/.we0j/agents)，后者覆盖 */
    public Optional<AgentInfo> resolve(String name) {
        if (name == null || name.isBlank()) name = "build";
        Map<String, AgentInfo> all = new LinkedHashMap<>(builtin);
        scanDir(paths.globalAgentsDir(), AgentKind.GLOBAL).forEach(a -> all.put(a.name(), a));
        scanDir(currentProjectAgentsDir(), AgentKind.PROJECT).forEach(a -> all.put(a.name(), a));
        return Optional.ofNullable(all.get(name));
    }

    public Collection<String> names() { /* builtin + 磁盘扫描 */ }

    private List<AgentInfo> scanDir(Path dir, AgentKind kind) {
        if (!Files.isDirectory(dir)) return List.of();
        List<AgentInfo> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path md : s.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                try {
                    FrontmatterResult fr = frontmatter.parse(Files.readString(md, UTF_8));
                    Map<String,String> m = fr.meta();
                    out.add(new AgentInfo(
                            m.getOrDefault("name", baseName(md)),
                            m.getOrDefault("description", ""),
                            fr.body(),
                            splitList(m.get("tools")),
                            parseRules(m.get("permission")),
                            m.get("model"),
                            m.get("steps") == null ? null : Integer.parseInt(m.get("steps")),
                            kind, md));
                } catch (Exception e) { log.warn("failed to parse agent {}: {}", md, e.getMessage()); }
            }
        } catch (IOException ignored) { }
        return out;
    }
}
```

**磁盘格式示例**（`<project>/.we0j/agents/db-reviewer.md`）：
```markdown
---
name: db-reviewer
description: Reviews SQL migrations and index design. Use proactively after any schema change.
tools: Read, Grep, Glob, Bash
model: fast
permission:
  bash: { "git *": allow, "*": ask }
---

You are a database schema reviewer. For every migration you review:
1. Read the migration file and the current schema.
2. Check: missing indexes on new FK columns, nullable-without-default, ...
...
```

---

### 5.15 CLI 层（FR-12）

#### 5.15.1 命令树

```java
@Command(name = "we0j", mixinStandardHelpOptions = true,
        versionProvider = We0jVersionProvider.class,
        description = "We0J — a coding agent runtime for real software engineering tasks.",
        subcommands = {
            SessionCommand.class, TaskCommand.class, SkillCommand.class, ConfigCommand.class,
            ProviderCommand.class, DoctorCommand.class, WebCommand.class, GcCommand.class,
            CompletionCommand.class                                   // picocli 内置补全脚本生成
        })
@RequiredArgsConstructor
public final class We0jCommand implements Callable<Integer> {

    private final ReplRunner repl;
    private final HeadlessRunner headless;
    private final BootstrapInitializer bootstrap;

    @Option(names = {"--resume"}, paramLabel = "<sessionId|last>",
            description = "Resume a session by id, or 'last' for the most recent.")
    String resume;

    @Option(names = {"--workdir", "-C"}, paramLabel = "<path>", description = "Working directory.")
    Path workdir;

    @Option(names = {"--model"}, paramLabel = "<provider/model>", description = "Initial model.")
    String model;

    @Option(names = {"--permission-mode"}, paramLabel = "<mode>",
            description = "ask | allow_once | bypass | reject")
    String permissionMode;

    @Option(names = {"-p", "--prompt"}, paramLabel = "<text>",
            description = "Non-interactive one-shot execution (headless).")
    String prompt;

    @Option(names = {"--output-format"}, defaultValue = "text",
            description = "text | json | stream-json (headless only).")
    String outputFormat;

    @Option(names = {"--allowedTools"}, split = ",",
            description = "Tool whitelist for headless mode.")
    List<String> allowedTools;

    @Option(names = {"--dangerously-skip-permissions"},
            description = "Bypass all permission prompts. Use only in sandboxed CI.")
    boolean skipPermissions;

    @Option(names = {"--no-web"}, description = "Do not start the embedded web console.")
    boolean noWeb;

    @Override
    public Integer call() {
        // ── 引导（UC-01）──────────────────────────────────────────────────
        try { bootstrap.run(workdir == null ? Path.of(".").toAbsolutePath() : workdir); }
        catch (ConfigValidationException e) {
            System.err.println(e.userFacingReport());                // 可操作错误，非堆栈
            return 2;
        }

        Path wd = workdir == null ? Path.of(".").toAbsolutePath().normalize() : workdir.toAbsolutePath();

        // ── headless 分支 ─────────────────────────────────────────────────
        if (prompt != null && !prompt.isBlank())
            return headless.run(HeadlessOptions.of(prompt, wd, model, outputFormat,
                    allowedTools, skipPermissions, resume));

        // ── REPL 分支 ─────────────────────────────────────────────────────
        if (!noWeb) startEmbeddedWebConsole();                       // 后台虚拟线程
        return repl.run(ReplOptions.of(wd, resume, model, permissionMode));
    }
}
```

#### 5.15.2 REPL 与流式渲染

```java
@Component
@RequiredArgsConstructor
public final class ReplRunner {

    private final SessionFacade facade;
    private final StreamingRenderer renderer;
    private final CliPermissionPrompt permissionPrompt;
    private final CliQuestionPrompt questionPrompt;
    private final SlashCommandRegistry slash;
    private final Bus bus;
    private final LineReaderFactory readers;

    public int run(ReplOptions opt) {
        Terminal terminal = readers.terminal();
        LineReader reader = readers.build(terminal, opt.workdir());

        // 1) 会话准备
        String sessionId = opt.resume() != null
                ? facade.resolveResumeRef(opt.resume(), opt.workdir())
                : facade.createSession(opt.workdir());

        // 2) 渲染头部：logo、模型、工作目录、权限模式
        renderer.renderIntro(sessionId, opt);

        // 3) 订阅 Bus → 渲染（★ push 模型，非原项目的轮询快照）
        List<Subscription> subs = List.of(
            bus.subscribe(MessagePartDelta.class, e -> { if (e.sessionId().equals(sessionId)) renderer.delta(e); }),
            bus.subscribe(MessagePartUpdated.class, e -> { if (e.sessionId().equals(sessionId)) renderer.part(e); }),
            bus.subscribe(SessionUpdated.class, e -> { if (e.sessionId().equals(sessionId)) renderer.status(e); }),
            bus.subscribe(PermissionAsked.class, e -> permissionPrompt.onAsked(e, sessionId)),
            bus.subscribe(QuestionAsked.class, e -> questionPrompt.onAsked(e, sessionId)),
            bus.subscribe(TaskUpdated.class, e -> renderer.backgroundTask(e)),
            bus.subscribe(NotificationPushed.class, e -> renderer.notification(e)));

        // 4) 主循环
        AbortSignal currentTurn = null;
        try {
            while (true) {
                renderer.renderPromptLine(sessionId);                    // 含模式/模型指示器
                String line;
                try {
                    line = reader.readLine(new Prompt(""));              // JLine 阻塞读（虚拟线程）
                } catch (UserInterruptException e) {                     // Ctrl+C
                    if (handleCtrlC(e, sessionId, currentTurn)) continue; else break;
                } catch (EndOfFileException e) { break; }                // Ctrl+D

                if (line == null || line.isBlank()) continue;
                reader.getHistory().add(line);

                // 4a) slash command
                if (line.startsWith("/")) {
                    SlashResult r = slash.execute(line, sessionId, opt);
                    if (r instanceof SlashResult.Exit) break;
                    renderer.renderSlashResult(r);
                    continue;
                }

                // 4b) 展开 #text<id> 折叠引用与 @ 文件引用
                String expanded = referenceCodec.expand(line, opt.workdir());

                // 4c) 提交（异步；渲染由 Bus 订阅驱动）
                currentTurn = AbortSignal.create();
                renderer.beginTurn();
                facade.prompt(new PromptInput(sessionId, expanded, attachments.drain(),
                                ChannelSource.CLI, null, null, null, null))
                      .whenComplete((outcome, err) -> renderer.endTurn(outcome, err));
                // ★ REPL 不阻塞等待：用户可继续输入（进入 queuedInputs，FR-028）
                //   但 Esc 需要能中断当前轮 → 记录 currentTurn 供按键分发使用
            }
        } finally {
            subs.forEach(Subscription::unsubscribe);
            facade.cancel(sessionId);
        }
        return 0;
    }
}
```

```java
/**
 * CLI 流式渲染器。
 * ★ 与 Web 的差异：CLI 是行式追加渲染（不做全屏重绘），reasoning 用暗色且可折叠，
 *   工具卡片按状态单行刷新（用 ANSI 光标回退实现"原地更新"）。
 */
@Component
@RequiredArgsConstructor
public final class StreamingRenderer {

    private final Terminal terminal;
    private final MarkdownCliRenderer markdown;
    private final AnsiColors colors;

    /** 当前轮的可变渲染态（单线程消费 Bus，无需同步） */
    private String activeReasoningId, activeTextId;
    private final Map<String, Integer> toolCardLines = new HashMap<>();     // partId → 已打印行数（用于原地刷新）
    private final StringBuilder reasoningBuf = new StringBuilder();
    private final StringBuilder textBuf = new StringBuilder();
    private boolean reasoningCollapsed = true;                              // 默认折叠，展开显示摘要行

    public void delta(MessagePartDelta e) {
        switch (e.field()) {
            case "reasoning" -> {
                reasoningBuf.append(e.delta());
                if (!reasoningCollapsed) printDim(e.delta());
                else updateCollapsedReasoningLine(reasoningBuf.length());   // "Thinking… (1,234 chars)"
            }
            case "text" -> {
                textBuf.append(e.delta());
                // markdown 增量渲染：按行缓冲，完整行才输出（避免半行 markdown 破坏格式）
                markdown.feedIncremental(e.delta());
            }
            default -> { }
        }
    }

    public void part(MessagePartUpdated e) {
        switch (e.part()) {
            case ToolPart t -> renderToolCard(t);
            case ReasoningPart r -> { if (r.time().end() != null) finishReasoning(r); }
            case TextPart t -> { if (t.time() != null && t.time().end() != null) markdown.flush(); }
            case StepFinishPart s -> renderUsage(s);
            case CompactionPart c -> printInfo("Context compacted. %d messages summarized."
                    .formatted(c.metadata().messagesSummarized()));
            default -> { }
        }
    }

    /**
     * 工具卡片渲染（FR-134 的 CLI 简化版）：
     *   running   → "⠋ Edit src/Foo.java …"        （spinner 帧 + 单行原地刷新）
     *   completed → "✓ Edit src/Foo.java (+12 -3)"  （绿）
     *   error     → "✗ Edit src/Foo.java — <error>" （红）
     *   denied    → "⊘ Bash rm -rf — denied"        （黄）
     * 原地刷新实现：ANSI "\r" + "\033[K"（清行）；多行卡片用 "\033[<n>A"（上移 n 行）
     */
    private void renderToolCard(ToolPart t) {
        ToolState s = t.state();
        String title = titleOf(t);
        String line = switch (s) {
            case ToolState.Pending p   -> spinnerFrame() + " " + t.toolName() + " " + title + " …";
            case ToolState.Running r   -> spinnerFrame() + " " + t.toolName() + " " + title + " …";
            case ToolState.Completed c -> colors.green("✓") + " " + t.toolName() + " " + title
                                        + summarizeCompleted(t, c);
            case ToolState.Error e     -> colors.red("✗") + " " + t.toolName() + " " + title
                                        + " — " + colors.red(Texts.truncate(e.error(), 120));
        };
        // 已渲染过该卡片 → 原地更新；否则新起一行
        Integer prevLines = toolCardLines.get(t.id());
        if (prevLines != null) moveCursorUp(prevLines);
        terminal.writer().println(colors.clearToEndOfLine() + line);
        // Edit 完成且有 diff → 追加 diff（audience=USER 的内容）
        if (s instanceof ToolState.Completed c && prevLines == null && hasUserDiff(c)) {
            printDiff(c.metadata().get("diff").toString());
        }
        toolCardLines.put(t.id(), countRenderedLines(line));
    }

    private String summarizeCompleted(ToolPart t, ToolState.Completed c) {
        Object add = c.metadata().get("additions"), del = c.metadata().get("deletions");
        if (add != null && del != null) return " (+%s -%s)".formatted(add, del);
        if ("Bash".equals(t.toolName())) return " (exit %s)".formatted(c.metadata().getOrDefault("exitCode", 0));
        if ("Grep".equals(t.toolName())) return " (%s matches)".formatted(c.metadata().getOrDefault("matchCount", 0));
        if ("Read".equals(t.toolName())) return " (%s lines)".formatted(c.metadata().getOrDefault("linesShown", 0));
        return "";
    }
}
```

**JLine 补全器**：

```java
@Component
@RequiredArgsConstructor
public final class FileMentionCompleter implements Completer {

    private final RipgrepClient ripgrep;

    /**
     * `@` 文件模糊补全 + `#L10-20` 行区间选择器。
     * 候选源：ripgrep --files（尊重 .gitignore）→ 降级 git ls-files → 降级 Files.walk
     */
    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();
        int at = buffer.lastIndexOf('@', line.cursorPosition() - 1);
        if (at < 0) return;
        String prefix = buffer.substring(at + 1, line.cursorPosition());
        if (prefix.contains(" ")) return;                              // 已离开 @ 引用范围

        Path workdir = currentWorkdir();
        List<String> files = listFiles(workdir);                       // 带 5s 缓存

        // 模糊匹配：子序列匹配 + 路径分段首字母加分
        files.stream()
             .map(f -> new FuzzyMatch(f, FuzzyScore.score(prefix, f)))
             .filter(m -> m.score() > 0)
             .sorted(comparingDouble(FuzzyMatch::score).reversed())
             .limit(50)
             .forEach(m -> candidates.add(new Candidate(
                     m.path(),                                          // value
                     m.path(),                                          // display
                     "file",                                            // group
                     null, null, null, true)));
    }

    private List<String> listFiles(Path workdir) {
        return fileCache.get(workdir, w ->
            ripgrep.locate().map(rg -> ProcessRunner.run(List.of(rg.toString(), "--files"), w, D5).stdout()
                        .lines().filter(s -> !s.isBlank()).limit(20_000).toList())
               .orElseGet(() -> gitLsFiles(w).orElseGet(() -> nioWalk(w))));
    }
}
```

#### 5.15.3 Headless 模式

```java
@Component
@RequiredArgsConstructor
public final class HeadlessRunner {

    private final SessionFacade facade;
    private final BootstrapInitializer bootstrap;

    public int run(HeadlessOptions opt) {
        String sessionId = opt.resume() != null ? facade.resolveResumeRef(opt.resume(), opt.workdir())
                                                : facade.createSession(opt.workdir());
        // 权限模式：--dangerously-skip-permissions → BYPASS；否则用 allowedTools 白名单 + REJECT 兜底
        PermissionMode mode = opt.skipPermissions() ? PermissionMode.BYPASS : PermissionMode.REJECT;
        if (opt.allowedTools() != null && !opt.allowedTools().isEmpty()) {
            facade.setToolWhitelist(sessionId, opt.allowedTools());
            mode = PermissionMode.ALLOW_ONCE;                  // 白名单内自动放行，白名单外拒绝
        }
        facade.setPermissionMode(sessionId, mode);

        OutputWriter writer = switch (opt.outputFormat()) {
            case "json" -> new JsonOutputWriter(System.out);
            case "stream-json" -> new StreamJsonOutputWriter(System.out);
            default -> new TextOutputWriter(System.out);
        };

        // stream-json：订阅 Bus，逐行输出 JSON 事件（用于 CI/管道集成）
        List<Subscription> subs = writer instanceof StreamJsonOutputWriter sj
                ? sj.attach(bus, sessionId) : List.of();

        try {
            LoopOutcome outcome = facade.prompt(new PromptInput(sessionId, opt.prompt(), List.of(),
                    ChannelSource.HEADLESS, null, opt.model() == null ? null : ModelRef.parse(opt.model()),
                    null, null)).get(opt.timeout().toMillis(), TimeUnit.MILLISECONDS);
            writer.writeOutcome(sessionId, outcome, facade.lastAssistantText(sessionId));
            return outcome.reason() == LoopExitReason.COMPLETED_REPLY ? 0 : 1;
        } catch (TimeoutException e) {
            facade.cancel(sessionId);
            writer.writeError("timeout after " + opt.timeout());
            return 124;
        } catch (Exception e) {
            writer.writeError(e.getMessage());
            return 1;
        } finally { subs.forEach(Subscription::unsubscribe); }
    }
}
```

**`stream-json` 输出协议**（每行一个 JSON 对象）：
```jsonl
{"type":"system","subtype":"init","sessionId":"01J...","model":"anthropic/claude-sonnet-4-5","tools":["Read","Edit",...],"permissionMode":"allow_once"}
{"type":"assistant","message":{"role":"assistant","parts":[{"type":"text","text":"I'll look at..."}]}}
{"type":"tool_use","toolName":"Grep","input":{"pattern":"UserService"},"callId":"toolu_01"}
{"type":"tool_result","callId":"toolu_01","output":"...","isError":false}
{"type":"usage","tokens":{"input":1234,"output":567,"cacheRead":890},"cost":0.0123}
{"type":"result","subtype":"success","exitReason":"COMPLETED_REPLY","steps":4,"text":"Done. I changed..."}
```

---

### 5.16 Web 控制台（FR-13）

#### 5.16.1 Spring Boot 配置

```java
@SpringBootApplication(scanBasePackages = "com.we0j")
@EnableConfigurationProperties
@EnableScheduling
public class We0jServerApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(We0jServerApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        // ★ 无头模式：作为 CLI 内嵌控制台启动时不打印 Spring banner/日志噪音
        System.setProperty("spring.main.log-startup-info", "false");
        app.run(args);
    }
}
```

```yaml
# application.yml
server:
  port: ${WE0J_WEB_PORT:8787}
  address: 127.0.0.1                # ★ 仅 loopback（NFR-04）
  shutdown: graceful
  tomcat:
    threads:
      max: 200                      # 虚拟线程下仅作连接接受上限
    max-connections: 2000

spring:
  threads:
    virtual:
      enabled: true                 # ★ MVC 请求走虚拟线程（阻塞式编程模型 + 高并发）
  main:
    banner-mode: off
    lazy-initialization: false      # Bus/Registry 需即时初始化
  jackson:
    default-property-inclusion: non_null
    property-naming-strategy: LOWER_CAMEL_CASE
    serialization:
      write-dates-as-timestamps: false
  mvc:
    async:
      request-timeout: -1           # ★ SSE 永不超时
  web:
    resources:
      static-locations: classpath:/static/
      cache:
        cachecontrol:
          max-age: 3600
  flyway:
    enabled: true
    locations: classpath:db/migration
  jpa:
    open-in-view: false
    hibernate.ddl-auto: validate
    properties:
      hibernate.jdbc.batch_size: 50
      hibernate.order_inserts: true
      hibernate.order_updates: true

logging:
  level:
    root: INFO
    com.we0j: INFO
    org.hibernate.SQL: WARN
  pattern:
    console: "%d{HH:mm:ss.SSS} %highlight(%-5level) [%thread] %cyan(%logger{24}) - %msg%n"
```

#### 5.16.2 SSE 订阅端（★ 背压与重放）

```java
/**
 * SSE 订阅端。关键设计：
 *  1) 每个连接一个有界队列 + 一个虚拟线程泵（Bus 分发线程只 offer，不阻塞）
 *  2) 队列满时：丢弃非 critical 事件（part.delta），critical 事件阻塞等待（保证不丢权限/提问）
 *  3) 环形重放缓冲（最近 200 条），支持 Last-Event-ID 断线重连补发
 *  4) 心跳：每 15s 发 comment 行，防代理断连
 */
@Component
@RequiredArgsConstructor
public final class SseSubscriber {

    private static final int QUEUE_CAPACITY = 1000;
    private static final int REPLAY_CAPACITY = 200;

    private final Bus bus;
    private final ObjectMapper mapper;
    /** sessionId → 环形重放缓冲 */
    private final ConcurrentMap<String, ArrayDeque<ServerSentEvent<String>>> replayBuffers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Connection>> connections = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String sessionId, String lastEventId, long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs <= 0 ? 0L : timeoutMs);   // 0 = 永不超时
        Connection conn = new Connection(sessionId, emitter,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY));

        // 1) 断线重连补发
        if (lastEventId != null && !lastEventId.isBlank()) {
            replay(sessionId, Long.parseLong(lastEventId)).forEach(ev -> sendQuietly(emitter, ev));
        }

        // 2) 注册 Bus 订阅（Bus 分发线程调用 conn.offer，非阻塞）
        conn.subscription = bus.subscribeAll(e -> {
            if (e.sessionId() != null && !e.sessionId().equals(sessionId)) return;
            conn.offer(e);
        });

        // 3) 泵线程：从队列取事件 → 写 SSE（阻塞写在虚拟线程上，安全）
        Thread.ofVirtual().name("we0j-sse-" + conn.id).start(() -> pump(conn));

        // 4) 心跳
        conn.heartbeat = scheduler.scheduleAtFixedRate(() -> {
            try { emitter.send(SseEmitter.event().comment("hb")); }
            catch (Exception e) { conn.close(); }
        }, 15, 15, TimeUnit.SECONDS);

        emitter.onCompletion(conn::close);
        emitter.onTimeout(conn::close);
        emitter.onError(t -> conn.close());

        connections.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(conn);
        return emitter;
    }

    private void pump(Connection conn) {
        try {
            while (!conn.closed.get()) {
                BusEvent e = conn.queue.poll(1, TimeUnit.SECONDS);
                if (e == null) continue;
                ServerSentEvent<String> sse = toSse(e);
                record(conn.sessionId, sse);                      // 存入重放缓冲
                conn.emitter.send(sse);
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (Exception ex) { log.debug("sse pump ended conn={}", conn.id, ex); }
        finally { conn.close(); }
    }

    /**
     * 入队策略（FR-112 背压）：
     *  - 队列未满 → offer 成功
     *  - 队列满 + 非 critical（part.delta）→ 丢弃，并置 droppedCounter，下次成功发送时附"省略 N 条"提示
     *  - 队列满 + critical（permission.asked / question.asked / message.updated）→ 阻塞 offer（最多 5s）
     */
    void offer(BusEvent e) {
        if (closed.get()) return;
        if (queue.offer(e)) return;
        if (!e.critical()) { dropped.incrementAndGet(); return; }
        try {
            if (!queue.offer(e, 5, TimeUnit.SECONDS))
                log.error("CRITICAL bus event dropped (queue full): topic={} session={}", e.topic(), e.sessionId());
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private ServerSentEvent<String> toSse(BusEvent e) throws JsonProcessingException {
        return ServerSentEvent.<String>builder()
                .id(String.valueOf(e.seq()))
                .event(e.topic())                                  // 点分主题名作为 SSE event 名
                .data(mapper.writeValueAsString(e))
                .build();
    }
}
```

#### 5.16.3 前端架构

```
we0j-web/                            (Vite 项目，构建产物打进 we0j-server/src/main/resources/static)
├── src
│   ├── main.ts
│   ├── App.vue
│   ├── api/
│   │   ├── client.ts                fetch 封装 + Bearer token 注入 + 错误归一
│   │   └── sse.ts                   EventSource 封装 + 断线重连 + Last-Event-ID + 事件解码为 TS 联合类型
│   ├── stores/
│   │   ├── session.ts               Pinia：消息树、Part 索引、流式增量合并
│   │   ├── events.ts                Bus 事件分发到各 store（单一订阅点）
│   │   ├── permission.ts            pending 权限请求队列
│   │   ├── question.ts              pending 提问队列
│   │   ├── tasks.ts                 后台任务
│   │   ├── settings.ts              配置/provider/model
│   │   └── status.ts                用量/状态/lane
│   ├── components/
│   │   ├── MessageList.vue          虚拟滚动（@tanstack/vue-virtual），万级 Part 不卡
│   │   ├── parts/
│   │   │   ├── TextPart.vue         marked + DOMPurify 渲染 markdown
│   │   │   ├── ReasoningPart.vue    可折叠暗色块，流式打字机效果
│   │   │   ├── ToolPart.vue         按 toolName 分发到具体卡片
│   │   │   ├── StepFinishPart.vue   用量条
│   │   │   └── CompactionPart.vue   压缩边界分隔线
│   │   ├── tools/
│   │   │   ├── EditCard.vue         diff 视图（diff2html，并排/统一切换）
│   │   │   ├── BashCard.vue         终端风格 + ansi_up 解析 ANSI
│   │   │   ├── ReadCard.vue         shiki 语法高亮 + 行号
│   │   │   ├── GrepCard.vue         结果表格 + 文件分组 + 跳转
│   │   │   ├── GlobCard.vue         路径列表
│   │   │   ├── AgentCard.vue        子 Agent 进度 + 输出尾随
│   │   │   ├── TaskCard.vue         任务看板
│   │   │   └── ToolSearchCard.vue   激活的工具标签
│   │   ├── Composer.vue             输入框：@ 文件补全、/ 命令补全、图片粘贴、多行
│   │   ├── PermissionDialog.vue     权限弹窗（diff 预览 + once/always/reject + 附加说明输入）
│   │   ├── QuestionDialog.vue       提问弹窗（单选/多选 + preview 侧栏 markdown + 自由输入）
│   │   ├── StatusBar.vue            状态/lane/上下文占用进度条/成本
│   │   ├── Sidebar.vue              Todo 列表 + Task 列表 + 后台任务
│   │   └── panels/
│   │       ├── ModelPanel.vue  ProviderPanel.vue  ConfigPanel.vue  ResumePanel.vue
│   │       ├── RewindPanel.vue TasksPanel.vue     McpPanel.vue     StatusPanel.vue
│   │       ├── ThemePanel.vue  PermissionPanel.vue TodosPanel.vue
│   └── router/index.ts              hash 路由：#/model #/provider ...
└── vite.config.ts                   base: './'（相对路径，便于 jar 内嵌）
```

**前端流式增量合并逻辑**（`stores/session.ts` 核心）：
```ts
// message.part.delta 是高频事件，必须高效合并
function onPartDelta(e: MessagePartDelta) {
  const msg = messages.value[e.messageId]
  if (!msg) return                                  // 尚未收到 part.updated，先缓存到 pendingDeltas
  const part = msg.parts[e.partId]
  if (!part) return
  // ★ 直接字符串拼接（Vue 3 的 reactive 对 string 字段赋值是 O(1)）
  if (e.field === 'text') part.text = (part.text ?? '') + e.delta
  else if (e.field === 'reasoning') part.text = (part.text ?? '') + e.delta
  else if (e.field === 'toolInput') part.state.raw = (part.state.raw ?? '') + e.delta
  // ★ 节流重渲染：markdown 渲染放到 requestAnimationFrame 批处理，避免每个 delta 触发一次
  scheduleRender(e.partId)
}

let rafPending = new Set<string>()
function scheduleRender(partId: string) {
  rafPending.add(partId)
  if (rafPending.size === 1) requestAnimationFrame(() => {
    const ids = [...rafPending]; rafPending.clear()
    ids.forEach(markDirty)        // 触发对应组件重渲染
  })
}
```

---

### 5.17 可观测性（FR-16）

```java
/** 每次模型请求一条 trace 记录，落 JSONL */
public record LlmTraceRecord(
        Instant time, String requestId, String sessionId, String messageId,
        String provider, String model, RuntimeLane lane,
        int messageCount, int toolCount, int estimatedTokens,
        Integer realInputTokens, Integer realOutputTokens,
        Integer cacheReadTokens, Integer cacheWriteTokens,
        long ttftMs, long totalMs, int attempts, boolean cacheCold,
        BigDecimal cost, String finishReason, String error) {}

@Component
@RequiredArgsConstructor
public final class LlmTracer {

    private final PathResolver paths;
    private final ObjectMapper mapper;
    private final BlockingQueue<LlmTraceRecord> queue = new LinkedBlockingQueue<>(2048);
    private final List<ObservabilityExporter> exporters;         // SPI，默认空实现
    /** requestId → 请求前信息 */
    private final ConcurrentMap<String, RequestStart> starts = new ConcurrentHashMap<>();

    @PostConstruct void startWriter() {
        Thread.ofVirtual().name("we0j-llm-trace").start(() -> {
            Path file = paths.llmTraceFile();
            try (BufferedWriter w = Files.newBufferedWriter(file, CREATE, WRITE, APPEND)) {
                while (true) {
                    LlmTraceRecord r = queue.poll(1, TimeUnit.SECONDS);
                    if (r == null) continue;
                    w.write(mapper.writeValueAsString(r)); w.newLine(); w.flush();
                    exporters.forEach(e -> safeExport(e, r));
                    // 轮转：> 10MB 则重命名归档，保留最近 5 份
                    if (Files.size(file) > 10 * 1024 * 1024) rotate(file);
                }
            } catch (Exception e) { log.warn("llm trace writer stopped", e); }
        });
    }

    public String beforeRequest(ChatRequest req, ChatRequest effective) {
        String requestId = Ulids.next();
        starts.put(requestId, new RequestStart(Instant.now(), req, effective,
                MDC.get(MdcKeys.SESSION_ID), RuntimeLaneRegistry.current()));
        return requestId;
    }

    public void afterStream(String requestId, TokenUsage usage, long ttftNs, long totalNs,
                            int attempts, String finishReason, Throwable error, ModelCard card) {
        RequestStart s = starts.remove(requestId);
        if (s == null) return;
        Tokens tokens = usage == null ? Tokens.empty() : usage.toTokens();
        LlmTraceRecord r = new LlmTraceRecord(Instant.now(), requestId, s.sessionId(), null,
                card.providerId(), card.id(), s.lane(),
                s.effective().messages().size(), s.effective().tools().size(),
                tokenCounter.countRequest(s.effective(), card),
                tokens.input(), tokens.output(), tokens.cache().read(), tokens.cache().write(),
                ttftNs / 1_000_000, totalNs / 1_000_000, attempts, tokens.isCacheCold(),
                costCalculator.cost(tokens, card), finishReason,
                error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage());
        queue.offer(r);                                  // 有界队列，满则丢弃（可观测不得拖垮主流程）
    }
}

/** 导出器 SPI（P2 接 Langfuse / OTel） */
public interface ObservabilityExporter {
    String name();
    void export(LlmTraceRecord record);
}
```

**`/status` 面板数据聚合**：
```java
public record StatusSnapshot(
        String sessionId, String sessionTitle, SessionStatus status, RuntimeLane lane,
        int step, String agentName, PermissionMode permissionMode, String modelRef,
        ContextUsage contextUsage, Tokens totalTokens, BigDecimal totalCost,
        double cacheHitRate, int activeToolCount, int deferredToolCount,
        int backgroundTaskCount, int snapshotCount, Instant startedAt,
        List<TodoItem> todos, int pendingPermissionCount, int pendingQuestionCount) {}

public record ContextUsage(int used, int window, double ratio, String state) {
    // state: "ok"(<70%) | "warn"(70-85%) | "critical"(>85%) | "compacting"
}
```

---

## 6. 关键流程时序

### 6.1 一次完整的编码任务（含工具与权限）

```
User          CLI/Web        SessionFacade      AgentLoop        TurnProcessor     ModelClient      ToolExecutor     PermissionService
 │                │                 │                │                 │                │                │                 │
 │─"改 UserService"→│                 │                │                 │                │                │                 │
 │                │─prompt(input)──→│                │                 │                │                │                 │
 │                │                 │─appendUserMsg─→DB + Bus(message.updated)           │                │                 │
 │                │                 │─tryAcquire()──→SessionRegistry（互斥）              │                │                 │
 │                │                 │─submit(虚拟线程)→│                 │                │                │                 │
 │                │                 │←─future────────│                 │                │                │                 │
 │                │←──SSE/渲染订阅───┤                 │                 │                │                │                 │
 │                │                 │                │═══ OUTER LOOP iteration 1 ═══     │                │                 │
 │                │                 │                │─setStatus(Busy,"context")──→Bus(session.updated)   │                 │
 │                │                 │                │─history.streamMessages()─→SessionStateCache        │                 │
 │                │                 │                │─CompactedHistoryFilter.apply()                     │                 │
 │                │                 │                │─LoopMarkers.extract(msgs)  ← 纯函数，无缓存         │                 │
 │                │                 │                │─hasCompletedReplyForLastUser()? → false            │                 │
 │                │                 │                │─overflow.shouldCompactBeforeRequest()? → false     │                 │
 │                │                 │                │─context.assemble()                                 │                 │
 │                │                 │                │   ├─SystemPromptAssembler（6 块，缓存稳定）         │                 │
 │                │                 │                │   ├─ReminderInjector（9 个 Contributor 按 order）   │                 │
 │                │                 │                │   ├─ToolResolver.resolve()  → 22 工具（lazy 过滤）  │                 │
 │                │                 │                │   ├─HistoryConverter.convert() → 交替/配对修复      │                 │
 │                │                 │                │   └─TokenCounter → estimatedTokens                 │                 │
 │                │                 │                │─snapshot.track() → treeHash（git add -A + write-tree）              │
 │                │                 │                │─createAssistantMessage + StepStartPart(snapshot)    │                 │
 │                │                 │                │─turnFactory.create()──────────────→│                │                 │
 │                │                 │                │─processor.process(bundle)─────────→│                │                 │
 │                │                 │                │                 │─openStream(req, abort)──────────→│                 │
 │                │                 │                │                 │                │─OkHttp POST──→ Anthropic
 │                │                 │                │                 │                │←─SSE 200───────
 │                │                 │                │                 │  ┌── for (StreamEvent ev : stream) ──┐            │
 │                │                 │                │                 │  │ message_start → Start, StartStep  │            │
 │                │                 │                │                 │  │ content_block_start(thinking)     │            │
 │                │                 │                │                 │  │   → 建 ReasoningPart + Bus        │──→ CLI 暗色流式
 │                │                 │                │                 │  │ thinking_delta × N                │            │
 │                │                 │                │                 │  │ content_block_stop → 落 signature │            │
 │                │                 │                │                 │  │ content_block_start(text)         │            │
 │                │                 │                │                 │  │ text_delta × N → Bus(part.delta)  │──→ CLI 正文流式
 │                │                 │                │                 │  │ content_block_start(tool_use)     │            │
 │                │                 │                │                 │  │   → 建 ToolPart(Pending) + Bus    │──→ 工具卡片 ⠋
 │                │                 │                │                 │  │ input_json_delta × N              │            │
 │                │                 │                │                 │  │ content_block_stop                │            │
 │                │                 │                │                 │  │   → JSON.parse → ToolCall         │            │
 │                │                 │                │                 │  │   → outToolCalls.add()            │            │
 │                │                 │                │                 │  │ message_delta → FinishStep(usage) │            │
 │                │                 │                │                 │  │   → overflow 判定 → 无需压缩       │            │
 │                │                 │                │                 │  │ message_stop → Finish             │            │
 │                │                 │                │                 │  └───────────────────────────────────┘            │
 │                │                 │                │                 │←─return CONTINUE（有 toolCalls）  │                │
 │                │                 │                │─StepFinishPart(snapshot,cost,tokens)                │                │
 │                │                 │                │─maxSteps 检查（step=1 < 200）OK                     │                │
 │                │                 │                │─toolExecutor.executeBatch(calls, abort.child())────────────────────→│
 │                │                 │                │                 │                │                │─并发 3 虚拟线程─┤
 │                │                 │                │                 │                │                │  ┌ Grep ────────┐
 │                │                 │                │                 │                │                │  │ resolveOne   │
 │                │                 │                │                 │                │                │  │ hook.before  │
 │                │                 │                │                 │                │                │  │ →Running+Bus │──→ 卡片 ⠋
 │                │                 │                │                 │                │                │  │ evaluate()   │
 │                │                 │                │                 │                │                │  │ =ALLOW(项目内)│
 │                │                 │                │                 │                │                │  │ rg 子进程    │
 │                │                 │                │                 │                │                │  │ truncate     │
 │                │                 │                │                 │                │                │  │ →Completed   │──→ 卡片 ✓
 │                │                 │                │                 │                │                │  └──────────────┘
 │                │                 │                │                 │                │                │  ┌ Read ────────┐
 │                │                 │                │                 │                │                │  │ … stampRead()│ ← 编辑锚点
 │                │                 │                │                 │                │                │  └──────────────┘
 │                │                 │                │                 │                │                │  ┌ Edit ────────┐
 │                │                 │                │                 │                │                │  │ withLock()   │
 │                │                 │                │                 │                │                │  │ assertRead() │
 │                │                 │                │                 │                │                │  │ ReplacerChain│
 │                │                 │                │                 │                │                │  │  →SIMPLE 命中 │
 │                │                 │                │                 │                │                │  │ unified diff │
 │                │                 │                │                 │                │                │  │─ask(EDIT)────────────→│
 │                │                 │                │                 │                │                │  │              │ evaluate=ASK
 │                │                 │                │                 │                │                │  │              │ pending.put(future)
 │                │                 │                │                 │                │                │  │              │─Bus(permission.asked)
 │                │←─────────────权限弹窗（含 diff）───────────────────────────────────────────────────────────────────────┤
 │─"always"──────→│                 │                │                 │                │                │  │              │
 │                │─POST /api/permissions/{id}/reply─────────────────────────────────────────────────────→│  │              │
 │                │                 │                │                 │                │                │  │              │─reply(ALWAYS)
 │                │                 │                │                 │                │                │  │              │ ├─future.complete
 │                │                 │                │                 │                │                │  │              │ ├─写运行时规则
 │                │                 │                │                 │                │                │  │              │ ├─持久化 settings
 │                │                 │                │                 │                │                │  │              │ └─级联放行同类 pending
 │                │                 │                │                 │                │                │  │←─返回────────┤
 │                │                 │                │                 │                │                │  │ 原子写文件    │
 │                │                 │                │                 │                │                │  │ 重读→真实 diff│
 │                │                 │                │                 │                │                │  │ LSP 诊断     │
 │                │                 │                │                 │                │                │  │ →Completed   │──→ 卡片 ✓ +12 -3 + diff
 │                │                 │                │                 │                │                │  └──────────────┘
 │                │                 │                │                 │                │←─allOf().join()┤
 │                │                 │                │                 │                │  ★ 按原顺序对齐 → role=tool 消息   │
 │                │                 │                │←─ToolBatchOutcome(messages, step=2)─────────────────┤                 │
 │                │                 │                │─shouldCompactAfterToolResults()? → false            │                 │
 │                │                 │                │─maybeMicrocompact() → 空闲不足，跳过                 │                 │
 │                │                 │                │═══ OUTER LOOP iteration 2（从含工具结果的历史重推导）═══                │
 │                │                 │                │  … 模型输出总结文本，无 toolCalls …                   │                 │
 │                │                 │                │─hasCompletedReplyForLastUser()? → TRUE               │                 │
 │                │                 │                │─break（COMPLETED_REPLY）                             │                 │
 │                │                 │                │─finalizeTurn()：异步标题生成 + diff 摘要 + todo 刷新  │                 │
 │                │                 │←─LoopOutcome───│                 │                │                │                 │
 │                │                 │                │─finally: setStatus(Idle) + partThrottler.flushAll() + registry.release()
 │                │←─future complete─┤                │                 │                │                │                 │
 │←─用量总结───────┤                 │                │                 │                │                │                 │
```

### 6.2 中断（Esc）时序

```
User     CLI         SessionFacade   AgentLoop        TurnProcessor    ModelClient    ToolExecutor   子进程
 │        │                │              │                 │               │              │            │
 │─Esc───→│                │              │                 │               │              │            │
 │        │─cancel(sid)───→│              │                 │               │              │            │
 │        │                │─entry.abortSignal.abort()       │               │              │            │
 │        │                │   ├─① cleanups（逆序执行）                                        │            │
 │        │                │   │    ├─ okhttpCall.cancel()──────────────────→│ IOException  │            │
 │        │                │   │    ├─ ProcessTreeKiller.kill(proc)──────────────────────────────────→ destroyForcibly
 │        │                │   │    ├─ permissionFuture.completeExceptionally(Aborted)                    │
 │        │                │   │    ├─ questionFuture.completeExceptionally(Aborted)                      │
 │        │                │   │    └─ childSignals.abort()（级联到每个工具）──────────────→│            │
 │        │                │   ├─② done.completeExceptionally → 唤醒所有 await()              │            │
 │        │                │   └─③ children.abort()                                               │            │
 │        │                │              │←─InferenceAbortedException（流读取处）             │            │
 │        │                │              │  throwIfAborted() 在每个事件边界复检                │            │
 │        │                │              │←─AbortedException 上抛                              │            │
 │        │                │←─catch(AbortedException)                                            │            │
 │        │                │  cleanupAfterAbort():                                               │            │
 │        │                │   ├─ 删除未完成 Part（ToolPart.pending/running、未闭合 text/reasoning）│            │
 │        │                │   ├─ Bus(message.part.removed) × N──────────→ CLI 移除卡片           │            │
 │        │                │   ├─ 写 TextPart("[Request interrupted by user]")                    │            │
 │        │                │   ├─ AssistantMessage.timeCompleted=now, error=Aborted               │            │
 │        │                │   └─ partThrottler.flushAll()（★ 保证清理结果落盘）                   │            │
 │        │                │  finally: setStatus(Idle) + registry.release()                       │            │
 │        │←─Bus(session.updated, Idle)                                                           │            │
 │←─恢复输入态                                                                                     │            │
 │        │                │              │                 │               │              │            │
 │        │  ★ 验证点：ProcessHandle.descendants() 为空；DB 无 pending/running Part                 │            │
```

### 6.3 自动压缩时序

```
AgentLoop        OverflowDetector    CompactionService   PreservedTailPlanner  HistorySanitizer  SessionFacade(子会话)  PostCompactionRestore
    │                   │                   │                    │                   │                  │                    │
    │─FinishStep(usage)─→│                   │                    │                   │                  │                    │
    │  needsCompaction?──→ isOverflow(): total >= window - min(8000, maxOut) - autoBuf │                  │                    │
    │←─true──────────────│                   │                    │                   │                  │                    │
    │  processor 返回 COMPACT                 │                    │                   │                  │                    │
    │─schedule(POST_FINISH_STEP)────────────→│                    │                   │                  │                    │
    │                   │                   │─guard.tryAcquire()（熔断 + 去重）        │                  │                    │
    │                   │                   │─sessions.markCompacting()               │                  │                    │
    │                   │                   │─Bus(session.updated, Compacting)────────────────────────→ UI "Compacting…"
    │  continue OUTER（下一轮）                │                    │                   │                  │                    │
    │─markers.pendingCompaction != null      │                    │                   │                  │                    │
    │─process(sessionId, req, abort.child())→│                    │                   │                  │                    │
    │                   │                   │─history + filterCompacted               │                  │                    │
    │                   │                   │─plan(history, card, s)─────────────────→│                  │                    │
    │                   │                   │                    │ groupIntoRounds()（user 边界）         │                    │
    │                   │                   │                    │ 倒序累加至 tailBudget(=window*0.2)     │                    │
    │                   │                   │                    │ ★ 切点必落在完整 round 边界            │                    │
    │                   │                   │←─Plan(toSummarize, preservedTail)────────┤                  │                    │
    │                   │                   │─sanitize(toSummarize)──────────────────────────────────→│  │                    │
    │                   │                   │                    │                   │ 剥离 FilePart    │                    │
    │                   │                   │                    │                   │ 剔除 reasoning   │                    │
    │                   │                   │                    │                   │ tool output→头500+尾500                │
    │                   │                   │←─List<ProviderMessage>──────────────────────────────────┤  │                    │
    │                   │                   │─generateSummaryWithRetry()──────────────────────────────────────────────────→│  │
    │                   │                   │                    │                   │  runHiddenSession(agent=compaction,   │
    │                   │                   │                    │                   │    lane=SIDE_LLM, incognito=true,     │
    │                   │                   │                    │                   │    tools=∅, tier=fast)                │
    │                   │                   │                    │                   │                  │─独立 Loop 一轮────→ 摘要文本
    │                   │                   │←─summary（或 ContextOverflowException → truncateHead 丢最早 round，重试≤3）─────┤  │
    │                   │                   │─appendCompactionBoundary(summary, metadata)                                   │  │
    │                   │                   │   ├─ 合成 UserMessage                                                         │  │
    │                   │                   │   └─ CompactionPart{prompt=summary, metadata={preservedTail,                 │  │
    │                   │                   │        preCompactDiscoveredTools, messagesSummarized, truePostCompactTokenCount}}│
    │                   │                   │─restore.apply(sessionId, boundaryMsgId, req, plan)────────────────────────────────→│
    │                   │                   │                    │                   │                  │  注入 reminder：    │
    │                   │                   │                    │                   │                  │  · plan 文件内容    │
    │                   │                   │                    │                   │                  │  · 最近编辑文件清单 │
    │                   │                   │                    │                   │                  │  · 已调用 skill     │
    │                   │                   │                    │                   │                  │  · task/todo 状态   │
    │                   │                   │                    │                   │                  │  · 恢复延迟工具激活 │
    │                   │                   │─clearCompacting() + guard.release()                                          │  │
    │                   │                   │─Bus(session.compacted)────────────────────────────────→ UI "已压缩 N 条消息"   │  │
    │←─CompactionOutcome(CONTINUE)───────────│                    │                   │                  │                    │
    │  continue OUTER → 下一轮 filterCompacted() 只取边界之后的消息                                                            │
```

### 6.4 后台子 Agent 与通知回流

```
AgentTool      SessionService   BackgroundTaskManager   子 Loop(虚拟线程)   AgentOutputWriter   NotificationService   父 Loop
    │                │                  │                     │                   │                   │                │
    │─create(parentId=sid)→│             │                     │                   │                   │                │
    │←─childId─────────┤                 │                     │                   │                   │                │
    │─overlays.set(childId, shadow=[Agent,TeamCreate,TeamDelete])                   │                   │                │
    │─appendRuntimePermissionRules(AGENT:DENY, TODOWRITE:DENY, CRON:DENY)           │                   │                │
    │─startAgent(cmd)────────────────→│  │                     │                   │                   │                │
    │                │                  │─agentSemaphore.acquire()（上限 10，排队）    │                   │                │
    │                │                  │─status=RUNNING + Bus(task.updated)─────────────────────────────────────────→ UI 侧栏
    │                │                  │─submit(虚拟线程)──────→│                   │                   │                │
    │←─BackgroundTask(id=childId)───────┤ │                     │─start()──────────→│                   │                │
    │                │                  │  │                     │                   │─subscribe(MessageUpdated/PartUpdated)
    │  ToolResult:   │                  │  │                     │                   │─writer 虚拟线程：queue→append JSONL
    │  "agent_id=…, output_file=…, 你会被自动通知，不要轮询"        │                   │                   │                │
    │──────────────────────────────────────────────────────────────────────────────────────────────→ 父 Loop 继续自己的轮次
    │                │                  │  │═══ 子 Loop 独立运行（lane=SIDE_AGENT）═══│                   │                │
    │                │                  │  │  ★ RuntimeGate.mainAgentOnlyDecision()=false                │                │
    │                │                  │  │    → 子会话不触发父会话压缩/标题生成/记忆提取                  │                │
    │                │                  │  │  ★ 子会话的 permission.asked 经 PermissionScopeResolver      │                │
    │                │                  │  │    沿 parent_id 链冒泡到父会话 → 父 UI 弹窗                   │                │
    │                │                  │  │←─LoopOutcome────────┤                   │                   │                │
    │                │                  │─status=COMPLETED + Bus(task.updated)                            │                │
    │                │                  │─pushOrResume(parentSessionId, notification)──────────────────→│                │
    │                │                  │                     │                   │                   │─registry.find(parent)
    │                │                  │                     │                   │            ┌──────┴───────┐          │
    │                │                  │                     │                   │      父 Busy│              │父 Idle   │
    │                │                  │                     │                   │            ▼              ▼          │
    │                │                  │                     │                   │      queue.offer()   appendSyntheticUserMessage
    │                │                  │                     │                   │            │         (落库, source=NOTIFICATION)
    │                │                  │                     │                   │            │              │          │
    │                │                  │                     │                   │            │         facade.resumeExisting()
    │                │                  │                     │                   │            │              │─唤醒────→│
    │                │                  │                     │                   │            │              │  OUTER LOOP
    │                │                  │                     │                   │            │              │  markers 推导
    │                │                  │                     │                   │            │              │  → 有新的 user 消息
    │                │                  │                     │                   │            │              │  → 继续处理
    │                │                  │                     │                   │            └──────┬───────┘          │
    │                │                  │                     │                   │        drain() 在步骤 8 前被调用      │
    │                │                  │                     │                   │        → <task-notification> reminder │
    │                │                  │                     │                   │        → 模型得知任务完成，用 Read 读 outputFile
```

---

## 7. 数据库设计（完整 DDL）

### 7.1 `V1__baseline.sql`

```sql
-- ============================================================================
-- We0J baseline schema
-- SQLite 3.x；Hibernate ddl-auto=validate，所有变更必须走 Flyway 版本化迁移
-- 设计原则（保留自原项目）：message / part 为「薄壳表 + JSON blob」，
--   Part 类型演进零 migration 成本；仅冗余高频查询字段为索引列。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- session：会话主表（强类型列）
-- ----------------------------------------------------------------------------
CREATE TABLE session (
    id                 TEXT    NOT NULL PRIMARY KEY,          -- ULID(26)，时间有序
    project_id         TEXT    NOT NULL,                      -- workdir 绝对路径 SHA-256 前 16 位
    parent_id          TEXT,                                  -- 非空 = 子 Agent 会话
    name               TEXT,                                  -- 用户可命名的会话别名
    directory          TEXT    NOT NULL,                      -- 工作目录绝对路径
    title              TEXT    NOT NULL,                      -- 展示标题（首轮后异步语义化）
    version            TEXT    NOT NULL,                      -- 创建时的 We0J 版本
    share_url          TEXT,
    -- 本轮 diff 摘要（异步回填，供 /rewind 面板）
    summary_additions  INTEGER,
    summary_deletions  INTEGER,
    summary_files      INTEGER,
    summary_diffs      TEXT,                                  -- JSON: List<FileDiff>
    -- 回滚记录（FR-102）
    revert             TEXT,                                  -- JSON: RevertRecord
    -- ★ 架构改进：会话运行时状态落库，保证 resume 完整性（FR-013）
    runtime_state      TEXT,                                  -- JSON: RuntimeState
    -- 时间戳（epoch millis，避免 SQLite 日期函数差异）
    time_created       INTEGER,
    time_updated       INTEGER,
    time_compacting    INTEGER,                               -- 非空 = 压缩进行中
    time_archived      INTEGER,
    is_incognito       INTEGER NOT NULL DEFAULT 0,            -- 1 = 内部子会话，不进 /resume
    CONSTRAINT fk_session_parent FOREIGN KEY (parent_id) REFERENCES session(id) ON DELETE SET NULL
);

CREATE INDEX idx_session_project          ON session (project_id);
CREATE INDEX idx_session_parent           ON session (parent_id);
CREATE INDEX idx_session_project_updated  ON session (project_id, time_updated DESC);
CREATE INDEX idx_session_archived         ON session (time_archived);
-- 部分唯一索引：name 非空时 (project_id, name) 唯一（对齐原项目 sqlite_where）
CREATE UNIQUE INDEX idx_session_project_name_unique
    ON session (project_id, name) WHERE name IS NOT NULL;

-- ----------------------------------------------------------------------------
-- message：消息薄壳表
-- ----------------------------------------------------------------------------
CREATE TABLE message (
    id            TEXT    NOT NULL PRIMARY KEY,
    session_id    TEXT    NOT NULL,
    role          TEXT    NOT NULL,                            -- 'user' | 'assistant'（冗余索引列）
    data          TEXT    NOT NULL,                            -- JSON blob: UserMessage | AssistantMessage
    time_created  INTEGER,
    time_updated  INTEGER,
    CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES session(id) ON DELETE CASCADE
);

CREATE INDEX idx_message_session          ON message (session_id);
CREATE INDEX idx_message_session_created  ON message (session_id, time_created);
CREATE INDEX idx_message_session_role     ON message (session_id, role);

-- ----------------------------------------------------------------------------
-- part：消息片段薄壳表（流式更新的最小粒度）
-- ----------------------------------------------------------------------------
CREATE TABLE part (
    id            TEXT    NOT NULL PRIMARY KEY,
    message_id    TEXT    NOT NULL,
    session_id    TEXT    NOT NULL,
    type          TEXT    NOT NULL,                            -- 'text'|'reasoning'|'tool'|'step-start'|…
    data          TEXT    NOT NULL,                            -- JSON blob: Part 多态
    time_created  INTEGER,
    time_updated  INTEGER,
    CONSTRAINT fk_part_message FOREIGN KEY (message_id) REFERENCES message(id) ON DELETE CASCADE,
    CONSTRAINT fk_part_session FOREIGN KEY (session_id) REFERENCES session(id) ON DELETE CASCADE
);

CREATE INDEX idx_part_message           ON part (message_id);
CREATE INDEX idx_part_session           ON part (session_id);
CREATE INDEX idx_part_session_created   ON part (session_id, time_created);
CREATE INDEX idx_part_session_type      ON part (session_id, type);
-- 启动时扫描修复悬挂 Part（NFR-03）：WHERE type='tool' 且 data 含 pending/running
CREATE INDEX idx_part_message_type      ON part (message_id, type);

-- ----------------------------------------------------------------------------
-- 触发器：自动维护 time_updated（减少应用层遗漏）
-- ----------------------------------------------------------------------------
CREATE TRIGGER trg_session_updated AFTER UPDATE ON session
    FOR EACH ROW WHEN NEW.time_updated IS OLD.time_updated
BEGIN
    UPDATE session SET time_updated = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE id = NEW.id;
END;

CREATE TRIGGER trg_message_updated AFTER UPDATE ON message
    FOR EACH ROW WHEN NEW.time_updated IS OLD.time_updated
BEGIN
    UPDATE message SET time_updated = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE id = NEW.id;
END;

CREATE TRIGGER trg_part_updated AFTER UPDATE ON part
    FOR EACH ROW WHEN NEW.time_updated IS OLD.time_updated
BEGIN
    UPDATE part SET time_updated = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE id = NEW.id;
END;

-- ----------------------------------------------------------------------------
-- 视图：便捷查询（不参与写入）
-- ----------------------------------------------------------------------------
CREATE VIEW v_session_latest AS
SELECT s.id, s.project_id, s.title, s.directory, s.time_updated, s.summary_files,
       (SELECT COUNT(*) FROM message m WHERE m.session_id = s.id) AS message_count
FROM session s
WHERE s.is_incognito = 0 AND s.time_archived IS NULL;

-- 未终结的 tool part（启动修复扫描用）
CREATE VIEW v_dangling_tool_parts AS
SELECT p.id, p.session_id, p.message_id, p.data
FROM part p
WHERE p.type = 'tool'
  AND (p.data LIKE '%"status":"pending"%' OR p.data LIKE '%"status":"running"%');
```

### 7.2 JSON blob 示例（真实数据形态）

**`session.runtime_state`**：
```json
{
  "agentName": "build",
  "permissionMode": "ASK",
  "runtimePermissionRules": [
    {"permission": "edit", "pattern": "src/main/java/com/we0j/**", "action": "ALLOW"},
    {"permission": "bash", "pattern": "mvn *", "action": "ALLOW"}
  ],
  "activatedDeferredTools": ["Read", "Grep", "Edit", "Bash"],
  "invokedSkills": ["mcp-scripting"],
  "lastModelRef": "anthropic/claude-sonnet-4-5",
  "pendingRevert": null,
  "extra": {}
}
```

**`message.data`（AssistantMessage）**：
```json
{
  "id": "01J9ZK3M8QWERTYUIOPASDFGHJ",
  "sessionId": "01J9ZK2X7PA1B2C3D4E5F6G7H8",
  "role": "assistant",
  "time": {"created": "2026-09-07T14:23:11.482Z", "completed": "2026-09-07T14:23:47.913Z"},
  "error": null,
  "cost": 0.043821,
  "tokens": {"total": 18432, "input": 15210, "output": 1024, "reasoning": 512,
             "cache": {"read": 14800, "write": 410}},
  "finish": "stop",
  "summary": null,
  "structured": null,
  "variant": null,
  "metadata": {"step": 3, "requestId": "01J9ZK3M0Q..."}
}
```

**`part.data`（ToolPart，completed）**：
```json
{
  "id": "01J9ZK3F2N...",
  "messageId": "01J9ZK3M8Q...",
  "sessionId": "01J9ZK2X7P...",
  "type": "tool",
  "callId": "toolu_01A2B3C4D5E6F7",
  "toolName": "Edit",
  "state": {
    "status": "completed",
    "input": {"path": "src/main/java/com/we0j/agent/loop/AgentLoop.java",
              "oldText": "if (step >= maxSteps)", "newText": "if (step >= settings.loop().maxSteps())",
              "replaceAll": false},
    "output": "Updated src/main/java/com/we0j/agent/loop/AgentLoop.java (1 additions, 1 deletions)",
    "title": "AgentLoop.java",
    "metadata": {
      "path": "src/main/java/com/we0j/agent/loop/AgentLoop.java",
      "diff": "--- a/AgentLoop.java\n+++ b/AgentLoop.java\n@@ -142,7 +142,7 @@\n-  if (step >= maxSteps)\n+  if (step >= settings.loop().maxSteps())\n",
      "strategy": "SIMPLE",
      "additions": 1, "deletions": 1, "diagnostics": 0, "isNewFile": false
    },
    "time": {"start": "2026-09-07T14:23:19.104Z",
             "end": "2026-09-07T14:23:19.387Z",
             "compacted": null},
    "attachments": null
  },
  "metadata": {}
}
```

### 7.3 文件存储布局（非 DB 状态）

| 路径 | 格式 | 内容 | 并发控制 |
|---|---|---|---|
| `~/.we0j/settings.json` | JSON | 用户级配置 | mtime 缓存键热加载 |
| `~/.we0j/providers.json` | JSON | provider/model 基础设施 | 同上 |
| `~/.we0j/history` | 行文本 | REPL 输入历史 | JLine FileHistory 独占 |
| `<project>/.we0j/settings.json` | JSON | 项目级配置 | mtime 热加载 |
| `~/.we0j/projects/<pid>/runtime.db` | SQLite | session/message/part | WAL + busy_timeout + 节流写 |
| `~/.we0j/projects/<pid>/snapshot/` | git dir | shadow git objects | striped ReentrantLock |
| `~/.we0j/projects/<pid>/sessions/<sid>/todos.json` | JSON | Todo 列表 | 文件锁 + 读改写 |
| `~/.we0j/projects/<pid>/sessions/<sid>/tasks.json` | JSON | TaskV2 列表 | 同上 |
| `~/.we0j/projects/<pid>/sessions/<sid>/crons.json` | JSON | Cron 作业（P2） | 同上 |
| `~/.we0j/projects/<pid>/tool_output/<callId>.txt` | 文本 | 工具完整输出（截断前） | 单写者 |
| `~/.we0j/projects/<pid>/tool_output/shell/<shellId>.output` | 文本 | 后台 shell 输出 | 逐行 append + flush |
| `~/.we0j/projects/<pid>/tool_output/agents/<agentId>.output` | JSONL | 子 Agent 事件流 | 单写入虚拟线程 |
| `~/.we0j/projects/<pid>/logs/we0j.log` | 文本/JSON | 应用日志 | Logback 轮转 |
| `~/.we0j/projects/<pid>/logs/llm-trace.jsonl` | JSONL | 模型请求追踪 | 有界队列 + 单写者 |
| `~/.we0j/skills/<name>/SKILL.md` | MD+YAML | 全局 Skill | WatchService 只读 |
| `~/.we0j/agents/<name>.md` | MD+YAML | 全局 Agent 人格 | 同上 |
| `~/.we0j/commands/<name>.md` | MD+YAML | 全局自定义命令 | 同上 |

---

## 8. Web API 与 SSE 协议

### 8.1 通用约定

- **Base URL**：`http://127.0.0.1:8787/api`
- **认证**：所有请求必须带 `Authorization: Bearer <token>`（token 在 `~/.we0j/settings.json` 的 `web.token`，首启随机生成；CLI 启动时打印带 token 的完整 URL）
- **内容类型**：`application/json; charset=utf-8`
- **错误响应**：
```json
{ "error": { "code": "SESSION_NOT_FOUND", "message": "session not found: 01J...",
             "details": null, "requestId": "01J9ZK..." } }
```
- **错误码表**：

| HTTP | code | 场景 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 请求体校验失败（含字段级 details） |
| 400 | `INVALID_CONFIG_LOCATION` | 配置写在非法层级 |
| 401 | `UNAUTHORIZED` | token 缺失或错误 |
| 404 | `SESSION_NOT_FOUND` / `TASK_NOT_FOUND` / `REQUEST_NOT_FOUND` | 资源不存在 |
| 409 | `SESSION_BUSY` | 对忙碌会话执行互斥操作（如 rewind） |
| 409 | `PERMISSION_EXPIRED` | 回复一个已完成/过期的权限请求 |
| 422 | `PROVIDER_NOT_CONFIGURED` | 未配置 API key 就发起对话 |
| 500 | `INTERNAL` | 未预期异常 |
| 503 | `MODEL_UNAVAILABLE` | Provider 连接失败/限流耗尽重试 |

- **分页**：`?limit=50&before=<timeUpdated>`（游标式，避免 offset 在时间序上错位）
- **时间格式**：ISO-8601 UTC（`2026-09-07T14:23:11.482Z`）

### 8.2 端点清单

#### 会话

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|---|---|---|---|---|
| POST | `/sessions` | 创建会话 | `{"workdir":"/d/proj","agentName":"build","modelRef":"anthropic/claude-sonnet-4-5","permissionMode":"ASK"}` | `SessionDto` |
| GET | `/sessions?projectId=&limit=50&before=&q=` | 列表/搜索 | — | `{"items":[SessionDto],"nextCursor":...}` |
| GET | `/sessions/{id}` | 详情（含状态、用量、runtimeState 摘要） | — | `SessionDetailDto` |
| DELETE | `/sessions/{id}` | 删除（级联） | — | `204` |
| POST | `/sessions/{id}/archive` | 归档 | — | `SessionDto` |
| POST | `/sessions/{id}/fork` | 派生 | `{"fromMessageId":"01J..."}` | `SessionDto` |
| GET | `/sessions/{id}/messages?limit=200` | 消息历史（含 parts） | — | `{"items":[MessageWithPartsDto]}` |

#### 对话

| 方法 | 路径 | 说明 | 请求体 |
|---|---|---|---|
| POST | `/sessions/{id}/prompt` | 提交输入（忙时自动排队） | `{"text":"...","attachments":[FilePartDto],"agentName":null,"modelRef":null,"format":null}` |
| POST | `/sessions/{id}/cancel` | 中断当前轮 | — |
| POST | `/sessions/{id}/compact` | 手动压缩 | `{"instruction":"重点保留 schema 讨论"}` |
| POST | `/sessions/{id}/mode` | 切换 agent 人格 | `{"agentName":"plan"}` |
| POST | `/sessions/{id}/model` | 切换模型 | `{"modelRef":"anthropic/claude-haiku-4-5"}` |
| POST | `/sessions/{id}/permission-mode` | 切换权限模式 | `{"mode":"ALLOW_ONCE"}` |
| POST | `/sessions/{id}/rename` | 改标题 | `{"title":"..."}` |

#### 回滚

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/sessions/{id}/rewind/anchors` | 列出可回滚锚点（含 diff 摘要） |
| POST | `/sessions/{id}/rewind` | 执行回滚，体 `{"targetMessageId":"01J...","mode":"BOTH"}` |
| POST | `/sessions/{id}/rewind/undo` | 撤销回滚 |
| GET | `/sessions/{id}/diff?from=<hash>&to=<hash>` | 两个快照间完整 diff |

#### 权限与提问

| 方法 | 路径 | 说明 | 请求体 |
|---|---|---|---|
| GET | `/sessions/{id}/permissions/pending` | 挂起的权限请求 | — |
| POST | `/permissions/{requestId}/reply` | 回复 | `{"sessionId":"...","reply":"ALWAYS","userMessage":"只改这个文件"}` |
| GET | `/sessions/{id}/questions/pending` | 挂起的提问 | — |
| POST | `/questions/{requestId}/reply` | 回复 | `{"sessionId":"...","answers":[["Option A"],["B","C"]]}` |
| POST | `/questions/{requestId}/reject` | 放弃问卷 | `{"sessionId":"..."}` |
| GET | `/sessions/{id}/permission-rules` | 有效规则集（合并后，含来源标注） | — |
| PUT | `/sessions/{id}/permission-rules` | 编辑运行时规则 | `{"rules":[PermissionRuleDto]}` |

#### 模型与配置

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/providers` | 全部 provider 与其模型（apiKey 脱敏为 `"sk-…abcd"`） |
| PUT | `/providers/{id}` | 更新 provider（enabled/apiKey/apiBase/models） |
| POST | `/providers/{id}/test` | 连通性测试（发 1-token 请求） |
| GET | `/models` | 扁平模型列表（含 contextWindow/features/pricing） |
| GET | `/settings` | 当前生效配置（合并后 + 各层来源标注） |
| PUT | `/settings` | 写项目级配置（body 为 partial JSON，深合并） |
| PUT | `/settings/user` | 写用户级配置 |
| POST | `/settings/reload` | 强制热加载 |

#### 工具与任务

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/sessions/{id}/tools` | 当前会话可用工具（含 lazy/activated/deferred 状态） |
| GET | `/tools` | 全部注册工具与 schema |
| GET | `/mcp/servers` | 外部 MCP server 状态（P2） |
| POST | `/mcp/servers/{name}/reconnect` | 重连（P2） |
| GET | `/sessions/{id}/tasks` | 后台任务列表 |
| GET | `/tasks/{taskId}/output?tail=65536&follow=false` | 任务输出（follow=true 走 SSE） |
| POST | `/tasks/{taskId}/stop` | 停止任务 |
| GET | `/sessions/{id}/todos` | Todo 列表 |
| PUT | `/sessions/{id}/todos` | 覆盖写入 |

#### Skill 与状态

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/skills` | 全部 skill（name/description/location/kind/allowedTools） |
| POST | `/skills/rescan` | 强制重扫 |
| GET | `/sessions/{id}/status` | `StatusSnapshot`（用量/上下文占用/lane/缓存命中率…） |
| GET | `/sessions/{id}/usage?groupBy=turn` | 用量明细（每轮 token 堆叠） |
| GET | `/doctor` | 环境自检结果 |

#### 事件流

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/sessions/{id}/events` | **SSE**：Bus 事件流。支持 `Last-Event-ID` 头做断线重放 |
| GET | `/events/global` | **SSE**：全局事件（session.updated 列表变更、task.updated 等） |
| GET | `/tasks/{taskId}/output/stream` | **SSE**：任务输出实时尾随 |

### 8.3 SSE 协议

**请求**：
```http
GET /api/sessions/01J9ZK2X7P.../events HTTP/1.1
Host: 127.0.0.1:8787
Authorization: Bearer <token>
Accept: text/event-stream
Last-Event-ID: 1842              ← 断线重连时由 EventSource 自动带上
```

**响应流**（`id` = Bus 事件 seq，`event` = 点分主题名）：
```
id: 1843
event: session.updated
data: {"topic":"session.updated","sessionId":"01J9ZK2X7P...","status":{"type":"busy","step":2,"phase":"streaming"},"seq":1843}

id: 1844
event: message.updated
data: {"topic":"message.updated","sessionId":"01J9ZK2X7P...","messageId":"01J9ZK3M8Q...","message":{...},"seq":1844}

id: 1845
event: message.part.updated
data: {"topic":"message.part.updated","sessionId":"...","messageId":"...","partId":"01J9ZK3F2N...","part":{"type":"tool","toolName":"Edit","state":{"status":"running",...}},"seq":1845}

id: 1846
event: message.part.delta
data: {"topic":"message.part.delta","sessionId":"...","messageId":"...","partId":"...","field":"text","delta":"I'll ","seq":1846}

id: 1847
event: message.part.delta
data: {"topic":"message.part.delta","sessionId":"...","partId":"...","field":"text","delta":"now edit ","seq":1847}

: hb                                    ← 心跳（注释行，每 15s）

id: 1902
event: permission.asked
data: {"topic":"permission.asked","sessionId":"...","request":{"id":"perm_01J...","permission":"edit","patterns":["src/main/java/Foo.java"],"message":"Edit src/main/java/Foo.java","metadata":{"diff":"--- a/...","additions":12,"deletions":3},"always":["src/main/java/Foo.java"],"tool":{"messageId":"...","callId":"toolu_01..."}},"seq":1902}

id: 1903
event: permission.replied
data: {"topic":"permission.replied","sessionId":"...","requestId":"perm_01J...","reply":"ALWAYS","seq":1903}

id: 1950
event: message.part.delta.dropped
data: {"topic":"message.part.delta.dropped","sessionId":"...","count":143,"reason":"sse queue overflow","seq":1950}

id: 1988
event: session.compacted
data: {"topic":"session.compacted","sessionId":"...","metadata":{"messagesSummarized":47,"truePostCompactTokenCount":18432,"preCompactDiscoveredTools":["Read","Edit"]},"seq":1988}

id: 2001
event: task.updated
data: {"topic":"task.updated","task":{"id":"bash_1757...","type":"SHELL","status":"COMPLETED","outputFile":"...","summary":"exit 0"},"seq":2001}
```

**前端 EventSource 封装要点**：
```ts
// sse.ts
export function connectSession(sessionId: string, handlers: BusHandlers) {
  const es = new EventSource(`/api/sessions/${sessionId}/events`, { withCredentials: false })
  // ★ EventSource 不支持自定义 header → token 走查询参数（loopback 场景可接受）
  //   实际实现：URL 加 ?token=xxx，服务端 TokenFilter 同时接受 header 与 query
  for (const topic of ALL_TOPICS) {
    es.addEventListener(topic, (ev) => handlers[topic]?.(JSON.parse((ev as MessageEvent).data)))
  }
  es.onerror = () => { /* EventSource 自动重连并带 Last-Event-ID；此处只更新 UI 连接指示器 */ }
  return () => es.close()
}
```

### 8.4 DTO 定义（关键几个）

```java
public record SessionDto(String id, String projectId, String parentId, String title, String directory,
                         Instant timeCreated, Instant timeUpdated, boolean incognito,
                         SessionStatusDto status, String agentName, String modelRef,
                         Integer summaryFiles, Integer summaryAdditions, Integer summaryDeletions) {}

public record MessageWithPartsDto(MessageDto message, List<PartDto> parts) {}

/** Part DTO 与领域 Part 同构，但把 Instant 序列化为 ISO-8601，并裁剪内部字段 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({ /* 与领域 Part 一致的 11 个映射 */ })
public sealed interface PartDto permits TextPartDto, ReasoningPartDto, ToolPartDto, /* … */ {
    String id(); String messageId(); String type();
}

public record ToolPartDto(String id, String messageId, String type, String callId, String toolName,
                          ToolStateDto state, Map<String,Object> metadata) implements PartDto {
    @Override public String type() { return "tool"; }
}

public record PermissionRequestDto(String id, String sessionId, String permission, List<String> patterns,
                                   String message, Map<String,Object> metadata, List<String> always,
                                   PermissionToolRefDto tool, Instant askedAt) {}

public record RewindAnchorDto(String messageId, Instant timeCreated, String previewText,
                              String snapshot, List<FileDiffDto> diffs, int changedFileCount) {}

public record StatusSnapshotDto(String sessionId, String sessionTitle, SessionStatusDto status,
                                String lane, int step, String agentName, String permissionMode,
                                String modelRef, ContextUsageDto contextUsage, TokensDto totalTokens,
                                BigDecimal totalCost, double cacheHitRate, int activeToolCount,
                                int deferredToolCount, int backgroundTaskCount, int snapshotCount,
                                Instant startedAt, List<TodoItemDto> todos,
                                int pendingPermissionCount, int pendingQuestionCount) {}

public record ContextUsageDto(int used, int window, double ratio, String state) {}
```

### 8.5 安全过滤器

```java
@Component
@RequiredArgsConstructor
public final class TokenFilter extends OncePerRequestFilter {

    private final SettingsStore settings;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        // 静态资源与 actuator 放行（静态资源不含敏感数据）
        if (!path.startsWith("/api/")) { chain.doFilter(req, resp); return; }

        String token = extractToken(req);
        String expected = settings.current(workdirOf(req)).web().token();
        if (expected == null || !MessageDigest.isEqual(
                expected.getBytes(UTF_8), String.valueOf(token).getBytes(UTF_8))) {   // ★ 常量时间比较
            resp.setStatus(401);
            resp.setContentType("application/json;charset=utf-8");
            resp.getWriter().write("""
                {"error":{"code":"UNAUTHORIZED","message":"Invalid or missing bearer token. \
                Run `we0j` in a terminal and use the URL it prints.","details":null}}""");
            return;
        }
        // ★ 二次防线：拒绝非 loopback 来源（即使 token 泄漏也无法远程访问）
        String remote = req.getRemoteAddr();
        if (!"127.0.0.1".equals(remote) && !"0:0:0:0:0:0:0:1".equals(remote) && !"::1".equals(remote)) {
            resp.setStatus(403);
            resp.getWriter().write("{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"only loopback access allowed\"}}");
            return;
        }
        chain.doFilter(req, resp);
    }

    /** 同时接受 Authorization: Bearer 与 ?token=（EventSource 无法设 header） */
    private String extractToken(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) return h.substring(7).trim();
        return req.getParameter("token");
    }
}
```

---

## 9. 关键技术难点专章

### 9.1 asyncio → 虚拟线程：语义映射全表（★ 全项目最关键的一章）

| Python (asyncio) | Java 21 (虚拟线程) | 语义差异与注意事项 |
|---|---|---|
| `async def f()` / `await f()` | 普通同步方法调用 | **无函数染色**。Java 版调用栈完整可读，调试体验远优于 async。这是选择虚拟线程而非 Reactor 的核心理由 |
| `asyncio.run(main())` | `Thread.ofVirtual().start(...)` 或直接调用 | 无需事件循环宿主 |
| `asyncio.create_task(coro)` | `CompletableFuture.supplyAsync(supplier, VirtualThreadExecutors.IO)` 或 `IO.submit(runnable)` | 返回 `Future`，可 `cancel(true)` |
| `asyncio.gather(*ts, return_exceptions=True)` | `CompletableFuture.allOf(...).join()` + 每个 future 内部 try/catch 收敛异常 | ★ **必须每个任务内部 catch**，否则 `allOf` 只抛第一个异常，其余被吞。见 `ToolExecutor.runOne` |
| `asyncio.gather(*ts)`（不容错） | `StructuredTaskScope.ShutdownOnFailure`（Java 21 preview，**不用**）→ 用 `allOf` + 检查全部结果 | Java 25 正式后可切换到 StructuredTaskScope，语义更贴合 |
| `asyncio.wait({a,b}, return_when=FIRST_COMPLETED)` | `CompletableFuture.anyOf(a, b).get()` | 用于「超时 vs abort」「流下一块 vs abort」竞速 |
| `asyncio.wait_for(coro, timeout)` | `future.get(timeout, unit)` + `TimeoutException` | 超时后必须显式 cancel，否则任务继续跑 |
| `asyncio.Event()` + `.set()` + `.wait()` | **自研 `AbortSignal`**（§4.1） | ★ 不能只用 `CompletableFuture`：需要级联 + `onCancel` 清理动作注册 |
| `asyncio.Queue()` | `LinkedBlockingQueue` / `ArrayBlockingQueue` | 虚拟线程上 `put/take` 阻塞零成本 |
| `asyncio.Lock()` | `ReentrantLock`（**不要 `synchronized`**） | ★ `synchronized` 内阻塞会 pin carrier 线程 |
| `asyncio.Semaphore(n)` | `java.util.concurrent.Semaphore` | 虚拟线程友好 |
| `contextvars.ContextVar` | `ThreadLocal` + **显式传递到子任务** | ★ 虚拟线程默认**不继承** ThreadLocal（`InheritableThreadLocal` 在 `Thread.ofVirtual()` 下默认禁用）。所有派生任务必须 `RuntimeLaneRegistry.callAs(lane, ...)` 显式包裹 |
| `asyncio.current_task()` | `Thread.currentThread()` | 虚拟线程对象轻量，可自由持有引用 |
| `loop.run_in_executor(None, fn)` | `cpuBoundExecutor.submit(fn)` | CPU 密集必须走**平台线程池**，否则占满 carrier |
| `await asyncio.sleep(n)` | `Thread.sleep(n)` | 虚拟线程 sleep 会 unmount，不占 carrier |
| 协程取消传播（`CancelledError`） | `AbortSignal` 级联 + `onCancel` 回调 + `throwIfAborted()` 检查点 | ★ Java 无自动取消传播，**必须在每个循环边界与 IO 前显式检查**。这是移植中最容易漏的地方 |
| `async with aiofiles.open()` | 虚拟线程 + 同步 `Files`/`FileChannel` | 不需要 aiofiles 这类异步文件库 |
| `async for chunk in stream` | `for (StreamEvent e : eventStream)` | 拉模型，阻塞在 `hasNext()`，虚拟线程零成本 |

**Pinning 排查清单**（R-02）：
```bash
# 启动参数（开发/压测期常开）
-Djdk.tracePinnedThreads=full
# JFR 事件
jdk.VirtualThreadPinned
```
已知需重点检查的位置：
1. **sqlite-jdbc**：内部有 `synchronized`。缓解：HikariCP 连接池限流（≤8），且所有 DB 写经 `PartWriteThrottler` 合并；若压测仍 pin，把 DB 访问收敛到独立的**平台线程**单写者执行器。
2. **OkHttp**：4.12+ 已适配 Loom，`Call.execute()` 不 pin。
3. **Logback**：`OutputStreamAppender` 有 `synchronized`。缓解：使用 `AsyncAppender`（`neverBlock=true`）或 Logback 1.5+ 的 Loom 友好配置。
4. **自研代码**：全程禁用 `synchronized` 包裹任何 IO/等待，统一 `ReentrantLock`。用 ArchUnit/Checkstyle 自定义规则强制。

### 9.2 性能实现要点

| 目标 | 手段 | 实现位置 |
|---|---|---|
| 冷启动 ≤ 2.5s | ① Spring `spring-context-indexer`（编译期生成组件索引）② 子命令懒加载（picocli `@Command(subcommands=...)` + `LazyGroup` 等价物）③ **AppCDS**：`-XX:SharedArchiveFile=we0j.jsa`，构建时用 `java -XX:ArchiveClassesAtExit` 训练一次 ④ Flyway/JPA 延迟到首个需要 DB 的命令 | `We0jCommand`、`pom.xml`（AppCDS profile） |
| TTFT ≤ 300ms | ① OkHttp 连接池预热（启动时对已启用 provider 发 HEAD）② SSE 解析零拷贝（`BufferedReader` + `StringBuilder`，不做多余 String 拼接）③ Bus → CLI 渲染路径无中间队列（直接 `subscribe` 回调）④ 渲染按行缓冲，不做逐字符 flush | `OkHttpClientFactory`、`SseParser`、`StreamingRenderer` |
| Part 写 ≤ 100/s | `PartWriteThrottler`：100ms / 4KB / 终态 三条件触发；批量 `batchUpdate` | §4.5.3 |
| 快照 P95 ≤ 500ms | ① shadow git `core.untrackedCache=true` + `core.preloadindex=true` ② exclude 文件覆盖 `node_modules` 等大目录 ③ `track()` 失败降级为不阻断 ④ 可选：仅在有写类工具执行过的 step 才 track（配置 `code.runtime.snapshotOnDemand`） | §5.10.1 |
| resume 1 万 Part ≤ 1.5s | ① 单条 SQL 拉全部 part（`WHERE session_id=?`，走索引）② Jackson 反序列化用 `ObjectReader` 复用（避免每次构造）③ 并行反序列化（`parallelStream` 走 cpuBoundExecutor，按 messageId 分组后再组装）④ 只装载最后一个 CompactionPart 之后的消息（`filterCompacted` 下推到 SQL：先查 compaction part 的 time_created，再 `WHERE time_created >= ?`） | `SessionService.restore` |
| Web 首屏 ≤ 1s | Vite 构建 + 路由级 code splitting + 静态资源 `Cache-Control: max-age=3600` + gzip/brotli（Tomcat `compression: on`） | `we0j-web/vite.config.ts` |
| 内存 ≤ 400MB 空闲 | ① `SessionStateCache` 用 LRU 限制常驻会话数（默认 5，超出则 evict 到 DB）② 工具输出内存态限 50KB（超出只在磁盘）③ `ModelInfoTable` LRU 4096 ④ Jackson `ObjectMapper` 单例复用 | `SessionStateCache`、`OutputTruncator` |

### 9.3 可维护性强制手段

```java
// we0j-server/src/test/java/com/we0j/architecture/LayeringTest.java
@AnalyzeClasses(packages = "com.we0j", importOptions = DoNotIncludeTests.class)
public class LayeringTest {

    @ArchTest
    static final ArchRule layers_are_respected = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Common").definedBy("com.we0j.common..")
            .layer("Infra").definedBy("com.we0j.infra..")
            .layer("Llm").definedBy("com.we0j.llm..")
            .layer("Tool").definedBy("com.we0j.tool..")
            .layer("Agent").definedBy("com.we0j.agent..")
            .layer("Cli").definedBy("com.we0j.cli..")
            .layer("Server").definedBy("com.we0j.server..")
            .whereLayer("Common").mayNotAccessAnyLayer()
            .whereLayer("Infra").mayOnlyBeAccessedByLayers("Llm","Tool","Agent","Cli","Server")
            .whereLayer("Llm").mayOnlyBeAccessedByLayers("Tool","Agent","Cli","Server")
            .whereLayer("Tool").mayOnlyBeAccessedByLayers("Agent","Cli","Server")
            .whereLayer("Agent").mayOnlyBeAccessedByLayers("Cli","Server")
            .whereLayer("Cli").mayNotBeAccessedByAnyLayer()
            .whereLayer("Server").mayNotBeAccessedByAnyLayer();

    /** ★ 架构改进验证：core 不得出现 IM/companion 语义（消除原项目的双向耦合） */
    @ArchTest
    static final ArchRule no_im_semantics_in_agent_core =
            noClasses().that().resideInAPackage("com.we0j.agent..")
                    .should().dependOnClassesThat()
                    .haveNameMatching(".*(Telegram|Companion|Persona|Heartbeat|Emotion|Selfie|Lyria).*");

    @ArchTest
    static final ArchRule common_has_no_spring =
            noClasses().that().resideInAPackage("com.we0j.common..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");

    /** ★ 禁止 LangChain4j / Spring AI（项目核心价值 = 自研 Loop） */
    @ArchTest
    static final ArchRule no_agent_frameworks =
            noClasses().should().dependOnClassesThat()
                    .resideInAnyPackage("dev.langchain4j..", "org.springframework.ai..");

    /** ★ 禁止 synchronized（虚拟线程 pinning，NFR-01） */
    @ArchTest
    static final ArchRule no_synchronized_methods =
            noMethods().should().beAnnotatedWith(Synchronized.class)   // 自定义注解匹配需字节码扫描
                    .as("synchronized causes virtual-thread pinning; use ReentrantLock");

    /** ★ 禁止裸 Map 穿越层边界（CLAUDE.md 约定的 Java 版） */
    @ArchTest
    static final ArchRule no_raw_map_in_service_signatures =
            noMethods().that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Service")
                    .should().haveRawParameterTypes(new DescribedPredicate<>("Map<String,Object>") {
                        @Override public boolean apply(JavaClass c) { return c.isAssignableTo(Map.class); }
                    });
}
```

**Checkstyle 附加规则**：
- `IllegalToken`：禁止 `synchronized` 关键字
- `RegexpSinglelineJava`：禁止 `BeanUtils.getProperty`（对应原项目"避免 getattr"约定）
- `ImportOrder` + `UnusedImports`：顶层导入，禁止通配
- `FinalClass` / `HideUtilityClassConstructor`

### 9.4 Windows / 跨平台专项（R-05）

| 问题 | 表现 | 解法 |
|---|---|---|
| CRLF 混入快照 | shadow git 把 `\r\n` 与 `\n` 视为不同内容，导致伪 diff | `core.autocrlf=false`（§5.10.1 已设）；读写文件统一用 `Files.readString`（保留原字节）；diff 前不做换行归一 |
| 长路径 > 260 字符 | `git add` 报 `Filename too long` | `core.longpaths=true`；Java 侧用 `\\?\` 前缀（`Path.of("\\\\?\\" + abs)`）作为兜底 |
| 路径大小写不敏感 | `FileTimeRegistry` 用不同大小写路径登记，锁与 staleness 校验失效 | `canon()` 统一 `toRealPath()`（返回文件系统的真实大小写） |
| 分隔符混用 | 权限 pattern `src\main\*` 匹配不上 `src/main/Foo.java` | `WildcardMatcher.normalize()` 统一 `\` → `/`（§5.8） |
| shell 差异 | `bash -c` 在 Windows 不存在 | `ShellExecutor.shellArgs()`：Windows 用 `%ComSpec% /c`；可配置为 PowerShell |
| 杀进程树 | Windows 无 SIGKILL 语义，`destroyForcibly` 走 `TerminateProcess` | `ProcessHandle.descendants()` 在 Windows 可用（JDK 9+）；先子后父 |
| 剪贴板图片 | 三平台 API 完全不同 | Windows: PowerShell `Get-Clipboard -Format Image` + `Save-Image`；macOS: `osascript` + `the clipboard as «class PNGf»`；Linux: `xclip -selection clipboard -t image/png -o`。全部缺失则禁用图片粘贴并在 `/doctor` 提示 |
| WatchService 不可靠 | macOS 基于轮询（延迟 ~2s），网络盘无事件 | `SkillWatcher` 双轨：WatchService + 2s mtime 指纹轮询兜底（§5.11） |
| ripgrep 分发 | 三平台三架构二进制 | `resources/bin/rg-{windows,linux,darwin}-{x64,arm64}[.exe]`，首启解压到 `~/.we0j/bin/` 并 `setExecutable(true)`；缺失则降级 NIO |
| 文件锁语义 | Windows 上 `FileChannel.tryLock` 会阻塞其他进程读 | `.lock` 旁路文件（`FileLocks` 已用此方案），不锁数据文件本身 |

### 9.5 提示词缓存稳定性的工程约束（G-05 / A-20）

缓存命中率 ≥ 90% 是硬指标。破坏缓存的行为必须被设计约束住：

| 破坏源 | 后果 | 约束 |
|---|---|---|
| system prompt 里含精确时间 | 每分钟全部 miss | 环境块时间**只到小时**（`yyyy-MM-dd HH:00`），并按该值做缓存 key（§5.4.1） |
| 动态信息进 system | 每轮 miss | **所有动态内容走 reminder 合成 TextPart 挂 user 消息**（FR-042），system 只放静态块 |
| 工具列表顺序变化 | tools 前缀变化 → miss | `ToolResolver` 输出**按 `order()` + 名称稳定排序**；新激活的延迟工具**追加到末尾**，不插入 |
| 历史消息被重写 | 前缀失效 | reminder 的"替换"只改**最后一条 user 消息**上的合成 Part，不动更早的历史 |
| 旁路调用（压缩/标题）用同一 model 但不同 system | 击穿主缓存 | 旁路调用使用 `CacheStrategy.LAST_USER_ONLY`，且 system 与主会话共享前缀块（§5.3.5） |
| thinking block 不回传或改动 | Anthropic 400 + 缓存失效 | `ReasoningPart.metadata.signature` 必须持久化并在 `HistoryConverter` 中原样回传（§5.4.3） |
| 空 content block | Anthropic 400 | `HistoryConverter` 显式剔除空 text/reasoning（§5.4.3） |

**命中率监控**：`/status` 面板与 `LlmTraceRecord.cacheCold` 字段。告警阈值：连续 3 轮 `cacheCold=true` → CLI 打印提示"prompt cache is not being reused; check system prompt stability"。

---

## 10. 测试策略

### 10.1 分层测试计划

| 层 | 类型 | 工具 | 覆盖重点 | 目标覆盖率 |
|---|---|---|---|---|
| `common` | 单测 | JUnit 5 + AssertJ | Jackson 多态序列化/反序列化往返（11 种 Part × 4 种 ToolState × 7 种 MessageError）、record 不变式 | 90% |
| `llm` | **fixture 回放** | JUnit 5 + 自研 `SseFixtureReplayer` | 三家 Provider 的真实 SSE 报文 → 断言 StreamEvent 序列完全一致 | 85% |
| `llm` | 契约测试 | WireMock | 错误码分类（429/500/溢出报文 13 种）、retry-after 解析（ms/秒/HTTP-date）、cache_control 打点位置 | 80% |
| `tool` | 单测 | JUnit 5 + `@TempDir` | **9 级替换策略链**（每级独立用例 + 降级路径）、唯一性冲突、staleness、Bash 解析器、ARITY pattern、Grep/Glob 降级 | 85% |
| `tool` | 并发测试 | JUnit 5 + Awaitility | 权限 ask/reply 阻塞与级联 reject、abort 级联清理、并发工具执行顺序对齐 | — |
| `agent` | 集成测试 | Spring Boot Test + **FakeModelProvider** | 完整 Loop：多轮工具调用、退出条件 6 种、压缩三策略、回滚双模式、resume、通知回流忙/闲双路径 | 80% |
| `agent` | 属性测试 | jqwik | `LoopMarkers.extract` 对任意历史序列不抛异常且满足不变式；`CompactedHistoryFilter` 幂等 | — |
| `infra` | 单测 | JUnit 5 | 分层配置深合并、mtime 热加载、Bus 顺序保证、`AbortSignal` 级联、`PartWriteThrottler` 合并语义 | 85% |
| `cli` | 端到端 | picocli `CommandLine.execute` + 假终端 | slash command 解析、补全、headless 三种 output-format | 70% |
| `server` | 端到端 | `@SpringBootTest(webEnvironment=RANDOM_PORT)` + WebTestClient | 全部 REST 端点、SSE 事件序列、token 认证、loopback 校验 | 75% |
| 全局 | 架构测试 | ArchUnit | 分层依赖、无 IM 语义、无 Agent 框架、无 `synchronized` | 100% 通过 |
| 全局 | 压力测试 | Gatling / 自研 | Bus 10k 事件/s 顺序性、100 并发 SSE、1 万 Part resume、pinning 检测 | 见 NFR-02 |

### 10.2 `FakeModelProvider` —— Loop 测试的基石

```java
/**
 * 可编程的假 Provider：按脚本回放预定义的 StreamEvent 序列。
 * 让 Loop 的集成测试完全脱离网络，且能精确构造边界场景。
 */
public final class FakeModelProvider implements ModelProvider {

    private final Queue<List<StreamEvent>> scripts = new ConcurrentLinkedQueue<>();
    private final List<ChatRequest> capturedRequests = Collections.synchronizedList(new ArrayList<>());
    private volatile Duration perEventDelay = Duration.ZERO;
    private volatile Consumer<AbortSignal> onStreamStart;

    public FakeModelProvider scriptText(String text) { /* 生成 TextStart/Delta×n/TextEnd/FinishStep/Finish */ }
    public FakeModelProvider scriptToolCall(String name, Map<String,Object> input) { /* … */ }
    public FakeModelProvider scriptReasoningThenText(String reasoning, String text) { /* … */ }
    public FakeModelProvider scriptError(Throwable t) { /* … */ }
    public FakeModelProvider scriptEmpty() { /* 空流，测 EmptyStreamGuard */ }
    public FakeModelProvider delay(Duration d) { this.perEventDelay = d; return this; }
    /** 在流开始时触发 abort，测中断清理 */
    public FakeModelProvider abortAfter(int eventCount, AbortSignal signal) { /* … */ }

    @Override public EventStream openStream(ChatRequest req, AbortSignal abort) {
        capturedRequests.add(req);
        List<StreamEvent> evs = scripts.poll();
        if (evs == null) throw new IllegalStateException("FakeModelProvider: no script left");
        if (onStreamStart != null) onStreamStart.accept(abort);
        return new ScriptedEventStream(evs, perEventDelay, abort);
    }

    /** 断言辅助 */
    public ChatRequest lastRequest() { return capturedRequests.get(capturedRequests.size() - 1); }
    public List<ChatRequest> requests() { return List.copyOf(capturedRequests); }
    public void assertSystemBlocksStable() {
        // ★ G-05 核心断言：多次请求的 system block 字节一致
        List<List<String>> systems = capturedRequests.stream()
                .map(r -> r.system().stream().map(PromptBlock::text).toList()).toList();
        assertThat(systems).allSatisfy(s -> assertThat(s).isEqualTo(systems.get(0)));
    }
    public void assertCacheBreakpointsAt(int systemCount, int messageCount) { /* … */ }
    public void assertToolSchemasEmitted(String... names) { /* … */ }
    public void assertDeferredToolsWithheld(String... names) { /* … */ }
}
```

### 10.3 SSE fixture 回放测试（R-01 缓解）

```
we0j-llm/src/test/resources/fixtures/
├── anthropic/
│   ├── text-only.sse                  纯文本
│   ├── thinking-then-text.sse         thinking + signature + text
│   ├── tool-use-single.sse            单工具调用
│   ├── tool-use-parallel.sse          并行 3 工具调用（含 index 交错）
│   ├── tool-use-empty-args.sse        无参数工具（input_json_delta 缺失）
│   ├── cache-hit.sse                  含 cache_read_input_tokens
│   ├── overflow-error.sse             prompt too long 错误流
│   └── interleaved-ping.sse           含 ping 心跳
├── openai/
│   ├── chat-text.sse
│   ├── chat-reasoning-content.sse     delta.reasoning_content（DeepSeek 风格）
│   ├── chat-tool-calls.sse            tool_calls 分片（index/id/name/arguments 分离）
│   ├── chat-usage-after-finish.sse    ★ usage 在 finish_reason 之后（网关常见坑）
│   ├── chat-missing-index.sse         ★ tool_calls 缺 index 字段
│   └── responses-tool-search.sse      Responses API + tool_search_call
└── gemini/
    ├── generate-content.sse
    └── function-call.sse
```

```java
@ParameterizedTest
@MethodSource("fixtures")
void replayProducesExpectedEvents(String fixturePath, String expectedPath) throws Exception {
    List<StreamEvent> actual = replayer.replay(fixturePath, providerFor(fixturePath));
    List<StreamEvent> expected = loader.load(expectedPath, StreamEvent.class);   // JSON 序列化的期望事件
    assertThat(actual).usingRecursiveComparison()
            .ignoringFieldsMatchingRegexes(".*providerMetadata\\..*")            // 元数据允许差异
            .isEqualTo(expected);
}

/** 采集 fixture 的脚本（一次性，从真实 API 录制） */
// scripts/record-sse-fixture.sh
// curl -N https://api.anthropic.com/v1/messages -H ... -d @req.json | tee fixtures/anthropic/xxx.sse
```

### 10.4 编辑策略链测试矩阵（R-04 缓解）

| 用例 | content | oldText | 期望策略 | 期望结果 |
|---|---|---|---|---|
| E-01 精确命中 | `a\nb\nc` | `b` | SIMPLE | `a\nX\nc` |
| E-02 多处命中未 replaceAll | `a\nb\na\nb` | `b` | — | 抛歧义错误，含行号 [2,4] |
| E-03 多处命中 + replaceAll | 同上 | `b` | MULTI_OCCURRENCE | 全部替换 |
| E-04 行尾空白差异 | `a  \nb` | `a\nb` | LINE_TRIMMED | 成功 |
| E-05 缩进整体偏移 | 4 空格缩进 | 2 空格缩进 | INDENTATION_FLEXIBLE | 成功，保留原缩进 |
| E-06 空白折叠 | `if  ( x )` | `if ( x )` | WHITESPACE_NORMALIZED | 成功 |
| E-07 转义差异 | `"a\nb"` 字面 | `"a\\nb"` | ESCAPE_NORMALIZED | 成功 |
| E-08 首尾空行 | `\n\nbody\n\n` | `body` | TRIMMED_BOUNDARY | 成功 |
| E-09 单字符差异 | `foo(bar)` | `foo(baz)` | BLOCK_ANCHOR_LOOSE(0.3) | 成功 |
| E-10 差异过大 | `completely different` | `xyz` | — | 抛无匹配错误（含 4 条排障建议） |
| E-11 未先 Read | — | — | — | `StaleFileException("File has not been read yet…")` |
| E-12 外部修改 | 读后 mtime 前进 100ms | — | — | `StaleFileException("…modified externally…")` |
| E-13 mtime 前进 30ms（容差内） | — | — | — | 通过（50ms 容差） |
| E-14 oldText 为空 + 文件为空 | `` | `` | — | 创建文件 |
| E-15 oldText 为空 + 文件非空 | `x` | `` | — | 抛错"file is not empty" |
| E-16 并发编辑同文件 | — | — | — | 串行化，两次都成功且内容正确 |
| E-17 CRLF 文件 | `a\r\nb` | `a\nb` | LINE_TRIMMED | 成功，保留 CRLF |

### 10.5 中断清理测试矩阵（R-11 缓解，FR-024 AC）

| 用例 | 中断时机 | 断言 |
|---|---|---|
| I-01 | 模型流 reasoning 中途 | DB 无未闭合 ReasoningPart；有 `[Request interrupted by user]`；AssistantMessage.error=Aborted |
| I-02 | 模型流 text 中途 | 同上，针对 TextPart |
| I-03 | tool_call 参数流式中途（JSON 未完整） | ToolPart 被删除（不是留 pending）；无 MalformedToolArguments 泄漏到用户 |
| I-04 | 工具执行中（Bash 子进程运行） | `ProcessHandle.descendants()` 为空；输出文件已 flush；ToolPart 被删除 |
| I-05 | 权限等待中 | pending map 清空；future 以 Aborted 完成；无泄漏的 CompletableFuture |
| I-06 | 提问等待中 | 同上 |
| I-07 | 重试退避 sleep 中 | 立即返回（不等完 delay）；RetryPart 已落库 |
| I-08 | 压缩子会话运行中 | 子会话被取消；`time_compacting` 清空；熔断计数不变（不算失败） |
| I-09 | 后台子 Agent 运行中（父中断） | 子 Loop 取消；子进程清理；task status=CANCELLED |
| I-10 | 连续 3 次快速 Esc | 幂等，无异常，状态一致 |

### 10.6 压力与稳定性

```java
@SpringBootTest
class BusOrderingStressTest {
    @Test void sameSessionEventsAreStrictlyOrdered() throws Exception {
        int events = 10_000;
        List<Long> received = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(MessagePartDelta.class, e -> received.add(e.seq()));

        CountDownLatch done = new CountDownLatch(events);
        for (int i = 0; i < events; i++) {
            final int n = i;
            VirtualThreadExecutors.IO.submit(() -> {
                bus.publish(new MessagePartDelta("sess-A", "m", "p", "text", "x", n));
                done.countDown();
            });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        await().until(() -> received.size() == events);
        // ★ 同一 sessionId 必须严格有序（分片串行保证）
        assertThat(received).isSorted();
    }

    @Test void criticalEventsAreNeverDroppedUnderOverflow() { /* SSE 队列打满，断言 permission.asked 全达 */ }
    @Test void noVirtualThreadPinning() throws Exception {
        // 用 JFR 录制，断言 jdk.VirtualThreadPinned 事件数为 0
    }
    @Test void hundredTurnsMemoryIsStable() { /* 100 轮后 heap used 波动 < 15% */ }
}
```

---

## 11. 工程规范

### 11.1 编码约定（Java 版 `AGENTS.md`，供 We0J 自举时遵循）

```markdown
## Repo Coding Conventions

- 领域模型一律用 `record`；判别联合用 `sealed interface` + Jackson `@JsonTypeInfo`。
  禁止用裸 `Map<String,Object>` 或 `Object` 穿越层边界（仅允许出现在：JSON blob 序列化边界、
  `ToolInput.raw()`、`ToolResult.structuredContent`、`Part.metadata` 四处）。
- 禁止反射式属性访问（`BeanUtils.getProperty`、`Field.setAccessible`）用于正常业务流。
  仅在插件/hook 边界（`HookChain`）允许。
- 禁止 `synchronized` 关键字（虚拟线程 pinning）。统一 `ReentrantLock` / `Semaphore` /
  `CompletableFuture` / `Striped<ReentrantLock>`。
- 每个阻塞点必须有超时或 abort 路径。任何 `future.get()` / `queue.take()` / `process.waitFor()`
  都必须有对应的 `AbortSignal.onCancel(...)` 注册。
- 派生并发任务时必须显式传递 `RuntimeLane`（`RuntimeLaneRegistry.callAs(lane, ...)`），
  禁止依赖 `InheritableThreadLocal`。
- 每个工具类必须有 `@We0Tool` 注解 + 一个入参 `record` + 一份 `tool-descriptions/<name>.md`。
  工具描述文本不写在 Java 代码里（便于调优与复用原项目提示词）。
- 提示词与 agent 人格文本放 `src/main/resources/prompts/` 与 `/agents/`，不进 Java 常量。
- 优先复用既有模式（`ReplacerChain` 加策略、`ContextContributor` 加贡献者、
  `ModelProvider` 加 provider），不新造解析/加载机制。
- 异常必须"可操作"：说明发生了什么、为什么、下一步怎么做。禁止裸 `throw new RuntimeException(e)`。
- 行为、意图或权衡不明确时，问用户，不要猜。
- 提交前必须跑：`mvn -q verify`（含 ArchUnit）、针对性单测、`-Djdk.tracePinnedThreads=full` 冒烟。
```

### 11.2 目录/命名规范

| 类型 | 规范 | 示例 |
|---|---|---|
| record（数据） | 名词，无 `Dto`/`Vo` 后缀（领域层）；`Dto` 后缀（server 层） | `ToolPart` / `ToolPartDto` |
| Service（有状态编排） | `XxxService` | `PermissionService` |
| 纯函数工具 | `Xxxs`（复数）或 `XxxUtil` | `Jsons` / `Ulids` / `Wildcards` |
| SPI 接口 | 能力名，无 `I` 前缀 | `Tool` / `ModelProvider` / `ContextContributor` |
| 实现类 | 具体技术名 | `GitCliSnapshotService` / `AnthropicProvider` |
| 枚举 | 单数 + 全大写值 | `Action.ALLOW` |
| 异常 | `XxxException`，必须有 `userFacingMessage()` | `StaleFileException` |
| 常量 | `Constants` 内部静态类分组 | `ToolNames.EDIT` / `Limits.MAX_TRUNCATE_BYTES` |
| 包 | 全小写，按能力分包（非按类型） | `com.we0j.tool.builtin.file` |

### 11.3 异常体系

```java
public abstract class We0jException extends RuntimeException {
    protected We0jException(String message) { super(message); }
    protected We0jException(String message, Throwable cause) { super(message, cause); }
    /** ★ 强制：每个异常都必须提供面向用户的可操作消息 */
    public abstract String userFacingMessage();
}

// 工具层（回灌模型，让模型自我纠正）
public class ToolException extends We0jException { }
public class StaleFileException extends ToolException { }
public class MalformedToolArgumentsException extends ToolException {
    public String toolName(), rawArguments();
}

// 权限/提问（可能终止 Loop）
public class PermissionDeniedException extends We0jException { public PermissionName permission(); }
public class PermissionRejectedException extends We0jException { public String requestId(); }
public class QuestionRejectedException extends We0jException { public String requestId(); }

// 中断（不视为错误，走清理路径）
public class AbortedException extends We0jException { }
public class InferenceAbortedException extends AbortedException { }

// 模型层
public class ModelException extends We0jException {
    public Integer statusCode(); public boolean isRetryable();
    public Map<String,String> responseHeaders(); public String responseBody();
    public String shortReason();          // 供 SessionStatus.Retry 展示
}
public class ContextOverflowException extends ModelException { }   // ★ 不可重试，转压缩
public class EmptyStreamException extends ModelException { }
public class ProviderAuthException extends ModelException { }

// 配置/存储
public class ConfigValidationException extends We0jException {
    public String userFacingReport();     // 多行：文件路径 + JSON Pointer + 期望 + 修复建议
}
public class SnapshotException extends We0jException { }
public class NotFoundException extends We0jException { }
```

---

## 12. 实施 Checklist

> 逐项勾选。每项都必须有对应测试。里程碑划分见 SRS §9。

### M0 工程骨架
- [ ] parent pom + 7 子模块 + 依赖版本锁定
- [ ] `we0j-common`：11 种 Part + 4 种 ToolState + 7 种 MessageError + Message 2 种 + StreamEvent 16 种（全部 record/sealed）
- [ ] Jackson `ObjectMapper` 单例 + 多态往返测试（100% 通过）
- [ ] `Ulids` / `Jsons` / `Wildcards` / `Texts` / `PathSafety`
- [ ] 异常体系（含 `userFacingMessage()`）
- [ ] `DirectoryLayout` + `PathResolver` + `ProjectId`
- [ ] `Settings` record 树 + `SettingsStore`（深合并 + mtime 热加载）+ `ConfigValidator`（含非法位置检测）
- [ ] `BootstrapInitializer`（首启模板创建，不覆盖已有配置）+ 文件权限收紧
- [ ] Flyway `V1__baseline.sql` + JPA 3 实体 + `ddl-auto=validate` 通过
- [ ] `SqlitePragmaInitializer` + `SqliteBusyRetry` + `JsonFileStore` + `FileLocks`
- [ ] `Bus` + 18 种事件 + `ShardedSerialExecutor`（顺序测试通过）
- [ ] `AbortSignal` + `AbortScope` + `RuntimeLaneRegistry` + `RuntimeGate` + `VirtualThreadExecutors`
- [ ] `FileTimeRegistry`（stampRead / assertRead 50ms 容差 / withLock）
- [ ] ArchUnit 5 条规则全绿
- [ ] `we0j doctor` 可运行

### M1 Provider 与最小 Loop
- [ ] `OkHttpClientFactory`（连接池/超时/代理/SOCKS）
- [ ] `SseParser`（多行 data、注释心跳、缺末尾空行容错）
- [ ] `AnthropicProvider` + `AnthropicEventMapper` + `AnthropicMessageConverter`
- [ ] `OpenAiChatProvider` + `OpenAiEventMapper` + `ToolCallAccumulator`
- [ ] `OpenAiResponsesProvider`（含 `tool_search` 声明）
- [ ] `GeminiProvider` + `GeminiEventMapper`
- [ ] `TokenUsageFactory`（多路取值 first-present）
- [ ] `ModelCardManager` + `ProviderRegistry` + `ModelInfoTable`（LRU 4096）
- [ ] `CacheMarkerApplier`（DEFAULT / LAST_USER_ONLY / OFF）
- [ ] `ParamDropper`（drop_params 语义 + WARN）
- [ ] `ErrorClassifier`（14 条溢出正则 + marker 表 + actionableHint）
- [ ] `RetryScheduler`（指数退避 + retry-after-ms/retry-after/HTTP-date + abort 响应 sleep）
- [ ] `EmptyStreamGuard`（3 次 / 500ms）
- [ ] `TokenCounter`（jtokkit + 兜底 length/4）+ `ContextWindowResolver` + `CostCalculator`（BigDecimal）
- [ ] `ModelClient` 门面 + `EventStream` 抽象 + `InferenceAbortedException` 竞速取消
- [ ] `SessionService`（create/restore/appendPart/updatePart/updateToolState/finishAssistantMessage/cleanupAbortedTurn）
- [ ] `SessionStateCache`（内存权威副本）+ `PartWriteThrottler`
- [ ] `SessionRegistry`（会话互斥 + attach）
- [ ] `AgentLoop` 骨架（无工具：user → stream → persist → 退出判定）
- [ ] `TurnProcessor`（16 事件状态机 + Part 落地 + Bus delta）
- [ ] SSE fixture 20 份录制 + 回放测试全绿
- [ ] `we0j -p "hello"` 三家 Provider 各跑通

### M2 工具与权限
- [ ] `Tool` SPI + `@We0Tool` + `ToolInput` 强类型访问器 + `ToolResult`（audience 分离）
- [ ] `ToolSchemaGenerator`（victools，从 record 生成 draft 2020-12）
- [ ] `ToolRegistry`（重名检测）+ `SessionToolOverlay` + `ToolFilterConfig`（含 `regex:`）
- [ ] `ToolResolver`（7 步过滤 + lazy 计算 + 稳定排序）
- [ ] `ToolExecutor`（虚拟线程并发 + 异常收敛 + 顺序对齐 + hook 前后置）
- [ ] `OutputTruncator`（2000 行 / 50KB / UTF-8 边界安全）+ `ToolOutputStorage`
- [ ] `PermissionService`（evaluate last-match-wins / ask 阻塞 / reply 幂等 / ALWAYS 级联 / REJECT 级联 / 4 种模式）
- [ ] `WildcardMatcher`（`*`/`?`、Windows 大小写、分隔符归一、Pattern 缓存）
- [ ] `RulesetMerger`（4 层合并 + 简写/展开两种配置形态）
- [ ] `PermissionPatternResolver`（项目内短路 / directoryTree / external_directory）
- [ ] `BashCommandParser`（引号/转义/子 shell/重定向/env 前缀/wrapper 跳过）
- [ ] `BashArityTable`（最长前缀匹配）
- [ ] `PermissionScopeResolver`（parent_id 链冒泡，防环 8 层）
- [ ] `DoomLoopDetector`
- [ ] `QuestionService` + `AskUserQuestionTool`（校验 1-4/2-4/12/60/保留标签拒绝）
- [ ] `ReadTool`（文本/图片降采样/PDF/行号/双阈值截断/stampRead/LSP 预热）
- [ ] `WriteTool`（assertRead/建父目录/原子写/LSP 后验证）
- [ ] `EditTool`（12 步完整流程）+ `ReplacerChain` 9 策略 + `DiffRenderer`（unified + dedent）
- [ ] `BashTool` + `ShellExecutor`（三方竞速 + 流式落盘 + 8192 chunk）+ `ProcessTreeKiller`
- [ ] `ShellResultBuilder`（超时/退出码/信号标注）
- [ ] `GrepTool` + `GlobTool` + `RipgrepClient`（退出码 0/1/≥2 语义）+ `NioSearchFallback`
- [ ] `GlobResolver`（绝对 glob → (pattern, baseDir)）
- [ ] `AtomicFileWriter` + `PathSafety`（穿越防护 + realpath + isInside）
- [ ] 编辑策略链 17 个用例（E-01~E-17）全绿
- [ ] 端到端：读-改-跑完整任务 + 权限全流程

### M3 上下文与压缩
- [ ] `SystemPromptAssembler`（6 块固定顺序 + 家族选择 + 块级缓存 + 小时级时间）
- [ ] `EnvInfoRenderer`
- [ ] `ContextContributor` SPI + 9 个实现 + `ReminderInjector`（source 去重 / persistent vs 内存态）
- [ ] `HistoryConverter`（Part 折叠 + displayOnly/ignored 过滤 + 微压缩占位 + thinking 回传）
- [ ] `MessageNormalizer`（孤儿 tool_use 修复 / 交替合并 / Anthropic id 清洗 / OpenAI 配对校验）
- [ ] `OverflowDetector`（三时机 + autoBuffer 环境变量覆盖）
- [ ] `PreservedTailPlanner`（API round 分组 + 倒序累加 + 至少保留 1 round）
- [ ] `HistorySanitizer`（剥附件 / 剔 reasoning / tool output 头尾各 500）
- [ ] `CompactionPromptBuilder`（5 章节强制结构）
- [ ] `RetryPlanner.truncateHead`（按 round 丢头，≤3 次）
- [ ] `CompactionService.process`（8 步）+ `PostCompactionRestore`（5 类恢复）
- [ ] `ChainGuard` 熔断器（3 次 + chainKey 去重）
- [ ] `MicroCompactor`（空闲阈值 + 保留最近 N + 占位符含原文件路径 + metadata 记录）
- [ ] `CompactedHistoryFilter`（+ SQL 下推优化）
- [ ] `/compact [指令]` 手动压缩
- [ ] `FakeModelProvider` + Loop 集成测试（压缩三策略 + 熔断 + 压缩后续跑）
- [ ] 缓存稳定性断言（`assertSystemBlocksStable`）+ 命中率 ≥ 90% 验证

### M4 快照与回滚
- [ ] `GitCliSnapshotService`（init/config/exclude 同步 .gitignore/track/patch/diffFull/restore/revert）
- [ ] `ShadowGitInitializer`（Windows longpaths/autocrlf/fsmonitor）
- [ ] Step 快照锚点接入（StepStartPart / StepFinishPart）
- [ ] `RevertService`（listAnchors / revert CONVERSATION / revert BOTH / unrevert / cleanup）
- [ ] `HistoryReader` 边界软过滤
- [ ] `SummaryDiffCalculator`（异步回填 UserSummary + session.summary_* 列）
- [ ] 快照失败降级（不阻断 Loop）
- [ ] 回滚端到端测试（改坏 → 回滚 → unrevert）

### M5 CLI
- [ ] picocli 命令树（10 个子命令 + 懒加载）
- [ ] JLine 3 `LineReader`（多行 / 历史持久化 / 括号粘贴模式）
- [ ] `SlashCommandCompleter` + `FileMentionCompleter`（@ 模糊 + 缓存）+ `LineRangeCompleter`（#L10-20）
- [ ] `SlashCommandRegistry`（22 个命令）+ 用户 markdown command 加载与模板渲染
- [ ] `StreamingRenderer`（reasoning 折叠 / text markdown 增量 / 工具卡片原地刷新 / spinner）
- [ ] `MarkdownCliRenderer`（commonmark-java → AttributedString）
- [ ] `KeyBindingDispatcher`（Esc 级联 8 层 / Ctrl+C 级联 3 层 / Ctrl+L / Ctrl+R / Ctrl+X Ctrl+K）
- [ ] `CliPermissionPrompt`（diff 预览 + once/always/reject + 附加说明）
- [ ] `CliQuestionPrompt`（多问 + 多选 + preview + 自由输入）
- [ ] `ClipboardImageGrabber`（三平台）+ `PastedTextReferenceCodec`（>800 字符折叠）
- [ ] `HeadlessRunner` + 三种 output-format + `--allowedTools` 白名单 + `--dangerously-skip-permissions`
- [ ] 中断清理 10 个用例（I-01~I-10）全绿

### M6 Web 控制台
- [ ] Spring Boot 3 + 虚拟线程 + `TokenFilter`（常量时间比较 + loopback 二次校验）
- [ ] `SseSubscriber`（有界队列 + 泵虚拟线程 + critical 不丢 + 环形重放 200 + 15s 心跳）
- [ ] REST 全量端点（§8.2）+ DTO + 错误码表 + 游标分页
- [ ] Vue 3 + Vite + TS + Pinia + Tailwind 骨架
- [ ] `stores/events.ts` 单一 SSE 订阅点 → 分发到各 store
- [ ] `MessageList.vue` 虚拟滚动（万级 Part）
- [ ] 11 种 Part 渲染组件 + 7 种工具卡片（diff2html / ansi_up / shiki）
- [ ] `Composer.vue`（@ 与 / 补全、图片粘贴、多行）
- [ ] `PermissionDialog.vue` + `QuestionDialog.vue`（preview 侧栏 markdown）
- [ ] 12 个面板（model/provider/config/resume/rewind/tasks/todos/mcp/status/theme/permission）
- [ ] 用量图表（每轮 token 堆叠：input/output/cacheRead/cacheWrite）
- [ ] CLI ↔ Web 双端联动测试（CLI 提问 / Web 回答）
- [ ] SSE 断线重连 + Last-Event-ID 重放测试
- [ ] 前端构建产物打进 jar + `we0j web` 子命令

### M7 Skills / 后台 / Todo / Task / Plan
- [ ] `YamlFrontmatterParser`（snakeyaml）
- [ ] `SkillScanner`（分层 + 项目覆盖全局 + disable 列表）
- [ ] `SkillWatcher`（WatchService + 2s mtime 指纹轮询兜底）
- [ ] `SkillTool`（`{{WE0J_*}}` 占位符展开 + `<skill>` 包裹 + 记录 invoked）
- [ ] `SkillsContributor`（渐进式披露 reminder）
- [ ] `AgentRegistry`（4 个内置人格 + markdown 分层扫描）
- [ ] `AgentTool`（子 Session + overlay 屏蔽 + 严格权限 + 模型选择 + 前后台双模式）
- [ ] `BackgroundTaskManager`（信号量 10 + 状态机 + 完成回调）
- [ ] `AgentOutputWriter`（JSONL + 单写虚拟线程 + 10MB 截断）
- [ ] `ShellManager`（后台 shell + tail + kill）
- [ ] `NotificationService.pushOrResume`（忙入队 / 闲落库唤醒）+ `TaskNotificationCodec`
- [ ] `BackgroundNotificationContributor`（`<task-notification>` reminder）
- [ ] `TodoService` + `TodoWriteTool` / `TodoReadTool`（至多一个 in_progress 警告）
- [ ] `TaskService`（CRUD + 双向依赖维护 + openBlockedBy + DELETED 物理移除）
- [ ] `TaskCreate/Get/Update/List/Output/Stop` 六个工具
- [ ] `EnterPlanMode` / `ExitPlanMode`（工具集收敛 + plan_exit 审批 + Loop break 重启）
- [ ] 子 Agent 端到端（前台 + 后台 + 权限冒泡 + TaskOutput 阻塞 + TaskStop）

### M8 收尾
- [ ] `ToolSearchTool`（select: / 关键词 / +required 打分，单次 ≤3）
- [ ] `ToolActivationManager`（激活 + 持久化 + resume 重建 + 压缩后重建）
- [ ] `DeferredToolSearchCodec`（Anthropic tool_reference / OpenAI 内嵌 schema / none）
- [ ] `DeferredToolsContributor`（`<available-deferred-tools>` reminder）
- [ ] `EnterWorktree` / `ExitWorktree`（JGit 或 git CLI + 无变更自动清理）
- [ ] `WebFetchTool` / `WebSearchTool`（jsoup 正文提取 + answer 模式）
- [ ] `LlmTracer`（JSONL + 轮转 + 有界队列 + exporter SPI）
- [ ] `UsageAggregator` + `/status` 面板数据 + `ContextUsage` 四态
- [ ] `we0j gc --older-than 30d`
- [ ] 性能调优：AppCDS + spring-context-indexer + resume SQL 下推 + 冷启动 ≤2.5s
- [ ] 压力测试：Bus 10k/s 有序、100 并发 SSE、1 万 Part resume、零 pinning
- [ ] 内存泄漏测试（100 轮 heap 稳定）
- [ ] **自举验证**：用 We0J 修复 We0J 自身 ≥5 个真实 bug 并提交
- [ ] 全量验收 A-01 ~ A-20
- [ ] `README.md` + `AGENTS.md`（Java 版编码约定）+ 架构文档
- [ ] v0.1.0 发布

---

## 附录 A：从原项目移植的资产清单

| 资产 | 原路径 | Java 目标 | 处理方式 |
|---|---|---|---|
| 核心提示词 | `core/session/prompts/*.py`（`CORE_ANTHROPIC` 等 4 套） | `we0j-agent/src/main/resources/prompts/core-*.md` | **重写**（保留结构与要点，不逐字复制），去掉 IM 相关段落 |
| Agent 人格 | `core/agent/`（build/plan/explore markdown） | `we0j-agent/src/main/resources/agents/*.md` | 直接移植（格式已兼容） |
| 压缩提示词 | `core/session/compaction.py` 内嵌 | `resources/prompts/compaction-*.md` | 重写为 5 章节强制结构 |
| 工具描述 | 各 `builtins/*.py` 的 `@mcp.tool(description=...)` | `resources/tool-descriptions/<Name>.md` | 重写（描述质量直接决定模型行为，值得逐条打磨） |
| 配置模板 | `resources/example.settings.json` | `resources/templates/settings.template.json` | 字段名 camelCase 化，去掉 `we0.*` 段 |
| ARITY 表 | `core/permission/arity.py` | `BashArityTable` 常量 | 直接移植 |
| 溢出错误特征 | `core/provider/error.py`（13 条正则 + marker） | `OverflowPatterns` | 直接移植（正则跨语言通用） |
| 模型信息表 | `litellm.get_model_info` | `ModelInfoTable` 内置 JSON | 手工整理主流 30 个模型的 contextWindow / maxOutput / pricing |
| Skill 示例 | `resources/we0/skills/*/SKILL.md` | `~/.we0j/skills/` | **格式完全兼容，可直接复用**（去掉 IM 专用的 selfie/music/travel） |
| 9 级替换策略 | `core/mcp/builtins/utils/replacers.py` | `ReplacerChain` + 9 个 Strategy | 按语义重写，用 E-01~E-17 用例对齐行为 |
| ripgrep 参数 | `core/mcp/builtins/grep.py` / `glob.py` | `RipgrepClient` | 直接移植命令行参数 |
| shadow git 命令序列 | `core/snapshot/snapshot.py` | `GitCliSnapshotService` | 直接移植命令序列 |

## 附录 B：与原项目的差异清单（架构改进，面试可讲）

| # | 原项目 | We0J | 理由 |
|---|---|---|---|
| 1 | `core/` ↔ `core/we0/` 双向依赖（~11 处） | 严格单向分层 + ArchUnit 强制"无 IM 语义" | 消除循环依赖，内核可独立演进 |
| 2 | `inject_system_reminders()` 400+ 行巨型函数 | `ContextContributor` SPI × 9 个 bean | 可插拔、可单测、新增 reminder 零改核心 |
| 3 | litellm 黑盒归一化 + 2 处 monkey-patch | 自研 `ModelProvider` SPI + 显式事件映射 | 无 monkey-patch；事件语义完全可控可测；fixture 回放验证 |
| 4 | 无版本化 migration（`PRAGMA table_info` 自愈 ALTER） | Flyway 版本化 + 迁移前自动备份 | 可追溯、可回滚、生产可用 |
| 5 | 每个 delta 都写库 | `PartWriteThrottler`（100ms/4KB/终态三条件） | SQLite 写压力降 1-2 个数量级 |
| 6 | token 估算 `len//4` | jtokkit 精确计数 + provider usage 校准 | 请求前可预判溢出，减少 400 往返 |
| 7 | `loop.max_steps` 注册但未强制 | 强制执行 + 用户可见提示 | 防失控循环（真实风险） |
| 8 | 部分会话运行时状态在内存/文件，重启丢失 | 统一到 `session.runtime_state` JSON 列 | resume 完整性（FR-013） |
| 9 | TUI 轮询快照渲染（0.25s/0.5s） | Bus 推送到 CLI/Web，delta 级增量渲染 | 延迟从 250-500ms 降到 <50ms |
| 10 | tree-sitter-bash 完整 AST | 自研递归下降解析器（~300 行） | MVP 只需子命令切分 + 前缀 + 路径，去掉 native 依赖 |
| 11 | 手写 LSP JSON-RPC 帧 | LSP4J（P2） | 减少协议层维护成本 |
| 12 | 手写 cron 解析 + 分钟步进（≤527040 次迭代） | Quartz `CronExpression`（P2） | 工业级解析，无需自研 |
| 13 | asyncio 单事件循环（一处阻塞全局卡） | 虚拟线程 thread-per-task（隔离性好） | 单个工具的慢 IO 不影响其他会话 |
| 14 | `getattr` 动态访问（约定禁止但存在） | record + sealed + 模式匹配（编译期保证） | 类型安全前移到编译期 |
