package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;

/**
 * 某一块取数失败的原因。
 * <p>
 * 分块失败<b>不拖垮整个响应</b>：失败的块从 {@code blocks} 里缺席、原因落到
 * {@code blockErrors}，其余块照常返回。整页白屏是最差的降级。
 *
 * @author fivetech
 */
public class OverviewBlockErrorVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    private int code;

    private String message;

    public static OverviewBlockErrorVO of(int code, String message)
    {
        OverviewBlockErrorVO vo = new OverviewBlockErrorVO();
        vo.code = code;
        vo.message = message;
        return vo;
    }

    public int getCode()
    {
        return code;
    }

    public void setCode(int code)
    {
        this.code = code;
    }

    public String getMessage()
    {
        return message;
    }

    public void setMessage(String message)
    {
        this.message = message;
    }
}
