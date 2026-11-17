package com.fivetech.risk.user;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户详情抽屉（FY-75）与全部用户搜索的请求。
 *
 * <ul>
 *   <li>window：REG_D0 註冊當日（默认）/ REG_D1 次日 / REG_7 / REG_30 / REG_90（含注册当日共 N 天）/ CUSTOM 自定义（from、to）</li>
 *   <li>listFrom、listTo：名单当前的时间范围（yyyy-MM-dd HH:mm）；注册早于资料起始日时抽屉默认用它</li>
 *   <li>資金流水：type DEPOSIT / WITHDRAW / BONUS，statuses 多选（不选 = 全部），sortCol time / amount / duration，pageNum（每页 20）</li>
 * </ul>
 *
 * @author fivetech
 */
public class RiskUserQuery implements Serializable
{
    private static final long serialVersionUID = 1L;

    private String userId;

    private String keyword;

    private String window;

    private String from;

    private String to;

    private String listFrom;

    private String listTo;

    private String type;

    private List<String> statuses = new ArrayList<>();

    private String sortCol;

    private String sortDir;

    private Integer pageNum;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public String getWindow() { return window; }
    public void setWindow(String window) { this.window = window; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public String getListFrom() { return listFrom; }
    public void setListFrom(String listFrom) { this.listFrom = listFrom; }
    public String getListTo() { return listTo; }
    public void setListTo(String listTo) { this.listTo = listTo; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public List<String> getStatuses() { return statuses; }
    public void setStatuses(List<String> statuses) { this.statuses = statuses == null ? new ArrayList<>() : statuses; }
    public String getSortCol() { return sortCol; }
    public void setSortCol(String sortCol) { this.sortCol = sortCol; }
    public String getSortDir() { return sortDir; }
    public void setSortDir(String sortDir) { this.sortDir = sortDir; }
    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer pageNum) { this.pageNum = pageNum; }
}
