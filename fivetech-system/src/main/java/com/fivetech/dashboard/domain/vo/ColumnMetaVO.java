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

    /** 展示格式 TEXT / INT / MONEY / MONEY1 / PCT / MIN / X / TIME / TAG */
    private String format;

    /** 所属分组，会员表用它实现列方案 */
    private String group;

    /** 是否可排序 */
    private boolean sortable;

    /** 是否可作为筛选条件 */
    private boolean filterable;

    /** 是否为脱敏字段（如会员账号） */
    private boolean masked;

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
