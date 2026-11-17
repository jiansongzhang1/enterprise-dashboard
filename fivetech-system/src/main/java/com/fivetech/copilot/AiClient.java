package com.fivetech.copilot;

/**
 * 调用大模型的统一入口。直连 Claude API（{@link AnthropicAiClient}）以后可以加 Bedrock 实现，业务代码不用改。
 *
 * @author fivetech
 */
public interface AiClient
{
    /**
     * 一问一答（无工具、无多轮）。
     *
     * @param model     模型 id，如 claude-haiku-5-5
     * @param system    系统提示
     * @param user      用户消息
     * @param maxTokens 最多输出 token
     * @throws AiException 调用失败（未配置、认证、限流重试后仍失败、超时等）
     */
    AiResult complete(String model, String system, String user, int maxTokens);

    /**
     * 多轮对话（无工具）。messages 按时间顺序，user / assistant 交替，最后一条是 user。
     * 默认实现把历史拼成一条用户消息（给不支持多轮的实现和测试用）。
     */
    default AiResult chat(String model, String system, java.util.List<Message> messages, int maxTokens)
    {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages)
        {
            sb.append("assistant".equals(m.getRole()) ? "助手：" : "使用者：").append(m.getContent()).append("\n\n");
        }
        return complete(model, system, sb.toString().trim(), maxTokens);
    }

    /**
     * 带工具的多轮对话：模型要求调用工具时，由 executor 执行并把结果回传，直到模型给出文字回答或用完 maxRounds 轮。
     * 默认实现不支持工具，退回 {@link #chat(String, String, java.util.List, int)}。
     *
     * @param tools     可用工具；为空时等同于不带工具
     * @param executor  执行工具，返回给模型看的结果文本（不要抛异常，失败也用文字说明）
     * @param maxRounds 最多执行几轮工具，用完后再问一次模型（不带工具）要文字回答
     */
    default AiResult chat(String model, String system, java.util.List<Message> messages, int maxTokens,
            java.util.List<Tool> tools, ToolExecutor executor, int maxRounds)
    {
        return chat(model, system, messages, maxTokens);
    }

    /** 工具定义：name 给模型调用，inputSchema 是入参的 JSON Schema */
    final class Tool
    {
        private final String name;

        private final String description;

        private final java.util.Map<String, Object> inputSchema;

        public Tool(String name, String description, java.util.Map<String, Object> inputSchema)
        {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public java.util.Map<String, Object> getInputSchema() { return inputSchema; }
    }

    /** 模型要求的一次工具调用；id 用来把结果对回去 */
    final class ToolCall
    {
        private final String id;

        private final String name;

        private final java.util.Map<String, Object> input;

        public ToolCall(String id, String name, java.util.Map<String, Object> input)
        {
            this.id = id;
            this.name = name;
            this.input = input == null ? java.util.Map.of() : input;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public java.util.Map<String, Object> getInput() { return input; }
    }

    /** 执行工具，返回给模型看的结果文本 */
    @FunctionalInterface
    interface ToolExecutor
    {
        String execute(ToolCall call);
    }

    /** 对话里的一条消息：role = user / assistant */
    final class Message
    {
        private final String role;

        private final String content;

        public Message(String role, String content)
        {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
    }

    /** 一次调用的结果 */
    final class AiResult
    {
        private final String text;

        private final String model;

        private final int inputTokens;

        private final int outputTokens;

        private final String stopReason;

        /** 这次回答里实际调用过的工具名（按顺序，可重复）；没用工具时为空 */
        private final java.util.List<String> toolsUsed;

        public AiResult(String text, String model, int inputTokens, int outputTokens, String stopReason)
        {
            this(text, model, inputTokens, outputTokens, stopReason, java.util.List.of());
        }

        public AiResult(String text, String model, int inputTokens, int outputTokens, String stopReason,
                java.util.List<String> toolsUsed)
        {
            this.text = text;
            this.model = model;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.stopReason = stopReason;
            this.toolsUsed = toolsUsed == null ? java.util.List.of() : java.util.List.copyOf(toolsUsed);
        }

        public String getText() { return text; }
        public String getModel() { return model; }
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public String getStopReason() { return stopReason; }
        public java.util.List<String> getToolsUsed() { return toolsUsed; }
    }

    /** 调用失败；message 可以直接给页面看（不含 API Key） */
    final class AiException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        private final int status;

        private final String type;

        public AiException(String message, int status, String type)
        {
            super(message);
            this.status = status;
            this.type = type;
        }

        public int getStatus() { return status; }
        public String getType() { return type; }
    }
}
