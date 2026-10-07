package com.fivetech.dashboard.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.vo.ColumnMetaVO;

/**
 * 明细表列注册表。
 * <p>
 * 同时承担两个职责：
 * <ul>
 *   <li>给前端下发表头（列名、格式、可排序、可筛选），前端不硬编码；</li>
 *   <li>作为<b>排序列与筛选列的白名单</b>，挡住任意字段名注入。</li>
 * </ul>
 * 会员表 27 列一屏放不下：按 group 分组下发，由用户自选显示列。
 *
 * @author fivetech
 */
@Component
public class RecordColumnRegistry
{
    private static final String[] G_BASE = {"base", "基本信息"};

    private static final String[] G_REG = {"reg", "注册与来源"};

    private static final String[] G_DEP = {"dep", "存款指标"};

    private static final String[] G_BET = {"bet", "投注指标"};

    private static final String[] G_WD = {"wd", "提款指标"};

    private static final String[] G_REV = {"rev", "收益与资产"};

    private final List<ColumnMetaVO> memberColumns = new ArrayList<>();

    private final List<ColumnMetaVO> depositColumns = new ArrayList<>();

    private final List<ColumnMetaVO> withdrawColumns = new ArrayList<>();

    private final List<ColumnMetaVO> betColumns = new ArrayList<>();

    public RecordColumnRegistry()
    {
        // ---- 会员表：27 列，对齐原型 MVP-V1.0。列显示由用户自选，这里只定义全集 ----
        // 基本信息：用户ID 与账号名称必选（locked），默认显示的 13 列标 defaultVisible
        member("userId", "用户ID", "TEXT", G_BASE).locked(true).defaultVisible(true).sortable(true);
        member("username", "账号名称", "TEXT", G_BASE).locked(true).defaultVisible(true);
        member("status", "用户状态", "TAG", G_BASE).defaultVisible(true).filterable(true);
        member("userType", "用户类型", "TAG", G_BASE).defaultVisible(true).filterable(true);
        member("level", "用户等级", "TAG", G_BASE).defaultVisible(true).filterable(true);
        member("country", "国家", "TAG", G_BASE).defaultVisible(true).filterable(true);
        // 注册与来源
        member("registerTime", "注册时间", "TIME", G_REG).defaultVisible(true).sortable(true).filterable(true);
        // 存款指标
        member("firstDepositTime", "首存时间", "TIME", G_DEP).sortable(true).filterable(true);
        member("firstDepositAmount", "首存金额", "MONEY", G_DEP).defaultVisible(true).sortable(true);
        member("firstDepositChannel", "首存通道", "TEXT", G_DEP);
        member("regToFtdHours", "注册→首存时长", "HOUR", G_DEP).sortable(true);
        member("lastDepositTime", "最近存款时间", "TIME", G_DEP).sortable(true);
        member("lastDepositAmount", "最近存款金额", "MONEY", G_DEP).sortable(true);
        member("cumulativeDepositAmount", "历史累计存款金额", "MONEY", G_DEP).defaultVisible(true).sortable(true).filterable(true);
        member("cumulativeDepositCount", "历史累计存款笔数", "INT", G_DEP).sortable(true);
        // 投注指标
        member("lastBetTime", "最近投注时间", "TIME", G_BET).sortable(true).filterable(true);
        member("lastBetAmount", "最近投注金额", "MONEY", G_BET).sortable(true);
        member("cumulativeBetAmount", "历史累计投注金额", "MONEY", G_BET).defaultVisible(true).sortable(true);
        member("cumulativeBetCount", "历史累计投注笔数", "INT", G_BET).sortable(true);
        member("turnoverMultiple", "流水倍数", "X", G_BET).sortable(true);
        // 提款指标
        member("lastWithdrawTime", "最近提款时间", "TIME", G_WD).sortable(true);
        member("lastWithdrawAmount", "最近提款金额", "MONEY", G_WD).sortable(true);
        member("cumulativeWithdrawAmount", "历史累计提款金额", "MONEY", G_WD).defaultVisible(true).sortable(true);
        member("cumulativeWithdrawCount", "历史累计提款笔数", "INT", G_WD).sortable(true);
        // 收益与资产
        member("cumulativeGgr", "历史累计GGR", "MONEY", G_REV).defaultVisible(true).sortable(true);
        member("cumulativeNgr", "历史累计NGR", "MONEY", G_REV).defaultVisible(true).sortable(true);

        // ---- 订单表（存款 / 提款 / 投注）：列固定、不提供列自选，全部默认显示 ----
        // 存提款金额是原币种（MONEY_CUR），跨币种比大小没有意义，所以金额列不可排序
        order(depositColumns, "orderNo", "订单号", "TEXT");
        order(depositColumns, "userId", "用户ID", "TEXT");
        order(depositColumns, "username", "账号名称", "TEXT");
        order(depositColumns, "amount", "存款金额", "MONEY_CUR");
        order(depositColumns, "currency", "币种", "TEXT");
        order(depositColumns, "status", "状态", "TAG").filterable(true);
        order(depositColumns, "createTime", "创建时间", "TIME").sortable(true).filterable(true);
        order(depositColumns, "finishTime", "完成时间", "TIME").sortable(true);
        order(depositColumns, "costMinutes", "耗时", "MIN").sortable(true);

        order(withdrawColumns, "orderNo", "订单号", "TEXT");
        order(withdrawColumns, "userId", "用户ID", "TEXT");
        order(withdrawColumns, "username", "账号名称", "TEXT");
        order(withdrawColumns, "amount", "提款金额", "MONEY_CUR");
        order(withdrawColumns, "currency", "币种", "TEXT");
        order(withdrawColumns, "status", "状态", "TAG").filterable(true);
        order(withdrawColumns, "auditStatus", "审核", "TAG").filterable(true);
        order(withdrawColumns, "auditor", "审核人", "TEXT");
        order(withdrawColumns, "payer", "资金审批人", "TEXT");
        order(withdrawColumns, "payTime", "资金操作时间", "TIME").sortable(true);
        order(withdrawColumns, "bankName", "银行名称", "TEXT");
        order(withdrawColumns, "bankCode", "银行代码", "TEXT");
        order(withdrawColumns, "bankCountry", "银行所在国家", "TAG");
        order(withdrawColumns, "createTime", "创建时间", "TIME").sortable(true).filterable(true);
        order(withdrawColumns, "finishTime", "完成时间", "TIME").sortable(true);
        order(withdrawColumns, "costMinutes", "耗时", "MIN").sortable(true);
        order(withdrawColumns, "auditNote", "审核备注", "LONGTEXT");

        // 投注金额统一是站点币种，可以排序和按区间筛选
        order(betColumns, "orderNo", "订单号", "TEXT");
        order(betColumns, "userId", "用户ID", "TEXT");
        order(betColumns, "username", "账号名称", "TEXT");
        order(betColumns, "vendorCode", "游戏平台Code", "TEXT").filterable(true);
        order(betColumns, "vendorName", "平台厂商名", "TEXT");
        order(betColumns, "gameType", "游戏类型", "TAG").filterable(true);
        order(betColumns, "gameId", "游戏ID", "TEXT").filterable(true);
        order(betColumns, "gameName", "游戏名称", "TEXT").filterable(true);
        order(betColumns, "betAmount", "投注金额", "MONEY").sortable(true).filterable(true);
        order(betColumns, "payout", "派彩", "MONEY").sortable(true);
        order(betColumns, "winLoss", "输赢", "MONEY").sortable(true);
        order(betColumns, "settleStatus", "结算状态", "TAG").filterable(true);
        order(betColumns, "betTime", "投注时间", "TIME").sortable(true).filterable(true);
        order(betColumns, "settleTime", "结算时间", "TIME").sortable(true);
    }

    /**
     * 会员表全部列。显示哪些列由用户在页面上自选（defaultVisible 为初始方案，locked 为必选），
     * 服务端不再按「视角」裁剪列——数据一次给全，切换列不用重新请求。
     */
    public List<ColumnMetaVO> memberColumns()
    {
        return new ArrayList<>(memberColumns);
    }

    private ColumnMetaVO member(String code, String label, String format, String[] group)
    {
        ColumnMetaVO column = ColumnMetaVO.of(code, label, format).group(group[0], group[1]);
        memberColumns.add(column);
        return column;
    }

    /** 订单表的列：固定显示，defaultVisible = true，没有必选/可选之分 */
    private ColumnMetaVO order(List<ColumnMetaVO> target, String code, String label, String format)
    {
        ColumnMetaVO column = ColumnMetaVO.of(code, label, format).defaultVisible(true);
        target.add(column);
        return column;
    }

    /** 存款明细：9 列 */
    public List<ColumnMetaVO> depositColumns()
    {
        return new ArrayList<>(depositColumns);
    }

    /** 提款明细：17 列 */
    public List<ColumnMetaVO> withdrawColumns()
    {
        return new ArrayList<>(withdrawColumns);
    }

    /** 投注明细：14 列 */
    public List<ColumnMetaVO> betColumns()
    {
        return new ArrayList<>(betColumns);
    }

    /**
     * 校验排序列。不在白名单内时回退到默认列，而不是把它拼进查询。
     *
     * @param columns 该表的列定义
     * @param requested 请求的排序列编码
     * @param fallback 默认排序列
     */
    public String resolveSortColumn(List<ColumnMetaVO> columns, String requested, String fallback)
    {
        if (StringUtils.isEmpty(requested))
        {
            return fallback;
        }
        for (ColumnMetaVO column : columns)
        {
            if (column.getCode().equals(requested) && column.isSortable())
            {
                return requested;
            }
        }
        return fallback;
    }

    /**
     * 校验排序方向，只允许 asc / desc
     */
    public String resolveSortDirection(String requested)
    {
        return "asc".equalsIgnoreCase(requested) ? "asc" : "desc";
    }
}
