package com.fivetech.promo.budget;

import tools.jackson.databind.JsonNode;

/**
 * 采集平台统一提交接口（《市场预算、消耗：后端端到端联调手册》第 2、3 节）：
 * {@code POST {collect_base}/v1/imports/market-cost:submit} / {@code market-budget:submit}，
 * 请求头 Authorization: Bearer、Idempotency-Key，Body {@code {"confirmation_id"?, "rows": [...]}}。
 *
 * <p>只发一次请求、原样返回状态码与响应体，不重试、不解释（由 {@link CollectBudgetSpendWriter} 处理 200 / 202 / 409 …）。</p>
 *
 * @author fivetech
 */
public interface CollectSubmitApi
{
    /**
     * @param site           站点（选 token）
     * @param kind           消耗 / 预算（选接口）
     * @param idempotencyKey 本次上传的 key，重试不变
     * @param body           UTF-8 JSON 请求体，重试原样
     */
    Response submit(String site, BudgetKind kind, String idempotencyKey, byte[] body);

    /** 响应：status = 0 表示没拿到响应（连不上、超时、断连），结果不确定 */
    final class Response
    {
        private final int status;
        private final JsonNode body;
        private final Integer retryAfterSeconds;
        private final String requestId;
        private final String error;

        public Response(int status, JsonNode body, Integer retryAfterSeconds, String requestId, String error)
        {
            this.status = status;
            this.body = body;
            this.retryAfterSeconds = retryAfterSeconds;
            this.requestId = requestId;
            this.error = error;
        }

        public int getStatus() { return status; }
        public JsonNode getBody() { return body; }
        public Integer getRetryAfterSeconds() { return retryAfterSeconds; }
        public String getRequestId() { return requestId; }
        /** status = 0 时的原因（异常类名、消息） */
        public String getError() { return error; }

        public String text(String field)
        {
            JsonNode v = body == null ? null : body.get(field);
            return v == null || v.isNull() ? null : v.asString();
        }
    }
}
