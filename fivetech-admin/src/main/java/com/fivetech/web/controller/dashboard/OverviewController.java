package com.fivetech.web.controller.dashboard;

import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.config.ExportProperties;
import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.dashboard.export.OverviewXlsxExporter;
import com.fivetech.dashboard.export.OverviewWorkbookExporter;
import com.fivetech.dashboard.domain.query.OverviewExportQuery;
import com.fivetech.dashboard.domain.query.CohortQuery;
import com.fivetech.dashboard.domain.query.RankingBoardQuery;
import com.fivetech.dashboard.domain.query.RegChannelQuery;
import com.fivetech.dashboard.service.IOverviewSectionService;
import com.fivetech.dashboard.service.IOverviewService;
import com.fivetech.system.domain.SysOperLog;
import com.fivetech.system.service.ISysOperLogService;

/**
 * 运营总览接口：主要指标（/overview）、排行榜（/leaderboard，兼容 /rankingboard）、留存与 LTV（/cohort）、
注册渠道（/reg-channels）。
 *
 * <p>当前只有指标墙一块：{@code blocks: ["METRICS"]}，不传时默认返回它。</p>
 *
 * <p>时间语义<b>左闭右开</b>：{@code slotFrom=09:00, slotTo=10:00, HOUR} 是 1 个时间片。
 * 必须这么定，否则 {@code 09:00 ~ 09:00} 到底是 0 个点还是 1 个点没有答案。
 * UDS 的 {@code to} 是闭区间，换算在网关内部完成，业务层只看左闭右开。</p>
 *
 * <p><b>导出与查询共用这一个接口</b>：{@code export=true} 时返回 xlsx 文件流，
 * 否则返回 JSON。之所以敢合并，是因为总览的数据量很小（20 张卡 × 数百个时间片），
 * 文件能在内存里整份生成完再写第一个响应字节——生成途中失败仍然能回 JSON 错误体。
 * 明细导出数据量大、必须边查边写，所以仍然走任务中心，不套用这里的做法。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/dashboard/metrics")
public class OverviewController extends BaseController
{
    private static final Logger log = LoggerFactory.getLogger(OverviewController.class);

    private final IOverviewService overviewService;

    private final IOverviewSectionService sectionService;

    private final OverviewXlsxExporter xlsxExporter;

    private final DashboardProperties properties;

    private final ISysOperLogService operLogService;

    private final OverviewWorkbookExporter workbookExporter;

    public OverviewController(IOverviewService overviewService, IOverviewSectionService sectionService,
            OverviewXlsxExporter xlsxExporter, DashboardProperties properties, ISysOperLogService operLogService,
            OverviewWorkbookExporter workbookExporter)
    {
        this.workbookExporter = workbookExporter;
        this.overviewService = overviewService;
        this.sectionService = sectionService;
        this.xlsxExporter = xlsxExporter;
        this.properties = properties;
        this.operLogService = operLogService;
    }

    /**
     * 查询指标墙；{@code export=true} 时下载 xlsx。
     *
     * <p>查询需要 {@code dashboard:overview:metrics}；能查询就能导出，{@code export=true} 时只校验导出总开关，
     * 并写入操作日志审计。</p>
     */
    @PreAuthorize("@ss.hasPermi('dashboard:overview:metrics')")
    @PostMapping("/overview")
    public ResponseEntity<?> overview(@Validated @RequestBody OverviewQuery query)
    {
        if (query.isExport())
        {
            checkExportEnabled();
        }

        OverviewVO data = overviewService.query(query);

        if (!query.isExport())
        {
            return ResponseEntity.ok(AjaxResult.success(data));
        }

        byte[] file = xlsxExporter.export(data);
        audit(query, file.length);
        return DashboardExportSupport.xlsx(xlsxExporter.fileName(data), file);
    }


    /**
     * 排行榜：赠金项目构成 + 热销游戏 Top N。区间聚合，不做环比。
     *
     * 前端按 leaderboard 调用，两个路径都映射到这里，以 leaderboard 为准。</p>
     */
    @PreAuthorize("@ss.hasPermi('dashboard:overview:leaderboard')")
    @PostMapping({"/rankingboard"})
    public AjaxResult rankingBoard(@Validated @RequestBody RankingBoardQuery query)
    {
        com.fivetech.dashboard.domain.vo.overview.RankingBoardVO vo = sectionService.rankingBoard(query);
        // 分析区展示精度：金额取整、百分数 1 位小数（导出直接调 service，保留两位）
        if (vo.getBonus() != null)
        {
            vo.getBonus().applyViewScale();
        }
        if (vo.getGames() != null)
        {
            vo.getGames().applyViewScale();
        }
        return AjaxResult.success(vo);
    }

    /**
     * 留存与 LTV：按首投日 / 首存日分群的两张 T-1 队列矩阵。
     */
    @PreAuthorize("@ss.hasPermi('dashboard:overview:view')")
    @PostMapping("/cohort")
    public AjaxResult cohort(@Validated @RequestBody CohortQuery query)
    {
        com.fivetech.dashboard.domain.vo.overview.CohortVO vo = sectionService.cohort(query);
        // 分析区展示精度：LTV 取整、留存率 1 位小数（导出直接调 service，保留两位）
        if (vo.getRetention() != null)
        {
            vo.getRetention().applyViewScale();
        }
        if (vo.getLtv() != null)
        {
            vo.getLtv().applyViewScale();
        }
        return AjaxResult.success(vo);
    }

    /**
     * 注册渠道：渠道分组 + 全部渠道，筛选在前端完成。
     */
    @PreAuthorize("@ss.hasPermi('dashboard:overview:view')")
    @PostMapping("/reg-channels")
    public AjaxResult regChannels(@Validated @RequestBody RegChannelQuery query)
    {
        com.fivetech.dashboard.domain.vo.overview.RegChannelVO vo = sectionService.regChannels(query);
        // 分析区展示精度：占比 1 位小数（导出直接调 service，保留两位）
        vo.getGroups().forEach(com.fivetech.dashboard.domain.vo.overview.RegChannelGroupVO::applyViewScale);
        vo.getChannels().forEach(com.fivetech.dashboard.domain.vo.overview.RegChannelItemVO::applyViewScale);
        return AjaxResult.success(vo);
    }

    /**
     * 运营总览整页导出：四个接口、六个子页面汇成一个 xlsx，一个子页面一张 sheet。
     *
     * <p>sheet 名与原型一致：核心指标 / 注册渠道 / 赠金项目结构 / 热销游戏 / 留存率 / LTV。
     * 任一块取数失败时对应 sheet 写明原因，其余照常导出。</p>
     */
    @PreAuthorize("@ss.hasPermi('dashboard:overview:metrics')")
    @PostMapping("/overview/export")
    public ResponseEntity<?> exportOverview(@Validated @RequestBody OverviewExportQuery query)
    {
        checkExportEnabled();
        byte[] file = workbookExporter.export(query);
        audit("运营总览整页导出", "/dashboard/metrics/overview/export",
            "slot=" + query.getSlotFrom() + "~" + query.getSlotTo() + ", granularity=" + query.getGranularity()
                + ", compareType=" + query.getCompareType() + ", cohort=" + query.getCohortFrom() + "~" + query.getCohortTo(),
            file.length);
        return DashboardExportSupport.xlsx(workbookExporter.fileName(query), file);
    }

    /** 能查询就能导出，这里只看导出总开关 */
    private void checkExportEnabled()
    {
        ExportProperties export = properties.getExport();
        if (!export.isEnabled())
        {
            throw new ServiceException("导出功能未开启");
        }
    }

    /**
     * 导出审计：谁在什么时候把哪个区间的数据带出了系统必须可追溯。
     *
     * <p>这里手写而不是用 {@code @Log} 注解：注解按方法记录，会把查询和导出混在一条
     * 业务类型里，导出审计就失去意义了。审计失败只记警告，不能让已经生成好的文件下不下来。</p>
     */
    private void audit(OverviewQuery query, int bytes)
    {
        audit("运营总览导出", "/dashboard/metrics/overview", "slot=" + query.getSlotFrom() + "~" + query.getSlotTo()
            + ", granularity=" + query.getGranularity() + ", blocks=" + query.getBlocks(), bytes);
    }

    private void audit(String title, String url, String param, int bytes)
    {
        try
        {
            SysOperLog operLog = new SysOperLog();
            operLog.setTitle(title);
            operLog.setBusinessType(BusinessType.EXPORT.ordinal());
            operLog.setOperatorType(1);
            operLog.setOperName(SecurityUtils.getUsername());
            operLog.setRequestMethod("POST");
            operLog.setOperUrl(url);
            operLog.setStatus(0);
            operLog.setOperTime(new Date());
            operLog.setOperParam(param);
            operLog.setJsonResult("xlsx " + bytes + " bytes");
            operLogService.insertOperlog(operLog);
        }
        catch (Exception e)
        {
            log.warn("运营总览导出审计写入失败: {}", e.getMessage());
        }
    }
}
