# 教材文件中文对照（textbooks/）

本文是 `spoken-server-app/src/main/resources/textbooks/` 下 4 个 JSON 的中英对照译本，用于核对教材内容与教学素材，方便产品/教研阅读。**不参与运行**，改动本文不影响程序。

目录构成与读取关系：

| 文件 | 是否被代码读取 | 用途 |
|---|---|---|
| `bandready-daily.json` | 是（唯一） | 运行时教材：课程目录 + 原题 + 练习目标 + 陪练指导 + 课名别称 |
| `provenance/card_p1_days_and_home_001.json` | 否 | 原始题卡留档：许可与题目溯源 |
| `provenance/card_p1_food_cooking_001.json` | 否 | 同上 |
| `provenance/card_p1_food_shopping_001.json` | 否 | 同上 |
| `SOURCE.md` | 否 | 来源、许可与首版范围说明 |

英文部分为原文照录。题目与错误示例**必须保留英文原文**（它们是教学素材本身），中文为对照翻译。

---

## 一、bandready-daily.json（运行时唯一读取）

### 教材级

| 字段 | 原文 | 中文 |
|---|---|---|
| `id` | bandready-daily | 教材编号：BandReady 日常版 |
| `title` | BandReady · 日常口语 Part 1 | BandReady · 日常口语 Part 1 |
| `sourceUrl` | https://github.com/Luxshan2000/ielts-prep/tree/10282cc853a03b6984907fec0affe6cbbd4fe0b0/content/core-en | 内容来源仓库地址（固定到具体提交） |
| `sourceCommit` | 10282cc853a03b6984907fec0affe6cbbd4fe0b0 | 固定参考提交号 |
| `license` | CC0-1.0 | 内容许可：CC0-1.0（公有领域贡献） |

### 第 1 课 · daily-home

| 字段 | 内容 |
|---|---|
| 课名 `title` | 日常与居家 |
| 练习目标 `objective` | 用简短回答描述日常生活，再补充一个具体细节。 |
| 原始卡号 `sourceCardId` | card_p1_days_and_home_001 |
| 别称 `aliases` | daily-home、日常与居家、what you do and the home you live in、居家 |

**原题（`questions`）**

| # | 英文原文 | 中文 |
|---|---|---|
| 1 | First of all, are you working or studying at the moment? | 首先，你目前是在工作还是在学习？ |
| 2 | Is it something you'd choose again? | 如果重来一次，你还会做同样的选择吗？ |
| 3 | Is there anything about it you'd change if you could? | 如果有得选，你有什么想改变的地方吗？ |
| 4 | Now, your home. Do you live on your own or with other people? | 现在说说你的家。你是自己一个人住，还是和别人一起住？ |
| 5 | Do most people your age where you live still live with family? | 在你住的地方，和你同龄的人大多还和家人一起住吗？ |
| 6 | Do you think it matters how big a home is? | 你觉得房子的大小重要吗？ |

**陪练指导（`addedGuidance`）—— 三课相同**

> 以轻松对话的形式，一次只练一道原题。从第一道还没回答的题开始。遵循学员当前等级对应的语言策略。引导学员补充一个具体细节或理由。不要给分数，也不要声称这是考试成绩。跟读是练习形式，不代表个人偏好。

### 第 2 课 · food-cooking

| 字段 | 内容 |
|---|---|
| 课名 `title` | 食物与烹饪 |
| 练习目标 `objective` | 表达饮食喜好，描述一道菜，并解释原因。 |
| 原始卡号 `sourceCardId` | card_p1_food_cooking_001 |
| 别称 `aliases` | food-cooking、食物与烹饪、food and cooking、烹饪、做饭、cooking |

**原题（`questions`）**

| # | 英文原文 | 中文 |
|---|---|---|
| 1 | Do you enjoy cooking? | 你喜欢做饭吗？ |
| 2 | Who does most of the cooking in your home? | 你家平时主要是谁做饭？ |
| 3 | What is a dish that you can cook well? | 有什么菜是你做得特别好的？ |
| 4 | Do you prefer eating at home or eating out? | 你更喜欢在家吃还是出去吃？ |
| 5 | Has your taste in food changed since you were a child? | 从童年到现在，你的饮食口味有变化吗？ |

### 第 3 课 · food-shopping

| 字段 | 内容 |
|---|---|
| 课名 `title` | 食品购物 |
| 练习目标 `objective` | 描述购买食物的习惯，并比较不同选择。 |
| 原始卡号 `sourceCardId` | card_p1_food_shopping_001 |
| 别称 `aliases` | food-shopping、食品购物、shopping for food、买菜、food shopping |

**原题（`questions`）**

| # | 英文原文 | 中文 |
|---|---|---|
| 1 | Where do you usually buy your food? | 你通常在哪儿买食物？ |
| 2 | Do you write a shopping list? | 你会写购物清单吗？ |
| 3 | How often do you shop for food? | 你多久买一次食物？ |
| 4 | Do you look at the price first or the quality? | 你会先看价格，还是先看质量？ |
| 5 | Have you ever bought food online? | 你在网上买过食物吗？ |

---

## 二、provenance/card_p1_days_and_home_001.json（原始题卡留档）

### 外层字段

| 字段 | 原文 | 中文 |
|---|---|---|
| `part` | 1 | 属于 Part 1（口语第一部分） |
| `card_set_id` | set_food_meals_001 | 所属题组：食物与日常 |
| `topic_id` | topic_housing | 话题分类：居家 |
| `title` | what you do and the home you live in | 你的工作（学习）与居住的家 |
| `difficulty` | core | 难度：核心级 |
| `tags_json` | work, housing, present-tense, frame-personal, opening-frame | 标签：工作、居住、一般现在时、个人话题框架、开场框架 |

### payload_json

| 字段 | 原文 | 中文 |
|---|---|---|
| `schema_version` | 2 | 结构版本 2 |
| `frame_tier` | 1 | 框架层级 1 |
| `frame_kind` | personal | 框架类型：个人话题 |
| `frame_slot` | 3 | 框架槽位 3 |
| `frame_role` | obligatory_opening | 框架角色：必选开场 |

### teaching 段（教学素材）

- `tense_focus`：**present simple, second conditional in the middle, generalising present at the end**
  → 时态焦点：一般现在时；中间部分用第二条件句；结尾用表示泛指的一般现在时。
- `band_move`：**On the 'would you choose again' question, answer the question asked — commit before you qualify.**
  → 提分动作：在"还会不会再次选择"这个问题上，先正面回答被问的问题 —— 先给出明确立场，再做限定补充。

**第 1 题**（q_index 0）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 `angle` | A1 | A1 类角度 |
| 答题结构 `answer_shape` | Say which one, name it, then say how many years you've been at it. | 说明是哪一种（工作还是学习），说出具体职业或专业，再说已经做了多少年。 |
| 延伸示范 `extend_move` | I'm working — I've been a chef in a hotel kitchen for going on five years now. | 我在工作 —— 我在酒店厨房当厨师，快五年了。 |
| 追问 `probe` | Long shifts? | 上班时间长吗？ |

典型错误 `common_error`：

- ✗ **I am chef since five years.**
- ✓ **I've been a chef for five years.**
- 原因：表示时长要用 `for`；表示职业时要加冠词。

**第 2 题**（q_index 1）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A4 | A4 类角度 |
| 答题结构 | Say yes or no first, give the reason, then one thing you'd do differently. | 先明确回答会或不会，再说理由，最后说一件你会做得不一样的事。 |
| 延伸示范 | I would, yes — though I'd train somewhere smaller first, because you learn more. | 会的 —— 不过我会先去小一些的地方受训，因为那样能学到更多。 |
| 追问 | What puts people off it? | 是什么让人对这个职业望而却步？ |

典型错误：

- ✗ **Yes, I choose it again because I like.**
- ✓ **Yes, I'd choose it again because I enjoy it.**
- 原因：`like` 和 `enjoy` 后面必须接宾语。

**第 3 题**（q_index 2）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A9 | A9 类角度 |
| 答题结构 | Pick one thing, say what it costs you now, keep it to your own job. | 只挑一件事，说它现在给你带来什么代价，范围限定在你自己的工作内。 |
| 延伸示范 | The hours, easily — if the shifts finished an hour earlier I'd actually see people. | 肯定是工时长 —— 如果班次能早一小时结束，我就真能见得到人了。 |
| 追问 | Could that happen? | 这有可能实现吗？ |

典型错误：

- ✗ **If I can change, I will finish earlier.**
- ✓ **If I could change one thing, I'd finish earlier.**
- 原因：这是假设情况，`if` 从句要用过去式。

**第 4 题**（q_index 3）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A3 | A3 类角度 |
| 答题结构 | Say who's there, name the arrangement, then say how long it's been that way. | 说清和谁一起住，说明居住形式，再说这种状态已经持续多久。 |
| 延伸示范 | With two friends from college — we've been in the same flat since the lease started. | 和两个大学同学一起 —— 从签租约起我们一直住同一套房子。 |
| 追问 | Does that work well? | 这样住得顺吗？ |

典型错误：

- ✗ **I live with my two friends since two years.**
- ✓ **I've lived with two friends for two years.**
- 原因：表时长用 `for`；这里不需要物主代词。

**第 5 题**（q_index 4）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A7 | A7 类角度 |
| 答题结构 | Give a quantifier, name the reason people stay, then note the exception. | 先用一个量词概括比例，说明大家住在家里的原因，再指出例外情况。 |
| 延伸示范 | A fair number do, mainly because rents are high — though it's less common than it was. | 相当一部分人是这样，主要是因为房租高 —— 不过这种情况比以前少了。 |
| 追问 | Is that changing? | 这种情况在变化吗？ |

典型错误：

- ✗ **The most of young people live with the family.**
- ✓ **Most young people live with their families.**
- 原因：`most` 前面不加冠词，也不接 `of`。

**第 6 题**（q_index 5）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A8 | A8 类角度 |
| 答题结构 | Take a position, name what actually matters instead, then hedge once. | 先表明立场，指出真正重要的是什么，最后做一次语气上的缓和。 |
| 延伸示范 | Less than people think — a small place laid out well beats a big place you can't heat. | 没有大家想的那么重要 —— 一套布局合理的小房子，胜过一套你连供暖都负担不起的大房子。 |
| 追问 | What matters more? | 那什么更重要？ |

典型错误：

- ✗ **The size of home are not important.**
- ✓ **The size of a home isn't that important.**
- 原因：主语是 `size`，是单数。

---

## 三、provenance/card_p1_food_cooking_001.json（原始题卡留档）

### 外层字段

| 字段 | 原文 | 中文 |
|---|---|---|
| `part` | 1 | 属于 Part 1 |
| `card_set_id` | set_food_meals_001 | 所属题组：食物与日常 |
| `topic_id` | topic_food | 话题分类：食物 |
| `title` | food and cooking | 食物与烹饪 |
| `difficulty` | core | 难度：核心级 |
| `tags_json` | food, daily-life, preference | 标签：食物、日常生活、偏好 |

### payload_json

| 字段 | 原文 | 中文 |
|---|---|---|
| `schema_version` | 2 | 结构版本 2 |
| `frame_tier` | 2 | 框架层级 2 |
| `frame_kind` | topic | 框架类型：话题 |

### teaching 段（教学素材）

- `tense_focus`：**present simple for habits, present perfect for how your taste has changed**
  → 时态焦点：表达习惯用一般现在时；说明口味如何变化用现在完成时。
- `band_move`：**Name the dish. Food questions are answered with nouns, and a specific dish is worth more than any adjective.**
  → 提分动作：把菜名说出来。食物类问题要靠名词作答，一道具体的菜胜过任何形容词。

**第 1 题**（q_index 0）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A4 | A4 类角度 |
| 答题结构 | Say how much, name what you enjoy or hate about it, then when you do it. | 先说喜欢的程度，说出你具体喜欢或讨厌的部分，再说你什么时候做这件事。 |
| 延伸示范 | I do when there's no rush — it's cooking on a Tuesday at seven that I resent. | 不赶时间的时候我挺喜欢 —— 我受不了的是周二晚上七点还得做饭。 |
| 追问 | What did you cook last? | 你最近做的一道菜是什么？ |

典型错误：

- ✗ **I enjoy to cook at the weekend.**
- ✓ **I enjoy cooking at the weekend.**
- 原因：`enjoy` 后面一律接 -ing 形式。

**第 2 题**（q_index 1）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A1 | A1 类角度 |
| 答题结构 | Name who, say how that was decided, then what you contribute. | 说清是谁做，说明这个分工是怎么形成的，再说你承担哪一部分。 |
| 延伸示范 | My partner does, mainly because he's faster and I'm the one who clears up. | 主要是我伴侣做，因为他手脚更快，我负责收拾。 |
| 追问 | Has that always been the case? | 一直都是这样吗？ |

典型错误：

- ✗ **My mother makes the most of cooking.**
- ✓ **My mother does most of the cooking.**
- 原因：做饭的搭配是 `do the cooking`；`make the most of` 是"充分利用"的意思，不是一回事。

**第 3 题**（q_index 2）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A2 | A2 类角度 |
| 答题结构 | Name the dish, say where you learned it, then who you cook it for. | 说出菜名，说明在哪儿学的，再说你会做给谁吃。 |
| 延伸示范 | A lentil soup my grandmother made — six ingredients and almost impossible to ruin. | 我奶奶做的一种扁豆汤 —— 六种食材，几乎不可能做砸。 |
| 追问 | Where did you learn it? | 你在哪儿学的？ |

典型错误：

- ✗ **I can cook well the rice.**
- ✓ **I can cook rice well.**
- 原因：副词放在宾语之后；`rice` 不加冠词。

**第 4 题**（q_index 3）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A10 | A10 类角度 |
| 答题结构 | Choose one, name what each gives you, then say when you'd swap. | 选一边，说明两种方式各自给你什么，再说什么情况下你会换成另一种。 |
| 延伸示范 | At home, mostly — eating out is fine, but I resent paying for something I could make. | 大多在家吃 —— 出去吃也不错，但花钱买自己能做的东西让我不太舒服。 |
| 追问 | How often do you eat out? | 你多久出去吃一次？ |

典型错误：

- ✗ **I prefer eat at home than in restaurant.**
- ✓ **I prefer eating at home to eating out.**
- 原因：`prefer` 后面用 -ing；两个选项之间用 `to` 连接。

**第 5 题**（q_index 4）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A5 | A5 类角度 |
| 答题结构 | Say what changed, name one food you now like, then one you have given up. | 说明发生了什么变化，举一样你现在爱吃的食物，再说一样你已经不吃的。 |
| 延伸示范 | Enormously — I wouldn't touch anything bitter as a child and now I live on coffee and olives. | 变化特别大 —— 小时候我碰都不碰苦味的东西，现在却靠咖啡和橄榄过活。 |
| 追问 | What changed it? | 是什么改变了你的口味？ |

典型错误：

- ✗ **My taste is changed since I am a child.**
- ✓ **My taste has changed since I was a child.**
- 原因：`since` 接一个过去的时点时，主句用现在完成时，从句用一般过去时。

---

## 四、provenance/card_p1_food_shopping_001.json（原始题卡留档）

### 外层字段

| 字段 | 原文 | 中文 |
|---|---|---|
| `part` | 1 | 属于 Part 1 |
| `card_set_id` | set_food_meals_001 | 所属题组：食物与日常 |
| `topic_id` | topic_food | 话题分类：食物 |
| `title` | shopping for food | 购买食物 |
| `difficulty` | core | 难度：核心级 |
| `tags_json` | food, shopping, habits | 标签：食物、购物、习惯 |

### payload_json

| 字段 | 原文 | 中文 |
|---|---|---|
| `schema_version` | 2 | 结构版本 2 |
| `frame_tier` | 3 | 框架层级 3 |
| `frame_kind` | topic | 框架类型：话题 |

### teaching 段（教学素材）

- `tense_focus`：**present simple with frequency adverbs, plus present perfect on the last question**
  → 时态焦点：配合频度副词使用一般现在时；最后一题用现在完成时。
- `band_move`：**Keep food nouns uncountable. 'Foods', 'breads' and 'rices' will cost you across this whole frame.**
  → 提分动作：食物名词保持不可数。`foods`、`breads`、`rices` 这几个错法会在整个话题框架里持续扣分。

**第 1 题**（q_index 0）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A1 | A1 类角度 |
| 答题结构 | Name the place, say why there, then name what you buy elsewhere. | 说出地点，说明为什么选那里，再说哪些东西你会在别处买。 |
| 延伸示范 | The supermarket for most of it, and the Turkish shop on the corner for anything fresh. | 大部分在超市买，新鲜的就去街角那家土耳其小店。 |
| 追问 | Is that the nearest one? | 那家是最近的吗？ |

典型错误：

- ✗ **I buy my foods in the supermarket.**
- ✓ **I buy my food at the supermarket.**
- 原因：`food` 没有复数形式；在某个地点买东西用 `at`。

**第 2 题**（q_index 1）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A4 | A4 类角度 |
| 答题结构 | Say whether you do, say what happens when you don't, then how strictly you follow it. | 先说你是不是每次都写，再说不写会怎样，最后说你执行得有多严格。 |
| 延伸示范 | Always — and I still come home with three things that weren't on it. | 每次都写 —— 但我还是会买回三样清单上没有的东西。 |
| 追问 | On paper or on your phone? | 写在纸上还是记在手机里？ |

典型错误：

- ✗ **I write always a list before shopping.**
- ✓ **I always write a list before I go.**
- 原因：频度副词放在实义动词之前。

**第 3 题**（q_index 2）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A3 | A3 类角度 |
| 答题结构 | Give the frequency, say what decides it, then what you buy in between. | 说出频率，说明是什么决定了这个频率，再说中间你会补买什么。 |
| 延伸示范 | A big shop once a week, then two or three small ones for bread and milk. | 一周大采购一次，中间再为面包和牛奶补买两三次。 |
| 追问 | Who does it in your house? | 在你家是谁负责买？ |

典型错误：

- ✗ **I go shopping two times in a week.**
- ✓ **I go shopping twice a week.**
- 原因：「一周两次」的地道说法是 `twice a week`；这里用 `in` 是错的。

**第 4 题**（q_index 3）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A10 | A10 类角度 |
| 答题结构 | Pick one, say which foods you make an exception for, then why. | 选一边，说明哪些食物你会例外处理，再说明原因。 |
| 延伸示范 | Price for most things, quality for anything I'm going to taste on its own. | 大多数东西看价格，单独入口品尝的东西看质量。 |
| 追问 | Has that changed recently? | 最近有变化吗？ |

典型错误：

- ✗ **I look first the price.**
- ✓ **I look at the price first.**
- 原因：`look` 后面要加 `at`；`first` 放在宾语之后。

**第 5 题**（q_index 4）

| 项目 | 原文 | 中文 |
|---|---|---|
| 出题角度 | A5 | A5 类角度 |
| 答题结构 | Say whether you have, name when, then whether you'd do it again. | 先回答有没有过，说明是什么时候，再说你还会不会再来一次。 |
| 延伸示范 | I have, during the winter, and half the fruit arrived in a state I wouldn't have chosen. | 买过，去年冬天，一半的水果到手的状态我根本不会挑。 |
| 追问 | Would you do it again? | 你还会再买吗？ |

典型错误：

- ✗ **Yes, I buy food online last year.**
- ✓ **Yes, I bought food online last year.**
- 原因：出现明确的过去时间状语时，要用一般过去时。

---

## 附：JSON 字段中文含义速查

| 字段 | 中文含义 |
|---|---|
| `id` | 稳定编号（教材编号 / 课编号 / 题卡编号，代码与数据库依赖它） |
| `title` | 展示名称 |
| `sourceUrl` / `sourceCommit` | 来源地址 / 固定参考提交号 |
| `license` | 内容许可 |
| `lessons` | 课列表 |
| `objective` | 本项目编写的练习目标 |
| `sourceCardId` | 对应的原始题卡编号 |
| `questions` | 原始完整题组，不改写原文 |
| `addedGuidance` | 本项目编写的陪练指导，不冒充教材原文 |
| `aliases` | 本项目标注的课名别称，用于判断用户是否明确点名了这门课 |
| `part` | 属于口语第几部分 |
| `card_set_id` | 所属题组 |
| `topic_id` | 话题分类 |
| `difficulty` | 难度级别 |
| `tags_json` | 标签 |
| `payload_json` | 原始题卡本体（留档用，运行时不被读取） |
| `frame_tier` / `frame_kind` / `frame_slot` / `frame_role` | 上游的题目编排框架：层级 / 类型 / 槽位 / 角色 |
| `tense_focus` | 本题组的时态焦点 |
| `band_move` | 提升表现的关键动作 |
| `angle` | 出题角度编号 |
| `answer_shape` | 建议的答题结构 |
| `extend_move` | 高分延伸示范句 |
| `common_error` | 典型错误（`wrong` 错句 / `right` 正确说法 / `why` 原因） |
| `probe` | 追问句 |
