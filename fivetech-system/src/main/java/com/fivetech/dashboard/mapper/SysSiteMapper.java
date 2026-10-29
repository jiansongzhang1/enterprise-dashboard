package com.fivetech.dashboard.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.dashboard.domain.SysSite;

/**
 * 站点（存在 monitor_site_setting：site_name 不为空的行是站点），以及改站点编码时对所有带 site_code 列的表做级联更新。
 *
 * @author fivetech
 */
public interface SysSiteMapper
{
    /** 默认站点在前，其余按 sort_no、编码 */
    List<SysSite> selectAll();

    int countAll();

    /** 编码已存在（不区分大小写），excludeCode 不为空时排除该站点自己 */
    int countCodeIgnoreCase(@Param("code") String code, @Param("excludeCode") String excludeCode);

    SysSite selectByCodeIgnoreCase(@Param("code") String code);

    /** 不是站点的设置行（site_name 为空）里与 code 相同（不区分大小写）的编码；没有返回 null */
    String selectOrphanCode(@Param("code") String code);

    /** 新增站点：行不存在时新建；已有设置行但还不是站点时补上站点字段（保留包网、规则号） */
    int insert(@Param("site") SysSite site);

    /** 改名称 / UDS 站点标识 / 时区 / 币种 / 币种符号；newCode 不为空时同时改编码 */
    int update(@Param("oldCode") String oldCode, @Param("newCode") String newCode, @Param("site") SysSite site);

    /** 站点表的版本（行数 + 最后修改时间），多实例热加载用 */
    String selectVersion();

    /** 当前 schema 里除 monitor_site_setting 外带 site_code 列的表（只含普通表） */
    List<String> selectSiteCodeTables();

    /** 某表里 site_code = code（不区分大小写）的行数 */
    int countSiteCode(@Param("table") String table, @Param("code") String code);

    /** 级联改编码：某表里 site_code = oldCode（不区分大小写）的行改为 newCode */
    int renameSiteCode(@Param("table") String table, @Param("oldCode") String oldCode, @Param("newCode") String newCode);
}
