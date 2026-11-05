package com.fivetech.dashboard.subscription;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.domain.MetricGroupConfig;
import com.fivetech.dashboard.enums.DashboardPage;
import com.fivetech.dashboard.mapper.DashboardSubscriptionMapper;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.dashboard.service.OverviewMetricText;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorLarkChatOptionVO;
import com.fivetech.monitor.domain.vo.MonitorLarkChatsVO;
import com.fivetech.monitor.domain.vo.MonitorOwnerVO;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.service.IMonitorLarkChatService;
import com.fivetech.monitor.service.IMonitorRuleService;

/**
 * 系统管理 · 订阅简报：每天在站点时区的某个整点，把运营总览某个范围的勾选指标整理成卡片，发到 Lark 群或个人。
 *
 * <ul>
 *   <li>站点建好后不能改（群、可选用户都按站点）；</li>
 *   <li>指标从该站点运营总览显示的指标里勾选，发送时按站点顺序输出；</li>
 *   <li>群从本站 Lark 机器人所在的群里选；个人从有本站权限、填了邮箱的用户里选，按邮箱发；</li>
 *   <li>「立即发送」发给全部接收者；「测试发送」只发给操作人自己（按其邮箱），都写发送记录。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class SubscriptionService
{
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final int NAME_MAX = 50;

    static final int DESC_MAX = 200;

    private final DashboardSubscriptionMapper mapper;

    private final SubscriptionSender sender;

    private final MetricRegistry registry;

    private final DashboardProperties dashboard;

    private final SubscriptionProperties properties;

    private final IMonitorLarkChatService chatService;

    private final IMonitorRuleService ruleService;

    private final ObjectMapper objectMapper;

    public SubscriptionService(DashboardSubscriptionMapper mapper, SubscriptionSender sender, MetricRegistry registry,
            DashboardProperties dashboard, SubscriptionProperties properties, IMonitorLarkChatService chatService,
            IMonitorRuleService ruleService, ObjectMapper objectMapper)
    {
        this.mapper = mapper;
        this.sender = sender;
        this.registry = registry;
        this.dashboard = dashboard;
        this.properties = properties;
        this.chatService = chatService;
        this.ruleService = ruleService;
        this.objectMapper = objectMapper;
    }

    /** AI 配置（模型选项、是否可用）；单测里没有时用默认配置 */
    private com.fivetech.copilot.CopilotProperties copilot = new com.fivetech.copilot.CopilotProperties();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCopilot(com.fivetech.copilot.CopilotProperties copilot)
    {
        if (copilot != null)
        {
            this.copilot = copilot;
        }
    }

    // ===================== 选项 =====================

    /** 站点、统计范围、发送整点等固定选项 */
    public Map<String, Object> meta()
    {
        List<Map<String, Object>> sites = new ArrayList<>();
        for (String code : dashboard.allSiteCodes())
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("siteCode", code);
            m.put("siteName", dashboard.siteName(code));
            m.put("timezone", dashboard.siteTimezone(code));
            sites.add(m);
        }
        List<Map<String, Object>> ranges = new ArrayList<>();
        for (SubscriptionRange r : SubscriptionRange.values())
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.name());
            m.put("label", r.getLabel());
            m.put("dataset", r.getDataset());
            m.put("granularity", r.getGranularity().name());
            ranges.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sites", sites);
        out.put("ranges", ranges);
        out.put("maxReceivers", properties.getMaxReceivers());
        out.put("scheduleEnabled", properties.isEnabled());
        out.put("sendDelayMinutes", properties.getSendDelayMinutes());
        // AI 摘要：页面上的模型下拉（默认 claude-haiku-5-5）；available = false 时（没配 ANTHROPIC_API_KEY）开关可存，但不会生成摘要
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("available", copilot.isConfigured());
        ai.put("defaultModel", copilot.getDefaultModel());
        List<Map<String, Object>> models = new ArrayList<>();
        for (com.fivetech.copilot.CopilotProperties.Model m : copilot.getModels())
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", m.getId());
            x.put("label", m.getLabel() == null ? m.getId() : m.getLabel());
            x.put("default", m.getId() != null && m.getId().equalsIgnoreCase(copilot.getDefaultModel()));
            models.add(x);
        }
        ai.put("models", models);
        ai.put("dailyLimit", copilot.getBrief().getDailyLimit());
        out.put("ai", ai);
        return out;
    }

    /**
     * 某站点的可选项：指标（该站点运营总览显示的，按站点顺序）、Lark 群（实时拉取；失败给原因）、个人（有本站权限且填了邮箱的用户）。
     */
    public Map<String, Object> options(String siteCode, Long userId)
    {
        String site = site(siteCode);
        Set<String> core = registry.coreMetricCodes();
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (String code : registry.columnsOf(DashboardPage.OVERVIEW, site))
        {
            MetricDefinition def = registry.get(code);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("label", def == null ? code : OverviewMetricText.label(code, def.getLabel()));
            m.put("groupCode", def == null ? null : def.getGroup());
            MetricGroupConfig g = def == null ? null : registry.groupOf(def.getGroup());
            m.put("groupName", g == null ? null : OverviewMetricText.group(g.getGroupName()));
            m.put("core", core.contains(code));
            metrics.add(m);
        }
        MonitorScope scope = new MonitorScope(site, userId);
        List<Map<String, Object>> chats = new ArrayList<>();
        String chatsError = null;
        try
        {
            MonitorLarkChatsVO vo = chatService.listChats(scope);
            for (MonitorLarkChatOptionVO c : vo.getChats())
            {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chatId", c.getChatId());
                m.put("name", c.getName());
                m.put("status", c.getStatus());
                chats.add(m);
            }
            if (!vo.isFetched())
            {
                chatsError = "Lark 群列表拉取失敗，以下為上次結果";
            }
        }
        catch (RuntimeException e)
        {
            chatsError = e.getMessage();
        }
        List<Map<String, Object>> users = new ArrayList<>();
        for (MonitorOwnerVO o : ruleService.listOwners(scope))
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", o.getUserId());
            m.put("email", o.getEmail());
            users.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("siteCode", site);
        out.put("timezone", dashboard.siteTimezone(site));
        out.put("metrics", metrics);
        out.put("chats", chats);
        out.put("chatsError", chatsError);
        out.put("users", users);
        return out;
    }

    // ===================== 查询 =====================

    public List<Map<String, Object>> list(String siteCode, Boolean enabled)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DashboardSubscription s : mapper.selectList(trimToNull(siteCode), enabled))
        {
            out.add(view(s));
        }
        return out;
    }

    public Map<String, Object> get(Long id)
    {
        return view(require(id));
    }

    public List<DashboardSubscriptionLog> logs(Long id)
    {
        require(id);
        return mapper.selectLogs(id, 100);
    }

    Map<String, Object> view(DashboardSubscription s)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("siteCode", s.getSiteCode());
        m.put("siteName", dashboard.siteName(s.getSiteCode()));
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        SubscriptionRange range = SubscriptionRange.of(s.getRangeCode());
        m.put("rangeCode", s.getRangeCode());
        m.put("rangeLabel", range == null ? s.getRangeCode() : range.getLabel());
        List<String> codes = SubscriptionSender.splitCodes(s.getMetrics());
        m.put("metrics", codes);
        List<String> labels = new ArrayList<>();
        for (String c : codes)
        {
            MetricDefinition def = registry.get(c);
            labels.add(def == null ? c : OverviewMetricText.label(c, def.getLabel()));
        }
        m.put("metricLabels", labels);
        m.put("sendHour", s.getSendHour());
        m.put("sendTimeText", s.getSendHour() == null ? null : String.format("每日 %02d:00", s.getSendHour()));
        m.put("timezone", dashboard.siteTimezone(s.getSiteCode()));
        m.put("receivers", sender.receivers(s));
        m.put("enabled", s.isOn());
        m.put("aiEnabled", s.isAiOn());
        m.put("aiModel", s.getAiModel());
        String aiModel = copilot.effectiveModel(s.getAiModel());
        m.put("aiModelEffective", aiModel);
        m.put("aiModelLabel", copilot.label(aiModel));
        m.put("nextRunAt", s.isOn() && properties.isEnabled() && s.getSendHour() != null
            ? nextRun(sender.zone(s.getSiteCode()) == null ? null : LocalDateTime.now(sender.zone(s.getSiteCode())), s)
            : null);
        m.put("lastRunAt", s.getLastRunAt() == null ? null : s.getLastRunAt().format(TIME));
        m.put("lastStatus", s.getLastStatus());
        m.put("lastError", s.getLastError());
        m.put("createBy", s.getCreateBy());
        m.put("createTime", s.getCreateTime() == null ? null : s.getCreateTime().format(TIME));
        m.put("updateBy", s.getUpdateBy());
        m.put("updateTime", s.getUpdateTime() == null ? null : s.getUpdateTime().format(TIME));
        return m;
    }

    /** 下一次定时发送时刻（站点时区）：今天的发送整点还没到（含等待分钟）就是今天，否则明天 */
    String nextRun(LocalDateTime now, DashboardSubscription s)
    {
        if (now == null)
        {
            return null;
        }
        LocalDateTime today = now.toLocalDate().atTime(s.getSendHour(), 0);
        String todaySlot = today.format(SubscriptionSender.SLOT);
        boolean doneToday = todaySlot.equals(s.getLastSlot());
        LocalDateTime next = !doneToday && !now.isAfter(today.plusHours(properties.getGraceHours())) ? today : today.plusDays(1);
        return next.format(TIME);
    }

    // ===================== 写入 =====================

    public Map<String, Object> create(Map<String, Object> body, String operator)
    {
        DashboardSubscription s = new DashboardSubscription();
        String site = trimToNull(str(body, "siteCode"));
        String canonical = site == null ? null : dashboard.canonical(site);
        Map<String, String> errors = new LinkedHashMap<>();
        if (canonical == null)
        {
            errors.put("siteCode", site == null ? "請選擇站點" : "站點不存在");
        }
        s.setSiteCode(canonical);
        fill(s, body, errors, null);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        s.setCreateBy(operator);
        mapper.insert(s);
        log.info("[subscription] 新增 id={} site={} name={} by={}", s.getId(), s.getSiteCode(), s.getName(), operator);
        return get(s.getId());
    }

    public Map<String, Object> update(Long id, Map<String, Object> body, String operator)
    {
        DashboardSubscription old = require(id);
        Map<String, String> errors = new LinkedHashMap<>();
        String site = trimToNull(str(body, "siteCode"));
        if (site != null && !site.equalsIgnoreCase(old.getSiteCode()))
        {
            errors.put("siteCode", "站點建立後不能修改");
        }
        DashboardSubscription s = new DashboardSubscription();
        s.setId(id);
        s.setSiteCode(old.getSiteCode());
        s.setEnabled(old.getEnabled());
        s.setAiEnabled(old.getAiEnabled());
        s.setAiModel(old.getAiModel());
        fill(s, body, errors, id);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        s.setUpdateBy(operator);
        mapper.update(s);
        return get(id);
    }

    public Map<String, Object> setEnabled(Long id, boolean enabled, String operator)
    {
        require(id);
        mapper.updateEnabled(id, enabled, operator);
        return get(id);
    }

    public void delete(Long id, String operator)
    {
        require(id);
        mapper.softDelete(id, operator);
        log.info("[subscription] 删除 id={} by={}", id, operator);
    }

    /**
     * 校验并填入可改字段。errors 里收集全部字段错误，一次返回。
     */
    void fill(DashboardSubscription s, Map<String, Object> body, Map<String, String> errors, Long excludeId)
    {
        String name = trimToNull(str(body, "name"));
        if (name == null)
        {
            errors.put("name", "請填寫名稱");
        }
        else if (name.length() > NAME_MAX)
        {
            errors.put("name", "名稱不能超過 " + NAME_MAX + " 個字");
        }
        else if (s.getSiteCode() != null && mapper.countName(s.getSiteCode(), name, excludeId) > 0)
        {
            errors.put("name", "本站已有同名訂閱");
        }
        s.setName(name);

        String desc = trimToNull(str(body, "description"));
        if (desc != null && desc.length() > DESC_MAX)
        {
            errors.put("description", "說明不能超過 " + DESC_MAX + " 個字");
        }
        s.setDescription(desc);

        SubscriptionRange range = SubscriptionRange.of(str(body, "rangeCode"));
        if (range == null)
        {
            errors.put("rangeCode", "請選擇統計範圍");
        }
        s.setRangeCode(range == null ? null : range.name());

        Integer hour = intOrNull(body.get("sendHour"));
        if (hour == null || hour < 0 || hour > 23)
        {
            errors.put("sendHour", "發送時間請選 00:00–23:00 的整點");
        }
        else if (range == SubscriptionRange.TODAY && hour == 0)
        {
            errors.put("sendHour", "統計「今日」時不能在 00:00 發送（還沒有資料）");
        }
        s.setSendHour(hour);

        List<String> metrics = strings(body.get("metrics"));
        if (metrics.isEmpty())
        {
            errors.put("metrics", "請至少勾選 1 個指標");
        }
        else if (s.getSiteCode() != null)
        {
            List<String> allowed = registry.columnsOf(DashboardPage.OVERVIEW, s.getSiteCode());
            List<String> bad = new ArrayList<>();
            for (String c : metrics)
            {
                if (!allowed.contains(c))
                {
                    bad.add(c);
                }
            }
            if (!bad.isEmpty())
            {
                errors.put("metrics", "不在本站運營總覽上的指標：" + String.join("、", bad));
            }
        }
        // 存的时候就按站点顺序排好，列表显示与卡片一致
        List<String> ordered = s.getSiteCode() == null ? metrics : sender.orderedCodes(s.getSiteCode(), String.join(",", metrics));
        s.setMetrics(String.join(",", ordered.isEmpty() ? metrics : ordered));

        List<SubscriptionReceiver> receivers = receivers(body.get("receivers"), errors);
        if (receivers.isEmpty() && !errors.containsKey("receivers"))
        {
            errors.put("receivers", "請至少選擇 1 個接收群或接收人");
        }
        else if (receivers.size() > properties.getMaxReceivers())
        {
            errors.put("receivers", "接收者不能超過 " + properties.getMaxReceivers() + " 個");
        }
        try
        {
            s.setReceivers(objectMapper.writeValueAsString(receivers));
        }
        catch (JacksonException e)
        {
            errors.put("receivers", "接收者格式不正確");
        }

        // AI 摘要：不传沿用原值（新增默认关闭）；模型为空 = 默认模型，否则必须在 copilot.models 里
        if (body.containsKey("aiEnabled"))
        {
            Object ai = body.get("aiEnabled");
            s.setAiEnabled(ai instanceof Boolean ? (Boolean) ai : Boolean.parseBoolean(String.valueOf(ai).trim()));
        }
        if (body.containsKey("aiModel"))
        {
            String model = trimToNull(str(body, "aiModel"));
            if (model != null && copilot.find(model) == null)
            {
                errors.put("aiModel", "不支援的模型：" + model);
            }
            s.setAiModel(model == null ? null : copilot.find(model) == null ? model : copilot.find(model).getId());
        }

        Object enabled = body.get("enabled");
        if (enabled != null)
        {
            s.setEnabled(enabled instanceof Boolean ? (Boolean) enabled : Boolean.parseBoolean(String.valueOf(enabled).trim()));
        }
        else if (s.getEnabled() == null)
        {
            s.setEnabled(Boolean.TRUE);
        }
    }

    /** 接收者：type 只能是 chat / user；群 ID 以 oc_ 开头；用户 ID 为数字；去重 */
    static List<SubscriptionReceiver> receivers(Object raw, Map<String, String> errors)
    {
        List<SubscriptionReceiver> out = new ArrayList<>();
        if (!(raw instanceof List))
        {
            return out;
        }
        Set<String> seen = new HashSet<>();
        List<String> bad = new ArrayList<>();
        for (Object o : (List<?>) raw)
        {
            if (!(o instanceof Map))
            {
                bad.add(String.valueOf(o));
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) o;
            String type = m.get("type") == null ? "" : String.valueOf(m.get("type")).trim().toLowerCase(java.util.Locale.ROOT);
            String id = m.get("id") == null ? "" : String.valueOf(m.get("id")).trim();
            String name = m.get("name") == null ? null : String.valueOf(m.get("name")).trim();
            boolean ok = (SubscriptionReceiver.CHAT.equals(type) && id.startsWith("oc_") && id.length() <= 64)
                || (SubscriptionReceiver.USER.equals(type) && id.matches("\\d{1,18}"));
            if (!ok)
            {
                bad.add(type + ":" + id);
                continue;
            }
            if (seen.add(type + ":" + id))
            {
                out.add(new SubscriptionReceiver(type, id, name == null || name.isEmpty() ? id : SubscriptionSender.cut(name, 128)));
            }
        }
        if (!bad.isEmpty())
        {
            errors.put("receivers", "接收者不正確：" + String.join("、", bad));
        }
        return out;
    }

    // ===================== 发送 =====================

    /** 用表单当前内容预览（不保存、不发送）：按现在的整点取数 */
    public Map<String, Object> preview(Map<String, Object> body)
    {
        return preview(body, null);
    }

    /** 同上；开了 AI 摘要时预览也会调用一次模型（计入每日次数） */
    public Map<String, Object> preview(Map<String, Object> body, String operator)
    {
        String site = site(str(body, "siteCode"));
        DashboardSubscription s = new DashboardSubscription();
        s.setSiteCode(site);
        Map<String, String> errors = new LinkedHashMap<>();
        fill(s, body, errors, null);
        // 预览只要求能取数：名称重复、没选接收者不影响预览
        errors.remove("name");
        errors.remove("receivers");
        errors.remove("sendHour");
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        if (s.getName() == null)
        {
            s.setName("（未命名）");
        }
        SubscriptionBrief brief = prepareOrFail(s, sender.nowHour(site), false);
        sender.addAi(s, brief, operator);
        Map<String, Object> out = brief.toPreview();
        out.put("card", brief.getCard());
        return out;
    }

    /** 立即发送：按现在的整点取数，发给全部接收者（不影响当天的定时发送） */
    public Map<String, Object> sendNow(Long id, String operator)
    {
        DashboardSubscription s = require(id);
        SubscriptionBrief brief = prepareOrFail(s, sender.nowHour(s.getSiteCode()), false);
        sender.addAi(s, brief, operator);
        return sender.deliver(s, brief, sender.receivers(s), SubscriptionSender.MANUAL, operator, null);
    }

    /** 测试发送：只发给操作人自己（按其邮箱），卡片标【測試】 */
    public Map<String, Object> test(Long id, Long userId, String email, String operator)
    {
        DashboardSubscription s = require(id);
        if (userId == null || email == null || email.trim().isEmpty())
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "你的帳號沒有填郵箱，無法發送測試訊息");
        }
        SubscriptionBrief brief = prepareOrFail(s, sender.nowHour(s.getSiteCode()), true);
        sender.addAi(s, brief, operator);
        List<SubscriptionReceiver> me = new ArrayList<>();
        me.add(new SubscriptionReceiver(SubscriptionReceiver.USER, String.valueOf(userId), email.trim()));
        return sender.deliver(s, brief, me, SubscriptionSender.TEST, operator, null);
    }

    private SubscriptionBrief prepareOrFail(DashboardSubscription s, LocalDateTime sendAt, boolean test)
    {
        try
        {
            return sender.prepare(s, sendAt, test);
        }
        catch (SubscriptionSender.PrepareException e)
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    // ===================== 工具 =====================

    private DashboardSubscription require(Long id)
    {
        DashboardSubscription s = id == null ? null : mapper.selectById(id);
        if (s == null)
        {
            throw MonitorException.notFound("訂閱不存在或已刪除");
        }
        return s;
    }

    private String site(String siteCode)
    {
        String code = siteCode == null ? null : dashboard.canonical(siteCode.trim());
        if (code == null)
        {
            throw MonitorFieldException.of("siteCode", siteCode == null || siteCode.trim().isEmpty() ? "請選擇站點" : "站點不存在");
        }
        return code;
    }

    private static String str(Map<String, Object> body, String key)
    {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String trimToNull(String s)
    {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    static Integer intOrNull(Object v)
    {
        if (v == null)
        {
            return null;
        }
        if (v instanceof Number)
        {
            double d = ((Number) v).doubleValue();
            return d == Math.rint(d) ? (int) d : null;
        }
        try
        {
            return Integer.valueOf(String.valueOf(v).trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    static List<String> strings(Object v)
    {
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof List)
        {
            for (Object o : (List<?>) v)
            {
                if (o != null && !String.valueOf(o).trim().isEmpty())
                {
                    out.add(String.valueOf(o).trim());
                }
            }
        }
        else if (v != null)
        {
            out.addAll(SubscriptionSender.splitCodes(String.valueOf(v)));
        }
        return new ArrayList<>(out);
    }
}
