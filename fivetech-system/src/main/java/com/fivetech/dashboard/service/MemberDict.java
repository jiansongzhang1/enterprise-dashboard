package com.fivetech.dashboard.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会员明细的枚举字典：编码 → 中文名。
 * <p>
 * 标签由服务端统一给出，页面与 CSV 导出用同一份，避免两边各写一套、改了一处漏一处。
 * 编码取值与原型 MVP-V1.0 一致；上游返回字典外的编码时原样显示编码，不吞掉。
 *
 * @author fivetech
 */
public final class MemberDict
{
    /** 用户状态：账号管理状态（人工 / 风控设定），不是行为判定的生命周期 */
    public static final Map<String, String> STATUS = new LinkedHashMap<>();

    /** 用户类型：测试与代理账号不计入运营口径，但明细表要看得见 */
    public static final Map<String, String> USER_TYPE = new LinkedHashMap<>();

    /** 国家：站点主体在印度，但同一套站群会收到周边国家流量 */
    public static final Map<String, String> COUNTRY = new LinkedHashMap<>();

    static
    {
        STATUS.put("ok", "正常");
        STATUS.put("pend", "待验证");
        STATUS.put("frozen", "冻结");
        STATUS.put("self", "自我排除");
        STATUS.put("banned", "已封禁");

        USER_TYPE.put("real", "正式用户");
        USER_TYPE.put("trial", "试玩用户");
        USER_TYPE.put("test", "测试账号");
        USER_TYPE.put("agent", "代理账号");

        COUNTRY.put("IN", "印度");
        COUNTRY.put("NP", "尼泊尔");
        COUNTRY.put("BD", "孟加拉");
        COUNTRY.put("LK", "斯里兰卡");
        COUNTRY.put("PK", "巴基斯坦");
    }

    private MemberDict()
    {
    }

    /** 查字典；编码为空返回 null，字典外的编码原样返回 */
    public static String label(Map<String, String> dict, String code)
    {
        if (code == null || code.isEmpty())
        {
            return null;
        }
        return dict.getOrDefault(code, code);
    }
}
