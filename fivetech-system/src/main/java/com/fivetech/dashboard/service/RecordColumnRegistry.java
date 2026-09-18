package com.fivetech.dashboard.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.vo.ColumnMetaVO;
import com.fivetech.dashboard.enums.MemberViewScheme;

/**
 * 明细表列注册表。
 * <p>
 * 同时承担两个职责：
 * <ul>
 *   <li>给前端下发表头（列名、格式、可排序、可筛选），前端不硬编码；</li>
 *   <li>作为<b>排序列与筛选列的白名单</b>，挡住任意字段名注入。</li>
 * </ul>
 * 会员表 20 列一屏放不下，按 group 分组实现列方案。
 *
 * @author fivetech
 */
@Component
public class RecordColumnRegistry
{
    /** 会员表列方案 → 允许的分组 */
    private static final Map<MemberViewScheme, List<String>> MEMBER_VIEWS = new LinkedHashMap<>();

    static
    {
        MEMBER_VIEWS.put(MemberViewScheme.REG, Arrays.asList("key", "reg"));
        MEMBER_VIEWS.put(MemberViewScheme.FTD, Arrays.asList("key", "ftd"));
        MEMBER_VIEWS.put(MemberViewScheme.VALUE, Arrays.asList("key", "cum", "st"));
        MEMBER_VIEWS.put(MemberViewScheme.ALL, Arrays.asList("key", "reg", "ftd", "cum", "st"));
    }

    private final List<ColumnMetaVO> memberColumns = new ArrayList<>();

    private final List<ColumnMetaVO> transactionColumns = new ArrayList<>();

    private final List<ColumnMetaVO> betColumns = new ArrayList<>();

    public RecordColumnRegistry()
    {
        memberColumns.add(ColumnMetaVO.of("account", "会员账号", "TEXT").group("key").masked(true));
        memberColumns.add(ColumnMetaVO.of("registerTime", "注册时间", "TIME").group("key").sortable(true));
        memberColumns.add(ColumnMetaVO.of("registerChannel", "注册渠道", "TEXT").group("reg").filterable(true));
        memberColumns.add(ColumnMetaVO.of("device", "注册设备", "TEXT").group("reg").filterable(true));
        memberColumns.add(ColumnMetaVO.of("hasFirstDeposit", "是否已首存", "TAG").group("reg").filterable(true));
        memberColumns.add(ColumnMetaVO.of("firstDepositTime", "首存时间", "TIME").group("ftd").sortable(true));
        memberColumns.add(ColumnMetaVO.of("firstDepositAmount", "首存金额", "MONEY").group("ftd").sortable(true));
        memberColumns.add(ColumnMetaVO.of("firstDepositChannel", "首存通道", "TEXT").group("ftd").filterable(true));
        memberColumns.add(ColumnMetaVO.of("regToFtdHours", "注册→首存", "INT").group("ftd").sortable(true));
        memberColumns.add(ColumnMetaVO.of("cumulativeDeposit", "累计存款", "MONEY").group("cum").sortable(true));
        memberColumns.add(ColumnMetaVO.of("cumulativeWithdraw", "累计提款", "MONEY").group("cum").sortable(true));
        memberColumns.add(ColumnMetaVO.of("cumulativeBet", "累计投注", "MONEY").group("cum").sortable(true));
        memberColumns.add(ColumnMetaVO.of("cumulativeNgr", "累计NGR", "MONEY").group("cum").sortable(true));
        memberColumns.add(ColumnMetaVO.of("turnoverMultiple", "流水倍数", "X").group("cum").sortable(true));
        memberColumns.add(ColumnMetaVO.of("tier", "价值层级", "TAG").group("st").filterable(true));
        memberColumns.add(ColumnMetaVO.of("stage", "生命周期", "TAG").group("st").filterable(true));
        memberColumns.add(ColumnMetaVO.of("lastActiveTime", "最近活跃", "TIME").group("st").sortable(true));
        memberColumns.add(ColumnMetaVO.of("lastBetGap", "最近投注", "TEXT").group("st"));

        transactionColumns.add(ColumnMetaVO.of("orderNo", "订单号", "TEXT"));
        transactionColumns.add(ColumnMetaVO.of("type", "类型", "TAG").filterable(true));
        transactionColumns.add(ColumnMetaVO.of("account", "会员账号", "TEXT").masked(true));
        transactionColumns.add(ColumnMetaVO.of("amount", "金额", "MONEY").sortable(true));
        transactionColumns.add(ColumnMetaVO.of("channel", "通道", "TEXT").filterable(true));
        transactionColumns.add(ColumnMetaVO.of("status", "状态", "TAG").filterable(true));
        transactionColumns.add(ColumnMetaVO.of("auditStatus", "审核", "TAG").filterable(true));
        transactionColumns.add(ColumnMetaVO.of("auditor", "审核人", "TEXT"));
        transactionColumns.add(ColumnMetaVO.of("createTime", "创建时间", "TIME").sortable(true));
        transactionColumns.add(ColumnMetaVO.of("finishTime", "完成时间", "TIME").sortable(true));
        transactionColumns.add(ColumnMetaVO.of("costMinutes", "耗时", "MIN").sortable(true));

        betColumns.add(ColumnMetaVO.of("orderNo", "注单号", "TEXT"));
        betColumns.add(ColumnMetaVO.of("account", "会员账号", "TEXT").masked(true));
        betColumns.add(ColumnMetaVO.of("vendor", "厂商", "TEXT").filterable(true));
        betColumns.add(ColumnMetaVO.of("gameType", "类型", "TEXT").filterable(true));
        betColumns.add(ColumnMetaVO.of("game", "游戏名称", "TEXT").filterable(true));
        betColumns.add(ColumnMetaVO.of("betAmount", "投注额", "MONEY").sortable(true));
        betColumns.add(ColumnMetaVO.of("payout", "派彩", "MONEY").sortable(true));
        betColumns.add(ColumnMetaVO.of("winLoss", "输赢", "MONEY").sortable(true));
        betColumns.add(ColumnMetaVO.of("createTime", "投注时间", "TIME").sortable(true));
    }

    /**
     * 会员表按列方案过滤列
     */
    public List<ColumnMetaVO> memberColumns(MemberViewScheme scheme)
    {
        List<String> groups = MEMBER_VIEWS.getOrDefault(
            scheme == null ? MemberViewScheme.ALL : scheme, MEMBER_VIEWS.get(MemberViewScheme.ALL));
        List<ColumnMetaVO> result = new ArrayList<>();
        for (ColumnMetaVO column : memberColumns)
        {
            if (groups.contains(column.getGroup()))
            {
                result.add(column);
            }
        }
        return result;
    }

    public List<ColumnMetaVO> transactionColumns()
    {
        return new ArrayList<>(transactionColumns);
    }

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
