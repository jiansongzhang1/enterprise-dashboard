package com.fivetech.monitor.service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.monitor.domain.MonitorOrderRuleFilter;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.OrderRuleConfig;
import com.fivetech.monitor.domain.OrderRuleConfig.ChannelScope;
import com.fivetech.monitor.domain.OrderRuleOpenQuery;
import com.fivetech.monitor.domain.dto.MonitorOrderRuleBody;
import com.fivetech.promo.budget.PromoChannelDictionary.Channel;
import com.fivetech.promo.budget.PromoChannelDictionary.Promoter;

/**
 * 订单规则的纯函数：新建 / 编辑的校验、对外查询参数的校验、规则详情的组装（设计文档 5.1、5.3）。
 *
 * <p>不读库、不依赖 Spring，方便单测；服务层 {@code MonitorOrderRuleService} 负责取数和写库。
 * 面向用户的提示用繁體，按字段放进 errors（同指标规则，前端按字段标红）。</p>
 *
 * @author fivetech
 */
public final class MonitorOrderRules
{
    /** monitor_rule.rule_type */
    public static final String METRIC = "METRIC";

    public static final String TYPE = "ORDER";

    public static final String WITHDRAW = "WITHDRAW";

    public static final String SUBMIT = "SUBMIT";

    public static final String PAID = "PAID";

    public static final String SINGLE = "SINGLE";

    public static final String DAILY_TOTAL = "DAILY_TOTAL";

    /** 对外的指标编码（metric.code） */
    public static final String METRIC_SINGLE = "WITHDRAW_AMOUNT_SINGLE";

    public static final String METRIC_DAILY_TOTAL = "WITHDRAW_AMOUNT_DAILY_TOTAL";

    public static final String PROMOTER = "PROMOTER";

    public static final String CHANNEL = "CHANNEL";

    static final List<String> ORDER_TYPES = List.of(WITHDRAW);

    static final List<String> TRIGGER_ATS = List.of(SUBMIT, PAID);

    static final List<String> AMOUNT_MODES = List.of(SINGLE, DAILY_TOTAL);

    static final List<String> LEVELS = List.of("CRIT", "WARN");

    static final List<String> ORDER_BYS = List.of("version", "updateTime", "ruleNo");

    /** 每日上限可选值；0 = 不限 */
    static final List<Integer> DAILY_CAPS = List.of(10, 20, 50, 100, 0);

    public static final int DEFAULT_DAILY_CAP = 50;

    public static final int NAME_MAX = 30;

    public static final int DESCRIPTION_MAX = 200;

    static final BigDecimal AMOUNT_MAX = new BigDecimal("100000000");

    /** 同一规则同一分钟命中的多笔合并成一条（判定服务做） */
    public static final int MERGE_SECONDS = 60;

    public static final int PAGE_SIZE_DEFAULT = 100;

    public static final int PAGE_SIZE_MAX = 500;

    /** 多值参数最多几个 */
    static final int LIST_MAX = 500;

    static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern RULE_NO = Pattern.compile("(?i)R-?(\\d{1,9})");

    private static final Pattern DIGITS = Pattern.compile("\\d{1,18}");

    private MonitorOrderRules()
    {
    }

    /** 规则编号 R-023（与指标规则共用 rule_no） */
    public static String ruleCode(Integer ruleNo)
    {
        return ruleNo == null ? null : String.format("R-%03d", ruleNo);
    }

    /** 路径里的 {ruleId}：纯数字 = 规则 ID；不是数字返回 null */
    public static Long ruleIdOf(String ref)
    {
        String v = StringUtils.trim(ref);
        return v != null && DIGITS.matcher(v).matches() ? Long.valueOf(v) : null;
    }

    /** 规则编号 R-023 / r-23 / R023 → 23；格式不对返回 null */
    public static Integer ruleNoOf(String ref)
    {
        String v = StringUtils.trim(ref);
        if (v == null)
        {
            return null;
        }
        Matcher m = RULE_NO.matcher(v);
        return m.matches() ? Integer.valueOf(m.group(1)) : null;
    }

    // ------------------------------------------------------------------ 新建 / 编辑

    /** 校验通过后的规则内容 */
    public static final class Checked
    {
        public String name;

        public String description;

        public String level;

        public boolean notifyEnabled;

        /** 通知群（1–5 个，去掉空白与重复，保留顺序）；没选为空列表 */
        public List<String> notifyChatIds = new java.util.ArrayList<>();

        public OrderRuleConfig config;
    }

    /**
     * 校验请求体（负责人、通知群是否存在由服务层查库校验）。
     *
     * @param promoters 当前站点的推广商字典（配置中心 PROMO_CHANNELS）
     * @param existing  编辑时原来的条件：渠道范围没改时即使字典里已经没有也保留；新建传 null
     */
    public static Checked check(MonitorOrderRuleBody body, List<Promoter> promoters, OrderRuleConfig existing,
            Map<String, String> errors)
    {
        Checked c = new Checked();
        OrderRuleConfig cfg = new OrderRuleConfig();
        c.config = cfg;

        String name = StringUtils.trim(body.getName());
        if (StringUtils.isEmpty(name))
        {
            errors.putIfAbsent("name", "請填寫規則名稱");
        }
        else if (name.codePointCount(0, name.length()) > NAME_MAX)
        {
            errors.putIfAbsent("name", "規則名稱最多 " + NAME_MAX + " 字");
        }
        c.name = name;

        String description = StringUtils.trim(body.getDescription());
        if (description != null && description.codePointCount(0, description.length()) > DESCRIPTION_MAX)
        {
            errors.putIfAbsent("description", "說明最多 " + DESCRIPTION_MAX + " 字");
        }
        c.description = StringUtils.isEmpty(description) ? null : description;

        String orderType = upper(body.getOrderType());
        if (orderType == null)
        {
            orderType = WITHDRAW;
        }
        else if (!ORDER_TYPES.contains(orderType))
        {
            errors.putIfAbsent("orderType", "訂單類型本期只支持提款（WITHDRAW）");
        }
        cfg.setOrderType(orderType);

        cfg.setTriggerAt(pick(body.getTriggerAt(), TRIGGER_ATS, "triggerAt", "請選擇觸發時點（提交時 / 出款成功時）", errors));
        cfg.setAmountMode(pick(body.getAmountMode(), AMOUNT_MODES, "amountMode", "請選擇金額口徑（單筆 / 當日累計）", errors));
        cfg.setAmountThreshold(amount(body.getAmountThreshold(), errors));
        cfg.setChannelScope(channelScope(body.getChannelScope(), promoters, existing == null ? null : existing.getChannelScope(), errors));
        c.level = pick(body.getLevel(), LEVELS, "level", "請選擇等級（嚴重 / 注意）", errors);

        Integer cap = body.getDailyCap() == null ? DEFAULT_DAILY_CAP : body.getDailyCap();
        if (!DAILY_CAPS.contains(cap))
        {
            errors.putIfAbsent("dailyCap", "每日上限只能選 10、20、50、100 或不限（0）");
        }
        cfg.setDailyCap(cap);

        c.notifyEnabled = body.getNotifyEnabled() == null || body.getNotifyEnabled();
        c.notifyChatIds = MonitorChats.requested(body.getNotifyChatIds(), body.getNotifyChatId());
        if (c.notifyEnabled && c.notifyChatIds.isEmpty())
        {
            errors.putIfAbsent("notifyChatIds", "開啟通知時請選擇通知群");
            errors.putIfAbsent("notifyChatId", "開啟通知時請選擇通知群");
        }
        if (c.notifyChatIds.size() > MonitorChats.MAX)
        {
            errors.putIfAbsent("notifyChatIds", "通知群最多 " + MonitorChats.MAX + " 個");
            errors.putIfAbsent("notifyChatId", "通知群最多 " + MonitorChats.MAX + " 個");
        }
        return c;
    }

    /** 金额门槛：大于 0、最多 100,000,000、最多 2 位小数；返回去掉多余 0 的值 */
    static BigDecimal amount(BigDecimal v, Map<String, String> errors)
    {
        if (v == null)
        {
            errors.putIfAbsent("amountThreshold", "請填寫金額門檻");
            return null;
        }
        BigDecimal p = plain(v);
        if (p.signum() <= 0)
        {
            errors.putIfAbsent("amountThreshold", "金額門檻須大於 0");
        }
        else if (p.compareTo(AMOUNT_MAX) > 0)
        {
            errors.putIfAbsent("amountThreshold", "金額門檻最多 100,000,000");
        }
        else if (p.scale() > 2)
        {
            errors.putIfAbsent("amountThreshold", "金額門檻最多 2 位小數");
        }
        return p;
    }

    /**
     * 渠道范围：空 = 全站；g:推广商ID = 该推广商合计；c:推广商ID|推广渠道ID。
     * 推广商、渠道要在当前站点的 PROMO_CHANNELS 里，名称一并存下（展示用）。
     */
    static ChannelScope channelScope(String code, List<Promoter> promoters, ChannelScope existing, Map<String, String> errors)
    {
        String c = StringUtils.trim(code);
        if (StringUtils.isEmpty(c))
        {
            return null;
        }
        if (existing != null && c.equals(channelCode(existing)))
        {
            // 编辑时没改范围：字典后来删了该渠道也不报错，保留原来的名称
            return existing;
        }
        String[] ref = parseChannel(c);
        if (ref == null)
        {
            errors.putIfAbsent("channelScope", "渠道範圍格式不對：g:推廣商ID 或 c:推廣商ID|推廣渠道ID");
            return null;
        }
        Promoter promoter = null;
        for (Promoter p : promoters == null ? List.<Promoter>of() : promoters)
        {
            if (ref[1].equals(p.getId()))
            {
                promoter = p;
                break;
            }
        }
        if (promoter == null)
        {
            errors.putIfAbsent("channelScope", "推廣商不存在，請重新選擇");
            return null;
        }
        ChannelScope s = new ChannelScope();
        s.setType(ref[0]);
        s.setPromoterId(promoter.getId());
        s.setPromoterName(promoter.getName());
        if (CHANNEL.equals(ref[0]))
        {
            Channel channel = null;
            for (Channel ch : promoter.getChannels())
            {
                if (ref[2].equals(ch.getId()))
                {
                    channel = ch;
                    break;
                }
            }
            if (channel == null)
            {
                errors.putIfAbsent("channelScope", "推廣渠道不存在，請重新選擇");
                return null;
            }
            s.setChannelId(channel.getId());
            s.setChannelName(channel.getName());
        }
        return s;
    }

    /** g:ID → {PROMOTER, ID, null}；c:PID|CID → {CHANNEL, PID, CID}；格式不对返回 null */
    static String[] parseChannel(String code)
    {
        if (code == null || code.length() < 3 || code.charAt(1) != ':')
        {
            return null;
        }
        char kind = Character.toLowerCase(code.charAt(0));
        String rest = code.substring(2).trim();
        if (kind == 'g' && !rest.isEmpty() && rest.indexOf('|') < 0)
        {
            return new String[] { PROMOTER, rest, null };
        }
        int bar = rest.indexOf('|');
        if (kind == 'c' && bar > 0 && bar < rest.length() - 1 && rest.indexOf('|', bar + 1) < 0)
        {
            return new String[] { CHANNEL, rest.substring(0, bar).trim(), rest.substring(bar + 1).trim() };
        }
        return null;
    }

    /** 渠道范围的编码写法（同请求体）；全站返回 null */
    public static String channelCode(ChannelScope s)
    {
        if (s == null)
        {
            return null;
        }
        return CHANNEL.equals(s.getType()) ? "c:" + s.getPromoterId() + "|" + s.getChannelId() : "g:" + s.getPromoterId();
    }

    // ------------------------------------------------------------------ 规则详情（对外查询、后台详情共用）

    /** 组装详情需要的站点信息 */
    public static final class Site
    {
        public String siteCode;

        public String siteName;

        /** IANA 时区，如 Asia/Kolkata */
        public String timezone;

        /** 当前站点币种 */
        public String currency;

        public String currencySymbol;

        /** 库里的时间（统计时区）→ 站点时区 */
        public Function<LocalDateTime, LocalDateTime> toSiteTime = t -> t;
    }

    /**
     * 一句话摘要，同原型规则列表的「触发条件」：［ROI 推廣 › Telegram ROI］單筆提款 ≥ ₹50,000（提交時）；
     * 不限范围时不写范围（原型 2026-11-13 起「全站」改为「不限範圍」，只有和其他限定了范围的条件对照时才标出）。
     */
    public static String summary(OrderRuleConfig cfg, String symbol)
    {
        String when = PAID.equals(cfg.getTriggerAt()) ? "出款成功時" : "提交時";
        String what = DAILY_TOTAL.equals(cfg.getAmountMode()) ? "帳號當日累計提款" : "單筆提款";
        String scope = channelLabel(cfg.getChannelScope());
        return (scope == null ? "" : "［" + scope + "］") + what + " ≥ " + money(cfg.getAmountThreshold(), symbol) + "（" + when + "）";
    }

    /** 不限范围为 null；推廣商 X / X › Y（没有名称时用 ID） */
    static String channelLabel(ChannelScope s)
    {
        if (s == null)
        {
            return null;
        }
        String promoter = nameOr(s.getPromoterName(), s.getPromoterId());
        return CHANNEL.equals(s.getType()) ? promoter + " › " + nameOr(s.getChannelName(), s.getChannelId()) : promoter;
    }

    /** ₹50,000、₹1,234.5 */
    public static String money(BigDecimal v, String symbol)
    {
        if (v == null)
        {
            return "—";
        }
        DecimalFormat f = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return (symbol == null ? "" : symbol) + f.format(v);
    }

    /**
     * 规则详情（设计文档 5.3「返回的规则详情」）。不返回机器人凭证、群密钥等敏感信息。
     *
     * @param cfg monitor_rule.config 解析结果
     */
    public static Map<String, Object> view(MonitorRule r, OrderRuleConfig cfg, Site site)
    {
        String unit = site.currency != null && site.currency.equals(r.getCurrency()) ? site.currencySymbol : r.getCurrency();
        BigDecimal threshold = cfg.getAmountThreshold() == null ? null : plain(cfg.getAmountThreshold());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", r.getRuleId());
        m.put("ruleNo", ruleCode(r.getRuleNo()));
        m.put("siteCode", r.getSiteCode());
        m.put("siteName", site.siteName);
        m.put("ruleType", TYPE);
        m.put("name", r.getRuleName());
        m.put("description", r.getDescription());
        m.put("level", r.getLevel());
        m.put("enabled", Boolean.TRUE.equals(r.getEnabled()));
        // 已删除（只在增量拉取时出现，release 2026-11-17）
        m.put("deleted", "2".equals(r.getDelFlag()));

        Map<String, Object> metric = new LinkedHashMap<>();
        boolean daily = DAILY_TOTAL.equals(cfg.getAmountMode());
        metric.put("code", daily ? METRIC_DAILY_TOTAL : METRIC_SINGLE);
        metric.put("name", daily ? "帳號當日累計提款金額" : "單筆提款金額");
        metric.put("unit", unit);
        metric.put("currency", r.getCurrency());
        m.put("metric", metric);

        Map<String, Object> th = new LinkedHashMap<>();
        th.put("op", "GE");
        th.put("value", threshold);
        th.put("currency", r.getCurrency());
        m.put("threshold", th);

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("orderType", cfg.getOrderType() == null ? WITHDRAW : cfg.getOrderType());
        trigger.put("triggerAt", cfg.getTriggerAt());
        trigger.put("channelScope", channelView(cfg.getChannelScope()));
        if (daily)
        {
            trigger.put("dailyTotalRule", dailyTotalRule(cfg.getTriggerAt(), site.timezone));
        }
        trigger.put("summary", summary(cfg, unit));
        m.put("trigger", trigger);

        Map<String, Object> notify = new LinkedHashMap<>();
        // 通知群最多 5 个（release_next ㉑）；chatId / chatName / chatStatus 为第一个群（旧字段）
        List<Map<String, Object>> chats = new java.util.ArrayList<>();
        for (MonitorChats.Chat c : MonitorChats.parse(r.getNotifyChats(), r.getNotifyChatId(), nameOr(r.getChatName(), r.getNotifyChatName()),
                r.getChatStatus()))
        {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("chatId", c.chatId());
            cm.put("chatName", nameOr(c.name(), c.chatId()));
            cm.put("chatStatus", "dead".equals(c.status()) ? "BOT_REMOVED" : "NORMAL");
            chats.add(cm);
        }
        Map<String, Object> first = chats.isEmpty() ? null : chats.get(0);
        notify.put("enabled", !Boolean.FALSE.equals(r.getNotifyEnabled()));
        notify.put("platform", "LARK");
        notify.put("chats", chats);
        notify.put("chatId", first == null ? null : first.get("chatId"));
        notify.put("chatName", first == null ? null : first.get("chatName"));
        notify.put("chatStatus", first == null ? null : first.get("chatStatus"));
        notify.put("mergeSeconds", MERGE_SECONDS);
        notify.put("dailyCap", cfg.getDailyCap() == null ? DEFAULT_DAILY_CAP : cfg.getDailyCap());
        notify.put("capResetTimezone", site.timezone);
        notify.put("titlePrefix", StringUtils.isEmpty(site.siteName) ? "" : "【" + site.siteName + "】");
        m.put("notify", notify);

        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("userId", r.getOwnerUserId());
        owner.put("name", nameOr(r.getOwnerName(), r.getOwnerEmail()));
        owner.put("email", r.getOwnerEmail());
        // 负责人是否仍有效（账号未删除、未停用），同条件规则详情的 owner.valid
        owner.put("valid", Boolean.TRUE.equals(r.getOwnerValid()));
        m.put("owner", owner);

        // 站点币种在规则保存后改过：门槛可能要复核（后台详情提示用）
        m.put("currencyChanged", site.currency != null && r.getCurrency() != null && !site.currency.equals(r.getCurrency()));
        m.put("version", r.getVersion());
        m.put("createTime", time(r.getCreateTime(), site));
        m.put("createBy", nameOr(r.getCreateByEmail(), r.getCreateBy()));
        m.put("updateTime", time(r.getUpdateTime(), site));
        m.put("updateBy", nameOr(r.getUpdateByEmail(), r.getUpdateBy()));
        return m;
    }

    public static Map<String, Object> channelView(ChannelScope s)
    {
        if (s == null)
        {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", s.getType());
        m.put("code", channelCode(s));
        m.put("promoterId", s.getPromoterId());
        m.put("promoterName", s.getPromoterName());
        m.put("channelId", CHANNEL.equals(s.getType()) ? s.getChannelId() : null);
        m.put("channelName", CHANNEL.equals(s.getType()) ? s.getChannelName() : null);
        return m;
    }

    /**
     * 当日累计的口径（原型定下、交给判定服务执行，Yata 不执行）：
     * 提交时 = 当日已出款 + 审核中（含本笔）；出款成功时 = 当日已出款（含本笔）；已拒绝不算；
     * 跨过门槛的那一笔触发，同一账号同一规则一天只触发一次；按站点时区 0 点切日。
     */
    static Map<String, Object> dailyTotalRule(String triggerAt, String timezone)
    {
        boolean submit = !PAID.equals(triggerAt);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("includeStatuses", submit ? List.of("PAID", "PENDING") : List.of("PAID"));
        m.put("excludeStatuses", List.of("REJECTED"));
        m.put("includeCurrentOrder", true);
        m.put("dayResetTimezone", timezone);
        m.put("onePerAccountPerDay", true);
        m.put("description", (submit ? "當日已出款 + 審核中（含本筆），" : "當日已出款（含本筆），審核中")
                + "已拒絕不算；跨過門檻的那一筆觸發，同一帳號同一規則當天只觸發一次");
        return m;
    }

    // ------------------------------------------------------------------ 对外查询参数

    /**
     * 校验并转换查询参数。
     *
     * @param siteToStat 站点时区的时间 → 库里的统计时区（update_time 按统计时区存）
     */
    public static MonitorOrderRuleFilter filter(OrderRuleOpenQuery q, Function<LocalDateTime, LocalDateTime> siteToStat,
            Map<String, String> errors)
    {
        MonitorOrderRuleFilter f = new MonitorOrderRuleFilter();
        List<Long> ids = new ArrayList<>();
        for (String v : csv(q.getRuleIds(), "ruleIds", errors))
        {
            Long id = ruleIdOf(v);
            if (id == null)
            {
                errors.putIfAbsent("ruleIds", "ruleIds 只能是數字，多個用英文逗號分隔");
            }
            else
            {
                ids.add(id);
            }
        }
        f.ruleIds = ids;

        List<Integer> nos = new ArrayList<>();
        for (String v : csv(q.getRuleNos(), "ruleNos", errors))
        {
            Integer no = ruleNoOf(v);
            if (no == null)
            {
                errors.putIfAbsent("ruleNos", "ruleNos 格式如 R-023，多個用英文逗號分隔");
            }
            else
            {
                nos.add(no);
            }
        }
        f.ruleNos = nos;

        String keyword = StringUtils.trim(q.getKeyword());
        if (StringUtils.isNotEmpty(keyword))
        {
            if (keyword.codePointCount(0, keyword.length()) > NAME_MAX)
            {
                errors.putIfAbsent("keyword", "keyword 最多 " + NAME_MAX + " 字");
            }
            f.keyword = keyword.toLowerCase(Locale.ROOT);
        }

        f.enabled = bool(q.getEnabled(), "enabled", errors);
        f.notifyEnabled = bool(q.getNotifyEnabled(), "notifyEnabled", errors);
        f.orderTypes = enums(q.getOrderType(), ORDER_TYPES, "orderType", errors);
        f.triggerAts = enums(q.getTriggerAt(), TRIGGER_ATS, "triggerAt", errors);
        f.levels = enums(q.getLevel(), LEVELS, "level", errors);

        List<String> modes = new ArrayList<>();
        for (String v : enums(q.getMetric(), List.of(METRIC_SINGLE, METRIC_DAILY_TOTAL), "metric", errors))
        {
            modes.add(METRIC_DAILY_TOTAL.equals(v) ? DAILY_TOTAL : SINGLE);
        }
        f.amountModes = modes;

        String channel = StringUtils.trim(q.getChannel());
        if (StringUtils.isNotEmpty(channel))
        {
            if ("ALL".equalsIgnoreCase(channel))
            {
                f.channelMode = "ALL";
            }
            else
            {
                String[] ref = parseChannel(channel);
                if (ref == null)
                {
                    errors.putIfAbsent("channel", "channel 只能是 ALL、g:推廣商ID 或 c:推廣商ID|推廣渠道ID");
                }
                else
                {
                    f.channelMode = ref[0];
                    f.promoterId = ref[1];
                    f.channelId = ref[2];
                }
            }
        }

        f.chatIds = csv(q.getChatId(), "chatId", errors);
        String email = StringUtils.trim(q.getOwnerEmail());
        f.ownerEmail = StringUtils.isEmpty(email) ? null : email.toLowerCase(Locale.ROOT);

        f.amountMin = decimal(q.getAmountMin(), "amountMin", errors);
        f.amountMax = decimal(q.getAmountMax(), "amountMax", errors);
        if (f.amountMin != null && f.amountMax != null && f.amountMin.compareTo(f.amountMax) > 0)
        {
            errors.putIfAbsent("amountMin", "amountMin 不能大於 amountMax");
        }

        String since = StringUtils.trim(q.getSinceVersion());
        if (StringUtils.isNotEmpty(since))
        {
            if (DIGITS.matcher(since).matches())
            {
                f.sinceVersion = Long.valueOf(since);
            }
            else
            {
                errors.putIfAbsent("sinceVersion", "sinceVersion 只能是不小於 0 的整數");
            }
        }

        LocalDateTime from = time(q.getUpdatedFrom(), "updatedFrom", errors);
        LocalDateTime to = time(q.getUpdatedTo(), "updatedTo", errors);
        if (from != null && to != null && !from.isBefore(to))
        {
            errors.putIfAbsent("updatedFrom", "updatedFrom 須早於 updatedTo");
        }
        f.updatedFrom = from == null ? null : siteToStat.apply(from);
        f.updatedTo = to == null ? null : siteToStat.apply(to);

        String orderBy = StringUtils.trim(q.getOrderBy());
        if (StringUtils.isNotEmpty(orderBy))
        {
            String hit = null;
            for (String o : ORDER_BYS)
            {
                if (o.equalsIgnoreCase(orderBy))
                {
                    hit = o;
                }
            }
            if (hit == null)
            {
                errors.putIfAbsent("orderBy", "orderBy 只能是 version、updateTime 或 ruleNo");
            }
            else
            {
                f.orderBy = hit;
            }
        }

        Integer pageNum = integer(q.getPageNum(), "pageNum", errors);
        if (pageNum != null)
        {
            if (pageNum < 1)
            {
                errors.putIfAbsent("pageNum", "pageNum 從 1 開始");
            }
            f.pageNum = pageNum;
        }
        Integer pageSize = integer(q.getPageSize(), "pageSize", errors);
        if (pageSize != null)
        {
            if (pageSize < 1 || pageSize > PAGE_SIZE_MAX)
            {
                errors.putIfAbsent("pageSize", "pageSize 範圍 1–" + PAGE_SIZE_MAX);
            }
            f.pageSize = pageSize;
        }
        return f;
    }

    // ------------------------------------------------------------------ 小工具

    /** 去掉多余的 0；整数不带小数位（50000.00 → 50000） */
    static BigDecimal plain(BigDecimal v)
    {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static String pick(String value, List<String> allowed, String field, String message, Map<String, String> errors)
    {
        String v = upper(value);
        if (v == null || !allowed.contains(v))
        {
            errors.putIfAbsent(field, message);
        }
        return v;
    }

    private static String upper(String v)
    {
        String t = StringUtils.trim(v);
        return StringUtils.isEmpty(t) ? null : t.toUpperCase(Locale.ROOT);
    }

    private static String nameOr(String name, String fallback)
    {
        return StringUtils.isNotEmpty(name) ? name : fallback;
    }

    private static String time(LocalDateTime t, Site site)
    {
        return t == null ? null : site.toSiteTime.apply(t).format(SECOND);
    }

    /** 逗号分隔、去空格、去空项；超过 {@value #LIST_MAX} 个报错 */
    static List<String> csv(String value, String field, Map<String, String> errors)
    {
        List<String> out = new ArrayList<>();
        if (value == null)
        {
            return out;
        }
        for (String part : value.split(","))
        {
            String p = part.trim();
            if (!p.isEmpty() && !out.contains(p))
            {
                out.add(p);
            }
        }
        if (out.size() > LIST_MAX)
        {
            errors.putIfAbsent(field, field + " 最多 " + LIST_MAX + " 個");
        }
        return out;
    }

    private static List<String> enums(String value, List<String> allowed, String field, Map<String, String> errors)
    {
        List<String> out = new ArrayList<>();
        for (String v : csv(value, field, errors))
        {
            String u = v.toUpperCase(Locale.ROOT);
            if (allowed.contains(u))
            {
                out.add(u);
            }
            else
            {
                errors.putIfAbsent(field, field + " 只能是 " + String.join("、", allowed));
            }
        }
        return out;
    }

    private static Boolean bool(String value, String field, Map<String, String> errors)
    {
        String v = StringUtils.trim(value);
        if (StringUtils.isEmpty(v))
        {
            return null;
        }
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v))
        {
            return Boolean.valueOf(v.toLowerCase(Locale.ROOT));
        }
        errors.putIfAbsent(field, field + " 只能是 true 或 false");
        return null;
    }

    private static BigDecimal decimal(String value, String field, Map<String, String> errors)
    {
        String v = StringUtils.trim(value);
        if (StringUtils.isEmpty(v))
        {
            return null;
        }
        try
        {
            return new BigDecimal(v);
        }
        catch (NumberFormatException e)
        {
            errors.putIfAbsent(field, field + " 須是數字");
            return null;
        }
    }

    private static Integer integer(String value, String field, Map<String, String> errors)
    {
        String v = StringUtils.trim(value);
        if (StringUtils.isEmpty(v))
        {
            return null;
        }
        if (v.matches("-?\\d{1,9}"))
        {
            return Integer.valueOf(v);
        }
        errors.putIfAbsent(field, field + " 須是整數");
        return null;
    }

    /** yyyy-MM-dd HH:mm:ss，或只给日期（当天 00:00:00） */
    private static LocalDateTime time(String value, String field, Map<String, String> errors)
    {
        String v = StringUtils.trim(value);
        if (StringUtils.isEmpty(v))
        {
            return null;
        }
        try
        {
            return v.length() == 10 ? LocalDate.parse(v).atStartOfDay() : LocalDateTime.parse(v, SECOND);
        }
        catch (DateTimeParseException e)
        {
            errors.putIfAbsent(field, field + " 格式為 yyyy-MM-dd HH:mm:ss");
            return null;
        }
    }
}
