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
 * 订阅简报：按发送频率（每日 / 每週 / 每 N 小時，站点时区整点）把运营总览某个视图的勾选指标整理成卡片（可带整页截图），发到 Lark 群。
 *
 * <ul>
 *   <li>站点建好后不能改；视图（统计范围、固定日期、粒度、对比）建立后也不能改（V0.4 原型：要换就在运营总览调整后重新订阅）；</li>
 *   <li>指标从该站点运营总览显示的指标里勾选，发送时按站点顺序输出；</li>
 *   <li>接收者只能是本站 Lark 机器人所在的群（2026-11-13 起不发个人）；</li>
 *   <li>负责人默认建立人，可转交；只有负责人和管理员能修改（{@link SubscriptionActor}）；</li>
 *   <li>「立即发送」异步执行，结果写发送历史；「测试发送」按表单发到所选的群，卡片标【測試】，不记录。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class SubscriptionService
{
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 名称（卡片标题）、说明（卡片正文）的字数上限（V0.4 原型：30 / 100） */
    static final int NAME_MAX = 30;

    static final int DESC_MAX = 100;

    /** 固定日期最长多少天 */
    static final int CUSTOM_MAX_DAYS = 365;

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
        // 运营总览截图：后端开了才显示开关；查询条件为空时按统计范围生成
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("enabled", sender.snapshotAvailable());
        snap.put("filtersHint", "留空按統計範圍；或填篩選條件 JSON，如 {\"range\":\"yesterday\",\"gran\":\"hour\",\"cmp\":\"previous-period\"}，"
            + "自定義加 \"from\" / \"to\"（yyyy-MM-dd，可用 {from} {to} 表示統計區間首尾日期）");
        out.put("snapshot", snap);
        // 发送频率（V0.4 原型）：每日、每週（週几 + 整点）、每 N 小時（N = 1–12，从…到…）
        List<Map<String, Object>> freqs = new ArrayList<>();
        for (String[] f : new String[][] { { SubscriptionSchedule.DAY, "每日" }, { SubscriptionSchedule.WEEK, "每週" },
            { SubscriptionSchedule.HOURS, "每 N 小時" } })
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", f[0]);
            x.put("label", f[1]);
            freqs.add(x);
        }
        out.put("freqTypes", freqs);
        List<Map<String, Object>> dows = new ArrayList<>();
        for (int i = 1; i <= 7; i++)
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("value", i);
            x.put("label", SubscriptionSchedule.DOW[i]);
            dows.add(x);
        }
        out.put("dows", dows);
        out.put("everyMin", SubscriptionSchedule.EVERY_MIN);
        out.put("everyMax", SubscriptionSchedule.EVERY_MAX);
        out.put("nameMax", NAME_MAX);
        out.put("descriptionMax", DESC_MAX);
        out.put("graceHours", properties.getGraceHours());
        return out;
    }

    /**
     * 某站点的可选项：指标（该站点运营总览显示的，按站点顺序）、Lark 群（实时拉取；失败给原因）、负责人候选（有本站权限且填了邮箱的帐号）。
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
        // 负责人候选：有本站权限、填了邮箱的帐号（2026-11-13 起接收者只能是群，不再列个人）
        List<Map<String, Object>> owners = new ArrayList<>();
        for (MonitorOwnerVO o : ruleService == null ? List.<MonitorOwnerVO>of() : ruleService.listOwners(scope))
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", o.getUserId());
            m.put("email", o.getEmail());
            owners.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("siteCode", site);
        out.put("timezone", dashboard.siteTimezone(site));
        out.put("metrics", metrics);
        out.put("chats", chats);
        out.put("chatsError", chatsError);
        out.put("owners", owners);
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
        m.put("freqType", SubscriptionSchedule.type(s.getFreqType()));
        m.put("freqDow", s.getFreqDow());
        m.put("freqEvery", s.getFreqEvery());
        m.put("freqTo", s.getFreqTo());
        m.put("sendTimeText", SubscriptionSchedule.text(s));
        m.put("viewGran", SubscriptionSender.granularity(s, range == null ? SubscriptionRange.YESTERDAY : range).name());
        m.put("viewCmp", s.isCustomCompare() ? "CUSTOM" : "PREV");
        m.put("viewFrom", s.getViewFrom() == null ? null : s.getViewFrom().toString());
        m.put("viewTo", s.getViewTo() == null ? null : s.getViewTo().toString());
        m.put("viewCmpFrom", s.getViewCmpFrom() == null ? null : s.getViewCmpFrom().toString());
        m.put("viewCmpTo", s.getViewCmpTo() == null ? null : s.getViewCmpTo().toString());
        m.put("fixedDate", range == SubscriptionRange.CUSTOM);
        m.put("viewText", viewText(s, range));
        m.put("sourcePage", s.getSourcePage() == null ? "ov" : s.getSourcePage());
        m.put("ownerUserId", s.getOwnerUserId());
        m.put("timezone", dashboard.siteTimezone(s.getSiteCode()));
        m.put("receivers", sender.receivers(s));
        m.put("enabled", s.isOn());
        m.put("aiEnabled", s.isAiOn());
        m.put("aiModel", s.getAiModel());
        String aiModel = copilot.effectiveModel(s.getAiModel());
        m.put("aiModelEffective", aiModel);
        m.put("aiModelLabel", copilot.label(aiModel));
        m.put("snapshotEnabled", s.isSnapshotOn());
        m.put("snapshotFilters", s.getSnapshotFilters());
        m.put("nextRunAt", s.isOn() && properties.isEnabled() && s.getSendHour() != null
            ? nextRun(LocalDateTime.now(sender.zone(s.getSiteCode())), s)
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

    /** 下一次定时发送时刻（站点时区，yyyy-MM-dd HH:mm）；按频率算，没有返回 null */
    String nextRun(LocalDateTime now, DashboardSubscription s)
    {
        if (now == null)
        {
            return null;
        }
        LocalDateTime next = SubscriptionSchedule.next(s, now, properties.getGraceHours(), s.getLastSlot());
        return next == null ? null : next.format(TIME);
    }

    /** 视图文字：近 7 天 · 日 · 對比上一週期 / 09-01 – 09-07 · 日 · 對比自訂 08-25 – 08-31 */
    static String viewText(DashboardSubscription s, SubscriptionRange range)
    {
        String r = range == null ? String.valueOf(s.getRangeCode())
            : range == SubscriptionRange.CUSTOM && s.getViewFrom() != null && s.getViewTo() != null
                ? s.getViewFrom() + " – " + s.getViewTo() : range.getLabel();
        String g;
        switch (SubscriptionSender.granularity(s, range == null ? SubscriptionRange.YESTERDAY : range))
        {
            case HOUR: g = "小時"; break;
            case WEEK: g = "週"; break;
            default: g = "日"; break;
        }
        String c = s.isCustomCompare() ? "對比自訂 " + s.getViewCmpFrom() + " – " + s.getViewCmpTo() : "對比上一週期";
        return r + " · " + g + " · " + c;
    }

    // ===================== 写入 =====================

    /** 管理后台新增（操作人按管理员；负责人默认为空 = 按建立人） */
    public Map<String, Object> create(Map<String, Object> body, String operator)
    {
        return create(body, SubscriptionActor.admin(null, operator));
    }

    /**
     * 新增。负责人默认是操作人（{@code ownerUserId} 可指定其他候选人）；开了截图功能时新订阅默认带整页截图，
     * 截图的筛选条件按视图生成。
     */
    public Map<String, Object> create(Map<String, Object> body, SubscriptionActor actor)
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
        s.setSourcePage("ov");
        if (!body.containsKey("snapshotEnabled"))
        {
            s.setSnapshotEnabled(sender.snapshotAvailable());
        }
        fill(s, body, errors, null, null);
        Long owner = body.containsKey("ownerUserId") ? longOrNull(body.get("ownerUserId")) : actor.getUserId();
        checkOwner(canonical, owner, null, errors);
        s.setOwnerUserId(owner);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        if (s.getSnapshotFilters() == null)
        {
            s.setSnapshotFilters(viewFilters(s));
        }
        s.setCreateBy(actor.getUsername());
        mapper.insert(s);
        log.info("[subscription] 新增 id={} site={} name={} owner={} by={}", s.getId(), s.getSiteCode(), s.getName(), owner,
            actor.getUsername());
        return get(s.getId());
    }

    public Map<String, Object> update(Long id, Map<String, Object> body, String operator)
    {
        return update(id, body, SubscriptionActor.admin(null, operator));
    }

    /**
     * 修改：只有负责人和管理员能改；站点、视图（统计范围、固定日期、粒度、对比）不能改；负责人改成别人即转交，管理员可以改成自己（接管）。
     */
    public Map<String, Object> update(Long id, Map<String, Object> body, SubscriptionActor actor)
    {
        DashboardSubscription old = require(id);
        requireEditable(old, actor);
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
        s.setSnapshotEnabled(old.getSnapshotEnabled());
        s.setSnapshotFilters(old.getSnapshotFilters());
        s.setSourcePage(old.getSourcePage());
        s.setCreateBy(old.getCreateBy());
        fill(s, body, errors, id, old);
        Long owner = body.containsKey("ownerUserId") ? longOrNull(body.get("ownerUserId")) : old.getOwnerUserId();
        checkOwner(old.getSiteCode(), owner, old.getOwnerUserId(), errors);
        s.setOwnerUserId(owner);
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        s.setUpdateBy(actor.getUsername());
        mapper.update(s);
        if (owner != null && !owner.equals(old.getOwnerUserId()))
        {
            log.info("[subscription] {}負責人 id={} {} → {} by={}", owner.equals(actor.getUserId()) ? "接管" : "轉交", id,
                old.getOwnerUserId(), owner, actor.getUsername());
        }
        return get(id);
    }

    public Map<String, Object> setEnabled(Long id, boolean enabled, String operator)
    {
        return setEnabled(id, enabled, SubscriptionActor.admin(null, operator));
    }

    public Map<String, Object> setEnabled(Long id, boolean enabled, SubscriptionActor actor)
    {
        requireEditable(require(id), actor);
        mapper.updateEnabled(id, enabled, actor.getUsername());
        log.info("[subscription] {} id={} by={}", enabled ? "啟用" : "暫停", id, actor.getUsername());
        return get(id);
    }

    public void delete(Long id, String operator)
    {
        delete(id, SubscriptionActor.admin(null, operator));
    }

    /** 逻辑删除；发送历史保留（标「已刪除」） */
    public void delete(Long id, SubscriptionActor actor)
    {
        requireEditable(require(id), actor);
        mapper.softDelete(id, actor.getUsername());
        log.info("[subscription] 删除 id={} by={}", id, actor.getUsername());
    }

    void requireEditable(DashboardSubscription s, SubscriptionActor actor)
    {
        if (actor != null && !actor.canEdit(s))
        {
            throw MonitorException.forbidden("只有負責人與管理員可以修改這個訂閱");
        }
    }

    /**
     * 负责人：为空 = 不指定（按建立人）；没改不校验；改了必须是本站负责人候选（有本站权限、填了邮箱）。
     * 单测里没有 ruleService 时不校验。
     */
    void checkOwner(String site, Long owner, Long oldOwner, Map<String, String> errors)
    {
        if (owner == null || owner.equals(oldOwner) || ruleService == null || site == null)
        {
            return;
        }
        boolean ok = false;
        for (MonitorOwnerVO o : ruleService.listOwners(new MonitorScope(site, null)))
        {
            if (owner.equals(o.getUserId()))
            {
                ok = true;
                break;
            }
        }
        if (!ok)
        {
            errors.put("ownerUserId", "此帳號沒有本站「訂閱管理」權限，請選擇其他負責人");
        }
    }

    /**
     * 校验并填入可改字段。errors 里收集全部字段错误，一次返回。
     *
     * @param old 修改时的原订阅：视图（统计范围、固定日期、粒度、对比）沿用原值，请求体改了就报错；新增为 null
     */
    void fill(DashboardSubscription s, Map<String, Object> body, Map<String, String> errors, Long excludeId,
            DashboardSubscription old)
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
        else if (desc != null && desc.contains("<at"))
        {
            errors.put("description", "說明不支援 @ 人");
        }
        s.setDescription(desc);

        SubscriptionRange range;
        if (old != null)
        {
            range = SubscriptionRange.of(old.getRangeCode());
            copyView(old, s);
            SubscriptionRange asked = body.containsKey("rangeCode") ? SubscriptionRange.of(str(body, "rangeCode")) : range;
            if (asked != range || viewChanged(old, body))
            {
                errors.put("rangeCode", "訂閱內容（時間範圍、粒度、對比）建立後不能修改；要換請在營運總覽調整後重新訂閱");
            }
        }
        else
        {
            range = SubscriptionRange.of(str(body, "rangeCode"));
            if (range == null)
            {
                errors.put("rangeCode", "請選擇統計範圍");
            }
            s.setRangeCode(range == null ? null : range.name());
            fillView(s, range, body, errors);
        }

        // 修改时没传的频率字段沿用原值（管理后台的旧页面只传 sendHour，不能把每週 / 每 N 小時改成每日）
        boolean keep = old != null && !body.containsKey("freqType");
        s.setSendHour(body.containsKey("sendHour") || old == null ? intOrNull(body.get("sendHour")) : old.getSendHour());
        s.setFreqType(keep ? old.getFreqType() : str(body, "freqType"));
        s.setFreqDow(keep && !body.containsKey("freqDow") ? old.getFreqDow() : intOrNull(body.get("freqDow")));
        s.setFreqEvery(keep && !body.containsKey("freqEvery") ? old.getFreqEvery() : intOrNull(body.get("freqEvery")));
        s.setFreqTo(keep && !body.containsKey("freqTo") ? old.getFreqTo() : intOrNull(body.get("freqTo")));
        SubscriptionSchedule.validate(s, range, errors);

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
            errors.put("receivers", "至少選擇一個通知群");
        }
        else if (receivers.size() > properties.getMaxReceivers())
        {
            errors.put("receivers", "接收群不能超過 " + properties.getMaxReceivers() + " 個");
        }
        try
        {
            s.setReceivers(objectMapper.writeValueAsString(receivers));
        }
        catch (JacksonException e)
        {
            errors.put("receivers", "接收群格式不正確");
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

        // 运营总览截图：不传沿用原值（新增时开了截图功能就默认带）；筛选条件为 JSON 对象（也接受 JSON 字符串、或贴整条带参数的页面地址）
        if (body.containsKey("snapshotEnabled"))
        {
            Object sn = body.get("snapshotEnabled");
            s.setSnapshotEnabled(sn instanceof Boolean ? (Boolean) sn : Boolean.parseBoolean(String.valueOf(sn).trim()));
        }
        if (body.containsKey("snapshotFilters"))
        {
            String q = com.fivetech.dashboard.subscription.snapshot.SubscriptionSnapshotService.normalizeFilters(body.get("snapshotFilters"));
            if (q == null)
            {
                errors.put("snapshotFilters", "截圖篩選條件格式不正確：應為 {\"range\":\"…\",\"gran\":\"…\",\"cmp\":\"…\"}，自定義時加 from / to（yyyy-MM-dd）");
            }
            else
            {
                s.setSnapshotFilters(q.isEmpty() ? null : q);
            }
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

    /** 兼容旧调用（单测、预览）：新增口径 */
    void fill(DashboardSubscription s, Map<String, Object> body, Map<String, String> errors, Long excludeId)
    {
        fill(s, body, errors, excludeId, null);
    }

    /** 视图字段：粒度 viewGran（HOUR / DAY / WEEK，空 = 范围默认）、固定日期 viewFrom / viewTo、对比 viewCmp（PREV / CUSTOM + viewCmpFrom / viewCmpTo） */
    void fillView(DashboardSubscription s, SubscriptionRange range, Map<String, Object> body, Map<String, String> errors)
    {
        String gran = trimToNull(str(body, "viewGran"));
        if (gran != null)
        {
            gran = gran.toUpperCase(java.util.Locale.ROOT);
            if (!List.of("HOUR", "DAY", "WEEK").contains(gran))
            {
                errors.put("viewGran", "粒度只能是小時、日或週");
            }
        }
        s.setViewGran(gran == null && range != null ? range.getGranularity().name() : gran);
        if (range == SubscriptionRange.CUSTOM)
        {
            java.time.LocalDate from = dateOrNull(body.get("viewFrom"));
            java.time.LocalDate to = dateOrNull(body.get("viewTo"));
            if (from == null || to == null)
            {
                errors.put("viewFrom", "固定日期請填起止日期（yyyy-MM-dd）");
            }
            else if (to.isBefore(from))
            {
                errors.put("viewTo", "結束日期不能早於開始日期");
            }
            else if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > CUSTOM_MAX_DAYS)
            {
                errors.put("viewTo", "固定日期最多 " + CUSTOM_MAX_DAYS + " 天");
            }
            s.setViewFrom(from);
            s.setViewTo(to);
        }
        String cmp = trimToNull(str(body, "viewCmp"));
        if (cmp != null && "CUSTOM".equalsIgnoreCase(cmp))
        {
            java.time.LocalDate cf = dateOrNull(body.get("viewCmpFrom"));
            java.time.LocalDate ct = dateOrNull(body.get("viewCmpTo"));
            if (cf == null || ct == null || ct.isBefore(cf))
            {
                errors.put("viewCmp", "自訂對比請填起止日期（yyyy-MM-dd），結束不能早於開始");
            }
            s.setViewCmp("CUSTOM");
            s.setViewCmpFrom(cf);
            s.setViewCmpTo(ct);
        }
        else if (cmp != null && !"PREV".equalsIgnoreCase(cmp))
        {
            errors.put("viewCmp", "對比只能是上一週期或自訂");
        }
        else
        {
            s.setViewCmp("PREV");
        }
    }

    static void copyView(DashboardSubscription from, DashboardSubscription to)
    {
        to.setRangeCode(from.getRangeCode());
        to.setViewGran(from.getViewGran());
        to.setViewCmp(from.getViewCmp());
        to.setViewFrom(from.getViewFrom());
        to.setViewTo(from.getViewTo());
        to.setViewCmpFrom(from.getViewCmpFrom());
        to.setViewCmpTo(from.getViewCmpTo());
    }

    /** 请求体带了视图字段且与原值不同 */
    static boolean viewChanged(DashboardSubscription old, Map<String, Object> body)
    {
        if (body.containsKey("viewGran") && trimToNull(str(body, "viewGran")) != null && old.getViewGran() != null
            && !old.getViewGran().equalsIgnoreCase(str(body, "viewGran").trim()))
        {
            return true;
        }
        if (body.containsKey("viewFrom") && dateOrNull(body.get("viewFrom")) != null
            && !dateOrNull(body.get("viewFrom")).equals(old.getViewFrom()))
        {
            return true;
        }
        return body.containsKey("viewTo") && dateOrNull(body.get("viewTo")) != null
            && !dateOrNull(body.get("viewTo")).equals(old.getViewTo());
    }

    /**
     * 截图的页面筛选条件（写进运营总览 localStorage overview-filters）：按视图生成。
     * 相对时间用 {from} {to} 占位（每次发送按统计区间替换），固定日期直接写日期。
     */
    static String viewFilters(DashboardSubscription s)
    {
        SubscriptionRange range = SubscriptionRange.of(s.getRangeCode());
        String gran = (s.getViewGran() == null ? (range == null ? "HOUR" : range.getGranularity().name()) : s.getViewGran())
            .toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder("{");
        if (range == SubscriptionRange.TODAY)
        {
            sb.append("\"range\":\"today\"");
        }
        else if (range == SubscriptionRange.YESTERDAY)
        {
            sb.append("\"range\":\"yesterday\"");
        }
        else if (range == SubscriptionRange.CUSTOM && s.getViewFrom() != null && s.getViewTo() != null)
        {
            sb.append("\"range\":\"custom\",\"from\":\"").append(s.getViewFrom()).append("\",\"to\":\"").append(s.getViewTo()).append('"');
        }
        else
        {
            sb.append("\"range\":\"custom\",\"from\":\"{from}\",\"to\":\"{to}\"");
        }
        sb.append(",\"gran\":\"").append(gran).append('"');
        if (s.isCustomCompare())
        {
            sb.append(",\"cmp\":\"custom\",\"cmpFrom\":\"").append(s.getViewCmpFrom()).append("\",\"cmpTo\":\"")
                .append(s.getViewCmpTo()).append('"');
        }
        else
        {
            sb.append(",\"cmp\":\"previous-period\"");
        }
        return sb.append('}').toString();
    }

    /**
     * 接收者：只能是群（type = chat，群 ID 以 oc_ 开头）；去重。2026-11-13 起不发个人（V0.4 原型），传了个人报错。
     */
    static List<SubscriptionReceiver> receivers(Object raw, Map<String, String> errors)
    {
        List<SubscriptionReceiver> out = new ArrayList<>();
        if (!(raw instanceof List))
        {
            return out;
        }
        Set<String> seen = new HashSet<>();
        List<String> bad = new ArrayList<>();
        boolean person = false;
        for (Object o : (List<?>) raw)
        {
            if (!(o instanceof Map))
            {
                bad.add(String.valueOf(o));
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) o;
            String type = m.get("type") == null ? SubscriptionReceiver.CHAT
                : String.valueOf(m.get("type")).trim().toLowerCase(java.util.Locale.ROOT);
            String id = m.get("id") == null ? "" : String.valueOf(m.get("id")).trim();
            String name = m.get("name") == null ? null : String.valueOf(m.get("name")).trim();
            if (SubscriptionReceiver.USER.equals(type))
            {
                person = true;
                continue;
            }
            boolean ok = SubscriptionReceiver.CHAT.equals(type) && id.startsWith("oc_") && id.length() <= 64;
            if (!ok)
            {
                bad.add(type + ":" + id);
                continue;
            }
            if (seen.add(id))
            {
                out.add(new SubscriptionReceiver(SubscriptionReceiver.CHAT, id,
                    name == null || name.isEmpty() ? id : SubscriptionSender.cut(name, 128)));
            }
        }
        if (person)
        {
            errors.put("receivers", "只能發到通知群，不發個人");
        }
        else if (!bad.isEmpty())
        {
            errors.put("receivers", "接收群不正確：" + String.join("、", bad));
        }
        return out;
    }

    // ===================== 发送 =====================

    /** 立即发送的执行器（异步，结果写发送历史）；单测里换成同步 */
    private java.util.concurrent.Executor executor = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "subscription-send");
        t.setDaemon(true);
        return t;
    });

    void setExecutor(java.util.concurrent.Executor executor)
    {
        this.executor = executor;
    }

    /** 正在立即发送的订阅（同一实例内防重复点击；跨实例看 RUNNING 批次） */
    private final Set<Long> sending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 用表单当前内容预览（不保存、不发送）：按现在的整点取数 */
    public Map<String, Object> preview(Map<String, Object> body)
    {
        return preview(body, null);
    }

    /**
     * 同上；开了 AI 摘要时预览也会调用一次模型（计入每日次数）。另外返回排程：下次发送 nextAt、今天的发送时刻 times、
     * 频率文字 frequencyText、组合提示 warnings（不禁止）。
     */
    public Map<String, Object> preview(Map<String, Object> body, String operator)
    {
        String site = site(str(body, "siteCode"));
        DashboardSubscription s = new DashboardSubscription();
        s.setSiteCode(site);
        Map<String, String> errors = new LinkedHashMap<>();
        fill(s, body, errors, null);
        // 预览只要求能取数：名称重复、没选接收群、频率不完整不影响预览
        for (String f : List.of("name", "receivers", "sendHour", "freqType", "freqDow", "freqEvery", "freqTo"))
        {
            errors.remove(f);
        }
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        if (s.getName() == null)
        {
            s.setName("（未命名）");
        }
        if (s.getSnapshotFilters() == null)
        {
            s.setSnapshotFilters(viewFilters(s));
        }
        SubscriptionBrief brief = prepareOrFail(s, sender.nowHour(site), false);
        sender.addAi(s, brief, operator);
        sender.addSnapshot(s, brief, false);
        Map<String, Object> out = brief.toPreview();
        out.put("card", brief.getCard());
        out.putAll(schedule(s));
        return out;
    }

    /** 排程信息：下次发送、今天的发送时刻、频率文字、组合提示 */
    Map<String, Object> schedule(DashboardSubscription s)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now(sender.zone(s.getSiteCode()));
        SubscriptionRange range = SubscriptionRange.of(s.getRangeCode());
        List<String> times = new ArrayList<>();
        for (int h : SubscriptionSchedule.hoursOn(s, now.toLocalDate()))
        {
            times.add(String.format("%02d:00", h));
        }
        out.put("frequencyText", SubscriptionSchedule.text(s));
        out.put("times", SubscriptionSchedule.HOURS.equals(SubscriptionSchedule.type(s.getFreqType())) ? times : null);
        out.put("nextAt", s.getSendHour() == null ? null : nextRun(now, s));
        out.put("warnings", SubscriptionSchedule.warnings(s, range, now.toLocalDate()));
        out.put("sendDelayMinutes", properties.getSendDelayMinutes());
        return out;
    }

    public Map<String, Object> sendNow(Long id, String operator)
    {
        return sendNow(id, SubscriptionActor.admin(null, operator));
    }

    /**
     * 立即发送：异步执行（2026-11-13 确认），立即返回 { accepted, message }；按现在的整点取数，发给全部接收群，
     * 结果（含取数失败、截图失败）写发送历史，不影响定时发送。已暂停、没有接收群、上一次还没发完时不接受。
     */
    public Map<String, Object> sendNow(Long id, SubscriptionActor actor)
    {
        DashboardSubscription s = require(id);
        requireEditable(s, actor);
        if (!s.isOn())
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "訂閱已暫停");
        }
        List<SubscriptionReceiver> receivers = sender.receivers(s);
        if (receivers.isEmpty())
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "沒有正常的接收群，無法發送");
        }
        if (sending.contains(id) || mapper.countRunning(id, SubscriptionScheduler.STALE_MINUTES) > 0)
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "上一次發送還沒完成，請稍後到發送歷史查看結果");
        }
        sending.add(id);
        String operator = actor.getUsername();
        try
        {
            executor.execute(() -> {
                try
                {
                    sendNowSync(s, receivers, operator);
                }
                catch (RuntimeException e)
                {
                    log.error("[subscription] 立即发送异常 id={}", id, e);
                }
                finally
                {
                    sending.remove(id);
                }
            });
        }
        catch (RuntimeException e)
        {
            sending.remove(id);
            throw e;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", true);
        out.put("message", "已提交發送，結果請在「發送歷史」查看");
        return out;
    }

    /** 立即发送的实际执行：取数失败、截图失败都写发送历史（整次失败） */
    Map<String, Object> sendNowSync(DashboardSubscription s, List<SubscriptionReceiver> receivers, String operator)
    {
        LocalDateTime plan = sender.nowHour(s.getSiteCode());
        SubscriptionBrief brief;
        try
        {
            brief = sender.prepare(s, plan, false);
        }
        catch (SubscriptionSender.PrepareException e)
        {
            return sender.failWhole(s, null, receivers, SubscriptionSender.MANUAL, operator, null, plan, SubscriptionReason.DATA,
                e.getMessage());
        }
        sender.addAi(s, brief, operator);
        sender.addSnapshot(s, brief, true);
        return sender.deliverChecked(s, brief, receivers, SubscriptionSender.MANUAL, operator, null, plan);
    }

    /**
     * 测试发送（已保存的订阅）：发到订阅的接收群，卡片标【測試】，不记录、不影响排程。
     */
    public Map<String, Object> test(Long id, SubscriptionActor actor)
    {
        DashboardSubscription s = require(id);
        requireEditable(s, actor);
        return testSend(s, sender.receivers(s), actor.getUsername());
    }

    /**
     * 测试发送（抽屉里的表单，可以是还没保存的订阅）：按当前设定发一次到所选的群，卡片标【測試】，不记录、不影响排程。
     */
    public Map<String, Object> testForm(Map<String, Object> body, SubscriptionActor actor)
    {
        String site = site(str(body, "siteCode"));
        DashboardSubscription s = new DashboardSubscription();
        s.setSiteCode(site);
        Map<String, String> errors = new LinkedHashMap<>();
        if (!body.containsKey("snapshotEnabled"))
        {
            s.setSnapshotEnabled(sender.snapshotAvailable());
        }
        fill(s, body, errors, null);
        for (String f : List.of("name", "sendHour", "freqType", "freqDow", "freqEvery", "freqTo"))
        {
            errors.remove(f);
        }
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        if (s.getName() == null)
        {
            s.setName("（未命名）");
        }
        if (s.getSnapshotFilters() == null)
        {
            s.setSnapshotFilters(viewFilters(s));
        }
        List<SubscriptionReceiver> receivers = receivers(body.get("receivers"), errors);
        return testSend(s, receivers, actor.getUsername());
    }

    private Map<String, Object> testSend(DashboardSubscription s, List<SubscriptionReceiver> receivers, String operator)
    {
        if (receivers.isEmpty())
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "至少選擇一個通知群");
        }
        SubscriptionBrief brief = prepareOrFail(s, sender.nowHour(s.getSiteCode()), true);
        sender.addAi(s, brief, operator);
        sender.addSnapshot(s, brief, true);
        String shot = sender.snapshotFailure(s, brief);
        if (shot != null)
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "截圖生成失敗，本次未發送：" + shot);
        }
        return sender.deliver(s, brief, receivers, SubscriptionSender.TEST, operator, null);
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

    DashboardSubscription require(Long id)
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

    static Long longOrNull(Object v)
    {
        if (v == null || String.valueOf(v).trim().isEmpty())
        {
            return null;
        }
        if (v instanceof Number)
        {
            return ((Number) v).longValue();
        }
        try
        {
            return Long.valueOf(String.valueOf(v).trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    static java.time.LocalDate dateOrNull(Object v)
    {
        if (v == null)
        {
            return null;
        }
        try
        {
            return java.time.LocalDate.parse(String.valueOf(v).trim());
        }
        catch (java.time.format.DateTimeParseException e)
        {
            return null;
        }
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
