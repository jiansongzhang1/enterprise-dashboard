package com.fivetech.dashboard.domain.vo.overview;

import java.util.ArrayList;
import java.util.List;

/**
 * 注册渠道响应。
 *
 * @author fivetech
 */
public class RegChannelVO extends OverviewSectionVO
{
    private static final long serialVersionUID = 1L;

    /** 标题 */
    private String title = "注册渠道";

    /** 注册总人数，等于指标卡 reg */
    private Long totalRegistrations;

    /** 按注册人数降序 */
    private List<RegChannelGroupVO> groups = new ArrayList<>();

    /** 全部渠道，按注册人数降序、同值按名称升序 */
    private List<RegChannelItemVO> channels = new ArrayList<>();

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public Long getTotalRegistrations()
    {
        return totalRegistrations;
    }

    public void setTotalRegistrations(Long totalRegistrations)
    {
        this.totalRegistrations = totalRegistrations;
    }

    public List<RegChannelGroupVO> getGroups()
    {
        return groups;
    }

    public void setGroups(List<RegChannelGroupVO> groups)
    {
        this.groups = groups;
    }

    public List<RegChannelItemVO> getChannels()
    {
        return channels;
    }

    public void setChannels(List<RegChannelItemVO> channels)
    {
        this.channels = channels;
    }
}
