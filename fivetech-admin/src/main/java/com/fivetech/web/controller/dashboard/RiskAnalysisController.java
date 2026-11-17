package com.fivetech.web.controller.dashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.risk.RiskOptionsService;
import com.fivetech.risk.RiskQuery;
import com.fivetech.risk.game.RiskGameService;
import com.fivetech.risk.my.RiskMyBoardService;
import com.fivetech.risk.rank.RiskRankService;
import com.fivetech.risk.rank.RiskSheet;
import com.fivetech.risk.user.RiskUserQuery;
import com.fivetech.risk.user.RiskUserService;

/**
 * 风控分析（设计文档 7.1）：筛选项、榜单条件、排行榜、我的榜单、游戏分析。概览走
 * {@code POST /dashboard/metrics/overview}（scene = RISK）。
 *
 * <p>权限 {@code dashboard:risk:view}；导出 {@code dashboard:risk:export}；改榜单条件 {@code dashboard:risk:cond:edit}。
 * 站点取请求头 X-Site-Code。参数错返回 code = 400（msg 为第一个错误，data 为 {字段: 原因}），不存在返回 404。
 * UDS 数据集没接入时取数接口照常返回结构，{@code dataPending = true}。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/dashboard/risk")
public class RiskAnalysisController extends BaseController
{
    static final String COND_EDIT = "dashboard:risk:cond:edit";

    private final RiskOptionsService options;

    private final RiskRankService rank;

    private final RiskMyBoardService myBoards;

    private final RiskGameService games;

    private final RiskUserService users;

    private final DashboardProperties properties;

    public RiskAnalysisController(RiskOptionsService options, RiskRankService rank, RiskMyBoardService myBoards,
        RiskGameService games, RiskUserService users, DashboardProperties properties)
    {
        this.options = options;
        this.rank = rank;
        this.myBoards = myBoards;
        this.games = games;
        this.users = users;
        this.properties = properties;
    }

    /** 筛选项与字典：游戏平台 / 品牌 / 类型 / 游戏、推广商 › 渠道、榜单定义、名单条件字段、列定义、风险类型 */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @GetMapping("/options")
    public AjaxResult options()
    {
        return success(options.options());
    }

    /** 读榜单条件（含修改人、时间；editable = 当前用户能否修改） */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @GetMapping("/rank/thresholds")
    public AjaxResult thresholds()
    {
        return success(options.thresholds(SecurityUtils.hasPermi(COND_EDIT)));
    }

    /** 改榜单条件：全站共用、对所有人的榜单生效 */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:cond:edit')")
    @Log(title = "风控分析 · 榜单条件", businessType = BusinessType.UPDATE)
    @PostMapping("/rank/thresholds")
    public AjaxResult saveThresholds(@RequestBody Map<String, Object> body)
    {
        options.saveThresholds(body, getUsername());
        return success(options.thresholds(true));
    }

    /** 榜单卡片：摘要、Top 5、占比档位 / 倍数分档 / 全部用户 RTP */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @Log(title = "风控分析 · 榜单卡片", businessType = BusinessType.QUERY, isSaveResponseData = false)
    @PostMapping("/rank/cards")
    public AjaxResult cards(@RequestBody RiskQuery query)
    {
        return success(rank.cards(query, getUserId()));
    }

    /** 完整名单（分页） */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @Log(title = "风控分析 · 完整名单", businessType = BusinessType.QUERY, isSaveResponseData = false)
    @PostMapping("/rank/list")
    public AjaxResult list(@RequestBody RiskQuery query)
    {
        return success(rank.list(query, getUserId()));
    }

    /** 导出完整名单（全部行） */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:export')")
    @Log(title = "风控分析 · 导出名单", businessType = BusinessType.EXPORT, isSaveResponseData = false)
    @PostMapping("/rank/export")
    public ResponseEntity<byte[]> export(@RequestBody RiskQuery query)
    {
        checkExportEnabled();
        RiskSheet sheet = rank.export(query, getUserId());
        return DashboardExportSupport.xlsx(sheet.fileName + ".xlsx", sheet.xlsx());
    }

    /** 名单内搜索（FY-67 R18）：body 同完整名单 + keyword；账号包含或用户 ID 开头，最多 8 条，带名次与所在页 */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @PostMapping("/rank/search")
    public AjaxResult searchInList(@RequestBody RiskQuery query)
    {
        return success(rank.search(query, getUserId()));
    }

    /** 渠道风险榜 · 我的风险类型（生效值与来源：个人 / 全局默认 / 系统默认） */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @GetMapping("/rank/risk-types")
    public AjaxResult riskTypes()
    {
        return success(options.riskTypes());
    }

    /** 保存我的风险类型：{ "types": [...] }；和全局默认一样或传空数组 = 跟随全局默认（恢復默認） */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @Log(title = "风控分析 · 渠道风险榜风险类型", businessType = BusinessType.UPDATE)
    @PostMapping("/rank/risk-types")
    public AjaxResult saveRiskTypes(@RequestBody Map<String, Object> body)
    {
        return success(options.saveRiskTypes(body));
    }

    // ------------------------------------------------------------------ 用户详情抽屉（FY-75）

    /** 全部用户搜索：{ "keyword": "..." }，账号包含或用户 ID 开头，最多 8 条 */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @PostMapping("/users/search")
    public AjaxResult searchUsers(@RequestBody RiskUserQuery query)
    {
        return success(users.search(query.getKeyword()));
    }

    /** 抽屉：抬头、时间范围、概況（指标 + 歷史累計 + 趋势）、页签数量 */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @Log(title = "风控分析 · 用户详情", businessType = BusinessType.QUERY, isSaveResponseData = false)
    @PostMapping("/users/detail")
    public AjaxResult userDetail(@RequestBody RiskUserQuery query)
    {
        return success(users.detail(query));
    }

    /** 抽屉 · 資金流水（存款 / 提款 / 优惠） */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @PostMapping("/users/funds")
    public AjaxResult userFunds(@RequestBody RiskUserQuery query)
    {
        return success(users.funds(query));
    }

    /** 抽屉 · 遊戲 */
    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @PostMapping("/users/games")
    public AjaxResult userGames(@RequestBody RiskUserQuery query)
    {
        return success(users.games(query));
    }

    // ------------------------------------------------------------------ 我的榜单

    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @GetMapping("/my-boards")
    public AjaxResult myBoards()
    {
        return success(myBoards.list(getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @Log(title = "风控分析 · 新建我的榜单", businessType = BusinessType.INSERT)
    @PostMapping("/my-boards")
    public AjaxResult createMyBoard(@RequestBody Map<String, Object> body)
    {
        return success(myBoards.create(body, getUserId(), getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @Log(title = "风控分析 · 修改我的榜单", businessType = BusinessType.UPDATE)
    @PostMapping("/my-boards/{id}/update")
    public AjaxResult updateMyBoard(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        myBoards.update(id, body, getUserId(), getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @Log(title = "风控分析 · 删除我的榜单", businessType = BusinessType.DELETE)
    @PostMapping("/my-boards/{id}/delete")
    public AjaxResult deleteMyBoard(@PathVariable Long id)
    {
        myBoards.delete(id, getUserId(), getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @Log(title = "风控分析 · 我的榜单排序", businessType = BusinessType.UPDATE)
    @PostMapping("/my-boards/reorder")
    public AjaxResult reorderMyBoards(@RequestBody Map<String, Object> body)
    {
        List<Long> ids = new ArrayList<>();
        if (body != null && body.get("ids") instanceof List<?> list)
        {
            for (Object o : list)
            {
                try
                {
                    ids.add(Long.valueOf(String.valueOf(o)));
                }
                catch (NumberFormatException e)
                {
                    throw com.fivetech.risk.RiskException.field("ids", "ids 應為榜單 id 數組");
                }
            }
        }
        myBoards.reorder(ids, getUserId());
        return success();
    }

    // ------------------------------------------------------------------ 游戏分析

    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @Log(title = "风控分析 · 游戏分析", businessType = BusinessType.QUERY, isSaveResponseData = false)
    @PostMapping("/games/table")
    public AjaxResult gameTable(@RequestBody RiskQuery query)
    {
        return success(games.table(query));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:risk:view')")
    @PostMapping("/games/detail")
    public AjaxResult gameDetail(@RequestBody RiskQuery query)
    {
        return success(games.detail(query));
    }

    @PreAuthorize("@ss.hasPermi('dashboard:risk:export')")
    @Log(title = "风控分析 · 导出游戏分析", businessType = BusinessType.EXPORT, isSaveResponseData = false)
    @PostMapping("/games/export")
    public ResponseEntity<byte[]> gameExport(@RequestBody RiskQuery query)
    {
        checkExportEnabled();
        RiskSheet sheet = games.export(query);
        return DashboardExportSupport.xlsx(sheet.fileName + ".xlsx", sheet.xlsx());
    }

    private void checkExportEnabled()
    {
        if (!properties.getExport().isEnabled())
        {
            throw new ServiceException("导出功能未开启");
        }
    }
}
