package com.fivetech.risk;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 风控分析的运行配置（application.yml 的 {@code risk}，每项都可用环境变量覆盖）。
 *
 * <p>这里只放「接到哪个数据集、用哪个维度字段、上限多少」这类部署相关的值。
 * 排行榜的列、名单条件字段、游戏分析的列，以及它们对应的 UDS 指标字段，都在「指标管理」里维护
 * （dashboard_metric_card，page_scope = RISK_RANK / RISK_GAME），不在这里。</p>
 *
 * <p>数据集名为空 = 还没接入：对应接口照常返回结构，{@code dataPending = true}，不报错。</p>
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "risk")
public class RiskProperties
{
    /** 关闭后风控分析不调 UDS，排行榜、游戏分析按「数据待接入」返回 */
    private boolean enabled = true;

    /** 用户 × 日汇总（存提、投注、派彩、优惠等），排行榜用户指标的来源 */
    private String userDayDataset = "";

    /** 用户 × 游戏 × 日汇总（投注、派彩、单笔最大派彩那一笔），游戏组列与中奖游戏、盈利游戏的来源；为空时游戏组列退回用户 × 日 */
    private String userGameDayDataset = "";

    /** 提款订单明细（大额提款榜） */
    private String withdrawOrderDataset = "";

    /** 游戏 × 日汇总（游戏分析，含去重人数） */
    private String gameDataset = "";

    /** 玩家快照（注册时间、首存、历史累计） */
    private String playerSnapshotDataset = "";

    /** 用户资料（账号、注册时间、推广渠道、上级）：全部用户搜索、用户详情抽屉。TODO：UDS 数据集名待定 */
    private String userDataset = "";

    /** 存款订单明细（用户详情抽屉 · 資金流水）。TODO：UDS 数据集名待定 */
    private String depositOrderDataset = "";

    /** 优惠发放明细（用户详情抽屉 · 資金流水）。TODO：UDS 数据集名待定 */
    private String bonusDataset = "";

    /** 渠道获客（渠道风险榜的註冊用戶、首存人數、首存金額）：按推广渠道的窗口汇总；为空 = 获客三列待接入 */
    private String channelAcqDataset = "";

    /** 渠道获客的指标字段：regUsers / ftdUsers / ftdAmt → UDS 字段 */
    private Map<String, String> channelAcqFields = new LinkedHashMap<>(Map.of("regUsers", "reg_users", "ftdUsers", "ftd_users", "ftdAmt", "ftd_amount"));

    /** 用户详情抽屉 · 趋势（按日）的数据集；为空 = 用用户 × 日数据集（userDayDataset） */
    private String userSeriesDataset = "";

    /**
     * 用户 × 日数据集的游戏范围字段（如 game_scope：A 全部 / V:平台编码）。配了时：不选平台或选多个平台取 gameScopeAll，
     * 只选一个平台取 gameScopePlatformPrefix + 平台编码；为空 = 不加这个条件。
     */
    private String gameScopeField = "";

    private String gameScopeAll = "A";

    private String gameScopePlatformPrefix = "V:";

    /**
     * 按数据集覆盖字段名：数据集名 → (逻辑名 → 字段)，没覆盖的用 {@link #fields}；配成空串 = 该数据集没有这个字段。
     * 例如用户窗口数据集的上级账号叫 parent_loginname、用户快照叫 agent_login。
     */
    private Map<String, Map<String, String>> datasetFields = new LinkedHashMap<>();

    /** 按数据集加的固定条件：数据集名 → (UDS 字段 → 值，EQ) */
    private Map<String, Map<String, String>> datasetFilters = new LinkedHashMap<>();

    /** 资料起始日（yyyy-MM-dd）：注册早于它的用户不能按注册日期看抽屉；为空 = 不限。TODO：以 UDS 实际起始日为准 */
    private String dataStartDate = "";

    /**
     * 订单状态：页面状态 → UDS 原始值（逗号分隔）。键：deposit.SUCCESS / FAILED / PENDING，withdraw.PAID / PENDING / REJECTED。
     * TODO：原始值以 UDS 为准。
     */
    private Map<String, String> statuses = new LinkedHashMap<>(Map.of(
        "deposit.SUCCESS", "SUCCESS", "deposit.FAILED", "FAILED", "deposit.PENDING", "PENDING",
        "withdraw.PAID", "PAID", "withdraw.PENDING", "PENDING,REVIEW", "withdraw.REJECTED", "REJECTED"));

    /** 区间汇总时 time.granularity 传什么（UDS 按区间整体 SUM / MAX）；为空则按日取再在后端汇总 */
    private String rangeGranularity = "";

    /** 用户 × 日数据集按日取（true，后端按指标管理里的汇总方式合并）还是由 UDS 按区间直接汇总（false，维度里不带日期） */
    private boolean userDayByDate = false;

    /** 提款订单的「成功出款」状态值（大额提款榜只算这一种） */
    private String paidStatus = "PAID";

    /** UDS 单页条数（上限 1000） */
    private int pageSize = 1000;

    /** 一次排行榜计算最多读入的用户数；超过返回 400 提示缩小范围 */
    private int maxUsers = 300_000;

    /** 一次读入的原始行数上限（用户 × 日 / 用户 × 游戏 × 日） */
    private int maxRows = 2_000_000;

    /** 导出行数上限 */
    private int maxExportRows = 1_000_000;

    /** 同一筛选的计算结果缓存秒数（翻页、切列、导出复用）；0 = 不缓存 */
    private int cacheSeconds = 300;

    /** 指标字典里风控列的缓存秒数（指标管理改了多久生效） */
    private int catalogCacheSeconds = 60;

    /** 游戏抽屉「盈利最多的用户（常玩此游戏）」：用排行榜的哪一列判断常玩游戏、按哪一列排、显示哪些列（都是排行榜列的接口编码） */
    private String drawerTopGameColumn = "topGame";

    private String drawerTopUsersRankCol = "winLoss";

    private java.util.List<String> drawerTopUsersCols = new java.util.ArrayList<>(java.util.List.of("bet", "winLoss", "rtp"));

    /** 游戏抽屉近 7 日显示哪些列（游戏分析列的接口编码） */
    private java.util.List<String> drawerDayCols = new java.util.ArrayList<>(java.util.List.of("bet", "ggr", "rtp"));

    /** 游戏分析默认行维度 */
    private java.util.List<String> gameDefaultPath = new java.util.ArrayList<>(java.util.List.of("platform", "brand", "game"));

    /** 用户详情抽屉（FY-75）：概況 9 项、歷史累計 6 项、趋势、遊戲页签用哪些列（排行榜列的接口编码） */
    private java.util.List<String> userOverviewCols = new java.util.ArrayList<>(java.util.List.of("dep", "wd", "net", "bet", "winLoss", "rtp", "turnX",
        "wdN", "bonusR"));

    private java.util.List<String> userHistoryCols = new java.util.ArrayList<>(java.util.List.of("firstDepTime", "firstDepAmt", "histDep", "histWd",
        "histNet", "histWinLoss"));

    /** 趋势：存款、提款、有效投注、玩家输赢（累計存款、累計提款、累計存提差由前两项累加） */
    private java.util.List<String> userTrendCols = new java.util.ArrayList<>(java.util.List.of("dep", "wd", "bet", "winLoss"));

    /** 遊戲页签：按游戏汇总的列（投注、派彩、玩家输赢、RTP）与投注笔数列 */
    private java.util.List<String> userGameCols = new java.util.ArrayList<>(java.util.List.of("bet", "pay", "winLoss", "rtp"));

    private String userBetCountCol = "betN";

    /** 查询区间最多多少天 */
    private int maxSpanDays = 93;

    /** 新老用户的固定天数（概览变化归因用；榜单条件只作用于榜单，不影响它） */
    private int newUserDays = 7;

    /**
     * UDS 维度字段名：逻辑名 → 数据集里的字段名。逻辑名固定（代码按它取值），字段名按 UDS 给的改。
     * 默认值见 {@link #defaultFields()}，yml / 环境变量里配了的覆盖默认。
     */
    private Map<String, String> fields = new LinkedHashMap<>();

    public static Map<String, String> defaultFields()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("date", "uds_dt");
        m.put("userId", "user_id");
        m.put("account", "account");
        m.put("regTime", "register_time");
        m.put("agentId", "promoter_id");
        m.put("channelId", "channel_id");
        m.put("parent", "parent_account");
        m.put("platform", "platform_code");
        m.put("brand", "brand_code");
        m.put("gameType", "game_type");
        m.put("game", "game_code");
        m.put("gameName", "game_name");
        m.put("orderNo", "order_no");
        m.put("orderAmount", "amount");
        m.put("orderStatus", "status");
        m.put("submitTime", "submit_time");
        m.put("paidTime", "paid_time");
        m.put("settleTime", "settle_time");
        m.put("rowCount", "record_count");
        // 用户详情抽屉（TODO：字段名待 UDS 确认）
        m.put("parentId", "parent_id");
        m.put("payMethod", "pay_method");
        m.put("bonusType", "bonus_type");
        m.put("hour", "uds_hour");
        return m;
    }

    /** 逻辑名对应的 UDS 字段；配成空串表示该数据集没有这个字段 */
    public String field(String logical)
    {
        String v = fields.get(logical);
        if (v == null)
        {
            v = defaultFields().get(logical);
        }
        return v == null ? "" : v.trim();
    }

    /** 某个数据集里逻辑名对应的字段：先看 datasetFields 的覆盖，再看全局 */
    public String field(String dataset, String logical)
    {
        Map<String, String> o = dataset == null ? null : datasetFields.get(dataset);
        if (o != null && o.containsKey(logical))
        {
            String v = o.get(logical);
            return v == null ? "" : v.trim();
        }
        return field(logical);
    }

    public String getChannelAcqDataset() { return channelAcqDataset; }
    public void setChannelAcqDataset(String v) { this.channelAcqDataset = v == null ? "" : v.trim(); }
    public Map<String, String> getChannelAcqFields() { return channelAcqFields; }
    public void setChannelAcqFields(Map<String, String> v) { if (v != null) { this.channelAcqFields.putAll(v); } }
    public String getUserSeriesDataset() { return userSeriesDataset; }
    public void setUserSeriesDataset(String v) { this.userSeriesDataset = v == null ? "" : v.trim(); }
    public String getGameScopeField() { return gameScopeField; }
    public void setGameScopeField(String v) { this.gameScopeField = v == null ? "" : v.trim(); }
    public String getGameScopeAll() { return gameScopeAll; }
    public void setGameScopeAll(String v) { this.gameScopeAll = v == null ? "" : v.trim(); }
    public String getGameScopePlatformPrefix() { return gameScopePlatformPrefix; }
    public void setGameScopePlatformPrefix(String v) { this.gameScopePlatformPrefix = v == null ? "" : v; }
    public Map<String, Map<String, String>> getDatasetFields() { return datasetFields; }
    public void setDatasetFields(Map<String, Map<String, String>> v) { this.datasetFields = v == null ? new LinkedHashMap<>() : v; }
    public Map<String, Map<String, String>> getDatasetFilters() { return datasetFilters; }
    public void setDatasetFilters(Map<String, Map<String, String>> v) { this.datasetFilters = v == null ? new LinkedHashMap<>() : v; }

    public java.util.List<String> getUserOverviewCols() { return userOverviewCols; }
    public void setUserOverviewCols(java.util.List<String> v) { if (v != null && !v.isEmpty()) { this.userOverviewCols = v; } }
    public java.util.List<String> getUserHistoryCols() { return userHistoryCols; }
    public void setUserHistoryCols(java.util.List<String> v) { if (v != null && !v.isEmpty()) { this.userHistoryCols = v; } }
    public java.util.List<String> getUserTrendCols() { return userTrendCols; }
    public void setUserTrendCols(java.util.List<String> v) { if (v != null && !v.isEmpty()) { this.userTrendCols = v; } }
    public java.util.List<String> getUserGameCols() { return userGameCols; }
    public void setUserGameCols(java.util.List<String> v) { if (v != null && !v.isEmpty()) { this.userGameCols = v; } }
    public String getUserBetCountCol() { return userBetCountCol; }
    public void setUserBetCountCol(String v) { this.userBetCountCol = v == null ? "" : v.trim(); }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUserDataset() { return userDataset; }
    public void setUserDataset(String v) { this.userDataset = v == null ? "" : v.trim(); }
    public String getDepositOrderDataset() { return depositOrderDataset; }
    public void setDepositOrderDataset(String v) { this.depositOrderDataset = v == null ? "" : v.trim(); }
    public String getBonusDataset() { return bonusDataset; }
    public void setBonusDataset(String v) { this.bonusDataset = v == null ? "" : v.trim(); }
    public String getDataStartDate() { return dataStartDate; }
    public void setDataStartDate(String v) { this.dataStartDate = v == null ? "" : v.trim(); }
    public Map<String, String> getStatuses() { return statuses; }
    public void setStatuses(Map<String, String> v) { if (v != null) { this.statuses.putAll(v); } }

    /** 页面状态对应的 UDS 原始值 */
    public java.util.List<String> statusValues(String kind, String status)
    {
        String v = statuses.get(kind + "." + status);
        java.util.List<String> out = new java.util.ArrayList<>();
        if (v != null)
        {
            for (String x : v.split(","))
            {
                if (!x.isBlank())
                {
                    out.add(x.trim());
                }
            }
        }
        return out;
    }

    public String getUserDayDataset() { return userDayDataset; }
    public void setUserDayDataset(String v) { this.userDayDataset = v == null ? "" : v.trim(); }
    public String getUserGameDayDataset() { return userGameDayDataset; }
    public void setUserGameDayDataset(String v) { this.userGameDayDataset = v == null ? "" : v.trim(); }
    public String getWithdrawOrderDataset() { return withdrawOrderDataset; }
    public void setWithdrawOrderDataset(String v) { this.withdrawOrderDataset = v == null ? "" : v.trim(); }
    public String getGameDataset() { return gameDataset; }
    public void setGameDataset(String v) { this.gameDataset = v == null ? "" : v.trim(); }
    public String getPlayerSnapshotDataset() { return playerSnapshotDataset; }
    public void setPlayerSnapshotDataset(String v) { this.playerSnapshotDataset = v == null ? "" : v.trim(); }
    public String getRangeGranularity() { return rangeGranularity; }
    public void setRangeGranularity(String v) { this.rangeGranularity = v == null ? "" : v.trim(); }
    public boolean isUserDayByDate() { return userDayByDate; }
    public void setUserDayByDate(boolean v) { this.userDayByDate = v; }
    public String getPaidStatus() { return paidStatus; }
    public void setPaidStatus(String v) { this.paidStatus = v == null ? "" : v.trim(); }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getMaxUsers() { return maxUsers; }
    public void setMaxUsers(int maxUsers) { this.maxUsers = maxUsers; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public int getMaxExportRows() { return maxExportRows; }
    public void setMaxExportRows(int maxExportRows) { this.maxExportRows = maxExportRows; }
    public int getCacheSeconds() { return cacheSeconds; }
    public void setCacheSeconds(int cacheSeconds) { this.cacheSeconds = cacheSeconds; }
    public int getCatalogCacheSeconds() { return catalogCacheSeconds; }
    public void setCatalogCacheSeconds(int v) { this.catalogCacheSeconds = v; }
    public String getDrawerTopGameColumn() { return drawerTopGameColumn; }
    public void setDrawerTopGameColumn(String v) { this.drawerTopGameColumn = v; }
    public String getDrawerTopUsersRankCol() { return drawerTopUsersRankCol; }
    public void setDrawerTopUsersRankCol(String v) { this.drawerTopUsersRankCol = v; }
    public java.util.List<String> getDrawerTopUsersCols() { return drawerTopUsersCols; }
    public void setDrawerTopUsersCols(java.util.List<String> v) { this.drawerTopUsersCols = v == null ? new java.util.ArrayList<>() : v; }
    public java.util.List<String> getDrawerDayCols() { return drawerDayCols; }
    public void setDrawerDayCols(java.util.List<String> v) { this.drawerDayCols = v == null ? new java.util.ArrayList<>() : v; }
    public java.util.List<String> getGameDefaultPath() { return gameDefaultPath; }
    public void setGameDefaultPath(java.util.List<String> v) { this.gameDefaultPath = v == null ? new java.util.ArrayList<>() : v; }
    public int getMaxSpanDays() { return maxSpanDays; }
    public void setMaxSpanDays(int v) { this.maxSpanDays = v; }
    public int getNewUserDays() { return newUserDays; }
    public void setNewUserDays(int newUserDays) { this.newUserDays = newUserDays; }
    public Map<String, String> getFields() { return fields; }
    public void setFields(Map<String, String> fields) { this.fields = fields == null ? new LinkedHashMap<>() : fields; }
}
