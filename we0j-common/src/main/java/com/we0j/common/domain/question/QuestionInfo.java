package com.we0j.common.domain.question;

import com.we0j.common.exception.ToolException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 单个问题（FR-078）：question + header(≤12) + options(2-4) + multiSelect。 */
public record QuestionInfo(
        @NotBlank String question,
        @Size(max = 12) String header,
        @Size(min = 2, max = 4) List<QuestionOption> options,
        Boolean multiSelect) {

    /** 运行时拒绝模型自行编写保留标签（系统自动附加，模型重复编写会破坏 UI）。 */
    private static final java.util.Set<String> RESERVED =
            java.util.Set.of("other", "type something.", "type something");

    public void validateNotReserved() {
        if (options == null) throw new ToolException("Question options must not be null.");
        for (QuestionOption o : options) {
            if (RESERVED.contains(o.label().trim().toLowerCase(Locale.ROOT))) {
                throw new ToolException(
                        "Reserved option label is not allowed: '" + o.label()
                                + "'. Reserved labels are appended by the runtime, do not author them.");
            }
        }
    }
}
