package com.fivetech.dashboard.config;

/**
 * CSV 导出配置，挂在 {@code dashboard.export.*} 下。
 *
 * @author fivetech
 */
public class ExportProperties
{
    /** 导出总开关。关闭后 export_csv=true 会被拒绝 */
    private boolean enabled = true;

    /**
     * 是否在文件开头写口径说明区。
     * <p>
     * 开启时文件前几行是「导出时间/站点/时区/区间/口径版本/免责声明」等键值对，
     * 空行之后才是表头与数据。Excel 打开无碍，但会让严格的 CSV 解析器多读几行——
     * 若下游是程序消费，关掉它。
     */
    private boolean includeMeta = true;

    /** 单次导出的最大行数，超过则拒绝并提示缩小范围 */
    private int maxRows = 100000;

    /**
     * 导出范围。
     * <ul>
     *   <li>{@code ALL_FILTERED}（默认）：当前筛选条件下的全部数据，忽略分页参数。
     *       与原型「匯出範圍：當前篩選條件下的全部資料（非當前頁）」一致，
     *       也是用户点「导出」时的普遍预期。</li>
     *   <li>{@code CURRENT_PAGE}：只导出本次请求返回的那一页，
     *       即 JSON 响应里有什么就导出什么。</li>
     * </ul>
     * 两种都复用同一条查询链路，差别只在是否覆盖分页参数。
     */
    private String scope = "ALL_FILTERED";

    /**
     * 导出是否需要独立权限点 {@code dashboard:export:csv}。
     * <p>
     * 明细含会员账号等个人信息，默认要求单独授权——
     * 否则「能看」就等于「能批量拖走」。
     */
    private boolean requirePermission = true;

    /** 独立权限点标识 */
    private String permission = "dashboard:export:csv";

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public boolean isIncludeMeta()
    {
        return includeMeta;
    }

    public void setIncludeMeta(boolean includeMeta)
    {
        this.includeMeta = includeMeta;
    }

    public int getMaxRows()
    {
        return maxRows;
    }

    public void setMaxRows(int maxRows)
    {
        this.maxRows = maxRows;
    }

    public String getScope()
    {
        return scope;
    }

    public void setScope(String scope)
    {
        this.scope = scope;
    }

    /** 是否导出全量（而非仅当前页） */
    public boolean isExportAll()
    {
        return !"CURRENT_PAGE".equalsIgnoreCase(scope);
    }

    public boolean isRequirePermission()
    {
        return requirePermission;
    }

    public void setRequirePermission(boolean requirePermission)
    {
        this.requirePermission = requirePermission;
    }

    public String getPermission()
    {
        return permission;
    }

    public void setPermission(String permission)
    {
        this.permission = permission;
    }
}
