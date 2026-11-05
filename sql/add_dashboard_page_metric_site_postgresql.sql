-- ============================================================================
-- 运营总览 / 指标汇总按站点调整指标的顺序与显示（2026-10-30）· PostgreSQL（dashboard_db）· 可重复执行
--
-- dashboard_page_metric 是全站默认；本表按站点覆盖：某站点在某页面上，指标排第几（sort_no 从小到大）、显不显示（visible）。
--   * 站点没有任何配置 = 全站默认顺序、全部显示；
--   * 站点配置里没有的指标（之后新上的指标）按全站默认顺序排在已配置的后面、默认显示；
--   * 接口只返回显示的指标，sortNo 为站点内的名次（1、2、3…），前端按 sortNo 直接展示，不再按分组重排。
-- 在「系统管理 › 站点管理 › 指標配置」里维护（权限 system:site:edit），不需要手工写 SQL。
-- 站点改编码时随其他带 site_code 的表一起级联更新（按 information_schema 自动找到本表）。
--
-- 执行：psql -X -v ON_ERROR_STOP=1 -d dashboard_db -f sql/add_dashboard_page_metric_site_postgresql.sql
-- 回滚：DROP TABLE IF EXISTS dashboard_page_metric_site;（各站点恢复全站默认）
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS dashboard_page_metric_site (
    id              BIGSERIAL       PRIMARY KEY,
    site_code       VARCHAR(32)     NOT NULL,
    page_code       VARCHAR(32)     NOT NULL,
    metric_code     VARCHAR(64)     NOT NULL,
    sort_no         INT             NOT NULL,
    visible         BOOLEAN         NOT NULL DEFAULT TRUE,
    update_by       VARCHAR(64)     DEFAULT '',
    update_time     TIMESTAMP       DEFAULT localtimestamp,
    CONSTRAINT uk_page_metric_site UNIQUE (site_code, page_code, metric_code),
    CONSTRAINT ck_page_metric_site_page CHECK (page_code IN ('OVERVIEW', 'SUMMARY')),
    CONSTRAINT fk_page_metric_site_code FOREIGN KEY (metric_code)
        REFERENCES dashboard_metric_card (metric_code) ON DELETE CASCADE
);

COMMENT ON TABLE  dashboard_page_metric_site             IS '按站点调整运营总览 / 指标汇总的指标顺序与显示；没有配置的站点、指标按 dashboard_page_metric 全站默认';
COMMENT ON COLUMN dashboard_page_metric_site.site_code   IS '站点编码（monitor_site_setting.site_code）';
COMMENT ON COLUMN dashboard_page_metric_site.page_code   IS '页面：OVERVIEW 运营总览 / SUMMARY 指标汇总';
COMMENT ON COLUMN dashboard_page_metric_site.metric_code IS '指标编码（须挂在该页面上，dashboard_page_metric）';
COMMENT ON COLUMN dashboard_page_metric_site.sort_no     IS '站点内顺序，从小到大';
COMMENT ON COLUMN dashboard_page_metric_site.visible     IS '是否显示；FALSE = 该站点的该页面不返回这个指标';

COMMIT;

-- 自检：表已建好（1 行）
SELECT to_regclass('dashboard_page_metric_site') AS site_layout_table;
