package com.fivetech.dashboard.gateway;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.fivetech.dashboard.domain.vo.BetRecordVO;
import com.fivetech.dashboard.domain.vo.DepositRecordVO;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.domain.vo.WithdrawRecordVO;

/**
 * 外部数据平台网关。
 * <p>
 * 本系统<b>不建设数仓、不做 ETL、不落业务明细</b>，所有业务数值实时向外部取。
 * 这个接口就是那条边界：上面是本系统的语义层（口径、时间、权限、编排），
 * 下面是数据平台。
 * <p>
 * 实现方需遵守：
 * <ol>
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
     * 查询存款明细分页
     */
    RecordPage<DepositRecordVO> queryDepositRecords(RecordPageRequest request);

    /**
     * 查询提款明细分页
     */
    RecordPage<WithdrawRecordVO> queryWithdrawRecords(RecordPageRequest request);

    /**
     * 查询投注明细分页
     */
    RecordPage<BetRecordVO> queryBetRecords(RecordPageRequest request);

    /**
     * 拆解查询：按配置的维度分组，取区间 [from, to) 的聚合值。运营总览的排行榜、队列、
     * 用户快照、注册渠道都走它。
     *
     * @param key {@code dashboard.gateway.uds.breakdowns} 下的配置名
     * @param from 区间开始（含）
     * @param to 区间结束（不含）
     * @return 未配置时 {@code configured=false}，不抛异常；数据平台故障照常抛出
     */
    BreakdownResult queryBreakdown(String key, java.time.LocalDateTime from, java.time.LocalDateTime to);

    // ===================== 异步导出 =====================

    /**
     * 提交明细异步导出。筛选、排序、时间与同名分页查询完全同源，只是不分页。
     *
     * @param tab member / deposit / withdraw / bet
     * @param request 与分页查询相同的请求（分页参数被忽略）
     * @param maxRows 行数上限，&lt;=0 表示用数据平台默认上限
     */
    ExportJob submitDetailExport(String tab, RecordPageRequest request, long maxRows);

    /**
     * 提交压测数据集导出，只用于验证大文件的生成与下载链路。
     *
     * @param merchantCode 商户过滤，空表示不过滤
     * @param detailRows true 按明细行导出（大文件），false 按天 × 商户汇总（几行）
     * @param maxRows 行数上限，&lt;=0 表示不限
     */
    ExportJob submitBenchmarkExport(String merchantCode, boolean detailRows, long maxRows);

    /** 查询导出作业；DONE 时文件链接为现签 */
    ExportJob getExportJob(String jobId);

    /** 取消排队或运行中的导出作业 */
    ExportJob cancelExportJob(String jobId);
}
