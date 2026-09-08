package com.we0j.common.domain.question;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 选项（FR-078）：label ≤60 字符，可携带 focus 时展示的 preview（markdown）。 */
public record QuestionOption(
        @NotBlank @jakarta.validation.constraints.Size(max = 60) String label,
        @NotBlank String description,
        String preview) {}
