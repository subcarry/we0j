package com.we0j.llm.registry;

import com.we0j.common.exception.ModelException;
import com.we0j.infra.concurrency.AbortSignal;
import com.we0j.llm.spi.ChatRequest;
import com.we0j.llm.spi.EventStream;
import com.we0j.llm.spi.ModelProvider;
import com.we0j.llm.transform.ParamDropper;
import org.springframework.stereotype.Component;

/**
 * 模型调用门面（DDD §5.3.8）：Loop 唯一入口 —— 选 Provider → ParamDropper 剔除不支持参数 → 拉流。
 *
 * <p>顺序与 DDD 伪码一致：先 forCard 解析 provider（apply 需要 provider.id() 作剔除规则键，
 * card.providerId 是自定义名如 tokenrhythm，不能直接喂 ParamDropper），未命中抛 ModelException。
 *
 * <p>预留钩子（本期不实现）：ObservabilityExporter —— 在 openStream 前后记录
 * beforeRequest(req, effective) / 流关闭后 aggregatedUsage 的 model/latency/tokens 导出；
 * 挂接点即本方法内 provider.openStream 调用两侧。
 */
@Component
public final class ModelClient {

    private final ProviderRegistry providers;
    private final ParamDropper paramDropper;

    public ModelClient(ProviderRegistry providers, ParamDropper paramDropper) {
        this.providers = providers;
        this.paramDropper = paramDropper;
    }

    /** 打开流式事件流；调用方负责 close()（try-with-resources），abort 由 AbortSignal 传播。 */
    public EventStream openStream(ChatRequest req, AbortSignal abort) {
        ModelProvider provider = providers.forCard(req.model())
                .orElseThrow(() -> new ModelException(
                        "no provider for model " + req.model().qualifiedId()));
        ChatRequest effective = paramDropper.apply(req, provider.id());
        // TODO(observability): ObservabilityExporter.beforeRequest(req, effective)
        EventStream stream = provider.openStream(effective, abort);
        // TODO(observability): 包装 stream，close 后导出 latency / aggregatedUsage
        return stream;
    }
}
