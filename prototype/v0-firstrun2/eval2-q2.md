# 验收复核报告：儿童拼音识字练习应用（题 2）

- 需求文档：`C:/Users/zhang/AppData/Local/Temp/v0-firstrun/prd-run2-kids.md`
- 旧应用：容器 `v0-run2`，http://localhost:18092（2026-11-21 生成，Next.js 生产构建 `next start`）
- 新应用：容器 `v0r2b-q2`，http://localhost:18122（2026-11-23 生成，**`next dev` 开发模式运行**）
- 复核日期：2026-11-23；复核方式：HTTP 探测、psql 查库、Chromium 截图（1366×900）、浏览器真实操作（仅新应用）
- 所有结论均来自本次亲自观察（HTTP 状态码、JSON 内容、数据库行、截图），未采信生成模型的自述

---

## 一、逐条对照表

| 条目 | 旧应用 | 新应用 | 证据 |
| --- | --- | --- | --- |
| **六-1** 今日练习页：3 个游戏入口卡片＋完成状态＋「开始练习」 | **完成**（超出：多一个「错题重练」第 4 游戏） | **完成** | 旧：`GET /` 200，`/api/today` 返回 4 个游戏（recognize/match/find/review），首页截图 00-home.png 有 4 张卡、按钮文案「开始练习/继续练习/再玩一次」。新：`GET /` 200，`/api/today` 返回 3 个游戏，截图 00-home.png 三张卡、按钮「开始练习/继续练习」。 |
| **六-2** 认一认：大卡片拼音＋汉字＋emoji，「认识啦／下一个」，一轮 10 张 | **完成** | **完成** | 旧：`/api/games/recognize` 返回 10 张（`count=999` 上限 20）；源码 `RecognizeGame.tsx` 按钮文字「👏 认识啦」「下一个 →」，页脚「一轮 10 张」；截图 01-recognize.png 字高 10rem。新：`/games/recognize` 200，页面「1 / 10」，按钮「👌 认识啦」「➡️ 下一个」，页脚「一轮 10 张」；浏览器实测点 10 次后结算「答对 10 / 10」；截图 01-recognize.png。 |
| **六-3** 连一连：左 5 拼音、右 5 汉字，点击配对连线，答对有奖励，答错可重试，共 4 轮 | **完成**（有真实 SVG 连线） | **完成**（无连线，只有点击配对着色） | 旧：`/api/games/match` 4 轮、每轮 `pairs` 5 个；源码 `MatchGame.tsx` 用 `<svg><line>` 画真实连线，配对成功有 ⭐ 飞出动画，答错 `animate-kid-shake`；截图 02-match.png 左 5 拼音右 5 汉字。新：`/games/match` 200，页面「第 1 / 4 轮 · 本轮还剩 5 对」，源码 `MatchGame.tsx` 左列 `round.pairs`（5）右列 `round.right`（5），答错 `animate-shake-x`＋提示「再想想」，但**没有画线**（配对后只是变底色+删除线）；浏览器实测 4 轮×5 对全对后结算「答对 20 / 20」；截图 02-match.png、25-match-round2.png。 |
| **六-4** 找一找：顶部大字目标汉字（家长读题），下方 3 个选项卡片，点选正确得星，共 10 题 | **完成**（选项是 emoji 图片） | **完成**（选项是拼音文字） | 旧：`/api/games/find` 10 题、每题 `options` 3 个（emoji＋汉字）；页面「请家长读一读这个字」、目标字 8.5rem；截图 03-find.png 三个 emoji 大卡。新：`/games/find` 10 题、每题 3 个拼音选项；页面「请家长读出这个字」、目标字 10rem；浏览器实测 10/10 后结算；截图 03-find.png。**差异说明**：PRD 没规定选项内容，两者都符合字面；旧应用用图片更贴近 5-7 岁不识拼音的孩子，新应用选拼音对目标年龄段偏难。 |
| **六-5** 拼音卡片集：23 声母＋24 韵母网格，点开看例字与例词 | **完成** | **完成**（多一个「我学会啦」标记） | 旧：`pinyin_cards` 表 initial 23 / final 24，声母 `b p m f d t n l g k h j q x zh ch sh r z c s y w`，韵母 `a o e i u ü ai ei ui ao ou iu ie üe er an en in un ün ang eng ing ong`；页面有「全部/声母/韵母」筛选，点卡弹窗显示例字、例音、例词、emoji；截图 05-pinyin.png、05b-pinyin-modal.png。新：库表同样 23/24、符号集完全一致；页面「全部 47 / 声母 23 / 韵母 24」，弹窗显示例字/例音/例词/emoji＋「我学会啦」按钮；截图 05-pinyin.png、05b-pinyin-modal.png。 |
| **六-6a** 闯关地图：12 关三态（已过/当前/锁定）＋星星总数＋徽章墙 | **完成** | **完成** | 旧：`/api/levels` 1-3 `done`、4 `current`、5-12 `locked`；`/levels` 200，页面「星星 80」「通关 3 / 12」「徽章 7 / 11」，徽章墙 11 枚；截图 06-levels-map.png。新：`/api/progress` levels 1-3 `passed:true`、4 `current:true`、5-12 `locked:true`；`/map` 200，页面「星星总数 87」「已过 3 / 12 关」「已点亮 6 / 11 枚」；截图 06-map.png。 |
| **六-6b** 家长页：周练习天数、闯关进度、错误最多的字 TOP10 | **完成** | **完成** | 旧：`/api/parent/summary` 返回 `weekPracticeDays:4`、`weekDays` 7 天明细、`levels` 12 条、`topWrong` **恰好 10 条**；`/parent` 200；截图 10-parent.png 有「近 7 天练习」柱状图、「闯关进度」3/12、「易错字 TOP10」。新：`/api/progress` 返回 `weekDays:5`、`week` 7 天、`last14Days` 14 天、`levels` 12 条、`mistakes` 10 条；`/parent` 200；截图 10-parent.png 有「本周练习天数」「近两周星星记录」「闯关进度」「错误最多的字 TOP10」。 |
| **三-1** 今日练习依次完成 3 个游戏后得星星 | **完成**（源码推断，未实操） | **完成**（实测） | 旧：未操作（按要求不改旧应用数据）。源码 `recordPractice` 有 `UNIQUE(child_id,day,game)` + `greatest()` upsert，同一天同一游戏取最好成绩、星星不重复累加；结算面板 `GameResult` 有「去玩连一连」引导下一个游戏。新：实测三个游戏全对 → `/api/games/*/result` 均 200，`starsToday` 0→9，`finished:true`，首页变「今天的小任务全部完成啦！」；截图 22/26/28 结算、29-home-after-all3.png。**但**结算面板只有「再来一次 / 回到今日练习」，没有「下一个游戏」引导。 |
| **三-3** 过关得星、解锁下一关 | **部分**（没有测验，点完 10 张卡就过关，星星固定 3） | **完成**（有 10 题测验，≥8 分过关） | 旧：`LevelPlay.tsx` 要点开全部 10 张字卡后按钮「我学会啦，过关！」才可点，`POST /api/levels/{idx}/complete` 传 `{stars:3, accuracy:0.95}` 写死；截图 07-level4-current.png。新：`LevelPlay.tsx` 先「认字」再「闯关测验」（看字选拼音 4 选项），`POST /api/levels/{level}/pass {score}`，`rules.ts` `LEVEL_PASS_SCORE=8`；实测第 4 关 10/10 → `passed:true, nextLevel:5`，地图第 4 关 ✅3⭐、第 5 关由🔒变「来挑战」，`/map/5` 可进入；截图 32/33/34。 |
| **三-5** 累计星星点亮成就徽章 | **完成**（7/11 已点亮） | **完成**（6/11 已点亮） | 旧：`achievements` 11 枚、`achievement_unlocks` 7 行（first_practice/stars_30/stars_80/days_10/level_3/match_perfect/find_perfect），`syncAchievements` 按 9 项指标判定。新：`achievements` 11 枚、`unlocked_at` 非空 6 行（first_step/star_10/star_50/level_3/streak_3/perfect_5），带 `target/metric/progress` 进度条。实测今日三游戏全对后 `unlocked:[]`（下一个徽章 streak_7 需 7 天，合理）。 |
| **八-验收** 完整闭环可走通 | **未能确认**（按要求未在旧应用实操） | **完成** | 新：今日三游戏 + 第 4 关闯关全部实测通过，数据库落库与页面一致（见第五节）。旧：只从源码和 API 推断闭环成立，未实际操作。 |
| **八-验收** 演示数据≥3 关进度＋两周练习记录 | **完成** | **完成** | 旧：`level_progress` 3 行（1-3 关，3/3/2 星，11-09～11-11）；`daily_logs` 30 行、**11 个练习日**（11-09～11-21，休息 11-15/19/22）。新：`level_progress` 3 行 passed（11-11/14/17）；`practice_days` **12 个练习日**（11-09～11-22，休息 11-12/19）。 |
| **五-约束** 风格：卡通明快大色块大圆角、文字大、装饰可爱 | **符合**（见第三节） | **符合**（见第三节） | 截图对照。 |
| **五-约束** 演示数据真实可信 | **完成**（小瑕疵） | **完成**（小瑕疵） | 见第二节。 |
| **四-边界** 单家庭单孩子 | 完成（`child` 表 1 行「小满」） | 完成（`profile` 表 id 固定 1「朵朵」，有 `CHECK (id = 1)`） | 查库。 |

### 计数

| | 完成 | 部分 | 未做 | 未能确认 |
| --- | --- | --- | --- | --- |
| 旧应用 | 12 | 1（闯关无测验） | 0 | 1（闭环未实操） |
| 新应用 | 14 | 0 | 0 | 0 |

---

## 二、演示数据核对（对照文档数字）

| 项目 | 文档要求 | 旧应用 | 新应用 |
| --- | --- | --- | --- |
| 汉字总数 | 120 | **120**，distinct 120 | **120**，distinct 120 |
| 每关字数 | 10 | 12 关各 10 | 12 关各 10（有 `CHECK level_no 1..12`） |
| 拼音真实 | 真实 | 抽查 120 字带声调拼音全部正确（如 爸 bà、窗 chuāng、绿 lǜ）；每字有例词 | 抽查 120 字带声调拼音全部正确（如 犬 quǎn、裙 qún）；**无例词字段**（表里只有 hanzi/pinyin/emoji） |
| 声母 | 23 | **23**，标准 23 个 | **23**，与旧应用完全一致 |
| 韵母 | 24 | **24**，标准 24 个 | **24**，与旧应用完全一致 |
| 拼音卡例字例词 | 每卡配例字与例词 | 47 张全部有 example_char/example_pinyin/example_word/emoji | 47 张全部有 |
| 已过关数 | ≥3 | **3**（3/3/2 星） | **3**（3/3/2 星） |
| 练习天数 | 两周 | **11 天**（13 天跨度） | **12 天**（14 天跨度） |
| 每日记录连贯 | 连贯 | 每天 2-3 游戏、对错数合理（如 match 20 对制、find 10 题制） | 每天 3 游戏、correct/total 合理（find x/10、match x/20） |
| 易错字 TOP10 | — | 10 条（`topWrong` 恰好 10） | 10 条 |
| 今日留白 | — | 今日 0 条（便于体验闭环） | 今日 0 条（便于体验闭环） |

**两应用共有的小瑕疵**：易错字里有未学关卡的汉字。旧应用 TOP10 中 7 个字来自第 7-11 关（短、窗、甜、蓝、椅、汤、春）；新应用 TOP10 中 8 个字来自第 6-12 关（睡、蓝、秋、笔、园、时、谢、桥、雷）。孩子才过 3 关，按理还没学到这些字。旧应用可以用"找一找的干扰项从全 120 字抽取"解释；**新应用不能**——它的 `getFindPlay` 干扰项只从当前关卡池抽，闯关测验只考本关字，所以按它自己的出题逻辑，孩子不可能在第 6-12 关的字上答错。演示数据与出题逻辑不自洽。

---

## 三、视觉风格描述（对照「五、关键约束」）

**旧应用**：奶油黄渐变底色，页面主色块是白底大圆角卡片（圆角约 1.5rem，带 3px 深色描边和底部硬阴影），配色为珊瑚红/薄荷绿/天蓝/葡萄紫/阳光黄五种糖果色。标题用黑色特粗体 2xl-3xl，认一认的汉字 10rem、找一找目标字 8.5rem、连一连汉字 5xl，按钮文字 2xl 带厚底边阴影。装饰用大号半透明 emoji（🌈🎈⭐）。首页卡片数量偏多（今日练习主卡 + 4 张游戏卡 + 闯关进度 + 底部导航），文字密度中等——每张卡上有标题、副标题、状态标签、按钮四层文字，对 5-7 岁孩子来说字略多，但都是大字。整体**明显符合**「卡通、明快、大色块、大圆角、文字大、装饰可爱」。

**新应用**：同样是奶油色底 + 白底大圆角卡片（圆角 2.5rem、4px 白描边、`0 12px 0` 硬阴影，更有"贴纸"感），配色为糖果粉/薄荷绿/天蓝/葡萄紫/阳光黄。标题 3xl-4xl 特粗，认一认汉字 9rem、找一找目标字 10rem、连一连汉字 4xl，按钮 2xl。首页更简洁：一张今日总览大卡 + 三张游戏卡（每卡只有图标、标题、一句提示、状态、按钮）+ 本周打卡条 + 四个快捷入口，文字明显比旧应用少。结算面板有满屏飘落的 ⭐🎉✨ 彩纸动画。地图页 12 个圆形节点按 3 行蛇形排列（奇数行反向），已过绿色/当前粉色跳动/锁定灰色🔒。整体**明显符合**要求，且"少而大"做得比旧应用更好。

**两边截图均无**：空白页、错误覆盖层、布局错乱、数据为空、文字不可读。新应用右下角有 Next.js 开发模式指示器按钮（因为跑在 `next dev`），不是错误。

---

## 四、探测过的路由与 API

### 旧应用（18092）

| 方法 | 路径 | 状态 | 说明 |
| --- | --- | --- | --- |
| GET | / | 200 | 首页 36980 字节 |
| GET | /pinyin | 200 | |
| GET | /levels | 200 | |
| GET | /levels/1、/4、/12 | 200 | |
| GET | /levels/5 | 200 | 锁定提示页 |
| GET | /levels/0、/13、/nonexistent | 200 / 200 / 404 | 0 和 13 是软 404（HTTP 200 但页面显示 404 内容） |
| GET | /parent | 200 | |
| GET | /games/recognize、match、find、review | 200 | |
| GET | /api/today | 200 | 4 游戏 + 12 关状态 + totalStars 80 |
| GET | /api/pinyin | 200 | 47 卡 |
| GET | /api/levels | 200 | 12 关 |
| GET | /api/achievements | 200 | 11 枚 |
| GET | /api/parent/summary | 200 | 完整家长数据 |
| GET | /api/games/recognize | 200 | 默认 10 张，`?count=abc` 回落 10，`?count=999` 上限 20 |
| GET | /api/games/match | 200 | [5,5,5,5] |
| GET | /api/games/find | 200 | 10 题、[3,3,3] 选项 |
| GET | /api/games/review | 200 | 错题池出题 |
| GET | /api/practice | 405 | 只接受 POST |
| GET | /api/levels/1、/api/nonexistent | 404 | |

### 新应用（18122）

| 方法 | 路径 | 状态 | 说明 |
| --- | --- | --- | --- |
| GET | / | 200 | |
| GET | /pinyin | 200 | |
| GET | /map、/map/1、/map/4、/map/5 | 200 | |
| GET | /map/0、/map/13 | 200 | 软 404 |
| GET | /parent | 200 | |
| GET | /games/recognize、match、find | 200 | |
| GET | /games/review、/levels、/nonexistent | 404 | 新应用没有这两个路由 |
| GET | /favicon.ico、/icon.svg | **404** | 没有网站图标，首页控制台报一条 404 |
| GET | /api/today | 200 | 3 游戏 + health |
| GET | /api/progress | 200 | 家长全量数据 |
| GET | /api/mistakes | 405 | 只接受 POST |
| GET | /api/pinyin、/api/levels、/api/achievements、/api/parent/summary | 404 | 新应用没有这些路由（数据合并进 /api/progress） |
| POST | /api/games/foo/result | 400 | 未知游戏 |
| POST | /api/games/find/result {correct:5,total:3} | 400 | |
| POST | /api/games/find/result 非 JSON | 400 | |
| POST | /api/levels/13/pass | 400 | |
| POST | /api/levels/6/pass（锁定关） | 409 | |
| POST | /api/levels/5/pass {score:11} | 400 | |
| POST | /api/mistakes {hanzi:""} / 5 个字 | 400 / 400 | |
| POST | /api/mistakes {hanzi:"龘"}（不在字表） | 200 | 静默不写入，但返回 ok:true |
| POST | /api/pinyin/abc/learn | 400 | |
| POST | /api/pinyin/999/learn | **500** | 把 SQL 外键错误原文暴露给前端 |

---

## 五、在旧应用上做的写操作

**无。** 开始与结束各查一次库：`daily_logs` 30 行、今日 0 行、`level_progress` 完成 3 行、`achievement_unlocks` 7 行——完全一致。浏览器截图时只打开页面和点开拼音卡弹窗（不提交），没有点任何「认识啦」「过关」等按钮。

## 六、在新应用上做的写操作（全部列出）

| # | 操作 | 产生的记录 | 前 → 后 |
| --- | --- | --- | --- |
| 1 | 浏览器玩认一认：10 张全点「认识啦」 | `game_sessions` id 37（recognize 10/10 ★3）；`practice_days` 2026-11-23 新建（games_done 1, stars 3） | profile.stars 87 → 90 |
| 2 | 浏览器玩连一连：4 轮 × 5 对全对 | `game_sessions` id 38（match 20/20 ★3）；practice_days 更新（2, 6） | 90 → 93 |
| 3 | 第一次找一找脚本：第 1 题（羊）故意点错 1 次后脚本中断，**未提交成绩** | `char_mistakes` 羊 wrong_count 0 → 1 | 星数不变 |
| 4 | 第二次找一找脚本（修正后）：10 题全对 | `game_sessions` id 39（find 10/10 ★3）；practice_days 更新（3, 9, completed=true） | 93 → 96；streak 3 → 4 |
| 5 | 浏览器玩第 4 关闯关测验：10 题全对 | `level_progress` 第 4 关 passed=true, stars 3, best_score 10；第 5 关保持未过 | 96 → 99；地图已过 3 → 4 |
| 6 | 直接 curl `POST /api/games/recognize/result {10,10}` 验证重玩口径 | `game_sessions` id 40（recognize 10/10 ★3）；practice_days stars 9 → 12 | 99 → **102**（暴露重复计星缺陷） |
| 7 | 一组非法输入 POST（全部被 400/409 拒绝，无写入） | 无 | 不变 |

最终新应用状态：profile.stars **102**，今日 4 条 game_sessions，practice_days 今日 (3, 12, completed)，第 1-4 关通过，徽章仍 6 枚。

---

## 七、各应用缺陷

### 旧应用

1. **闯关没有测验**（部分）：`LevelPlay.tsx` 只要孩子把 10 张字卡各点一下就允许过关，过关请求写死 `stars:3, accuracy:0.95`。与 PRD「过关得星」的"闯"字不符——没有检验孩子是否真认识。
2. **今日练习是 4 个游戏而非 3 个**：多出「错题重练」。PRD 说 3 个，首页写「每天 4 个小游戏」「收获 12 颗星星」。属于超范围，不算违反但与文档数字不一致。
3. **`/api/parent/summary` 的 `newBadges` 字段**返回了全部 7 枚已解锁徽章（这些是种子数据播下的，并非"新"解锁），语义误导。
4. **软 404**：`/levels/0`、`/levels/13` 返回 HTTP 200 但页面是 404 内容。
5. **首页文字密度偏高**：每张卡四层文字，对 5-7 岁孩子偏多（见第三节）。
6. 演示数据：易错字含未学关卡的字（第二节）。
7. 控制台：0 错误。日志里出现的 `ERR_ABORTED` 都是 Next.js 链接预取在页面关闭时被取消，不是故障。

### 新应用

1. **星星重复累加**（口径缺陷，最严重）：同一游戏重玩一次，星星再加 3（99 → 102），`/api/today` 显示「今日 12 颗」而三个游戏满分只有 9，`games[0].stars` 变 6。README 自述「幂等不漂移」不成立。根源：`game_sessions` 每次都插新行，`getTodayState` 对同游戏 `reduce` 累加，`profile.stars` 从全表重算。
2. **HTTP 500 泄露 SQL 错误**：`POST /api/pinyin/999/learn` 返回 500，body 含 `violates foreign key constraint "learned_pinyin_card_id_fkey"`。应先查卡片存在性再插入。
3. **连一连没有"连线"**：PRD 明确写「点击配对连线」，新应用只是点对后变色加删除线和一个小圆点，没有画线。
4. **找一找选项是拼音**：PRD 未规定选项形式，但目标用户 5-7 岁尚在学拼音，选拼音比选图片难。
5. **结算后没有"下一个游戏"引导**：只有「再来一次」「回到今日练习」，孩子要自己回首页再点下一个；旧应用有「🔗 去玩连一连」直达按钮。
6. **连一连结算时头部进度显示错误**：完成 20/20 时显示「16 / 20」（第 4 轮 matched 被清空后未更新计数）。
7. **缺少网站图标**：`/favicon.ico`、`/icon.svg` 404，首页控制台一条 404 错误。
8. **运行在开发模式**：`next dev`，页面带 Next.js 开发指示器，右下角显示 "N Issues"。
9. **演示数据与出题逻辑不自洽**：易错字 8/10 来自第 6-12 关，但新应用的找一找干扰项和闯关测验都只用本关/已学字（第二节）。
10. **`POST /api/mistakes` 对不存在的汉字返回 ok:true** 但静默不写（第 7 条探测），前端无从得知记录失败。
11. 字表没有例词字段（旧应用每个字有例词），认一认卡片上不显示例词。
12. 软 404（`/map/0`、`/map/13`），与旧应用相同。

---

## 八、新应用比旧应用好／差的地方

**更好**：
- 闯关有真正的 10 题测验（≥8 过关），旧应用只点卡片
- 家长看板多了「近两周星星记录」「三个游戏正确率」「满分局数」「连续天数」
- 徽章带进度条和 `progress/target`，孩子能看到离下一枚还差多少
- 拼音卡可标记「我学会啦」并计入徽章
- 首页更简洁、文字更少更大，更贴近「少而大」要求
- 地图页蛇形路径更有"闯关地图"感
- 今日练习严格 3 个游戏，与 PRD 一致

**更差**：
- 星星重复累加（旧应用有 `UNIQUE + greatest()` 保护）
- 连一连没有画线（旧应用有 SVG 真实连线 + ⭐ 飞出）
- 结算后无"下一个游戏"引导
- 找一找选项是拼音而非图片
- `POST /api/pinyin/999/learn` 500 泄露 SQL 错误（旧应用所有 API 无 500）
- 没有网站图标（旧应用有 icon.svg）
- 跑在开发模式（旧应用是生产构建）
- 字表无例词
- 演示数据易错字与自身出题逻辑矛盾

---

## 九、截图文件

### 新应用 `C:/Users/zhang/AppData/Local/Temp/v0-firstrun2/shots-new2-q2/`
- `00-home.png` 首页（整页）
- `01-recognize.png` 认一认第 1 张
- `02-match.png` 连一连第 1 轮
- `03-find.png` 找一找第 1 题
- `05-pinyin.png` 拼音卡片集（整页）
- `05b-pinyin-modal.png` 拼音卡弹窗
- `06-map.png` 闯关地图＋徽章墙（整页）
- `07-map4-current.png` 第 4 关（当前）
- `08-map1-done.png` 第 1 关（已过）
- `09-map5-locked.png` 第 5 关锁定提示
- `10-parent.png` 家长看板（整页）
- `22-recognize-result.png` 认一认结算（写操作 1）
- `25-match-round2.png` 连一连第 2 轮（写操作 2）
- `26-match-result.png` 连一连结算，头部显示「16 / 20」（写操作 2）
- `27-find-wrong-then-retry.png` 找一找点错后「再想想」提示（写操作 3）
- `32-level4-quiz-q1.png` 第 4 关测验第 1 题（写操作 5）
- `33-level4-result.png` 第 4 关过关结算（写操作 5）
- `34-map-after-level4.png` 过关后地图，第 4 关 ✅、第 5 关「来挑战」（写操作 5）
- `report-new.json` 爬取报告（状态码、控制台错误、页面文字）

### 旧应用 `C:/Users/zhang/AppData/Local/Temp/v0-firstrun2/shots-old2-q2/`
- `00-home.png` 首页（整页）
- `01-recognize.png` 认一认第 1 张
- `02-match.png` 连一连第 1 轮
- `03-find.png` 找一找第 1 题
- `04-review.png` 错题重练（旧应用独有）
- `05-pinyin.png` 拼音卡片集（整页）
- `05b-pinyin-modal.png` 拼音卡弹窗
- `06-levels-map.png` 闯关地图＋徽章墙（整页）
- `07-level4-current.png` 第 4 关（当前）
- `08-level1-done.png` 第 1 关（已过）
- `09-level5-locked.png` 第 5 关锁定提示
- `10-parent.png` 家长中心（整页）
- `report-old.json` 爬取报告
- `_other-job-files/` 子目录：另一个并行任务留在容器 `/tmp/shots` 里、被 `docker cp` 一并带出的 24 个文件（08:33–08:40 时间戳），**不是本次生成**，已隔离到子目录，未删。

---

## 十、脚本

本次使用的脚本（宿主机 `C:/Users/zhang/AppData/Local/Temp/v0-firstrun2/`，拷入容器 `/tmp/shots/`）：
`shots-script.js`（截图爬取）、`play-recognize.js`、`play-match.js`、`play-find.js`、`play-level.js`（新应用闭环实测）。
