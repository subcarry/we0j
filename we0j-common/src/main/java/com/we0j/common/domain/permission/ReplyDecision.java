package com.we0j.common.domain.permission;

/** ONCE 回复可携带用户附加说明，作为 &lt;permission_feedback&gt; 追加到工具输出（FR-084）。 */
public record ReplyDecision(Reply reply, String userMessage) {}
