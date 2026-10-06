package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;

/**
 * 一条提示。前端<b>必须渲染</b>——粒度被降级却不说，用户会以为自己还在看小时。
 *
 * @author fivetech
 */
public class OverviewNoticeVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** GRANULARITY_DOWNGRADED / NO_COMPLETE_SLOT_YET / DATA_LAGGING / PARAM_IGNORED ... */
    private String code;

    /** INFO / WARN */
    private String level = "INFO";

    /** 一句话说明，可直接展示 */
    private String message;

    /** 为什么会这样，展开后才看的细节 */
    private String detail;

    public static OverviewNoticeVO of(String code, String level, String message, String detail)
    {
        OverviewNoticeVO vo = new OverviewNoticeVO();
        vo.code = code;
        vo.level = level;
        vo.message = message;
        vo.detail = detail;
        return vo;
    }

    public static OverviewNoticeVO info(String code, String message, String detail)
    {
        return of(code, "INFO", message, detail);
    }

    public static OverviewNoticeVO warn(String code, String message, String detail)
    {
        return of(code, "WARN", message, detail);
    }

    public String getCode()
    {
        return code;
    }

    public void setCode(String code)
    {
        this.code = code;
    }

    public String getLevel()
    {
        return level;
    }

    public void setLevel(String level)
    {
        this.level = level;
    }

    public String getMessage()
    {
        return message;
    }

    public void setMessage(String message)
    {
        this.message = message;
    }

    public String getDetail()
    {
        return detail;
    }

    public void setDetail(String detail)
    {
        this.detail = detail;
    }
}
