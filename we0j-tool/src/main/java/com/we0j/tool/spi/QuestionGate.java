package com.we0j.tool.spi;

import com.we0j.common.domain.question.QuestionRequest;
import com.we0j.infra.concurrency.AbortSignal;

import java.util.List;

/** 提问门控（FR-078）：AskUserQuestion 工具经由它把问卷挂到运行时并阻塞等待。 */
public interface QuestionGate {

    /** 阻塞等待用户回答；拒绝 → QuestionRejectedException；abort → AbortedException。 */
    List<List<String>> ask(QuestionRequest request, AbortSignal abort);
}
