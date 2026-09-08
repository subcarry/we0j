package com.we0j.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 分层与架构约束守护（DDD §9.3，CI 门禁）。
 *
 * <p>5 条规则：
 * <ol>
 *   <li>模块分层单向依赖：Common ← Infra ← Llm ← Tool ← Agent ← Cli / Server</li>
 *   <li>★ agent 内核不得出现 IM/companion 语义（消除原项目双向耦合的架构改进验证）</li>
 *   <li>common 纯净：零 Spring 依赖（纯 POJO/record 层）</li>
 *   <li>★ 禁 langchain4j / spring-ai（项目核心价值 = 自研 Loop）</li>
 *   <li>★ 禁 synchronized 方法（虚拟线程 pinning，NFR-01；统一 ReentrantLock）</li>
 * </ol>
 */
@AnalyzeClasses(packages = "com.we0j", importOptions = ImportOption.DoNotIncludeTests.class)
public class LayeringTest {

    /** 规则 1：分层单向依赖（§9.3 layeredArchitecture）。 */
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
            .whereLayer("Infra").mayOnlyBeAccessedByLayers("Llm", "Tool", "Agent", "Cli", "Server")
            .whereLayer("Llm").mayOnlyBeAccessedByLayers("Tool", "Agent", "Cli", "Server")
            .whereLayer("Tool").mayOnlyBeAccessedByLayers("Agent", "Cli", "Server")
            .whereLayer("Agent").mayOnlyBeAccessedByLayers("Cli", "Server")
            .whereLayer("Cli").mayNotBeAccessedByAnyLayer()
            .whereLayer("Server").mayNotBeAccessedByAnyLayer()
            .withOptionalLayers(true);   // 允许空层（模块尚无类）

    /** 规则 2 ★：agent 包禁止依赖任何 IM/companion 语义类。 */
    @ArchTest
    static final ArchRule no_im_semantics_in_agent_core = noClasses()
            .that().resideInAPackage("com.we0j.agent..")
            .should().dependOnClassesThat()
            .haveNameMatching(".*(Telegram|Companion|Heartbeat|Emotion|Selfie|Lyria).*")
            .as("agent core must stay free of IM/companion semantics (架构改进 #1)")
            .allowEmptyShould(true);   // agent 模块尚无类，避免"未检查到任何类"失败

    /** 规则 3：common 层零 Spring 依赖。 */
    @ArchTest
    static final ArchRule common_has_no_spring = noClasses()
            .that().resideInAPackage("com.we0j.common..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
            .as("common must be framework-free POJO/record layer");

    /** 规则 4 ★：禁 LangChain4j / Spring AI（自研 Loop 核心价值）。 */
    @ArchTest
    static final ArchRule no_agent_frameworks = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage("dev.langchain4j..", "org.springframework.ai..")
            .as("langchain4j / spring-ai are banned: the Loop is hand-rolled");

    /** 规则 5 ★：禁 synchronized 方法（虚拟线程 pinning，NFR-01）。 */
    @ArchTest
    static final ArchRule no_synchronized_methods = noMethods()
            .should().haveModifier(JavaModifier.SYNCHRONIZED)
            .as("synchronized causes virtual-thread pinning; use ReentrantLock");
}
