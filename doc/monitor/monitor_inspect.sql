-- ============================================================================
-- 监控管理 · 查看规则与告警事件（dashboard_db，只读）
-- 时间字段都是 IST 墙钟时间。站点按需改 :site（psql：\set site 'SITE-IN'）。
-- 用法：psql ... -v site="'SITE-IN'" -f monitor_inspect.sql，或在 psql 里先 \set site '''SITE-IN''' 再 \i
-- ============================================================================

\if :{?site}
\else
  \set site '''SITE-IN'''
\endif

-- ---------- 1. 已建立的规则（未删除） ----------
SELECT r.rule_id,
       'R-' || lpad(r.rule_no::text, 3, '0')               AS rule_code,
       r.metric_code,
       coalesce(c.metric_name, r.metric_code)               AS metric_name,
       r.judge_type,
       r.warn_low, r.warn_high, r.crit_low, r.crit_high,
       r.period_hours || 'h × ' || r.duration_count         AS period_x_count,
       r.escalate_hours, r.mute_hours,
       r.min_sample_key, r.min_sample_value,
       r.enabled,
       u.email                                              AS owner_email,
       coalesce(ch.name, r.notify_chat_name)                AS chat_name,
       coalesce(ch.status, 'dead')                          AS chat_status,
       s.last_data_hour,
       r.last_judged_at, r.judge_error, r.last_notified_at,
       r.update_by, r.update_time
  FROM monitor_rule r
  LEFT JOIN dashboard_metric_card c ON c.metric_code = r.metric_code
  LEFT JOIN sys_user u              ON u.user_id = r.owner_user_id
  LEFT JOIN monitor_lark_chat ch    ON ch.site_code = r.site_code AND ch.chat_id = r.notify_chat_id
  LEFT JOIN monitor_rule_state s    ON s.rule_id = r.rule_id
 WHERE r.site_code = :site AND r.del_flag = '0'
 ORDER BY r.rule_no;

-- ---------- 2. 规则运行状态：最近几个周期的值和等级 ----------
SELECT r.rule_id, 'R-' || lpad(r.rule_no::text, 3, '0') AS rule_code, r.metric_code,
       s.run_start_hour, s.open_event_id, s.last_period_end, s.last_data_hour,
       p.ord, p.item ->> 'from' AS period_from, p.item ->> 'to' AS period_to,
       p.item ->> 'value' AS value, p.item ->> 'baseline' AS baseline,
       p.item ->> 'level' AS level, p.item ->> 'escalated' AS escalated
  FROM monitor_rule r
  JOIN monitor_rule_state s ON s.rule_id = r.rule_id
  LEFT JOIN LATERAL jsonb_array_elements(s.recent_periods) WITH ORDINALITY AS p(item, ord) ON TRUE
 WHERE r.site_code = :site AND r.del_flag = '0'
 ORDER BY r.rule_no, p.ord;

-- ---------- 3. 历史告警事件（含已删除规则的事件），异常中排前 ----------
SELECT e.event_id,
       'R-' || lpad(r.rule_no::text, 3, '0')               AS rule_code,
       e.metric_code,
       coalesce(c.metric_name, e.metric_code)               AS metric_name,
       e.level, e.peak_level, e.escalated,
       e.status,                                            -- open 异常中 / resolved 已恢复 / stopped 规则暂停或删除
       e.start_hour, e.trigger_hour, e.end_hour, e.last_period_end,
       extract(epoch FROM coalesce(e.end_hour, e.last_period_end) - e.start_hour)::int / 3600 AS duration_hours,
       e.current_value, e.baseline_value,
       e.rule_snapshot ->> 'judgeType'                       AS judge_type,
       e.rule_snapshot -> 'crit'                             AS crit,
       e.rule_snapshot -> 'warn'                             AS warn,
       e.notify_status, e.notified_at, e.notify_count,
       e.handle_status, e.handled_level, e.handle_reset, hu.email AS handled_by, e.handled_at, e.handle_note,
       e.stopped_at,
       r.del_flag = '2'                                      AS rule_deleted
  FROM monitor_event e
  LEFT JOIN monitor_rule r          ON r.rule_id = e.rule_id
  LEFT JOIN dashboard_metric_card c ON c.metric_code = e.metric_code
  LEFT JOIN sys_user hu             ON hu.user_id = e.handled_by
 WHERE e.site_code = :site AND e.event_type = 'alert'
 ORDER BY (e.status = 'open') DESC,
          CASE WHEN e.status = 'open' AND e.level = 'CRIT' THEN 0 ELSE 1 END,
          CASE WHEN e.status = 'open' THEN e.start_hour END ASC,
          e.trigger_hour DESC
 LIMIT 100;

-- ---------- 4. 每个事件的发送记录 ----------
SELECT n.id, n.event_id, n.msg_type, n.chat_id, n.status, n.retry_count, n.next_retry_at,
       n.sent_at, n.success, n.error_msg, n.create_time,
       left(n.content, 80) AS content_head
  FROM monitor_notify_log n
 WHERE n.site_code = :site
 ORDER BY n.id DESC
 LIMIT 50;

-- ---------- 5. 数据缺失：不再写事件记录（2026-10-22），看各规则最近一次处理的小时和判定结果 ----------
SELECT r.rule_id, r.metric_code, st.last_data_hour, st.recent_levels, r.last_judged_at, r.judge_error
  FROM monitor_rule r
  LEFT JOIN monitor_rule_state st ON st.rule_id = r.rule_id
 WHERE r.site_code = :site AND r.del_flag = '0' AND r.enabled
 ORDER BY r.rule_no;

-- ---------- 6. 判定进度：最近 24 个数据小时 ----------
SELECT data_hour, status, check_count, last_check_at, judged_at
  FROM monitor_hour_check
 WHERE site_code = :site
 ORDER BY data_hour DESC
 LIMIT 24;

-- ---------- 7. 汇总 ----------
SELECT count(*) FILTER (WHERE status = 'open')                          AS open_events,
       count(*) FILTER (WHERE status = 'open' AND level = 'CRIT')       AS open_crit,
       count(*) FILTER (WHERE handle_status = 'pending')                AS pending_handle,
       count(*) FILTER (WHERE trigger_hour >= date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata')) AS triggered_today_ist,
       count(*)                                                         AS total_alert_events
  FROM monitor_event
 WHERE site_code = :site AND event_type = 'alert';
