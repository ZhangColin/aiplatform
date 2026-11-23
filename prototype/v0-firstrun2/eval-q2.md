# 儿童拼音识字练习应用 · 旧实现 / 新实现对照核验（第 2 题）

核验日期：2026-11-23　核验人：独立只读核验（不信生成方自述，只记自己看到的 HTTP 响应、数据库行、截图）

| | 旧应用 | 新应用 |
|---|---|---|
| 容器 | `v0-run2` | `v0r2-q2` |
| 宿主地址 | http://localhost:18092 | http://localhost:18112 |
| 运行方式（`ps` 看到的进程） | `next start`（生产模式） | `next dev`（开发模式；package.json 的 `start` 脚本被改成了 `next dev`） |
| 需求文档 | `C:/Users/zhang/AppData/Local/Temp/v0-firstrun/prd-run2-kids.md`（容器内 `/workspace/docs/PRD.md`） | 同左 |

核验手段：curl 探路由和 API、psql 查库、容器内 Chromium 截图（1366×900）并收集浏览器控制台错误、读源码核对逻辑。
旧应用上**零写入**（截图脚本把所有非 GET 请求全部拦截，拦截列表为空，事后查库确认无任何今日记录）；新应用上做了 4 次写操作（见第 6 节），全部通过页面按钮触发。

---

## 1. 逐条对照表

判定口径：完成＝页面上/API/库里都能看到且行为符合文档；部分＝主体在但有一处明显不达标或演示不出来；未做＝找不到。

| 条目 | 旧应用 | 新应用 | 证据 |
|---|---|---|---|
| 六-1 今日练习页：3 个游戏入口卡片＋各自完成状态＋「开始练习」按钮 | **完成**（实际是 4 张卡：认一认/连一连/找一找之外多做了一个「错题重练」） | **完成**（正好 3 张卡） | 旧：`/` 200；`/api/today` 返回 `games` 4 项，`done:false`，`nextGame:"recognize"`；首页截图有「开始练习 →」按钮、「今日星星 0 / 12」。新：`/` 200；`/api/today` 返回 `games` 3 项、`completedCount:0`、`nextGame:"recognize"`；截图三个卡片均「⏳ 待完成」、按钮「开始 ▶」、主按钮「🚀 开始练习」、右侧「已完成 0 / 3」 |
| 六-2 认一认：大卡片逐张展示拼音＋汉字＋emoji 配图，按钮「认识啦／下一个」，一轮 10 张 | **完成** | **完成** | 旧：`GET /api/games/recognize` 返回 `cards` 恰 10 张；截图第 2 张卡片显示拼音 jiǔ、大字「九」、emoji 9️⃣、例词，按钮「👏 认识啦」「下一个 →」，进度「第 2 / 10 张」。新：`GET /api/play/recognize` 返回 `cards` 恰 10 张；截图显示「拼音 shān / 大字 山 / 例词 大山 / ⛰️」，按钮「下一个 ▶」「👍 认识啦」，「第 1 / 10 张」；实际点完 10 张后 `POST /api/play/recognize` 返回 `stars:3` |
| 六-3 连一连：左 5 拼音、右 5 汉字，点击配对连线，答对有奖励反馈，答错可重试，共 4 轮 | **完成**（配对后真的画一条彩色 SVG 连线） | **完成**（配对成功用同色底＋✓＋🔗 表示，不画实体连线） | 旧：`GET /api/games/match` 返回 `rounds` 4 轮、每轮 `pairs` 5 组；截图配对一处后「rì—日」之间有一条粉色连线，标签「🎯 连对 1 / 5」「本轮错了 0 次」。新：`GET /api/play/match` 返回 `rounds` 4 轮、每轮 `pinyin` 5 个＋`hanzi` 5 个；截图第 2 轮连完后左侧「xiǎo 🔗」等、右侧「✓ 小 🐜」等同色，面板「🎉 第 2 轮全部连对！」按钮「下一轮 ▶」；答错时源码提示「这一对不是好朋友，再试一次～」并允许重选（我故意连错 1 次，最终提交体里 `mistakes:[{symbol:"shǎo",char:"七"}]`） |
| 六-4 找一找：顶部大字目标汉字（家长读题），下方 3 个选项卡片，点选正确得星，共 10 题 | **完成**（选项是 emoji 图片卡，选错可原地再选） | **完成**（选项是 3 个拼音文字卡，选错直接揭示答案进下一题，不能重选；文档对找一找没要求可重试） | 旧：`GET /api/games/find` 返回 `questions` 10 题、每题 `options` 3 个；截图「请家长读一读这个字 👇」、大字「九」jiǔ、3 张 emoji 卡，点中后中间卡变绿「答对啦 ✅」并有 ⭐ 飞出，标签「⭐ 小星星 1」。新：`GET /api/play/find` 返回 `questions` 10 题、每题 `options` 3 个拼音且都含正确项；截图「请家长读题：这个字读什么？」、大字「火」🔥、三张卡 huǒ/niǎo/cí；故意点错后 huǒ 变绿「✅ 答对啦」、niǎo 变红「再想想」、提示「正确的读音是「huǒ」」 |
| 六-5 拼音卡片集：23 声母＋24 韵母网格，点开看例字与例词 | **完成** | **完成** | 库：旧 `pinyin_cards` initial 23 / final 24；新 `pinyin_cards` initial 23 / final 24。旧：`/pinyin` 页签「全部 47 / 声母 23 / 韵母 24」，每卡显示拼音＋emoji＋例字＋例词；点开 zh 弹窗「声母 / zh / 纸 zhǐ / 例词：白纸 📄」＋「上一个/下一个」。新：`/pinyin` 「已经学会 22 / 47 张」，声母网格 23 张（8 列）、韵母 24 张；点开 zh 弹窗「声母 / zh / 🐷 猪 小猪 / ✅ 这张卡片已经学会啦」 |
| 六-6a 闯关地图：12 关路径图（已过/当前/锁定三态）＋星星总数＋成就徽章墙 | **完成** | **完成** | 旧：`/levels` 页「12 关识字大冒险」「⭐ 星星 80」「🏁 通关 3 / 12」「🏅 徽章 7 / 11」；关卡左右交替排列：第 1～3 关绿色「已通过」带星，第 4 关黄色当前，5～12 关 🔒「未解锁」；页面底部「成就徽章墙」已点亮 7 枚。新：`/levels` 页「⭐ 星星 111 / ✅ 已通过 3 / 12 / 🎯 当前第 4 关」；12 个节点左右交替，1～3 关「已通过」（3/3/2 星），第 4 关「当前关 · 点一点，开始闯关 ▸」，5～12 关「未解锁 · 先通过第 N 关」；底部「成就徽章墙 9 / 10」 |
| 六-6b 家长页：周练习天数、闯关进度、错误最多的字 TOP10 | **完成** | **完成** | 旧：`/api/parent/summary` 返回 `weekDays` 7 天、`weekPracticeDays:4`、`levels`、`topWrong` 10 条；页面「本周练了 4 / 7 天，共获得 28 颗星星」「闯关进度 3 / 12」「易错字 TOP10」列出 毛/九/窗/石/短/甜/汤/椅/蓝/雪 及错误次数。新：`/api/progress` 返回 `weeklyDays` 7 天、`practiceDaysTotal:14`、`levels`、`topWrong` 10 条（含 `wrong`、`accuracy`）；页面「近 7 天练习 · 练习 6 / 7 天」（玩之前）/「闯关进度 已过 3 / 12 关」/「错误最多的字 TOP 10」列出 雪/说/外/石/田/妹/叶/鞋/水/蓝 及错误次数、正确率 |
| 三-1 今日练习闭环：进今日练习→依次玩 3 个游戏→得星星 | **完成**（静态核实：三个游戏结算后都会 `POST /api/practice`，服务端返回 `nextGame` 引导下一个；因不允许写入没有实际跑） | **完成**（实测） | 新：依次玩完三个游戏，三次 POST 分别返回 `starsToday:3 → 6 → 9`；首页随后显示「✅ 认一认 → ✅ 连一连 → ✅ 找一找」「今日星星 9 ⭐」「已完成 3 / 3」，主按钮变成「🚀 去闯关」；家长看板「今天 9」「练习 7 / 7 天」 |
| 三-3 识字闯关：120 字分 12 关每关 10 字，过关得星 | **完成**（静态核实） | **完成**（实测） | 库：两边都是 12 关 × 10 字（见第 2 节）。旧：`/levels/[idx]` 页把 10 张字卡逐个点开算「学过」，全看过后按钮「🎉 我学会啦，过关！」→ `POST /api/levels/[idx]/complete`（客户端固定传 `stars:3, accuracy:0.95`，服务端 `greatest` 保留最好成绩）；锁定关显示「这一关还没解锁」。新：`/levels/[n]` 先列 10 个生字，点「🚀 开始闯关」进 10 题四选一拼音测验，「10 道题，答对 8 道就能过关、解锁下一关」；我在第 1 关（已通过）全对重玩一次，`POST /api/levels/1/complete` 返回 `passed:true, stars:3, nextLevelUnlocked:2`，结果页「过关啦！· 答对 10 / 10 题，第 1 关通过 · 解锁新关卡：第 2 关」；`POST /api/levels/5/complete`（锁定关）返回 403 `{"error":"这一关还没有解锁哦"}`，未写库 |
| 三-5 奖励激励：练习完成得星星，累计星星点亮成就徽章 | **完成**（11 枚徽章定义在库，7 枚已亮；其中「今日全勤」徽章专门为「一天完成 3 个游戏」设计，今天玩完就能看到点亮） | **部分**（机制在位，但演示数据已点亮 9/10，剩下「识字高手」要认识 60 字而当前只有 34～40 字；今天玩完 3 个游戏 + 重玩 1 关，`newAchievements` 四次都是 `[]`，页面上看不到任何徽章被点亮，「闭环→徽章更新」这一段用演示数据演示不出来） | 旧库：`achievements` 11 行、`achievement_unlocks` 7 行、`daily_three`（今日全勤）未亮。新库：`achievements` 10 行、`achievement_unlocks` 9 行，唯一未亮 `chars-60`（阈值 60，当前 34） |
| 三-2 拼音学习 / 三-4 进度查看 | 完成 | 完成 | 同六-5、六-6b |
| 五-风格要求：卡通、明快、大色块、大圆角、鲜艳活泼、文字大、装饰可爱 | **部分**（大字、大圆角、emoji 装饰都有；但整体是浅色渐变底＋白卡片＋淡色描边，游戏卡底色只有约 15% 透明度的淡彩，「大色块、鲜艳」这一条看着不够） | **完成**（见第 3 节描述） | 见第 3 节 |
| 五-演示数据真实可信（真实汉字拼音、连贯记录、无占位文本） | **完成** | **完成**（有三处小瑕疵，见第 2 节末尾） | 见第 2 节 |
| 五-无支付、无外发通知、不接外部系统 | 完成 | 完成 | 两边 `src/` 里没有支付、通知、第三方调用 |
| 四-范围：只做网页、不做语音、单孩档案 | 完成 | 完成 | 两边源码 grep 不到 `speechSynthesis`/`<audio>`；库里各只有 1 个孩子档案 |
| 八-验收：功能可走通完整闭环 | 完成（静态） | 完成（实测：今日练习 3 游戏→得星→首页/家长页更新；闯关→得星→解锁；徽章更新见三-5 的说明） | 见三-1、三-3 |
| 八-演示数据覆盖至少 3 关闯关进度与两周练习记录 | 完成（3 关已过＋第 4 关当前；11 个练习日，跨 11-09～11-21 共 13 个日历天） | 完成（3 关已过＋第 4 关当前；14 天连续记录 11-09～11-22） | 见第 2 节 |
| 八-待确认：120 字按小学一年级常用字表选 | 完成（一～十、日月山水火、爸妈哥弟姐妹、口耳目手足、牛羊马鸟鱼虫、花草竹叶、饭菜肉蛋奶、门窗床灯书笔、走跑跳看听、红黄蓝白黑绿、早晚春夏秋冬、学生老师好请谢） | 完成（一～十、人口手足目耳大小多少、上下左右前后中里外间、天地日月水火山石田土、爸妈哥弟姐妹你我他好、花草树木果叶虫鸟鱼马、春夏秋冬风雨雪云星光、学生书本字词读听说、走跑跳飞看笑哭吃喝睡、早晚白黑红黄绿蓝长短、家门窗床桌椅灯衣鞋帽、车船路桥城村民年岁） | 库里逐关列出 120 字，全部是常用字 |

**汇总**（按上表 18 行逐行数）：旧应用 完成 17、部分 1（风格）、未做 0；新应用 完成 17、部分 1（三-5 徽章演示）、未做 0。

---

## 2. 演示数据核对（对照文档数字）

| 项目 | 文档要求 | 旧应用（psql 实查） | 新应用（psql 实查） |
|---|---|---|---|
| 常用汉字 | 120 | `characters` 120 行，`pinyin` 全部是小写字母＋声调符号（正则核对 0 条异常），`emoji` 120 行非空 | `hanzi` 120 行，`pinyin` 0 条异常，`emoji` 120 行非空 |
| 每关字数 | 12 关 × 10 | 每关 10 行（`group by level_idx`） | 每关 10 行（`group by level_no`） |
| 声母 / 韵母 | 23 / 24 | initial 23、final 24；每张有 `example_char`、`example_pinyin`、`example_word`、`emoji` | initial 23、final 24；每张有 `example_char`、`example_word`、`emoji`（没有单独的例字拼音字段） |
| 闯关进度 | 至少 3 关 | `level_progress` 3 行：第 1 关 3 星 98%、第 2 关 3 星 95%、第 3 关 2 星 90%，完成日期 11-09/11-10/11-11；第 4 关在 `/api/today` 里 `status:"current"`，5～12 关 `locked` | `level_progress` 12 行：1～3 关 `passed`（3/3/2 星，最好成绩 10/9/8），4 关 `passed:false` 且 API `status:"current"`，5～12 关 `locked` |
| 练习记录 | 两周 | `daily_logs` 30 行、11 个练习日（11-09～11-21，11-15 与 11-19 休息），每天 2～3 个游戏，星星 3～9 颗，时长 330～600 秒；按游戏计 find 11 / recognize 11 / match 8 | `game_runs` 42 行、14 天连续（11-09～11-22）每天 3 局，`practice_days` 14 行星星 4～9；按游戏计 recognize 14 / find 13 / match 12 / level 3 |
| 星星总数 | — | 游戏 72 ＋ 闯关 8 ＝ 80（与页面「⭐ 80」一致） | `star_ledger` 合计 102（与页面「⭐ 102」一致） |
| 错误最多的字 | 家长页 TOP10 | `char_stats` 39 字有记录，最多错 4 次（毛、九），页面列 10 条 | `char_stats` 50 字有记录，最多错 3 次（雪、说、外），页面列 10 条 |
| 徽章 | 成就徽章墙 | 11 枚定义、7 枚已亮 | 10 枚定义、9 枚已亮 |

演示数据小瑕疵（新应用）：
1. 韵母卡片 `o` 的例字是「哦」、例词是「哦哦」，不像一个真正的词（旧应用同一张卡是「我 / 我们」）。
2. `char_stats` 合计正确 383 / 错误 44，但 `game_runs` 42 局合计正确 464 / 错误 76，两张表对不上（种子数据各写各的）；「雪」「说」属于第 7、8 关（未解锁）却有错 3 次的记录。
3. 11-09、11-12、11-15 三天各只有「认一认＋找一找」两局外加一局闯关，但 `practice_days.completed_games` 仍是 3。

演示数据小瑕疵（旧应用）：
1. 「蓝」的配图 emoji 是 🌊（海浪），对应例词「蓝天」，图不对题。
2. 连一连只记答错的字，找一找结算时 10 题一律按 `correct:true` 上报（见第 5 节缺陷），所以家长页「错误最多的字」只包含认一认里点了「下一个」的字、错题重练和连一连里连错的字，找一找里点错的字永远不会出现。

---

## 3. 视觉风格（对照「卡通、明快、大色块、大圆角、鲜艳活泼、文字大、装饰可爱」）

**旧应用**：浅色渐变背景（上淡蓝绿、下淡黄粉），内容都是大圆角白卡片配柔和阴影，游戏卡用淡粉/淡绿/淡蓝/淡紫的淡彩底和彩色描边；顶部是头像＋「你好，满满！」＋三颗药丸标签（⭐ 80、🔥 0天、家长），底部一条悬浮药丸导航（今日练习/拼音卡片/闯关地图/家长中心）。游戏页目标字很大（约 150～170px）、拼音约 40～48px、按钮约 330×70px 的大药丸；卡片四角有被裁切的彩虹、山形装饰；配图全是 emoji。整体感觉是「柔和、干净」，大字和大圆角都有，但底色和卡片色都比较淡，没有明显的大块鲜艳色；帮助文字（12～13px）偏小。全页截图里悬浮导航条出现在页面中部，这是 fixed 元素在整页截图里的伪影，不是页面问题。

**新应用**：奶油黄点阵背景，四角有云朵、彩虹、星星装饰；顶部是白色药丸导航条（小雨的识字乐园 / 今日练习 / 拼音卡片 / 闯关地图 / 家长看板 / ⭐ 星数）；首页顶部是一整块橙黄渐变的大圆角横幅（带笑脸太阳），三张游戏卡各有一条饱和色顶边（黄/粉/绿），下面三块「星星宝库 / 闯关进度 / 成就徽章」白色大圆角卡；闯关地图是绿色渐变横幅，家长看板是紫蓝渐变横幅加彩色统计块。游戏页目标字约 150～200px、emoji 100～170px、拼音 40～50px，按钮带 3D 底边阴影的大圆角块；配图全是 emoji。按文档的字面要求（大色块、大圆角、鲜艳、装饰可爱、文字大）逐项都能看到。小问题：横幅上的问候语按服务器 UTC 时间显示「小雨，早点休息哦！」（容器时间是 00 点），北京时间早上看会怪；结果页下半屏留白较大。

---

## 4. 探测过的路由 / API

**旧应用 http://localhost:18092**（全部 GET）
`/` 200、`/games/recognize` 200、`/games/match` 200、`/games/find` 200、`/games/review` 200、`/pinyin` 200、`/levels` 200、`/levels/1` 200、`/levels/4` 200、`/levels/5` 200、`/parent` 200、`/api/today` 200、`/api/pinyin` 200、`/api/levels` 200、`/api/achievements` 200、`/api/parent/summary` 200、`/api/games/recognize` 200、`/api/games/match` 200、`/api/games/find` 200、`/api/games/review` 200、`/api/practice`（GET）405、`/api/levels/1/complete`（GET）405、`/nonexistent` 404。

**新应用 http://localhost:18112**
`/` 200、`/play/recognize` 200、`/play/match` 200、`/play/find` 200、`/pinyin` 200、`/levels` 200、`/levels/1` 200、`/levels/4` 200、`/levels/5` 200、`/progress` 200、`/api/today` 200、`/api/pinyin` 200、`/api/levels` 200、`/api/levels/4` 200、`/api/levels/5` 200（`locked:true`，但仍返回该关 10 个字）、`/api/levels/1` 200、`/api/progress` 200、`/api/play/recognize` 200、`/api/play/match` 200、`/api/play/find` 200、`/api/play/match/check`（GET）405、`/api/levels/4/complete`（GET）405、`/api/today/reset`（GET）405、`/api/pinyin/1/learn`（GET）405、`/nonexistent` 404；`POST /api/levels/5/complete`（锁定关，空答卷）403、`POST /api/levels/13/complete` 404、`POST /api/play/recognize`（空体）400。页面触发的写请求见第 6 节。

两边**均未出现 HTTP 500**。浏览器控制台：两边都只有我故意访问 `/nonexistent` 时的 404 报错；旧应用另有 63 条 `?_rsc=` 预取请求 `net::ERR_ABORTED`，是 Next.js 翻页时取消预取的正常现象，不是错误。

---

## 5. 各应用观察到的缺陷

**旧应用**
1. 找一找结算时把 10 题全部按 `correct:true` 上报（`FindGame.tsx` 第 `chars: data.questions.map((q) => ({ id: q.id, correct: true }))`），孩子第一遍点错的字不会进「错误最多的字」统计；连一连也只上报答错的字、不上报连对的字。家长页 TOP10 因此只反映认一认/错题重练/连一部分连一连。
2. 闯关过关的星星数和正确率由客户端固定传 `stars:3, accuracy:0.95`（`LevelPlay.tsx`），服务端直接采信，不是按答题结果算的。
3. 首页「今日练习」是 4 个游戏而不是文档的 3 个（多做了错题重练），主卡写「每天 4 个小游戏」「收获 12 颗星星」，与文档「3 个小游戏」口径不同。
4. 已通过的关再进去，顶部显示「已学 0 / 10」、按钮灰着写「还有 10 个字没看哦」（这是本次会话里还没点开的计数），和旁边「这一关已经通过啦」放在一起容易误读。
5. 家长页易错字只显示「错 N 次」，没有正确率（家长看板 API 里有 `accuracy` 字段但页面没展示）。
6. 风格偏淡（见第 3 节）。

**新应用**
1. 演示数据已点亮 9/10 徽章，最后一枚门槛 60 字，用演示数据走完今日练习闭环看不到任何新徽章点亮（四次结算 `newAchievements` 都为空）；README 说「可在演示中反复体验完整闭环」，「徽章更新」这一段实际看不到。
2. 以 `next dev` 开发模式提供服务（`package.json` 的 `start` 被改成 `next dev`），不是生产构建。
3. 种子数据小瑕疵（第 2 节）：韵母 o 例词「哦哦」；`char_stats` 与 `game_runs` 对不上账；未解锁关的字有错误记录；三天 `completed_games=3` 但只有两局日常游戏。
4. 欢迎语按服务器 UTC 时间算，容器 00 点时显示「早点休息哦」。
5. 锁定关的 API `/api/levels/5` 仍把该关 10 个字返回给前端（页面上没显示，只是接口层面没拦）。
6. 连一连不画实体连线，用同色底＋🔗 表示配对；找一找选错不能重选（文档没要求，但和旧应用比少了一个「再试试」的机会）。

---

## 6. 在新应用上执行的写操作（全部通过页面按钮触发，未调用 reset）

| 时间（UTC） | 页面 | 请求 | 写入的行 |
|---|---|---|---|
| 00:34:58 | `/play/recognize` 点「认识啦」×10 | `POST /api/play/recognize` body `{"knownIds":[37,38,36,35,40,39,16,9,4,5],"skippedIds":[]}` → 200 `{"stars":3,"correct":10,"total":10,"starsToday":3,"totalStars":105,"learnedChars":40,"newAchievements":[]}` | `game_runs` id 43（recognize 10/10，3 星，5 秒）；`star_ledger` id 43（+3「游戏奖励：认一认」）；`practice_days` 2026-11-23 新行；`char_stats` 山石火水土田大四五六 10 字 `correct_count+1` 且 `learned=true` |
| 00:35:23 | `/play/match` 四轮（第 1 轮故意连错 1 次「shǎo—七」，其余正确） | 21 次 `POST /api/play/match/check`（只读校验）；`POST /api/play/match` → 200 `{"stars":3,"correct":19,"total":20,"starsToday":6,"totalStars":108,"newAchievements":[]}` | `game_runs` id 44（match 19/20，3 星，23 秒）；`star_ledger` id 44（+3）；`char_stats` 20 个连对字 `correct+1`，「七」`wrong_count+1` |
| 00:35:39 | `/play/find` 10 题（第 1 题故意选错 niǎo，其余正确） | `POST /api/play/find` → 200 `{"stars":3,"correct":9,"total":10,"starsToday":9,"totalStars":111,"newAchievements":[]}` | `game_runs` id 45（find 9/10，3 星，12 秒）；`star_ledger` id 45（+3）；`char_stats` 9 字 `correct+1`，「火」`wrong_count+1` |
| 00:47:25 | `/levels/1` 点「开始闯关」10 题全对（选已通过的第 1 关重玩，为了不改变关卡地图） | `POST /api/levels/1/complete` → 200 `{"stars":3,"correct":10,"total":10,"starsToday":12,"totalStars":114,"learnedChars":40,"newAchievements":[],"score":10,"passed":true,"bestScore":10,"nextLevelUnlocked":2}` | `game_runs` id 46（level 10/10，3 星，10 秒）；`star_ledger` id 46（+3「游戏奖励：闯关挑战（第 1 关）」）；`char_stats` 一～十 `correct+1`；`level_progress` 第 1 关不变（passed、3 星、最好 10、passed_at 仍为 2026-11-09） |

不写库的探测：`POST /api/levels/5/complete` 403、`POST /api/levels/13/complete` 404、`POST /api/play/recognize {}` 400（都在校验阶段被拒，`game_runs` 行数未变）。

写前 / 写后对比：`game_runs` 42 → 46；`star_ledger` 合计 102 → 114；`practice_days` 14 → 15 行（今天 12 星、completed_games 4）；`char_stats` 正确 383 → 432、错误 44 → 46、已认识 34 → 40；`achievement_unlocks` 9 → 9（无变化）；`pinyin_stats` 22 → 22；`level_progress` 无变化。
这些记录**留在库里没有清除**。家长看板（`/progress`）底部有「✏️ 重练今日练习」按钮，对应 `POST /api/today/reset`，会删掉今天的 `game_runs` / `star_ledger` / `practice_days`，但不会回滚 `char_stats` 的计数。我没有按它。

---

## 7. 截图清单（1366×900，`fullPage` 的已注明）

**旧应用** `C:/Users/zhang/AppData/Local/Temp/v0-firstrun2/shots-old-q2/`
- 01-home.png（整页）今日练习首页
- 02-recognize-1.png / 03-recognize-2-after-click.png 认一认第 1 张 / 点「认识啦」后第 2 张
- 04-match-1.png / 05-match-2-one-pair.png 连一连初始 / 连对一对（有连线）
- 06-find-1.png / 07-find-2-after-correct.png 找一找初始 / 选中正确卡
- 08-review-1.png 错题重练（多做的第 4 个游戏）
- 09-pinyin-1.png（整页）/ 10-pinyin-2-detail.png 拼音卡片网格 / 点开 zh 弹窗
- 11-levels-map.png（整页）＋ 30～33-levelsmap-clip1～4.png（分段）闯关地图与徽章墙
- 12-level-1-passed.png / 13-level-4-current.png / 14-level-5-locked.png 单关页
- 15-parent.png（整页）家长中心
- 16-not-found.png 404 页
- shots-old.log 截图脚本日志（含被拦截的非 GET 请求列表：空）

**新应用** `C:/Users/zhang/AppData/Local/Temp/v0-firstrun2/shots-new-q2/`
- 01-home-before.png（整页）/ 15-home-after.png（整页）首页玩前 / 玩后
- 02-parent-before.png（整页）/ 16-parent-after.png（整页）家长看板玩前 / 玩后；33～35-parent-clip1～3.png 分段
- 03-recognize-1.png（加载中）/ 04-recognize-1-loaded.png / 05-recognize-2-after-click.png / 06-recognize-3-result.png 认一认
- 07-match-1.png / 08-match-2-wrong-pair.png / 09-match-3-round-done.png / 10-match-4-result.png 连一连（08 是点错后 300ms 的帧，红框抖动提示还没渲染出来；点错这件事以提交体里的 `mistakes` 为证）
- 11-find-1.png / 12-find-2-wrong-pick.png / 13-find-3-correct-pick.png / 14-find-4-result.png 找一找
- 17-pinyin-1.png（整页）/ 18-pinyin-2-detail.png 拼音卡片
- 19-levels-map.png（整页）＋ 30～32-levelsmap-clip1～3.png 闯关地图与徽章墙
- 20-level-1-passed.png / 21-level-4-current.png（整页）/ 22-level-5-locked.png 单关页
- 40-level1-quiz-q1.png / 41-level1-quiz-q1-correct.png / 42-level1-quiz-result.png 第 1 关闯关测验与结果
- 23-not-found.png 404 页
- shots-new.log / shots-new2.log / shots-new3.log 截图脚本日志（含每次 POST 的请求体和响应体）
