# MinerU 财务表格重绑定与结构化抽取实施方案（给 ClaudeCode 执行）

## 1. 文档目的

这份文档用于指导 ClaudeCode 在**不依赖人工先验规则、不要求人工先看 PDF、不把人工参与当作必要条件**的前提下，基于同一批 MinerU 返回的：

- `content_list_v2.json`
- `full.md`

实现一套更稳健的财务表格解析、表类型重绑定、结构化抽取和查询链路。

目标不是“继续优化提示词”，而是把问题从“让 LLM 在混乱 HTML 和错误 caption 中猜答案”，转成“先对同一批解析结果做自洽重建，再做结构化提取和检索”。

---

## 2. 已知现状与核心问题

### 2.1 当前代码路径

现有代码主流程大致如下：

1. `downloadAndParseZip()` 读取 ZIP 中的：
   - `full.md`
   - `content_list_v2.json`
   - `layout.json`
2. 调用 `parseMarkdownWithStructure(contentJson, fullMd)`
3. 若 `contentJson` 可解析，则优先走 `parseContentListV2(root)`
4. 遇到 `table` block 时，调用 `parseTableChunkV2(contentObj, sectionPath, chunkId, pageNum)`
5. `parseTableChunkV2()` 当前直接使用 `contentObj.path("html")` 中的 HTML 作为表内容来源，并按 `<tr>` 进行切分

### 2.2 当前代码的关键限制

当前实现存在几个会直接放大错误的点：

#### 2.2.1 caption 只取第一个元素

现有逻辑只读取 `table_caption` 数组的第一个元素，类似：

```java
JsonNode captionNode = contentObj.path("table_caption");
String captionPreview = captionNode.isArray() && captionNode.size() > 0
        ? captionNode.get(0).path("content").asText() : "";
```

这会导致：

- `table_caption = ["会计年度", "利润表"]` 时，最终只保留 `会计年度`
- 丢掉真正更有语义价值的 `利润表`

#### 2.2.2 表内容仍然是原始 HTML

当前表格解析没有真正把 HTML 解析成二维矩阵，而是直接把 HTML 当作字符串使用。

这会导致：

- embedding 吃到大量标签噪声
- chunk 内容可读性差
- LLM 容易在长 HTML 中串行抄错

#### 2.2.3 大表按 `<tr>` 机械切分

当前逻辑会：

1. `split("(?=<tr>)")`
2. 取前两行作为 header
3. 余下数据行按 token 上限拼接成多个 chunk

这种分法对普通表也许可用，但对利润表、现金流量表、资产负债表这类**高结构化财务表**很不友好。非常容易出现：

- 开头几行正确
- 中间字段跨 chunk 漏召回或混表
- 尾部几行又重新对齐

这正是当前“前三行对、最后一两行对、中间错”的根本原因之一。

#### 2.2.4 `sectionPath` 对表帮助有限

当前 `currentSectionPath` 主要依赖 `title` block 更新，而很多表前的“附表”“资产负债表”“单位：百万元”等，在 MinerU 输出中往往是 `paragraph` 而不是 `title`。

因此表格最终 chunk 的上下文标题并不可靠。

---

## 3. 从样例中观察到的问题模式

基于当前样例，可明确观察到：

1. 某张表 caption 中出现“利润表”，但表内行名却包含：
   - 经营活动产生现金流量
   - 投资活动产生现金流量
   - 融资活动产生现金流量
   - 现金净变动
   - 现金的期初余额 / 期末余额

   这说明 caption 与表内容发生了错配。

2. 另一张表 caption 中出现“现金流量表”，但表内行名却包含：
   - 流动资产
   - 非流动资产
   - 资产总计
   - 流动负债
   - 负债合计
   - 股东权益合计

   这明显更接近资产负债表。

3. 真正的利润表通常包含：
   - 营业收入
   - 营业成本
   - 税金及附加
   - 销售费用
   - 管理费用
   - 研发费用
   - 财务费用
   - 其他收益
   - 投资收益
   - 公允价值变动收益
   - 信用减值损失
   - 资产减值损失
   - 资产处置收益
   - 营业利润
   - 利润总额
   - 所得税
   - 净利润
   - 归属母公司净利润
   - EPS(元)

4. `full.md` 和 `content_list_v2.json` 来自同一批结果，`full.md` 只是以更连续的文本顺序复述了同样的解析内容。它能提供辅助上下文，但**不会天然纠正 JSON 中已经存在的错绑定**。

因此，单用 `md` 不会比单用 `json` 更好；直接把二者原样都喂给 RAG，也通常不会更好。

---

## 4. 总体设计原则

### 原则 1：不采用人工先验规则直接决定表类型

不允许人工提前看 PDF，然后写“只要出现某词就是利润表”的硬编码规则作为最终判定依据。

### 原则 2：允许使用同一批解析结果进行二次自洽重建

允许：

- 第一次扫描 MinerU 输出，抽取各表的候选特征
- 第二次利用 LLM 对这些表对象做分类和重绑定
- 第三次在重绑定后的表上做结构化提取

这不属于作弊，因为证据全部来自同一批解析结果。

### 原则 3：JSON 为主骨架，MD 为辅助上下文

- `content_list_v2.json` 负责提供：页面、block 类型、caption、html、bbox、footnote
- `full.md` 负责提供：连续顺序、表前表后邻近文本、回填候选

但最终入库对象不能是原始 json/html/md，而应该是**融合后的结构化表对象与行级记录**。

### 原则 4：LLM 负责分类与冲突解释，不负责自由抄表

LLM 适合做：

- 表类型判断
- caption / 内容冲突解释
- 低置信度标注

LLM 不适合做：

- 在长 HTML 里自由抽取 20 多个字段并直接当最终答案

---

## 5. 推荐方案：两阶段扫描 + 约束式重绑定 + 结构化抽取

这部分是本方案的核心。

---

## 6. 阶段 0：输入统一与职责划分

### 输入

同一批 MinerU 返回内容：

- `content_list_v2.json`
- `full.md`

### 职责

#### `content_list_v2.json`
用于：

- 页面遍历
- block 类型识别
- 原始 `table_caption`
- 原始 `table_footnote`
- 原始 `html`
- `bbox`

#### `full.md`
用于：

- 提供表前后邻近文本
- 提供更自然的顺序上下文
- 在 json 某些标题缺失时提供文本补充证据

### 明确禁止

禁止把以下任一内容直接作为最终 RAG 载体：

- 原始 HTML chunk
- 原始 full.md 长段文本
- 原始 caption 文本

因为这三者都可能携带错位信息。

---

## 7. 阶段 1：第一次扫描 —— 提取 TableObject，不做最终表类型判定

### 7.1 目标

第一次扫描的目标不是“认定这是利润表/现金流量表/资产负债表”，而是**把每张表变成一个结构化的候选表对象**。

### 7.2 扫描范围

仅扫描 `content_list_v2.json` 中的 `type = table` block。

### 7.3 每张表提取的 TableObject 字段

建议输出如下结构：

```json
{
  "tableId": "p3_t2",
  "page": 3,
  "blockIndex": 5,
  "bbox": [60, 657, 475, 891],
  "rawCaption": ["会计年度", "利润表"],
  "rawFootnote": ["单位：百万元"],
  "prevTexts": ["附表", "资产负债表", "单位：百万元"],
  "nextTexts": [],
  "rawHtml": "<table>...</table>",
  "headerCandidates": ["会计年度", "2022", "2023E", "2024E", "2025E"],
  "rowLabels": [
    "归母净利润",
    "折旧和摊销",
    "资产减值准备",
    "经营活动产生现金流量",
    "现金净变动",
    "现金的期末余额"
  ],
  "columnCount": 5,
  "rowCount": 15,
  "yearColumns": ["2022", "2023E", "2024E", "2025E"],
  "mdNeighborHints": ["会计年度", "利润表", "单位：百万元"]
}
```

### 7.4 第一次扫描中必须做的轻量解析

虽然第一次扫描不做最终判表，但必须把 HTML 做最基本的矩阵化处理：

1. 解析 `<tr>`
2. 解析每行 `<td>`
3. 提取第一列行名
4. 提取第一行列头
5. 统计行数列数
6. 判断是否存在年份列
7. 提取前 N 行行名作为“表特征摘要”

### 7.5 第一次扫描阶段明确不做的事情

- 不做最终表类型判定
- 不做 caption 纠偏定论
- 不把表写入向量库
- 不生成最终 RAG chunk

因为这一步的任务只是“收集证据”。

---

## 8. 阶段 2：LLM 表分类 —— 对每张表做类型判断，而不是直接配 caption

### 8.1 为什么不是“先配 caption，再认表”

因为 caption 本身是脏的。当前案例已经证明：

- 带“利润表”字样的 caption 可能对应现金流量表内容
- 带“现金流量表”字样的 caption 可能对应资产负债表内容

所以更合理的顺序是：

1. 先根据表本身的结构与特征判断“它是什么”
2. 再判断 caption 是否与之冲突

### 8.2 LLM 的输入

不要把整份文档给 LLM。只给单表摘要，例如：

```json
{
  "tableId": "p3_t2",
  "rawCaption": ["会计年度", "利润表"],
  "prevTexts": ["附表", "资产负债表", "单位：百万元"],
  "rowLabelsTop20": [
    "归母净利润",
    "折旧和摊销",
    "资产减值准备",
    "资产处置损失",
    "公允价值变动损失",
    "财务费用",
    "投资损失",
    "少数股东损益",
    "营运资金的变动",
    "经营活动产生现金流量",
    "投资活动产生现金流量",
    "融资活动产生现金流量",
    "现金净变动",
    "现金的期初余额",
    "现金的期末余额"
  ],
  "yearColumns": ["2022", "2023E", "2024E", "2025E"],
  "candidateTypes": [
    "利润表",
    "现金流量表",
    "资产负债表",
    "财务比率表",
    "市场数据表",
    "未知"
  ]
}
```

### 8.3 LLM 的输出格式

要求严格输出 JSON：

```json
{
  "tableId": "p3_t2",
  "predictedType": "现金流量表",
  "confidence": 0.97,
  "evidence": [
    "出现经营活动产生现金流量",
    "出现投资活动产生现金流量",
    "出现融资活动产生现金流量",
    "出现现金净变动",
    "出现现金的期初余额和期末余额"
  ],
  "rawCaptionConflict": true,
  "resolvedTitle": "现金流量表",
  "notes": "rawCaption 含有 利润表，但表内特征明显属于现金流量表"
}
```

### 8.4 对 LLM 的硬约束

ClaudeCode 执行时必须遵守：

1. 只允许从 `candidateTypes` 中选择 `predictedType`
2. 必须输出 `confidence`
3. 必须输出 `evidence`
4. 必须允许输出 `未知`
5. 不允许擅自新增未定义报表类型
6. 不允许凭 caption 直接判表类型，必须引用 `rowLabels` 或 `headerCandidates` 中的特征

### 8.5 为什么这一步不是作弊

这一步没有使用人工先验标签，也没有让人工介入 PDF 判读。
它只是使用同一批 MinerU 输出中的：

- 原始 caption
- 原始 html
- 邻近文本
- 行名与列头特征

对内部冲突做语义重建。

---

## 9. 阶段 3：全局一致性约束 —— 防止 LLM 在局部正确、全局冲突

这是非常重要的一步，不能省。

### 9.1 为什么需要这一层

即使 LLM 单表分类总体靠谱，仍可能出现：

- 两张表都被分类为利润表
- 没有任何表被分类为现金流量表
- 某表低置信度但仍被硬判

因此必须有一层程序化约束来做全局消歧。

### 9.2 推荐的约束规则（注意：这是约束，不是人工先验定表）

对于同一“附表区域”或同一页的多张大财务表：

1. 若多个表都高置信命中同一类型，则：
   - 保留最高置信度者
   - 其余降级为 `UNKNOWN` 或进入二次仲裁

2. 若三大表中出现明显缺失，例如：
   - 资产负债表 / 现金流量表 / 利润表只识别出两种
   - 则对低置信表做一次补判

3. 若某表 `confidence < threshold`（如 0.70），则不直接绑定到最终标题，标记为 `UNKNOWN_TABLE`

4. 若 `rawCaption` 与 `predictedType` 冲突，则保留冲突标志，但最终以 `predictedType` 为 `resolvedTitle`

### 9.3 可选的二次仲裁

当出现冲突时，可将若干候选表汇总交给 LLM 再做一次组级判定：

输入示例：

```json
{
  "tables": [
    {"tableId": "p3_t1", "predictedType": "资产负债表", "confidence": 0.91, ...},
    {"tableId": "p3_t2", "predictedType": "现金流量表", "confidence": 0.97, ...},
    {"tableId": "p3_t3", "predictedType": "利润表", "confidence": 0.99, ...}
  ],
  "task": "请检查这组三张财务附表是否构成合理的一组主表，如有冲突请指出"
}
```

但注意：这一步是可选的，只有在冲突明显时才启用。

---

## 10. 阶段 4：第二次扫描 —— 在已重绑定的表上做结构化抽取

### 10.1 第二次扫描的前提

第二次扫描不是再次自由理解整份文档，而是：

- 仅针对已完成 `resolvedTitle` 的 TableObject
- 针对该表的 HTML 做精准结构化抽取

### 10.2 产出目标

将表转成“行级记录”而不是长文本 chunk。

推荐结构：

```json
{
  "statementType": "利润表",
  "resolvedTitle": "利润表",
  "page": 3,
  "tableId": "p3_t3",
  "year": "2022",
  "item": "营业收入",
  "value": "4432",
  "unit": "百万元",
  "confidence": 0.99,
  "source": {
    "jsonCaption": [],
    "predictedType": "利润表",
    "footnote": ["单位：百万元"]
  }
}
```

### 10.3 为什么这一层必须结构化

对于用户问题：

> 查询利润表中 22 年营业收入、营业成本、税金及附加、销售费用、管理费用、研发费用、财务费用……EPS 分别是多少

如果仍然依赖普通文本 RAG，本质上还是在让 LLM 从长 HTML 中抄表。

如果使用结构化行记录，就可以直接：

- `statementType = 利润表`
- `year = 2022`
- `item in [营业收入, 营业成本, ...]`

然后程序返回稳定结果。

### 10.4 第二次扫描应做的技术工作

1. 把 HTML 解析为二维表格
2. 提取第一行为列头（如果存在）
3. 提取第一列为行名
4. 建立 `(rowLabel, colHeader) -> value` 映射
5. 若列头缺失但已有高置信 `yearColumns`，允许回填
6. 输出规范化记录

---

## 11. 阶段 5：查询链路调整 —— 表格问答不再走普通文本 RAG

### 11.1 问题识别

当 query 命中以下模式时，应直接进入“结构化表查询链路”：

- 出现“利润表 / 现金流量表 / 资产负债表”
- 出现年份，例如 `2022`、`22年`、`2023E`
- 出现典型表项，如：
  - 营业收入
  - 营业成本
  - 利润总额
  - 所得税
  - EPS
  - 归属母公司净利润

### 11.2 新查询流程

1. 识别表类型
2. 标准化年份
3. 标准化 item 名称
4. 在结构化记录中 exact match / alias match
5. 返回结果
6. 若用户需要，再由 LLM 负责自然语言组织输出

### 11.3 不再推荐的做法

不再推荐：

- 把利润表整表切成多个文本 chunk
- 用向量检索找 chunk
- 再让 LLM 自由抽 20 多个字段

这会重现当前问题。

---

## 12. 关于 `full.md` 的定位：可用，但只能做辅助

### 12.1 `full.md` 的价值

`full.md` 可以用于：

1. 找表前表后的自然语言标题
2. 找“附表”“单位：百万元”等邻近线索
3. 回填 json 中偶发缺失的短标题
4. 给表对象增加 `mdNeighborHints`

### 12.2 `full.md` 不能承担的职责

`full.md` 不应承担：

1. 最终报表类型判定主证据
2. 最终值抽取主来源
3. 直接构建普通 RAG chunk 的主文本

原因是：

- `full.md` 与 `content_list_v2.json` 来自同一批结果
- 它会更连续地复述同一份错位
- 它不是“更正确的真相源”

### 12.3 最佳角色分工

最终建议：

- `json`：结构主源
- `md`：上下文辅助
- `LLM`：分类与冲突解释
- `程序`：全局约束、结构化抽取、最终检索

---

## 13. 对 ClaudeCode 的明确实施约束

以下是 ClaudeCode 必须遵守的规范。

### 13.1 允许做的事

1. 可以新增 `TableObject`、`TableClassificationResult`、`StructuredFinancialRow` 等数据结构
2. 可以新增 HTML 表格轻量解析器
3. 可以新增一次或两次 LLM 调用，用于表分类与冲突解释
4. 可以修改当前 MinerUService 中的表处理路径
5. 可以新增专门的财报表查询入口

### 13.2 不允许做的事

1. 不允许人工提前阅读 PDF 并写死“这个表一定是什么表”的白名单
2. 不允许把“营业收入 -> 利润表”这类人工规则作为唯一最终判定逻辑
3. 不允许直接将原始 HTML 表格 chunk 塞进向量库作为最终方案
4. 不允许继续依赖“caption 第一元素”作为表标题
5. 不允许把 `full.md` 全文当作主检索文本

### 13.3 LLM 使用限制

1. LLM 只负责：
   - 单表分类
   - 冲突解释
   - 低置信度标注
2. LLM 不负责：
   - 最终值抽取真值定义
   - 直接生成财务字段答案
3. LLM 输出必须是 JSON，且包含：
   - `predictedType`
   - `confidence`
   - `evidence`
   - `rawCaptionConflict`
   - `resolvedTitle`

### 13.4 对“未知”的要求

系统必须允许 LLM 输出 `未知`。绝不允许为了凑齐三大表而强行拍板。

---

## 14. 建议的数据结构

### 14.1 TableObject

```java
class TableObject {
    String tableId;
    int page;
    int blockIndex;
    List<String> rawCaption;
    List<String> rawFootnote;
    List<String> prevTexts;
    List<String> nextTexts;
    List<String> mdNeighborHints;
    List<String> headerCandidates;
    List<String> rowLabels;
    List<String> yearColumns;
    String rawHtml;
    int rowCount;
    int columnCount;
    List<Integer> bbox;
}
```

### 14.2 TableClassificationResult

```java
class TableClassificationResult {
    String tableId;
    String predictedType;   // 利润表 / 现金流量表 / 资产负债表 / 财务比率表 / 市场数据表 / 未知
    double confidence;
    List<String> evidence;
    boolean rawCaptionConflict;
    String resolvedTitle;
    String notes;
}
```

### 14.3 StructuredFinancialRow

```java
class StructuredFinancialRow {
    String tableId;
    int page;
    String statementType;
    String year;
    String item;
    String value;
    String unit;
    double confidence;
}
```

---

## 15. 建议的实现步骤（最小可落地版本）

建议按以下顺序实现，而不是一次性大改。

### 第 1 步：保留现有 MinerU 下载与解析入口

保留：

- `downloadAndParseZip()`
- `parseMarkdownWithStructure(contentJson, fullMd)`

不推翻现有总体流程。

### 第 2 步：在 `parseContentListV2()` 中分离“表扫描”和“文本 chunk”

当前 `parseContentListV2()` 遇到表时直接 `parseTableChunkV2()`。
建议改为：

- 先调用 `extractTableObject(...)`
- 将表对象加入 `tableObjects`
- 暂不立即生成最终表格 chunk

### 第 3 步：新增 `extractTableObject()`

功能：

- 收集原始 caption 数组
- 收集 footnote
- 提取相邻 paragraph/title
- 轻量解析 html，提取行名、列头、年份列
- 构建 TableObject

### 第 4 步：新增 `classifyTablesWithLLM(tableObjects)`

功能：

- 将每张表摘要发给 LLM
- 获取 `TableClassificationResult`

### 第 5 步：新增 `applyGlobalConsistency(results)`

功能：

- 做组级一致性约束
- 处理冲突
- 标注低置信表

### 第 6 步：新增 `extractStructuredRows(tableObject, classification)`

功能：

- 仅对已分类的财务主表做二次扫描
- 提取行级财务记录

### 第 7 步：新增财报问答专用查询链路

功能：

- 当用户问“利润表 2022 营业收入是多少”时，不再走普通文本向量检索
- 直接走结构化记录查询

---

## 16. 测试建议

### 16.1 单元测试

至少覆盖：

1. `table_caption` 多元素拼接是否完整保留
2. HTML 是否成功解析出行名与列头
3. 含年份列的表是否成功识别 `yearColumns`
4. 无 caption 表是否仍可构建 TableObject

### 16.2 集成测试

针对当前样例，验证：

1. 被错误标成“利润表”的表，最终是否重绑定为现金流量表
2. 被错误标成“现金流量表”的表，最终是否重绑定为资产负债表
3. 真正的利润表（原 caption 为空）是否最终被正确识别为利润表
4. 结构化记录中是否能准确返回：
   - 2022 营业收入 = 4432
   - 2022 营业成本 = 3597
   - 2022 税金及附加 = 13
   - 2022 EPS = 0.82

### 16.3 失败用例要求

若某表无法稳定判断，系统必须：

- 输出 `未知`
- 不强行绑定
- 不进入最终结构化问答主链路

---

## 17. 可行性分析

### 17.1 为什么这套方案可行

#### 可行性点 1：不需要更换 MinerU

当前问题不是“数值完全提不出来”，而是“caption 与表内容错配 + HTML 文本化使用方式不合适”。

既然数值已经基本存在，就说明不需要先推翻 MinerU，只需要在现有结果上做自洽重建。

#### 可行性点 2：不需要人工参与判表

通过：

- 第一次扫描提取表特征
- 第二次 LLM 分类
- 第三次全局一致性约束

已经可以替代“人工先验规则”。

#### 可行性点 3：与现有代码兼容

当前代码已经具备：

- 统一 ZIP 入口
- JSON 优先、MD 降级的流程
- 表格专门解析入口

因此本方案属于中等改造，而不是推倒重来。

### 17.2 代价

代价主要包括：

1. 新增一次或两次 LLM 调用
2. 新增 HTML 轻量解析器
3. 新增表对象与结构化行记录存储
4. 查询链路从“普通 RAG”扩展为“结构化表查询”

### 17.3 风险

1. LLM 分类仍有波动，因此必须保留 `confidence` 与 `unknown`
2. 某些极少数表可能特征不明显，无法稳定分类
3. 若 future 文档中表结构更复杂，HTML 解析器需要增强

总体判断：

**这是可行且值得实施的方案。**

---

## 18. 最终结论（给 ClaudeCode 的一句话执行摘要）

请不要继续优化“让 LLM 从原始 HTML chunk 中抄财务表”这条路线。

请改为执行以下策略：

1. `content_list_v2.json` 做主结构源，`full.md` 只做辅助上下文；
2. 第一次扫描所有 `table` block，构建 `TableObject`，只收集证据，不做最终判表；
3. 将每张表的摘要交给 LLM，要求输出受约束的 JSON 分类结果；
4. 对分类结果做全局一致性约束，允许输出 `未知`，不要强行凑表；
5. 在重绑定后的表上做第二次扫描，抽取行级结构化财务记录；
6. 财报查询走结构化表查询，不再依赖普通文本 RAG。

如果必须做最小改动版本，则优先顺序为：

1. 新增 `TableObject` 提取；
2. 新增单表 LLM 分类；
3. 新增全局一致性约束；
4. 新增结构化行记录抽取；
5. 最后再改问答链路。

