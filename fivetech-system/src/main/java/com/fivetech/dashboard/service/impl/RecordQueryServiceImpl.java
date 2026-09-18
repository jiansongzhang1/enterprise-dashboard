package com.fivetech.dashboard.service.impl;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.ResolvedRange;
import com.fivetech.dashboard.domain.query.BaseRecordQuery;
import com.fivetech.dashboard.domain.query.BetRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.TransactionRecordQuery;
import com.fivetech.dashboard.domain.vo.BetRecordVO;
import com.fivetech.dashboard.domain.vo.ColumnMetaVO;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.domain.vo.PageResultVO;
import com.fivetech.dashboard.domain.vo.QueryContext;
import com.fivetech.dashboard.domain.vo.RecordResultVO;
import com.fivetech.dashboard.domain.vo.TransactionRecordVO;
import com.fivetech.dashboard.enums.MemberTimeField;
import com.fivetech.dashboard.gateway.DataFreshness;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.gateway.RecordPage;
import com.fivetech.dashboard.gateway.RecordPageRequest;
import com.fivetech.dashboard.gateway.ScopeFilter;
import com.fivetech.dashboard.service.DashboardScopeResolver;
import com.fivetech.dashboard.service.IRecordQueryService;
import com.fivetech.dashboard.service.RecordColumnRegistry;
import com.fivetech.dashboard.service.TimeRangeResolver;

/**
 * 明细查询实现。
 * <p>
 * 三张表共用一条链路：解析权限 → 解析时间 → 组装白名单内的筛选与排序 → 下推 → 组装响应。
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

    private final DashboardScopeResolver scopeResolver;

    private final MetricDataGateway gateway;

    public RecordQueryServiceImpl(DashboardProperties properties, TimeRangeResolver timeResolver,
            RecordColumnRegistry columnRegistry, DashboardScopeResolver scopeResolver, MetricDataGateway gateway)
    {
        this.properties = properties;
        this.timeResolver = timeResolver;
        this.columnRegistry = columnRegistry;
        this.scopeResolver = scopeResolver;
        this.gateway = gateway;
    }

    @Override
    public RecordResultVO<MemberRecordVO> queryMembers(MemberRecordQuery query)
    {
        List<ColumnMetaVO> columns = columnRegistry.memberColumns(query.getViewScheme());
        Context ctx = prepare(query, columns, "registerTime");

        // 时间字段由下钻来源决定：注册类指标按注册时间，首存类按首存时间，活跃类按最近活跃时间。
        // 选错字段，下钻行数就与指标值对不上。
        MemberTimeField timeField = query.getTimeField() == null
            ? MemberTimeField.REG_TIME : query.getTimeField();
        ctx.request.setTimeField(timeField.name());
        putIfPresent(ctx.request, "registerChannel", query.getRegisterChannel());
        putIfPresent(ctx.request, "device", query.getDevice());
        putIfPresent(ctx.request, "hasFirstDeposit", query.getHasFirstDeposit());
        putIfPresent(ctx.request, "tier", query.getTier());
        putIfPresent(ctx.request, "stage", query.getStage());

        RecordPage<MemberRecordVO> page = gateway.queryMemberRecords(ctx.request);
        return assemble(ctx, columns, page, null);
    }

    @Override
    public RecordResultVO<TransactionRecordVO> queryTransactions(TransactionRecordQuery query)
    {
        List<ColumnMetaVO> columns = columnRegistry.transactionColumns();
        Context ctx = prepare(query, columns, "createTime");

        // 时间口径未拍板：默认按创建时间，与原型一致。改用完成时间会让所有资金类
        // 指标的归属时间片整体位移，须与财务确认后再切换。
        ctx.request.setTimeField("FINISH".equalsIgnoreCase(query.getTimeField()) ? "FINISH_TIME" : "CREATE_TIME");
        putIfPresent(ctx.request, "type", query.getType() == null ? null : query.getType().name());
        putIfPresent(ctx.request, "status", query.getStatus());
        putIfPresent(ctx.request, "channel", query.getChannel());
        putIfPresent(ctx.request, "auditStatus", query.getAuditStatus());

        RecordPage<TransactionRecordVO> page = gateway.queryTransactionRecords(ctx.request);
        // 合计口径必须随合计一起返回，前端不得自行假设
        return assemble(ctx, columns, page, "仅计成功单");
    }

    @Override
    public RecordResultVO<BetRecordVO> queryBets(BetRecordQuery query)
    {
        List<ColumnMetaVO> columns = columnRegistry.betColumns();
        Context ctx = prepare(query, columns, "createTime");
        ctx.request.setTimeField("CREATE_TIME");
        putIfPresent(ctx.request, "vendor", query.getVendor());
        putIfPresent(ctx.request, "gameType", query.getGameType());
        putIfPresent(ctx.request, "game", query.getGame());

        RecordPage<BetRecordVO> page = gateway.queryBetRecords(ctx.request);
        return assemble(ctx, columns, page, null);
    }

    /**
     * 三张表共用的前置处理：权限 → 时间 → 白名单校验
     */
    private Context prepare(BaseRecordQuery query, List<ColumnMetaVO> columns, String defaultSort)
    {
        Context ctx = new Context();
        ctx.siteCode = StringUtils.isEmpty(query.getSiteCode())
            ? properties.getDefaultSite() : query.getSiteCode();
        ScopeFilter scope = scopeResolver.resolve();

        ctx.freshness = timeResolver.resolveFreshness(ctx.siteCode);
        ctx.warnings = new ArrayList<>();
        // 记录表允许「不限时间」
        ctx.range = timeResolver.resolveMain(query, ctx.freshness.getAsOf(), true);

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
        request.setScopeFilter(scope);
        ctx.request = request;
        return ctx;
    }

    private <T> RecordResultVO<T> assemble(Context ctx, List<ColumnMetaVO> columns,
            RecordPage<T> page, String summaryNote)
    {
        RecordResultVO<T> vo = new RecordResultVO<>();
        QueryContext context = timeResolver.buildContext(
            ctx.siteCode, ctx.range, null, ctx.freshness, ctx.warnings);
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
    }
}
