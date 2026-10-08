package com.fivetech.dashboard.service.impl;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.ResolvedRange;
import com.fivetech.dashboard.domain.query.BaseRecordQuery;
import com.fivetech.dashboard.domain.query.BetRecordQuery;
import com.fivetech.dashboard.domain.query.DepositRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.WithdrawRecordQuery;
import com.fivetech.dashboard.domain.vo.BetRecordVO;
import com.fivetech.dashboard.domain.vo.ColumnMetaVO;
import com.fivetech.dashboard.domain.vo.DepositRecordVO;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.domain.vo.PageResultVO;
import com.fivetech.dashboard.domain.vo.QueryContext;
import com.fivetech.dashboard.domain.vo.RecordResultVO;
import com.fivetech.dashboard.domain.vo.WithdrawRecordVO;
import com.fivetech.dashboard.gateway.DataFreshness;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.gateway.RecordPage;
import com.fivetech.dashboard.gateway.RecordPageRequest;
import com.fivetech.dashboard.service.IRecordQueryService;
import com.fivetech.dashboard.service.RecordColumnRegistry;
import com.fivetech.dashboard.service.TimeRangeResolver;

/**
 * 明细查询实现。
 * <p>
 * 四张表共用一条链路：解析权限 → 解析时间 → 组装白名单内的筛选与排序 → 下推 → 组装响应。
 * 差异只在时间字段与筛选项。
 *
 * @author fivetech
 */
@Service
public class RecordQueryServiceImpl implements IRecordQueryService
{
    private final DashboardProperties properties;

    private final TimeRangeResolver timeResolver;

    private final RecordColumnRegistry columnRegistry;

    private final MetricDataGateway gateway;

    public RecordQueryServiceImpl(DashboardProperties properties, TimeRangeResolver timeResolver,
            RecordColumnRegistry columnRegistry, MetricDataGateway gateway)
    {
        this.properties = properties;
        this.timeResolver = timeResolver;
        this.columnRegistry = columnRegistry;
        this.gateway = gateway;
    }

    @Override
    public RecordResultVO<MemberRecordVO> queryMembers(MemberRecordQuery query)
    {
        Context ctx = memberContext(query);
        RecordPage<MemberRecordVO> page = gateway.queryMemberRecords(ctx.request);
        return assemble(ctx, ctx.columns, page, null);
    }

    @Override
    public RecordPageRequest buildMemberRequest(MemberRecordQuery query)
    {
        return memberContext(query).request;
    }

    /**
     * 会员明细（对齐原型 MVP-V1.0）。
     * <p>
     * 和存款、提款、投注表不同，会员表没有统一的时间范围：注册时间、首存时间、最近投注时间
     * 是三个独立的区间条件，可以叠加，都不传就是全量会员（累计口径本来就看全量）。
     */
    private Context memberContext(MemberRecordQuery query)
    {
        List<ColumnMetaVO> columns = columnRegistry.memberColumns();
        Context ctx = prepare(query, columns, "registerTime", false);
        RecordPageRequest request = ctx.request;

        String regFrom = query.getRegisterTimeFrom();
        String regTo = query.getRegisterTimeTo();
        String ftdFrom = query.getFirstDepositTimeFrom();
        String ftdTo = query.getFirstDepositTimeTo();
        String betFrom = query.getLastBetTimeFrom();
        String betTo = query.getLastBetTimeTo();

        // 下钻调用：只给了 slotFrom/slotTo 时，按来源指标写进对应的时间条件。
        // 只有两类指标能在会员表里「行数 = 指标值」：注册类 → 注册时间，首存类 → 首存时间。
        // 活跃人数不能用「最近投注时间」——那只覆盖之后再没投过注的人，历史时间片会严重偏少，
        // 应下钻到投注明细；登录人数会员表没有登录时间字段，不支持下钻。其余指标一律拒绝，
        // 不再「默认按注册时间」——默认值会让错误的下钻静默返回一张看似正常的表
        boolean noTime = StringUtils.isEmpty(regFrom) && StringUtils.isEmpty(regTo)
            && StringUtils.isEmpty(ftdFrom) && StringUtils.isEmpty(ftdTo)
            && StringUtils.isEmpty(betFrom) && StringUtils.isEmpty(betTo);
        if (noTime && StringUtils.isNotEmpty(query.getSlotFrom()) && StringUtils.isNotEmpty(query.getSlotTo()))
        {
            String source = query.getSourceMetricCode();
            if ("ftd".equals(source) || "ftdA".equals(source))
            {
                ftdFrom = query.getSlotFrom();
                ftdTo = query.getSlotTo();
            }
            else if (StringUtils.isEmpty(source) || "reg".equals(source))
            {
                regFrom = query.getSlotFrom();
                regTo = query.getSlotTo();
            }
            else if ("active".equals(source))
            {
                throw new com.fivetech.common.exception.ServiceException(
                    "活跃人数请下钻到投注明细（/dashboard/records/bet，按投注时间筛选）");
            }
            else
            {
                throw new com.fivetech.common.exception.ServiceException(
                    "指标 " + source + " 不支持下钻到会员明细");
            }
        }
        addTimeRange(request, "registerTime", "注册时间", regFrom, regTo);
        addTimeRange(request, "firstDepositTime", "首存时间", ftdFrom, ftdTo);
        addTimeRange(request, "lastBetTime", "最近投注时间", betFrom, betTo);

        putIfPresent(request, "status", query.getStatus());
        putIfPresent(request, "userType", query.getUserType());
        putIfPresent(request, "level", query.getLevel());
        putIfPresent(request, "country", query.getCountry());

        java.math.BigDecimal depMin = query.getCumulativeDepositMin();
        java.math.BigDecimal depMax = query.getCumulativeDepositMax();
        if (depMin != null && depMax != null && depMin.compareTo(depMax) > 0)
        {
            throw new com.fivetech.common.exception.ServiceException("历史累计存款金额的下限不能大于上限");
        }
        // 金额上下限都包含（与页面「0 ~ 50000」的读法一致）
        request.addRange("cumulativeDepositAmount", depMin, depMax, true);
        return ctx;
    }

    /** 时间区间条件，左闭右开；结束不晚于开始时直接报错，不返回一张必然为空的表 */
    private void addTimeRange(RecordPageRequest request, String field, String label, String from, String to)
    {
        java.time.LocalDateTime start = StringUtils.isEmpty(from) ? null : timeResolver.parseDateTime(from, field + "From");
        java.time.LocalDateTime end = StringUtils.isEmpty(to) ? null : timeResolver.parseDateTime(to, field + "To");
        if (start != null && end != null && !end.isAfter(start))
        {
            throw new com.fivetech.common.exception.ServiceException(label + "的结束时间必须晚于开始时间（左闭右开）");
        }
        request.addRange(field, start, end, false);
    }

    @Override
    public RecordResultVO<DepositRecordVO> queryDeposits(DepositRecordQuery query)
    {
        Context ctx = depositContext(query);
        RecordPage<DepositRecordVO> page = gateway.queryDepositRecords(ctx.request);
        return assembleOrders(ctx, page);
    }

    @Override
    public RecordPageRequest buildDepositRequest(DepositRecordQuery query)
    {
        return depositContext(query).request;
    }

    /** 存款明细：时间范围按创建时间 */
    private Context depositContext(DepositRecordQuery query)
    {
        Context ctx = prepare(query, columnRegistry.depositColumns(), "createTime", true);
        ctx.request.setTimeField("CREATE_TIME");
        applyDrillTimeField(ctx, query, properties.getDepositDrillTimeField());
        putIfPresent(ctx.request, "status", query.getStatus());
        return ctx;
    }

    @Override
    public RecordResultVO<WithdrawRecordVO> queryWithdrawals(WithdrawRecordQuery query)
    {
        Context ctx = withdrawContext(query);
        RecordPage<WithdrawRecordVO> page = gateway.queryWithdrawRecords(ctx.request);
        return assembleOrders(ctx, page);
    }

    @Override
    public RecordPageRequest buildWithdrawRequest(WithdrawRecordQuery query)
    {
        return withdrawContext(query).request;
    }

    /** 提款明细：时间范围按创建时间 */
    private Context withdrawContext(WithdrawRecordQuery query)
    {
        Context ctx = prepare(query, columnRegistry.withdrawColumns(), "createTime", true);
        ctx.request.setTimeField("CREATE_TIME");
        applyDrillTimeField(ctx, query, properties.getWithdrawDrillTimeField());
        putIfPresent(ctx.request, "status", query.getStatus());
        return ctx;
    }

    @Override
    public RecordResultVO<BetRecordVO> queryBets(BetRecordQuery query)
    {
        Context ctx = betContext(query);
        RecordPage<BetRecordVO> page = gateway.queryBetRecords(ctx.request);
        return assembleOrders(ctx, page);
    }

    @Override
    public RecordPageRequest buildBetRequest(BetRecordQuery query)
    {
        return betContext(query).request;
    }

    /** 投注明细：时间范围按投注时间 */
    private Context betContext(BetRecordQuery query)
    {
        Context ctx = prepare(query, columnRegistry.betColumns(), "betTime", true);
        ctx.request.setTimeField("BET_TIME");
        putIfPresent(ctx.request, "vendorCode", query.getVendorCode());
        putIfPresent(ctx.request, "gameType", query.getGameType());
        // 游戏名称或游戏ID，模糊匹配；去掉首尾空白，避免「 Super Ace 」查不到
        putIfPresent(ctx.request, "game", query.getGame() == null ? null : query.getGame().trim());
        putIfPresent(ctx.request, "settleStatus", query.getSettleStatus());

        java.math.BigDecimal min = query.getBetAmountMin();
        java.math.BigDecimal max = query.getBetAmountMax();
        if (min != null && max != null && min.compareTo(max) > 0)
        {
            throw new com.fivetech.common.exception.ServiceException("投注金额的下限不能大于上限");
        }
        // 金额区间两端都包含
        ctx.request.addRange("betAmount", min, max, true);
        return ctx;
    }

    /**
     * 从指标下钻（带 slotFrom / slotTo）到订单表时，按配置的时间口径筛选。
     * <p>默认按创建时间，与普通查询一致，什么都不用改。配成 finishTime 时，
     * 把主区间从「创建时间」挪到「完成时间」的区间条件上（左闭右开），
     * 这样下钻金额与按完成时间归属的指标值一致。非下钻的普通查询不受影响。</p>
     */
    private void applyDrillTimeField(Context ctx, BaseRecordQuery query, String timeField)
    {
        boolean drill = StringUtils.isNotEmpty(query.getSlotFrom()) && StringUtils.isNotEmpty(query.getSlotTo());
        if (!drill || !"finishTime".equals(timeField) || ctx.range.isUnbounded())
        {
            return;
        }
        ctx.request.setTimeField("FINISH_TIME");
        ctx.request.setFrom(null);
        ctx.request.setTo(null);
        ctx.request.addRange("finishTime", ctx.range.getFrom(), ctx.range.getTo(), false);
    }

    /**
     * 订单表（存款 / 提款 / 投注）没有合计行：同一批订单混着 INR / USD / USDT，
     * 加总没有意义。summary 固定为空对象、summaryNote 为 null，不透传上游可能带回的合计。
     */
    private <T> RecordResultVO<T> assembleOrders(Context ctx, RecordPage<T> page)
    {
        RecordResultVO<T> vo = assemble(ctx, ctx.columns, page, null);
        vo.setSummary(new java.util.LinkedHashMap<>());
        return vo;
    }

    /**
     * 四张表共用的前置处理：时间解析 → 白名单校验
     * <p>
     * 一期不做行/列维度数据权限，访问控制完全由控制器的 @PreAuthorize 承担。
     */
    private Context prepare(BaseRecordQuery query, List<ColumnMetaVO> columns, String defaultSort,
            boolean useMainRange)
    {
        Context ctx = new Context();
        ctx.columns = columns;
        ctx.siteCode = StringUtils.isEmpty(query.getSiteCode())
            ? properties.getDefaultSite() : query.getSiteCode();
        ctx.freshness = timeResolver.resolveFreshness(ctx.siteCode);
        ctx.warnings = new ArrayList<>();
        // 会员表不走统一时间范围（见 memberContext），订单表按 from/to（或 slotFrom/slotTo）解析。
        // 订单表一个时间参数都不传时不限业务日期：快照数据集 time 固定 now~now 即「返回全部数据」，
        // 不再默认「今天」——默认今天会按水位截到某一天，列表只剩零星几行
        boolean noTime = StringUtils.isEmpty(query.getFrom()) && StringUtils.isEmpty(query.getTo())
            && StringUtils.isEmpty(query.getSlotFrom()) && StringUtils.isEmpty(query.getSlotTo());
        ctx.range = useMainRange && !noTime
            ? timeResolver.resolveMain(query, ctx.freshness.getAsOf())
            : ResolvedRange.unbounded();

        RecordPageRequest request = new RecordPageRequest();
        request.setSiteCode(ctx.siteCode);
        request.setFrom(ctx.range.isUnbounded() ? null : ctx.range.getFrom());
        request.setTo(ctx.range.isUnbounded() ? null : ctx.range.getTo());
        request.setKeyword(query.getKeyword());
        // 排序列与方向都过白名单，非法值回退默认，绝不拼进查询
        request.setSortColumn(columnRegistry.resolveSortColumn(columns, query.getSortColumn(), defaultSort));
        request.setSortDirection(columnRegistry.resolveSortDirection(query.getSortDirection()));
        request.setPageNum(query.getPageNum() == null ? 1 : query.getPageNum());
        request.setPageSize(query.getPageSize() == null ? 20 : query.getPageSize());
        request.setRowLimit(properties.getRowLimit());
        ctx.request = request;
        return ctx;
    }

    private <T> RecordResultVO<T> assemble(Context ctx, List<ColumnMetaVO> columns,
            RecordPage<T> page, String summaryNote)
    {
        RecordResultVO<T> vo = new RecordResultVO<>();
        QueryContext context = timeResolver.buildContext(
            ctx.siteCode, ctx.range, ctx.freshness, ctx.warnings);
        // 明细快照按小时更新
        context.setUpdateFrequency("hour");
        vo.setContext(context);
        vo.setColumns(columns);

        PageResultVO<T> result = PageResultVO.of(page.getRows(), page.getTotal(),
            ctx.request.getPageNum(), ctx.request.getPageSize());
        result.setTruncated(page.isTruncated());
        result.setRowLimit(properties.getRowLimit());
        vo.setPage(result);
        vo.setSummary(page.getSummary());
        vo.setSummaryNote(summaryNote);
        return vo;
    }

    /**
     * 只有真正传了值的筛选项才下推，避免把空串当成「等于空」
     */
    private void putIfPresent(RecordPageRequest request, String key, Object value)
    {
        if (value == null)
        {
            return;
        }
        if (value instanceof String && StringUtils.isEmpty((String) value))
        {
            return;
        }
        request.getFilters().put(key, value);
    }

    /** 前置处理的中间产物 */
    private static class Context
    {
        private String siteCode;

        private DataFreshness freshness;

        private ResolvedRange range;

        private List<String> warnings;

        private RecordPageRequest request;

        private List<ColumnMetaVO> columns;
    }
}
