# ClaudeCode 修改指导文档：通用表格标题-特征匹配与两阶段重绑定修复方案

## 1. 目标

本次修改的目标不是仅修复“利润表 / 资产负债表 / 现金流量表”这类财务表，而是把现有的两阶段解析流程升级为**通用表格标题-特征匹配系统**，适用于任意类型的表格，包括但不限于：

- 财务报表（利润表、现金流量表、资产负债表、财务比率表、市场数据表）
- 评级说明表
- 联系信息表
- 课程表、成绩表、库存表、价目表、项目清单、参数表、配置表等通用结构化表格

核心原则：

1. **不要人工写死“某些关键词 -> 某种表”的最终规则** 作为主判定依据。
2. **表类型判定必须以单表 feature 为主、caption 为辅**。
3. **允许 caption 与 feature 冲突，并优先信 feature + 邻近上下文**。
4. **必须允许 unknown/未确定，不得强行归类。**
5. **不要把结构化抽取后的结果再次降级为普通文本检索主路径。**

---

## 2. 当前实际问题总结

### 2.1 两阶段分类在设计上是对的，但运行时没有真正生效

现有代码已经具备以下结构：

- `parseMarkdownWithStructure(contentJson, fullMd)` 在识别到 V2 页面结构后进入 `parseContentListV2TwoStage(...)`。 
- `parseContentListV2TwoStage(...)` 会执行：
  1. `extractTableObjects(contentList)`
  2. `classifyTablesWithLLM(tableObjects)`
  3. `applyGlobalConsistency(classifications)`
  4. `extractStructuredRows(tableObj, classification)`
  5. `convertStructuredRowsToChunks(...)`

说明系统已经不是“直接按原始 caption 切 chunk”，而是试图做“单表分类 + 重绑定 + 结构化抽取”。

### 2.2 但当前最大的致命问题：LLM 输出解析全部失败

日志中已经证明，LLM 的判断实际上是正确或接近正确的，例如：

- 原始 caption = `现金流量表` 的表，被 LLM 判为 `资产负债表`
- 原始 caption = `会计年度 / 利润表` 的表，被 LLM 判为 `现金流量表`
- 原始 caption 为空的表，被 LLM 判为 `利润表`

这说明当前流程已经是“按单表 feature 判类型”，不是“根据 caption 反推 feature”。

**但是**，LLM 返回的是带 markdown 代码块的字符串，例如：

```json
{
  "predictedType": "利润表",
  "confidence": 0.95,
  ...
}
```

而 `parseLLMClassificationOutput(String jsonOutput, String tableId)` 直接执行：

```java
JsonNode node = objectMapper.readTree(jsonOutput);
```

因此遇到开头的 ```json 会直接报错，导致所有分类结果都退化为：

- `predictedType = 未知`
- `confidence = 0.0`
- `resolvedTitle = 未知`

后果是：

1. 阶段 2 的正确分类结果全部丢失。
2. 阶段 3 只是在处理一堆 `未知`。
3. 最终又退回到 `parseTableChunkV2(...)` 路径，继续使用原始 caption 和原始 html 切块。

这不是算法思想错误，而是**工程落地失败**。

### 2.3 另一个关键问题：结构化查询接口没有真正实现

当前 `queryStructuredData(...)` 仍然是 `TODO`，直接返回空列表：

```java
public List<StructuredFinancialRow> queryStructuredData(
        String tableType,
        String rowLabel,
        String columnHeader,
        List<String> items) {

    log.info("[MinerU] 阶段5: 查询结构化数据 ...");
    return new ArrayList<>();
}
```

这意味着即使阶段 4 已经抽出了结构化记录，问答阶段也没有真正走结构化精确查询，仍然会退化为文本 chunk + 生成模型总结。

### 2.4 当前 `convertStructuredRowsToChunks(...)` 会把结构化数据重新降级为普通文本

这一步仅适合展示或兜底，不应该作为主查询路径。

---

## 3. 必须明确的架构原则（通用版本）

为了支持“通用表格标题-特征匹配”，请严格遵守以下原则：

### 3.1 表的“类型”不是预定义枚举的硬限制

不要把系统设计成只能输出以下几种：

- 利润表
- 资产负债表
- 现金流量表
- 未知

LLM 应该可以输出任意合理类型，例如：

- 市场数据表
- 财务预测表
- 财务比率分析表
- 投资评级说明表
- 分支机构联系信息表
- 学生成绩表
- 课程安排表
- 库存清单表
- 参数配置表
- 未知

也就是说，**通用性来自“开放类型空间”**，不是“把所有类型都预注册”。

### 3.2 表分类必须是“单表判定”，不是“全局 caption 池匹配”

禁止把流程设计成：

1. 先收集所有 caption；
2. 再把某张表往这些 caption 上硬匹配；
3. 最终决定这张表属于哪个 caption。

正确做法是：

1. 每张表提取自己的 `TableObject`；
2. 用该表的 `rowLabels / headerCandidates / rawCaption / prevTexts / nextTexts / footnote / bbox / page` 作为完整证据；
3. 让 LLM **对该表本身进行分类**；
4. 输出 `predictedType`、`resolvedTitle`、`rawCaptionConflict`；
5. 程序再决定如何使用该结果。

### 3.3 caption 只是弱证据，不是主真值

对于任意表格：

- 如果 `rawCaption` 与 `rowLabels/headerCandidates` 一致，可保留；
- 如果冲突，必须允许 `rawCaptionConflict=true`；
- 如果 `rawCaption` 为空，但 feature 足够强，仍然要允许分类成功；
- 如果 feature 不足，也必须允许 `unknown`。

### 3.4 结构化数据一旦抽出，就不能再作为普通文本主检索路径

结构化抽取的意义在于：

- 把“让 LLM 读长表”变成“程序查表”；
- 保证问答结果完整、可校验、可回溯。

因此：

- `StructuredRow` / `StructuredTable` 应进入存储层或缓存；
- 查询应优先走结构化检索；
- `convertStructuredRowsToChunks(...)` 仅作为辅助展示，不作为主检索。

---

## 4. 必须完成的修改项（按优先级）

## P0：修复 LLM 输出解析失败

### 问题
`parseLLMClassificationOutput(...)` 直接 `readTree(jsonOutput)`，无法解析带 markdown 代码块的输出。

### 必须修改
在 JSON 解析前增加标准化清洗函数，去掉：

- 前后 ```json
- 前后 ```
- 可能的首尾多余空白

### 推荐实现
新增：

```java
private String normalizeLlmJson(String raw) {
    if (raw == null) return "";
    String s = raw.trim();
    s = s.replaceFirst("^```json\\s*", "");
    s = s.replaceFirst("^```\\s*", "");
    s = s.replaceFirst("\\s*```$", "");
    return s.trim();
}
```

然后在 `parseLLMClassificationOutput(...)` 中：

```java
String cleaned = normalizeLlmJson(jsonOutput);
JsonNode node = objectMapper.readTree(cleaned);
```

### 必须加日志
分类成功后打印：

- `tableId`
- `predictedType`
- `confidence`
- `resolvedTitle`
- `rawCaptionConflict`

目的：确认阶段 2 真的生效，而不是又 silently fallback。

---

## P0：让第二次扫描真正使用 `resolvedTitle`，而不是继续用原始 caption

### 问题
即使 LLM 分类正确，如果后续 chunk/结构化记录仍然使用原始 caption，就会重新把错误标题带回系统。

### 必须修改
在阶段 4/5 中：

- 只要分类成功并通过阈值，就优先使用 `classification.getResolvedTitle()`；
- 只有分类失败/低置信时，才回退到原始 `table_caption` 或 `sectionPath`。

### 适用范围
不仅适用于财务表，也适用于：

- `投资评级说明` 纠正为 `投资评级标准表` 或保留原标题；
- `兴业证券研究` 纠正为 `分支机构联系信息表`；
- `市场数据` 细化为 `市场数据（财务指标）`。

---

## P0：实现真正可用的结构化查询接口（通用版）

### 问题
当前 `queryStructuredData(...)` 为空实现，导致结构化抽取没有闭环。

### 必须修改
请把“结构化查询”抽象成**通用表查询**，而不是只服务财务表。

#### 不推荐的旧签名
```java
queryStructuredData(String tableType, String rowLabel, String columnHeader, List<String> items)
```

#### 推荐的新抽象
可设计为两层：

##### 方案 A：通用表单元格查询
```java
List<StructuredCell> queryStructuredCells(
    String documentId,
    String tableType,
    List<String> resolvedTitles,
    List<String> rowLabels,
    List<String> columnHeaders,
    double minConfidence
)
```

##### 方案 B：通用结构化表查询
```java
List<StructuredTableRecord> queryStructuredTable(
    String documentId,
    String tableType,
    Map<String, List<String>> filters,
    double minConfidence
)
```

对于财务表，filters 可以是：

- `year = [2022, 2023E, 2024E, 2025E]`
- `item = [营业收入, 营业成本, ...]`

对于联系信息表，filters 可以是：

- `city = [上海, 北京]`
- `field = [地址, 邮编, 邮箱]`

### 核心要求
- **结构化查询必须支持“请求项全集”校验**。
- 对未命中的项明确返回“未找到”，不能静默省略。

---

## P0：不要再把结构化结果“降级回普通文本”作为主回答路径

### 当前问题
`convertStructuredRowsToChunks(...)` 会把结构化记录重新拼成：

```text
【利润表】
2022年:
- 营业收入: 4432
- 营业成本: 3597
...
```

这对于展示可以，但对于精确问答会再次依赖 LLM 做总结，容易：

- 漏项
- 少行
- 压缩结果
- 混年份

### 必须修改
把结构化结果分成两个用途：

1. **主查询对象**：结构化记录/结构化表对象
2. **展示辅助对象**：可读文本块

其中：
- 主问答必须走结构化查询；
- 展示块仅用于预览、回显、人工检查。

---

## P1：把 `TableObject` 和 `StructuredRow` 抽象成“通用表格实体”

当前命名如 `StructuredFinancialRow` 明显偏财务，会限制通用性。

### 建议重命名
- `StructuredFinancialRow` -> `StructuredTableCellRecord` 或 `StructuredTableRecord`
- `statementType` -> `tableType`
- `year` -> `columnHeader`
- `item` -> `rowLabel`

如果已有代码耦合严重，可以先做兼容层，但新字段语义必须通用。

### 推荐最小通用模型

```java
class StructuredTableRecord {
    String documentId;
    String tableId;
    int page;
    String tableType;          // resolvedTitle / predictedType
    double confidence;

    String rawCaption;
    String resolvedTitle;
    boolean rawCaptionConflict;

    String rowLabel;
    String columnHeader;
    String value;
    String unit;

    String sourceSectionPath;
    String sourcePreview;
}
```

这样同一套结构既能表示财务表，也能表示地址表、参数表、成绩表。

---

## P1：完善 LLM 提示词，让它天然支持通用表格

### 当前提示词方向是对的
日志显示你已经让模型输出：

- `predictedType`
- `confidence`
- `evidence`
- `rawCaptionConflict`
- `resolvedTitle`
- `notes`

而且类型说明里已经允许：

- 利润表
- 学生成绩表
- 课程表
- 库存清单等

这个方向需要保留。

### 但要进一步强化的约束
请把提示词改成以下原则：

1. `predictedType` 可以是任意自然语言表类型，但必须简洁。
2. `evidence` 必须引用输入中的 `rowLabels/headerCandidates/rawCaption/prevTexts/nextTexts`，不能凭空编造。
3. 若仅凭 caption 无法判断，必须主要依据行标签与表头。
4. 若证据不足，必须输出 `未知`。
5. 不允许输出 markdown 代码块。
6. 不允许在 JSON 外输出任何额外解释。

### 强制输出格式
应明确要求：

- 返回 **纯 JSON 字符串**，不能有 ```json 包裹。

例如：

```text
只返回一个 JSON 对象，不要添加 ```json，不要添加任何额外说明文字。
```

这一步虽然不能百分之百避免模型犯错，但能显著降低解析失败概率。

---

## P1：保留“全局一致性约束”，但改成通用版本

### 当前问题
`applyGlobalConsistency(...)` 看起来更像是“同类型只保留一个高置信候选”。

这对于财务三大表有一定意义，但对于通用表格场景会有副作用：

- 一个文档里可能真的有多个“参数表”
- 也可能有多个“联系信息表”
- 多个 `未知` 更不能这样降级

### 必须修改
一致性约束不能简单理解为“同类型只能有一个”。

应改为：

#### 通用约束原则
1. **只对“明显同一组互斥类型”做约束**，例如同一附表区域中：
   - 利润表
   - 现金流量表
   - 资产负债表
2. 对开放类型（市场数据表、联系信息表、配置表等）不做“全局唯一”限制。
3. `未知` 不要用“同类型多候选”逻辑再降级，因为 `未知` 本身不是类型。
4. 若两个表都高置信地判成同一类型，保留二者，但打日志告警，不要轻易覆盖。

### 推荐做法
把一致性约束拆成两层：

- `applyMutualExclusionForKnownFamilies(...)`
- `applyConfidenceFloorAndUnknownFallback(...)`

其中“known families”指的是少数有明确互斥关系的表家族，不是全部表。

---

## P1：让 `extractStructuredRows(...)` 变成通用表格抽取，而不是财务专用

### 当前问题
从日志片段看，当前结构化抽取假设：

- 第一列是 `rowLabel`
- 第一行之后是多个年份列
- 输出字段是：`statementType/year/item/value`

这非常适合财务报表，但不适合所有表。

### 必须升级成通用抽取
对任意表，至少支持以下三种模式：

#### 模式 A：标准矩阵表
- 第一行是列头
- 第一列是行名
- 其余是单元格

例如财务表、成绩表、价目表。

#### 模式 B：键值表（2 列或少量列）
- 左列是字段名，右列是值

例如：市场数据表、基础信息表、配置表。

#### 模式 C：多列记录表
- 每一行是一条记录
- 第一行是列头

例如：联系信息表、人员名单表、库存明细表。

### 建议
把表结构模式抽出来单独判定：

```java
enum TableShape {
    MATRIX,
    KEY_VALUE,
    RECORD_LIST,
    UNKNOWN
}
```

再根据 shape 执行不同的结构化抽取器。

---

## P1：通用标题匹配不应只依赖“类型名”，还应支持“标题分辨率提升”

有些表并不是“标题错了”，而是“标题太泛”。

例如：
- `市场数据` -> `市场数据（财务指标）`
- `兴业证券研究` -> `分支机构联系信息表`
- `主要财务指标` -> `主要财务指标预测`

这类情况不是简单纠错，而是**标题分辨率提升**。

### 必须支持两类输出
- `predictedType`: 表的类型类别
- `resolvedTitle`: 当前文档上下文下最合适的表标题

它们可以相同，也可以不同。

例如：

```json
{
  "predictedType": "联系信息表",
  "resolvedTitle": "兴业证券分支机构联系信息"
}
```

---

## P2：保留 `content_list_v2.json` 为主，`full.md` 为辅，但通用化使用方式

### 当前原则仍然正确
- `content_list_v2.json` 提供：页面、block 类型、bbox、table html、caption、footnote
- `full.md` 提供：连续上下文、表前后自然语言线索

### 通用化要求
`full.md` 不能只服务财务表，也要服务一般表：

- 联系信息表前面的章节名
- 参数表前后的说明段落
- 成绩表前面的课程名
- 配置表前面的模块标题

### 使用原则
- JSON 是主骨架
- MD 是邻近上下文补充
- 不得把 MD 当成单独真值表来源

---

## 5. 具体修改建议（方法级）

## 5.1 修改 `parseLLMClassificationOutput(...)`

### 必改点
- 增加 `normalizeLlmJson(...)`
- 解析失败时记录 cleaned/raw 双日志
- 解析成功时打印 classification summary

### 结果要求
分类结果不能再全部变成 `未知 / 0.0`。

---

## 5.2 修改 `classifyTablesWithLLM(...)`

### 必改点
- 调用 LLM 后，先清洗输出再解析
- 若 LLM 输出非法 JSON，可尝试“提取第一个最外层大括号内容”作为二次修复
- 若仍失败，再 fallback

### 推荐兜底函数
```java
private String extractFirstJsonObject(String raw) {
    if (raw == null) return "";
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start >= 0 && end > start) {
        return raw.substring(start, end + 1);
    }
    return raw;
}
```

---

## 5.3 修改 `applyGlobalConsistency(...)`

### 必改点
不要再按“同类型只能留一个”做通用降级。

### 推荐改法
- 只对明确互斥的已知家族做约束（例如财务三大表）
- 开放类型不做全局唯一约束
- `未知` 类型不参与“同类型冲突”逻辑

---

## 5.4 修改 `shouldExtractAsStructured(...)`

### 当前问题
只要不是 `未知` 且置信度 >= 0.6 就抽，逻辑可以保留，但要更通用。

### 建议
增加基于 `TableShape` 的判断：

- 只有当 `shape != UNKNOWN` 时才进行结构化抽取；
- 类型名称不是关键，结构可解析才关键。

---

## 5.5 重写 `queryStructuredData(...)` 为通用表查询

### 必改点
- 真正连接存储层或内存缓存
- 支持按：
  - `documentId`
  - `tableType / resolvedTitle`
  - `rowLabels`
  - `columnHeaders`
  - `page / tableId`
  - `minConfidence`
  做查询

### 严格要求
必须支持“完整性校验”：

- 请求项全集
- 实际命中项
- 差集输出

不能漏项不报。

---

## 5.6 弱化 `convertStructuredRowsToChunks(...)` 的职责

### 改造目标
- 可以保留，但仅用于展示和调试；
- 不允许作为核心问答主路径。

可以考虑在 `TextChunk` 中加入标签：

- `chunkSource = STRUCTURED_PREVIEW`
- `chunkSource = RAW_TABLE_FALLBACK`

便于检索时区分优先级。

---

## 6. 测试要求（必须覆盖通用表）

请至少构造以下测试集：

### A. 财务表类
- 原始 caption 正确、内容也正确
- 原始 caption 错误、内容可纠正
- 原始 caption 为空、内容可判定
- 多张财务表混排

### B. 非财务通用表类
- 市场数据表
- 投资评级说明表
- 联系信息表
- 课程表
- 学生成绩表
- 库存/物料清单
- 参数配置表

### C. 异常类
- LLM 返回带 ```json
- LLM 返回前后多余解释文本
- 行标签极少、无法判定
- caption 和 feature 都很弱
- 表结构异常（列数不一致）

### D. 验收标准
1. LLM 分类结果能被成功解析。
2. `rawCaptionConflict=true` 的表能真正用 `resolvedTitle` 进入后续流程。
3. 通用表不会被强行挤进财务类型。
4. `queryStructuredData(...)` 不再返回空列表。
5. 对用户要求的行/列，若查不到，必须明确返回未找到。

---

## 7. 你必须遵守的实现边界

1. **不要把系统改成“人工规则主导分类”。**
   - 允许 fallback 使用有限规则兜底；
   - 但主路径必须是：单表特征 -> LLM 分类 -> 程序校验。

2. **不要把通用性理解成“全部靠 LLM 自由发挥”。**
   - LLM 负责分类；
   - 程序负责：解析、校验、一致性、存储、查询、完整性约束。

3. **不要把结构化抽取重新降级为普通文本 RAG。**

4. **不要只围绕财务表写死字段。**
   - 字段名、列头、标题都应设计为通用抽象。

5. **允许 unknown。**
   - 通用系统的成熟标志不是“什么都能猜出来”，而是“证据不足时不乱猜”。

---

## 8. ClaudeCode 的执行清单（按顺序）

### 第一步
修复 `parseLLMClassificationOutput(...)`，解决 markdown 代码块导致的 JSON 解析失败。

### 第二步
确保阶段 2 的分类结果真正进入阶段 4/5，分类成功时优先使用 `resolvedTitle`。

### 第三步
将 `applyGlobalConsistency(...)` 改成“已知互斥家族约束 + 开放类型保留”的通用版本。

### 第四步
把结构化实体从“财务行”升级为“通用表格记录”。

### 第五步
真正实现通用版 `queryStructuredData(...)`，并接入存储层/缓存。

### 第六步
确保最终问答优先走结构化查询，不再把 `convertStructuredRowsToChunks(...)` 作为主路径。

### 第七步
补齐测试：财务表 + 非财务表 + 异常输出。

---

## 9. 最终判断标准

如果修改正确，系统应满足：

1. 能识别“caption 错，但 feature 对”的表。
2. 能识别“caption 空，但 feature 足够强”的表。
3. 能处理非财务表，不会强制套用财务报表类型。
4. LLM 输出即使带 ```json，也不会导致整批分类全部失效。
5. 分类成功后，后续结构化抽取与查询真正使用 `resolvedTitle`。
6. 面向用户查询时，返回结果来自结构化查询，而不是长文本总结。
7. 对请求项不再静默漏项。

---

## 10. 最后一句要求

请不要把这次修改理解成“修一个利润表 bug”。

这次修改的真正目标是：

> **把当前的两阶段表格解析流程，升级成一个“通用的、可纠偏的、可结构化查询的表格理解系统”。**

