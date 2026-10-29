package com.fivetech.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.mapper.DashboardConfigMapper;

/** 多实例热加载：dashboard_metric_card 的版本（行数 + 最后修改时间）变了才重载；重载失败下次再试 */
class MetricRegistryRefresherTest
{
    private String version = "28:20261027100000000000";

    private int reloads;

    private boolean fail;

    @Test
    void reloadsOnlyWhenVersionChanges()
    {
        DashboardConfigMapper mapper = (DashboardConfigMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { DashboardConfigMapper.class }, (p, m, a) -> {
                    if ("selectMetricCardVersion".equals(m.getName()))
                    {
                        return version;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        MetricRegistry registry = new MetricRegistry(null, null)
        {
            @Override
            public synchronized void reload()
            {
                reloads++;
                if (fail)
                {
                    throw new IllegalStateException("校验不通过");
                }
            }
        };
        MetricRegistryRefresher r = new MetricRegistryRefresher(registry, mapper, 0);
        r.start();                          // 0 = 关闭定时，不起线程
        r.check();                          // 第一次：没有记录过版本 → 重载一次
        assertEquals(1, reloads);
        r.check();
        assertEquals(1, reloads, "版本没变不重载");

        version = "28:20261027101500000000"; // 其他实例改了名称
        r.check();
        assertEquals(2, reloads);

        version = "29:20261027102000000000";
        fail = true;
        r.check();
        assertEquals(3, reloads);
        fail = false;
        r.check();
        assertEquals(4, reloads, "上次重载失败，版本没记下，下次再试");
        r.check();
        assertEquals(4, reloads);
        r.stop();
    }
}
