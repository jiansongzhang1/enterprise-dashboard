-- dashboard_metric_card 新增 single_day_only：仅支持单日查询的指标
--
-- 背景：活跃人数、ARPPU 上游只提供按自然日的口径（活跃人数是日去重，ARPPU 的分母是日存款人数），
--       跨天区间查出来的数没有业务含义。运营总览首页在区间跨越多个自然日时，
--       这类指标卡不向 UDS 查询、也不返回给前端，并在 notices 里给出 SINGLE_DAY_ONLY_HIDDEN 提示。
--
-- 可重复执行。执行后需重启服务（或刷新指标配置缓存）才生效。
-- 注意：先执行本脚本再发布新版后端——新版后端加载指标配置时会读这一列，列不存在会导致配置加载失败。

ALTER TABLE dashboard_metric_card
    ADD COLUMN IF NOT EXISTS single_day_only BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN dashboard_metric_card.single_day_only IS '仅支持单日查询：区间跨天时运营总览不查询、不展示';

UPDATE dashboard_metric_card
   SET single_day_only = TRUE, update_time = CURRENT_TIMESTAMP
 WHERE metric_code IN ('active', 'arppu');

-- 自检：应返回 active、arppu 两行
SELECT metric_code, metric_name, single_day_only
  FROM dashboard_metric_card
 WHERE single_day_only = TRUE
 ORDER BY sort_no;
