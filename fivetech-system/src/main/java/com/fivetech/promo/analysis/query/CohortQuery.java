package com.fivetech.promo.analysis.query;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 留存与回收（概要、曲线、按注册日明细）的请求。
 *
 * @author fivetech
 */
public class CohortQuery implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 注册日开始（含）yyyy-MM-dd；最早 120 天前 */
    private String regFrom;

    /** 注册日结束（不含）yyyy-MM-dd；最晚到今天（即注册日最晚昨天），单次最多 60 天 */
    private String regTo;

    private List<String> agents = new ArrayList<>();

    private List<String> channels = new ArrayList<>();

    /** 曲线 / 明细：regRet 注册留存 / ltv / roas */
    private String metric;

    /** 曲线的对比线，最多 4 项 */
    private List<CompareItem> compareItems = new ArrayList<>();

    /** 对比项：type agent / channel，code 推广商或推广渠道编码 */
    public static class CompareItem implements Serializable
    {
        private static final long serialVersionUID = 1L;

        private String type;

        private String code;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }

    public String getRegFrom() { return regFrom; }
    public void setRegFrom(String regFrom) { this.regFrom = regFrom; }
    public String getRegTo() { return regTo; }
    public void setRegTo(String regTo) { this.regTo = regTo; }
    public List<String> getAgents() { return agents; }
    public void setAgents(List<String> agents) { this.agents = agents == null ? new ArrayList<>() : agents; }
    public List<String> getChannels() { return channels; }
    public void setChannels(List<String> channels) { this.channels = channels == null ? new ArrayList<>() : channels; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public List<CompareItem> getCompareItems() { return compareItems; }
    public void setCompareItems(List<CompareItem> compareItems) { this.compareItems = compareItems == null ? new ArrayList<>() : compareItems; }
}
