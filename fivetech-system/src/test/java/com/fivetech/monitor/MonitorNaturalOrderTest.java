package com.fivetech.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.monitor.service.MonitorNaturalOrder;

class MonitorNaturalOrderTest
{
    @Test
    void numbersCompareByValue()
    {
        List<String> names = new ArrayList<>(Arrays.asList("告警 10 群", "告警 2 群", "Alpha", "告警 1 群", "alpha 2", "告警 02 群", "告警"));
        names.sort(MonitorNaturalOrder.INSTANCE);
        assertEquals(Arrays.asList("Alpha", "alpha 2", "告警", "告警 1 群", "告警 02 群", "告警 2 群", "告警 10 群"), names);
    }

    @Test
    void longNumbersDoNotOverflow()
    {
        List<String> names = new ArrayList<>(Arrays.asList("群 99999999999999999999", "群 100000000000000000000", "群 3"));
        names.sort(MonitorNaturalOrder.INSTANCE);
        assertEquals(Arrays.asList("群 3", "群 99999999999999999999", "群 100000000000000000000"), names);
    }
}
