# Skills 热加载增强方案（现状验证 + 缺口修复）

> 结论先行：**双轨热加载（WatchService + 2s mtime 指纹）在运行中的 server 上实测是工作的**
> （新增/删除 skill 均无需重启即生效，验证记录见 §1.3）。用户"似乎没有热加载"的感知来自
> 四个真实缺口（§2），按 §4 的分期方案修复。

---

## 1. 现状核查

### 1.1 组件与接线（均已存在）

| 组件 | 文件 | 职责 |
|---|---|---|
| `SkillScanner` | `we0j-agent/.../skill/SkillScanner.java` | 分层扫描：`DirectoryLayout.globalSkillsDir()`（`~/.we0j/skills`）→ `<projectRoot>/.we0j/skills` 覆盖同名；解析 `SKILL.md` frontmatter |
| `SkillStore` | `we0j-agent/.../skill/SkillStore.java` | 只读快照 `byName` + `AtomicBoolean pendingRescan` 脏标记 |
| `SkillWatcher` | `we0j-agent/.../skill/SkillWatcher.java` | 虚拟线程双轨：①WatchService 递归注册 roots；②每 2s 目录树 mtime SHA-256 指纹比对；命中 → `store.markPendingRescan()`；roots 变化经 `registerMissing()` 每轮补注册 |
| `SkillService` | `we0j-agent/.../skill/SkillService.java` | `refresh()` 全量重扫；`refreshIfPending(root)` drain 脏标记；`startWatcher(root)` |
| 装配 | `RuntimeBootstrap.init()` L275-287 | 启动时 `refresh(root)` + `startWatcher(root)`（异常降级仅 warn） |
| 消费点 | `SkillsContributor.render()` L63-67 | **每轮 reminder 注入点** drain：`refreshIfPending(ctx.projectRoot())` 后渲染 `<available-skills>` |

### 1.2 生效链路

```
文件变更 → watcher 置脏（≤2.2s）→ 下一轮 reminder 注入 drain → scan → 快照替换
→ 本轮 system-reminder 携带新 <available-skills> → SKILL 工具 lookup 即时可查
```

### 1.3 运行实例实测证据（we0j-server, PID 33896, 无重启）

| 步骤 | 结果 |
|---|---|
| 在 `~/.we0j/skills/` 新建 `hot-e2e-probe-741/SKILL.md`，等 5s，LLM 探针调 SKILL(bogus) | 错误回显 `Available skills: hot-e2e-probe-741, notes-build-smoke, notes-clean-smoke` ✅ 新增被热感知 |
| `rm -rf` 该 skill 目录，等 6s，再次探针 | `Available skills: notes-build-smoke, notes-clean-smoke` ✅ 删除被热感知 |

---

## 2. 真实缺口（"看不到热加载"的根因）

**G1 · Watcher roots 与会话 workdir 不一致（最关键）**
`startWatcher(root)` 只监视 `global + 启动根/.we0j/skills`（RuntimeBootstrap L284）。
但 web server 的会话可指定**任意 workdir**（`POST /api/sessions {workdir}`），
`refreshIfPending` 又按 `ctx.projectRoot()` 重扫。
→ **在非启动根的项目 `.we0j/skills` 下增删 skill：指纹不变、永不置脏、永不重扫**——
表现为"改了没反应"，与用户感知吻合。

**G2 · 零可观测性**
无 `GET /api/skills`；Web 控制台无 Skills 面板；无法确认"当前进程到底加载了哪些 skill、
上次重扫是什么时候、监视哪些目录"。改了文件没地方验证，只能靠和模型对话猜。

**G3 · 失败静默**
`SKILL.md` frontmatter 非法 / `name:` 与目录名不一致 / 编码问题 → scanner 跳过该 skill，
仅 debug 级日志。用户以为"热加载失效"，实为"该 skill 从未有效"。

**G4 · 降级无感知**
`RuntimeBootstrap` 里 skills init 包在 try/catch，watcher 启动失败只 warn 一行，
之后进程退化为"启动时快照常驻"。用户无从得知。

---

## 3. 设计原则

- 复用双轨架构，只做增量，不引入新依赖（禁 synchronized / 虚拟线程约束不变）；
- 遵守 ArchUnit 分层：`server → agent → tool → llm → infra → common`，观测 API 只读透传；
- 热路径零开销：快照替换是引用赋值，drain 是 CAS；指纹轮询成本不变；
- ★ **缓存契约（§3.5）是硬约束**：任何实现不得退化 prompt cache 命中率。

### 3.5 缓存契约（硬约束，违者否决合入）

背景事实（代码口径，决定爆炸半径）：

1. skills reminder 是 **ephemeral**（`SkillsContributor.persistent()=false`）：每轮在内存重建、
   挂在 lastUser 尾部、**不落库** → 永不进入历史稳定前缀；
2. 打点策略 `CacheStrategy.DEFAULT` = system 前 2 块 + 末 2 条消息尾块；Anthropic 按最长
   公共前缀命中 → skills 块变化只影响其后字节，**tools + system + 旧历史 cache 不动**；
3. 渲染已是确定性：`scanDir` 已 `.sorted()`，`toSystemReminder()` 只输出 name+description
   → 内容不变 ⇒ 字节不变 ⇒ **TTL 重扫/置脏误触发对缓存零成本**。

因此单次真实热加载的代价 = 尾部 1 个 block 重写（约 300~500 token），语义上不可避免。
但以下两个陷阱会把影响放大成“每轮 miss + 每轮重写（写价 1.25×）”：

**陷阱 1 · 单快照多 root 交替覆写（最关键）**
`SkillStore.byName` 当前是全局单份；若按 P1 把 N 个活跃会话 workdir 纳入监视，
A/B 两个不同项目的会话每轮 `refresh(root)` 互相覆写快照 → 同一会话尾部 reminder 字节
在 A/B 清单间反复横跳 → **互相打穿 suffix cache**。这也是正确性缺陷（会话能看到
别的项目的 skill）。**必须随 P1 落地 per-root 快照（见 P1′）。**

**陷阱 2 · reminder 掺入 volatile 字段**
若在 `<skill>` 标签里加 location 绝对路径 / mtime / scannedAt，或遍历 `metadata`
（`Map.copyOf` 迭代序不定）→ 每轮字节都变 → steady state 从“前缀全命中”退化为
“每轮 miss + 重写”。新字段一律进 `GET /api/skills`，**不进 prompt**。

契约条款：

| # | 条款 |
|---|---|
| C1 | reminder 文本 = 快照的纯函数：确定性排序、无 volatile 字段、无 Map 迭代 |
| C2 | 快照替换用内容 equals 短路：equals 则保留旧引用（字节必然不变，且省分配） |
| C3 | per-root 快照隔离，会话永不互写 |
| C4 | ephemeral 地位不变：skills 清单绝不进 system 块（那里是全量前缀缓存，一动全爆炸） |
| C5 | 回归门禁：10 轮无变更对话，`/status` 的 `hitRate` 相对基线下降 ≤2pp；改一次 skill，只允许一次性小 dip |

## 4. 分期方案

### P0 · 可观测性（G2/G4，改动最小，收益最大）

1. `SkillStore` 增加扫描元数据（不可变对象随快照一起替换）：
   ```java
   public record ScanMeta(Instant scannedAt, int total, List<String> failed, List<Path> roots) {}
   ```
2. `SkillService` 增加 `scanMeta()` / `watcherRunning()` 只读透传；
3. 新端点 `GET /api/skills`（server 层新建 `SkillController`，构造器注入 SkillService bean）：
   ```json
   { "skills": [{"name","description","location","source":"global|project"}],
     "scannedAt": "...", "watcherRunning": true,
     "watchedRoots": ["C:\\Users\\25068\\.we0j\\skills", "D:\\dev\\downloads\\w0j_m2_e2e\\.we0j\\skills"],
     "failed": ["bad-skill: frontmatter missing name"] }
   ```
   （`RuntimeBootstrap` 需暴露 skillService bean：已有 `skillService()` L613，直接 `@Bean` 导出）
4. Web 控制台加 `skills` 面板（PANELS + `loadPanel` 分支 + 列表模板），显示
   `watcherRunning` 徽标、监视根、每个 skill 来源；
5. CLI `doctor` 增加 skills 检查项（扫描数 / 失败数 / 目录存在性）。

### P1 · 动态根（G1）+ P1′ · per-root 快照（陷阱 1 的强制配套，同批交付）

**P1′：`SkillStore` 快照结构改造（缓存契约 C3/C2 的落地）**

```java
// SkillStore：单快照 → per-root 缓存（键 = 归一化绝对路径）
private final ConcurrentMap<Path, RootSnapshot> byRoot;   // RootSnapshot(List<SkillCard> cards, ScanMeta meta)
public List<SkillCard> snapshotFor(Path projectRoot) { ... }   // 未命中时才 scan；C2：equals 短路换引用
```

`SkillsContributor.render()` 改用 `service.snapshotFor(ctx.projectRoot())`：
不同 workdir 的会话各取各的清单，互不覆写（同时修复会话间 skill 串扰的正确性 bug）。

**P1：`SkillWatcher` 支持“动态根 supplier”，每轮指纹计算/补注册时合并**，替代固定 roots：

```java
// SkillWatcher 增构造参数（保留旧签名委托）：
SkillWatcher(SkillStore store, List<Path> roots, Supplier<List<Path>> dynamicRoots)
// loop() 每 POLL 周期：
List<Path> all = mergedRoots();      // roots ∪ dynamicRoots.get()，去重
```

- `SkillService.startWatcher(root, Supplier<List<Path>> sessionDirs)`；
- RuntimeBootstrap 接线：supplier 取 `SessionRegistry` 活跃会话的 `workdir/.we0j/skills`
  （agent 内部依赖，不破坏分层）；
- 指纹计算天然覆盖新目录（`<missing>` 语义已有），首次出现的目录既进指纹也补注册 WatchService。
- 代价：每 2s 多 walk N 个目录——与现状同量级，可接受。

### P2 · 手动强制刷新（演示 / 兜底）

- `POST /api/skills/refresh`：直接 `store.scan(...)` 全量重扫并返回新 ScanMeta
  （不等脏标记，解决"刚改完 2s 内就要生效"的体感问题）；
- CLI slash `/skills reload`；Web 面板放 ⟳ 按钮调同一端点。

### P3 · 失败可见化（G3）

- `SkillScanner.scanDir` 的 catch 收集 `failed[(dir, reason)]` 进 ScanMeta（不抛出）；
  首次失败 WARN 一次（按 dir 去重限频），避免每次重扫刷屏；
- P0 的 `/api/skills` 与 Web 面板把 `failed` 渲染为红条，鼠标悬停显示原因。

### 验收标准

| 阶段 | 证据 |
|---|---|
| P0 | 改文件 → `GET /api/skills` 的 `scannedAt`/`skills` 在 ≤3s 内变化；watcher 未运行时徽标可见 |
| P1/P1′ | 集成测试：会话 A（workdir=W）新建后，在 `W/.we0j/skills` 增删 SKILL.md，不重启、不做任何手动操作，`GET /api/skills`（或探针会话）能观察到变化；`SkillWatcherTest` 扩展动态根用例；**串扰用例**：A/B 两项目各有同名 skill，交替两轮请求，A 的 reminder 字节不变（C3） |
| P1 缓存门禁 | 契约 C5：无变更 10 轮 hitRate 降幅 ≤2pp；单测断言：二次 scan 内容 equals 时 `snapshotFor` 返回同一 List 引用（C2） |
| P2 | refresh 端点 P99 < 100ms（本地小目录）；返回体与随后 GET 一致 |
| P3 | 放一个坏 frontmatter 的 skill，`failed` 中出现且带原因；好 skill 不受影响 |

## 5. 立即可用的规避（方案落地前）

1. 把 skill 放到 **`~/.we0j/skills/`（全局）**——任何项目会话都能热感知；
2. 项目级 skill 放到 **server 启动根** `D:\dev\downloads\w0j_m2_e2e\.we0j\skills\`（当前 watcher 监视的就是它）；
3. 改完等 ≥3s 再发下一条消息（dirty → 下一轮 reminder 才 drain）；
4. 临时验证手段：让模型调 `SKILL name=<不存在的名字>`，报错会列出当前全量可用清单。

## 6. 工作量估算

| 阶段 | 估算 | 说明 |
|---|---|---|
| P0 | 0.5 天 | 后端 2 文件 + controller + 前端面板 |
| P1 | 0.5 天 | SkillWatcher/SkillService/RuntimeBootstrap + 测试 |
| P1′ | 0.5 天 | SkillStore per-root 快照 + Contributor 改道 + 串扰/引用相等单测（不可拆，与 P1 同批） |
| P2 | 0.5 天 | 端点 + CLI slash + 按钮 |
| P3 | 0.5 天 | scanner 收集 + 渲染 |

依赖顺序：P0 → (P1 + P1′ 同批) → P2/P3。**P1 禁止单独交付**（无 P1′ 则违反缓存契约 C3，
见 §3.5 陷阱 1）；P0 先落地即可让 G1 问题从“玄学”变为“可见”。

---

## 7. 实现记录（2026-09-09，P0→P3 全部落地）

**变更文件**：`SkillStore.java`（per-root 快照 + ScanMeta + C2 引用短路）、
`SkillService.java`（snapshotFor/find(root)/names(root)/refreshAll/watcherRunning/watchedRoots +
startWatcher 动态根重载）、`SkillWatcher.java`（dynamicRoots supplier + mergedRoots）、
`SkillScanner.java`（scanOutcome 失败收集 + rootsOf + 限频 WARN）、`SkillsContributor.java`
（per-session 快照渲染）、`RuntimeBootstrap.java`（skillLookup 按会话 workdir + skillDirsHook
后绑定动态根）、`We0jServerApplication.java`（SkillService bean）、`SkillController.java`（新增：
GET /api/skills、POST /api/skills/refresh）、前端 Skills 面板、CLI `/skills [reload]` + doctor 检查项。

**验证结论**（运行实例，无重启热验证）：
| 项 | 结果 |
|---|---|
| agent 模块单测（含新增 4 例：C2 引用短路 / C3 串扰 / 动态根置脏 / P3 失败可见） | 18 例全绿；全量 102 例仅 2 个既有负载敏感用例超时（单跑均通过，非本改动引入） |
| 热感知（新建全局 skill → 4s 内 GET /api/skills） | total 2→3 ✅ |
| 手动刷新端点 | refreshedRoots≥1 ✅ |
| 串扰（B workdir 会话 vs 默认根） | B 视图含 b-only-skill、默认视图不含 ✅ |
| C2 稳态（连续 GET scannedAt） | 恒定不抖动 ✅ |
| Web Skills 面板（jsdom headless） | 6/6（徽标/列表/来源标签/⟳ 刷新）✅ |
| CLI doctor skills 检查 | "2 个 skill · C:\Users\25068\.we0j\skills" ✅ |
