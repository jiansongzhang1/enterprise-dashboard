package com.fivetech.dashboard.service;

import com.fivetech.dashboard.domain.query.BetRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.TransactionRecordQuery;
import com.fivetech.dashboard.domain.vo.BetRecordVO;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.domain.vo.RecordResultVO;
import com.fivetech.dashboard.domain.vo.TransactionRecordVO;

/**
 * 明细查询（会员 / 交易 / 投注）。
 *
 * @author fivetech
 */
public interface IRecordQueryService
{
    /** 会员明细 */
    RecordResultVO<MemberRecordVO> queryMembers(MemberRecordQuery query);

    /** 交易明细，存提合并 */
    RecordResultVO<TransactionRecordVO> queryTransactions(TransactionRecordQuery query);

    /** 投注明细 */
    RecordResultVO<BetRecordVO> queryBets(BetRecordQuery query);
}
