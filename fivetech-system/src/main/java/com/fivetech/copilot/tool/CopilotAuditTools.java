package com.fivetech.copilot.tool;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.copilot.AiClient;
import com.fivetech.copilot.CopilotProperties;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.system.domain.SysLogininfor;
import com.fivetech.system.domain.SysOperLog;
import com.fivetech.system.mapper.SysLogininforMapper;
import com.fivetech.system.mapper.SysOperLogMapper;
import com.github.pagehelper.PageHelper;

/**
 * AI 助手的审计日志查询工具（受限工具，不是任意 SQL）。
 *
 * <ul>
 *   <li>模型只能填筛选条件，SQL 由后端按固定模板拼，没有注入面；</li>
 *   <li>权限跟随页面：{@code query_oper_log} 要 monitor:operlog:list，{@code query_login_log} 要 monitor:logininfor:list，
 *       没权限的工具根本不给模型，模型硬调也会被挡；</li>
 *   <li>不返回 oper_param、json_result、error_msg：里面可能有请求体、响应体等敏感内容；</li>
 *   <li>时间按站点时区理解，行数、时间跨度都有上限，每次调用记一行日志。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class CopilotAuditTools
{
    private static final Logger log = LoggerFactory.getLogger(CopilotAuditTools.class);

    public static final String OPER_LOG = "query_oper_log";

    public static final String LOGIN_LOG = "query_login_log";

    static final String PERMI_OPER = "monitor:operlog:list";

    static final String PERMI_LOGIN = "monitor:logininfor:list";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 业务类型：RuoYi 的 business_type 取值 */
    private static final Map<String, Integer> BUSINESS = new LinkedHashMap<>();

    private static final Map<Integer, String> BUSINESS_NAME = new LinkedHashMap<>();

    static
    {
        String[] names = { "其它", "新增", "修改", "删除", "授权", "导出", "导入", "强退", "生成代码", "清空数据" };
        String[] codes = { "OTHER", "INSERT", "UPDATE", "DELETE", "GRANT", "EXPORT", "IMPORT", "FORCE", "GENCODE", "CLEAN" };
        for (int i = 0; i < codes.length; i++)
        {
            BUSINESS.put(codes[i], i);
            BUSINESS_NAME.put(i, names[i]);
        }
    }

    private final CopilotProperties properties;

    private final DashboardProperties dashboard;

    private final SysOperLogMapper operLogMapper;

    private final SysLogininforMapper logininforMapper;

    public CopilotAuditTools(CopilotProperties properties, DashboardProperties dashboard,
            SysOperLogMapper operLogMapper, SysLogininforMapper logininforMapper)
    {
        this.properties = properties;
        this.dashboard = dashboard;
        this.operLogMapper = operLogMapper;
        this.logininforMapper = logininforMapper;
    }

    /** 当前登录人能用的工具；关了开关或一个都没权限时返回空（空 = 不带工具调用模型） */
    public List<AiClient.Tool> tools()
    {
        List<AiClient.Tool> out = new ArrayList<>();
        if (!properties.getChat().getDataQuery().isEnabled())
        {
            return out;
        }
        if (hasPermi(PERMI_OPER))
        {
            out.add(new AiClient.Tool(OPER_LOG,
                "查后台操作日志（谁在什么时候改了什么）。按时间倒序返回，不含请求体和返回值。"
                    + "问「某人做过什么」「谁删过规则」「昨天有哪些失败操作」用它。",
                schema(true)));
        }
        if (hasPermi(PERMI_LOGIN))
        {
            out.add(new AiClient.Tool(LOGIN_LOG,
                "查后台登录日志（谁在什么时候登录、成功还是失败、来源 IP）。按时间倒序返回。"
                    + "问「某人最近登录情况」「有没有异常登录」用它。",
                schema(false)));
        }
        return out;
    }

    /** 入参 JSON Schema；operLog = true 时多 title / businessType 两个条件 */
    private Map<String, Object> schema(boolean operLog)
    {
        CopilotProperties.DataQuery cfg = properties.getChat().getDataQuery();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("from", str("开始日期 yyyy-MM-dd（含），按站点时区。不传 = 往前 " + cfg.getDefaultDays() + " 天"));
        props.put("to", str("结束日期 yyyy-MM-dd（含），按站点时区。不传 = 今天"));
        props.put(operLog ? "operName" : "userName", str("操作人账号，模糊匹配。不传 = 所有人"));
        if (operLog)
        {
            props.put("title", str("功能模块名，模糊匹配，如「告警规则」「站点管理」。不传 = 所有模块"));
            Map<String, Object> bt = str("业务类型");
            bt.put("enum", new ArrayList<>(BUSINESS.keySet()));
            props.put("businessType", bt);
        }
        Map<String, Object> status = str("只看成功或失败。不传 = 都要");
        status.put("enum", List.of("SUCCESS", "FAIL"));
        props.put("status", status);
        props.put("ip", str("来源 IP，模糊匹配"));
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("description", "最多返回几条，1–" + cfg.getMaxRows() + "，默认 " + cfg.getDefaultRows());
        props.put("limit", limit);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of());
        return schema;
    }

    private static Map<String, Object> str(String description)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        return m;
    }

    /** 执行一次工具调用；任何失败都返回文字说明，不抛异常 */
    public String execute(AiClient.ToolCall call, String operator)
    {
        String name = call.getName();
        long started = System.currentTimeMillis();
        try
        {
            String text;
            if (OPER_LOG.equals(name))
            {
                require(PERMI_OPER);
                text = operLog(call.getInput());
            }
            else if (LOGIN_LOG.equals(name))
            {
                require(PERMI_LOGIN);
                text = loginLog(call.getInput());
            }
            else
            {
                return "沒有這個工具：" + name;
            }
            log.info("[copilot] tool={} user={} args={} {}ms", name, operator, call.getInput(),
                System.currentTimeMillis() - started);
            return text;
        }
        catch (Forbidden e)
        {
            log.warn("[copilot] tool={} user={} 无权限", name, operator);
            return e.getMessage();
        }
        catch (RuntimeException e)
        {
            log.error("[copilot] tool={} user={} 查询失败：{}", name, operator, e.getMessage(), e);
            return "查詢失敗：" + e.getMessage();
        }
    }

    /** 当前登录人是否有这个权限；拿不到登录态一律当没有 */
    static boolean hasPermi(String permi)
    {
        try
        {
            LoginUser user = SecurityUtils.getLoginUser();
            return user != null && user.getPermissions() != null && SecurityUtils.hasPermi(user.getPermissions(), permi);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private void require(String permi)
    {
        if (!hasPermi(permi))
        {
            throw new Forbidden("你沒有查看這份日誌的權限（需要 " + permi + "），請聯繫管理員授權。");
        }
    }

    // ------------------------------------------------------------------ 操作日志

    private String operLog(Map<String, Object> input)
    {
        CopilotProperties.DataQuery cfg = properties.getChat().getDataQuery();
        LocalDate[] range = range(input, cfg);
        int limit = limit(input, cfg);

        SysOperLog q = new SysOperLog();
        q.setOperName(text(input, "operName"));
        q.setTitle(text(input, "title"));
        q.setOperIp(text(input, "ip"));
        Integer status = status(input);
        q.setStatus(status);
        String bt = text(input, "businessType");
        if (bt != null && BUSINESS.containsKey(bt.toUpperCase(java.util.Locale.ROOT)))
        {
            q.setBusinessType(BUSINESS.get(bt.toUpperCase(java.util.Locale.ROOT)));
        }
        q.getParams().put("beginTime", range[0].format(DATE) + " 00:00:00");
        q.getParams().put("endTime", range[1].format(DATE) + " 23:59:59");

        List<SysOperLog> rows;
        try (com.github.pagehelper.Page<SysOperLog> page = PageHelper.startPage(1, limit, false))
        {
            rows = operLogMapper.selectOperLogList(q);
        }
        if (rows.isEmpty())
        {
            return head(range, limit, input) + "\n沒有符合條件的記錄。";
        }
        StringBuilder sb = new StringBuilder(head(range, limit, input));
        sb.append("\n時間 | 操作人 | 模塊 | 類型 | 狀態 | IP | 耗時ms");
        for (SysOperLog r : rows)
        {
            sb.append('\n').append(time(r.getOperTime()))
                .append(" | ").append(nvl(r.getOperName()))
                .append(" | ").append(nvl(r.getTitle()))
                .append(" | ").append(BUSINESS_NAME.getOrDefault(r.getBusinessType(), "—"))
                .append(" | ").append(r.getStatus() != null && r.getStatus() == 0 ? "成功" : "失敗")
                .append(" | ").append(nvl(r.getOperIp()))
                .append(" | ").append(r.getCostTime() == null ? "—" : r.getCostTime());
        }
        if (rows.size() >= limit)
        {
            sb.append("\n（已達本次返回上限 ").append(limit).append(" 條，可能還有更多；可縮小時間範圍或加條件）");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 登录日志

    private String loginLog(Map<String, Object> input)
    {
        CopilotProperties.DataQuery cfg = properties.getChat().getDataQuery();
        LocalDate[] range = range(input, cfg);
        int limit = limit(input, cfg);

        SysLogininfor q = new SysLogininfor();
        q.setUserName(text(input, "userName"));
        q.setIpaddr(text(input, "ip"));
        Integer status = status(input);
        q.setStatus(status == null ? null : String.valueOf(status));
        q.getParams().put("beginTime", range[0].format(DATE) + " 00:00:00");
        q.getParams().put("endTime", range[1].format(DATE) + " 23:59:59");

        List<SysLogininfor> rows;
        try (com.github.pagehelper.Page<SysLogininfor> page = PageHelper.startPage(1, limit, false))
        {
            rows = logininforMapper.selectLogininforList(q);
        }
        if (rows.isEmpty())
        {
            return head(range, limit, input) + "\n沒有符合條件的記錄。";
        }
        StringBuilder sb = new StringBuilder(head(range, limit, input));
        sb.append("\n時間 | 帳號 | 狀態 | IP | 地點 | 瀏覽器 | 說明");
        for (SysLogininfor r : rows)
        {
            sb.append('\n').append(time(r.getLoginTime()))
                .append(" | ").append(nvl(r.getUserName()))
                .append(" | ").append("0".equals(r.getStatus()) ? "成功" : "失敗")
                .append(" | ").append(nvl(r.getIpaddr()))
                .append(" | ").append(nvl(r.getLoginLocation()))
                .append(" | ").append(nvl(r.getBrowser()))
                .append(" | ").append(nvl(r.getMsg()));
        }
        if (rows.size() >= limit)
        {
            sb.append("\n（已達本次返回上限 ").append(limit).append(" 條，可能還有更多）");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 入参

    /** 时间范围：默认往前 defaultDays 天到今天；跨度超过 maxDays 时截到 maxDays */
    LocalDate[] range(Map<String, Object> input, CopilotProperties.DataQuery cfg)
    {
        LocalDate today = LocalDate.now(dashboard.currentZone());
        LocalDate to = date(text(input, "to"), today);
        LocalDate from = date(text(input, "from"), to.minusDays(Math.max(0, cfg.getDefaultDays() - 1)));
        if (to.isAfter(today))
        {
            to = today;
        }
        if (from.isAfter(to))
        {
            from = to;
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > cfg.getMaxDays())
        {
            from = to.minusDays(Math.max(0, cfg.getMaxDays() - 1));
        }
        return new LocalDate[] { from, to };
    }

    private static LocalDate date(String text, LocalDate fallback)
    {
        if (StringUtils.isEmpty(text))
        {
            return fallback;
        }
        try
        {
            String t = text.trim();
            return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t, DATE);
        }
        catch (DateTimeParseException e)
        {
            return fallback;
        }
    }

    int limit(Map<String, Object> input, CopilotProperties.DataQuery cfg)
    {
        Object v = input.get("limit");
        int n = cfg.getDefaultRows();
        if (v instanceof Number)
        {
            n = ((Number) v).intValue();
        }
        else if (v != null)
        {
            try
            {
                n = Integer.parseInt(String.valueOf(v).trim());
            }
            catch (NumberFormatException ignore)
            {
                n = cfg.getDefaultRows();
            }
        }
        return Math.max(1, Math.min(cfg.getMaxRows(), n));
    }

    /** SUCCESS → 0，FAIL → 1，其他 → null（都要） */
    static Integer status(Map<String, Object> input)
    {
        Object v = input.get("status");
        if (v == null)
        {
            return null;
        }
        String s = String.valueOf(v).trim().toUpperCase(java.util.Locale.ROOT);
        if ("SUCCESS".equals(s) || "0".equals(s))
        {
            return 0;
        }
        return "FAIL".equals(s) || "FAILED".equals(s) || "1".equals(s) ? 1 : null;
    }

    private static String text(Map<String, Object> input, String key)
    {
        Object v = input.get(key);
        String s = v == null ? null : String.valueOf(v).trim();
        return StringUtils.isEmpty(s) || "null".equals(s) ? null : s;
    }

    private String head(LocalDate[] range, int limit, Map<String, Object> input)
    {
        StringBuilder sb = new StringBuilder("查詢範圍 ").append(range[0].format(DATE)).append(" ~ ").append(range[1].format(DATE))
            .append("（站點時區 ").append(dashboard.currentTimezone()).append("），最多 ").append(limit).append(" 條");
        List<String> conds = new ArrayList<>();
        for (String k : new String[] { "operName", "userName", "title", "businessType", "status", "ip" })
        {
            String v = text(input, k);
            if (v != null)
            {
                conds.add(k + "=" + v);
            }
        }
        if (!conds.isEmpty())
        {
            sb.append("，條件：").append(String.join("、", conds));
        }
        return sb.toString();
    }

    private String time(java.util.Date d)
    {
        if (d == null)
        {
            return "—";
        }
        return LocalDateTime.ofInstant(d.toInstant(), dashboard.currentZone()).format(SLOT);
    }

    private static String nvl(String s)
    {
        return StringUtils.isEmpty(s) ? "—" : s;
    }

    /** 没权限 */
    static final class Forbidden extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        Forbidden(String message)
        {
            super(message);
        }
    }
}
