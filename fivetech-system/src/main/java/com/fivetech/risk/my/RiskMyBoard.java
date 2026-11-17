package com.fivetech.risk.my;

import java.util.Date;

/**
 * 我的榜单（risk_my_board）：名单条件加排行字段保存为一张榜，按「用户 + 站点」隔离。
 *
 * @author fivetech
 */
public class RiskMyBoard
{
    private Long boardId;

    private Long userId;

    private String siteCode;

    private String boardName;

    /** 名单条件 JSON（结构同 rank/list 的 conditions），数值按保存时固定 */
    private String conditions;

    private String rankCol;

    private String rankDir;

    private Integer orderNum;

    private Boolean showCard;

    private String createBy;

    private Date createTime;

    private String updateBy;

    private Date updateTime;

    public Long getBoardId() { return boardId; }
    public void setBoardId(Long boardId) { this.boardId = boardId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
    public String getBoardName() { return boardName; }
    public void setBoardName(String boardName) { this.boardName = boardName; }
    public String getConditions() { return conditions; }
    public void setConditions(String conditions) { this.conditions = conditions; }
    public String getRankCol() { return rankCol; }
    public void setRankCol(String rankCol) { this.rankCol = rankCol; }
    public String getRankDir() { return rankDir; }
    public void setRankDir(String rankDir) { this.rankDir = rankDir; }
    public Integer getOrderNum() { return orderNum; }
    public void setOrderNum(Integer orderNum) { this.orderNum = orderNum; }
    public Boolean getShowCard() { return showCard; }
    public void setShowCard(Boolean showCard) { this.showCard = showCard; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
