-- !!! 已并入 sql/release_next_postgresql.sql 第 ① 段（2026-11-02），请执行那个文件，本文件不再维护。
-- ============================================================================
-- Yata Copilot · 订阅简报 AI 摘要（2026-11-02）· PostgreSQL（dashboard_db）· 可重复执行
--
--   1. dashboard_subscription 加 ai_enabled（卡片带 AI 摘要，默认关）、ai_model（模型 id，为空用 copilot.default-model）
--   2. copilot_usage_log：每次调用模型记一行（功能、站点、模型、结果、token 用量、耗时），用于对账和每日次数上限
--
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/add_copilot_ai_postgresql.sql（在 add_dashboard_subscription 之后、部署新 JAR 之前）
-- 回滚：ALTER TABLE dashboard_subscription DROP COLUMN IF EXISTS ai_model, DROP COLUMN IF EXISTS ai_enabled;
--       DROP TABLE IF EXISTS copilot_usage_log;
-- ============================================================================

BEGIN;

ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS ai_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE dashboard_subscription ADD COLUMN IF NOT EXISTS ai_model   VARCHAR(64);
COMMENT ON COLUMN dashboard_subscription.ai_enabled IS '卡片带 AI 摘要（Yata Copilot）；生成失败时照常发送、不带摘要';
COMMENT ON COLUMN dashboard_subscription.ai_model   IS 'AI 模型 id（如 claude-haiku-5-5）；为空用 copilot.default-model';

CREATE TABLE IF NOT EXISTS copilot_usage_log (
    id              BIGSERIAL       PRIMARY KEY,
    feature         VARCHAR(32)     NOT NULL,
    site_code       VARCHAR(32),
    ref_id          VARCHAR(64),
    model           VARCHAR(64)     NOT NULL,
    status          VARCHAR(16)     NOT NULL,
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    latency_ms      INTEGER,
    error           VARCHAR(500),
    operator        VARCHAR(64),
    create_time     TIMESTAMP       NOT NULL DEFAULT localtimestamp,
    CONSTRAINT ck_copilot_usage_status CHECK (status IN ('SUCCESS', 'FAILED', 'REJECTED'))
);
CREATE INDEX IF NOT EXISTS idx_copilot_usage_feature_time ON copilot_usage_log (feature, create_time);

COMMENT ON TABLE  copilot_usage_log           IS 'AI 调用记录：一次调用模型一行（对账、每日次数上限）';
COMMENT ON COLUMN copilot_usage_log.feature   IS 'BRIEF 订阅简报摘要（以后 IMPORT Excel 导入等）';
COMMENT ON COLUMN copilot_usage_log.ref_id    IS '业务 ID：订阅 ID；预览为 preview';
COMMENT ON COLUMN copilot_usage_log.status    IS 'SUCCESS 采用 / FAILED 调用失败 / REJECTED 返回了但未通过后端校验（如出现数据里没有的数字）';

COMMIT;

-- 自检：两列、一张表
SELECT column_name FROM information_schema.columns
 WHERE table_name = 'dashboard_subscription' AND column_name IN ('ai_enabled', 'ai_model') ORDER BY 1;
SELECT to_regclass('copilot_usage_log') AS usage_table;

-- 对账：本月按模型汇总 token
-- SELECT model, count(*) AS calls, sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens
--   FROM copilot_usage_log WHERE create_time >= date_trunc('month', current_date) GROUP BY model;
