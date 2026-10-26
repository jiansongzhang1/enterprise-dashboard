package com.fivetech.monitor.service;

import java.util.Comparator;

/**
 * 自然排序：连续数字按数值比较（「2 群」排在「10 群」前），其余字符按字典序、忽略大小写。
 *
 * @author fivetech
 */
public final class MonitorNaturalOrder implements Comparator<String>
{
    public static final MonitorNaturalOrder INSTANCE = new MonitorNaturalOrder();

    private MonitorNaturalOrder()
    {
    }

    @Override
    public int compare(String a, String b)
    {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        int i = 0;
        int j = 0;
        while (i < x.length() && j < y.length())
        {
            char cx = x.charAt(i);
            char cy = y.charAt(j);
            if (isDigit(cx) && isDigit(cy))
            {
                int si = i;
                int sj = j;
                while (i < x.length() && isDigit(x.charAt(i)))
                {
                    i++;
                }
                while (j < y.length() && isDigit(y.charAt(j)))
                {
                    j++;
                }
                String nx = stripZeros(x.substring(si, i));
                String ny = stripZeros(y.substring(sj, j));
                if (nx.length() != ny.length())
                {
                    return nx.length() - ny.length();
                }
                int c = nx.compareTo(ny);
                if (c != 0)
                {
                    return c;
                }
                continue;
            }
            int c = Character.compare(Character.toLowerCase(cx), Character.toLowerCase(cy));
            if (c != 0)
            {
                return c;
            }
            i++;
            j++;
        }
        int rest = (x.length() - i) - (y.length() - j);
        return rest != 0 ? rest : x.compareTo(y);
    }

    private static boolean isDigit(char c)
    {
        return c >= '0' && c <= '9';
    }

    private static String stripZeros(String digits)
    {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0')
        {
            k++;
        }
        return digits.substring(k);
    }
}
