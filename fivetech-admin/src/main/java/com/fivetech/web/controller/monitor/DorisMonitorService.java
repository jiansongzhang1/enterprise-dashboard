package com.fivetech.web.controller.monitor;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.web.config.DorisProperties;

@Service
public class DorisMonitorService
{
    private static final String SOURCE_CURRENCY_SQL = """
        SELECT source_currency, SUM(source_amount) AS source_amount
        FROM dwd_main_pax_admin_t_cache_rate_log
        GROUP BY source_currency
        ORDER BY source_currency
        """;

    private final JdbcTemplate jdbcTemplate;
    private final DorisProperties properties;

    public DorisMonitorService(@Qualifier("dorisJdbcTemplate") JdbcTemplate jdbcTemplate,
                               DorisProperties properties)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<SourceCurrencySummary> querySourceCurrencySummary()
    {
        if (!properties.isEnabled())
        {
            throw new ServiceException("Doris 数据源未启用，请配置 doris.enabled=true");
        }
        return jdbcTemplate.query(SOURCE_CURRENCY_SQL, (rs, rowNum) ->
            new SourceCurrencySummary(rs.getString("source_currency"), rs.getBigDecimal("source_amount")));
    }

    public record SourceCurrencySummary(String sourceCurrency, BigDecimal sourceAmount)
    {
    }
}
