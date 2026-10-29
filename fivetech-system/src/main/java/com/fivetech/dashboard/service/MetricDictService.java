package com.fivetech.dashboard.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.MetricDictItem;
import com.fivetech.dashboard.gateway.uds.UdsProperties;
import com.fivetech.dashboard.mapper.MetricDictMapper;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;

/**
 * 系统管理 · 指标管理：每个指标的口径与取数方式收口在 dashboard_metric_card。
 *
 * <p><b>不改线上取数</b>：向 UDS 取哪个字段仍以 application.yml（dashboard.gateway.uds）为准，跌幅基准以
 * monitor.engine.baseline-fields 为准；本服务只读这些配置用来对照（{@link MetricDictItem#getConfigMismatch()}）。</p>
 * <ul>
 *   <li>已接入（status = '0'）：能改口径（remark）、UDS 上游原始表 / 字段、四个 UDS 字段列，以及名称、英文名、分组、数值格式
 *       （2026-10-27 放开）。名称、分组、格式保存后立即重新加载 {@link MetricRegistry}，运营总览、指标汇总、告警规则、
 *       异常事件、Lark 消息都用这里的值；其他实例由 {@link MetricRegistryRefresher} 定时发现后重载。
 *       有告警规则（含暂停）的指标不能改数值格式（阈值按格式的单位填写）；漲跌方向、更新频率不能改；不能删除；</li>
 *   <li>仅登记（status = '1'，后台新增的都是）：运营总览、指标汇总不加载、不能建告警规则；
 *       还能改名称、分组、格式、方向、更新频率；没有任何引用时可以删除。要接入取数需另改配置与 SQL。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class MetricDictService
{
    public static final String STATUS_LIVE = "0";

    public static final String STATUS_REGISTERED = "1";

    static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    static final Pattern FIELD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    static final Pattern TABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]{0,127}$");

    static final Set<String> FORMATS = new HashSet<>(Arrays.asList("INT", "MONEY", "PCT", "MIN", "MULTIPLE"));

    static final Set<String> DIRECTIONS = new HashSet<>(Arrays.asList("up", "down", "flat", "range"));

    static final Set<String> FREQUENCIES = new HashSet<>(Arrays.asList("realtime", "hour", "day"));

    /** 四个 UDS 字段列：属性名 → 显示名（对照不一致时用） */
    static final Map<String, String> UDS_FIELDS = new LinkedHashMap<>();

    static
    {
        UDS_FIELDS.put("udsHourField", "小時數據集字段");
        UDS_FIELDS.put("udsDayField", "日數據集字段");
        UDS_FIELDS.put("udsAmountField", "金額數據集字段");
        UDS_FIELDS.put("udsBaselineField", "跌幅基準列");
    }

    private final MetricDictMapper mapper;

    private final UdsProperties uds;

    private final MonitorProperties monitor;

    private final MetricRegistry registry;

    public MetricDictService(MetricDictMapper mapper, UdsProperties uds, MonitorProperties monitor, MetricRegistry registry)
    {
        this.mapper = mapper;
        this.uds = uds;
        this.monitor = monitor;
        this.registry = registry;
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 列表：{ rows, groups, datasets }。keyword 匹配编码、名称、英文名、口径；status = 0 已接入 / 1 仅登记。
     */
    public Map<String, Object> list(String keyword, String groupCode, String status)
    {
        List<MetricDictItem> all = mapper.selectAll();
        decorate(all);
        List<MetricDictItem> rows = new ArrayList<>();
        String kw = StringUtils.trimToEmpty(keyword).toLowerCase();
        for (MetricDictItem it : all)
        {
            if (StringUtils.isNotEmpty(groupCode) && !groupCode.equals(it.getGroupCode()))
            {
                continue;
            }
            if (StringUtils.isNotEmpty(status) && !status.equals(it.getStatus()))
            {
                continue;
            }
            if (!kw.isEmpty() && !contains(kw, it.getMetricCode(), it.getMetricName(), it.getMetricNameEn(), it.getRemark()))
            {
                continue;
            }
            rows.add(it);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", rows.size());
        out.put("groups", mapper.selectGroups());
        out.put("datasets", datasets());
        return out;
    }

    public MetricDictItem detail(String metricCode)
    {
        MetricDictItem it = require(metricCode);
        List<MetricDictItem> all = mapper.selectAll();
        decorate(all);
        for (MetricDictItem x : all)
        {
            if (x.getMetricCode().equals(it.getMetricCode()))
            {
                return x;
            }
        }
        return it;
    }

    /** 三个数据集与跌幅基准对应的 UDS 数据集名（页面表头用） */
    Map<String, String> datasets()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("udsHourField", datasetName(overview() == null ? null : overview().getHour()));
        m.put("udsDayField", datasetName(overview() == null ? null : overview().getDay()));
        m.put("udsAmountField", datasetName(overview() == null ? null : overview().getAmount()));
        m.put("udsBaselineField", datasetName(overview() == null ? null : overview().getHour()));
        return m;
    }

    // ------------------------------------------------------------------ 写入

    /** 登记新指标（仅登记），返回编码 */
    @Transactional(rollbackFor = Exception.class)
    public String create(MetricDictItem body, String operator)
    {
        if (body == null)
        {
            throw MonitorFieldException.of("metricCode", "請填寫指標編碼");
        }
        MetricDictItem item = new MetricDictItem();
        item.setMetricCode(StringUtils.trimToEmpty(body.getMetricCode()));
        Map<String, String> errors = new LinkedHashMap<>();
        if (!CODE.matcher(item.getMetricCode()).matches())
        {
            errors.put("metricCode", "指標編碼只能用英文字母、數字、底線，以字母開頭，最長 64 個字元");
        }
        else if (mapper.countCodeIgnoreCase(item.getMetricCode()) > 0)
        {
            errors.put("metricCode", "指標編碼已存在（不區分大小寫）");
        }
        copyBasic(body, item, errors);
        copyDictionary(body, item, errors);
        failIfAny(errors);
        item.setCreateBy(operator);
        mapper.insertRegistered(item);
        return item.getMetricCode();
    }

    /**
     * 修改：所有指标都能改口径与取数说明、名称、英文名、分组、数值格式；仅登记的指标还能改漲跌方向、更新频率。
     * 没传（null）的字段沿用原值，传空串表示清空。
     * <ul>
     *   <li>已接入的指标改了方向 / 更新频率：返回 400（前端置灰）；</li>
     *   <li>已接入的指标改数值格式、但有告警规则（含暂停）：返回 400 valueFormat，要先删规则（阈值按格式的单位填写）；</li>
     *   <li>已接入的指标保存后在同一事务里重载 {@link MetricRegistry}，校验不通过（如分组未启用）整体回滚并返回 400。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(String metricCode, MetricDictItem body, String operator)
    {
        MetricDictItem existing = require(metricCode);
        if (body == null)
        {
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        MetricDictItem item = new MetricDictItem();
        item.setMetricCode(existing.getMetricCode());
        item.setUpdateBy(operator);
        copyDictionary(dictionaryWithDefaults(body, existing), item, errors);
        boolean registered = STATUS_REGISTERED.equals(existing.getStatus());
        MetricDictItem merged = withDefaults(body, existing);
        copyBasic(merged, item, errors);
        if (!registered)
        {
            rejectLiveBasicChanges(body, existing, errors);
            if (item.getValueFormat() != null && !item.getValueFormat().equalsIgnoreCase(StringUtils.trimToEmpty(existing.getValueFormat())))
            {
                int rules = mapper.countRules(existing.getMetricCode());
                if (rules > 0)
                {
                    errors.put("valueFormat", "該指標有 " + rules + " 條告警規則（含暫停），不能改數值格式：閾值按格式的單位填寫，請先刪除規則再改");
                }
            }
        }
        failIfAny(errors);
        mapper.updateDictionary(item);
        if (registered)
        {
            mapper.updateRegisteredBasic(item);
        }
        else
        {
            mapper.updateLiveBasic(item);
            reloadRegistry();
        }
    }

    /** 同一事务里重载指标配置：校验不通过时抛 400，事务回滚，线上快照保持原样 */
    private void reloadRegistry()
    {
        if (registry == null)
        {
            return;
        }
        try
        {
            registry.reload();
        }
        catch (RuntimeException e)
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "保存失敗，修改後的指標配置校驗不通過：" + e.getMessage());
        }
    }

    /** 删除：只允许仅登记、且没有页面 / 告警规则 / 异常事件 / 其他指标引用的指标 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String metricCode)
    {
        MetricDictItem it = detail(metricCode);
        if (!STATUS_REGISTERED.equals(it.getStatus()))
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "已接入運營總覽、指標匯總的指標不能刪除");
        }
        List<String> refs = new ArrayList<>();
        if (!it.getPages().isEmpty())
        {
            refs.add("頁面 " + String.join("、", it.getPages()));
        }
        if (it.getRuleCount() != null && it.getRuleCount() > 0)
        {
            refs.add(it.getRuleCount() + " 條告警規則");
        }
        int events = mapper.countEvents(it.getMetricCode());
        if (events > 0)
        {
            refs.add(events + " 條異常事件");
        }
        if (!it.getUsedBy().isEmpty())
        {
            refs.add("指標 " + String.join("、", it.getUsedBy()));
        }
        if (!refs.isEmpty())
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "指標仍被引用，不能刪除：" + String.join("；", refs));
        }
        mapper.deleteRegistered(it.getMetricCode());
    }

    // ------------------------------------------------------------------ 内部

    private MetricDictItem require(String metricCode)
    {
        MetricDictItem it = StringUtils.isEmpty(metricCode) ? null : mapper.selectByCode(metricCode.trim());
        if (it == null)
        {
            throw MonitorException.notFound("指標不存在");
        }
        return it;
    }

    /** 补上线上配置对照、引用情况、能否删除 */
    void decorate(List<MetricDictItem> all)
    {
        Map<String, List<String>> pages = new LinkedHashMap<>();
        for (Map<String, Object> r : mapper.selectPageRefs())
        {
            pages.computeIfAbsent(String.valueOf(r.get("metricCode")), k -> new ArrayList<>()).add(String.valueOf(r.get("pageCode")));
        }
        Map<String, Integer> rules = new LinkedHashMap<>();
        for (Map<String, Object> r : mapper.selectRuleCounts())
        {
            Object cnt = r.get("cnt");
            rules.put(String.valueOf(r.get("metricCode")), cnt instanceof Number ? ((Number) cnt).intValue() : 0);
        }
        Map<String, List<String>> usedBy = new LinkedHashMap<>();
        for (MetricDictItem it : all)
        {
            for (String ref : new String[] { it.getLeftCode(), it.getRightCode(), it.getMinSampleKey() })
            {
                if (StringUtils.isNotEmpty(ref) && !"base".equalsIgnoreCase(ref) && !ref.equals(it.getMetricCode()))
                {
                    List<String> l = usedBy.computeIfAbsent(ref, k -> new ArrayList<>());
                    if (!l.contains(it.getMetricCode()))
                    {
                        l.add(it.getMetricCode());
                    }
                }
            }
        }
        for (MetricDictItem it : all)
        {
            String code = it.getMetricCode();
            it.setPages(pages.getOrDefault(code, new ArrayList<>()));
            it.setRuleCount(rules.getOrDefault(code, 0));
            it.setUsedBy(usedBy.getOrDefault(code, new ArrayList<>()));
            it.setConfig(liveConfig(code));
            it.setConfigMismatch(mismatch(it));
            it.setDeletable(STATUS_REGISTERED.equals(it.getStatus()) && it.getPages().isEmpty()
                && it.getRuleCount() == 0 && it.getUsedBy().isEmpty());
        }
    }

    /** 线上配置里这个指标实际怎么取数（只读 application.yml，不改） */
    Map<String, String> liveConfig(String code)
    {
        Map<String, String> m = new LinkedHashMap<>();
        UdsProperties.Overview ov = overview();
        m.put("udsHourField", ov == null ? null : field(ov.getHour(), code));
        m.put("udsDayField", ov == null ? null : field(ov.getDay(), code));
        m.put("udsAmountField", ov == null ? null : field(ov.getAmount(), code));
        Map<String, String> base = monitor == null || monitor.getEngine() == null ? null : monitor.getEngine().getBaselineFields();
        m.put("udsBaselineField", base == null ? null : StringUtils.trimToNull(base.get(code)));
        String assemble = null;
        if (ov != null && ov.getSubtract() != null && ov.getSubtract().get(code) != null && ov.getSubtract().get(code).size() == 2)
        {
            assemble = ov.getSubtract().get(code).get(0) + " − " + ov.getSubtract().get(code).get(1);
        }
        else if (ov != null && ov.getAdd() != null && ov.getAdd().get(code) != null && ov.getAdd().get(code).size() == 2)
        {
            assemble = ov.getAdd().get(code).get(0) + " + " + ov.getAdd().get(code).get(1);
        }
        m.put("assemble", assemble);
        return m;
    }

    /** 表里登记的 UDS 字段与线上配置不一致的项（显示名） */
    static List<String> mismatch(MetricDictItem it)
    {
        List<String> out = new ArrayList<>();
        Map<String, String> live = it.getConfig();
        if (live == null)
        {
            return out;
        }
        String[] table = { it.getUdsHourField(), it.getUdsDayField(), it.getUdsAmountField(), it.getUdsBaselineField() };
        int i = 0;
        for (Map.Entry<String, String> e : UDS_FIELDS.entrySet())
        {
            if (!Objects.equals(StringUtils.trimToNull(table[i]), StringUtils.trimToNull(live.get(e.getKey()))))
            {
                out.add(e.getValue());
            }
            i++;
        }
        return out;
    }

    private UdsProperties.Overview overview()
    {
        return uds == null ? null : uds.getOverview();
    }

    private String field(String datasetKey, String code)
    {
        if (uds == null || uds.getDatasets() == null || datasetKey == null)
        {
            return null;
        }
        UdsProperties.Dataset d = uds.getDatasets().get(datasetKey);
        return d == null || d.getMetrics() == null ? null : StringUtils.trimToNull(d.getMetrics().get(code));
    }

    private String datasetName(String datasetKey)
    {
        if (uds == null || uds.getDatasets() == null || datasetKey == null)
        {
            return null;
        }
        UdsProperties.Dataset d = uds.getDatasets().get(datasetKey);
        return d == null ? null : d.getName();
    }

    /** 口径与取数说明：所有指标都能改 */
    static void copyDictionary(MetricDictItem body, MetricDictItem item, Map<String, String> errors)
    {
        String remark = StringUtils.trimToNull(body.getRemark());
        if (remark != null && remark.length() > 500)
        {
            errors.put("remark", "口徑與備註最多 500 個字");
        }
        item.setRemark(remark);
        item.setSourceTable(name(body.getSourceTable(), TABLE, "sourceTable", "上游原始表只能用英文字母、數字、底線和「.」", errors));
        item.setSourceField(name(body.getSourceField(), FIELD, "sourceField", "上游原始字段只能用英文字母、數字、底線", errors));
        item.setUdsHourField(name(body.getUdsHourField(), FIELD, "udsHourField", "字段名只能用英文字母、數字、底線", errors));
        item.setUdsDayField(name(body.getUdsDayField(), FIELD, "udsDayField", "字段名只能用英文字母、數字、底線", errors));
        item.setUdsAmountField(name(body.getUdsAmountField(), FIELD, "udsAmountField", "字段名只能用英文字母、數字、底線", errors));
        item.setUdsBaselineField(name(body.getUdsBaselineField(), FIELD, "udsBaselineField", "字段名只能用英文字母、數字、底線", errors));
        if (item.getSourceTable() == null && item.getSourceField() != null)
        {
            errors.putIfAbsent("sourceTable", "填了上游原始字段時，請一起填上游原始表");
        }
    }

    /** 名称、分组、格式、方向、更新频率：新增和仅登记的指标用 */
    private void copyBasic(MetricDictItem body, MetricDictItem item, Map<String, String> errors)
    {
        String name = StringUtils.trimToNull(body.getMetricName());
        if (name == null)
        {
            errors.put("metricName", "請填寫指標名稱");
        }
        else if (name.length() > 128)
        {
            errors.put("metricName", "指標名稱最多 128 個字");
        }
        item.setMetricName(name);
        String en = StringUtils.trimToNull(body.getMetricNameEn());
        if (en != null && en.length() > 128)
        {
            errors.put("metricNameEn", "英文名最多 128 個字");
        }
        item.setMetricNameEn(en);
        String group = StringUtils.trimToNull(body.getGroupCode());
        if (group != null && !groupExists(group))
        {
            errors.put("groupCode", "分組不存在");
        }
        item.setGroupCode(group);
        item.setValueFormat(pick(body.getValueFormat(), "INT", FORMATS, "valueFormat", "數值格式只能是 INT / MONEY / PCT / MIN / MULTIPLE", errors));
        item.setDirection(pick(body.getDirection(), "up", DIRECTIONS, "direction", "漲跌方向只能是 up / down / flat / range", errors));
        item.setUpdateFrequency(pick(body.getUpdateFrequency(), "hour", FREQUENCIES, "updateFrequency", "更新頻率只能是 realtime / hour / day", errors));
    }

    /** 修改仅登记指标时，没传的基础字段沿用原值 */
    private static MetricDictItem withDefaults(MetricDictItem body, MetricDictItem existing)
    {
        MetricDictItem m = new MetricDictItem();
        m.setMetricName(body.getMetricName() != null ? body.getMetricName() : existing.getMetricName());
        m.setMetricNameEn(body.getMetricNameEn() != null ? body.getMetricNameEn() : existing.getMetricNameEn());
        m.setGroupCode(body.getGroupCode() != null ? body.getGroupCode() : existing.getGroupCode());
        m.setValueFormat(body.getValueFormat() != null ? body.getValueFormat() : existing.getValueFormat());
        m.setDirection(body.getDirection() != null ? body.getDirection() : existing.getDirection());
        m.setUpdateFrequency(body.getUpdateFrequency() != null ? body.getUpdateFrequency() : existing.getUpdateFrequency());
        return m;
    }

    /** 修改时没传（null）的口径与取数字段沿用原值；传空串表示清空 */
    static MetricDictItem dictionaryWithDefaults(MetricDictItem body, MetricDictItem existing)
    {
        MetricDictItem m = new MetricDictItem();
        m.setRemark(body.getRemark() != null ? body.getRemark() : existing.getRemark());
        m.setSourceTable(body.getSourceTable() != null ? body.getSourceTable() : existing.getSourceTable());
        m.setSourceField(body.getSourceField() != null ? body.getSourceField() : existing.getSourceField());
        m.setUdsHourField(body.getUdsHourField() != null ? body.getUdsHourField() : existing.getUdsHourField());
        m.setUdsDayField(body.getUdsDayField() != null ? body.getUdsDayField() : existing.getUdsDayField());
        m.setUdsAmountField(body.getUdsAmountField() != null ? body.getUdsAmountField() : existing.getUdsAmountField());
        m.setUdsBaselineField(body.getUdsBaselineField() != null ? body.getUdsBaselineField() : existing.getUdsBaselineField());
        return m;
    }

    /** 已接入的指标改了漲跌方向 / 更新频率：报错而不是静默忽略，免得以为改成功了（名称、分组、格式 2026-10-27 起可改） */
    static void rejectLiveBasicChanges(MetricDictItem body, MetricDictItem existing, Map<String, String> errors)
    {
        String msg = "已接入的指標不能修改，以免影響運營總覽、指標匯總";
        if (changed(body.getDirection(), existing.getDirection())) errors.put("direction", msg);
        if (changed(body.getUpdateFrequency(), existing.getUpdateFrequency())) errors.put("updateFrequency", msg);
    }

    /** 传了（非 null）且与原值不同 */
    private static boolean changed(String given, String current)
    {
        return given != null && !Objects.equals(StringUtils.trimToNull(given), StringUtils.trimToNull(current));
    }

    private boolean groupExists(String group)
    {
        for (Map<String, Object> g : mapper.selectGroups())
        {
            if (group.equals(String.valueOf(g.get("groupCode"))))
            {
                return true;
            }
        }
        return false;
    }

    private static String name(String value, Pattern p, String field, String msg, Map<String, String> errors)
    {
        String v = StringUtils.trimToNull(value);
        if (v != null && !p.matcher(v).matches())
        {
            errors.put(field, msg);
        }
        return v;
    }

    private static String pick(String value, String dflt, Set<String> allowed, String field, String msg, Map<String, String> errors)
    {
        String v = StringUtils.trimToNull(value);
        if (v == null)
        {
            return dflt;
        }
        if (!allowed.contains(v))
        {
            errors.put(field, msg);
        }
        return v;
    }

    private static boolean contains(String kw, String... values)
    {
        for (String v : values)
        {
            if (v != null && v.toLowerCase().contains(kw))
            {
                return true;
            }
        }
        return false;
    }

    private static void failIfAny(Map<String, String> errors)
    {
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
    }
}
