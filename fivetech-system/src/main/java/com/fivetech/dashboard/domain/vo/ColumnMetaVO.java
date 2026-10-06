package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;

/**
 * 列元信息。表头、格式、能否排序全部由服务端下发，前端不硬编码。
 *
 * @author fivetech
 */
public class ColumnMetaVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 列编码，前端排序与自定义列传这个值，绝不传数据库列名 */
    private String code;

    /** 列名（按请求语言返回） */
    private String label;

    /** 展示格式 TEXT / LONGTEXT / INT / MONEY / MONEY_CUR（按同一行 currency 的原币种金额）/ MONEY1 / PCT / MIN / HOUR / X / TIME / TAG */
    private String format;

    /** 所属分组，会员表用它实现列方案 */
    private String group;

    /** 是否可排序 */
    private boolean sortable;

    /** 是否可作为筛选条件 */
    private boolean filterable;

    /** 是否为脱敏字段（如会员账号） */
    private boolean masked;

    /** 分组名称（列选择器里的分组标题） */
    private String groupLabel;

    /** 是否默认显示。用户没有自定义过列时，前端只显示这些列 */
    private boolean defaultVisible;

    /** 是否必选：不可在列选择器里取消（如会员表的用户ID、账号名称） */
    private boolean locked;

    /**
     * 是否核心指标（仅指标汇总的指标列有意义，其余列恒 false）。
     * 取自 dashboard_page_metric 中运营总览 emphasis = 'CORE' 的指标，与指标墙大号卡是同一套。
     */
    private boolean core;

    /**
     * 是否支持点单元格下钻到明细（仅指标汇总的指标列有意义，其余列恒 false）。
     * 取自配置 dashboard.summary-drillable-metrics。
     */
    private boolean drillable;

    public static ColumnMetaVO of(String code, String label, String format)
    {
        ColumnMetaVO vo = new ColumnMetaVO();
        vo.code = code;
        vo.label = label;
        vo.format = format;
        return vo;
    }

    public ColumnMetaVO group(String group)
    {
        this.group = group;
        return this;
    }

    public ColumnMetaVO sortable(boolean sortable)
    {
        this.sortable = sortable;
        return this;
    }

    public ColumnMetaVO filterable(boolean filterable)
    {
        this.filterable = filterable;
        return this;
    }

    public ColumnMetaVO masked(boolean masked)
    {
        this.masked = masked;
        return this;
    }

    public ColumnMetaVO group(String group, String groupLabel)
    {
        this.group = group;
        this.groupLabel = groupLabel;
        return this;
    }

    public ColumnMetaVO defaultVisible(boolean defaultVisible)
    {
        this.defaultVisible = defaultVisible;
        return this;
    }

    public ColumnMetaVO core(boolean core)
    {
        this.core = core;
        return this;
    }

    public boolean isCore()
    {
        return core;
    }

    public void setCore(boolean core)
    {
        this.core = core;
    }

    public ColumnMetaVO drillable(boolean drillable)
    {
        this.drillable = drillable;
        return this;
    }

    public boolean isDrillable()
    {
        return drillable;
    }

    public void setDrillable(boolean drillable)
    {
        this.drillable = drillable;
    }

    public ColumnMetaVO locked(boolean locked)
    {
        this.locked = locked;
        return this;
    }

    public String getGroupLabel()
    {
        return groupLabel;
    }

    public void setGroupLabel(String groupLabel)
    {
        this.groupLabel = groupLabel;
    }

    public boolean isDefaultVisible()
    {
        return defaultVisible;
    }

    public void setDefaultVisible(boolean defaultVisible)
    {
        this.defaultVisible = defaultVisible;
    }

    public boolean isLocked()
    {
        return locked;
    }

    public void setLocked(boolean locked)
    {
        this.locked = locked;
    }

    public String getCode()
    {
        return code;
    }

    public void setCode(String code)
    {
        this.code = code;
    }

    public String getLabel()
    {
        return label;
    }

    public void setLabel(String label)
    {
        this.label = label;
    }

    public String getFormat()
    {
        return format;
    }

    public void setFormat(String format)
    {
        this.format = format;
    }

    public String getGroup()
    {
        return group;
    }

    public void setGroup(String group)
    {
        this.group = group;
    }

    public boolean isSortable()
    {
        return sortable;
    }

    public void setSortable(boolean sortable)
    {
        this.sortable = sortable;
    }

    public boolean isFilterable()
    {
        return filterable;
    }

    public void setFilterable(boolean filterable)
    {
        this.filterable = filterable;
    }

    public boolean isMasked()
    {
        return masked;
    }

    public void setMasked(boolean masked)
    {
        this.masked = masked;
    }
}
