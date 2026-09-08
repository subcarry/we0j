package com.we0j.llm.token;

import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ModelInfoTable;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 内置模型信息表（DDD §5.3.7 / FR-038）：litellm.get_model_info 等价物的**手工整理子集**，
 * 覆盖主流 30 个模型的 contextWindow / maxOutput（含 providerId 维度消歧），带 LRU 4096 缓存。
 * 数据表与查询逻辑收敛在 {@link ContextWindowResolver}（同一份内置表，避免双份维护漂移）。
 * 新增模型只需扩表，无需改动解析顺序。
 */
@Component
public class ModelInfoTableImpl implements ModelInfoTable {

    @Override
    public Optional<Integer> contextWindow(ModelCard card) {
        return ContextWindowResolver.findContextWindow(card);
    }

    @Override
    public Optional<Integer> maxOutput(ModelCard card) {
        return ContextWindowResolver.findMaxOutput(card);
    }
}
