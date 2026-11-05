package com.fivetech.promo.analysis.query;

/**
 * 裂变层级：逐层代理列表 / 导出。
 *
 * @author fivetech
 */
public class FissionQuery extends PromoFilter
{
    private static final long serialVersionUID = 1L;

    /** 要进入的用户 ID；null = 顶层（一级代理） */
    private String parentId;

    private Sort sort;

    private Integer pageNum;

    private Integer pageSize;

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public Sort getSort() { return sort; }
    public void setSort(Sort sort) { this.sort = sort; }
    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer pageNum) { this.pageNum = pageNum; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
}
