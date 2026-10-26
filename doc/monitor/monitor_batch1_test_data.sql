-- ============================================================================
-- 监控管理第一批 · Postman 联调测试数据（dashboard_db）
--
-- 通知群来自 Lark 实时拉取（GET /monitor/lark/chats），规则保存时校验 chat_id
-- 必须在本站的群列表缓存 monitor_lark_chat 里。为了不依赖真实的 Lark 群，先用 SQL 造两条缓存：
--   oc_postman_ok    Postman 联调群   status = ok    → 集合变量 chatId
--   oc_postman_dead  Postman 失效群   status = dead  → 集合变量 deadChatId（测 4805）
-- 不会真的发消息（发送功能在第二批）。
--
-- 第 1 段：造数据（可重复执行）。chat_id 固定，Postman 集合变量已预填，无需改。
-- 注意：调用一次 GET /monitor/lark/chats（Postman 第 9 组）拉取成功后，机器人不在的 oc_postman_ok 会被置为 dead，
--       再跑第 1～8 组前重新执行第 1 段即可（会把状态改回 ok）。
-- 第 2 段：测完清理。会物理删除 Postman 建的规则并重置规则号，生产环境执行前确认没有真实规则用到这两个群。
-- ============================================================================

-- ---------- 第 1 段：造数据 ----------
INSERT INTO monitor_lark_chat (site_code, chat_id, name, status, last_seen_at, update_time)
VALUES ('SITE-IN', 'oc_postman_ok',   'Postman 聯調群', 'ok',   localtimestamp, localtimestamp),
       ('SITE-IN', 'oc_postman_dead', 'Postman 失效群', 'dead', localtimestamp - interval '1 day', localtimestamp)
ON CONFLICT (site_code, chat_id) DO UPDATE
   SET name = EXCLUDED.name, status = EXCLUDED.status, update_time = EXCLUDED.update_time;

SELECT chat_id, name, status FROM monitor_lark_chat
WHERE chat_id LIKE 'oc_postman_%' ORDER BY chat_id DESC;

-- ---------- 第 2 段：测完清理（确认后再执行） ----------
-- BEGIN;
-- DELETE FROM monitor_rule_state WHERE rule_id IN (
--     SELECT rule_id FROM monitor_rule WHERE notify_chat_id LIKE 'oc_postman_%');
-- DELETE FROM monitor_event WHERE rule_id IN (
--     SELECT rule_id FROM monitor_rule WHERE notify_chat_id LIKE 'oc_postman_%');
-- DELETE FROM monitor_rule WHERE notify_chat_id LIKE 'oc_postman_%';
-- DELETE FROM monitor_lark_chat WHERE chat_id LIKE 'oc_postman_%';
-- -- 测试规则已物理删除，规则号回到「现有最大号 + 1」，真实规则仍从 R-001 起
-- UPDATE monitor_site_setting s
--    SET next_rule_no = COALESCE((SELECT max(r.rule_no) FROM monitor_rule r WHERE r.site_code = s.site_code), 0) + 1;
-- COMMIT;
