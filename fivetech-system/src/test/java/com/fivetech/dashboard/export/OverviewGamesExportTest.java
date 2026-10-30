package com.fivetech.dashboard.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.query.RankingBoardQuery;
import com.fivetech.dashboard.domain.vo.overview.GameBoardVO;
import com.fivetech.dashboard.domain.vo.overview.GameItemVO;
import com.fivetech.dashboard.domain.vo.overview.RankingBoardVO;

/** 运营总览导出「熱銷遊戲」：遊戲名稱後面加「遊戲中文名」「遊戲英文名」兩列（game_name_cn / game_name_en，2026-10-29） */
class OverviewGamesExportTest
{
    @Test
    void gamesSheetHasCnAndEnNames() throws Exception
    {
        GameItemVO g = new GameItemVO();
        g.setRank(1);
        g.setName("Aviator");
        g.setGameNameCn("飛行員");
        g.setGameNameEn("Aviator");
        g.setVendorName("SPRIBE");
        g.setPlatformCode("SPRIBE");
        g.setGameId("SPB-1001");
        g.setBetAmount(new BigDecimal("3158470"));
        GameItemVO noName = new GameItemVO();
        noName.setRank(2);
        noName.setName("g2");
        noName.setGameId("g2");
        List<GameItemVO> items = new ArrayList<>(List.of(g, noName));
        GameBoardVO board = new GameBoardVO();
        board.setItems(items);
        RankingBoardVO vo = new RankingBoardVO();
        vo.setGames(board);
        RankingBoardQuery q = new RankingBoardQuery();
        q.setSlotFrom("2026-10-29 00:00");
        q.setSlotTo("2026-10-29 12:00");

        byte[] file = new OverviewWorkbookExporter(null, null, new DashboardProperties()).exportGames(q, vo);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file)))
        {
            Sheet sheet = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++)
            {
                if (wb.getSheetAt(i).getRow(0) != null && "遊戲名稱".equals(text(wb.getSheetAt(i).getRow(0), 3)))
                {
                    sheet = wb.getSheetAt(i);
                }
            }
            assertTrue(sheet != null, "找到熱銷遊戲 sheet");
            Row head = sheet.getRow(0);
            assertEquals("遊戲中文名", text(head, 4));
            assertEquals("遊戲英文名", text(head, 5));
            assertEquals("平台廠商名", text(head, 6));
            assertEquals("飛行員", text(sheet.getRow(1), 4));
            assertEquals("Aviator", text(sheet.getRow(1), 5));
            assertEquals("", text(sheet.getRow(2), 4), "上游沒有中文名時為空");
        }
    }

    private static String text(Row row, int col)
    {
        return row.getCell(col) == null ? "" : row.getCell(col).getStringCellValue();
    }
}
