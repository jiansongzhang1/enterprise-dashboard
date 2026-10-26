package com.fivetech.monitor.engine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkResult;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.HourlyFetch;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.domain.MonitorNotifyTemplate;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.MonitorJudge;
import com.fivetech.monitor.engine.judge.PeriodPoint;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.mapper.MonitorEngineMapper;
import com.fivetech.monitor.service.MonitorNotifyTemplateService;

/**
 * 管理后台「手动触发测试告警」：取一条规则最近一个已结束周期的真实数据，按线上模板生成「严重」告警，
 * 开头加「【測試】」，发到 monitor.lark.test-chat-id（环境变量 MONITOR_TEST_CHAT_ID）配置的固定测试群。
 *
 * <p>只用于看消息格式和数据对不对：不写事件、不写发送记录、不改规则运行状态，不受静默期限制，
 * 也不管这一周期实际是否越线（返回里的 level 是按真实数据判定的结果，供对照）。规则暂停也可以触发。</p>
 *
 * @author fivetech
 */
@Service
public class MonitorTestAlertService
{
    private static final Logger log = LoggerFactory.getLogger(MonitorTestAlertService.class);

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static final String TEST_PREFIX = "【測試】";

    private final MonitorEngineMapper mapper;

    private final MonitorDataFetcher fetcher;

    private final MonitorPeriodBuilder periodBuilder;

    private final MonitorMessageBuilder messages;

    private final MonitorNotifyTemplateService templates;

    private final LarkClient larkClient;

    private final MonitorProperties properties;

    private final ZoneId zone;

    public MonitorTestAlertService(MonitorEngineMapper mapper, MonitorDataFetcher fetcher, MonitorPeriodBuilder periodBuilder,
            MonitorMessageBuilder messages, MonitorNotifyTemplateService templates, LarkClient larkClient,
            MonitorProperties properties, DashboardProperties dashboard)
    {
        this.mapper = mapper;
        this.fetcher = fetcher;
        this.periodBuilder = periodBuilder;
        this.messages = messages;
        this.templates = templates;
        this.larkClient = larkClient;
        this.properties = properties;
        this.zone = ZoneId.of(dashboard.getTimezone());
    }

    /**
     * @return { success, messageId, error, chatId, ruleId, ruleCode, metricCode, periodFrom, periodTo, value, baseline,
     *         level, dataError, content, templateId, templateVersion }
     */
    public Map<String, Object> trigger(MonitorScope scope, Long ruleId, String operator)
    {
        MonitorProperties.Lark lark = properties.getLark();
        String chatId = StringUtils.trim(lark.getTestChatId());
        if (StringUtils.isEmpty(chatId))
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "未配置測試群（環境變量 MONITOR_TEST_CHAT_ID）");
        }
        if (StringUtils.isEmpty(lark.getAppId()) || StringUtils.isEmpty(lark.getAppSecret()))
        {
            throw new MonitorException(MonitorErrorCode.LARK_CREDENTIAL_INVALID, "Lark 應用憑證未配置");
        }
        MonitorEngineRule rule = ruleId == null ? null : mapper.selectEngineRule(scope, ruleId);
        if (rule == null)
        {
            throw MonitorException.notFound("規則不存在");
        }
        JudgeRule judge = MonitorEventWriter.toJudgeRule(rule);
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime[] period = latestPeriod(now, judge.getPeriodHours());

        // 取数：失败或没就绪时照样发，数值显示「—」，返回 dataError 说明原因
        PeriodPoint point;
        String dataError = null;
        try
        {
            HourlyFetch data = fetcher.fetch(List.of(rule), period[0], period[1]);
            if (data == null || data.isNotReady())
            {
                dataError = "資料平台數據未就緒";
                point = new PeriodPoint(period[0], period[1], null, null, null, true);
            }
            else
            {
                point = periodBuilder.build(rule, data, period[0], period[1]);
                if (point.isMissing())
                {
                    dataError = "該週期沒有數據";
                }
            }
        }
        catch (RuntimeException e)
        {
            dataError = "取數失敗：" + StringUtils.substring(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 0, 200);
            point = new PeriodPoint(period[0], period[1], null, null, null, true);
            log.warn("[monitor] 手动触发测试告警取数失败 site={} rule={}：{}", scope.getSiteCode(), ruleId, e.getMessage());
        }
        String level = MonitorJudge.periodLevel(judge, point);

        List<BigDecimal[]> recent = new ArrayList<>();
        recent.add(new BigDecimal[] { point.getValue(), point.getBaseline() });
        Map<String, String> values = messages.alertValues(rule, judge, false, period[0], recent, now);
        MonitorNotifyTemplateService.Rendered rendered = templates.render(scope.getSiteCode(), MonitorNotifyTemplate.ALERT_NEW, values);
        String content = TEST_PREFIX + rendered.getContent();

        LarkResult<String> r = larkClient.sendPost(lark.getAppId(), lark.getAppSecret(), "chat_id", chatId, "",
                MonitorLarkPost.paragraphs(content));
        String error = r.isSuccess() ? null : MonitorNotifySender.reason(r, false);
        log.info("[monitor] 手动触发测试告警 site={} rule={} metric={} period={}~{} level={} by={} → {}", scope.getSiteCode(),
                ruleId, rule.getMetricCode(), period[0], period[1], level, operator, r.isSuccess() ? "已发送 " + r.getData() : error);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", r.isSuccess());
        out.put("messageId", r.isSuccess() ? r.getData() : null);
        out.put("error", error);
        out.put("chatId", chatId);
        out.put("ruleId", rule.getRuleId());
        out.put("ruleCode", values.get("ruleCode"));
        out.put("metricCode", rule.getMetricCode());
        out.put("periodFrom", period[0].format(MINUTE));
        out.put("periodTo", period[1].format(MINUTE));
        out.put("value", point.getValue());
        out.put("baseline", point.getBaseline());
        out.put("level", level);
        out.put("dataError", dataError);
        out.put("content", content);
        out.put("templateId", rendered.getTemplateId());
        out.put("templateVersion", rendered.getTemplateVersion());
        return out;
    }

    /**
     * 最近一个已结束的检测周期 [from, to)：周期最后一个小时 H 满足 (H.hour + 1) % periodHours == 0，且 H + 1 ≤ 当前整点。
     */
    static LocalDateTime[] latestPeriod(LocalDateTime now, int periodHours)
    {
        int p = Math.max(1, periodHours);
        LocalDateTime h = now.truncatedTo(ChronoUnit.HOURS).minusHours(1);
        while ((h.getHour() + 1) % p != 0)
        {
            h = h.minusHours(1);
        }
        return new LocalDateTime[] { h.minusHours(p - 1L), h.plusHours(1) };
    }
}
