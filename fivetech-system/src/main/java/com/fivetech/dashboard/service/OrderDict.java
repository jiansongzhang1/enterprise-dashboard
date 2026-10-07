package com.fivetech.dashboard.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存款 / 提款 / 投注明细的枚举字典：编码 → 中文名。
 * <p>
 * 与 {@link MemberDict} 同样的原则：标签由服务端统一给出，页面与 CSV 导出共用；
 * 上游返回字典外的编码时原样显示编码。银行所在国家复用 {@link MemberDict#COUNTRY}。
 *
 * @author fivetech
 */
public final class OrderDict
{
    /** 存款订单状态 */
    public static final Map<String, String> DEPOSIT_STATUS = new LinkedHashMap<>();

    /** 提款订单状态 */
    public static final Map<String, String> WITHDRAW_STATUS = new LinkedHashMap<>();

    /** 提款风控审核状态 */
    public static final Map<String, String> AUDIT_STATUS = new LinkedHashMap<>();

    /** 游戏类型 */
    public static final Map<String, String> GAME_TYPE = new LinkedHashMap<>();

    /** 投注明细游戏类型（UDS game_catalog） */
    public static final Map<String, String> GAME_CATALOG = new LinkedHashMap<>();

    /** 注单结算状态 */
    public static final Map<String, String> SETTLE_STATUS = new LinkedHashMap<>();

    static
    {
        DEPOSIT_STATUS.put("succ", "成功");
        DEPOSIT_STATUS.put("fail", "失败");

        WITHDRAW_STATUS.put("succ", "成功");
        WITHDRAW_STATUS.put("fail", "失败");
        WITHDRAW_STATUS.put("auditing", "待审核");
        WITHDRAW_STATUS.put("paying", "出款中");
        WITHDRAW_STATUS.put("rejected", "已驳回");

        AUDIT_STATUS.put("pass", "已通过");
        AUDIT_STATUS.put("pending", "待审");
        AUDIT_STATUS.put("reject", "驳回");

        GAME_TYPE.put("slots", "老虎机");
        GAME_TYPE.put("live", "真人");
        GAME_TYPE.put("mini", "小游戏");

        GAME_CATALOG.put("1", "真人");
        GAME_CATALOG.put("2", "电游");
        GAME_CATALOG.put("3", "体育");
        GAME_CATALOG.put("4", "捕鱼");
        GAME_CATALOG.put("5", "彩票");
        GAME_CATALOG.put("6", "棋牌");
        GAME_CATALOG.put("7", "电竞");

        SETTLE_STATUS.put("done", "已结算");
        SETTLE_STATUS.put("open", "未结算");
        SETTLE_STATUS.put("cancel", "已取消");
    }

    private OrderDict()
    {
    }

    /** 查字典；编码为空返回 null，字典外的编码原样返回 */
    public static String label(Map<String, String> dict, String code)
    {
        return MemberDict.label(dict, code);
    }

    /** 整数编码规整：UDS 数值列可能以 "2.0" 返回，统一成 "2"；非数值原样返回 */
    public static String intCode(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String s = raw.trim();
        if (s.matches("-?\\d+\\.0+"))
        {
            return s.substring(0, s.indexOf('.'));
        }
        return s;
    }
}
