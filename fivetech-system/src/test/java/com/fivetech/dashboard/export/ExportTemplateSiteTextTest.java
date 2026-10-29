package com.fivetech.dashboard.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.export.ExportTemplate.Book;

/** 导出文案按站点：模板里的「(INR)」「INR，」「（IST）」换成站点币种和时区；时区说明不再写死 IST */
class ExportTemplateSiteTextTest
{
    @Test
    void localizeCurrencyAndZone() throws Exception
    {
        try (Book book = new Book(false).site("CNY", "UTC+8"))
        {
            assertEquals("金額(CNY)", book.localize("金額(INR)"));
            assertEquals("CNY，純數值，2 位小數", book.localize("INR，純數值，2 位小數"));
            assertEquals("YYYY-MM-DD HH:mm:ss（UTC+8）", book.localize("YYYY-MM-DD HH:mm:ss（IST）"));
            assertEquals("India_DDHH", book.localize("India_DDHH"), "其他文字不动");
            assertEquals(null, book.localize(null));
        }
    }

    @Test
    void defaultSiteUnchanged() throws Exception
    {
        try (Book book = new Book(false).site("INR", "IST"))
        {
            assertEquals("金額(INR)", book.localize("金額(INR)"));
            assertEquals("YYYY-MM-DD HH:mm:ss（IST）", book.localize("YYYY-MM-DD HH:mm:ss（IST）"));
        }
    }

    @Test
    void commonNotesUseSiteZone()
    {
        List<String[]> detail = ExportTemplate.commonNotes(true, "Asia/Shanghai（UTC+8）");
        assertEquals("時區 Asia/Shanghai（UTC+8）", detail.get(0)[2]);
        List<String[]> summary = ExportTemplate.commonNotes(false, "IST（UTC+5:30）");
        assertTrue(summary.get(0)[2].startsWith("統計時區 IST（UTC+5:30）；"));
    }
}
