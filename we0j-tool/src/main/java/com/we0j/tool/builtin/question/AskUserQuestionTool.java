package com.we0j.tool.builtin.question;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.common.constant.ToolNames;
import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.question.QuestionInfo;
import com.we0j.common.domain.question.QuestionRequest;
import com.we0j.common.domain.question.QuestionToolRef;
import com.we0j.common.exception.QuestionRejectedException;
import com.we0j.common.exception.ToolException;
import com.we0j.common.util.Jsons;
import com.we0j.common.util.Ulids;
import com.we0j.llm.spi.ToolDefinition;
import com.we0j.tool.spi.Audience;
import com.we0j.tool.spi.Tool;
import com.we0j.tool.spi.ToolContext;
import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import com.we0j.tool.spi.We0Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AskUserQuestion 工具（DDD §5.9，FR-078）：模型经它向用户发起 1-4 问结构化问卷，
 * 阻塞等待回答；用户放弃 → 返回安抚文本（不抛）。
 *
 * <p>deferLoading=false：问卷能力必须常驻（模型冷启动即可调用）。
 */
@We0Tool(name = ToolNames.ASK_USER_QUESTION,
        permission = PermissionName.QUESTION,
        deferLoading = false,
        description = "Ask the user one or more structured questions during execution and block "
                + "until they answer. Use to gather preferences, clarify ambiguous instructions, "
                + "or get decisions on implementation choices. 1-4 questions, 2-4 options each; "
                + "reserved labels (e.g. \"Type something.\") are appended by the runtime.")
public final class AskUserQuestionTool implements Tool {

    /** 入参契约（schema 与文档用途；实际解析走 raw → Jackson 转换）。 */
    public record Input(List<QuestionInfo> questions) {}

    @Override
    public ToolDefinition definition() {
        We0Tool a = AskUserQuestionTool.class.getAnnotation(We0Tool.class);
        return new ToolDefinition(ToolNames.ASK_USER_QUESTION,
                a.description(), inputSchema(), false, java.util.Set.of());
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext ctx) {
        List<QuestionInfo> qs = parseQuestions(input);

        QuestionRequest req = new QuestionRequest(Ulids.next(), ctx.sessionId(), qs, Map.of(),
                new QuestionToolRef(ctx.messageId(), ctx.callId()));

        List<List<String>> answers;
        try {
            answers = ctx.questions().ask(req, ctx.abort());
        } catch (QuestionRejectedException e) {
            // 用户放弃：返回安抚文本，不抛（模型据此改用 plain text 或自行判断）
            return ToolResult.text("The user dismissed the questionnaire without answering. "
                    + "Proceed with your best judgment, or ask in plain text if the decision is blocking.");
        }

        // 结果文本（对齐原项目格式）："Q"="A" 逗号分隔，多选用 " + " 连接
        StringBuilder sb = new StringBuilder("User has answered your questions: ");
        for (int i = 0; i < qs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(qs.get(i).question()).append("\"=\"")
              .append(String.join(" + ", answers.get(i))).append('"');
        }

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("questions", qs);
        structured.put("answers", answers);
        return new ToolResult(
                List.of(new ToolResult.AnnotatedBlock(sb.toString(), Audience.ASSISTANT)),
                structured, List.of());
    }

    /** raw List 元素可能是 LinkedHashMap（JSON 反序列化产物），逐项强转/转换。 */
    private static List<QuestionInfo> parseQuestions(ToolInput input) {
        Object v = input.raw().get("questions");
        if (!(v instanceof List<?> l) || l.isEmpty()) {
            throw new ToolException("Missing required parameter: questions");
        }
        List<QuestionInfo> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o instanceof QuestionInfo qi) out.add(qi);
            else out.add(Jsons.mapper().convertValue(o, QuestionInfo.class));
        }
        return out;
    }

    /** 手写 JSON Schema（victools 生成器属后续里程碑的统一 schema 链路）。 */
    private static JsonNode inputSchema() {
        ObjectNode option = Jsons.mapper().createObjectNode();
        option.put("type", "object");
        ObjectNode optProps = option.putObject("properties");
        optProps.putObject("label").put("type", "string")
                .put("description", "MAX 60 CHARACTERS. Display text the user selects.");
        optProps.putObject("description").put("type", "string")
                .put("description", "What this option means / trade-offs.");
        optProps.putObject("preview").put("type", "string")
                .put("description", "Optional markdown preview rendered when focused (single-select only).");
        option.putArray("required").add("label").add("description");

        ObjectNode question = Jsons.mapper().createObjectNode();
        question.put("type", "object");
        ObjectNode qProps = question.putObject("properties");
        qProps.putObject("question").put("type", "string")
                .put("description", "The complete question to ask the user, ending with '?'");
        qProps.putObject("header").put("type", "string")
                .put("description", "MAX 16 CHARACTERS short chip label, e.g. \"Auth method\".");
        ObjectNode qOptions = Jsons.mapper().createObjectNode();
        qOptions.put("type", "array");
        qOptions.set("items", option);
        qOptions.put("minItems", 2);
        qOptions.put("maxItems", 4);
        qProps.set("options", qOptions);
        qProps.putObject("multiSelect").put("type", "boolean")
                .put("description", "Allow selecting multiple options (default false).");
        question.putArray("required").add("question").add("header").add("options");

        ObjectNode questions = Jsons.mapper().createObjectNode();
        questions.put("type", "array");
        questions.set("items", question);
        questions.put("minItems", 1);
        questions.put("maxItems", 4);
        questions.put("description", "Questions to ask the user (1-4).");
        ObjectNode props = Jsons.mapper().createObjectNode();
        props.set("questions", questions);
        ObjectNode root = Jsons.mapper().createObjectNode();
        root.put("type", "object");
        root.set("properties", props);
        root.putArray("required").add("questions");
        return root;
    }
}
