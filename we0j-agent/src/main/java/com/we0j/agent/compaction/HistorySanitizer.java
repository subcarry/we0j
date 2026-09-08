package com.we0j.agent.compaction;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.message.Message;
import com.we0j.common.domain.part.FilePart;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.ReasoningPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.domain.part.ToolState;
import com.we0j.common.util.Ulids;
import com.we0j.llm.spi.ModelCard;
import com.we0j.llm.spi.ProviderMessage;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 历史清洗（DDD §5.5.3）：摘要前降低摘要请求本身的 token 量。
 * <ul>
 *   <li>剥离 FilePart（图片/附件）→ "[attachment omitted: &lt;filename&gt;]" 占位（保留文件名线索）；</li>
 *   <li>reasoning part 全部剔除（思考过程对摘要价值低、体积大）；</li>
 *   <li>超长 tool output（&gt;1100 字符）→ 头 500 + "\n...[truncated N chars]...\n" + 尾 500；</li>
 *   <li>保留：文件路径、命令原文、错误信息原文 —— 摘要提示词要求逐字保留的关键事实。</li>
 * </ul>
 */
public final class HistorySanitizer {

    static final int OUTPUT_HEAD = 500;
    static final int OUTPUT_TAIL = 500;
    /** 超过 head+tail+100 才裁剪（留 100 字符余量，避免临界输出被无意义改写）。 */
    static final int OUTPUT_LIMIT = OUTPUT_HEAD + OUTPUT_TAIL + 100;

    public List<ProviderMessage> sanitize(List<MessageWithParts> toSummarize, ModelCard card) {
        List<MessageWithParts> cleaned = toSummarize.stream().map(this::cleanMessage).toList();
        return HistoryCodec.convert(cleaned);
    }

    MessageWithParts cleanMessage(MessageWithParts m) {
        List<Part> parts = m.parts().stream().flatMap(p -> switch (p) {
            case ReasoningPart ignored -> Stream.<Part>empty();                              // 剔除
            case FilePart f -> Stream.<Part>of(new TextPart(Ulids.next(), m.message().id(),
                    m.message().sessionId(), "[attachment omitted: " + f.filename() + "]",
                    Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, null, Map.of()));
            case ToolPart t -> Stream.<Part>of(shrinkToolOutput(t));
            default -> Stream.<Part>of(p);
        }).toList();
        return new MessageWithParts(m.message(), parts);
    }

    private Part shrinkToolOutput(ToolPart t) {
        if (!(t.state() instanceof ToolState.Completed c)) return t;
        String out = c.output();
        if (out == null || out.length() <= OUTPUT_LIMIT) return t;
        int omitted = out.length() - OUTPUT_HEAD - OUTPUT_TAIL;
        String shrunk = out.substring(0, OUTPUT_HEAD)
                + "\n...[truncated " + omitted + " chars]...\n"
                + out.substring(out.length() - OUTPUT_TAIL);
        return t.withState(c.withOutput(shrunk));
    }

    /** 清洗不改消息本体；导出给 restore 复用的消息文本探针。 */
    static String firstText(MessageWithParts m, String fallback) {
        for (Part p : m.parts()) {
            if (p instanceof TextPart tp && tp.text() != null && !tp.text().isBlank()) return tp.text();
        }
        return fallback;
    }

    static boolean isUser(MessageWithParts m) {
        Message msg = m.message();
        return msg instanceof com.we0j.common.domain.message.UserMessage;
    }
}
