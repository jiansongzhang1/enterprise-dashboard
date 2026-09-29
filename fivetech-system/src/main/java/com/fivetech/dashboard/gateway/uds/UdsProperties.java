package com.fivetech.dashboard.gateway.uds;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * UDS（统一数据服务）对接配置，挂在 {@code dashboard.gateway.uds.*} 下。
 * <p>
 * 地址、租户、身份等一律配置化，不写死在代码里 —— POC 环境、测试环境、生产环境
 * 的 IP 与租户都不同。
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "dashboard.gateway.uds")
public class UdsProperties
{
    /** 服务地址，如 http://172.29.106.105:18082 */
    private String baseUrl;

    /** 查询接口路径 */
    private String queryPath = "/v1/query";

    /** X-Uds-Principal：调用方身份，建议用固定的服务账号而不是个人账号 */
    private String principal = "fivetech-dashboard";

    /** X-Uds-Tenant */
    private String tenant = "poc";

    /** X-Uds-Roles，多个逗号分隔 */
    private String roles = "merchant_ops";

    /** X-Uds-Merchant-Codes，多个逗号分隔 */
    private String merchantCodes = "";

    private int connectTimeoutSeconds = 5;

    private int readTimeoutSeconds = 30;

    /**
     * 撞上「数据未就绪」时，把区间上界按粒度往回退几格再试。
     * 退到 0 格仍不就绪就放弃——不无限重试，免得一次页面刷新打出几十个请求。
     */
    private int notReadyRetries = 6;

    /**
     * 探到的水位缓存秒数。缓存期内直接按水位夹住上界，不再浪费一次必然失败的请求；
     * 过期后重新试满区间，这样上游追上进度时能自动恢复。
     */
    private int watermarkCacheSeconds = 120;

    public int getNotReadyRetries()
    {
        return notReadyRetries;
    }

    public void setNotReadyRetries(int notReadyRetries)
    {
        this.notReadyRetries = notReadyRetries;
    }

    public int getWatermarkCacheSeconds()
    {
        return watermarkCacheSeconds;
    }

    public void setWatermarkCacheSeconds(int watermarkCacheSeconds)
    {
        this.watermarkCacheSeconds = watermarkCacheSeconds;
    }

    /**
     * 区间合计使用的粒度。
     * <p>
     * 合计必须由数据侧按整个区间重算，不能把时间序列加总（派生指标要先聚合分子分母、
     * 去重人数不可相加、平均耗时要按笔数加权）。这里传什么值取决于 UDS 的约定，
     * <b>待与数据平台确认</b>。
     */
    private String totalGranularity = "ALL";

    /** 指标编码 → 数据集的映射 */
    private Map<String, Dataset> datasets = new LinkedHashMap<>();

    /** 明细表映射：member / transaction / bet */
    private Map<String, DetailDataset> details = new LinkedHashMap<>();

    /**
     * 一个数据集的映射定义
     */
    public static class Dataset
    {
        /** 时间维度字段名，如 dt */
        private String timeDimension = "dt";

        /**
         * 数据集的原生粒度。区间合计不传维度、只传这个粒度的时间块，
         * 让上游按整个区间重算——人数走 bitmap 跨小时合并去重，绝不是小时值相加。
         */
        private String grain = "HOUR";

        public String getGrain()
        {
            return grain;
        }

        public void setGrain(String grain)
        {
            this.grain = grain;
        }

        /** 查询时带上的维度，如 merchant_code */
        private java.util.List<String> dimensions = new java.util.ArrayList<>();

        /** 固定 options，如 { "channel": "ONLINE" } */
        private Map<String, Object> options = new LinkedHashMap<>();

        /** 本系统指标编码 → UDS 指标名 */
        private Map<String, String> metrics = new LinkedHashMap<>();

        public String getTimeDimension()
        {
            return timeDimension;
        }

        public void setTimeDimension(String timeDimension)
        {
            this.timeDimension = timeDimension;
        }

        public java.util.List<String> getDimensions()
        {
            return dimensions;
        }

        public void setDimensions(java.util.List<String> dimensions)
        {
            this.dimensions = dimensions;
        }

        public Map<String, Object> getOptions()
        {
            return options;
        }

        public void setOptions(Map<String, Object> options)
        {
            this.options = options;
        }

        public Map<String, String> getMetrics()
        {
            return metrics;
        }

        public void setMetrics(Map<String, String> metrics)
        {
            this.metrics = metrics;
        }
    }

    /**
     * 明细表映射。UDS 是聚合型接口，是否支持明细查询待确认；
     * 未配置时对应的明细方法返回空结果而不是报错。
     */
    public static class DetailDataset
    {
        private String dataset;

        private String timeDimension = "dt";

        /** VO 字段名 → UDS 列名 */
        private Map<String, String> fields = new LinkedHashMap<>();

        private Map<String, Object> options = new LinkedHashMap<>();

        public String getDataset()
        {
            return dataset;
        }

        public void setDataset(String dataset)
        {
            this.dataset = dataset;
        }

        public String getTimeDimension()
        {
            return timeDimension;
        }

        public void setTimeDimension(String timeDimension)
        {
            this.timeDimension = timeDimension;
        }

        public Map<String, String> getFields()
        {
            return fields;
        }

        public void setFields(Map<String, String> fields)
        {
            this.fields = fields;
        }

        public Map<String, Object> getOptions()
        {
            return options;
        }

        public void setOptions(Map<String, Object> options)
        {
            this.options = options;
        }
    }

    public String getBaseUrl()
    {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl)
    {
        this.baseUrl = baseUrl;
    }

    public String getQueryPath()
    {
        return queryPath;
    }

    public void setQueryPath(String queryPath)
    {
        this.queryPath = queryPath;
    }

    public String getPrincipal()
    {
        return principal;
    }

    public void setPrincipal(String principal)
    {
        this.principal = principal;
    }

    public String getTenant()
    {
        return tenant;
    }

    public void setTenant(String tenant)
    {
        this.tenant = tenant;
    }

    public String getRoles()
    {
        return roles;
    }

    public void setRoles(String roles)
    {
        this.roles = roles;
    }

    public String getMerchantCodes()
    {
        return merchantCodes;
    }

    public void setMerchantCodes(String merchantCodes)
    {
        this.merchantCodes = merchantCodes;
    }

    public int getConnectTimeoutSeconds()
    {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds)
    {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds()
    {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds)
    {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public String getTotalGranularity()
    {
        return totalGranularity;
    }

    public void setTotalGranularity(String totalGranularity)
    {
        this.totalGranularity = totalGranularity;
    }

    public Map<String, Dataset> getDatasets()
    {
        return datasets;
    }

    public void setDatasets(Map<String, Dataset> datasets)
    {
        this.datasets = datasets;
    }

    public Map<String, DetailDataset> getDetails()
    {
        return details;
    }

    public void setDetails(Map<String, DetailDataset> details)
    {
        this.details = details;
    }
}
