package com.fivetech.dashboard.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;

/**
 * 导出模板 v1.5（《导出模板样例-v1_5》）的公共写法。
 *
 * <ul>
 *   <li>第 1 行即表头，第 2 行起为数据；表头上方、表格下方不加说明行；表头冻结、加粗、浅灰底；</li>
 *   <li>工作表名与表头用界面文案（本期繁体）；表头带单位：(INR)、(%)、(分鐘)、(倍)、(小時)；</li>
 *   <li>金额、比率写纯数值，无符号与千分位；百分比存百分数值（92.12）；空值为空单元格；</li>
 *   <li>时间写 Excel 日期时间值（IST）：明细 YYYY-MM-DD HH:mm:ss，统计起止 YYYY-MM-DD HH:mm，分群日 YYYY-MM-DD；</li>
 *   <li>「口径说明」每列一行（工作表 / 列 / 口径 / 单位格式），「导出说明」只写本次导出特有的信息。</li>
 * </ul>
 *
 * @author fivetech
 */
public final class ExportTemplate
{
    private ExportTemplate()
    {
    }

    public static final String FMT_SLOT = "yyyy-mm-dd hh:mm";

    public static final String FMT_DATETIME = "yyyy-mm-dd hh:mm:ss";

    public static final String FMT_DATE = "yyyy-mm-dd";

    public static final String DISCLAIMER = "本檔為業務參考，財務數據以財務系統為準";

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter SLOT_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Excel 单元格文本上限 32767 字符 */
    private static final int MAX_CELL_TEXT = 32000;

    /** Excel 单张 sheet 行数上限（含表头） */
    private static final int MAX_SHEET_ROWS = 1_048_575;

    // ===================== 列定义 =====================

    public enum Kind
    {
        TEXT, NUMBER, DATETIME, SLOT, DATE
    }

    /** 一列：表头、类型、数字格式（仅 NUMBER）、列宽（字符数） */
    public static final class Col
    {
        final String header;

        final Kind kind;

        final String format;

        final int width;

        private Col(String header, Kind kind, String format, int width)
        {
            this.header = header;
            this.kind = kind;
            this.format = format;
            this.width = width;
        }

        public static Col text(String header)
        {
            return new Col(header, Kind.TEXT, null, Math.max(10, header.length() * 2 + 2));
        }

        public static Col text(String header, int width)
        {
            return new Col(header, Kind.TEXT, null, width);
        }

        /** 数字列；format 为 Excel 数字格式，null 为 General */
        public static Col num(String header, String format)
        {
            return new Col(header, Kind.NUMBER, format, Math.max(10, header.length() * 2 + 2));
        }

        public static Col dateTime(String header)
        {
            return new Col(header, Kind.DATETIME, FMT_DATETIME, 20);
        }

        public static Col slot(String header)
        {
            return new Col(header, Kind.SLOT, FMT_SLOT, 17);
        }

        public static Col date(String header)
        {
            return new Col(header, Kind.DATE, FMT_DATE, 12);
        }

        public String getHeader()
        {
            return header;
        }
    }

    // ===================== 工作簿 =====================

    /** 一个导出文件。streaming=true 用 SXSSF（明细大表），否则 XSSF */
    public static final class Book implements AutoCloseable
    {
        private final Workbook wb;

        private final CellStyle head;

        private final CellStyle text;

        private final CellStyle wrap;

        private final Map<String, CellStyle> formats = new HashMap<>();

        public Book(boolean streaming)
        {
            if (streaming)
            {
                SXSSFWorkbook s = new SXSSFWorkbook(200);
                s.setCompressTempFiles(true);
                wb = s;
            }
            else
            {
                wb = new XSSFWorkbook();
            }
            Font bold = wb.createFont();
            bold.setBold(true);
            head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            if (head instanceof XSSFCellStyle)
            {
                ((XSSFCellStyle) head).setFillForegroundColor(
                    new XSSFColor(new byte[] { (byte) 0xEE, (byte) 0xF1, (byte) 0xF6 }, null));
            }
            text = wb.createCellStyle();
            wrap = wb.createCellStyle();
            wrap.setWrapText(true);
        }

        /** 新建工作表：冻结表头 */
        public Sheet sheet(String name)
        {
            Sheet sheet = wb.createSheet(name);
            sheet.createFreezePane(0, 1);
            return sheet;
        }

        /** 写表头（第 1 行）并设列宽，返回下一行行号 */
        public int header(Sheet sheet, List<Col> cols)
        {
            Row row = sheet.createRow(0);
            for (int i = 0; i < cols.size(); i++)
            {
                Cell c = row.createCell(i);
                c.setCellValue(cols.get(i).header);
                c.setCellStyle(head);
                sheet.setColumnWidth(i, Math.min(255, cols.get(i).width) * 256);
            }
            return 1;
        }

        /** 写一行；null 写空单元格 */
        public void row(Sheet sheet, int r, List<Col> cols, List<?> values)
        {
            if (r > MAX_SHEET_ROWS)
            {
                throw new ServiceException("導出行數超過 Excel 單表上限，請縮小篩選範圍");
            }
            Row row = sheet.createRow(r);
            for (int i = 0; i < values.size() && i < cols.size(); i++)
            {
                Object v = values.get(i);
                if (v == null || (v instanceof String && ((String) v).isEmpty()))
                {
                    continue;
                }
                Col col = cols.get(i);
                Cell cell = row.createCell(i);
                switch (col.kind)
                {
                    case NUMBER:
                        Double d = toDouble(v);
                        if (d == null)
                        {
                            setText(cell, String.valueOf(v));
                        }
                        else
                        {
                            cell.setCellValue(d);
                            cell.setCellStyle(format(col.format));
                        }
                        break;
                    case DATETIME:
                    case SLOT:
                    case DATE:
                        LocalDateTime t = toDateTime(v);
                        if (t == null)
                        {
                            setText(cell, String.valueOf(v));
                        }
                        else
                        {
                            cell.setCellValue(Date.from(t.atZone(ZoneId.systemDefault()).toInstant()));
                            cell.setCellStyle(format(col.format));
                        }
                        break;
                    default:
                        setText(cell, String.valueOf(v));
                }
            }
        }

        private void setText(Cell cell, String s)
        {
            cell.setCellValue(s.length() > MAX_CELL_TEXT ? s.substring(0, MAX_CELL_TEXT) + "…" : s);
            cell.setCellStyle(text);
        }

        private CellStyle format(String pattern)
        {
            String p = pattern == null ? "General" : pattern;
            return formats.computeIfAbsent(p, k -> {
                CellStyle s = wb.createCellStyle();
                s.setDataFormat(wb.createDataFormat().getFormat(k));
                return s;
            });
        }

        /** 「口径说明」：工作表 / 列 / 口径 / 单位格式 */
        public void notes(String sheetName, List<String[]> lines)
        {
            Sheet sheet = sheet(sheetName);
            List<Col> cols = List.of(Col.text("工作表", 14), Col.text("列", 26), Col.text("口徑", 70),
                Col.text("單位 / 格式", 30));
            int r = header(sheet, cols);
            for (String[] line : lines)
            {
                row(sheet, r++, cols, java.util.Arrays.asList((Object[]) line));
            }
        }

        /** 「导出说明」：项目 / 内容 */
        public void info(String sheetName, Map<String, String> items)
        {
            Sheet sheet = sheet(sheetName);
            List<Col> cols = List.of(Col.text("項目", 12), Col.text("內容", 90));
            int r = header(sheet, cols);
            for (Map.Entry<String, String> e : items.entrySet())
            {
                row(sheet, r++, cols, List.of(e.getKey(), e.getValue() == null ? "" : e.getValue()));
            }
        }

        public byte[] toBytes()
        {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream())
            {
                wb.write(out);
                return out.toByteArray();
            }
            catch (IOException e)
            {
                throw new ServiceException("導出文件生成失敗：" + e.getMessage(), 5002);
            }
        }

        @Override
        public void close()
        {
            if (wb instanceof SXSSFWorkbook)
            {
                // SXSSF 的临时文件必须显式清理
                ((SXSSFWorkbook) wb).dispose();
            }
            try
            {
                wb.close();
            }
            catch (IOException ignore)
            {
                // 关闭失败不影响已生成的字节
            }
        }
    }

    // ===================== 导出说明 =====================

    /**
     * 「导出说明」的固定五项：导出时间、导出人、站点、筛选条件、免责声明；extra 追加在筛选条件之后。
     */
    public static Map<String, String> exportInfo(DashboardProperties properties, String siteCode, String filter,
            Map<String, String> extra)
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("導出時間", now(properties).format(HUMAN));
        m.put("導出人", currentUser());
        String code = StringUtils.isEmpty(siteCode) ? properties.getDefaultSite() : siteCode;
        String name = properties.getSiteName();
        m.put("站點", StringUtils.isEmpty(name) ? code : name + " · " + code);
        m.put("篩選條件", StringUtils.isEmpty(filter) ? "無" : filter);
        if (extra != null)
        {
            extra.forEach((k, v) -> {
                if (StringUtils.isNotEmpty(v))
                {
                    m.put(k, v);
                }
            });
        }
        m.put("免責聲明", DISCLAIMER);
        return m;
    }

    /** 文件名：{页面}-yyyyMMdd-HHmmss.xlsx（统计时区） */
    public static String fileName(DashboardProperties properties, String page)
    {
        return page + "-" + now(properties).format(FILE_STAMP) + ".xlsx";
    }

    public static LocalDateTime now(DashboardProperties properties)
    {
        try
        {
            return LocalDateTime.now(ZoneId.of(properties.getTimezone()));
        }
        catch (Exception e)
        {
            return LocalDateTime.now();
        }
    }

    /** 导出人：优先邮箱，没有邮箱时用登录账号 */
    public static String currentUser()
    {
        try
        {
            LoginUser loginUser = SecurityUtils.getLoginUser();
            SysUser user = loginUser == null ? null : loginUser.getUser();
            if (user != null && StringUtils.isNotEmpty(user.getEmail()))
            {
                return user.getEmail();
            }
            return SecurityUtils.getUsername();
        }
        catch (Exception e)
        {
            return "-";
        }
    }

    /** 「2026-09-13 00:00 – 14:00」：同一天时结束只写时分 */
    public static String range(String from, String to)
    {
        LocalDateTime f = toDateTime(from);
        LocalDateTime t = toDateTime(to);
        if (f == null || t == null)
        {
            return StringUtils.isEmpty(from) && StringUtils.isEmpty(to) ? "" : nvl(from) + " – " + nvl(to);
        }
        String end = f.toLocalDate().equals(t.toLocalDate()) ? t.format(DateTimeFormatter.ofPattern("HH:mm"))
            : t.format(SLOT_TEXT);
        return f.format(SLOT_TEXT) + " – " + end;
    }

    private static String nvl(String s)
    {
        return s == null ? "" : s;
    }

    // ===================== 取值 =====================

    /** 数字：Number 直接取；"12.3%"、"12.3" 这类文本也能解析 */
    public static Double toDouble(Object v)
    {
        if (v instanceof Number)
        {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String)
        {
            String s = ((String) v).replace("%", "").replace(",", "").trim();
            if (s.isEmpty())
            {
                return null;
            }
            try
            {
                return Double.valueOf(s);
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }
        return null;
    }

    public static BigDecimal toDecimal(Object v)
    {
        Double d = toDouble(v);
        return d == null ? null : BigDecimal.valueOf(d);
    }

    /**
     * 时间：LocalDateTime / LocalDate 直接用；文本支持
     * yyyy-MM-dd、yyyy-MM-dd HH:mm、yyyy-MM-dd HH:mm:ss、带 T 的 ISO 写法与小数秒。
     */
    public static LocalDateTime toDateTime(Object v)
    {
        if (v instanceof LocalDateTime)
        {
            return (LocalDateTime) v;
        }
        if (v instanceof LocalDate)
        {
            return ((LocalDate) v).atStartOfDay();
        }
        if (!(v instanceof String))
        {
            return null;
        }
        String s = ((String) v).trim().replace('T', ' ');
        if (s.isEmpty())
        {
            return null;
        }
        int dot = s.indexOf('.');
        if (dot > 0)
        {
            s = s.substring(0, dot);
        }
        try
        {
            if (s.length() == 10)
            {
                return LocalDate.parse(s).atStartOfDay();
            }
            if (s.length() == 16)
            {
                return LocalDateTime.parse(s, SLOT_TEXT);
            }
            if (s.length() == 19)
            {
                return LocalDateTime.parse(s, HUMAN);
            }
        }
        catch (Exception ignore)
        {
            // 落到下面返回 null，按文本写出
        }
        return null;
    }

    /** (当前 − 上期) ÷ 上期 × 100，一位小数；上期为 0 或无数据时为 null */
    public static BigDecimal changePct(BigDecimal cur, BigDecimal prev)
    {
        if (cur == null || prev == null || prev.signum() == 0)
        {
            return null;
        }
        return cur.subtract(prev).multiply(BigDecimal.valueOf(100))
            .divide(prev.abs(), 1, java.math.RoundingMode.HALF_UP);
    }

    // ===================== 繁体 =====================

    private static final String[][] WORDS = {
        { "注册", "註冊" }, { "备注", "備註" }, { "账号", "帳號" }, { "账户", "帳戶" }, { "周期", "週期" },
        { "一周", "一週" }, { "上周", "上週" }, { "本周", "本週" }, { "游戏", "遊戲" }, { "小游戏", "小遊戲" },
        { "里程", "里程" }, { "只有", "只有" }, { "后台", "後台" }, { "之后", "之後" }, { "以后", "以後" },
        { "前后", "前後" }, { "最后", "最後" }, { "发放", "發放" }, { "出发", "出發" } };

    private static final Map<Character, Character> CHARS = new HashMap<>();

    static
    {
        String pairs = "机機 鱼魚 体體 游遊 戏戲 发發 结結 败敗 审審 驳駁 试試 账帳 号號 户戶 观觀 险險 铁鐵 铜銅 银銀 黄黃 铂鉑 伪偽 启啟 尔爾 兰蘭 冻凍 验驗 证證 处處 过過 获獲 转轉 资資 链鏈 赠贈 额額 总總 数數 时時 间間 钟鐘 册冊 页頁 设設 统統 计計 区區 级級 风風 类類 态態 状狀 国國 际際 历歷 笔筆 亏虧 厂廠 码碼 订訂 单單 创創 备備 称稱 长長 东東 门門 问問 闻聞 网網 络絡 开開 关關 现現 实實 经經 营營 运運 览覽 汇匯 报報 导導 说說 条條 选選 择擇 围圍 范範 环環 对對 应應 场場 赛賽 电電 竞競 签簽 会會 员員 动動 广廣 来來 标標 记記 杀殺 净淨 录錄 访訪 达達 换換 华華 业業 务務 财財 准準 为為 参參 责責 声聲 测測 与與 产產 价價 让讓 这這 个個 们們 还還 没沒 进進 从從 将將 无無 于於 种種 币幣 读讀 写寫 见見 规規 则則 样樣 两兩 万萬 亿億 几幾 当當 变變 组組 项項 构構 热熱 销銷 阵陣 据據 顺順 识識 别別 异異 终終 内內 视視 赢贏 输輸 余餘 确確 认認 闭閉 题題 显顯 隐隱 节節 点點 线線 图圖 画畫 质質 车車 杂雜 话話 语語 讯訊 档檔 载載 继繼 续續 损損 盘盤 赔賠 宝寶 买買 卖賣 贵貴 费費 优優 奖獎 励勵 领領 钱錢 键鍵 锁鎖 错錯 张張 罚罰 帮幫 带帶 师師 归歸 势勢 边邊 迟遲 递遞 远遠 连連 逻邏 调調 谁誰 请請 论論 诉訴 诚誠 课課 谢謝 贡貢 货貨 购購 贴貼 贷貸 贸貿 趋趨 软軟 轻輕 较較 违違 适適 邮郵 释釋 钢鋼 闪閃 闲閒 阅閱 队隊 阳陽 阶階 陆陸 随隨 难難 静靜 顶頂 须須 顾顧 预預 频頻 飞飛 马馬 驾駕 鸡雞 齐齊 龙龍 鉴鑒 拨撥 审審 绝絕 断斷 级級 级級 极極 构構 涨漲 跌跌 胜勝 负負 战戰 局局 庄莊 闲閒 彩彩 票票 捕捕 街街 桌桌 钻鑽 卡卡 牌牌 视視 讯訊 维維 护護 举舉 报報 补補 冲衝 担擔 状狀 级級 侧側 规規 范範 仅僅 间間 码碼 跃躍 询詢 径徑 复復 积積 纪紀 钮鈕 谈談 宽寬 窄窄 灵靈 态態 惯慣 职職 称稱 权權 树樹 剩剩 余餘 担擔 额額 赖賴 际際 详詳 细細 简簡 体體 们們 庄莊 盖蓋 层層 级級";
        for (String pair : pairs.split(" "))
        {
            if (pair.length() == 2)
            {
                CHARS.putIfAbsent(pair.charAt(0), pair.charAt(1));
            }
        }
        // 「系」在「系统」里是「系統」，「关系」里才是「關係」；这里只出现前一种
        CHARS.put('系', '系');
        // 「里」：本模块只出现「公里/里程」类用法的概率极低，统一按「裡」会出错，保持原字
        CHARS.put('里', '里');
    }

    /** 界面文案简→繁（只覆盖本模块会出现的字词；用户数据如渠道名、游戏名不要转换） */
    public static String t(String s)
    {
        if (s == null || s.isEmpty())
        {
            return s;
        }
        String r = s;
        for (String[] w : WORDS)
        {
            r = r.replace(w[0], w[1]);
        }
        StringBuilder sb = new StringBuilder(r.length());
        for (int i = 0; i < r.length(); i++)
        {
            char c = r.charAt(i);
            Character m = CHARS.get(c);
            sb.append(m == null ? c : m);
        }
        return sb.toString();
    }

    /** 常用的三行公共口径 */
    public static List<String[]> commonNotes(boolean detail)
    {
        List<String[]> l = new ArrayList<>();
        if (detail)
        {
            l.add(new String[] { "（全部）", "時間", "時區 IST（UTC+5:30）", "YYYY-MM-DD HH:mm:ss" });
            l.add(new String[] { "（全部）", "空值", "空單元格表示無值或不適用，不是 0", "" });
        }
        else
        {
            l.add(new String[] { "（全部）", "時間",
                "統計時區 IST（UTC+5:30）；「開始」含、「結束」不含（00:00–01:00 即 0 點這一小時）；今日的「結束」為資料截至時刻",
                "YYYY-MM-DD HH:mm" });
            l.add(new String[] { "（全部）", "空值", "空單元格表示無值或不適用，不是 0", "" });
            l.add(new String[] { "（全部）", "比率", "百分數值：92.12 表示 92.12%", "" });
        }
        return l;
    }
}
