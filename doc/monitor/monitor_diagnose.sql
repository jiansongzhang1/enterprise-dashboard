-- ============================================================================
-- 监控管理 · 为什么没触发 / 没发消息：逐条规则诊断（dashboard_db，只读）
-- 用法：psql ... -v site="'SITE-IN'" -f monitor_diagnose.sql
-- 时间都是 IST 墙钟时间（与库里存的一致）
-- ============================================================================

\if :{?site}
\else
  \set site '''SITE-IN'''
\endif

-- ---------- 0. 现在是几点（IST），判定引擎应该处理到哪个小时 ----------
SELECT (now() AT TIME ZONE 'Asia/Kolkata')                                   AS now_ist,
       date_trunc('hour', now() AT TIME ZONE 'Asia/Kolkata') - interval '1 hour' AS expect_data_hour;

-- ---------- 1. 判定进度：最近 12 个数据小时（waiting 一直不变 = 引擎没跑或 UDS 水位没到） ----------
SELECT data_hour, status, check_count, last_check_at, judged_at
  FROM monitor_hour_check
 WHERE site_code = :site
 ORDER BY data_hour DESC
 LIMIT 12;

-- ---------- 2. 逐条规则：卡在哪一步 ----------
WITH s AS (
    SELECT r.rule_id, r.rule_no, r.metric_code, r.enabled, r.judge_type,
           r.warn_low, r.warn_high, r.crit_low, r.crit_high,
           r.period_hours, r.duration_count, r.min_sample_key, r.min_sample_value,
           r.notify_chat_id, r.last_judged_at, r.judge_error, r.last_notified_at, r.mute_hours,
           r.create_time, r.update_time,
           st.last_data_hour, st.recent_periods,
           (SELECT string_agg(coalesce(p ->> 'level', '?'), ' ' ORDER BY ord)
              FROM jsonb_array_elements(coalesce(st.recent_periods, '[]'::jsonb)) WITH ORDINALITY AS t(p, ord)) AS recent_levels,
           (SELECT string_agg(coalesce(p ->> 'value', 'null'), ' | ' ORDER BY ord)
              FROM jsonb_array_elements(coalesce(st.recent_periods, '[]'::jsonb)) WITH ORDINALITY AS t(p, ord)) AS recent_values,
           e.event_id, e.level AS event_level, e.notify_status
      FROM monitor_rule r
      LEFT JOIN monitor_rule_state st ON st.rule_id = r.rule_id AND st.site_code = r.site_code
      LEFT JOIN monitor_event e       ON e.rule_id = r.rule_id AND e.site_code = r.site_code
                                     AND e.event_type = 'alert' AND e.status = 'open'
     WHERE r.site_code = :site AND r.del_flag = '0'
)
SELECT 'R-' || lpad(rule_no::text, 3, '0') AS rule, metric_code, judge_type,
       coalesce(warn_low, warn_high)::text || ' / ' || coalesce(crit_low, crit_high)::text AS warn_crit,
       period_hours || 'h×' || duration_count AS period_n,
       min_sample_key || '≥' || min_sample_value AS min_sample,
       last_data_hour, last_judged_at,
       recent_levels, recent_values,
       event_id, event_level, notify_status,
       CASE
         WHEN NOT enabled                       THEN '已暂停，不判定'
         WHEN last_data_hour IS NULL            THEN '还没判定过（新建/启用后从下一个完整小时开始，等该小时数据落库，最晚 :50）'
         WHEN judge_error IS NOT NULL           THEN '判定失败：' || judge_error
         WHEN recent_periods IS NULL OR jsonb_array_length(recent_periods) = 0
                                                THEN '已处理到 ' || to_char(last_data_hour, 'MM-DD HH24:00') || '，但还没有完整的检测周期（周期 > 1 小时要等周期结束）'
         WHEN event_id IS NOT NULL AND event_level = 'WARN'
                                                THEN '已触发·注意（注意只记录、不发群；升为严重才发）'
         WHEN event_id IS NOT NULL AND notify_status = 'muted'
                                                THEN '已触发·严重，静默期内未发送'
         WHEN event_id IS NOT NULL AND notify_status = 'unset'
                                                THEN '已触发·严重，规则没配通知群'
         WHEN event_id IS NOT NULL              THEN '已触发·严重，通知状态 ' || notify_status || '（看第 4 段发送记录）'
         WHEN recent_levels !~ '(WARN|CRIT)' AND recent_levels ~ 'SKIP' AND recent_levels !~ 'OK'
                                                THEN '最近周期都没判定：样本量不足（' || min_sample_key || ' < ' || min_sample_value || '）或该小时数据为空 / 跌幅缺基准'
         WHEN recent_levels ~ '(WARN|CRIT)'     THEN '有越线，但还没连续 ' || duration_count || ' 次（被 OK/SKIP 打断也会清零）'
         ELSE '最近周期都在阈值内，正常'
       END AS diagnosis
  FROM s
 ORDER BY rule_no;

-- ---------- 3. 最近的告警事件 ----------
SELECT e.event_id, 'R-' || lpad(r.rule_no::text, 3, '0') AS rule, e.metric_code, e.level, e.escalated, e.status,
       e.start_hour, e.trigger_hour, e.notify_status, e.notified_at, e.notify_count, e.mute_sent_at, e.mute_hours
  FROM monitor_event e
  LEFT JOIN monitor_rule r ON r.rule_id = e.rule_id
 WHERE e.site_code = :site AND e.event_type = 'alert'
 ORDER BY e.event_id DESC
 LIMIT 30;

-- ---------- 4. 发送记录（pending 一直不变 = 发送任务没跑；failed 看 error_msg） ----------
SELECT n.id, n.event_id, n.chat_id, n.status, n.retry_count, n.next_retry_at, n.sent_at, n.error_msg, n.create_time
  FROM monitor_notify_log n
 WHERE n.site_code = :site
 ORDER BY n.id DESC
 LIMIT 20;

-- ---------- 5. 通知群状态（规则引用的群是不是 dead） ----------
SELECT r.rule_no, r.metric_code, r.notify_chat_id, ch.name, coalesce(ch.status, '不在群缓存里') AS chat_status
  FROM monitor_rule r
  LEFT JOIN monitor_lark_chat ch ON ch.site_code = r.site_code AND ch.chat_id = r.notify_chat_id
 WHERE r.site_code = :site AND r.del_flag = '0'
 ORDER BY r.rule_no;
