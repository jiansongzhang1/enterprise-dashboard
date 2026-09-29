-- ============================================================================
-- 页面 ↔ 指标 对应配置
--
-- 边界：page_code 是枚举（Java 侧 DashboardPage），metric_code 是配置。
--   页面有独立 Controller、独立权限标识、独立返回体结构，新增页面必然要写代码，
--   做成表只会让「表里有一行、代码里没实现」的情况静默 404。
--   而「哪个指标出现在哪个页面、排第几、默认选不选」是运营会调的，不该发版。
--   证据：原型 #58 已经改过一次——登录人数从指标墙撤下但保留在汇总表列选择器（NOWALL），
--   分组也从 g1~g4 重划成 eg1~eg4。一年内改过的东西不该写死在代码里。
-- ============================================================================

DROP TABLE IF EXISTS dashboard_page_metric;
DROP TABLE IF EXISTS dashboard_metric_group;

-- ---------------------------------------------------------------------------
-- 指标分组：只存分组本身与显示名，成员关系在 dashboard_metric_card.group_code
-- ---------------------------------------------------------------------------
CREATE TABLE dashboard_metric_group (
    group_code      VARCHAR(32)     PRIMARY KEY,
    group_name      VARCHAR(64)     NOT NULL,
    group_name_en   VARCHAR(64),
    sort_no         INT             NOT NULL DEFAULT 0,
    status          CHAR(1)         NOT NULL DEFAULT '0',
    remark          VARCHAR(500)
);

COMMENT ON TABLE dashboard_metric_group IS '指标分组字典，对齐原型 EG';

INSERT INTO dashboard_metric_group (group_code, group_name, group_name_en, sort_no, remark) VALUES
('eg1','获客与转化','Acquisition & conversion',10,'组内顺序即业务与计算顺序，原子量在前，派生量紧跟算出它的原子量，不可重排'),
('eg2','资金流','Cash flow',20,'组内顺序即业务与计算顺序，原子量在前，派生量紧跟算出它的原子量，不可重排'),
('eg3','通道健康度','Channel health',30,'组内顺序即业务与计算顺序，原子量在前，派生量紧跟算出它的原子量，不可重排'),
('eg4','投注与盈收','Betting & revenue',40,'组内顺序即业务与计算顺序，原子量在前，派生量紧跟算出它的原子量，不可重排');

-- ---------------------------------------------------------------------------
-- 页面 ↔ 指标
-- ---------------------------------------------------------------------------
CREATE TABLE dashboard_page_metric (
    id              BIGSERIAL       PRIMARY KEY,

    -- 与 Java 枚举 DashboardPage 一一对应。加值必须同时改枚举与这条 CHECK，
    -- 这个「麻烦」是故意的：它保证配置里不会出现没有实现的页面
    page_code       VARCHAR(32)     NOT NULL,

    metric_code     VARCHAR(64)     NOT NULL,

    -- 页内顺序。留出间隔，插入一个指标不必重排整页
    sort_no         INT             NOT NULL DEFAULT 0,

    -- 是否默认选中。指标汇总的列选择器用它决定初始列；
    -- 指标墙没有选择器，全部为 TRUE
    is_default      BOOLEAN         NOT NULL DEFAULT TRUE,

    -- 视觉权重。CORE 在指标墙上是大号卡；汇总表不使用此列
    emphasis        VARCHAR(8)      NOT NULL DEFAULT 'NORMAL',

    status          CHAR(1)         NOT NULL DEFAULT '0',
    create_by       VARCHAR(64)     DEFAULT '',
    create_time     TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_by       VARCHAR(64)     DEFAULT '',
    update_time     TIMESTAMP,
    remark          VARCHAR(500),

    CONSTRAINT uk_page_metric UNIQUE (page_code, metric_code),
    CONSTRAINT ck_page_code CHECK (page_code IN ('OVERVIEW','SUMMARY')),
    CONSTRAINT ck_emphasis  CHECK (emphasis  IN ('CORE','NORMAL')),

    -- 稀疏属性收紧：emphasis 只对指标墙有意义，is_default 只对汇总表有意义。
    -- 不管住的话，无意义的那一列会被随手改成任意值，不报错也没效果——
    -- 直到哪天给汇总表加了「大号列」功能，那些历史脏值会突然意外生效
    CONSTRAINT ck_page_attrs CHECK (
        (page_code = 'OVERVIEW' AND is_default = TRUE)
        OR (page_code = 'SUMMARY' AND emphasis = 'NORMAL')
    ),

    -- 引用完整性：写错一个字母插不进去，而不是等到页面少一张卡才发现
    CONSTRAINT fk_page_metric_code FOREIGN KEY (metric_code)
        REFERENCES dashboard_metric_card (metric_code)
);

CREATE INDEX idx_page_metric ON dashboard_page_metric (page_code, sort_no);

COMMENT ON TABLE  dashboard_page_metric IS '页面与指标的对应关系、顺序、默认选中与视觉权重';
COMMENT ON COLUMN dashboard_page_metric.page_code  IS 'OVERVIEW 运营总览指标墙 / SUMMARY 指标汇总表';
COMMENT ON COLUMN dashboard_page_metric.is_default IS '指标汇总列选择器的初始列；指标墙恒 TRUE';
COMMENT ON COLUMN dashboard_page_metric.emphasis   IS 'CORE 大号卡；仅指标墙使用';

-- ===== OVERVIEW 运营总览 · 指标墙：20 个（21 去掉登录人数）=====
-- 登录人数运营不看盘，从卡片墙撤下，但保留在汇总表的列选择器里——撤的是版面，不是数据
INSERT INTO dashboard_page_metric (page_code, metric_code, sort_no, is_default, emphasis, create_by, remark) VALUES
('OVERVIEW','reg',10,TRUE,'NORMAL','admin','注册人数'),
('OVERVIEW','ftd',20,TRUE,'CORE','admin','首存人数'),
('OVERVIEW','ftdr',30,TRUE,'NORMAL','admin','首存转化率'),
('OVERVIEW','ftdA',40,TRUE,'NORMAL','admin','首存ARPPU'),
('OVERVIEW','dep',50,TRUE,'CORE','admin','存款总额'),
('OVERVIEW','wd',60,TRUE,'CORE','admin','提款总额'),
('OVERVIEW','net',70,TRUE,'CORE','admin','存提差'),
('OVERVIEW','arppu',80,TRUE,'NORMAL','admin','ARPPU'),
('OVERVIEW','turnX',90,TRUE,'NORMAL','admin','流水倍数'),
('OVERVIEW','dOkR',100,TRUE,'NORMAL','admin','存款成功率'),
('OVERVIEW','dT',110,TRUE,'NORMAL','admin','平均到帐时间'),
('OVERVIEW','wOkR',120,TRUE,'NORMAL','admin','出款成功率'),
('OVERVIEW','wT',130,TRUE,'NORMAL','admin','平均出款时间'),
('OVERVIEW','active',140,TRUE,'CORE','admin','活跃人数'),
('OVERVIEW','bet',150,TRUE,'CORE','admin','投注总额'),
('OVERVIEW','ggr',160,TRUE,'CORE','admin','GGR'),
('OVERVIEW','killR',170,TRUE,'NORMAL','admin','平均杀率'),
('OVERVIEW','bonus',180,TRUE,'NORMAL','admin','发放赠金总额'),
('OVERVIEW','bonusR',190,TRUE,'NORMAL','admin','赠金比'),
('OVERVIEW','ngr',200,TRUE,'CORE','admin','NGR');

-- ===== SUMMARY 指标汇总表：21 个全部可选，默认选中 8 个核心指标 =====
-- 默认列顺序沿用 EG 的既定顺序，与指标墙的大号卡是同一套 8 个，不另立一套
INSERT INTO dashboard_page_metric (page_code, metric_code, sort_no, is_default, emphasis, create_by, remark) VALUES
('SUMMARY','login',10,FALSE,'NORMAL','admin','登录人数'),
('SUMMARY','reg',20,FALSE,'NORMAL','admin','注册人数'),
('SUMMARY','ftd',30,TRUE,'NORMAL','admin','首存人数'),
('SUMMARY','ftdr',40,FALSE,'NORMAL','admin','首存转化率'),
('SUMMARY','ftdA',50,FALSE,'NORMAL','admin','首存ARPPU'),
('SUMMARY','dep',60,TRUE,'NORMAL','admin','存款总额'),
('SUMMARY','wd',70,TRUE,'NORMAL','admin','提款总额'),
('SUMMARY','net',80,TRUE,'NORMAL','admin','存提差'),
('SUMMARY','arppu',90,FALSE,'NORMAL','admin','ARPPU'),
('SUMMARY','turnX',100,FALSE,'NORMAL','admin','流水倍数'),
('SUMMARY','dOkR',110,FALSE,'NORMAL','admin','存款成功率'),
('SUMMARY','dT',120,FALSE,'NORMAL','admin','平均到帐时间'),
('SUMMARY','wOkR',130,FALSE,'NORMAL','admin','出款成功率'),
('SUMMARY','wT',140,FALSE,'NORMAL','admin','平均出款时间'),
('SUMMARY','active',150,TRUE,'NORMAL','admin','活跃人数'),
('SUMMARY','bet',160,TRUE,'NORMAL','admin','投注总额'),
('SUMMARY','ggr',170,TRUE,'NORMAL','admin','GGR'),
('SUMMARY','killR',180,FALSE,'NORMAL','admin','平均杀率'),
('SUMMARY','bonus',190,FALSE,'NORMAL','admin','发放赠金总额'),
('SUMMARY','bonusR',200,FALSE,'NORMAL','admin','赠金比'),
('SUMMARY','ngr',210,TRUE,'NORMAL','admin','NGR');

-- ---------------------------------------------------------------------------
-- 自检：配置装完跑一遍，比等页面少一张卡再回头查要便宜得多
-- ---------------------------------------------------------------------------
-- 1) 每页指标数：OVERVIEW 应为 20，SUMMARY 应为 21
--    SELECT page_code, count(*) FROM dashboard_page_metric GROUP BY page_code;
-- 2) 默认列数：OVERVIEW 20，SUMMARY 8
--    SELECT page_code, count(*) FROM dashboard_page_metric WHERE is_default GROUP BY page_code;
-- 3) 大号卡应为 8 个，且必须同时出现在两个页面
--    SELECT metric_code FROM dashboard_page_metric WHERE emphasis = 'CORE';
-- 4) 有没有指标一个页面都没进（新增了卡但忘了挂页面）
--    SELECT c.metric_code FROM dashboard_metric_card c
--     LEFT JOIN dashboard_page_metric p ON p.metric_code = c.metric_code
--     WHERE c.show_as_card AND p.id IS NULL;
-- 5) 有没有隐藏原子量被误挂到页面上（它们不是「列」）
--    SELECT p.page_code, p.metric_code FROM dashboard_page_metric p
--     JOIN dashboard_metric_card c ON c.metric_code = p.metric_code
--     WHERE c.show_as_card = FALSE;
-- 6) 【最容易真实发生】页面挂着已停用的指标。外键只管 DELETE，管不住 status 改成 '1'
--    SELECT p.page_code, p.metric_code, c.metric_name FROM dashboard_page_metric p
--     JOIN dashboard_metric_card c ON c.metric_code = p.metric_code
--     WHERE p.status = '0' AND c.status <> '0';
-- 7) 两个页面的核心集是否仍然一致（不是错误，是提醒——分叉了要有人知道）
--    SELECT 'wall_core_only' AS side, metric_code FROM dashboard_page_metric
--     WHERE page_code = 'OVERVIEW' AND emphasis = 'CORE'
--     AND metric_code NOT IN (SELECT metric_code FROM dashboard_page_metric
--                             WHERE page_code = 'SUMMARY' AND is_default)
--    UNION ALL
--    SELECT 'sum_default_only', metric_code FROM dashboard_page_metric
--     WHERE page_code = 'SUMMARY' AND is_default
--     AND metric_code NOT IN (SELECT metric_code FROM dashboard_page_metric
--                             WHERE page_code = 'OVERVIEW' AND emphasis = 'CORE');
-- 8) 派生指标的操作数有没有被停用（分子停了，派生指标会静默变 null）
--    SELECT c.metric_code, c.calc_type, o.metric_code AS operand, o.status
--      FROM dashboard_metric_card c
--      JOIN dashboard_metric_card o
--        ON o.metric_code IN (c.numerator_code, c.denominator_code)
--     WHERE c.calc_type <> 'NONE' AND c.status = '0' AND o.status <> '0';

