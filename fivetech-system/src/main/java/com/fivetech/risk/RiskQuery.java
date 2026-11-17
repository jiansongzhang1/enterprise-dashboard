package com.fivetech.risk;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fivetech.risk.data.RiskFilter;

/**
 * 风控接口的查询 body（7.1.1 公共字段 + 排行榜 / 游戏分析各自的字段）。
 *
 * @author fivetech
 */
public class RiskQuery implements Serializable
{
    private static final long serialVersionUID = 1L;

    static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ---------- 公共 ----------

    private String slotFrom;

    private String slotTo;

    private List<String> platform = new ArrayList<>();

    private List<String> brand = new ArrayList<>();

    private List<String> gameType = new ArrayList<>();

    private List<String> agents = new ArrayList<>();

    private List<String> channels = new ArrayList<>();

    // ---------- 排行榜 ----------

    private List<String> boards = new ArrayList<>();

    private List<Integer> bands = new ArrayList<>();

    private String board;

    private List<Condition> conditions = new ArrayList<>();

    private String rankCol;

    private String rankDir;

    private String viewSortCol;

    private String viewSortDir;

    private Integer topX;

    /** 已废弃：同 conditions 里的 amount 最小值（大额提款榜单笔门槛）；两者都传时以 conditions 为准 */
    private BigDecimal bigThreshold;

    /**
     * true = conditions 是名单的全部条件，替换榜单的默认条件（入榜条件 / 我的榜单保存的条件，FY-67 R1）；
     * false / 不传 = conditions 加在默认条件之上（兼容旧调用）。全部用户的范围（有存款或投注）始终生效。
     */
    private Boolean custom;

    /** 渠道风险榜的风险类型；不传 = 个人设置 → 全局默认 → 系统默认 */
    private List<String> riskTypes = new ArrayList<>();

    /** 渠道风险榜下钻：{agentId?, channelId?, riskType?}；为空 = 渠道列表 */
    private Drill drill;

    /** 搜索（名單內）：账号包含或用户 ID 开头，最多 8 条 */
    private String keyword;

    private List<String> cols = new ArrayList<>();

    private Boolean showShare;

    private Integer pageNum;

    private Integer pageSize;

    private String locateKey;

    // ---------- 游戏分析 ----------

    private List<String> path = new ArrayList<>();

    private String sortCol;

    private String sortDir;

    private String expandKey;

    private String gameCode;

    /** 名单条件：范围字段传 min / max（含，null = 不限），选择字段传 values */
    public static class Condition implements Serializable
    {
        private static final long serialVersionUID = 1L;

        private String field;

        private BigDecimal min;

        private BigDecimal max;

        private List<String> values;

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public BigDecimal getMin() { return min; }
        public void setMin(BigDecimal min) { this.min = min; }
        public BigDecimal getMax() { return max; }
        public void setMax(BigDecimal max) { this.max = max; }
        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }

        public Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", field);
            if (values != null)
            {
                m.put("values", values);
            }
            else
            {
                m.put("min", min);
                m.put("max", max);
            }
            return m;
        }
    }

    /** 渠道风险榜下钻：推广商 / 渠道 / 风险类型，三项都可空（全部渠道、所选全部风险类型） */
    public static class Drill implements Serializable
    {
        private static final long serialVersionUID = 1L;

        private String agentId;

        private String channelId;

        private String riskType;

        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public String getChannelId() { return channelId; }
        public void setChannelId(String channelId) { this.channelId = channelId; }
        public String getRiskType() { return riskType; }
        public void setRiskType(String riskType) { this.riskType = riskType; }
    }

    /** 时间：左闭右开，站点时区 yyyy-MM-dd HH:mm；区间最多 maxDays 天 */
    public RiskFilter filter(String site, int maxDays)
    {
        Map<String, String> errors = new LinkedHashMap<>();
        LocalDateTime from = parse(slotFrom, "slotFrom", errors);
        LocalDateTime to = parse(slotTo, "slotTo", errors);
        if (from != null && to != null)
        {
            if (!to.isAfter(from))
            {
                errors.put("slotTo", "結束時間要晚於開始時間");
            }
            else if (ChronoUnit.HOURS.between(from, to) > maxDays * 24L)
            {
                errors.put("slotTo", "時間範圍最多 " + maxDays + " 天");
            }
        }
        RiskException.failIfAny(errors);
        return new RiskFilter(site, from, to, platform, brand, gameType, agents, channels);
    }

    private static LocalDateTime parse(String v, String field, Map<String, String> errors)
    {
        if (v == null || v.isBlank())
        {
            errors.put(field, "請選擇時間範圍");
            return null;
        }
        try
        {
            String s = v.trim().replace('T', ' ');
            return LocalDateTime.parse(s.length() > 16 ? s.substring(0, 16) : s, SLOT);
        }
        catch (RuntimeException e)
        {
            errors.put(field, "時間格式應為 yyyy-MM-dd HH:mm");
            return null;
        }
    }

    public String getSlotFrom() { return slotFrom; }
    public void setSlotFrom(String slotFrom) { this.slotFrom = slotFrom; }
    public String getSlotTo() { return slotTo; }
    public void setSlotTo(String slotTo) { this.slotTo = slotTo; }
    public List<String> getPlatform() { return platform; }
    public void setPlatform(List<String> platform) { this.platform = platform == null ? new ArrayList<>() : platform; }
    public List<String> getBrand() { return brand; }
    public void setBrand(List<String> brand) { this.brand = brand == null ? new ArrayList<>() : brand; }
    public List<String> getGameType() { return gameType; }
    public void setGameType(List<String> gameType) { this.gameType = gameType == null ? new ArrayList<>() : gameType; }
    public List<String> getAgents() { return agents; }
    public void setAgents(List<String> agents) { this.agents = agents == null ? new ArrayList<>() : agents; }
    public List<String> getChannels() { return channels; }
    public void setChannels(List<String> channels) { this.channels = channels == null ? new ArrayList<>() : channels; }
    public List<String> getBoards() { return boards; }
    public void setBoards(List<String> boards) { this.boards = boards == null ? new ArrayList<>() : boards; }
    public List<Integer> getBands() { return bands; }
    public void setBands(List<Integer> bands) { this.bands = bands == null ? new ArrayList<>() : bands; }
    public String getBoard() { return board; }
    public void setBoard(String board) { this.board = board; }
    public List<Condition> getConditions() { return conditions; }
    public void setConditions(List<Condition> conditions) { this.conditions = conditions == null ? new ArrayList<>() : conditions; }
    public String getRankCol() { return rankCol; }
    public void setRankCol(String rankCol) { this.rankCol = rankCol; }
    public String getRankDir() { return rankDir; }
    public void setRankDir(String rankDir) { this.rankDir = rankDir; }
    public String getViewSortCol() { return viewSortCol; }
    public void setViewSortCol(String viewSortCol) { this.viewSortCol = viewSortCol; }
    public String getViewSortDir() { return viewSortDir; }
    public void setViewSortDir(String viewSortDir) { this.viewSortDir = viewSortDir; }
    public Integer getTopX() { return topX; }
    public void setTopX(Integer topX) { this.topX = topX; }
    public Boolean getCustom() { return custom; }
    public void setCustom(Boolean custom) { this.custom = custom; }
    public boolean isCustom() { return Boolean.TRUE.equals(custom); }
    public List<String> getRiskTypes() { return riskTypes; }
    public void setRiskTypes(List<String> riskTypes) { this.riskTypes = riskTypes == null ? new ArrayList<>() : riskTypes; }
    public Drill getDrill() { return drill; }
    public void setDrill(Drill drill) { this.drill = drill; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public BigDecimal getBigThreshold() { return bigThreshold; }
    public void setBigThreshold(BigDecimal bigThreshold) { this.bigThreshold = bigThreshold; }
    public List<String> getCols() { return cols; }
    public void setCols(List<String> cols) { this.cols = cols == null ? new ArrayList<>() : cols; }
    public Boolean getShowShare() { return showShare; }
    public void setShowShare(Boolean showShare) { this.showShare = showShare; }
    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer pageNum) { this.pageNum = pageNum; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    public String getLocateKey() { return locateKey; }
    public void setLocateKey(String locateKey) { this.locateKey = locateKey; }
    public List<String> getPath() { return path; }
    public void setPath(List<String> path) { this.path = path == null ? new ArrayList<>() : path; }
    public String getSortCol() { return sortCol; }
    public void setSortCol(String sortCol) { this.sortCol = sortCol; }
    public String getSortDir() { return sortDir; }
    public void setSortDir(String sortDir) { this.sortDir = sortDir; }
    public String getExpandKey() { return expandKey; }
    public void setExpandKey(String expandKey) { this.expandKey = expandKey; }
    public String getGameCode() { return gameCode; }
    public void setGameCode(String gameCode) { this.gameCode = gameCode; }
}
