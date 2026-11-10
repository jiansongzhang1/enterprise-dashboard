# 推广分析 · 字段数据检查

- 环境：https://d3ajrxvlugsarb.cloudfront.net/prod-api（站点 SITE-IN）
- 时间：2026-11-09T08:54:24.046Z；取数区间 2026-11-02 ~ 2026-11-09（不含）
- 汇总：接口报错 2，未接入 47，未配置 42，口径待确认 18，无数据 2，未到期 10，提示 2，有值 64

## 接口报错

| 位置 | 字段 | 说明 |
|---|---|---|
| 用户明细·筛选 | 按终端 Web | code=5001 用戶明細的數據還沒有「註冊終端」字段（數據平台待提供），暫時不能按它篩選 |
| 用户明细·筛选 | 按推广商 | code=5001 用戶明細的數據還沒有「註冊推廣渠道」字段（數據平台待提供），暫時不能按它篩選 |

## 未接入

| 位置 | 字段 | 说明 |
|---|---|---|
| 概览指标卡 | spend 消耗 | state=NO_SOURCE；數據待接入；value=null prev=null；走势 0/7 点有值 |
| 概览指标卡 | budget 預算 | state=NO_SOURCE；數據待接入；value=null prev=null；走势 0/7 点有值 |
| 概览指标卡 | cpa CPA | state=NO_SOURCE；數據待接入；value=null prev=null；走势 0/7 点有值 |
| 概览指标卡 | ftdCost 首存成本 | state=NO_SOURCE；數據待接入；value=null prev=null；走势 0/7 点有值 |
| 概览指标卡 | roi D0 ROI | state=NO_SOURCE；數據待接入；value=null prev=null；走势 0/7 点有值 |
| 概览指标卡 | hUV UV-主頁 | state=NO_SOURCE；數據待接入；value=null prev=null；走势 0/7 点有值 |
| 变化归因·agent | net 淨充值 | PENDING_SOURCE：數據平台還沒有按推廣商拆分的數據，暫不能歸因。 |
| 变化归因·agent | rd 復存人數 | PENDING_SOURCE：數據平台還沒有按推廣商拆分的數據，暫不能歸因。 |
| 变化归因·channel | net 淨充值 | PENDING_SOURCE：數據平台還沒有按推廣渠道拆分的數據，暫不能歸因。 |
| 变化归因·channel | rd 復存人數 | PENDING_SOURCE：數據平台還沒有按推廣渠道拆分的數據，暫不能歸因。 |
| 渠道表现·date›terminal | hPV PV-主頁 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | hUV UV-主頁 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | spend 消耗 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | budget 預算 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | cpa CPA | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | ftdCost 首存成本 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | roi D0 ROI | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | regRet1 次日註冊留存 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=PENDING value=null |
| 渠道表现·date›terminal | regRet3 3 日註冊留存 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=PENDING value=null |
| 渠道表现·date›terminal | wdU 提款人數 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | wd 提款總額 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | vbet 有效投注 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | ggr GGR | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | hPV PV-主頁 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | hUV UV-主頁 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | spend 消耗 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | budget 預算 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | cpa CPA | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | ftdCost 首存成本 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | roi D0 ROI | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | regRet1 次日註冊留存 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | regRet3 3 日註冊留存 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | wdU 提款人數 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | wd 提款總額 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | vbet 有效投注 | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | ggr GGR | 合计 state=NO_SOURCE value=null；數據待接入；首行 state=NO_SOURCE value=null |
| 获客漏斗·整体 | hUV UV-主頁 | value=null；數據待接入 |
| 获客漏斗·终端步骤 | web · hUV | value=null rate=null；數據待接入 |
| 获客漏斗·终端步骤 | ios · hUV | value=null rate=null；數據待接入 |
| 留存与回收·概要 | regRet1 | state=NO_SOURCE |
| 留存与回收·概要 | regRet7 | state=NO_SOURCE |
| 留存与回收·概要 | ltv7 | state=NO_SOURCE |
| 留存与回收·概要 | roas7 | state=NO_SOURCE |
| 留存与回收·明细 | regRet | 30 行；dataPending=true |
| 留存与回收·明细 | ltv | 30 行；dataPending=true |
| 留存与回收·明细 | roas | 30 行；dataPending=true |
| 裂变层级 | 顶层一级代理清单 | 0 行；notices=FISSION_TOP_PENDING |

## 未配置

| 位置 | 字段 | 说明 |
|---|---|---|
| 变化归因·terminal | spend 消耗 | NOT_CONFIGURED：消耗是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | budget 預算 | NOT_CONFIGURED：預算是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | cpa CPA | NOT_CONFIGURED：CPA是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | ftdCost 首存成本 | NOT_CONFIGURED：首存成本是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | roi D0 ROI | NOT_CONFIGURED：D0 ROI是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | lpUV UV-落地頁 | NOT_CONFIGURED：UV-落地頁是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | hUV UV-主頁 | NOT_CONFIGURED：UV-主頁是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | dl 下載點擊 | NOT_CONFIGURED：下載點擊是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | act 激活 | NOT_CONFIGURED：激活是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | reg 註冊人數 | NOT_CONFIGURED：註冊人數是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | regR 註冊轉化率 | NOT_CONFIGURED：註冊轉化率是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | ftd 首存人數 | NOT_CONFIGURED：首存人數是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | ftdR 首存轉化率 | NOT_CONFIGURED：首存轉化率是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·terminal | ftdAmt 首存金額 | NOT_CONFIGURED：首存金額是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | spend 消耗 | NOT_CONFIGURED：消耗是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | budget 預算 | NOT_CONFIGURED：預算是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | cpa CPA | NOT_CONFIGURED：CPA是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | ftdCost 首存成本 | NOT_CONFIGURED：首存成本是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | roi D0 ROI | NOT_CONFIGURED：D0 ROI是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | lpUV UV-落地頁 | NOT_CONFIGURED：UV-落地頁是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | hUV UV-主頁 | NOT_CONFIGURED：UV-主頁是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | dl 下載點擊 | NOT_CONFIGURED：下載點擊是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | act 激活 | NOT_CONFIGURED：激活是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | reg 註冊人數 | NOT_CONFIGURED：註冊人數是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | regR 註冊轉化率 | NOT_CONFIGURED：註冊轉化率是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | ftd 首存人數 | NOT_CONFIGURED：首存人數是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | ftdR 首存轉化率 | NOT_CONFIGURED：首存轉化率是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·agent | ftdAmt 首存金額 | NOT_CONFIGURED：首存金額是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | spend 消耗 | NOT_CONFIGURED：消耗是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | budget 預算 | NOT_CONFIGURED：預算是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | cpa CPA | NOT_CONFIGURED：CPA是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | ftdCost 首存成本 | NOT_CONFIGURED：首存成本是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | roi D0 ROI | NOT_CONFIGURED：D0 ROI是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | lpUV UV-落地頁 | NOT_CONFIGURED：UV-落地頁是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | hUV UV-主頁 | NOT_CONFIGURED：UV-主頁是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | dl 下載點擊 | NOT_CONFIGURED：下載點擊是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | act 激活 | NOT_CONFIGURED：激活是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | reg 註冊人數 | NOT_CONFIGURED：註冊人數是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | regR 註冊轉化率 | NOT_CONFIGURED：註冊轉化率是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | ftd 首存人數 | NOT_CONFIGURED：首存人數是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | ftdR 首存轉化率 | NOT_CONFIGURED：首存轉化率是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |
| 变化归因·channel | ftdAmt 首存金額 | NOT_CONFIGURED：首存金額是比率類指標，變化同時來自各渠道自身的變化和渠道結構的變化，不能拆成各渠道的貢獻。整體變化見上方趨勢；各渠道的值可在渠道表現查看。 |

## 口径待确认

| 位置 | 字段 | 说明 |
|---|---|---|
| 概览指标卡 | lpUV UV-落地頁 | state=NO_SOURCE；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；value=null prev=null；走势 0/7 点有值 |
| 概览指标卡 | dl 下載點擊 | state=NO_SOURCE；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；value=null prev=null；走势 0/7 点有值 |
| 概览指标卡 | regR 註冊轉化率 | state=NO_SOURCE；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；value=null prev=null；走势 0/7 点有值 |
| 渠道表现·date›terminal | lpPV PV-落地頁 | 合计 state=NO_SOURCE value=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | lpUV UV-落地頁 | 合计 state=NO_SOURCE value=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | dl 下載點擊 | 合计 state=NO_SOURCE value=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | regR 註冊轉化率 | 合计 state=NO_SOURCE value=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；首行 state=NO_SOURCE value=null |
| 渠道表现·date›terminal | uvCost UV 成本 | 合计 state=NO_SOURCE value=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | lpPV PV-落地頁 | 合计 state=NO_SOURCE value=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | lpUV UV-落地頁 | 合计 state=NO_SOURCE value=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | dl 下載點擊 | 合计 state=NO_SOURCE value=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | regR 註冊轉化率 | 合计 state=NO_SOURCE value=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；首行 state=NO_SOURCE value=null |
| 渠道表现·agent›channel | uvCost UV 成本 | 合计 state=NO_SOURCE value=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示；首行 state=NO_SOURCE value=null |
| 获客漏斗·整体 | lpUV UV-落地頁 | value=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示 |
| 获客漏斗·终端步骤 | web · lpUV | value=null rate=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示 |
| 获客漏斗·终端步骤 | android · lpUV | value=null rate=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示 |
| 获客漏斗·终端步骤 | android · dl | value=null rate=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示 |
| 获客漏斗·终端步骤 | ios · lpUV | value=null rate=null；口徑待確認（數據平台字段與頁面口徑不同），暫不展示 |

## 无数据

| 位置 | 字段 | 说明 |
|---|---|---|
| 用户明细·筛选 | 默认（近 N 天注册） | 0 行 / total=0 |
| 用户明细·筛选 | 流失 lost=true | 0 行 / total=0 |

## 未到期

| 位置 | 字段 | 说明 |
|---|---|---|
| 渠道表现·date›terminal | roas7 ROAS D7 | 合计 state=PENDING value=null；首行 state=PENDING value=null |
| 渠道表现·date›terminal | roas30 ROAS D30 | 合计 state=PENDING value=null；首行 state=PENDING value=null |
| 渠道表现·date›terminal | regRet7 7 日註冊留存 | 合计 state=PENDING value=null；首行 state=PENDING value=null |
| 渠道表现·date›terminal | regRet15 15 日註冊留存 | 合计 state=PENDING value=null；首行 state=PENDING value=null |
| 渠道表现·date›terminal | regRet30 30 日註冊留存 | 合计 state=PENDING value=null；首行 state=PENDING value=null |
| 渠道表现·agent›channel | roas7 ROAS D7 | 合计 state=PENDING value=null；首行 state=PENDING value=null |
| 渠道表现·agent›channel | roas30 ROAS D30 | 合计 state=PENDING value=null；首行 state=PENDING value=null |
| 渠道表现·agent›channel | regRet7 7 日註冊留存 | 合计 state=PENDING value=null；首行 state=PENDING value=null |
| 渠道表现·agent›channel | regRet15 15 日註冊留存 | 合计 state=PENDING value=null；首行 state=PENDING value=null |
| 渠道表现·agent›channel | regRet30 30 日註冊留存 | 合计 state=PENDING value=null；首行 state=PENDING value=null |

## 提示

| 位置 | 字段 | 说明 |
|---|---|---|
| 概览提示 | PROMO_DATA_PARTIAL |  部分指標暫無數據 |
| 概览提示 | PROMO_DATA_PARTIAL |  部分指標暫無數據 |

## 有值

选项：agents（推广商字典）；选项：terminals（终端字典）；选项：siteLaunchDate；概览指标卡：net 淨充值；概览指标卡：act 激活；概览指标卡：reg 註冊人數；概览指标卡：ftd 首存人數；概览指标卡：ftdR 首存轉化率；概览指标卡：ftdAmt 首存金額；概览指标卡：rd 復存人數；变化归因·terminal：net 淨充值；变化归因·terminal：rd 復存人數；渠道表现·date›terminal：行数（第 1 层）；渠道表现·date›terminal：act 激活；渠道表现·date›terminal：reg 註冊人數；渠道表现·date›terminal：ftd 首存人數；渠道表现·date›terminal：ftdR 首存轉化率；渠道表现·date›terminal：depR 存款率；渠道表现·date›terminal：ftdAmt 首存金額；渠道表现·date›terminal：rd 復存人數；渠道表现·date›terminal：rdAmt 復存金額；渠道表现·date›terminal：rdR 復存率；渠道表现·date›terminal：depU 存款人數；渠道表现·date›terminal：dep 存款總額；渠道表现·date›terminal：net 淨充值；渠道表现·date›terminal：arppu ARPPU；渠道表现·date›terminal：betU 投注人數；渠道表现·date›terminal：bet 投注總額；渠道表现·agent›channel：行数（第 1 层）；渠道表现·agent›channel：act 激活；渠道表现·agent›channel：reg 註冊人數；渠道表现·agent›channel：ftd 首存人數；渠道表现·agent›channel：ftdR 首存轉化率；渠道表现·agent›channel：depR 存款率；渠道表现·agent›channel：ftdAmt 首存金額；渠道表现·agent›channel：rd 復存人數；渠道表现·agent›channel：rdAmt 復存金額；渠道表现·agent›channel：rdR 復存率；渠道表现·agent›channel：depU 存款人數；渠道表现·agent›channel：dep 存款總額；渠道表现·agent›channel：net 淨充值；渠道表现·agent›channel：arppu ARPPU；渠道表现·agent›channel：betU 投注人數；渠道表现·agent›channel：bet 投注總額；获客漏斗·整体：reg 註冊人數；获客漏斗·整体：ftd 首存人數；获客漏斗·终端：web 注册 / 流失；获客漏斗·终端步骤：web · reg；获客漏斗·终端步骤：web · ftd；获客漏斗·终端：android 注册 / 流失；获客漏斗·终端步骤：android · act；获客漏斗·终端步骤：android · reg；获客漏斗·终端步骤：android · ftd；获客漏斗·终端：ios 注册 / 流失；获客漏斗·终端步骤：ios · reg；获客漏斗·终端步骤：ios · ftd；获客漏斗：regShare（各终端注册占比）；用户明细：options.terminals / agents；预算与消耗：消耗（UDS import_yata_promo_cost）；预算与消耗：预算（UDS import_yata_promo_budget）；投放日报：昨日导出；个人视图：promo.overview.cards；个人视图：promo.channel.columns；个人视图：promo.channel.rows

## 请求记录

| 请求 | HTTP | code | 耗时 ms |
|---|---|---|---|
| 推广分析选项 /dashboard/promo/options | 200 | 200 | 606 |
| 概览 spend,budget,cpa,ftdCost,net,roi,lpUV,hUV,dl,act | 200 | 200 | 2465 |
| 概览 reg,regR,ftd,ftdR,ftdAmt,rd | 200 | 200 | 116 |
| 概览归因 terminal spend,budget,cpa,ftdCost,net,roi,lpUV,hUV,dl,act | 200 | 200 | 119 |
| 概览归因 terminal reg,regR,ftd,ftdR,ftdAmt,rd | 200 | 200 | 118 |
| 概览归因 agent spend,budget,cpa,ftdCost,net,roi,lpUV,hUV,dl,act | 200 | 200 | 129 |
| 概览归因 agent reg,regR,ftd,ftdR,ftdAmt,rd | 200 | 200 | 227 |
| 概览归因 channel spend,budget,cpa,ftdCost,net,roi,lpUV,hUV,dl,act | 200 | 200 | 127 |
| 概览归因 channel reg,regR,ftd,ftdR,ftdAmt,rd | 200 | 200 | 128 |
| 渠道表现 date›terminal | 200 | 200 | 213 |
| 渠道表现 agent›channel | 200 | 200 | 211 |
| 获客漏斗 | 200 | 200 | 373 |
| 留存概要 | 200 | 200 | 246 |
| 留存明细 regRet | 200 | 200 | 119 |
| 留存明细 ltv | 200 | 200 | 229 |
| 留存明细 roas | 200 | 200 | 201 |
| 裂变层级 顶层 | 200 | 200 | 128 |
| 用户明细选项 | 200 | 200 | 126 |
| 用户明细 默认（近 N 天注册） | 200 | 200 | 451 |
| 用户明细 按终端 Web | 200 | 5001 | 120 |
| 用户明细 流失 lost=true | 200 | 200 | 205 |
| 用户明细 按推广商 | 200 | 5001 | 115 |
| 预算与消耗 SPEND | 200 | 200 | 225 |
| 预算与消耗 BUDGET | 200 | 200 | 139 |
| 投放日报 昨日导出 | 200 |  | 258 |
| 个人视图 PROMO | 200 | 200 | 127 |
