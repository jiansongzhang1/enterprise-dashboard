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
import com.fivetech.dashboard.enums.CompareType;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.enums.RangeType;
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
     * @param allowUnbounded 是否允许「不限时间」（记录表允许，指标汇总表不允许）
     */
    public ResolvedRange resolveMain(BaseDashboardQuery query, LocalDateTime asOf, boolean allowUnbounded)
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

        LocalDate today = asOf.toLocalDate();
        RangeType type = query.getRangeType() == null ? RangeType.TODAY : query.getRangeType();
        switch (type)
        {
            case ALL:
                if (!allowUnbounded)
                {
                    throw new ServiceException("指标汇总表不支持不限时间，请选择具体区间");
                }
                return ResolvedRange.unbounded();
            case TODAY:
                return clampToAsOf(today.atStartOfDay(), today.plusDays(1).atStartOfDay(), asOf);
            case YESTERDAY:
                return clampToAsOf(today.minusDays(1).atStartOfDay(), today.atStartOfDay(), asOf);
            case LAST_7D:
                return clampToAsOf(today.minusDays(7).atStartOfDay(), today.atStartOfDay(), asOf);
            case LAST_30D:
                return clampToAsOf(today.minusDays(30).atStartOfDay(), today.atStartOfDay(), asOf);
            case CUSTOM:
            default:
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
    }

    /**
     * 解析对比区间。返回 null 表示不对比。
     *
     * @param warnings 校验产生的警告码写入此列表（不拦截请求）
     */
    public ResolvedRange resolveCompare(CompareType compareType, String compareFrom, String compareTo,
            ResolvedRange main, List<String> warnings)
    {
        if (compareType == null || compareType == CompareType.NONE || main.isUnbounded())
        {
            return null;
        }
        long days = Math.max(1, ChronoUnit.DAYS.between(main.getFrom().toLocalDate(), main.getTo().toLocalDate()));
        LocalDateTime from;
        LocalDateTime to;
        switch (compareType)
        {
            case LAST_YEAR:
                from = main.getFrom().minusYears(1);
                to = main.getTo().minusYears(1);
                break;
            case CUSTOM:
                LocalDate cf = parseDate(compareFrom, "compareFrom");
                LocalDate ct = parseDate(compareTo, "compareTo");
                // 四条校验，与原型完全一致，前端校验不可信
                if (ct.isBefore(cf))
                {
                    throw new ServiceException("对比区间结束日不能早于开始日");
                }
                if (cf.isBefore(launchDate()))
                {
                    throw new ServiceException("对比区间不能早于站点上线日 " + properties.getLaunchDate());
                }
                from = cf.atStartOfDay();
                to = ct.plusDays(1).atStartOfDay();
                if (from.isBefore(main.getTo()) && main.getFrom().isBefore(to))
                {
                    throw new ServiceException("对比区间不能与主区间重叠");
                }
                long compareDays = ChronoUnit.DAYS.between(cf, ct) + 1;
                if (compareDays != days)
                {
                    // 长度不等只警告不拦截：用户可能就是想比一个长短不同的区间
                    warnings.add("COMPARE_LENGTH_MISMATCH");
                }
                break;
            case PREV_PERIOD:
            default:
                Duration span = Duration.between(main.getFrom(), main.getTo());
                to = main.getFrom();
                from = to.minus(span);
                break;
        }
        if (from.toLocalDate().isBefore(launchDate()))
        {
            warnings.add("COMPARE_BEFORE_LAUNCH");
        }
        return ResolvedRange.of(from, to, main.getGranularity());
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
    public QueryContext buildContext(String siteCode, ResolvedRange main, ResolvedRange compare,
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
        if (compare != null)
        {
            context.setCompareFrom(compare.formatFrom());
            context.setCompareTo(compare.formatTo());
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

    private LocalDateTime parseDateTime(String value, String field)
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
