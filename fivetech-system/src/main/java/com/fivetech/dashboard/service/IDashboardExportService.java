package com.fivetech.dashboard.service;

import java.util.List;
import com.fivetech.dashboard.domain.query.BetRecordQuery;
import com.fivetech.dashboard.domain.query.DepositRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.WithdrawRecordQuery;
import com.fivetech.dashboard.domain.vo.ExportTaskVO;

/**
 * 明细异步导出。
 * <p>
 * 链路：本系统校验权限与条件 → 向数据平台提交导出作业 → 前端轮询作业状态 →
 * 完成后拿到对象存储的预签名链接，浏览器直连下载。文件不经过本服务。
 *
 * @author fivetech
 */
public interface IDashboardExportService
{
    ExportTaskVO submitMembers(MemberRecordQuery query);

    ExportTaskVO submitDeposits(DepositRecordQuery query);

    ExportTaskVO submitWithdrawals(WithdrawRecordQuery query);

    ExportTaskVO submitBets(BetRecordQuery query);

    /**
     * 压测导出：只用于验证大文件链路与浏览器下载性能
     *
     * @param merchantCode 商户，空表示全部
     * @param detailRows true 明细行（大文件），false 汇总（几行）
     * @param maxRows 行数上限，&lt;=0 表示不限
     */
    ExportTaskVO submitBenchmark(String merchantCode, boolean detailRows, long maxRows);

    /** 查询任务状态，只能查自己的任务 */
    ExportTaskVO getTask(String jobId);

    /** 取消任务，只能取消自己的任务 */
    ExportTaskVO cancelTask(String jobId);

    /** 当前用户最近的导出任务（仅本系统记录，不含平台状态） */
    List<ExportTaskVO> listMyTasks();
}
