package com.fivetech.promo.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.configcenter.ConfigCodes;
import com.fivetech.promo.budget.PromoChannelDictionary;

/**
 * 推广筛选的解析结果：推广商、推广渠道（并集）与终端。
 *
 * <p>编码是渠道字典的 ID（按值比较，见 {@link ConfigCodes#idKey(Object)}）；不认识的推广商 / 推广渠道 / 终端返回 4004。
 * 推广商与推广渠道都为空 = 全部（含官方等自然量、停用渠道），此时 {@link #isAll()} 为 true。</p>
 *
 * @author fivetech
 */
public final class PromoScope
{
    public static final String TERMINAL_ALL = "all";

    private final List<PromoChannelDictionary.Promoter> dictionary;

    private final List<PromoChannelDictionary.Promoter> agents;

    /** 选中的推广渠道（并集，按字典顺序）；isAll 时为字典全部渠道 */
    private final List<PromoChannelDictionary.Channel> channels;

    private final boolean all;

    /** 终端编码；all 表示不限 */
    private final String terminal;

    private final PromoTerminalDictionary.Terminal terminalEntry;

    private PromoScope(List<PromoChannelDictionary.Promoter> dictionary, List<PromoChannelDictionary.Promoter> agents,
        List<PromoChannelDictionary.Channel> channels, boolean all, String terminal, PromoTerminalDictionary.Terminal terminalEntry)
    {
        this.dictionary = dictionary;
        this.agents = agents;
        this.channels = channels;
        this.all = all;
        this.terminal = terminal;
        this.terminalEntry = terminalEntry;
    }

    /**
     * 解析并校验筛选。
     *
     * @param terminalCode 终端编码；null / 空 / all = 不限；allowUnidentified 为 true 时可以是 unidentified
     */
    public static PromoScope resolve(List<PromoChannelDictionary.Promoter> dictionary, List<String> agentCodes,
        List<String> channelCodes, PromoTerminalDictionary.Config terminals, String terminalCode)
    {
        List<PromoChannelDictionary.Promoter> dict = dictionary == null ? List.of() : dictionary;
        Map<String, PromoChannelDictionary.Promoter> promoterById = new LinkedHashMap<>();
        Map<String, PromoChannelDictionary.Channel> channelById = new LinkedHashMap<>();
        Map<String, PromoChannelDictionary.Promoter> ownerById = new LinkedHashMap<>();
        for (PromoChannelDictionary.Promoter p : dict)
        {
            promoterById.putIfAbsent(ConfigCodes.idKey(p.getId()), p);
            for (PromoChannelDictionary.Channel c : p.getChannels())
            {
                channelById.putIfAbsent(ConfigCodes.idKey(c.getId()), c);
                ownerById.putIfAbsent(ConfigCodes.idKey(c.getId()), p);
            }
        }
        Set<PromoChannelDictionary.Promoter> agents = new LinkedHashSet<>();
        for (String code : nonBlank(agentCodes))
        {
            PromoChannelDictionary.Promoter p = promoterById.get(ConfigCodes.idKey(code));
            if (p == null)
            {
                throw new ServiceException("不認識的推廣商：" + code, PromoErrors.UNKNOWN_FILTER);
            }
            agents.add(p);
        }
        Set<String> selected = new LinkedHashSet<>();
        for (String code : nonBlank(channelCodes))
        {
            if (!channelById.containsKey(ConfigCodes.idKey(code)))
            {
                throw new ServiceException("不認識的推廣渠道：" + code, PromoErrors.UNKNOWN_FILTER);
            }
            selected.add(ConfigCodes.idKey(code));
        }
        agents.forEach(p -> p.getChannels().forEach(c -> selected.add(ConfigCodes.idKey(c.getId()))));
        boolean all = selected.isEmpty();
        List<PromoChannelDictionary.Channel> channels = new ArrayList<>();
        // 按字典顺序
        for (PromoChannelDictionary.Promoter p : dict)
        {
            for (PromoChannelDictionary.Channel c : p.getChannels())
            {
                if (all || selected.contains(ConfigCodes.idKey(c.getId())))
                {
                    channels.add(c);
                }
            }
        }
        String t = terminalCode == null || terminalCode.trim().isEmpty() ? TERMINAL_ALL : terminalCode.trim().toLowerCase(Locale.ROOT);
        PromoTerminalDictionary.Terminal entry = null;
        if (!TERMINAL_ALL.equals(t))
        {
            entry = terminals == null ? null : terminals.find(t);
            if (entry == null)
            {
                throw new ServiceException("不認識的終端：" + terminalCode, PromoErrors.UNKNOWN_FILTER);
            }
        }
        return new PromoScope(dict, new ArrayList<>(agents), channels, all, t, entry);
    }

    private static List<String> nonBlank(List<String> codes)
    {
        List<String> out = new ArrayList<>();
        if (codes != null)
        {
            for (String c : codes)
            {
                if (c != null && !c.trim().isEmpty())
                {
                    out.add(c.trim());
                }
            }
        }
        return out;
    }

    public boolean isAll() { return all; }

    public List<PromoChannelDictionary.Promoter> getDictionary() { return dictionary; }

    public List<PromoChannelDictionary.Promoter> getAgents() { return agents; }

    public List<PromoChannelDictionary.Channel> getChannels() { return channels; }

    public String getTerminal() { return terminal; }

    public boolean isTerminalSelected() { return !TERMINAL_ALL.equals(terminal); }

    public PromoTerminalDictionary.Terminal getTerminalEntry() { return terminalEntry; }

    /** 推广商下属于本次范围的推广渠道（按字典顺序） */
    public List<PromoChannelDictionary.Channel> channelsOf(PromoChannelDictionary.Promoter p)
    {
        Set<String> in = new LinkedHashSet<>();
        channels.forEach(c -> in.add(ConfigCodes.idKey(c.getId())));
        List<PromoChannelDictionary.Channel> out = new ArrayList<>();
        for (PromoChannelDictionary.Channel c : p.getChannels())
        {
            if (in.contains(ConfigCodes.idKey(c.getId())))
            {
                out.add(c);
            }
        }
        return out;
    }

    /** 范围内有渠道的推广商（按字典顺序） */
    public List<PromoChannelDictionary.Promoter> promotersInScope()
    {
        List<PromoChannelDictionary.Promoter> out = new ArrayList<>();
        for (PromoChannelDictionary.Promoter p : dictionary)
        {
            if (!channelsOf(p).isEmpty())
            {
                out.add(p);
            }
        }
        return out;
    }

    /** 导出标题里的「渠道：…」：全部，或所选推广商、推广渠道的名称，用「、」连接 */
    public String channelLabel()
    {
        if (all)
        {
            return "全部";
        }
        Set<String> names = new LinkedHashSet<>();
        agents.forEach(p -> names.add(p.getName()));
        Set<String> agentChannelIds = new LinkedHashSet<>();
        agents.forEach(p -> p.getChannels().forEach(c -> agentChannelIds.add(ConfigCodes.idKey(c.getId()))));
        for (PromoChannelDictionary.Channel c : channels)
        {
            if (!agentChannelIds.contains(ConfigCodes.idKey(c.getId())))
            {
                names.add(c.getName());
            }
        }
        return String.join("、", names);
    }
}
