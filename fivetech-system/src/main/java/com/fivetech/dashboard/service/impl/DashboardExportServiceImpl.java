package com.fivetech.dashboard.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.core.redis.RedisCache;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.common.utils.TraceIdUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.config.ExportProperties;
import com.fivetech.dashboard.domain.query.BetRecordQuery;
import com.fivetech.dashboard.domain.query.DepositRecordQuery;
import com.fivetech.dashboard.domain.query.MemberRecordQuery;
import com.fivetech.dashboard.domain.query.WithdrawRecordQuery;
import com.fivetech.dashboard.domain.vo.ExportTaskVO;
import com.fivetech.dashboard.gateway.ExportJob;
import com.fivetech.dashboard.gateway.MetricDataGateway;
import com.fivetech.dashboard.gateway.RecordPageRequest;
import com.fivetech.dashboard.gateway.uds.UdsQueryException;
import com.fivetech.dashboard.service.IDashboardExportService;
import com.fivetech.dashboard.service.IRecordQueryService;
import com.fivetech.system.domain.SysOperLog;
import com.fivetech.system.service.ISysOperLogService;

/**
 * 明细异步导出实现。
 * <p>
 * <b>归属必须由本系统自己管</b>：调用数据平台时所有用户共用同一个服务身份（X-Uds-Principal），
 * 平台只能区分「本系统」而分不出本系统里的哪个用户。所以提交成功后把 jobId 记到
 * 「用户 → 作业」的 Redis 记录里，查询 / 取消前先校验这条记录，不存在就当作业不存在。
 * 同一份导出被两个用户各自提交时，平台去重返回同一个 jobId，两人各有一条记录，互不影响。
 *
 * @author fivetech
 */
@Service
public class DashboardExportServiceImpl implements IDashboardExportService
{
    private static final Logger log = LoggerFactory.getLogger(DashboardExportServiceImpl.class);

    /** 与平台文件保留期一致：7 天后文件已被删除，记录留着也没用 */
    private static final int TASK_TTL_DAYS = 7;

    private static final String TASK_KEY = "dashboard:export:task:";

    private static final String USER_KEY = "dashboard:export:user:";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern RETRY_AFTER = Pattern.compile("\"retryAfterMillis\"\\s*:\\s*(\\d+)");

    private static final Pattern HINT = Pattern.compile("\"hint\"\\s*:\\s*\"([^\"]*)\"");

    private final DashboardProperties properties;

    private final IRecordQueryService recordQueryService;

    private final MetricDataGateway gateway;

    private final RedisCache redisCache;

    private final ISysOperLogService operLogService;

    private final com.fivetech.dashboard.gateway.uds.UdsProperties udsProperties;

    public DashboardExportServiceImpl(DashboardProperties properties, IRecordQueryService recordQueryService,
            MetricDataGateway gateway, RedisCache redisCache, ISysOperLogService operLogService,
            com.fivetech.dashboard.gateway.uds.UdsProperties udsProperties)
    {
        this.udsProperties = udsProperties;
        this.properties = properties;
        this.recordQueryService = recordQueryService;
        this.gateway = gateway;
        this.redisCache = redisCache;
        this.operLogService = operLogService;
    }

    // ===================== 提交 =====================

    @Override
    public ExportTaskVO submitMembers(MemberRecordQuery query)
    {
        precheck();
        RecordPageRequest request = recordQueryService.buildMemberRequest(query);
        return accept("member", "会员明细", () -> gateway.submitDetailExport("member", request, maxRows()));
    }

    @Override
    public ExportTaskVO submitDeposits(DepositRecordQuery query)
    {
        precheck();
        RecordPageRequest request = recordQueryService.buildDepositRequest(query);
        return accept("deposit", "存款明细", () -> gateway.submitDetailExport("deposit", request, maxRows()));
    }

    @Override
    public ExportTaskVO submitWithdrawals(WithdrawRecordQuery query)
    {
        precheck();
        RecordPageRequest request = recordQueryService.buildWithdrawRequest(query);
        return accept("withdraw", "提款明细", () -> gateway.submitDetailExport("withdraw", request, maxRows()));
    }

    @Override
    public ExportTaskVO submitBets(BetRecordQuery query)
    {
        precheck();
        RecordPageRequest request = recordQueryService.buildBetRequest(query);
        return accept("bet", "投注明细", () -> gateway.submitDetailExport("bet", request, maxRows()));
    }

    /** 明细同步导出受 UDS offset ≤ 100000 限制，再大的 max-rows 也取不到 */
    private static final long SYNC_HARD_LIMIT = 100_000L;

    private static final Map<String, String> DETAIL_LABELS = Map.of(
        "member", "会员明细", "deposit", "存款明细", "withdraw", "提款明细", "bet", "投注明细");

    @Override
    public ExportTaskVO submitIfOverLimit(String tab, RecordPageRequest request)
    {
        ExportProperties export = properties.getExport();
        if (!export.isEnabled())
        {
            throw new ServiceException("导出功能未开启");
        }
        long limit = Math.min(Math.max(1, export.getMaxRows()), SYNC_HARD_LIMIT);

        // 1. explain：只编译不执行，开销最小。它按底表估算、不看筛选条件，是结果总数的上界——
        //    不超过上限就一定能同步导完；explain 失败不影响导出，继续用 count 判断
        try
        {
            Long scan = gateway.explainDetailScanRows(tab, request);
            if (scan != null && scan <= limit)
            {
                log.info("[export] {} 预估扫描 {} 行 ≤ {}，走同步导出", tab, scan, limit);
                return null;
            }
        }
        catch (UdsQueryException e)
        {
            log.warn("[export] {} explain 失败（{}），改用 count 判断", tab, e.getMessage());
        }

        // 2. 扫描量超过上限不代表结果多（如加了账号筛选），再用同条件 COUNT 取准确总数
        Long total;
        try
        {
            total = gateway.countDetailRows(tab, request);
        }
        catch (UdsQueryException e)
        {
            throw translate(e);
        }
        if (total != null && total <= limit)
        {
            log.info("[export] {} 结果 {} 行 ≤ {}，走同步导出", tab, total, limit);
            return null;
        }

        // 3. 超过上限：转异步导出，文件由数据平台生成，前端轮询任务状态后直连下载
        log.info("[export] {} 结果 {} 行 > {}，转异步导出", tab, total, limit);
        String label = DETAIL_LABELS.getOrDefault(tab, tab);
        return accept(tab, label, () -> gateway.submitDetailExport(tab, request, maxRows()));
    }

    @Override
    public ExportTaskVO submitBenchmark(String merchantCode, boolean detailRows, long maxRows)
    {
        precheck();
        String label = "压测导出_" + (StringUtils.isEmpty(merchantCode) ? "ALL" : merchantCode)
            + (detailRows ? "_明细" : "_汇总");
        return accept("benchmark", label, () -> gateway.submitBenchmarkExport(merchantCode, detailRows, maxRows));
    }

    private long maxRows()
    {
        return properties.getExport().getAsyncMaxRows();
    }

    /** 提交、登记归属、写审计 */
    private ExportTaskVO accept(String kind, String label, java.util.function.Supplier<ExportJob> submit)
    {
        ExportJob job;
        try
        {
            job = submit.get();
        }
        catch (UdsQueryException e)
        {
            throw translate(e);
        }
        if (StringUtils.isEmpty(job.getJobId()))
        {
            throw new ServiceException("数据平台未返回作业编号，请稍后重试");
        }
        Long userId = SecurityUtils.getUserId();
        String submittedAt = LocalDateTime.now().format(HUMAN);
        Map<String, String> record = new LinkedHashMap<>();
        record.put("kind", kind);
        record.put("label", label);
        record.put("submittedAt", submittedAt);
        // 只存文件名主干，扩展名按平台实际生成的文件决定（见 fileNames）
        record.put("fileBase", label + "_" + LocalDateTime.now().format(STAMP));
        String key = taskKey(userId, job.getJobId());
        redisCache.setCacheMap(key, record);
        redisCache.expire(key, TASK_TTL_DAYS, TimeUnit.DAYS);
        String userKey = USER_KEY + userId;
        Set<String> ids = redisCache.getCacheSet(userKey);
        Set<String> merged = new java.util.LinkedHashSet<>(ids == null ? Set.of() : ids);
        merged.add(job.getJobId());
        redisCache.deleteObject(userKey);
        redisCache.setCacheSet(userKey, merged);
        redisCache.expire(userKey, TASK_TTL_DAYS, TimeUnit.DAYS);

        audit(label, job);
        return toVO(record, job);
    }

    // ===================== 查询 / 取消 / 列表 =====================

    @Override
    public ExportTaskVO getTask(String jobId)
    {
        Map<String, String> record = ownRecord(jobId);
        try
        {
            return toVO(record, gateway.getExportJob(jobId));
        }
        catch (UdsQueryException e)
        {
            throw translate(e);
        }
    }

    @Override
    public ExportTaskVO cancelTask(String jobId)
    {
        Map<String, String> record = ownRecord(jobId);
        try
        {
            return toVO(record, gateway.cancelExportJob(jobId));
        }
        catch (UdsQueryException e)
        {
            throw translate(e);
        }
    }

    @Override
    public List<ExportTaskVO> listMyTasks()
    {
        Long userId = SecurityUtils.getUserId();
        Set<String> ids = redisCache.getCacheSet(USER_KEY + userId);
        List<ExportTaskVO> list = new ArrayList<>();
        if (ids == null)
        {
            return list;
        }
        for (String id : ids)
        {
            Map<String, String> record = redisCache.getCacheMap(taskKey(userId, id));
            if (record == null || record.isEmpty())
            {
                continue;   // 已过期
            }
            // 列表不逐个查平台（N 次远程调用），前端点开某一条再查实时状态
            ExportTaskVO vo = toVO(record, null);
            vo.setJobId(id);
            list.add(vo);
        }
        list.sort(Comparator.comparing(ExportTaskVO::getSubmittedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return list.size() > 20 ? list.subList(0, 20) : list;
    }

    private Map<String, String> ownRecord(String jobId)
    {
        if (StringUtils.isEmpty(jobId))
        {
            throw new ServiceException("缺少任务编号");
        }
        Map<String, String> record = redisCache.getCacheMap(taskKey(SecurityUtils.getUserId(), jobId));
        if (record == null || record.isEmpty())
        {
            // 不区分「不存在」和「不是你的」，避免被用来探测别人的任务
            throw new ServiceException("导出任务不存在或已过期，请重新提交");
        }
        return record;
    }

    // ===================== 公共 =====================

    private void precheck()
    {
        ExportProperties export = properties.getExport();
        if (!export.isEnabled())
        {
            throw new ServiceException("导出功能未开启");
        }

        if (export.isRequirePermission() && !SecurityUtils.hasPermi(export.getPermission()))
        {
            throw new ServiceException("没有导出权限，请联系管理员授权");
        }
    }

    /** 数据平台业务错误 → 给用户看得懂的提示；其余照常抛出，由全局处理器告警 */
    private RuntimeException translate(UdsQueryException e)
    {
        String body = e.getResponseBody();
        switch (e.getUdsCode())
        {
            case 4402:
                return new ServiceException("时间范围超出可导出的数据范围，请缩小区间后重试");
            case 4403:
                String hint = match(HINT, body);
                return new ServiceException("导出通道不可用" + (hint == null ? "" : "：" + hint));
            case 4406:
                // 跨 principal 访问或作业已过期（7 天）
                return new ServiceException("导出任务不存在或已过期，请重新提交");
            case 4407:
                // 接入说明：对已结束（DONE）的作业执行取消返回 400/4407
                return new ServiceException("任务已结束，无法取消；如已完成可直接下载");
            case 4290:
                // TODO：接入说明未给出「导出并发超限」的错误码，确认后在这里单独给出提示
                String retry = match(RETRY_AFTER, body);
                return new ServiceException(retry == null ? "请求过于频繁，请稍后重试"
                    : "请求过于频繁，请 " + Math.max(1, Long.parseLong(retry) / 1000) + " 秒后重试");
            default:
                return e;
        }
    }

    private static String match(Pattern pattern, String text)
    {
        if (text == null)
        {
            return null;
        }
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private ExportTaskVO toVO(Map<String, String> record, ExportJob job)
    {
        ExportTaskVO vo = new ExportTaskVO();
        vo.setKind(record.get("kind"));
        vo.setLabel(record.get("label"));
        vo.setSubmittedAt(record.get("submittedAt"));
        String base = fileBase(record);
        vo.setSuggestedFileName(base + extensionOf(job == null || job.getFiles().isEmpty() ? null : job.getFiles().get(0).getKey()));
        vo.setJobId(job == null ? null : job.getJobId());
        if (job != null)
        {
            // 多文件时逐个命名：xxx_part1.csv.gz、xxx_part2.csv.gz……
            int n = job.getFiles().size();
            for (int i = 0; i < n; i++)
            {
                ExportJob.ExportFile f = job.getFiles().get(i);
                f.setFileName(base + (n > 1 ? "_part" + (i + 1) : "") + extensionOf(f.getKey()));
            }
        }
        vo.setJob(job);
        return vo;
    }

    private String taskKey(Long userId, String jobId)
    {
        return TASK_KEY + userId + ":" + jobId;
    }

    /** 文件名主干；兼容旧记录（旧记录存的是带扩展名的 fileName） */
    private static String fileBase(Map<String, String> record)
    {
        String base = record.get("fileBase");
        if (StringUtils.isNotEmpty(base))
        {
            return base;
        }
        String old = record.get("fileName");
        if (StringUtils.isEmpty(old))
        {
            return "export";
        }
        int dot = old.indexOf('.');
        return dot > 0 ? old.substring(0, dot) : old;
    }

    /**
     * 扩展名优先取平台实际生成的文件（对象 key 的后缀，如 .csv.gz / .parquet），
     * 取不到时按导出配置推断。不再写死 .csv.gz——格式是可配置的。
     */
    private String extensionOf(String key)
    {
        if (StringUtils.isNotEmpty(key))
        {
            String name = key.substring(key.lastIndexOf('/') + 1);
            int dot = name.indexOf('.');
            if (dot > 0 && name.length() - dot <= 12)
            {
                return name.substring(dot).toLowerCase();
            }
        }
        String format = udsProperties.getExportFormat() == null ? "CSV" : udsProperties.getExportFormat().toUpperCase();
        String ext = "PARQUET".equals(format) ? ".parquet" : "." + format.toLowerCase();
        String compression = udsProperties.getExportCompression() == null ? "" : udsProperties.getExportCompression().toUpperCase();
        // PARQUET 的 SNAPPY / ZSTD 是文件内部压缩，不改扩展名；只有 CSV 这类文本格式整体压缩
        if (!"PARQUET".equals(format))
        {
            if ("GZIP".equals(compression))
            {
                ext += ".gz";
            }
            else if ("ZSTD".equals(compression))
            {
                ext += ".zst";
            }
        }
        return ext;
    }

    /**
     * 导出审计：明细含个人信息，谁在什么时候提交了哪次导出必须可追溯。
     * 这里记录提交；文件由浏览器直连对象存储下载，本系统看不到下载动作。
     */
    private void audit(String label, ExportJob job)
    {
        try
        {
            SysOperLog operLog = new SysOperLog();
            operLog.setTitle("仪表板异步导出 · " + label);
            operLog.setBusinessType(BusinessType.EXPORT.ordinal());
            operLog.setOperatorType(1);
            operLog.setOperName(SecurityUtils.getUsername());
            operLog.setRequestMethod("POST");
            operLog.setStatus(0);
            operLog.setOperTime(new Date());
            operLog.setOperParam("traceId=" + TraceIdUtils.get() + ", jobId=" + job.getJobId()
                + ", deduplicated=" + job.isDeduplicated());
            operLog.setJsonResult("已提交，状态 " + job.getStatus());
            operLogService.insertOperlog(operLog);
        }
        catch (Exception e)
        {
            log.warn("异步导出审计写入失败: {}", e.getMessage());
        }
    }
}
