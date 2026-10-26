package com.fivetech.monitor.domain.dto;

import java.util.List;

/**
 * POST /monitor/events/handle：{ "eventIds": [1032, 1029], "note": "…" }
 *
 * @author fivetech
 */
public class MonitorHandleBody
{
    private List<Long> eventIds;

    private String note;

    public List<Long> getEventIds() { return eventIds; }
    public void setEventIds(List<Long> eventIds) { this.eventIds = eventIds; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
