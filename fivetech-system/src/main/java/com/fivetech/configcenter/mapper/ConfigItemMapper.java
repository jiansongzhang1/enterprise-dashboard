package com.fivetech.configcenter.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.configcenter.domain.ConfigItem;

/**
 * 配置中心（config_center）。
 *
 * @author fivetech
 */
public interface ConfigItemMapper
{
    /** 列表：configCode 模糊、siteCode 精确（传 "-" 只看全站通用）、pageName 模糊，均可为空 */
    List<ConfigItem> selectList(@Param("configCode") String configCode, @Param("siteCode") String siteCode,
            @Param("pageName") String pageName);

    ConfigItem selectById(@Param("id") Long id);

    /** 按编码 + 站点精确取一条（站点空串 = 全站通用） */
    ConfigItem selectOne(@Param("configCode") String configCode, @Param("siteCode") String siteCode);

    /** 同一编码 + 站点的条数（excludeId 不为空时排除自己） */
    int countKey(@Param("configCode") String configCode, @Param("siteCode") String siteCode, @Param("excludeId") Long excludeId);

    /** 已用过的页面名称（新增时下拉提示） */
    List<String> selectPageNames();

    int insert(ConfigItem item);

    int update(ConfigItem item);

    int deleteById(@Param("id") Long id);
}
