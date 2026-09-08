package com.we0j.common.domain.part;

import java.math.BigDecimal;

/** 步骤完成锚点：携带该步用量与成本（G-06 成本核算的最小单位）。 */
public record StepFinishPart(String id, String messageId, String sessionId,
                             String snapshot, BigDecimal cost, Tokens tokens) implements Part {}
