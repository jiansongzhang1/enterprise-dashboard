package com.fivetech.monitor.cond;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 条件规则的部署配置（application.yml 的 {@code monitor.cond}，都可用环境变量覆盖）。
 *
 * <p>字段目录（名称、格式、UDS 取数字段、支持的维度、比率的分子分母、样本口径）不在这里，在「指标管理」里所属范围
 * MONITOR_COND 的指标上维护；这里只放接哪个 UDS 数据集、维度字段叫什么、各种上限。</p>
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "monitor.cond")
public class CondProperties
{
    /** 判定开关：关闭时不检测（规则照常可配置、可预览） */
    private boolean enabled = true;

    /** 整点后多少分钟开始检测刚结束的时段（等 UDS 落库） */
    private int readyMinute = 5;

    /** 小时数据集（每行 = 一小时 × 维度组合）；为空 = 未接入，判定与预览都返回「數據未接入」 */
    private String hourDataset = "";

    /** 小时数据集的时间维度 */
    private String timeField = "stat_hour";

    /** 计数指标名（只取快照字段时 UDS 也要求至少一个指标） */
    private String countMetric = "record_count";

    /**
     * 条件范围的维度 → UDS 字段：ch 拆成推广商 chPromoter、推广渠道 chChannel；pay 支付通道；pf 游戏平台；gty 游戏类型；game 游戏；term 终端。
     * 为空的维度不能选（字段目录里也不返回）。
     */
    private Map<String, String> dimFields = defaultDims();

    /** 各窗口的持续次数上限（durationCount 1..上限） */
    private Map<String, Integer> durationMax = defaultDurationMax();

    /** 静默时长选项（小时） */
    private List<Integer> muteHours = new ArrayList<>(List.of(1, 2, 4, 12));

    private int defaultMuteHours = 2;

    private int maxConditions = 5;

    private int maxChats = 5;

    private int nameMax = 30;

    private int descriptionMax = 200;

    /** 较基准：近几周同星期同时段的平均 */
    private int baseWeeks = 4;

    /** 触发预览回测天数 */
    private int previewDays = 30;

    /** 触发预览按表单签名缓存秒数 */
    private int previewCacheSeconds = 300;

    /** 字段目录缓存秒数（指标管理改了之后多久生效） */
    private int catalogCacheSeconds = 60;

    /** 事件详情走势：事件开始前多少个时段 */
    private int seriesBefore = 24;

    /** 每次 UDS 批量查询最多几项（/v1/query/batch 上限 50） */
    private int batchSize = 50;

    static Map<String, String> defaultDims()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("chPromoter", "agent_id");
        m.put("chChannel", "channel_id");
        m.put("pay", "pay_channel");
        m.put("pf", "platform");
        m.put("gty", "game_type");
        m.put("game", "game_id");
        m.put("term", "terminal");
        return m;
    }

    static Map<String, Integer> defaultDurationMax()
    {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("1h", 4);
        m.put("4h", 3);
        m.put("6h", 3);
        m.put("12h", 2);
        m.put("24h", 2);
        m.put("TODAY", 4);
        return m;
    }

    /** 维度的 UDS 字段；未配置为 null */
    public String dimField(String key)
    {
        String v = dimFields.get(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    public int durationMax(String window)
    {
        Integer v = durationMax.get(window);
        return v == null || v < 1 ? 1 : v;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getReadyMinute() { return readyMinute; }
    public void setReadyMinute(int readyMinute) { this.readyMinute = readyMinute; }
    public String getHourDataset() { return hourDataset; }
    public void setHourDataset(String v) { this.hourDataset = v == null ? "" : v.trim(); }
    public String getTimeField() { return timeField; }
    public void setTimeField(String timeField) { this.timeField = timeField; }
    public String getCountMetric() { return countMetric; }
    public void setCountMetric(String countMetric) { this.countMetric = countMetric; }
    public Map<String, String> getDimFields() { return dimFields; }
    public void setDimFields(Map<String, String> v) { Map<String, String> m = defaultDims(); if (v != null) { m.putAll(v); } this.dimFields = m; }
    public Map<String, Integer> getDurationMax() { return durationMax; }
    public void setDurationMax(Map<String, Integer> v) { Map<String, Integer> m = defaultDurationMax(); if (v != null) { m.putAll(v); } this.durationMax = m; }
    public List<Integer> getMuteHours() { return muteHours; }
    public void setMuteHours(List<Integer> muteHours) { this.muteHours = muteHours == null || muteHours.isEmpty() ? new ArrayList<>(List.of(1, 2, 4, 12)) : muteHours; }
    public int getDefaultMuteHours() { return defaultMuteHours; }
    public void setDefaultMuteHours(int defaultMuteHours) { this.defaultMuteHours = defaultMuteHours; }
    public int getMaxConditions() { return maxConditions; }
    public void setMaxConditions(int maxConditions) { this.maxConditions = maxConditions; }
    public int getMaxChats() { return maxChats; }
    public void setMaxChats(int maxChats) { this.maxChats = maxChats; }
    public int getNameMax() { return nameMax; }
    public void setNameMax(int nameMax) { this.nameMax = nameMax; }
    public int getDescriptionMax() { return descriptionMax; }
    public void setDescriptionMax(int descriptionMax) { this.descriptionMax = descriptionMax; }
    public int getBaseWeeks() { return baseWeeks; }
    public void setBaseWeeks(int baseWeeks) { this.baseWeeks = baseWeeks; }
    public int getPreviewDays() { return previewDays; }
    public void setPreviewDays(int previewDays) { this.previewDays = previewDays; }
    public int getPreviewCacheSeconds() { return previewCacheSeconds; }
    public void setPreviewCacheSeconds(int previewCacheSeconds) { this.previewCacheSeconds = previewCacheSeconds; }
    public int getCatalogCacheSeconds() { return catalogCacheSeconds; }
    public void setCatalogCacheSeconds(int catalogCacheSeconds) { this.catalogCacheSeconds = catalogCacheSeconds; }
    public int getSeriesBefore() { return seriesBefore; }
    public void setSeriesBefore(int seriesBefore) { this.seriesBefore = seriesBefore; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
