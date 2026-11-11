package com.fivetech.promo.analysis.query;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 渠道表现 · 取一层（导出时不传 parent、pageNum、pageSize，服务端展开全部层级）。
 *
 * @author fivetech
 */
public class ChannelTableQuery extends PromoFilter
{
    private static final long serialVersionUID = 1L;

    /** 行维度路径，1–5 层：agent / channel / date / terminal / relation；不重复，先后不限；不传 = 个人视图 promo.channel.rows */
    private List<String> rows = new ArrayList<>();

    /** 要展开的那一行的完整路径（上一层返回的 rows[].path）；空 = 第 1 层 */
    private List<PathItem> parent = new ArrayList<>();

    /** 列编码，按显示顺序；不传 = 个人视图 promo.channel.columns */
    private List<String> columns = new ArrayList<>();

    private Sort sort;

    private Integer pageNum;

    private Integer pageSize;

    /** 路径上的一级：维度 + 成员编码 */
    public static class PathItem implements Serializable
    {
        private static final long serialVersionUID = 1L;

        private String dim;

        private String key;

        public PathItem()
        {
        }

        public PathItem(String dim, String key)
        {
            this.dim = dim;
            this.key = key;
        }

        public String getDim() { return dim; }
        public void setDim(String dim) { this.dim = dim; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
    }

    public List<String> getRows() { return rows; }
    public void setRows(List<String> rows) { this.rows = rows == null ? new ArrayList<>() : rows; }
    public List<PathItem> getParent() { return parent; }
    public void setParent(List<PathItem> parent) { this.parent = parent == null ? new ArrayList<>() : parent; }
    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns == null ? new ArrayList<>() : columns; }
    public Sort getSort() { return sort; }
    public void setSort(Sort sort) { this.sort = sort; }
    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer pageNum) { this.pageNum = pageNum; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
}
