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

        public AiResult(String text, String model, int inputTokens, int outputTokens, String stopReason)
        {
            this.text = text;
            this.model = model;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.stopReason = stopReason;
        }

        public String getText() { return text; }
        public String getModel() { return model; }
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public String getStopReason() { return stopReason; }
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
