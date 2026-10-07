package com.fivetech.dashboard.domain.query;

import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 投注明细查询入参（对齐原型 MVP-V1.0「明细查询 · 投注明细」）。
 * <p>
 * 时间范围按<b>投注时间</b>筛选（{@code from / to} 或 {@code slotFrom / slotTo}）。
 * 关键字搜索 订单号 / 用户ID / 账号名称。
 *
 * @author fivetech
 */
public class BetRecordQuery extends BaseRecordQuery
{
    private static final long serialVersionUID = 1L;

    /** 游戏平台 Code，如 JILI / EVOLUTION / SPRIBE */
    @Pattern(regexp = "^([A-Za-z0-9_-]{1,32})?$", message = "vendorCode 只能包含字母、数字、下划线和横线")
    private String vendorCode;

    /** 游戏类型（game_catalog）：1 真人 / 2 电游 / 3 体育 / 4 捕鱼 / 5 彩票 / 6 棋牌 / 7 电竞 */
    @Pattern(regexp = "^[1-7]?$", message = "gameType 取值应为 1-7")
    private String gameType;

    /** 游戏名称或游戏ID，模糊匹配 */
    @Size(max = 64)
    private String game;

    /** 结算状态：done 已结算 / open 未结算 */
    @Pattern(regexp = "^(done|open|cancel)?$", message = "settleStatus 取值应为 done/open/cancel")
    private String settleStatus;

    /** 投注金额下限（含） */
    @DecimalMin(value = "0", message = "投注金额不能为负")
    private BigDecimal betAmountMin;

    /** 投注金额上限（含） */
    @DecimalMin(value = "0", message = "投注金额不能为负")
    private BigDecimal betAmountMax;

    public String getVendorCode()
    {
        return vendorCode;
    }

    public void setVendorCode(String vendorCode)
    {
        this.vendorCode = vendorCode;
    }

    public String getGameType()
    {
        return gameType;
    }

    public void setGameType(String gameType)
    {
        this.gameType = gameType;
    }

    public String getGame()
    {
        return game;
    }

    public void setGame(String game)
    {
        this.game = game;
    }

    public String getSettleStatus()
    {
        return settleStatus;
    }

    public void setSettleStatus(String settleStatus)
    {
        this.settleStatus = settleStatus;
    }

    public BigDecimal getBetAmountMin()
    {
        return betAmountMin;
    }

    public void setBetAmountMin(BigDecimal betAmountMin)
    {
        this.betAmountMin = betAmountMin;
    }

    public BigDecimal getBetAmountMax()
    {
        return betAmountMax;
    }

    public void setBetAmountMax(BigDecimal betAmountMax)
    {
        this.betAmountMax = betAmountMax;
    }
}
