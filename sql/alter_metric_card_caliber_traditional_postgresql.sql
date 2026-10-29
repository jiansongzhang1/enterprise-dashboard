-- ============================================================================
-- 运营总览指标口径改为以库为准（2026-10-28）· PostgreSQL（dashboard_db）· 可重复执行
--
-- 运营总览 overview 返回的 expression（指标卡口径、计算构成口径）原来写死在代码里（按原型的繁体），
-- 现在取 dashboard_metric_card.remark 里「口徑：」那一行（后台「系统管理 · 指标管理」的「口徑與備註」）。
-- 本脚本把 ⑫ 写入的 28 条简体口径换成页面一直在显示的繁体口径（原型；原子指标按 MON-26「總訂單數」写法），
-- 上线前后页面显示不变；「備註：」等其余行保留。
--
--   1. 备份 remark 到 dashboard_metric_card_remark_bak_20261028（只在第一次执行时写入）
--   2. 只改第一行还是 ⑫ 原值的行：后台改过口径的不覆盖；remark 为空的补上口径
--
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/alter_metric_card_caliber_traditional_postgresql.sql
-- 回滚：UPDATE dashboard_metric_card c SET remark = b.remark
--         FROM dashboard_metric_card_remark_bak_20261028 b WHERE b.metric_code = c.metric_code;
--      （监控管理一期整体回滚时 02_rollback 按 0.5 的整表备份恢复，并删除本备份表）
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS dashboard_metric_card_remark_bak_20261028 (
    metric_code  VARCHAR(64)  PRIMARY KEY,
    remark       TEXT,
    backup_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO dashboard_metric_card_remark_bak_20261028 (metric_code, remark)
SELECT metric_code, remark FROM dashboard_metric_card
ON CONFLICT (metric_code) DO NOTHING;

UPDATE dashboard_metric_card c
   SET remark = v.caliber || CASE WHEN position(E'\n' IN coalesce(c.remark, '')) > 0
                                  THEN substr(c.remark, position(E'\n' IN c.remark)) ELSE '' END,
       update_by = 'admin', update_time = CURRENT_TIMESTAMP
  FROM (VALUES
    ('reg', '口径：完成会员注册的人数，按注册时间（IST）归入所属小时。', '口徑：完成會員註冊的人數'),
    ('ftd', '口径：完成生涯首笔存款的人数，按首笔存款成功时间（IST）归入所属小时。', '口徑：完成生涯首筆存款的人數'),
    ('ftdr', '口径：首存人数 ÷ 注册人数 × 100%。', '口徑：首存人數 ÷ 註冊人數 × 100%'),
    ('ftdA', '口径：首存总金额 ÷ 首存人数。', '口徑：首存總金額 ÷ 首存人數'),
    ('dep', '口径：所有会员成功存款金额加总（站点币种主单位）。', '口徑：所有會員成功存款金額加總'),
    ('wd', '口径：所有会员成功提款金额加总（站点币种主单位）。', '口徑：所有會員成功提款金額加總'),
    ('net', '口径：存款总额 − 提款总额，可为负。', '口徑：存款總額 − 提款總額'),
    ('arppu', '口径：存款总额 ÷ 去重存款人数；按小时／日去重，仅支持查看单日数据。', '口徑：存款總額 ÷ 存款人數（同一人只計一次）。支援按小時／日去重，僅支援查看單日資料。'),
    ('turnX', '口径：投注总额 ÷ 存款总额。', '口徑：投注總額 ÷ 存款總額'),
    ('dOkR', '口径：存款成功笔数 ÷ 存款总尝试笔数 × 100%。', '口徑：存款成功筆數 ÷ 存款總訂單數 × 100%'),
    ('dT', '口径：存款自提交到入账的平均耗时，按成功笔数加权（分钟）。', '口徑：存款自送出到入帳的平均耗時'),
    ('wOkR', '口径：出款成功笔数 ÷ 出款总尝试笔数 × 100%。', '口徑：出款成功筆數 ÷ 出款總訂單數 × 100%'),
    ('wT', '口径：提款自申请到拨款完成的平均耗时，按成功笔数加权（分钟）。', '口徑：提款自申請到撥款完成的平均耗時'),
    ('active', '口径：有投注行为的去重会员数；按小时／日去重，跨时段不可相加，仅支持查看单日数据。', '口徑：有投注行為的去重會員數。支援按小時／日去重，僅支援查看單日資料。'),
    ('bet', '口径：所有投注金额加总。', '口徑：所有投注金額加總（＝總流水）'),
    ('ggr', '口径：0 − 玩家输赢（win loss amount），即投注总额 − 派彩金额。', '口徑：0 − 玩家輸贏（win loss amount）'),
    ('killR', '口径：GGR ÷ 投注总额 × 100%。', '口徑：GGR ÷ 投注總額'),
    ('bonus', '口径：各赠金项目实际发放金额加总。', '口徑：各贈金項目實際發放金額加總'),
    ('bonusR', '口径：发放赠金总额 ÷ 投注总额 × 100%。', '口徑：贈金發放總額 ÷ 總流水 × 100%'),
    ('ngr', '口径：GGR − 发放赠金总额。', '口徑：GGR − 發放贈金總額'),
    ('login', '口径：完成登录的去重会员数；按小时／日去重，跨时段不可相加。', '口徑：完成登入的去重會員數'),
    ('depU', '口径：有成功存款的去重会员数；按小时／日去重。', '口徑：有成功存款的去重會員數；按小時／日去重'),
    ('ftdAmt', '口径：首笔存款金额加总。', '口徑：首筆存款金額加總'),
    ('pay', '口径：派彩金额 = 投注总额 + 玩家输赢（win_los_amt）。', '口徑：派彩金額 = 投注總額 + 玩家輸贏（win_los_amt）'),
    ('dOk', '口径：成功的存款订单笔数。', '口徑：成功的存款訂單筆數'),
    ('dTry', '口径：存款总尝试笔数（发起的存款订单数）。', '口徑：存款總訂單數（發起的存款訂單數，不限狀態）'),
    ('wOk', '口径：成功的提款订单笔数。', '口徑：成功的提款訂單筆數'),
    ('wTry', '口径：提款总尝试笔数（发起的提款订单数）。', '口徑：提款總訂單數（發起的提款訂單數，不限狀態）')
  ) AS v(code, seeded, caliber)
 WHERE c.metric_code = v.code
   AND (coalesce(c.remark, '') = '' OR split_part(c.remark, E'\n', 1) = v.seeded);

COMMIT;

-- 自检：口径还不是繁体「口徑：」开头的（应为 0 行；有行说明是后台改过，人工确认）
SELECT metric_code, split_part(remark, E'\n', 1) AS caliber
  FROM dashboard_metric_card
 WHERE status = '0' AND coalesce(remark, '') NOT LIKE '口徑：%'
 ORDER BY metric_code;
