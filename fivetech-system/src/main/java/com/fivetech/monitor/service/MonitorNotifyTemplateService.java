package com.fivetech.monitor.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorNotifyTemplate;
import com.fivetech.monitor.engine.MonitorLarkPost;
import com.fivetech.monitor.engine.MonitorTemplateRenderer;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.mapper.MonitorNotifyTemplateMapper;

/**
 * 告警通知模板：取模板（站点专用 → 全站默认 → 代码内置）、渲染，以及系统管理页面的增删改、预览、测试发送。
 * <p>取模板结果在本机缓存 60 秒，页面上修改后立即清空本机缓存（多实例时其他实例最多 60 秒后生效）。</p>
 *
 * @author fivetech
 */
@Service
public class MonitorNotifyTemplateService
{
    private static final Logger log = LoggerFactory.getLogger(MonitorNotifyTemplateService.class);

    public static final String PLATFORM_LARK = "lark";

    private static final long CACHE_MILLIS = 60_000L;

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 预览 / 测试发送用的示例值 */
    static final Map<String, String> SAMPLE;

    static
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("site", "uWin");
        m.put("metric", "存款成功率");
        m.put("currentValue", "81.33%");
        m.put("judgeText", "低於嚴重閾值 90.00%");
        m.put("ruleCode", "R-001");
        m.put("triggerTime", "2026-10-22 14:00");
        m.put("owner", "zhang@yata.demo");
        m.put("level", "嚴重");
        m.put("reason", "近 2 小時 81.33% – 84.20%，低於嚴重線 90.00%");
        m.put("reasonValue", "近 2 小時 81.33% – 84.20%");
        m.put("reasonLine", "低於嚴重線 90.00%");
        m.put("baseline", "");
        m.put("warnLine", "< 95.00%");
        m.put("critLine", "< 90.00%");
        m.put("periodText", "每小時 · 連續 2 小時");
        m.put("escalateHours", "3");
        m.put("eventUrl", "");
        m.put("sentAt", "2026-10-22 15:05");
        SAMPLE = java.util.Collections.unmodifiableMap(m);
    }

    private final MonitorNotifyTemplateMapper mapper;

    private final DashboardProperties dashboard;

    private final MonitorProperties properties;

    private final LarkClient larkClient;

    private final ZoneId zone;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public MonitorNotifyTemplateService(MonitorNotifyTemplateMapper mapper, DashboardProperties dashboard,
            MonitorProperties properties, LarkClient larkClient)
    {
        this.mapper = mapper;
        this.dashboard = dashboard;
        this.properties = properties;
        this.larkClient = larkClient;
        this.zone = ZoneId.of(dashboard.getTimezone());
    }

    // ================================================================== 发送时用

    /** 渲染结果：正文 + 用的模板（内置文案时 templateId 为 null） */
    public static final class Rendered
    {
        private final String content;
        private final Long templateId;
        private final Integer templateVersion;

        Rendered(String content, Long templateId, Integer templateVersion)
        {
            this.content = content;
            this.templateId = templateId;
            this.templateVersion = templateVersion;
        }

        public String getContent() { return content; }
        public Long getTemplateId() { return templateId; }
        public Integer getTemplateVersion() { return templateVersion; }
    }

    /**
     * 渲染告警正文：站点专用 → 全站默认 → 内置默认模板（{@link MonitorNotifyTemplate#DEFAULT_CONTENT}）。
     * 取模板或渲染出错都退回内置默认模板，不影响告警。
     */
    public Rendered render(String siteCode, String notifyType, Map<String, String> values)
    {
        try
        {
            MonitorNotifyTemplate t = effective(siteCode, notifyType);
            if (t != null)
            {
                String text = MonitorTemplateRenderer.render(t.getContent(), values);
                if (StringUtils.isNotEmpty(text))
                {
                    return new Rendered(text, t.getTemplateId(), t.getVersion());
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] 通知模板渲染失败，改用内置文案 site={} type={}：{}", siteCode, notifyType, e.getMessage());
        }
        return new Rendered(MonitorTemplateRenderer.render(MonitorNotifyTemplate.DEFAULT_CONTENT, values), null, null);
    }

    /** 当前生效的模板；没有为 null */
    public MonitorNotifyTemplate effective(String siteCode, String notifyType)
    {
        String key = StringUtils.nvl(siteCode, "*") + "|" + notifyType;
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && c.expireAt > now)
        {
            return c.template;
        }
        List<MonitorNotifyTemplate> list = mapper.selectCandidates(siteCode, notifyType, PLATFORM_LARK);
        MonitorNotifyTemplate t = list == null || list.isEmpty() ? null : list.get(0);
        cache.put(key, new Cached(t, now + CACHE_MILLIS));
        return t;
    }

    private static final class Cached
    {
        private final MonitorNotifyTemplate template;
        private final long expireAt;

        Cached(MonitorNotifyTemplate template, long expireAt)
        {
            this.template = template;
            this.expireAt = expireAt;
        }
    }

    /**
     * 启动时初始化全站默认模板（{@link MonitorNotifyTemplateInitializer} 调用）：
     * <ul>
     *   <li>某种类型还没有全站默认模板 → 用 {@link MonitorNotifyTemplate#DEFAULT_CONTENT} 新建；</li>
     *   <li>全站默认还是旧的预置内容（{@link MonitorNotifyTemplate#LEGACY_CONTENTS}）且没人改过 → 换成当前的 FY-30 格式；</li>
     *   <li>有人改过的（内容不同）不动。</li>
     * </ul>
     *
     * @return 新建或更新的条数
     */
    public int initDefaults()
    {
        int changed = 0;
        List<MonitorNotifyTemplate> all = mapper.selectAll();
        LocalDateTime now = now();
        for (String type : MonitorNotifyTemplate.TYPES)
        {
            MonitorNotifyTemplate def = find(all, null, type, false);
            if (def == null)
            {
                MonitorNotifyTemplate t = new MonitorNotifyTemplate();
                t.setNotifyType(type);
                t.setPlatform(PLATFORM_LARK);
                t.setMsgFormat("text");
                t.setContent(MonitorNotifyTemplate.DEFAULT_CONTENT);
                t.setEnabled(true);
                t.setRemark(MonitorNotifyTemplate.ALERT_NEW.equals(type) ? "新觸發的嚴重（FY-30 預設）" : "注意升級為嚴重（FY-30 預設）");
                t.setCreateBy("system");
                t.setCreateTime(now);
                t.setUpdateBy("system");
                t.setUpdateTime(now);
                try
                {
                    mapper.insert(t);
                    changed++;
                }
                catch (DuplicateKeyException e)
                {
                    // 另一个实例同时在初始化
                }
            }
            else if (MonitorNotifyTemplate.LEGACY_CONTENTS.contains(normalize(def.getContent())))
            {
                def.setContent(MonitorNotifyTemplate.DEFAULT_CONTENT);
                def.setEnabled(true);
                def.setUpdateBy("system");
                def.setUpdateTime(now);
                mapper.update(def);
                changed++;
            }
        }
        if (changed > 0)
        {
            cache.clear();
            log.info("[monitor] 通知模板：初始化 / 升级全站默认模板 {} 条", changed);
        }
        return changed;
    }

    // ================================================================== 系统管理页面

    /** 列表 + 每个站点每种类型实际生效的是哪一份（SITE 站点专用 / DEFAULT 全站默认 / BUILTIN 代码内置） */
    public Map<String, Object> list()
    {
        List<MonitorNotifyTemplate> all = mapper.selectAll();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MonitorNotifyTemplate t : all)
        {
            rows.add(row(t));
        }
        List<Map<String, Object>> effective = new ArrayList<>();
        for (String site : siteCodes())
        {
            for (String type : MonitorNotifyTemplate.TYPES)
            {
                MonitorNotifyTemplate own = find(all, site, type, true);
                MonitorNotifyTemplate def = find(all, null, type, true);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("siteCode", site);
                e.put("siteName", dashboard.siteName(site));
                e.put("notifyType", type);
                e.put("source", own != null ? "SITE" : def != null ? "DEFAULT" : "BUILTIN");
                e.put("templateId", own != null ? own.getTemplateId() : def != null ? def.getTemplateId() : null);
                effective.add(e);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("effective", effective);
        return out;
    }

    /** 占位符：名字、说明、示例值 */
    public List<Map<String, String>> placeholders()
    {
        List<Map<String, String>> list = new ArrayList<>();
        for (Map.Entry<String, String> e : MonitorTemplateRenderer.PLACEHOLDERS.entrySet())
        {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("token", "{{" + e.getKey() + "}}");
            m.put("description", e.getValue());
            m.put("sample", SAMPLE.getOrDefault(e.getKey(), ""));
            list.add(m);
        }
        return list;
    }

    public Long create(MonitorNotifyTemplate body, String operator)
    {
        String type = checkType(body.getNotifyType());
        String site = checkSite(body.getSiteCode());
        checkContent(body.getContent());
        MonitorNotifyTemplate t = new MonitorNotifyTemplate();
        t.setSiteCode(site);
        t.setNotifyType(type);
        t.setPlatform(PLATFORM_LARK);
        t.setMsgFormat("text");
        t.setContent(normalize(body.getContent()));
        t.setEnabled(body.getEnabled() == null || body.getEnabled());
        t.setRemark(StringUtils.substring(StringUtils.nvl(body.getRemark(), ""), 0, 500));
        LocalDateTime now = now();
        t.setCreateBy(StringUtils.nvl(operator, ""));
        t.setCreateTime(now);
        t.setUpdateBy(StringUtils.nvl(operator, ""));
        t.setUpdateTime(now);
        try
        {
            mapper.insert(t);
        }
        catch (DuplicateKeyException e)
        {
            throw MonitorFieldException.of("notifyType", (site == null ? "全站預設" : dashboard.siteName(site)) + "已有這種通知類型的模板，請直接修改");
        }
        cache.clear();
        return t.getTemplateId();
    }

    /** 只能改内容、启用状态和备注；站点、类型不能改（要换就新建一份） */
    public void update(Long id, MonitorNotifyTemplate body, String operator)
    {
        MonitorNotifyTemplate t = require(id);
        checkContent(body.getContent());
        if (t.getSiteCode() == null && Boolean.FALSE.equals(body.getEnabled()))
        {
            throw MonitorFieldException.of("enabled", "全站預設模板不能停用（停用後沒有專用模板的站點會改用內置文案）");
        }
        t.setContent(normalize(body.getContent()));
        t.setEnabled(body.getEnabled() == null ? t.getEnabled() : body.getEnabled());
        t.setRemark(StringUtils.substring(StringUtils.nvl(body.getRemark(), StringUtils.nvl(t.getRemark(), "")), 0, 500));
        t.setUpdateBy(StringUtils.nvl(operator, ""));
        t.setUpdateTime(now());
        mapper.update(t);
        cache.clear();
    }

    public void delete(Long id)
    {
        MonitorNotifyTemplate t = require(id);
        if (t.getSiteCode() == null)
        {
            throw new ServiceException("全站預設模板不能刪除，可以直接修改內容");
        }
        mapper.deleteSiteTemplate(id);
        cache.clear();
    }

    /** 预览：用示例值渲染；有错误时返回 errors，content 为 null */
    public Map<String, Object> preview(String content)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> errors = MonitorTemplateRenderer.validate(content);
        out.put("errors", errors);
        out.put("content", errors.isEmpty() ? MonitorTemplateRenderer.render(content, SAMPLE) : null);
        return out;
    }

    /**
     * 测试发送：用示例值渲染 content（不传则用模板 id 的内容），开头加「【測試】」发到 chatId。
     *
     * @return { success, messageId, error }
     */
    public Map<String, Object> test(Long id, String content, String chatId)
    {
        if (StringUtils.isEmpty(StringUtils.trim(chatId)))
        {
            throw MonitorFieldException.of("chatId", "請選擇要發送的 Lark 群");
        }
        String src = StringUtils.isNotEmpty(content) ? content : require(id).getContent();
        checkContent(src);
        String text = "【測試】" + MonitorTemplateRenderer.render(src, SAMPLE);
        MonitorProperties.Lark lark = properties.getLark();
        LarkResult<String> r = larkClient.sendPost(lark.getAppId(), lark.getAppSecret(), "chat_id", chatId.trim(), "",
            MonitorLarkPost.paragraphs(text));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", r.isSuccess());
        out.put("messageId", r.isSuccess() ? r.getData() : null);
        out.put("error", r.isSuccess() ? null : r.getCategory() + (r.getLarkCode() == null ? "" : " code=" + r.getLarkCode())
            + (StringUtils.isEmpty(r.getMsg()) ? "" : " " + r.getMsg()));
        return out;
    }

    // ------------------------------------------------------------------ 校验

    private MonitorNotifyTemplate require(Long id)
    {
        MonitorNotifyTemplate t = id == null ? null : mapper.selectById(id);
        if (t == null)
        {
            throw new ServiceException("模板不存在", 404);
        }
        return t;
    }

    private static String checkType(String type)
    {
        String t = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        if (!MonitorNotifyTemplate.TYPES.contains(t))
        {
            throw MonitorFieldException.of("notifyType", "通知類型只能是 ALERT_NEW（新觸發）、ALERT_ESCALATED（注意升級為嚴重）");
        }
        return t;
    }

    /** 空 = 全站默认；有值必须是已配置的站点 */
    private String checkSite(String site)
    {
        if (StringUtils.isEmpty(StringUtils.trim(site)))
        {
            return null;
        }
        String c = dashboard.canonical(site);
        if (c == null)
        {
            throw MonitorFieldException.of("siteCode", "站點不存在或未配置：" + site);
        }
        return c;
    }

    private static void checkContent(String content)
    {
        List<String> errors = MonitorTemplateRenderer.validate(content);
        if (!errors.isEmpty())
        {
            throw MonitorFieldException.of("content", String.join("；", errors));
        }
    }

    private static String normalize(String content)
    {
        return content.replace("\r\n", "\n");
    }

    private List<String> siteCodes()
    {
        return dashboard.allSiteCodes();
    }

    private static MonitorNotifyTemplate find(List<MonitorNotifyTemplate> all, String site, String type, boolean enabledOnly)
    {
        for (MonitorNotifyTemplate t : all)
        {
            boolean siteOk = site == null ? t.getSiteCode() == null : site.equalsIgnoreCase(t.getSiteCode());
            if (siteOk && type.equals(t.getNotifyType()) && (!enabledOnly || Boolean.TRUE.equals(t.getEnabled())))
            {
                return t;
            }
        }
        return null;
    }

    private Map<String, Object> row(MonitorNotifyTemplate t)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateId", t.getTemplateId());
        m.put("siteCode", t.getSiteCode());
        m.put("siteName", t.getSiteCode() == null ? null : dashboard.siteName(t.getSiteCode()));
        m.put("isDefault", t.getSiteCode() == null);
        m.put("notifyType", t.getNotifyType());
        m.put("platform", t.getPlatform());
        m.put("msgFormat", t.getMsgFormat());
        m.put("content", t.getContent());
        m.put("enabled", Boolean.TRUE.equals(t.getEnabled()));
        m.put("version", t.getVersion());
        m.put("remark", t.getRemark());
        m.put("updateBy", t.getUpdateBy());
        m.put("updateTime", t.getUpdateTime() == null ? null : t.getUpdateTime().format(MINUTE));
        return m;
    }

    private LocalDateTime now()
    {
        return LocalDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS);
    }
}
