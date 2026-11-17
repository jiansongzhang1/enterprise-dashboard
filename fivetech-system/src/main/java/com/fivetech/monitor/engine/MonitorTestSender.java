package com.fivetech.monitor.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorLarkChat;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.mapper.MonitorLarkChatMapper;
import com.fivetech.monitor.service.IMonitorLarkChatService;
import com.fivetech.monitor.service.MonitorChats;

/**
 * 三类规则的「测试告警」共用（FY-91 R9 / FY-92 R4 / FY-95 R7）：同一条测试消息发到规则当前所有正常的群。
 * 群状态取本站群列表缓存：缓存里没有或已失效的群跳过（记 skipped）；发送时发现机器人已不在群里，顺手把群标为失效。
 * 不写事件、发送记录和规则的 last_notified_at。
 *
 * @author fivetech
 */
@Component
public class MonitorTestSender
{
    private static final Logger log = LoggerFactory.getLogger(MonitorTestSender.class);

    public static final String TEST_PREFIX = "【測試】";

    private final LarkClient larkClient;

    private final MonitorProperties properties;

    private final MonitorLarkChatMapper chatMapper;

    private IMonitorLarkChatService chatService;

    public MonitorTestSender(LarkClient larkClient, MonitorProperties properties, MonitorLarkChatMapper chatMapper)
    {
        this.larkClient = larkClient;
        this.properties = properties;
        this.chatMapper = chatMapper;
    }

    /** setter 注入：群列表服务依赖较多，测试里可不给 */
    @Autowired(required = false)
    public void setChatService(IMonitorLarkChatService chatService)
    {
        this.chatService = chatService;
    }

    /**
     * 发送。凭证没配 → 4802；规则没有正常的群 → 400（不调 Lark）。
     *
     * @param notifyChats 规则的 notify_chats（jsonb 文本）；为空时退回 fallbackChatId（旧数据）
     * @return { success（正常的群全部送达）, chatId, messageId（第一个送达的群）, error（第一个失败原因）,
     *         chats[{chatId, name, status: sent / failed / skipped, messageId, error}] }
     */
    public Map<String, Object> send(MonitorScope scope, String notifyChats, String fallbackChatId, String fallbackName, String content)
    {
        MonitorProperties.Lark lark = properties.getLark();
        if (StringUtils.isEmpty(lark.getAppId()) || StringUtils.isEmpty(lark.getAppSecret()))
        {
            throw new MonitorException(MonitorErrorCode.LARK_CREDENTIAL_INVALID, "Lark 應用憑證未配置");
        }
        List<MonitorChats.Chat> chats = MonitorChats.parse(notifyChats, fallbackChatId, fallbackName, null);
        List<Map<String, Object>> results = new ArrayList<>();
        List<MonitorLarkChat> targets = new ArrayList<>();
        for (MonitorChats.Chat c : chats)
        {
            MonitorLarkChat cached = chatMapper == null ? null : chatMapper.selectChat(scope, c.chatId());
            if (cached == null || cached.isDead())
            {
                results.add(row(c.chatId(), c.name(), "skipped", null, "群已失效或不在群列表裡，未發送"));
                continue;
            }
            targets.add(cached);
        }
        if (targets.isEmpty())
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, chats.isEmpty() ? "規則沒有設置通知群，無法發送測試告警"
                : "規則的通知群都已失效，請先在規則裡重新選擇群");
        }
        String firstMessage = null;
        String firstChat = null;
        String firstError = null;
        int sent = 0;
        for (MonitorLarkChat c : targets)
        {
            LarkResult<String> r = larkClient.sendPost(lark.getAppId(), lark.getAppSecret(), "chat_id", c.getChatId(), "",
                MonitorLarkPost.paragraphs(content));
            if (r.isSuccess())
            {
                sent++;
                if (firstMessage == null)
                {
                    firstMessage = r.getData();
                    firstChat = c.getChatId();
                }
                results.add(row(c.getChatId(), c.getName(), "sent", r.getData(), null));
                continue;
            }
            String error = MonitorNotifySender.reason(r, false);
            if (firstError == null)
            {
                firstError = error;
            }
            if (r.getCategory() == LarkErrorCategory.TARGET_INVALID && chatService != null)
            {
                chatService.markDeadIfChatGone(scope, c.getChatId(), r);
            }
            results.add(row(c.getChatId(), c.getName(), "failed", null, error));
        }
        log.info("[monitor] 测试告警 site={} 正常群 {} 个，送达 {} 个", scope.getSiteCode(), targets.size(), sent);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", sent == targets.size());
        out.put("chatId", firstChat != null ? firstChat : targets.get(0).getChatId());
        out.put("messageId", firstMessage);
        out.put("error", firstError);
        out.put("chats", results);
        return out;
    }

    private static Map<String, Object> row(String chatId, String name, String status, String messageId, String error)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chatId", chatId);
        m.put("name", StringUtils.isEmpty(name) ? chatId : name);
        m.put("status", status);
        m.put("messageId", messageId);
        m.put("error", error);
        return m;
    }
}
