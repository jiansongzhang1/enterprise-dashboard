package com.fivetech.dashboard.gateway;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.fivetech.dashboard.domain.vo.BetRecordVO;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.domain.vo.TransactionRecordVO;

/**
 * 外部数据平台网关。
 * <p>
 * 本系统<b>不建设数仓、不做 ETL、不落业务明细</b>，所有业务数值实时向外部取。
 * 这个接口就是那条边界：上面是本系统的语义层（口径、时间、权限、编排），
 * 下面是数据平台。
 * <p>
 * 实现方需遵守：
 * <ol>
 *   <li>必须把 {@link ScopeFilter} 的条件下推，不得忽略或放宽；</li>
 *   <li>指标编码到表/列的映射在实现内部完成，绝不接受调用方传入表名列名；</li>
 *   <li>指标墙一次请求会要多个指标，实现应按表合并查询，而不是逐指标发一次；</li>
 *   <li>无数据返回 null，<b>不要返回 0</b>——两者在业务上不是一回事。</li>
 * </ol>
 *
 * @author fivetech
 */
public interface MetricDataGateway
{
    /**
     * 查询数据新鲜度水位线。
     *
     * @param siteCode 站点
     * @return 水位线；返回 null 表示上游未提供，调用方会退化为按当前整点推测
     */
    DataFreshness getFreshness(String siteCode);

    /**
     * 按时间片查询指标序列。
     *
     * @param request 已解析好的确定区间与指标编码
     * @return key 为指标编码，value 为按时间片升序排列的值；<b>无数据的位置为 null 而非 0</b>；
     *         长度须与请求区间的时间片数一致
     */
    Map<String, List<BigDecimal>> querySeries(MetricSlotRequest request);

    /**
     * 查询整个区间的指标合计。
     * <p>
     * 不能用 {@link #querySeries} 的结果自行加总：派生指标必须先聚合分子分母再套公式，
     * 去重人数类指标（活跃、登录、存款人数）跨时间片不可相加，
     * 平均耗时类指标必须按笔数加权。这些都只能在数据侧算。
     *
     * @param request 同上，granularity 被忽略
     * @return key 为指标编码，value 为区间合计；无数据为 null
     */
    Map<String, BigDecimal> queryTotals(MetricSlotRequest request);

    /**
     * 查询会员明细分页
     */
    RecordPage<MemberRecordVO> queryMemberRecords(RecordPageRequest request);

    /**
     * 查询交易明细分页（存提合并）
     */
    RecordPage<TransactionRecordVO> queryTransactionRecords(RecordPageRequest request);

    /**
     * 查询投注明细分页
     */
    RecordPage<BetRecordVO> queryBetRecords(RecordPageRequest request);
}
