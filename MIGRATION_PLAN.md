# PaiSmart 功能增强技术手册

> 本文档记录 PaiSmart 项目各项功能优化的**技术演进历程、架构决策与实现细节**。

---

## 一、项目现状总览

### 1.1 技术架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              客户端 (Vue 3)                                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         后端 (Spring Boot 3.4)                              │
│                                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ UploadAPI    │  │ ChatHandler   │  │ HybridSearch │  │ MinerUService │   │
│  │ (MinIO)      │  │ (WebSocket)  │  │ (RRF+Rerank)│  │ (PDF解析)    │   │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │
│                                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ Kafka        │  │ Elasticsearch │  │ Redis        │  │ MySQL        │   │
│  │ (消息队列)    │  │ (向量+全文)   │  │ (缓存+会话)  │  │ (元数据)     │   │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 RAG Pipeline 现状

| 环节 | 原有方案 | 现有方案 | 状态 |
|------|---------|---------|------|
| **文档解析** | Apache Tika (纯文本) | MinerU (Markdown+JSON) | ✅ 已优化 |
| **文本分块** | 512字符固定切分 | V3 语义感知切分（标题/表格/列表结构化） | ✅ 已实现 |
| **文档结构识别** | 无 | MinerU content_list_v2.json 结构化识别 | ✅ 已实现 |
| **表格处理** | 无感知 | V3 表格切分（小表格整体，大表格按行+表头，**max_token=1024**） | ✅ 已实现 |
| **列表前导句** | 无 | V3 前导句+列表项合并，过长拆分保留前导句 | ✅ 已实现 |
| **查询预处理** | 无 | 规则 Query Rewrite | ✅ 已实现 |
| **向量召回** | KNN only | RRF 融合 (KNN+BM25) | ✅ 已优化 |
| **重排序** | 无 | Cross-Encoder (qwen-rerank) | ✅ 已实现 |

---

## 二、文档解析技术选型

### 2.1 技术方案对比

| 方案 | 输出格式 | 表格处理 | 图片OCR | 部署难度 | 状态 |
|------|---------|---------|--------|---------|------|
| **Apache Tika** | 纯文本 | ❌ 无结构 | ❌ 无 | 简单 | ⚠️ 备选降级 |
| **MinerU API** | Markdown + JSON | ✅ 结构化保留 | ✅ OCR | 简单 (HTTP) | ✅ 现有 |

**选型结论**：采用 MinerU API 作为主要解析方案，Tika 作为降级备选。

**决策理由**：
- Tika 输出纯文本，表格结构丢失，无法支持语义级别的表格切分
- MinerU 返回 `content_list_v2.json`，包含类型、位置、内容等结构化信息
- API 方式部署简单，无需额外部署 Python 服务或 ONNX 模型
- MinerU 失败时自动降级到 Tika，保证解析链路的可靠性

**为什么不选其他方案**：

| 方案 | 淘汰原因 |
|------|---------|
| DeepDoc (Python+ONNX) | 部署复杂，需额外部署 Python 微服务；ONNX 模型推理增加运维负担 |
| 阿里云文档解析 | 成本较高；MinerU 已满足当前需求 |
| 自研解析模型 | 成本高，周期长，当前阶段不划算 |

---

### 2.2 MinerU 集成架构

```
用户上传 PDF
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. MinIO 存储 (原有流程不变)                                                │
│    → 分片上传 → 合并 → merged/{md5}                                         │
│    → 发送 Kafka 消息                                                        │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. FileProcessingConsumer 处理                                              │
│    → 下载 MinIO 文件到本地临时目录                                           │
│    → 调用 MinerUService.uploadAndParse()                                   │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. MinerU API 调用流程                                                     │
│    ┌─────────────────────────────────────────────────────────────────┐     │
│    │ applyUploadUrl() → 申请上传链接 + batch_id                        │     │
│    │         ↓                                                         │     │
│    │ uploadFile() → PUT 上传 PDF 到 MinerU OSS (预签名 URL)             │     │
│    │         ↓                                                         │     │
│    │ waitForBatchDone() → 轮询 (最多100次, 每次5秒)                    │     │
│    │         ↓                                                         │     │
│    │ downloadAndParseZip() → 下载 ZIP → 解压 full.md/content.json      │     │
│    └─────────────────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. 结果处理                                                                 │
│    → MinerUParseResult 存入 MySQL                                          │
│    → 文本块向量化 → 存入 Elasticsearch                                       │
│    → 清理本地临时文件                                                       │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
降级: MinerU 失败 → 自动降级到 Tika
```

### 2.3 MinerU 返回文件说明

| 文件 | 内容 | 用途 |
|------|------|------|
| `full.md` | 完整 Markdown | RAG 检索文本来源 |
| `content_list_v2.json` | 结构化内容列表 | 精细分块、页码追溯 |
| `layout.json` | 布局信息 | 调试用，不存储 |
| `*_origin.pdf` | 原始 PDF | ❌ 不存储 (MinIO 已有) |

### 2.4 MinerU 配置项

```yaml
MinerU:
  enabled: true
  api-url: https://mineru.net
  api-key: ${MINERU_TOKEN:}
  model-version: vlm
  language: ch
  enable-table: true
  enable-formula: true
  is-ocr: false
  polling-max-attempts: 100
  polling-interval-ms: 5000
  timeout-ms: 30000
  temp-download-path: D:/tmp/mineru
```

### 2.5 端到端验证日志

```
[MinerU] 开始解析文件: RAG简历.pdf, 大小: 224750 bytes
[MinerU] 申请上传链接响应状态: 200
[MinerU] batch_id: 5f732568-9afc-4ae2-aa1f-d2a824104fd4
[MinerU] 文件上传成功 (HTTP 200)
[MinerU] 轮询状态: waiting-file → done
[MinerU] ZIP 下载完成: 239476 bytes
[MinerU] 提取到 full.md: 2612 bytes
[MinerU] 提取到 content_list_v2.json: 26319 bytes
[MinerU] 解析完成，共 12 个文本块
```

---

## 三、查询优化技术方案

### 3.1 RRF + Cross-Encoder 二阶段重排

#### 3.1.1 问题背景

**原有方案的问题**：
- 仅使用 KNN 向量召回，召回结果与关键词相关性弱
- 无重排序阶段，Top-K 结果质量不稳定
- BM25 单独使用效果差，未与向量召回融合

#### 3.1.2 技术方案对比

| 方案 | 原理 | 优点 | 缺点 | 状态 |
|------|------|------|------|------|
| **纯 KNN** | 向量相似度召回 | 语义理解强 | 关键词召回弱 | ❌ 原有 |
| **纯 BM25** | 关键词词频召回 | 关键词精准 | 语义理解弱 | ❌ 原有 |
| **RRF 融合** | KNN+BM25 排名加权 | 两路互补 | 需要调参 | ✅ 现有 |
| **RRF + Rerank** | 融合后 Cross-Encoder 重排 | 精准度最高 | 额外 API 费用 | ✅ 现有 |

#### 3.1.3 现有架构流程

```
用户查询
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ Stage 1: KNN 召回                                          │
│ executeKnnSearchForRanking()                               │
│ → 返回文档ID列表 + 排名 (按向量相似度降序)                    │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ Stage 2: BM25 召回                                         │
│ executeBm25SearchForRanking()                              │
│ → 返回文档ID列表 + 排名 (按 BM25 分数降序)                   │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ Stage 3: RRF 融合                                          │
│ rrfFusion(knnRankMap, bm25RankMap, knnWeight, bm25Weight) │
│ RRF_score = knnWeight/(k+rank_knn) + bm25Weight/(k+rank_bm25) │
│ → 融合后按 RRF_score 降序排列                               │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ Stage 4: Cross-Encoder 重排 (Top-20 候选)                   │
│ rerankClient.rerank(query, candidateDocs)                  │
│ → qwen3-rerank API 计算相关性分数                          │
│ → 按相关性分数重排，输出最终 Top-5                          │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
返回结果给用户
```

#### 3.1.4 关键配置

```yaml
rerank:
  enabled: true
  api-url: https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
  api-key: ${EMBEDDING_API_KEY:}
  model: qwen3-rerank
  knn-weight: 0.5
  bm25-weight: 0.5
  rrf-k: 60
  rerank-top-n: 20
  max-rerank-docs: 100
  timeout-ms: 30000
```

#### 3.1.5 验证日志

```
[HybridSearchService] 用户查询: "深度学习框架"
[HybridSearchService] KNN 召回文档数: 41
[HybridSearchService] BM25 召回文档数: 0
[HybridSearchService] RRF 融合完成 - 参与融合文档数: 41
[HybridSearchService] 发送 Rerank 请求 - 模型: qwen3-rerank, 文档数: 20
[RerankClient] Response 200 OK
[HybridSearchService] 返回最终搜索结果数量: 5
```

#### 3.1.6 回退机制

```
Rerank API 失败 → 回退到 RRF 融合结果
向量生成失败 → 回退到纯 BM25 搜索
RRF 结果为空 → 回退到纯 BM25 搜索
```

---

### 3.2 查询改写与同义词扩展

#### 3.2.1 问题背景

**原有方案的问题**：
- 用户输入全角字符无法匹配（如 "ＡＢＣ" vs "ABC"）
- 用户使用口语化疑问词影响召回（如 "深度学习是啥" 中的 "啥" 无贡献）
- 同义词无法扩展（如 "电脑" 和 "计算机" 无法互召回）

#### 3.2.2 优化方案

| 优化项 | 原有 | 优化后 | 效果 |
|--------|------|--------|------|
| 全角转半角 | ❌ 无 | ✅ 实现 | "１２３" → "123" |
| WWW 疑问词过滤 | ❌ 无 | ✅ 实现 | "啥是深度学习" → "深度学习" |
| 同义词扩展 | ❌ 无 | ✅ 实现 | "电脑" → "电脑 计算机" |
| 错别字纠正 | ❌ 无 | ⚠️ 规则实现 | 有限覆盖 |

#### 3.2.3 核心算法

**全角转半角**:
```
全角范围: \uFF01-\uFF5E (！～)
转换: 半角 = 全角 - 0xFEE0
示例: "ＡＢＣ１２３" → "ABC123"
```

**WWW 疑问词过滤** (移除前缀疑问词，保留核心语义):
```java
// 中文疑问词模式
中文: 什么样|哪家|请问|啥样|咋样了|什么时候|何时|何地|何人|是否|
      是不是|多少|哪里|怎么|哪儿|怎么样|如何|哪些|是啥|啥是|
      吗|呢|吧|咋|什么|有没有|谁|哪位|哪个

// 示例
"深度学习是啥" → 移除 "啥" → "深度学习是" → 移除 "是" → "深度学习"
"什么是机器学习" → 移除 "什么是" → "机器学习"
```

**同义词查表**:
```
来源: classpath:dict/synonym.txt (哈希表 O(1) 查找)
扩展策略: 最多 5 个同义词，避免 Query 过长
内置默认同义词，无词典也能工作
```

#### 3.2.4 验证日志

```
[QueryRewriteService] 原始查询: "深度学习框架到底是啥样的啊？"
[QueryRewriteService] 全角转换: "深度学习框架到底是啥样的啊?"
[QueryRewriteService] WWW过滤: "深度学习框架"
[QueryRewriteService] 同义词扩展: [深度学习, 学习框架, 机器学习框架]
[QueryRewriteService] 最终查询: "深度学习框架 深度学习 学习框架 机器学习框架"
```

#### 3.2.5 配置项

```yaml
query-rewrite:
  enabled: true
  synonym-path: classpath:dict/synonym.txt
  synonym-enabled: true
  synonym-max: 5
  www-filter-enabled: true
  fullwidth-normalization-enabled: true
```

#### 3.2.6 为什么不选用其他方案

| 方案 | 原因 |
|------|------|
| **HyDE** | 幻觉问题 + 额外 500ms 延迟 |
| **Multi-Query** | Token 消耗翻倍，仅备选短 Query |
| **Query Decomposition** | 适合多跳推理，场景不匹配 |
| **BERT 意图分类** | 需训练数据，简化版备选 |

---

## 四、文档切分策略 V3

### 4.1 原有方案问题分析

| 解析方式 | 切分依据 | 优点 | 缺点 |
|---------|---------|------|------|
| **Tika 解析** | 按段落 `\n\n+` | 实现简单 | 无语义边界感知 |
| **Tika 长段落** | 按句子 `[。！？；.!?;]` | 保留句子完整性 | 可能切断列表 |
| **Tika 超长句子** | HanLP 词法分析 | 中文友好 | 切分可能破坏语义 |
| **MinerU 解析** | 按 Markdown 标题 `#` | 保留文档结构 | 标题下内容仍按字符切 |

**核心问题**：
1. 表格作为普通文本处理，结构被打断
2. 列表项在任意位置被切断，语义丢失
3. 512 字符限制过小，导致 chunk 语义不完整
4. 未利用 MinerU 返回的 `content_list_v2.json` 结构化信息

### 4.2 V3 语义感知切分方案

#### 4.2.1 content_list_v2.json 结构解析

MinerU 返回的 `content_list_v2.json` 包含完整的结构化信息：

```json
{
  "content_list_v2": [
    {"type": "title", "level": 1, "content": "第3条 保险责任"},
    {"type": "table", "content": {...}, "html": "<table>...</table>"},
    {"type": "list", "lead": "以下情况不在承保范围：", "items": ["(1) 核辐射", "(2) 战争"]},
    {"type": "text", "content": "正文段落..."}
  ]
}
```

**V2 Block 类型说明**：

| type | 说明 | content 结构 |
|------|------|-------------|
| `paragraph` | 段落 | `contentObj.path("paragraph_content")` |
| `title` | 标题 | `contentObj.path("title_content")` + `level` |
| `equation_interline` | 行内公式 | `contentObj.path("equation_interline_content")` |
| `list` | 列表 | `contentObj.path("list_content")` + `lead` |
| `table` | 表格 | `contentObj.path("html")` + `table_caption` |

#### 4.2.2 V3 Pipeline 处理流程

```
原始文档
   ↓
结构识别（标题/表格/列表）← 利用 content_list_v2.json
   ↓
按 section 粗切（同一 section 内合并，跨 section 不混合）
   ↓
超长 section 递归细切（优先按子标题，否则句子级切分）
   ↓
表格专项切分（小表格整体，大表格按行+表头）
   ↓
列表专项切分（前导句+列表项合并，过长则保留前导句拆分）
   ↓
句子级兜底切分
   ↓
智能 overlap（100 token base + 扩展到最近句子边界）
   ↓
补齐 metadata（section_path, chunk_type, is_key_clause）
   ↓
输出最终 chunks
```
```
splitSectionText(text, maxTokens=1024)
    │
    ├─ text ≤ 1024？→ 直接1个chunk，return
    │
    ├─ text > 1024，有子标题（split by \n(?=#)）
    │   ├─ 子部分A → 递归调用 splitSectionText(子部分A, 1024)
    │   │             ├─ ≤1024？→ 直接1个chunk
    │   │             └─ >1024？→ 子标题有 → 再递归（最多1层）
    │   │                            └─ 子标题无 → sentenceAwareSplit（不递归）
    │   └─ 子部分B → 同上
    │
    └─ text > 1024，无子标题 → sentenceAwareSplit（直接切，不递归）

```

#### 4.2.3 核心切分规则

| 功能 | 实现方式 | chunkSize 规则 |
|------|---------|---------------|
| 标题层级识别 | JSON 中 `type: "title"` + `level` 字段 | section 内合并，最大 **1024 token** |
| 表格处理 | JSON 中 `type: "table"`, 提取 `html` + `table_caption` | **max_size = 1024 token**，小表格整体为1个chunk；大表格按行切分，每行 chunk **必须包含前2行表头** |
| 列表处理 | JSON 中 `type: "list"`, 提取前导句 + items | 前导句+列表项合并为1个chunk；**如果过长可拆分，但每个 chunk 必须保留前导句** |
| 关键条款 | 标题含"保险责任", "责任免除", "费率", "赔付"关键词 | max_size 可扩大到 **1536 token** |
| 智能 overlap | 每 chunk 末尾保留 100 token，与下一 chunk 开头重叠 | 扩展到最近句子边界（。！？；）避免截断半句 |

#### 4.2.4 关键条款识别

```java
List<String> KEY_CLAUSE_KEYWORDS = Arrays.asList(
    "保险责任", "责任免除", "费率", "赔付", "赔偿", "免责", "承保范围"
);
```

#### 4.2.5 表格切分实现

```java
// 1. 解析 HTML 表格获取行
List<String> rows = parseHtmlTable(html);

// 2. 前2行作为表头
List<String> headerRows = rows.subList(0, 2);
String headerText = String.join("\n", headerRows);

// 3. 数据行按 1024 token 限制切分，每 chunk 必须包含表头
for (int i = 2; i < rows.size(); i++) {
    if (currentTokens + estimateTokens(rows.get(i)) > 1024 && !currentRows.isEmpty()) {
        chunks.add(headerText + "\n" + String.join("\n", currentRows));
        currentRows.clear();
        currentTokens = estimateTokens(headerText);
    }
    currentRows.add(rows.get(i));
    currentTokens += estimateTokens(rows.get(i));
}
```

#### 4.2.6 列表前导句处理

```java
// 1. 识别前导句（以冒号/句号结尾的说明句）
// 2. 前导句 + 列表项 合并为1个整体 chunk
// 3. 如果整体超过 max_size，过拆分但每个 chunk 保留前导句
//    chunk1: [前导句] + (1) + (2)
//    chunk2: [前导句] + (3) + (4)
```

### 4.3 实施检查清单

| 阶段 | 任务 | 状态 |
|------|------|------|
| 阶段一 | 重写 `MinerUService.parseMarkdownWithStructure()` 使用 content_list_v2.json | ✅ 完成 |
| 阶段一 | 标题层级识别 + section path 构建 | ✅ 完成 |
| 阶段一 | 表格检测 + 按行切分 + 前2行表头复制（max_size=1024 token） | ✅ 完成 |
| 阶段一 | 列表前导句绑定（前导句+items合并，过长拆分但保留前导句） | ✅ 完成 |
| 阶段一 | 关键条款识别（关键词匹配，max_size=1536） | ✅ 完成 |
| 阶段一 | 智能 overlap（100 token + 扩展到句子边界） | ✅ 完成 |
| 阶段一 | sentence-aware split（避免句子中间截断） | ✅ 完成 |
| 阶段二 | Tika 正则结构识别 | ⏳ 待实施 |
| 阶段三 | TextChunk/EsDocument 新增字段 | ⏳ 待实施 |
| 阶段三 | prev/next chunk 串联 | ⏳ 待实施 |

---

## 五、待优化项

### 5.1 两级对话记忆架构 (中优先级)

**现状**：
- Redis 存储，TTL 7天，限制 20 条（简单截断）
- 长对话会溢出上下文窗口

**优化方案：两级记忆架构**
```
用户问问题
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 短期记忆 (Redis)                                            │
│ - 存储最近 5 轮对话（原始 JSON）                             │
│ - TTL 7 天，超出 5 轮时触发摘要逻辑                         │
│ - 查询时直接拼入上下文                                       │
└─────────────────────────────────────────────────────────────┘
    │
    ▼ 触发条件: history.size() % 5 == 0
┌─────────────────────────────────────────────────────────────┐
│ 长期记忆 (ES conversation_summaries index)                  │
│ - 早期对话摘要后存入 ES                                     │
│ - 向量+关键词混合检索                                       │
│ - 按 user_id 过滤                                           │
│ - 查询时由 query 动态唤醒                                   │
└─────────────────────────────────────────────────────────────┘
```

**摘要触发时机**：
- 当 `history.size() % 5 == 0` 时触发摘要
- 摘要内容：关键实体、问题模式、决策结论
- 摘要后删除 Redis 中的原始记录

**预期效果**：
- 上下文窗口利用率提升 50%+
- 长时间对话质量稳定
- 近期对话保留原文精确性，早期对话保留语义可检索性

---

### 5.2 RAG 评测框架 (低优先级)

**现状**：仅有用量统计，无效果评测

**优化方案**：
```java
// 1. 评测数据集
@Entity
public class EvalDataset {
    String query;           // 用户问题
    String groundTruth;     // 标准答案
    List<String> relDocIds;  // 相关文档 ID
}

// 2. 评测指标
// Recall@K, MRR@K, NDCG@K, F1@K

// 3. 评测流程
// 定期运行评测集 → 生成报告 → 持续监控优化效果
```

---

## 六、文件变更清单

### 6.1 已修改文件

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `service/HybridSearchService.java` | 重写 | RRF + Rerank 两阶段排序 |
| `service/QueryRewriteService.java` | 新增 | 查询改写服务 |
| `service/ElasticsearchService.java` | 修改 | deleteByFileMd5 返回删除数量 |
| `consumer/FileProcessingConsumer.java` | 修改 | MinerU 解析分支 + 降级逻辑 |
| `service/MinerUService.java` | 重写 | V3 语义感知切分 |
| `model/FileUpload.java` | 修改 | 添加解析状态字段 |
| `application.yml` | 修改 | MinerU/Rerank/QueryRewrite 配置 |
| `frontend/src/enum/index.ts` | 修改 | ParseStatus 枚举 |

### 6.2 新增文件

| 文件 | 说明 |
|------|------|
| `config/RerankProperties.java` | Rerank 配置类 |
| `client/RerankClient.java` | DashScope qwen-rerank API 客户端 |
| `config/MinerUProperties.java` | MinerU 配置类 |
| `model/MinerUParseResult.java` | MinerU 解析结果实体 |
| `repository/MinerUParseResultRepository.java` | MinerU 数据访问 |
| `test/MinerUApiDemo.java` | MinerU API 验证 Demo |
| `dict/synonym.txt` | 同义词词典 |

---

## 七、技术债务与风险

| 项目 | 风险 | 缓解措施 |
|------|------|---------|
| MinerU API 费用 | 按量计费，无上限 | 设置每日调用配额监控 |
| MinerU 服务不可用 | 依赖外部 API | 降级到 Tika 已实现 |
| Rerank API 费用 | qwen-rerank 按次计费 | RRF 结果可回退 |
| 同义词词典质量 | 覆盖有限 | 内置默认同义词 |
| 长对话上下文溢出 | Redis 截断丢失信息 | 待实现摘要策略 |

---

## 八、配置参考

### 8.1 环境变量

```bash
# 必须配置
export MINERU_TOKEN="your-mineru-token"
export EMBEDDING_API_KEY="your-aliyun-key"

# 可选配置
export ELASTIC_PASSWORD="PaiSmart2025"
```

### 8.2 关键端口

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL | 3306 | 元数据存储 |
| Redis | 6379 | 缓存 + 会话 |
| Kafka | 9092 | 消息队列 |
| Elasticsearch | 9200 | 向量 + 全文检索 |
| MinIO API | 19000 | 对象存储 |
| MinIO Console | 19001 | 管理界面 |
| Spring Boot | 8081 | 后端服务 |
| Vue Frontend | 9527 | 前端服务 |
