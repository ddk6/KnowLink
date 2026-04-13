# PaiSmart 功能增强技术手册

> 本文档记录 PaiSmart 项目各项功能优化的**演进历程、技术方案对比、现有架构和未来规划**。

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
| **文本分块** | 512字符固定切分 | 512字符 + 语义边界 + 句子分割 | ⚠️ 部分优化 |
| **文档结构识别** | 无 | MinerU 保留标题 | ⚠️ 待增强 |
| **表格处理** | 无感知 | MinerU 返回 JSON | ⚠️ 待增强 |
| **查询预处理** | 无 | 规则 Query Rewrite | ✅ 已实现 |
| **向量召回** | KNN only | RRF 融合 (KNN+BM25) | ✅ 已优化 |
| **重排序** | 无 | Cross-Encoder (qwen-rerank) | ✅ 已实现 |

---

## 二、已完成的优化

### 2.1 RRF + Cross-Encoder 二阶段重排 ✅

#### 2.1.1 问题背景

**原有方案的问题**：
- 仅使用 KNN 向量召回，召回结果与关键词相关性弱
- 无重排序阶段，Top-K 结果质量不稳定
- BM25 单独使用效果差，未与向量召回融合

#### 2.1.2 技术方案对比

| 方案 | 原理 | 优点 | 缺点 | 状态 |
|------|------|------|------|------|
| **纯 KNN** | 向量相似度召回 | 语义理解强 | 关键词召回弱 | ❌ 原有 |
| **纯 BM25** | 关键词词频召回 | 关键词精准 | 语义理解弱 | ❌ 原有 |
| **RRF 融合** | KNN+BM25 排名加权 | 两路互补 | 需要调参 | ✅ 现有 |
| **RRF + Rerank** | 融合后 Cross-Encoder 重排 | 精准度最高 | 额外 API 费用 | ✅ 现有 |

#### 2.1.3 现有架构流程

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

#### 2.1.4 关键配置

```yaml
rerank:
  enabled: true
  api-url: https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
  api-key: ${EMBEDDING_API_KEY:}
  model: qwen3-rerank
  knn-weight: 0.5              # KNN 召回权重
  bm25-weight: 0.5             # BM25 召回权重
  rrf-k: 60                    # RRF k 参数，缓解排名差距
  rerank-top-n: 20             # 重排候选数量
  max-rerank-docs: 100
  timeout-ms: 30000
```

#### 2.1.5 验证日志

```
[HybridSearchService] 用户查询: "深度学习框架"
[HybridSearchService] KNN 召回文档数: 41
[HybridSearchService] BM25 召回文档数: 0
[HybridSearchService] RRF 融合完成 - 参与融合文档数: 41
[HybridSearchService] 发送 Rerank 请求 - 模型: qwen3-rerank, 文档数: 20
[RerankClient] Response 200 OK
[HybridSearchService] 返回最终搜索结果数量: 5
```

#### 2.1.6 回退机制

```
Rerank API 失败 → 回退到 RRF 融合结果
向量生成失败 → 回退到纯 BM25 搜索
RRF 结果为空 → 回退到纯 BM25 搜索
```

---

### 2.2 查询改写与同义词扩展 ✅

#### 2.2.1 问题背景

**原有方案的问题**：
- 用户输入全角字符无法匹配（如 "ＡＢＣ" vs "ABC"）
- 用户使用口语化疑问词影响召回（如 "深度学习是啥" 中的 "啥" 无贡献）
- 同义词无法扩展（如 "电脑" 和 "计算机" 无法互召回）

#### 2.2.2 优化方案

| 优化项 | 原有 | 优化后 | 效果 |
|--------|------|--------|------|
| 全角转半角 | ❌ 无 | ✅ 实现 | "１２３" → "123" |
| WWW 疑问词过滤 | ❌ 无 | ✅ 实现 | "啥是深度学习" → "深度学习" |
| 同义词扩展 | ❌ 无 | ✅ 实现 | "电脑" → "电脑 计算机" |
| 错别字纠正 | ❌ 无 | ⚠️ 规则实现 | 有限覆盖 |

#### 2.2.3 核心算法

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

#### 2.2.4 验证日志

```
[QueryRewriteService] 原始查询: "深度学习框架到底是啥样的啊？"
[QueryRewriteService] 全角转换: "深度学习框架到底是啥样的啊?"
[QueryRewriteService] WWW过滤: "深度学习框架"
[QueryRewriteService] 同义词扩展: [深度学习, 学习框架, 机器学习框架]
[QueryRewriteService] 最终查询: "深度学习框架 深度学习 学习框架 机器学习框架"
```

#### 2.2.5 配置项

```yaml
query-rewrite:
  enabled: true
  synonym-path: classpath:dict/synonym.txt
  synonym-enabled: true
  synonym-max: 5
  www-filter-enabled: true
  fullwidth-normalization-enabled: true
```

#### 2.2.6 为什么不选用其他方案

| 方案 | 原因 |
|------|------|
| **HyDE** | 幻觉问题 + 额外 500ms 延迟 |
| **Multi-Query** | Token 消耗翻倍，仅备选短 Query |
| **Query Decomposition** | 适合多跳推理，场景不匹配 |
| **BERT 意图分类** | 需训练数据，简化版备选 |

---

### 2.4 文档切分策略分析 🔍

#### 2.4.1 现状分析

**PaiSmart 现有切分策略**：

| 解析方式 | 切分依据 | 优点 | 缺点 |
|---------|---------|------|------|
| **Tika 解析** | 按段落 `\n\n+` | 实现简单 | 无语义边界感知 |
| **Tika 长段落** | 按句子 `[。！？；.!?;]` | 保留句子完整性 | 可能切断列表 |
| **Tika 超长句子** | HanLP 词法分析 | 中文友好 | 切分可能破坏语义 |
| **MinerU 解析** | 按 Markdown 标题 `#` | 保留文档结构 | 标题下内容仍按字符切 |

**现有流程**：
```
文本输入
    ↓
按 \n\n+ 分割段落
    ↓
当前 chunk + 段落 ≤ 512?
    ├── 是 → 添加段落
    └── 否 → 新建 chunk
            ↓
        段落 > 512?
            ├── 是 → 按句子分割
            └── 否 → 添加到当前 chunk
                    ↓
                句子 > 512?
                    ├── 是 → HanLP 分词
                    └── 否 → 添加到 chunk
```

#### 2.4.2 对比 anotherRagProject 的 DeepDoc 切分

| 特性 | PaiSmart (现有) | anotherRagProject (DeepDoc) |
|------|-----------------|---------------------------|
| **文档结构识别** | ❌ 无 | ✅ YOLO Layout Detection |
| **标题感知** | ⚠️ MinerU 保留标题 | ✅ Markdown 标题合并 |
| **表格处理** | ❌ 无感知 | ✅ Table Structure Recognition |
| **列表前导句** | ❌ 无 | ✅ `naive_merge` 合并机制 |
| **递归细切** | ⚠️ 句子级 + 词级 | ✅ Token 级 + 细粒度合并 |
| **OCR 集成** | ❌ 无 | ✅ CRNN OCR |
| **切分单位** | 字符数 (512) | Token 数 (128) |

**DeepDoc 切分流程**：
```
PDF 输入
    ↓
Layout Analysis (YOLO)
    ↓
┌────────────────────────────────────────┐
│  文本区域    表格区域    图片区域        │
│  (Text)     (Table)    (Image)         │
└────────────────────────────────────────┘
    ↓                    ↓
Table Structure     OCR + Layout
Recognition (TSR)    Recognition
    ↓                    ↓
HTML Table         图片 + 文字
    ↓
naive_merge (按 Token 合并)
    ↓
tokenize_chunks (添加位置信息)
```

#### 2.4.3 关键差异详解

**1. 文档结构识别 (Document Structure Recognition)**

PaiSmart: 无结构感知，按固定字符数切分
```java
// ParseService.java:500
String[] paragraphs = text.split("\n\n+");
```

anotherRagProject: 识别布局结构
```python
# DeepDoc Layout Recognition
self._layouts_rec(zoomin)  # YOLO-based
self._table_transformer_job(zoomin)  # Table detection
```

**2. 递归细切 (Recursive Fine-Grained Chunking)**

PaiSmart: 句子 → 词语 两级切分
```java
// 句子分割
String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");
// 超长句子用 HanLP 分词
List<Term> termList = StandardTokenizer.segment(sentence);
```

anotherRagProject: Token 级细切 + 合并
```python
# naive_merge: 按 delimiter 分割后，按 token 数合并
# chunk_token_num=128 tokens
def naive_merge(sections, chunk_token_num=128, delimiter="\n!?。；！？"):
```

**3. 表格表头保留 (Table Header Preservation)**

PaiSmart: 无表格感知
```java
// Tika 输出纯文本，表格结构丢失
// MinerU 返回 content_list_v2.json，但切分时未特殊处理
```

anotherRagProject: 表格单独处理
```python
# tokenize_table: 按行分批处理表格
for i in range(0, len(rows), batch_size):
    r = "; ".join(rows[i:i+batch_size])  # 保留行结构
    d["content_with_weight"] = r
```

**4. 列表前导句合并 (List Leading Sentence Merging)**

PaiSmart: 无此机制
```java
// 列表项被当作普通段落，可能在任意位置切断
```

anotherRagProject: naive_merge 自动合并
```python
# 关键机制：如果 chunk 超过限制，新 chunk 会包含前一个 chunk 的位置信息
# 位置信息 (pos) 作为列表前导句被合并到下一个 chunk
if t.find(pos) < 0:
    t += pos  # 合并前导句
```

#### 2.4.4 优化建议

**方案 A: 基于 MinerU 的结构化切分 (推荐)**

现状: MinerU 已返回 `content_list_v2.json`，包含结构化信息
```json
{
  "content_list_v2": [
    {
      "type": "table",
      "content": "...",
      "bbox": {...}
    },
    {
      "type": "text",
      "content": "...",
      "heading": "相关工作"
    }
  ]
}
```

优化方向:
```java
// MinerUService.java 优化
public List<TextChunk> parseMarkdownWithStructure(String markdown, String contentJson) {
    // 1. 解析 content_list_v2.json 获取结构信息
    // 2. 按结构类型分别处理:
    //    - table: 整表作为一个 chunk 或按行拆分
    //    - text: 按标题分块，标题作为 prefix
    // 3. 确保表格不被切断
}
```

**方案 B: 增强 Tika 的表格感知**

优化方向:
```java
// 在 ParseService 中添加表格检测
private List<String> detectTables(String text) {
    // 检测 markdown 表格格式 | col1 | col2 |
    // 或 HTML 表格 <table>...</table>
    // 表格整块输出，不切分
}

// 检测列表结构
private boolean isListItem(String line) {
    // 检测 - item, 1. item, (1) item 等格式
    // 列表项与前导句合并
}
```

**方案 C: 引入 DeepDoc 级别的切分 (高成本)**

需要:
- 部署 Python 微服务
- ONNX 模型推理
- 复杂的状态管理

不推荐，除非有特殊需求（如简历解析）。

#### 2.4.5 优先级评估

| 优化项 | 难度 | 收益 | 推荐 |
|--------|------|------|------|
| **标题作为 prefix** | 低 | 中 (检索质量提升) | ✅ 推荐 |
| **表格整块保留** | 中 | 高 (表格检索质量) | ✅ 推荐 |
| **列表前导句合并** | 中 | 中 (列表语义完整) | ⚠️ 备选 |
| **Token 级切分** | 中 | 中 (切分更均匀) | ⚠️ 备选 |
| **DeepDoc 集成** | 高 | 高 (最优效果) | ❌ 暂不推荐 |

#### 2.4.6 可用配置

```yaml
# 当前配置
file:
  parsing:
    chunk-size: 512  # 字符数，不是 token 数

# 建议优化配置
file:
  parsing:
    chunk-size: 512
    # chunk-token-num: 128  # 未来可切换为 token 数
    preserve-tables: true    # 表格整块保留
    merge-list-leading: true # 列表前导句合并
    heading-as-prefix: true  # 标题作为 chunk 前缀
```

---

### 2.5 MinerU 文档解析增强 ✅

#### 2.3.1 问题背景

| 解析方式 | 输出格式 | 表格处理 | 图片OCR | 代码解析 |
|---------|---------|---------|--------|---------|
| Apache Tika | 纯文本 | ❌ 差 | ❌ 无 | ❌ 无 |
| MinerU (vlm) | Markdown + JSON | ✅ 保留结构 | ✅ OCR | ✅ 保留 |

#### 2.3.2 技术方案对比

| 方案 | 成本 | 精度 | 部署难度 | 状态 |
|------|------|------|---------|------|
| **Apache Tika** | 免费 | 基础 | 简单 | ❌ 原有 |
| **DeepDoc (Python)** | 免费 | 高 | 复杂 (需 ONNX) | ❌ 未采用 |
| **MinerU API** | 按量计费 | 高 | 简单 (HTTP API) | ✅ 现有 |
| **阿里云文档解析** | 按量计费 | 高 | 简单 | ⚠️ 备选 |

#### 2.3.3 现有架构流程

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
│    → MinerUParseResult 存入 MySQL (mineru_parse_result 表)                 │
│    → 文本块向量化 → 存入 Elasticsearch                                       │
│    → 清理本地临时文件                                                       │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
降级: MinerU 失败 → 自动降级到 Tika
```

#### 2.3.4 MinerU 返回文件说明

| 文件 | 内容 | 大小示例 | 用途 |
|------|------|---------|------|
| `full.md` | 完整 Markdown | ~3KB | RAG 检索文本来源 |
| `content_list_v2.json` | 结构化内容列表 | ~26KB | 精细分块、页码追溯 |
| `layout.json` | 布局信息 | ~82KB | 调试用，不存储 |
| `*_origin.pdf` | 原始 PDF | ~224KB | ❌ 不存储 (MinIO 已有) |

#### 2.3.5 配置项

```yaml
MinerU:
  enabled: true                                         # 启用 MinerU
  api-url: https://mineru.net
  api-key: eyJ0eXBl...                                # Token
  model-version: vlm
  language: ch
  enable-table: true                                   # 表格识别
  enable-formula: true                                 # 公式识别
  is-ocr: true                                         # 图片 OCR
  polling-max-attempts: 100
  polling-interval-ms: 5000
  timeout-ms: 30000
  temp-download-path: D:/tmp/mineru                    # Windows 临时目录
```

#### 2.3.6 端到端验证日志

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

#### 2.3.7 注意事项

1. **YAML 配置结构**：必须使用扁平结构 `api-url`/`api-key`
2. **Windows 路径**：`temp-download-path` 使用 `D:/tmp/mineru`
3. **降级机制**：MinerU 失败时自动降级到 Tika
4. **文件清理**：解析完成后删除本地临时文件

---

## 四、待优化项

### 4.1 对话记忆摘要 (中优先级)

**现状**：
- Redis 存储，TTL 7天，限制 20 条（简单截断）
- 长对话会溢出上下文窗口

**优化方案**：
```java
@Service
public class ConversationMemoryService {
    // 对话超过 N 轮时，调用 LLM 摘要历史
    // 保留关键实体、问题模式、决策结论
    // 替换超长历史为: 摘要 + 关键点列表
}
```

**预期效果**：
- 上下文窗口利用率提升 50%
- 长时间对话质量稳定

---

### 4.2 MCP 协议 + Agent 架构 (中优先级)

**现状**：
- 仅有单轮问答能力
- 无法调用外部工具（查天气、读文件等）

**优化方案**：
```java
// 1. 定义工具接口
@Tool(name = "file_reader", description = "读取本地文件内容")
String readFile(@ToolParam("path") String path);

@Tool(name = "search_web", description = "搜索网页内容")
String searchWeb(@ToolParam("query") String query);

// 2. 实现 ReAct Agent
@Service
public class ReActAgent {
    // Loop: Thought → Action → Observation → Answer
    // 使用 DeepSeek 思维链能力
}
```

---

### 4.3 RAG 评测框架 (低优先级)

**现状**：仅有用量统计，无效果评测

**优化方案**：
```java
// 1. 评测数据集
@Entity
public class EvalDataset {
    String query;           // 用户问题
    String groundTruth;     // 标准答案
    List<String> relDocIds; // 相关文档 ID
}

// 2. 评测指标
// Recall@K, MRR@K, NDCG@K, F1@K

// 3. 评测流程
// 定期运行评测集 → 生成报告 → 持续监控优化效果
```

---

## 五、文件变更清单

### 5.1 已修改文件

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `service/HybridSearchService.java` | 重写 | RRF + Rerank 两阶段排序 |
| `service/QueryRewriteService.java` | 新增 | 查询改写服务 |
| `service/ElasticsearchService.java` | 修改 | deleteByFileMd5 返回删除数量 |
| `consumer/FileProcessingConsumer.java` | 修改 | MinerU 解析分支 + 降级逻辑 |
| `model/FileUpload.java` | 修改 | 添加解析状态字段 |
| `application.yml` | 修改 | MinerU/Rerank/QueryRewrite 配置 |
| `frontend/src/enum/index.ts` | 修改 | ParseStatus 枚举 |

### 5.2 新增文件

| 文件 | 说明 |
|------|------|
| `config/RerankProperties.java` | Rerank 配置类 |
| `client/RerankClient.java` | DashScope qwen-rerank API 客户端 |
| `config/MinerUProperties.java` | MinerU 配置类 |
| `service/MinerUService.java` | MinerU API 服务 |
| `model/MinerUParseResult.java` | MinerU 解析结果实体 |
| `repository/MinerUParseResultRepository.java` | MinerU 数据访问 |
| `test/MinerUApiDemo.java` | MinerU API 验证 Demo |
| `dict/synonym.txt` | 同义词词典 |

---

## 六、技术债务与风险

| 项目 | 风险 | 缓解措施 |
|------|------|---------|
| MinerU API 费用 | 按量计费，无上限 | 设置每日调用配额监控 |
| MinerU 服务不可用 | 依赖外部 API | 降级到 Tika 已实现 |
| Rerank API 费用 | qwen-rerank 按次计费 | RRF 结果可回退 |
| 同义词词典质量 | 覆盖有限 | 内置默认同义词 |
| 长对话上下文溢出 | Redis 截断丢失信息 | 待实现摘要策略 |

---

## 七、配置参考

### 7.1 环境变量

```bash
# 必须配置
export MINERU_TOKEN="your-mineru-token"
export EMBEDDING_API_KEY="your-aliyun-key"  # 通义千问 API Key

# 可选配置
export ELASTIC_PASSWORD="PaiSmart2025"
```

### 7.2 关键端口

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
