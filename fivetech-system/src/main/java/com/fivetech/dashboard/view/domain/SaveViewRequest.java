package com.fivetech.dashboard.view.domain;

import java.util.List;
import java.util.Map;

/**
 * POST /dashboard/views/{slotCode} 请求体。
 *
 * <ul>
 *   <li>{@code {"mode":"CUSTOM","items":[…]}}：自己挑的（存 items 并记为 lastCustom）</li>
 *   <li>{@code {"mode":"PRESET","presetCode":"daily"}}：跟随预置组合</li>
 *   <li>{@code {"mode":"DEFAULT"}}：跟随默认</li>
 *   <li>{@code {"attrs":{…}}}：只改小状态（不传 mode = 项不动）；attrs 整体替换，不传 = 不变</li>
 * </ul>
 *
 * @author fivetech
 */
public class SaveViewRequest
{
    private String mode;
    private String presetCode;
    private List<String> items;
    private Map<String, Object> attrs;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getPresetCode() { return presetCode; }
    public void setPresetCode(String presetCode) { this.presetCode = presetCode; }
    public List<String> getItems() { return items; }
    public void setItems(List<String> items) { this.items = items; }
    public Map<String, Object> getAttrs() { return attrs; }
    public void setAttrs(Map<String, Object> attrs) { this.attrs = attrs; }
}
