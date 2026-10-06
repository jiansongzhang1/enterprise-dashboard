package com.fivetech.dashboard.domain.query;

import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 会员明细查询入参（对齐原型 MVP-V1.0「会员明细」页）。
 * <p>
 * 页面没有统一的「时间范围」选择器，而是三个<b>各自独立、可叠加</b>的时间条件：
 * 注册时间、首存时间、最近投注时间。三个都不传就是不限时间（累计口径的会员表本来就看全量）。
 * 所有时间条件都是 {@code yyyy-MM-dd HH:mm}，<b>左闭右开</b>，与全站时间语义一致。
 * <p>
 * 从指标汇总表下钻时，前端把时间片直接写进对应的那个时间条件：
 * 注册类指标 → registerTime，首存类（ftd / ftdA）→ firstDepositTime，活跃人数 → lastBetTime。
 * 为兼容旧调用，只传 {@code slotFrom/slotTo + sourceMetricCode} 时服务端按同样的规则映射。
 * <p>
 * 父类的 {@code from / to} 对会员表不生效。
 *
 * @author fivetech
 */
public class MemberRecordQuery extends BaseRecordQuery
{
    private static final long serialVersionUID = 1L;

    /** 注册时间起（含），yyyy-MM-dd HH:mm */
    @Size(max = 19)
    private String registerTimeFrom;

    /** 注册时间止（不含） */
    @Size(max = 19)
    private String registerTimeTo;

    /** 首存时间起（含） */
    @Size(max = 19)
    private String firstDepositTimeFrom;

    /** 首存时间止（不含） */
    @Size(max = 19)
    private String firstDepositTimeTo;

    /** 最近投注时间起（含） */
    @Size(max = 19)
    private String lastBetTimeFrom;

    /** 最近投注时间止（不含） */
    @Size(max = 19)
    private String lastBetTimeTo;

    /** 用户状态：ok 正常 / pend 待验证 / frozen 冻结 / self 自我排除 / banned 已封禁 */
    @Pattern(regexp = "^(ok|pend|frozen|self|banned)?$", message = "status 取值应为 ok/pend/frozen/self/banned")
    private String status;

    /** 用户类型：real 正式用户 / trial 试玩用户 / test 测试账号 / agent 代理账号 */
    @Pattern(regexp = "^(real|trial|test|agent)?$", message = "userType 取值应为 real/trial/test/agent")
    private String userType;

    /** 用户等级 VIP1–VIP18 */
    @Pattern(regexp = "^(VIP([1-9]|1[0-8]))?$", message = "level 取值应为 VIP1–VIP18")
    private String level;

    /** 国家，ISO 3166-1 两位代码，如 IN / NP / BD / LK / PK */
    @Pattern(regexp = "^([A-Z]{2})?$", message = "country 应为两位大写国家代码")
    private String country;

    /** 注册渠道，如 LP-01 / organic */
    @Size(max = 64)
    private String registerChannel;

    /** 历史累计存款金额下限（含） */
    @DecimalMin(value = "0", message = "累计存款金额不能为负")
    private BigDecimal cumulativeDepositMin;

    /** 历史累计存款金额上限（含） */
    @DecimalMin(value = "0", message = "累计存款金额不能为负")
    private BigDecimal cumulativeDepositMax;

    /**
     * 导出时要包含的列编码（页面上用户当前选择的列）。只影响 CSV 导出，不影响查询；
     * 不传则导出全部列。白名单外的编码忽略，用户ID / 账号名称总是导出
     */
    @Size(max = 64)
    private java.util.List<String> columns;

    public java.util.List<String> getColumns() { return columns; }
    public void setColumns(java.util.List<String> columns) { this.columns = columns; }
    public String getRegisterTimeFrom() { return registerTimeFrom; }
    public void setRegisterTimeFrom(String registerTimeFrom) { this.registerTimeFrom = registerTimeFrom; }
    public String getRegisterTimeTo() { return registerTimeTo; }
    public void setRegisterTimeTo(String registerTimeTo) { this.registerTimeTo = registerTimeTo; }
    public String getFirstDepositTimeFrom() { return firstDepositTimeFrom; }
    public void setFirstDepositTimeFrom(String firstDepositTimeFrom) { this.firstDepositTimeFrom = firstDepositTimeFrom; }
    public String getFirstDepositTimeTo() { return firstDepositTimeTo; }
    public void setFirstDepositTimeTo(String firstDepositTimeTo) { this.firstDepositTimeTo = firstDepositTimeTo; }
    public String getLastBetTimeFrom() { return lastBetTimeFrom; }
    public void setLastBetTimeFrom(String lastBetTimeFrom) { this.lastBetTimeFrom = lastBetTimeFrom; }
    public String getLastBetTimeTo() { return lastBetTimeTo; }
    public void setLastBetTimeTo(String lastBetTimeTo) { this.lastBetTimeTo = lastBetTimeTo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getRegisterChannel() { return registerChannel; }
    public void setRegisterChannel(String registerChannel) { this.registerChannel = registerChannel; }
    public BigDecimal getCumulativeDepositMin() { return cumulativeDepositMin; }
    public void setCumulativeDepositMin(BigDecimal cumulativeDepositMin) { this.cumulativeDepositMin = cumulativeDepositMin; }
    public BigDecimal getCumulativeDepositMax() { return cumulativeDepositMax; }
    public void setCumulativeDepositMax(BigDecimal cumulativeDepositMax) { this.cumulativeDepositMax = cumulativeDepositMax; }
}
