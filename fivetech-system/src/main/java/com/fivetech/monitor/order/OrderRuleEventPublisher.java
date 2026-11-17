package com.fivetech.monitor.order;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.fivetech.monitor.domain.MonitorRule;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.mapper.MonitorOrderRuleMapper;
import com.fivetech.monitor.service.impl.MonitorOrderRuleService;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单规则变更事件 → MSK。判定服务订阅后更新自己缓存的规则。
 *
 * <ul>
 *   <li>只发<b>启用中</b>的规则：启用中的规则新建、修改 → UPSERT（带完整规则快照）；
 *       暂停、删除 → DELETE（只带 ruleId，判定服务照此移除）。暂停中的规则被改不发（判定服务本来就没在用）；</li>
 *   <li><b>事务提交后</b>才发：数据库回滚时不会发出一条不存在的变更；</li>
 *   <li>消息 key = ruleId，同一条规则落同一分区，保证它自己的事件有序；</li>
 *   <li>发送失败只记 error 日志，<b>不影响业务</b>：判定服务按 {@code /open/monitor/order-rules?sinceVersion=} 定时增量兜底，
 *       漏一条事件下次轮询会补上。所以这里是「尽力通知」，不是唯一的同步通道；</li>
 *   <li>没配 {@code spring.kafka.bootstrap-servers} 时整个 Bean 不装配；即使装配了但容器里没有 KafkaTemplate，
 *       也只记一条 error 就跳过——<b>发消息这种可选功能绝不能让应用起不来</b>。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class OrderRuleEventPublisher
{
    private static final Logger log = LoggerFactory.getLogger(OrderRuleEventPublisher.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 启用中的规则新建或修改 */
    static final String UPSERT = "UPSERT";

    /** 规则被暂停或删除，判定服务不再用它 */
    static final String DELETE = "DELETE";

    /** 取不到 KafkaTemplate 时只记一次日志，不影响启动 */
    private final ObjectProvider<KafkaTemplate<String, String>> kafka;

    private volatile boolean warned;

    private final MonitorOrderRuleMapper mapper;

    /** 懒取：MonitorOrderRuleService 反过来也持有本类，构造期注入会成环 */
    private final ObjectProvider<MonitorOrderRuleService> orderRules;

    private final ObjectMapper json;

    /** 开关：配了也可以临时关掉，关掉后只记 debug 日志不发 */
    @Value("${monitor.order-rule.kafka.enabled:true}")
    private boolean enabled;

    @Value("${monitor.order-rule.kafka.topic:}")
    private String topic;

    public OrderRuleEventPublisher(ObjectProvider<KafkaTemplate<String, String>> kafka, MonitorOrderRuleMapper mapper,
            ObjectProvider<MonitorOrderRuleService> orderRules, ObjectMapper json)
    {
        this.kafka = kafka;
        this.mapper = mapper;
        this.orderRules = orderRules;
        this.json = json;
    }

    /**
     * 规则变更后登记一次推送；在事务里调用，真正发送发生在提交之后。
     * 没有事务时（不该出现）立即发送。
     */
    public void publish(MonitorScope scope, Long ruleId)
    {
        if (!enabled || topic == null || topic.isBlank() || scope == null || ruleId == null)
        {
            return;
        }
        String site = scope.getSiteCode();
        if (!TransactionSynchronizationManager.isSynchronizationActive())
        {
            send(scope, site, ruleId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override
            public void afterCommit()
            {
                send(scope, site, ruleId);
            }
        });
    }

    /** 按规则此刻的状态决定发 UPSERT 还是 DELETE */
    private void send(MonitorScope scope, String site, Long ruleId)
    {
        try
        {
            // 查不到 = 已逻辑删除（selectOrderRuleById 只看 del_flag = '0'）
            MonitorRule rule = mapper.selectOrderRuleById(scope, ruleId);
            boolean gone = rule == null || !Boolean.TRUE.equals(rule.getEnabled());
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", gone ? DELETE : UPSERT);
            event.put("siteCode", site);
            event.put("ruleId", ruleId);
            event.put("occurredAt", LocalDateTime.now().format(TIME));
            if (gone)
            {
                // 已删除时版本号另查（上面那条 SQL 看不到已删除的行）
                MonitorRule state = mapper.selectOrderRuleStateById(scope, ruleId);
                if (state != null)
                {
                    event.put("version", state.getVersion());
                    event.put("deleted", !"0".equals(state.getDelFlag()));
                }
            }
            else
            {
                event.put("version", rule.getVersion());
                event.put("deleted", false);
                event.put("rule", orderRules.getObject().view(rule, site));
            }
            KafkaTemplate<String, String> template = kafka.getIfAvailable();
            if (template == null)
            {
                // 少了 spring-boot-starter-kafka 之类的情况：只记一次，绝不影响业务
                if (!warned)
                {
                    warned = true;
                    log.error("[order-rule] 没有 KafkaTemplate，规则变更事件不会发送；"
                        + "判定服务仍可用 /open/monitor/order-rules?sinceVersion= 增量拉取");
                }
                return;
            }
            String payload = json.writeValueAsString(event);
            template.send(topic, String.valueOf(ruleId), payload).whenComplete((r, e) -> {
                if (e != null)
                {
                    log.error("[order-rule] 事件发送失败 topic={} ruleId={} type={}：{}", topic, ruleId, event.get("type"),
                        e.getMessage(), e);
                }
                else
                {
                    log.info("[order-rule] 事件已发送 topic={} ruleId={} type={} version={} partition={} offset={}", topic, ruleId,
                        event.get("type"), event.get("version"),
                        r.getRecordMetadata().partition(), r.getRecordMetadata().offset());
                }
            });
        }
        catch (RuntimeException e)
        {
            // 发事件失败不能影响已经提交的业务；判定服务轮询会补
            log.error("[order-rule] 事件组装或发送失败 ruleId={}：{}", ruleId, e.getMessage(), e);
        }
    }
}
