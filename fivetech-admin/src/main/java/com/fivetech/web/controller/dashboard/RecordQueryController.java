package com.fivetech.web.controller.dashboard;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.dashboard.domain.query.BetRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.TransactionRecordQuery;
import com.fivetech.dashboard.service.IRecordQueryService;

/**
 * 明细查询接口：会员 / 交易 / 投注三张表。
 * <p>
 * 从指标汇总表下钻时，传 slotFrom / slotTo 即可把条件带过来；
 * 会员表还需按来源指标传 timeField（注册 / 首存 / 活跃），否则行数与指标值对不上。
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/dashboard/records")
public class RecordQueryController extends BaseController
{
    private final IRecordQueryService recordQueryService;

    public RecordQueryController(IRecordQueryService recordQueryService)
    {
        this.recordQueryService = recordQueryService;
    }

    /**
     * 会员明细
     */
    @PreAuthorize("@ss.hasPermi('dashboard:record:member')")
    @PostMapping("/member")
    public AjaxResult member(@Validated @RequestBody MemberRecordQuery query)
    {
        return AjaxResult.success(recordQueryService.queryMembers(query));
    }

    /**
     * 交易明细（存款与提款合并，由 type 区分方向）
     */
    @PreAuthorize("@ss.hasPermi('dashboard:record:transaction')")
    @PostMapping("/transaction")
    public AjaxResult transaction(@Validated @RequestBody TransactionRecordQuery query)
    {
        return AjaxResult.success(recordQueryService.queryTransactions(query));
    }

    /**
     * 投注明细
     */
    @PreAuthorize("@ss.hasPermi('dashboard:record:bet')")
    @PostMapping("/bet")
    public AjaxResult bet(@Validated @RequestBody BetRecordQuery query)
    {
        return AjaxResult.success(recordQueryService.queryBets(query));
    }
}
