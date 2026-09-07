# We0J —— Java 版 Coding Agent 运行时

> 面向真实软件工程任务的 Coding Agent 运行时（对标 Claude Code 产品形态）。
> **Java 21 虚拟线程 + Spring Boot 3.4**，UI 形态：CLI（picocli + JLine 3）+ 本地 Web 控制台（SSE + Vue 3）。
> 自研 Agent Loop 双循环内核，**不依赖 LangChain4j / Spring AI**。

## 当前状态

`文档阶段完成 → M0 工程骨架开发中`（里程碑计划见下方文档）

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 工程骨架（7 模块 + 领域模型 + 基础设施） | 🚧 进行中 |
| M1 | Provider 层与最小 Agent Loop（SSE 事件归一化） | ⬜ |
| M2 | 工具体系与权限引擎 | ⬜ |
| M3 | 上下文工程与压缩 | ⬜ |
| M4 | 快照与回滚（shadow git） | ⬜ |
| M5 | CLI | ⬜ |
| M6 | Web 控制台 | ⬜ |
| M7 | Skills / 后台任务 / Plan | ⬜ |
| M8 | 收尾 + 自举验证 v0.1.0 | ⬜ |

## 文档

| 文档 | 说明 |
|---|---|
| [docs/00-README-导航.md](docs/00-README-导航.md) | 项目概述、原项目（We0 Code）架构速览、范围界定、术语表 |
| [docs/01-需求文档-SRS.md](docs/01-需求文档-SRS.md) | 需求规格说明书：92 条功能需求、非功能需求、验收标准、里程碑 |
| [docs/02-详细设计文档-DDD.md](docs/02-详细设计文档-DDD.md) | 详细设计：技术选型映射、模块设计、领域模型、DDL、API 协议、实施 Checklist |

## 开发环境

- JDK 21 LTS（Temurin）+ Maven 3.9.11（阿里云镜像，本地仓库 `D:\dev\maven-repo`）
- 全局 Java 8（公司项目）不受影响；开发 We0J 前在终端执行 `D:\dev\we0j-env.cmd` 切换会话到 JDK 21
- 详见 `docs/02-详细设计文档-DDD.md` §1.2 依赖清单

## License

暂未定（待 M8 发布时确定）
