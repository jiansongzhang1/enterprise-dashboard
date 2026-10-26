package com.fivetech.monitor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 守住「数据范围」的约定：监控模块 Mapper XML 里的每个 select 都必须引用 monitorScopeFilter
 * （直接引用，或经由同文件的 sql 片段间接引用）。
 *
 * <p>新增查询忘了加数据范围，就会看到其他站点的规则和事件——这条测试让它在构建时失败。
 * 以后在 monitorScopeFilter 里加别的范围条件时，所有查询自动生效。
 * 确实不需要站点条件的查询（指标配置、用户、生成 scope 之前的管理员判断、判定任务的内部查询）
 * 写进 {@link #WHITELIST}，并在 XML 里注明原因。</p>
 *
 * @author fivetech
 */
class MonitorMapperScopeTest
{
    private static final Path MAPPER_DIR = Paths.get("src", "main", "resources", "mapper", "monitor");

    private static final String SCOPE_FILTER = "com.fivetech.monitor.mapper.MonitorCommon.monitorScopeFilter";

    /** namespace 简名.select id */
    private static final Set<String> WHITELIST = Set.of(
            // 指标配置全站通用，不带 site_code
            "MonitorMetaMapper.selectMonitorableMetrics",
            "MonitorMetaMapper.selectMonitorableMetric",
            // sys_user：用户的站点权限表还没有落地
            "MonitorMetaMapper.selectOwnerCandidates",
            "MonitorMetaMapper.selectOwnerCandidate",
            // 判定任务入口：跨站点找出有启用规则的站点，之后每个站点的查询都带 scope
            "MonitorEngineMapper.selectEngineSites",
            // 发送任务入口：跨站点找出有到期待发送记录的站点，之后每个站点的查询都带 scope
            "MonitorNotifyMapper.selectNotifySites",
            // 系统管理 · 站点管理：列出全部站点的设置和用过的包网（system:site:list 权限）
            "MonitorSiteMapper.selectAllSiteSettings",
            "MonitorSiteMapper.selectPackageNetworks",
            // 通知模板：系统级配置（全站默认 + 各站点专用），system:notifyTpl:* 权限；取模板时按站点参数筛
            "MonitorNotifyTemplateMapper.selectAll",
            "MonitorNotifyTemplateMapper.selectById",
            "MonitorNotifyTemplateMapper.selectCandidates");

    @Test
    void everySelectUsesMonitorScope() throws Exception
    {
        assertTrue(Files.isDirectory(MAPPER_DIR), "找不到 " + MAPPER_DIR.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(MAPPER_DIR))
        {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml"))::iterator)
            {
                Document doc = parse(file.toFile());
                Element root = doc.getDocumentElement();
                String namespace = root.getAttribute("namespace");
                String simple = namespace.substring(namespace.lastIndexOf('.') + 1);

                // 同文件的 sql 片段是否（直接）引用了数据范围片段
                Map<String, Boolean> fragmentScoped = new HashMap<>();
                NodeList sqls = root.getElementsByTagName("sql");
                for (int i = 0; i < sqls.getLength(); i++)
                {
                    Element sql = (Element) sqls.item(i);
                    fragmentScoped.put(sql.getAttribute("id"), referencesScope(sql, Map.of()));
                }

                NodeList selects = root.getElementsByTagName("select");
                for (int i = 0; i < selects.getLength(); i++)
                {
                    Element select = (Element) selects.item(i);
                    String key = simple + "." + select.getAttribute("id");
                    checked++;
                    if (WHITELIST.contains(key))
                    {
                        continue;
                    }
                    if (!referencesScope(select, fragmentScoped))
                    {
                        violations.add(file.getFileName() + " → " + key);
                    }
                }
            }
        }
        assertTrue(checked > 0, "没有扫描到任何 select，检查 MAPPER_DIR");
        assertTrue(violations.isEmpty(),
                "以下查询没有引用 monitorScopeFilter（确实不需要的请加白名单并注明原因）：\n"
                        + String.join("\n", violations));
    }

    @Test
    void whitelistEntriesStillExist() throws Exception
    {
        // 白名单里的查询被删掉或改名后要同步清理，避免白名单越积越大
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(MAPPER_DIR))
        {
            for (Path file : (Iterable<Path>) files::iterator)
            {
                Element root = parse(file.toFile()).getDocumentElement();
                String namespace = root.getAttribute("namespace");
                String simple = namespace.substring(namespace.lastIndexOf('.') + 1);
                NodeList selects = root.getElementsByTagName("select");
                for (int i = 0; i < selects.getLength(); i++)
                {
                    all.append('|').append(simple).append('.').append(((Element) selects.item(i)).getAttribute("id")).append('|');
                }
            }
        }
        for (String key : WHITELIST)
        {
            assertFalse(all.indexOf('|' + key + '|') < 0, "白名单里的查询已不存在：" + key);
        }
    }

    private static boolean referencesScope(Element element, Map<String, Boolean> fragmentScoped)
    {
        NodeList includes = element.getElementsByTagName("include");
        for (int i = 0; i < includes.getLength(); i++)
        {
            String refid = ((Element) includes.item(i)).getAttribute("refid");
            if (SCOPE_FILTER.equals(refid) || Boolean.TRUE.equals(fragmentScoped.get(refid)))
            {
                return true;
            }
        }
        return false;
    }

    private static Document parse(File file) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(false);
        // 不去下载 mybatis 的 DTD
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }
}
