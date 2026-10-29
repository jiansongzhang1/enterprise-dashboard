package com.fivetech.monitor.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.lark.LarkChat;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorLarkChat;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorLarkChatOptionVO;
import com.fivetech.monitor.domain.vo.MonitorLarkChatsVO;
import com.fivetech.monitor.engine.MonitorNotifySender;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.mapper.MonitorLarkChatMapper;
import com.fivetech.monitor.service.IMonitorLarkChatService;
import com.fivetech.monitor.service.MonitorNaturalOrder;

/**
 * GET /monitor/lark/chats：实时拉取机器人所在的 Lark 群，刷新本站 monitor_lark_chat，返回群下拉选项。
 *
 * <ul>
 *   <li>拉取成功：拉到的群 upsert 为 ok、没拉到的置 dead，fetched = true。</li>
 *   <li>拉取失败（超时、5xx、限流、未识别的错误码）：返回缓存里的上次结果，fetched = false，fetchedAt 为上次成功时间。</li>
 *   <li>凭证无效、应用缺少权限、没配置凭证：4802。</li>
 *   <li>同一站点 monitor.lark.chats-cache-seconds（默认 30）秒内重复调用直接返回缓存，fetched = true。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class MonitorLarkChatServiceImpl implements IMonitorLarkChatService
{
    private static final Logger log = LoggerFactory.getLogger(MonitorLarkChatServiceImpl.class);

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 群连接测试卡片标题（FY-30 四、群连接测试消息：灰色表头、没有按钮、不加站点前缀） */
    static final String TEST_TITLE = "【測試】Yata 群連接測試";

    private static final DateTimeFormatter TEST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** monitor_lark_chat.name 的长度 */
    static final int NAME_MAX = 100;

    private final LarkClient larkClient;

    private final MonitorLarkChatMapper chatMapper;

    private final MonitorLarkChatWriter writer;

    private final MonitorProperties monitorProperties;

    private final ZoneId zone;

    private final DashboardProperties dashboard;

    public MonitorLarkChatServiceImpl(LarkClient larkClient, MonitorLarkChatMapper chatMapper, MonitorLarkChatWriter writer,
            MonitorProperties monitorProperties, DashboardProperties properties)
    {
        this.larkClient = larkClient;
        this.chatMapper = chatMapper;
        this.writer = writer;
        this.monitorProperties = monitorProperties;
        this.zone = ZoneId.of(properties.getTimezone());
        this.dashboard = properties;
    }

    @Override
    public MonitorLarkChatsVO listChats(MonitorScope scope)
    {
        LocalDateTime now = LocalDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS);
        MonitorProperties.Lark lark = monitorProperties.getLark();
        LocalDateTime lastFetched = chatMapper.selectLastSeenAt(scope);
        boolean fetched;
        if (withinCache(lastFetched, now, lark.getChatsCacheSeconds()))
        {
            fetched = true;
        }
        else
        {
            LarkResult<List<LarkChat>> result = larkClient.listChats(lark.getAppId(), lark.getAppSecret());
            if (result.isSuccess())
            {
                writer.refresh(scope, toRows(result.getData()), now);
                lastFetched = now;
                fetched = true;
                log.info("Lark 群列表刷新 site={} 群数={}", scope.getSiteCode(), result.getData().size());
            }
            else if (result.getCategory() == LarkErrorCategory.CREDENTIAL_INVALID)
            {
                log.error("Lark 应用凭证无效或缺少权限，无法拉取群列表 site={} {}（检查 MONITOR_LARK_APP_ID / MONITOR_LARK_APP_SECRET 和应用权限 im:chat:readonly）",
                        scope.getSiteCode(), result);
                throw new MonitorException(MonitorErrorCode.LARK_CREDENTIAL_INVALID, "Lark 應用憑證無效");
            }
            else
            {
                log.warn("Lark 群列表拉取失败，返回上次结果 site={} lastFetched={} {}", scope.getSiteCode(), lastFetched, result);
                fetched = false;
            }
        }
        MonitorLarkChatsVO vo = new MonitorLarkChatsVO();
        vo.setFetched(fetched);
        vo.setFetchedAt(lastFetched == null ? null : lastFetched.format(MINUTE));
        List<MonitorLarkChatOptionVO> chats = new ArrayList<>(chatMapper.selectChatOptions(scope));
        chats.sort(Comparator.comparing(MonitorLarkChatOptionVO::getName, MonitorNaturalOrder.INSTANCE)
                .thenComparing(MonitorLarkChatOptionVO::getChatId));
        vo.setChats(chats);
        return vo;
    }

    @Override
    public boolean markDeadIfChatGone(MonitorScope scope, String chatId, LarkResult<?> sendResult)
    {
        if (sendResult == null || !sendResult.isChatGone() || chatId == null || chatId.isBlank())
        {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS);
        boolean changed = chatMapper.markChatDead(scope, chatId, now) > 0;
        log.warn("Lark 群不可用（机器人已不在群里或群已解散）site={} chatId={} larkCode={} msg={}，{}", scope.getSiteCode(), chatId,
                sendResult.getLarkCode(), sendResult.getMsg(), changed ? "已置为失效" : "原来就是失效状态或不在缓存里");
        return changed;
    }

    @Override
    public Map<String, Object> testChat(MonitorScope scope, String chatId, String sender)
    {
        String id = chatId == null ? "" : chatId.trim();
        MonitorLarkChat chat = id.isEmpty() ? null : chatMapper.selectChat(scope, id);
        if (chat == null)
        {
            throw new MonitorException(MonitorErrorCode.NOT_FOUND, "群不存在，請重新拉取群列表");
        }
        if ("dead".equals(chat.getStatus()))
        {
            throw new MonitorException(MonitorErrorCode.CHAT_DEAD, "通知群已失效（機器人已不在群裡或群已解散）");
        }
        MonitorProperties.Lark lark = monitorProperties.getLark();
        if (isBlank(lark.getAppId()) || isBlank(lark.getAppSecret()))
        {
            log.error("群测试失败：未配置 MONITOR_LARK_APP_ID / MONITOR_LARK_APP_SECRET site={}", scope.getSiteCode());
            throw new MonitorException(MonitorErrorCode.LARK_CREDENTIAL_INVALID, "Lark 應用憑證無效");
        }
        LarkResult<String> bot = larkClient.getBotName(lark.getAppId(), lark.getAppSecret());
        if (!bot.isSuccess() && bot.getCategory() == LarkErrorCategory.CREDENTIAL_INVALID)
        {
            log.warn("群测试：Lark 凭证无效 site={} {}", scope.getSiteCode(), bot);
            throw new MonitorException(MonitorErrorCode.LARK_CREDENTIAL_INVALID, "Lark 應用憑證無效");
        }
        // 取不到名称（超时、未开通机器人能力等）不影响测试，用 App ID 代替
        String botName = bot.isSuccess() && !isBlank(bot.getData()) ? bot.getData() : lark.getAppId();
        // 卡片「時間」按站点时区（站点表的时区，内存里，不查库），不写时区
        String site = scope.getSiteCode();
        String time = LocalDateTime.now(ZoneId.of(dashboard.siteTimezone(site))).format(TEST_TIME);
        Map<String, Object> card = groupTestCard(botName, chat.getName(), isBlank(sender) ? "—" : sender.trim(), time);
        LarkResult<String> r = larkClient.sendCard(lark.getAppId(), lark.getAppSecret(), "chat_id", id, card);
        if (r.isSuccess())
        {
            log.info("群测试消息已发送 site={} chat={} messageId={}", scope.getSiteCode(), id, r.getData());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("chatId", id);
            out.put("name", chat.getName());
            out.put("messageId", r.getData());
            return out;
        }
        String reason = MonitorNotifySender.reason(r, false);
        log.warn("群测试消息发送失败 site={} chat={} {}", scope.getSiteCode(), id, r);
        if (r.getCategory() == LarkErrorCategory.CREDENTIAL_INVALID)
        {
            throw new MonitorException(MonitorErrorCode.LARK_CREDENTIAL_INVALID, "Lark 應用憑證無效");
        }
        if (markDeadIfChatGone(scope, id, r) || r.isChatGone())
        {
            throw new MonitorException(MonitorErrorCode.CHAT_DEAD, "測試訊息發送失敗：" + reason);
        }
        throw new MonitorException(MonitorErrorCode.LARK_SEND_FAILED, "測試訊息發送失敗：" + reason);
    }

    /**
     * 群连接测试卡片：表头灰色、只放标题；正文每行一个元素「**行名**：值」；没有按钮。
     * 行：機器人 / 群 / 發送人 / 時間（FY-30 四、群连接测试消息）。
     */
    static Map<String, Object> groupTestCard(String botName, String chatName, String sender, String time)
    {
        Map<String, Object> title = new LinkedHashMap<>();
        title.put("tag", "plain_text");
        title.put("content", TEST_TITLE);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("template", "grey");
        header.put("title", title);
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(cardRow("機器人", botName));
        elements.add(cardRow("群", chatName));
        elements.add(cardRow("發送人", sender));
        elements.add(cardRow("時間", time));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("wide_screen_mode", true);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", config);
        card.put("header", header);
        card.put("elements", elements);
        return card;
    }

    private static Map<String, Object> cardRow(String label, String value)
    {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("tag", "lark_md");
        text.put("content", "**" + label + "**：" + mdEscape(value));
        Map<String, Object> div = new LinkedHashMap<>();
        div.put("tag", "div");
        div.put("text", text);
        return div;
    }

    /** lark_md 里群名、机器人名可能带 * _ ~ ` 等，转成 HTML 实体避免被当成格式 */
    static String mdEscape(String s)
    {
        if (s == null)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray())
        {
            switch (c)
            {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '*': sb.append("&#42;"); break;
                case '_': sb.append("&#95;"); break;
                case '~': sb.append("&#126;"); break;
                case '`': sb.append("&#96;"); break;
                case '[': sb.append("&#91;"); break;
                case ']': sb.append("&#93;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.isBlank();
    }

    static boolean withinCache(LocalDateTime lastFetched, LocalDateTime now, int cacheSeconds)
    {
        return cacheSeconds > 0 && lastFetched != null
                && !lastFetched.isAfter(now) && lastFetched.plusSeconds(cacheSeconds).isAfter(now);
    }

    /** 群名去掉首尾空白；没有群名用 chat_id；超过 100 字截断 */
    static List<MonitorLarkChat> toRows(List<LarkChat> chats)
    {
        List<MonitorLarkChat> rows = new ArrayList<>(chats.size());
        for (LarkChat chat : chats)
        {
            String name = chat.getName() == null ? "" : chat.getName().trim();
            if (name.isEmpty())
            {
                name = chat.getChatId();
            }
            if (name.codePointCount(0, name.length()) > NAME_MAX)
            {
                name = name.substring(0, name.offsetByCodePoints(0, NAME_MAX));
            }
            MonitorLarkChat row = new MonitorLarkChat();
            row.setChatId(chat.getChatId());
            row.setName(name);
            row.setStatus("ok");
            rows.add(row);
        }
        return rows;
    }
}
