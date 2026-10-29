package com.fivetech.dashboard.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.SysSite;
import com.fivetech.dashboard.mapper.SysSiteMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 站点以库为准（2026-10-28）：站点存在 monitor_site_setting（site_name 不为空的行，与所属包网、规则号同一行），
 * 启动时加载到 {@link DashboardProperties}（默认站点 + 其余站点），
 * 登录的站点列表、X-Site-Code 校验、X-Uds-Site、判定引擎都从这里取。
 *
 * <ul>
 *   <li>首次启动还没有站点行：把 application.yml 里的 default-site / site-name / default-uds-site / sites 导入（已有设置行的补上站点字段），
 *       之后 yml 的这几项不再生效；</li>
 *   <li>站点表加载失败（没执行 ⑯ alter_monitor_site_setting_site / ⑱ alter_monitor_site_setting_currency、没有默认站点等）：
 *       记警告，继续用 yml 的站点配置，不影响启动；之后每次刷新周期、以及后台新增 / 修改站点时都会重试，
 *       执行完 SQL 不用重启；失败原因见 {@link #getLoadError()}；</li>
 *   <li>后台新增、修改站点后处理请求的实例当场 {@link #reload()}；其他实例每 {@code dashboard.config-refresh-seconds} 秒
 *       （默认 60，0 = 关闭）比对一次表的版本，变了就重载。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class SiteRegistry
{
    private static final Logger log = LoggerFactory.getLogger(SiteRegistry.class);

    private final SysSiteMapper mapper;

    private final DashboardProperties dashboard;

    private final int refreshSeconds;

    private volatile String version;

    private volatile boolean fromTable;

    /** 最近一次加载站点表失败的原因（加载成功后为 null），后台报错时带上 */
    private volatile String loadError;

    private ScheduledExecutorService executor;

    public SiteRegistry(SysSiteMapper mapper, DashboardProperties dashboard,
            @Value("${dashboard.config-refresh-seconds:60}") int refreshSeconds)
    {
        this.mapper = mapper;
        this.dashboard = dashboard;
        this.refreshSeconds = refreshSeconds;
    }

    @PostConstruct
    public void init()
    {
        if (!tryLoad())
        {
            log.warn("[site] 站点表 monitor_site_setting 加载失败，继续用 application.yml 的站点配置"
                + "（执行 sql/alter_monitor_site_setting_site_postgresql.sql、alter_monitor_site_setting_currency_postgresql.sql 后自动重试，不用重启）：{}",
                loadError);
        }
        // 加载失败也启动：之后按周期重试
        if (refreshSeconds > 0)
        {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "site-config-refresh");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleWithFixedDelay(this::check, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public void stop()
    {
        if (executor != null)
        {
            executor.shutdownNow();
        }
    }

    /** 站点是否已改为以库为准（表可用且已加载） */
    public boolean isFromTable()
    {
        return fromTable;
    }

    /** 最近一次加载失败的原因；已加载或还没试过为 null */
    public String getLoadError()
    {
        return loadError;
    }

    /**
     * 加载站点表（还没有站点行时先从 yml 导入）；失败不抛错，记下原因并沿用当前配置。
     *
     * @return 是否已从站点表加载
     */
    public synchronized boolean tryLoad()
    {
        if (fromTable)
        {
            return true;
        }
        try
        {
            if (mapper.countAll() == 0)
            {
                seedFromConfig();
            }
            reload();
            loadError = null;
            return true;
        }
        catch (RuntimeException e)
        {
            loadError = rootMessage(e);
            return false;
        }
    }

    /**
     * 首次启动：把 yml 里的站点写进 monitor_site_setting（默认站点 sort_no = 0，其余按配置顺序）。
     * 时区、币种写明确的值（站点没配的用 dashboard.timezone / dashboard.currency），之后以站点表为准。
     */
    void seedFromConfig()
    {
        SysSite def = new SysSite();
        def.setSiteCode(dashboard.getDefaultSite());
        def.setSiteName(dashboard.getSiteName());
        def.setUdsSite(blankToNull(dashboard.getDefaultUdsSite()));
        def.setTimezone(blankToNull(dashboard.getTimezone()));
        def.setCurrency(blankToNull(dashboard.getCurrency()));
        def.setDefaultSite(true);
        def.setSortNo(0);
        def.setUpdateBy("system");
        mapper.insert(def);
        int i = 1;
        for (DashboardProperties.Site s : dashboard.getSites())
        {
            if (s.getCode() == null || s.getCode().trim().isEmpty() || s.getCode().trim().equalsIgnoreCase(def.getSiteCode()))
            {
                continue;
            }
            SysSite row = new SysSite();
            row.setSiteCode(s.getCode().trim());
            row.setSiteName(s.getName() == null || s.getName().trim().isEmpty() ? s.getCode().trim() : s.getName().trim());
            row.setUdsSite(blankToNull(s.getUdsSite()));
            row.setTimezone(firstNonBlank(s.getTimezone(), dashboard.getTimezone()));
            row.setCurrency(firstNonBlank(s.getCurrency(), dashboard.getCurrency()));
            row.setCurrencySymbol(blankToNull(s.getCurrencySymbol()));
            row.setSortNo(i++);
            row.setUpdateBy("system");
            mapper.insert(row);
        }
        log.info("[site] 还没有站点，已从 application.yml 导入 {} 个站点", i);
    }

    /** 从 monitor_site_setting 重新加载；没有默认站点时抛错并保留原配置 */
    public synchronized void reload()
    {
        // 已按创建先后排好：排序号 sort_no 从小到大（新增站点取最大值 + 1），相同时按编码
        List<SysSite> rows = mapper.selectAll();
        SysSite def = null;
        List<DashboardProperties.Site> others = new ArrayList<>();
        List<String> order = new ArrayList<>();
        for (SysSite r : rows)
        {
            order.add(r.getSiteCode());
            if (r.isDefaultSite() && def == null)
            {
                def = r;
                continue;
            }
            DashboardProperties.Site s = new DashboardProperties.Site();
            s.setCode(r.getSiteCode());
            s.setName(r.getSiteName());
            s.setUdsSite(r.getUdsSite());
            s.setTimezone(r.getTimezone());
            s.setCurrency(r.getCurrency());
            s.setCurrencySymbol(r.getCurrencySymbol());
            others.add(s);
        }
        if (def == null)
        {
            throw new IllegalStateException("monitor_site_setting 里没有默认站点（is_default = true）");
        }
        dashboard.applySites(def.getSiteCode(), def.getSiteName(), def.getUdsSite(), def.getTimezone(), def.getCurrency(),
            def.getCurrencySymbol(), others, order);
        version = mapper.selectVersion();
        fromTable = true;
        log.info("[site] 已加载 {} 个站点，默认站点 {}", rows.size(), def.getSiteCode());
    }

    /** 版本变了就重载；包内可见供测试 */
    void check()
    {
        if (!fromTable)
        {
            String before = loadError;
            if (tryLoad())
            {
                log.info("[site] 站点表已可用，改为以库为准");
            }
            else if (!java.util.Objects.equals(before, loadError))
            {
                log.warn("[site] 站点表仍加载失败，继续用 application.yml 的站点配置：{}", loadError);
            }
            return;
        }
        try
        {
            String now = mapper.selectVersion();
            if (now != null && !now.equals(version))
            {
                reload();
            }
        }
        catch (RuntimeException e)
        {
            log.warn("[site] 站点有变更但重新加载失败，继续用旧配置：{}", e.getMessage());
        }
    }

    /** 最底层的异常信息（如 PSQLException 的「column "currency" does not exist」），不带 SQL 和堆栈 */
    static String rootMessage(Throwable e)
    {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t)
        {
            t = t.getCause();
        }
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage().trim();
        int nl = msg.indexOf('\n');
        if (nl > 0)
        {
            msg = msg.substring(0, nl).trim();
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }

    private static String firstNonBlank(String a, String b)
    {
        String v = blankToNull(a);
        return v != null ? v : blankToNull(b);
    }

    private static String blankToNull(String s)
    {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
