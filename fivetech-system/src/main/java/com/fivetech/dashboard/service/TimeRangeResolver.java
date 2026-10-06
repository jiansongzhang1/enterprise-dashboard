package com.fivetech.dashboard.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.ResolvedRange;
import com.fivetech.dashboard.domain.query.BaseDashboardQuery;
import com.fivetech.dashboard.domain.vo.QueryContext;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.gateway.DataFreshness;
import com.fivetech.dashboard.gateway.MetricDataGateway;

/**
 * 时间语义解析器：本模块的地基。
 * <p>
 * 三条规则贯穿实现：
 * <ol>
 *   <li><b>统计区间截到 asOf</b>。数据源是小时级，14:00 这一行要等 15:00 才落库，
 *       所以 14:33 发起的「今日」请求返回的是 00:00–14:00，不造分钟级假精度。</li>
 *   <li><b>统计区间与数据新鲜度是两件事</b>。asOf 是数据截止，updatedAt 是计算完成，
 *       混用就会出现「区间写 14:03、顶栏写 19:33」这种自相矛盾。</li>
 *   <li><b>对比期四条校验一律在服务端做</b>。前端校验只是体验，绕过前端直接调接口
 *       同样必须被拦住。</li>
 * </ol>
 *
 * @author fivetech
 */
@Component
public class TimeRangeResolver
{
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DashboardProperties properties;

    private final MetricDataGateway gateway;

    public TimeRangeResolver(DashboardProperties properties, MetricDataGateway gateway)
    {
        this.properties = properties;
        this.gateway = gateway;
    }

    /**
     * 取数据新鲜度。上游未提供水位线时，退化为「上一个完整整点」，
     * 并在上下文中如实标记，不假装精确。
     */
    public DataFreshness resolveFreshness(String siteCode)
    {
        DataFreshness freshness = gateway.getFreshness(siteCode);
        if (freshness != null && freshness.getAsOf() != null)
        {
            return freshness;
        }
        LocalDateTime now = LocalDateTime.now(zone());
        LocalDateTime asOf = now.truncatedTo(ChronoUnit.HOURS);
        return DataFreshness.of(asOf, asOf);
    }

    /**
     * 解析主区间。
     *
     * @param query 查询入参
     * @param asOf 数据截止时间
     */
    public ResolvedRange resolveMain(BaseDashboardQuery query, LocalDateTime asOf)
    {
        // 下钻时间片优先级最高：从指标汇总表某一行点进来时，条件就是那一格
        if (StringUtils.isNotEmpty(query.getSlotFrom()) && StringUtils.isNotEmpty(query.getSlotTo()))
        {
            LocalDateTime from = parseDateTime(query.getSlotFrom(), "slotFrom");
            LocalDateTime to = parseDateTime(query.getSlotTo(), "slotTo");
            if (!to.isAfter(from))
            {
                throw new ServiceException("下钻时间片结束不能早于开始");
            }
            return ResolvedRange.of(from, to, Granularity.HOUR);
        }

        boolean noFrom = StringUtils.isEmpty(query.getFrom());
        boolean noTo = StringUtils.isEmpty(query.getTo());
        if (noFrom && noTo)
        {
            // 不传区间时默认「今天」，并截到数据截止时间
            LocalDate today = asOf.toLocalDate();
            return clampToAsOf(today.atStartOfDay(), today.plusDays(1).atStartOfDay(), asOf);
        }
        if (noFrom || noTo)
        {
            throw new ServiceException("from 与 to 需同时传入");
        }
        LocalDate from = parseDate(query.getFrom(), "from");
        LocalDate to = parseDate(query.getTo(), "to");
        if (to.isBefore(from))
        {
            throw new ServiceException("结束日不能早于开始日");
        }
        if (from.isBefore(launchDate()))
        {
            throw new ServiceException("开始日不能早于站点上线日 " + properties.getLaunchDate());
        }
        return clampToAsOf(from.atStartOfDay(), to.plusDays(1).atStartOfDay(), asOf);
    }

    /**
     * 计算当前区间可用的粒度。前端只做渲染，不自行判断。
     */
    public List<Granularity> availableGranularities(ResolvedRange range)
    {
        List<Granularity> list = new ArrayList<>();
        if (range.isUnbounded())
        {
            return list;
        }
        long hours = ChronoUnit.HOURS.between(range.getFrom(), range.getTo());
        long days = ChronoUnit.DAYS.between(range.getFrom().toLocalDate(), range.getTo().toLocalDate());
        if (hours >= 1 && hours <= properties.getMaxPointsPerGranularity())
        {
            list.add(Granularity.HOUR);
        }
        if (days >= 1)
        {
            list.add(Granularity.DAY);
        }
        if (days >= 14)
        {
            list.add(Granularity.WEEK);
        }
        if (list.isEmpty())
        {
            list.add(Granularity.DAY);
        }
        return list;
    }

    /**
     * 决定生效粒度：请求的粒度不可用时自动降级，而不是报错或返回超大数组
     */
    public Granularity resolveGranularity(Granularity requested, List<Granularity> available)
    {
        if (requested != null && available.contains(requested))
        {
            return requested;
        }
        return available.isEmpty() ? Granularity.DAY : available.get(0);
    }

    /**
     * 组装查询上下文
     */
    public QueryContext buildContext(String siteCode, ResolvedRange main,
            DataFreshness freshness, List<String> warnings)
    {
        QueryContext context = new QueryContext();
        context.setSiteCode(siteCode);
        context.setTimezone(properties.getTimezone());
        context.setCurrency(properties.getCurrency());
        context.setRegistryVersion(properties.getRegistryVersion());
        if (!main.isUnbounded())
        {
            context.setSpanFrom(main.formatFrom());
            context.setSpanTo(main.formatTo());
            context.setGranularity(main.getGranularity());
            context.setAvailableGranularities(availableGranularities(main));
        }
        if (freshness != null)
        {
            context.setAsOf(format(freshness.getAsOf()));
            context.setUpdatedAt(format(freshness.getUpdatedAt()));
            // 区间尾部落在延迟窗口内时标记，前端据此展示提示条
            if (!main.isUnbounded() && freshness.getAsOf() != null)
            {
                LocalDateTime delayFrom = freshness.getAsOf().minusHours(properties.getDelayWindowHours());
                if (main.getTo().isAfter(delayFrom))
                {
                    context.setDelayed(true);
                    context.setDelayWindowHours(properties.getDelayWindowHours());
                }
            }
        }
        warnings.forEach(context::addWarning);
        return context;
    }

    /**
     * 区间末端截到 asOf：不返回尚未落库的时间片
     */
    private ResolvedRange clampToAsOf(LocalDateTime from, LocalDateTime to, LocalDateTime asOf)
    {
        LocalDateTime end = (asOf != null && asOf.isBefore(to)) ? asOf : to;
        if (!end.isAfter(from))
        {
            // 区间完全落在未来，返回一个空区间而不是报错
            end = from;
        }
        return ResolvedRange.of(from, end, null);
    }

    private LocalDate launchDate()
    {
        return LocalDate.parse(properties.getLaunchDate(), DATE);
    }

    private ZoneId zone()
    {
        return ZoneId.of(properties.getTimezone());
    }

    private LocalDate parseDate(String value, String field)
    {
        if (StringUtils.isEmpty(value))
        {
            throw new ServiceException("缺少参数 " + field);
        }
        try
        {
            return LocalDate.parse(value.trim(), DATE);
        }
        catch (Exception e)
        {
            throw new ServiceException("参数 " + field + " 格式应为 yyyy-MM-dd");
        }
    }

    /**
     * 解析 yyyy-MM-dd HH:mm（也接受 yyyy-MM-dd，按当天 00:00）。格式不对抛 ServiceException，提示带参数名
     */
    public LocalDateTime parseDateTime(String value, String field)
    {
        try
        {
            String text = value.trim();
            if (text.length() == 10)
            {
                return LocalDate.parse(text, DATE).atStartOfDay();
            }
            if (text.length() == 16)
            {
                return LocalDateTime.parse(text, DATETIME);
            }
            return LocalDateTime.parse(text.substring(0, 16), DATETIME);
        }
        catch (Exception e)
        {
            throw new ServiceException("参数 " + field + " 格式应为 yyyy-MM-dd HH:mm");
        }
    }

    private String format(LocalDateTime value)
    {
        return value == null ? null : value.format(DATETIME);
    }

    /**
     * 供外部拼接时间片标签使用
     */
    public String formatSlot(LocalDateTime value)
    {
        return format(value);
    }

    public LocalTime dayStart()
    {
        return LocalTime.MIDNIGHT;
    }
}
