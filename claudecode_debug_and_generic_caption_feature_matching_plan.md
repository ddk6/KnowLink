# ClaudeCode 修改指导文档：通用表格标题-特征匹配、排查与修复方案

## 1. 文档目的

本文件用于指导 ClaudeCode 修改当前 `MinerUService` 两阶段表格解析/查询链路。目标有三个：

1. **确认当前系统已经从“按 caption 猜表”升级为“按单表 feature 分类，caption 只是参考”**；
2. **将“表的 caption 和 feature 匹配”做成通用能力**，而不仅限于利润表、资产负债表、现金流量表；
3. **彻底修复当前“查询结果大部分无数据”的问题**，让结构化记录真正进入查询链路，并按请求项全集返回。

---

## 2. 当前状态结论（基于最新日志）

### 2.1 当前已经不是“先看 caption 再猜表”了

从最新日志可以确认，当前阶段 2 的行为是：

- 先对每张表提取 `TableObject`
- 把这张表的：
  - `rawCaption`
  - `prevTexts`
  - `headerCandidates`
  - `yearColumns`
  - `rowLabels`
- 一起发给 LLM
- 由 LLM 给出：
  - `predictedType`
  - `confidence`
  - `rawCaptionConflict`
  - `resolvedTitle`

这说明：**当前逻辑是“单表 feature 分类”**，不是“建立 caption 池，然后拿 caption 去倒推 feature”。

### 2.2 最新日志中，分类结果已经成功落地

旧版本的主要问题是：LLM 返回 ```json 代码块，`parseLLMClassificationOutput(...)` 解析失败，所有表都掉回“未知”。

但最新日志里，这个问题已经修好。每张表都出现了：

- `分类成功 -> predictedType=..., confidence=..., resolvedTitle=...`

说明阶段 2 分类结果已经真正进入程序。

### 2.3 但“查询结果大部分无数据”说明问题已经转移到了查询层

最新日志显示：

- 两阶段解析完成
- 共生成 **41 个 chunks**
- 共提取 **507 条结构化记录**

这说明解析/抽取层已经不再是“完全抽不到”的状态。  
现在“大部分无数据”的高概率原因是：

1. 结构化记录**没有真正写入查询缓存**，或者查询时 `documentId` 对不上；
2. 查询层使用了**过严的精确匹配**，导致绝大部分记录被过滤掉；
3. 年份、标题、行名**缺少归一化**；
4. “完整性校验”只是打日志，没有把“未找到”项补入返回结果。

---

## 3. 当前系统识别出的表-特征-标题映射（基于最新日志）

下面这张表非常重要，它说明“caption + feature 匹配”已经具备基本能力，但仍需整理成**通用机制**。

| 表ID | 原始 caption | 关键 feature（示例） | LLM 判定类型 | resolvedTitle | 冲突 |
|---|---|---|---|---|---|
| A = `p1_t3` | `市场数据` | `[净资产, 总资产, 每股净资产, 总股本, 流通股本, 市场数据日期]` | 公司财务数据表 | 公司市场与财务数据（含预测） | 是 |
| B = `p2_t0` | `主要财务指标` | `[营业收入, 归母净利润, 毛利率, ROE, 每股收益, 市盈率]` | 主要财务指标表 | 主要财务指标 | 否 |
| C = `p3_t3` | `现金流量表` | `[流动资产, 非流动资产, 资产总计, 流动负债, 短期借款, 应付票据及应付账款]` | 资产负债表 | 资产负债表 | 是 |
| D = `p3_t4` | `会计年度 / 利润表` | `[经营活动产生现金流量, 投资活动产生现金流量, 融资活动产生现金流量, 现金净变动, 折旧和摊销, 营运资金的变动]` | 现金流量表补充资料（间接法） | 现金流量表（间接法）补充资料 / 预测 | 是 |
| E = `p3_t5` | `(空)` | `[营业收入, 营业成本, 税金及附加, 销售费用, 管理费用, 研发费用, 财务费用, 营业利润, 利润总额, 所得税, 净利润, EPS(元)]` | 利润表 | 利润表预测（2022-2025E） | 是 |
| F = `p3_t6` | `主要财务比率` | `[成长性, 盈利能力, 偿债能力, 营运能力, 每股资料, 营业收入增长率, 毛利率, 资产负债率, 每股收益]` | 财务比率分析表 | 主要财务比率 | 否 |
| G = `p4_t2` | `投资评级说明` | `[投资建议的评级标准, 类别, 评级, 说明, 增持, 中性, 减持, 无评级]` | 投资评级标准表 | 投资建议评级标准说明 | 是 |
| H = `p4_t16` | `兴业证券研究` | `[地址, 邮编, 邮箱, 上海, 北京, 深圳]` | 联系信息表 | 兴业证券分支机构或部门联系信息表 | 是 |

---

## 4. 你现在的直觉是对的：把每个表的 `[a,b,c]` 和标题拿出来让 LLM 匹配，并不难

### 4.1 推荐的通用思想

不要把问题设计成“财务三大表专用规则”，而要设计成：

> **对每张表抽取一组 feature summary，然后让 LLM 判断：  
> 这张表最可能是什么主题/标题？  
> 原始 caption 是否冲突？  
> 如果冲突，应该把标题纠正成什么？**

也就是说，通用策略应当是：

#### 输入：
- `tableId`
- `rawCaption[]`
- `prevTexts[]`
- `headerCandidates[]`
- `yearColumns[]`
- `rowLabels[]`
- 可能还有 `sampleCells[]`

#### 输出：
- `predictedType`
- `resolvedTitle`
- `confidence`
- `rawCaptionConflict`
- `evidence[]`

### 4.2 用一句更工程化的话来说

对于任意表，不管是财务表、比率表、评级表、联系信息表、学生成绩表、库存表，都统一走：

> **“标题只是候选，feature 才是主证据，最终输出 resolvedTitle”**

这才是真正通用的方案。

---

## 5. 当前“大部分无数据”的高概率原因

这部分是当前最关键的排查点。

### 5.1 原因 1：结构化记录可能没有真正写进查询缓存

当前代码中：

- `parseMarkdownWithStructure(...)` 返回 `ParseResult(chunks, structuredRecords)`
- `downloadAndParseZip(...)` 把 `parseResult.structuredRecords` 塞进 `MinerUParseResult`
- 但是查询函数 `queryStructuredData(...)` 只从 `structuredRecordCache` 查

这意味着：  
**如果解析完成后没有把 `parseResult.structuredRecords` 写进 `structuredRecordCache`，查询时就会拿到空结果。**

#### 需要确认的点
检查主调用链里是否真的执行了：

```java
structuredRecordCache.put(documentId, parseResult.structuredRecords);
```

或者等价逻辑。

#### 如果没有，就必须补上。

---

### 5.2 原因 2：查询条件是“精确匹配”，没有做 normalize

当前查询过滤逻辑大概率类似：

- `tableType.equals(record.getTableType())`
- `columnHeaders.contains(record.getColumnHeader())`
- `rowLabels.contains(record.getRowLabel())`

这在工程上过于脆弱。

#### 典型会 miss 的情况

##### 表名 miss
用户 query 用的是：

- `利润表`

而当前分类结果存的是：

- `利润表预测（2022-2025E）`

如果直接精确比对，就会 miss。

##### 年份 miss
表内年份是：

- `2022`
- `2023E`
- `2024E`
- `2025E`

用户可能问：

- `22 23 24 25 年`
- 或 `2022 2023 2024 2025`

如果没有 normalize，就会 miss。

##### 行名 miss
比如：

- 用户写 `EPS (元)`
- 表里是 `EPS(元)`

或者：

- 用户写 `归属母公司净利润`
- 表里是 `归母净利润`

没有 alias / normalize 就会 miss。

---

### 5.3 原因 3：阶段 4 的列头数量疑似有 off-by-one 问题

从之前的日志看：

- `p3_t5`（利润表）行数=23，列数=5，提取了 **110 条**结构化记录

这个数量非常可疑。  
如果一张利润表是：

- 第一列 = 科目名
- 后面四列 = `2022 / 2023E / 2024E / 2025E`

那么 22 行有效数据通常应该更接近 `22 × 4 = 88`，而不是 110。

这说明很可能发生了：

- 把“会计年度”也当成有效年份列；
- 或者 `years` 列表比真实年份列多一项；
- 或者列遍历边界条件写错。

#### 这会进一步导致：
- 查询时 `columnHeader` 乱掉
- 数据虽然抽出来了，但年份维度不对，查的时候自然 miss

---

### 5.4 原因 4：完整性校验只打日志，没有真正补齐返回项

现在系统看起来会做“完整性校验”，但很可能只是：

- 统计缺失项
- 打日志
- 最终只返回命中的记录

这会导致：
- 用户要 22 个字段
- 系统只命中 4 个
- 返回结果里只显示 4 个
- 剩下 18 个直接“消失”

从用户视角看，就是：

> “大部分都是无数据”  
> 或者 “大部分行没返回”

#### 正确做法
即使没查到，也必须显式返回：

- `item = 营业外收入`
- `value = 未找到`

而不是静默省略。

---

## 6. 排查方案（ClaudeCode 必须按顺序做）

---

### P0：先确认缓存有没有真的写进去

#### 任务
搜索以下关键点：

- `structuredRecordCache.put(`
- `cacheStructuredRows(`
- `downloadAndParseZip(...)` 返回后，谁接收 `MinerUParseResult`
- 是否存在把 `parseResult.structuredRecords` 写入缓存/数据库的逻辑

#### 需要加的日志
在解析结束后打印：

```java
log.info("[MinerU] 缓存写入: documentId={}, structuredRecordCount={}", documentId, records.size());
```

在查询开始前打印：

```java
log.info("[MinerU] 查询前缓存状态: documentId={}, hasCache={}, cacheSize={}",
    documentId,
    structuredRecordCache.containsKey(documentId),
    structuredRecordCache.getOrDefault(documentId, java.util.List.of()).size());
```

#### 预期结果
如果这里显示缓存为空，那么“大部分无数据”的第一根因就找到了。

---

### P1：打印阶段 4 的 years 和生成记录样本

#### 任务
在阶段 4 抽取时，打印：

- `tableId`
- `matrix.get(0)`（第一行）
- `years = [...]`
- 前 10 条生成记录 `(rowLabel, columnHeader/year, value)`

#### 建议日志
```java
log.info("[MinerU] 阶段4: 表 {} years={}", table.getTableId(), years);
log.info("[MinerU] 阶段4: 表 {} sampleRecord rowLabel={}, year={}, value={}",
    table.getTableId(), rowLabel, year, value);
```

#### 重点检查
是否出现：

- `years = [会计年度, 2022, 2023E, 2024E, 2025E]`

如果出现，说明列头处理有 bug。

---

### P2：给查询层增加 normalize

必须新增以下方法：

#### `normalizeTableType(String s)`
统一处理：
- `利润表`
- `利润表预测（2022-2025E）`
- `利润表（预测）`

都映射到 canonical key，例如：

- `利润表`

同理：

- `资产负债表`
- `现金流量表`
- `财务比率表`
- `投资评级标准表`
- `联系信息表`

#### `normalizeYear(String s)`
建议映射：

- `22` -> `2022`
- `23` -> `2023E`
- `24` -> `2024E`
- `25` -> `2025E`

也允许：

- `2023` -> `2023E`
- `2024` -> `2024E`
- `2025` -> `2025E`

#### `normalizeItem(String s)`
至少做：
- 去空格
- 去全角半角差异
- 括号统一
- `EPS (元)` -> `EPS(元)`
- `归属母公司净利润` -> `归母净利润`（可选 alias 表）

---

### P3：查询函数改成“归一化后匹配”，不是原始精确匹配

#### 当前错误方向
```java
tableType.equals(r.getTableType())
rowLabels.contains(r.getRowLabel())
columnHeaders.contains(r.getColumnHeader())
```

#### 修改方向
```java
normalizeTableType(tableType).equals(normalizeTableType(r.getResolvedTitle()))
normalizeItem(queryItem).equals(normalizeItem(r.getRowLabel()))
normalizeYear(queryYear).equals(normalizeYear(r.getColumnHeader()))
```

并且，**如果用户没有传 columnHeaders，就不要用年份过滤。**

---

### P4：完整性校验必须进入最终返回结果

新增逻辑：

1. 建立 `requestedItems`
2. 建立 `foundItems`
3. 做差集 `missingItems`
4. 对每个 `missingItem` 构造占位结果：

```java
StructuredTableRecord.builder()
    .resolvedTitle(tableType)
    .rowLabel(missingItem)
    .columnHeader(year)
    .value("未找到")
    .confidence(0.0)
    .build();
```

这样最终回答时不会“少行”。

---

## 7. 通用 caption-feature 匹配机制：建议的最终实现方案

---

### 7.1 不要把“表类型”写死成财务表分类器

不要只让系统输出：

- 利润表
- 资产负债表
- 现金流量表

而应允许输出任意主题型标题，例如：

- 公司市场与财务数据（含预测）
- 主要财务指标
- 资产负债表
- 现金流量表（间接法）补充资料 / 预测
- 利润表预测（2022-2025E）
- 财务比率分析表
- 投资建议评级标准说明
- 兴业证券分支机构或部门联系信息表

也就是说，**resolvedTitle 必须允许开放式生成。**

---

### 7.2 推荐的表摘要结构（给 LLM 用）

每张表摘要成：

```json
{
  "tableId": "p3_t5",
  "rawCaption": [],
  "prevTexts": ["附表", "资产负债表", "单位：百万元"],
  "headerCandidates": ["会计年度", "2022", "2023E", "2024E", "2025E"],
  "yearColumns": ["2022", "2023E", "2024E", "2025E"],
  "rowLabels": ["营业收入", "营业成本", "税金及附加", "销售费用", "管理费用", "研发费用", "财务费用", "营业利润", "利润总额", "净利润", "EPS(元)"]
}
```

然后让 LLM 输出：

```json
{
  "predictedType": "利润表",
  "confidence": 0.95,
  "rawCaptionConflict": true,
  "resolvedTitle": "利润表预测（2022-2025E）",
  "evidence": ["营业收入", "营业成本", "营业利润", "利润总额", "净利润", "EPS(元)"],
  "notes": "原始caption为空，但行标签完整符合利润表结构"
}
```

---

### 7.3 最终判定原则

#### 原则 1
**feature 是主证据，caption 是辅助证据。**

#### 原则 2
如果 `rawCaptionConflict=true` 且置信度高，**允许推翻原始 caption**。

#### 原则 3
如果 caption 为空，但 feature 足够强，**允许直接补标题**。

#### 原则 4
如果无法确定，也要允许：
- `predictedType = 未知`
- `resolvedTitle = 原始caption 或 sectionPath`

不能硬猜。

---

## 8. ClaudeCode 修改清单（必须执行）

### 必改项 1
确认并补齐：**结构化记录写入缓存/数据库**

### 必改项 2
给阶段 4 增加 `years` 和 sample records 日志

### 必改项 3
实现：
- `normalizeTableType`
- `normalizeYear`
- `normalizeItem`

### 必改项 4
修改 `queryStructuredData(...)`，使用归一化匹配

### 必改项 5
把完整性校验变成“返回占位结果”，而不是只打日志

### 必改项 6
对“caption-feature 匹配”做成通用机制，不限制于财务表

### 必改项 7
保留当前“单表分类”方向，不要退回“全局 caption 池匹配”

---

## 9. 最后一句话

你现在的直觉是对的：

> **把表的每个 `[a,b,c]`（feature 摘要）和标题拿出来让 LLM 匹配，并不难。**

真正的难点不在“匹配本身”，而在：

1. 匹配结果要真正落地；
2. 结构化记录要真正进入查询缓存；
3. 查询层要做 normalize；
4. 最终返回要做完整性保障。

只要这四步补齐，当前系统就会从“能解析但查不到”进化为“能解析、能定位、能完整返回”。
