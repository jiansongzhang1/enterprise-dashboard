package com.fivetech.dashboard.gateway.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.domain.vo.BetRecordVO;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.domain.vo.TransactionRecordVO;
import com.fivetech.dashboard.gateway.DataFreshness;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.gateway.MetricSlotRequest;
import com.fivetech.dashboard.gateway.RecordPage;
import com.fivetech.dashboard.gateway.RecordPageRequest;

/**
 * 外部数据平台网关的占位实现。
 * <p>
 * <b>TODO：接入 Doris 数据平台后，新建一个实现类替换本类。</b>
 * 本类不做任何查询，一律返回空值，让上层的时间语义、权限注入、分页与列定义
 * 可以先行联调，不被数据平台的进度卡住。
 * <p>
 * 切换方式：真正的实现类加上 {@code @ConditionalOnProperty(prefix = "dashboard.gateway",
 * name = "type", havingValue = "doris")}，再把配置项 {@code dashboard.gateway.type}
 * 从 stub 改成 doris 即可，调用方无需改动。
 *
 * @author fivetech
 */
@Component
@ConditionalOnProperty(prefix = "dashboard.gateway", name = "type", havingValue = "stub", matchIfMissing = true)
public class StubMetricDataGateway implements MetricDataGateway
{
    private static final Logger log = LoggerFactory.getLogger(StubMetricDataGateway.class);

    @Override
    public DataFreshness getFreshness(String siteCode)
    {
        // TODO 替换为数据平台的水位线接口。在此之前返回 null，
        //      由上层退化为「当前整点」推测，并在响应里如实标记。
        log.debug("[stub] getFreshness site={}", siteCode);
        return null;
    }

    @Override
    public Map<String, List<BigDecimal>> querySeries(MetricSlotRequest request)
    {
        // TODO 按 metric_source_mapping 把指标编码翻译成外部表/列，
        //      按表分组合并为少数几条查询，注入 ScopeFilter 后下推。
        log.debug("[stub] querySeries site={} from={} to={} gran={} metrics={}",
            request.getSiteCode(), request.getFrom(), request.getTo(),
            request.getGranularity(), request.getMetricCodes());
        int points = countPoints(request);
        Map<String, List<BigDecimal>> result = new LinkedHashMap<>();
        for (String code : request.getMetricCodes())
        {
            // 长度必须与时间片数一致，值为 null 表示无数据
            result.put(code, java.util.Collections.nCopies(points, (BigDecimal) null));
        }
        return result;
    }

    @Override
    public Map<String, BigDecimal> queryTotals(MetricSlotRequest request)
    {
        // TODO 区间合计必须由数据侧重新聚合，不能把 querySeries 的结果加总。
        log.debug("[stub] queryTotals site={} metrics={}", request.getSiteCode(), request.getMetricCodes());
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (String code : request.getMetricCodes())
        {
            result.put(code, null);
        }
        return result;
    }

    @Override
    public RecordPage<MemberRecordVO> queryMemberRecords(RecordPageRequest request)
    {
        // TODO 对接会员明细表。注意 timeField 决定按注册/首存/活跃哪个时间筛选。
        log.debug("[stub] queryMemberRecords timeField={} filters={}",
            request.getTimeField(), request.getFilters());
        return RecordPage.empty();
    }

    @Override
    public RecordPage<TransactionRecordVO> queryTransactionRecords(RecordPageRequest request)
    {
        // TODO 对接交易明细表（存提合并，type 区分方向）。
        //      合计口径需与 summaryNote 保持一致：仅计成功单。
        log.debug("[stub] queryTransactionRecords filters={}", request.getFilters());
        return RecordPage.empty();
    }

    @Override
    public RecordPage<BetRecordVO> queryBetRecords(RecordPageRequest request)
    {
        // TODO 对接投注明细表。
        log.debug("[stub] queryBetRecords filters={}", request.getFilters());
        return RecordPage.empty();
    }

    /**
     * 按区间与粒度算出时间片数，保证返回的序列长度与上层预期一致
     */
    private int countPoints(MetricSlotRequest request)
    {
        LocalDateTime from = request.getFrom();
        LocalDateTime to = request.getTo();
        if (from == null || to == null || request.getGranularity() == null)
        {
            return 0;
        }
        switch (request.getGranularity())
        {
            case HOUR:
                return (int) Math.max(0, ChronoUnit.HOURS.between(from, to));
            case WEEK:
                return (int) Math.max(0, Math.ceil(ChronoUnit.DAYS.between(from, to) / 7.0));
            case DAY:
            default:
                return (int) Math.max(0, ChronoUnit.DAYS.between(from, to));
        }
    }
}
