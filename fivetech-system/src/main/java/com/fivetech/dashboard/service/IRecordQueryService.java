package com.fivetech.dashboard.service;

import com.fivetech.dashboard.domain.query.BetRecordQuery;
import com.fivetech.dashboard.domain.query.DepositRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.WithdrawRecordQuery;
import com.fivetech.dashboard.domain.vo.BetRecordVO;
import com.fivetech.dashboard.domain.vo.DepositRecordVO;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.domain.vo.RecordResultVO;
import com.fivetech.dashboard.domain.vo.WithdrawRecordVO;
import com.fivetech.dashboard.gateway.RecordPageRequest;

/**
 * 明细查询（会员 / 存款 / 提款 / 投注）。
 *
 * @author fivetech
 */
public interface IRecordQueryService
{
    /** 会员明细 */
    RecordResultVO<MemberRecordVO> queryMembers(MemberRecordQuery query);

    /** 存款明细 */
    RecordResultVO<DepositRecordVO> queryDeposits(DepositRecordQuery query);

    /** 提款明细 */
    RecordResultVO<WithdrawRecordVO> queryWithdrawals(WithdrawRecordQuery query);

    /** 投注明细 */
    RecordResultVO<BetRecordVO> queryBets(BetRecordQuery query);

    /**
     * 组装会员明细的下推请求（不执行查询），供异步导出复用，保证导出与页面同源
     */
    RecordPageRequest buildMemberRequest(MemberRecordQuery query);

    /** 组装存款明细的下推请求（不执行查询） */
    RecordPageRequest buildDepositRequest(DepositRecordQuery query);

    /** 组装提款明细的下推请求（不执行查询） */
    RecordPageRequest buildWithdrawRequest(WithdrawRecordQuery query);

    /** 组装投注明细的下推请求（不执行查询） */
    RecordPageRequest buildBetRequest(BetRecordQuery query);
}
