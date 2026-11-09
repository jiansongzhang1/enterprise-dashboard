package com.fivetech.dashboard.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个配置位下发给前端的完整结构：定义（候选项、上下限、规则、预置组合、默认）+ 我在本站的视图（服务端已合成）。
 * 字段与《个人自定义指标集 · 设计方案》的 Slot 类型一致，由 {@link #toMap()} 输出。
 *
 * @author fivetech
 */
public final class SlotView
{
    public static final class Candidate
    {
        final String code;
        final String label;
        final String groupCode;
        final String groupName;
        final boolean available;
        final String reason;

        Candidate(String code, String label, String groupCode, String groupName, boolean available, String reason)
        {
            this.code = code;
            this.label = label;
            this.groupCode = groupCode;
            this.groupName = groupName;
            this.available = available;
            this.reason = reason;
        }

        public String getCode() { return code; }
        public String getLabel() { return label; }
        public String getGroupCode() { return groupCode; }
        public boolean isAvailable() { return available; }

        Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("label", label);
            m.put("groupCode", groupCode);
            m.put("groupName", groupName);
            m.put("available", available);
            if (!available)
            {
                m.put("reason", reason);
            }
            return m;
        }
    }

    public static final class Preset
    {
        final String code;
        final String name;
        final String tip;
        /** 已去掉本站不可用项 */
        final List<String> items;
        final boolean isDefault;

        Preset(String code, String name, String tip, List<String> items, boolean isDefault)
        {
            this.code = code;
            this.name = name;
            this.tip = tip;
            this.items = items;
            this.isDefault = isDefault;
        }

        public String getCode() { return code; }
        public List<String> getItems() { return items; }
        public boolean isDefault() { return isDefault; }

        Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", name);
            m.put("tip", tip);
            m.put("items", items);
            m.put("isDefault", isDefault);
            return m;
        }
    }

    String slotCode;
    String pageCode;
    String name;
    String kind;
    int minItems;
    Integer maxItems;
    boolean groupAdjacent;
    List<SlotDef.OrderRule> rules = new ArrayList<>();
    List<Candidate> candidates = new ArrayList<>();
    List<Preset> presets = new ArrayList<>();
    List<String> defaultItems = new ArrayList<>();
    String defaultPresetCode;
    List<String> items = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    List<String> lastCustom;
    Map<String, Object> attrs = new LinkedHashMap<>();
    List<Map<String, Object>> notices = new ArrayList<>();

    public String getSlotCode() { return slotCode; }
    public String getPageCode() { return pageCode; }
    public String getName() { return name; }
    public int getMinItems() { return minItems; }
    public Integer getMaxItems() { return maxItems; }
    public boolean isGroupAdjacent() { return groupAdjacent; }
    public List<SlotDef.OrderRule> getRules() { return rules; }
    public List<Candidate> getCandidates() { return candidates; }
    public List<Preset> getPresets() { return presets; }
    public List<String> getDefaultItems() { return defaultItems; }
    public String getDefaultPresetCode() { return defaultPresetCode; }
    /** 合成后的最终列表：展示、取数都用它 */
    public List<String> getItems() { return items; }
    public List<String> getSkipped() { return skipped; }
    public List<String> getLastCustom() { return lastCustom; }
    public Map<String, Object> getAttrs() { return attrs; }
    public List<Map<String, Object>> getNotices() { return notices; }

    public Preset preset(String code)
    {
        for (Preset p : presets)
        {
            if (p.code.equals(code))
            {
                return p;
            }
        }
        return null;
    }

    public Map<String, Object> toMap()
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slotCode", slotCode);
        m.put("name", name);
        m.put("kind", kind);
        m.put("minItems", minItems);
        m.put("maxItems", maxItems);
        m.put("groupAdjacent", groupAdjacent);
        List<Map<String, Object>> rs = new ArrayList<>();
        for (SlotDef.OrderRule r : rules)
        {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("before", r.getBefore());
            x.put("after", r.getAfter());
            x.put("message", r.getMessage());
            rs.add(x);
        }
        m.put("rules", rs);
        List<Map<String, Object>> cs = new ArrayList<>();
        candidates.forEach(c -> cs.add(c.toMap()));
        m.put("candidates", cs);
        List<Map<String, Object>> ps = new ArrayList<>();
        presets.forEach(p -> ps.add(p.toMap()));
        m.put("presets", ps);
        m.put("defaultItems", defaultItems);
        m.put("defaultPresetCode", defaultPresetCode);
        m.put("items", items);
        m.put("skipped", skipped);
        m.put("lastCustom", lastCustom);
        m.put("attrs", attrs);
        m.put("notices", notices);
        return m;
    }
}
