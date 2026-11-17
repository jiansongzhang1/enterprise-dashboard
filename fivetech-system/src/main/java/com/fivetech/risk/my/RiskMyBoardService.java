package com.fivetech.risk.my;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.risk.RiskException;
import com.fivetech.risk.RiskQuery;
import com.fivetech.risk.catalog.RiskCatalog;
import com.fivetech.risk.catalog.RiskColumn;
import com.fivetech.risk.config.RiskBoards;
import com.fivetech.risk.config.RiskConfigService;
import com.fivetech.risk.mapper.RiskMyBoardMapper;
import com.fivetech.risk.rank.RiskRankService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 我的榜单（7.1.3）：按「当前用户 + 当前站点」隔离，只能看、改自己的；别人的或已删除的返回 404。
 * 名称最多 16 字、不与系统榜或本人已有的重名；每人每站最多 RISK_RANK_BOARDS.myBoardLimit 个。
 *
 * @author fivetech
 */
@Service
public class RiskMyBoardService
{
    static final ObjectMapper JSON = JsonMapper.builder().build();

    static final int NAME_MAX = 16;

    private final RiskMyBoardMapper mapper;

    private final RiskConfigService config;

    private final RiskCatalog catalog;

    private final DashboardProperties dashboard;

    public RiskMyBoardService(RiskMyBoardMapper mapper, RiskConfigService config, RiskCatalog catalog, DashboardProperties dashboard)
    {
        this.mapper = mapper;
        this.config = config;
        this.catalog = catalog;
        this.dashboard = dashboard;
    }

    String site()
    {
        return dashboard.currentSite();
    }

    public Map<String, Object> list(Long userId)
    {
        RiskBoards cfg = config.boards(site());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RiskMyBoard b : mapper.selectMine(userId, site()))
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getBoardId());
            m.put("code", "m:" + b.getBoardId());
            m.put("name", b.getBoardName());
            Object conds;
            try
            {
                conds = StringUtils.isEmpty(b.getConditions()) ? List.of() : JSON.readValue(b.getConditions(), Object.class);
            }
            catch (RuntimeException e)
            {
                conds = List.of();
            }
            m.put("conditions", conds);
            m.put("rankCol", b.getRankCol());
            m.put("rankDir", b.getRankDir());
            m.put("showCard", Boolean.TRUE.equals(b.getShowCard()));
            m.put("orderNum", b.getOrderNum());
            String reason = unavailable(b, cfg);
            m.put("unavailable", reason != null);
            m.put("reason", reason);
            m.put("createTime", b.getCreateTime());
            m.put("updateTime", b.getUpdateTime());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("limit", cfg.myBoardLimit);
        out.put("rows", rows);
        return out;
    }

    /** 条件或排行字段用到已下线的字段时的原因 */
    String unavailable(RiskMyBoard b, RiskBoards cfg)
    {
        try
        {
            RiskQuery.Condition[] arr = StringUtils.isEmpty(b.getConditions()) ? new RiskQuery.Condition[0]
                : JSON.readValue(b.getConditions(), RiskQuery.Condition[].class);
            for (RiskQuery.Condition c : arr)
            {
                if (RiskRankService.RISK_TYPE.equals(c.getField()))
                {
                    continue;
                }
                RiskColumn col = catalog.rank(c.getField());
                if (col == null || col.getCondition() == null)
                {
                    return "條件「" + (col == null ? c.getField() : col.getName()) + "」已下線";
                }
            }
        }
        catch (RuntimeException e)
        {
            return "條件無法解析";
        }
        if (StringUtils.isNotEmpty(b.getRankCol()) && catalog.rank(b.getRankCol()) == null)
        {
            return "排行字段「" + b.getRankCol() + "」已下線";
        }
        return null;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Map<String, Object> body, Long userId, String operator)
    {
        RiskBoards cfg = config.boards(site());
        Map<String, String> errors = new LinkedHashMap<>();
        RiskMyBoard b = new RiskMyBoard();
        b.setUserId(userId);
        b.setSiteCode(site());
        b.setBoardName(name(body.get("name"), cfg, userId, null, errors));
        b.setConditions(conditions(body.get("conditions"), cfg, errors));
        rank(body, b, errors, true, defaultRankCol(cfg));
        b.setShowCard(Boolean.TRUE.equals(body.get("showCard")));
        b.setCreateBy(operator);
        if (errors.isEmpty() && mapper.countMine(userId, site()) >= cfg.myBoardLimit)
        {
            throw RiskException.field("name", "每個站點最多 " + cfg.myBoardLimit + " 個我的榜單");
        }
        RiskException.failIfAny(errors);
        mapper.insert(b);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", b.getBoardId());
        out.put("code", "m:" + b.getBoardId());
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, Map<String, Object> body, Long userId, String operator)
    {
        require(id, userId);
        RiskBoards cfg = config.boards(site());
        Map<String, String> errors = new LinkedHashMap<>();
        RiskMyBoard b = new RiskMyBoard();
        b.setBoardId(id);
        b.setUserId(userId);
        b.setSiteCode(site());
        if (body.containsKey("name"))
        {
            b.setBoardName(name(body.get("name"), cfg, userId, id, errors));
        }
        if (body.containsKey("conditions"))
        {
            b.setConditions(conditions(body.get("conditions"), cfg, errors));
        }
        rank(body, b, errors, false, null);
        if (body.get("showCard") instanceof Boolean s)
        {
            b.setShowCard(s);
        }
        b.setUpdateBy(operator);
        RiskException.failIfAny(errors);
        mapper.update(b);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId, String operator)
    {
        require(id, userId);
        mapper.softDelete(userId, site(), id, operator);
    }

    /** 调整顺序：必须是本人本站全部榜单的 id，缺或多都返回 400 */
    @Transactional(rollbackFor = Exception.class)
    public void reorder(List<Long> ids, Long userId)
    {
        Set<Long> mine = new HashSet<>();
        mapper.selectMine(userId, site()).forEach(b -> mine.add(b.getBoardId()));
        if (ids == null || ids.size() != mine.size() || !mine.equals(new HashSet<>(ids)))
        {
            throw RiskException.field("ids", "要傳本人在這個站點的全部榜單");
        }
        for (int i = 0; i < ids.size(); i++)
        {
            mapper.updateOrder(userId, site(), ids.get(i), i + 1);
        }
    }

    private RiskMyBoard require(Long id, Long userId)
    {
        RiskMyBoard b = id == null ? null : mapper.selectMineById(userId, site(), id);
        if (b == null)
        {
            throw RiskException.notFound("榜單不存在");
        }
        return b;
    }

    private String name(Object raw, RiskBoards cfg, Long userId, Long excludeId, Map<String, String> errors)
    {
        String name = raw == null ? "" : String.valueOf(raw).trim();
        if (name.isEmpty())
        {
            errors.put("name", "請填寫名稱");
        }
        else if (name.length() > NAME_MAX)
        {
            errors.put("name", "名稱最多 " + NAME_MAX + " 個字");
        }
        else if (cfg.boards.stream().anyMatch(x -> x.name.equalsIgnoreCase(name)))
        {
            errors.put("name", "不能與系統榜單同名");
        }
        else if (mapper.countName(userId, site(), name, excludeId) > 0)
        {
            errors.put("name", "已有同名的榜單");
        }
        return name;
    }

    /** 名单条件：结构同 rank/list 的 conditions，数值按保存时固定 */
    private String conditions(Object raw, RiskBoards cfg, Map<String, String> errors)
    {
        List<?> list = raw instanceof List<?> l ? l : List.of();
        if (raw != null && !(raw instanceof List<?>))
        {
            errors.put("conditions", "conditions 應為數組");
            return "[]";
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list)
        {
            RiskQuery.Condition c = JSON.convertValue(o, RiskQuery.Condition.class);
            if (StringUtils.isEmpty(c.getField()))
            {
                errors.put("conditions", "條件缺少 field");
                continue;
            }
            if (!RiskRankService.RISK_TYPE.equals(c.getField()))
            {
                RiskColumn col = catalog.rank(c.getField());
                if (col == null || col.getCondition() == null)
                {
                    errors.put("conditions", "不支持的條件字段：" + c.getField());
                    continue;
                }
            }
            out.add(c.toMap());
        }
        return JSON.writeValueAsString(out);
    }

    /** 不传排行字段时：「全部用户」榜的排行字段 */
    static String defaultRankCol(RiskBoards cfg)
    {
        RiskBoards.Board all = cfg.board("all");
        return all != null ? all.rankCol : cfg.boards.isEmpty() ? null : cfg.boards.get(0).rankCol;
    }

    private void rank(Map<String, Object> body, RiskMyBoard b, Map<String, String> errors, boolean create, String fallback)
    {
        Object col = body.get("rankCol");
        if (col != null || create)
        {
            String code = col == null ? String.valueOf(fallback) : String.valueOf(col).trim();
            RiskColumn c = catalog.rank(code);
            if (c == null || !c.isRankable())
            {
                errors.put("rankCol", "不能按「" + code + "」排行");
            }
            b.setRankCol(code);
            Object dir = body.get("rankDir");
            String d = dir == null ? (c == null ? "desc" : c.getRiskDir()) : String.valueOf(dir).trim().toLowerCase(java.util.Locale.ROOT);
            if (!"asc".equals(d) && !"desc".equals(d))
            {
                errors.put("rankDir", "排行方向只能是 asc 或 desc");
            }
            b.setRankDir(d);
        }
        else if (body.get("rankDir") != null)
        {
            String d = String.valueOf(body.get("rankDir")).trim().toLowerCase(java.util.Locale.ROOT);
            if (!"asc".equals(d) && !"desc".equals(d))
            {
                errors.put("rankDir", "排行方向只能是 asc 或 desc");
            }
            b.setRankDir(d);
        }
    }
}
