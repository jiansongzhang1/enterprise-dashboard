package com.fivetech.promo.analysis.query;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import com.fivetech.dashboard.enums.CompareType;

/**
 * 推广分析公共请求体（渠道表现、裂变层级、获客漏斗、投放日报）。时间左闭右开，按站点时区。
 *
 * @author fivetech
 */
public class PromoFilter implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主区间开始（含）yyyy-MM-dd HH:mm */
    private String slotFrom;

    /** 主区间结束（不含）yyyy-MM-dd HH:mm；跨度最多 365 天 */
    private String slotTo;

    /** NONE / PREV_PERIOD（默认）/ CUSTOM；渠道表现、裂变层级不对比，传了忽略 */
    private CompareType compareType = CompareType.PREV_PERIOD;

    private String compareFrom;

    private String compareTo;

    /** 推广商编码（渠道字典的推广商 ID），与 channels 取并集；都为空 = 全部 */
    private List<String> agents = new ArrayList<>();

    /** 推广渠道编码（渠道字典的推广渠道 ID） */
    private List<String> channels = new ArrayList<>();

    /** 终端编码或 all；只对概览生效，其他页签按 all */
    private String terminal;

    public String getSlotFrom() { return slotFrom; }
    public void setSlotFrom(String slotFrom) { this.slotFrom = slotFrom; }
    public String getSlotTo() { return slotTo; }
    public void setSlotTo(String slotTo) { this.slotTo = slotTo; }
    public CompareType getCompareType() { return compareType; }
    public void setCompareType(CompareType compareType) { this.compareType = compareType; }
    public String getCompareFrom() { return compareFrom; }
    public void setCompareFrom(String compareFrom) { this.compareFrom = compareFrom; }
    public String getCompareTo() { return compareTo; }
    public void setCompareTo(String compareTo) { this.compareTo = compareTo; }
    public List<String> getAgents() { return agents; }
    public void setAgents(List<String> agents) { this.agents = agents == null ? new ArrayList<>() : agents; }
    public List<String> getChannels() { return channels; }
    public void setChannels(List<String> channels) { this.channels = channels == null ? new ArrayList<>() : channels; }
    public String getTerminal() { return terminal; }
    public void setTerminal(String terminal) { this.terminal = terminal; }
}
