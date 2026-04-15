# RAGAS 自动化评估闭环 — 技术方案

## 文档概述

本文档描述如何在 PaiSmart 项目中构建一套基于 RAGAS 的自动化 RAG 评估闭环，实现检索质量可量化、回答质量可追溯、证据链完整可查。整个方案不修改现有 Java 业务逻辑，以新增表、新增 Service、新增 Python 独立服务的方式实现。

---

## 一、整体架构

### 1.1 架构全貌

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              PaiSmart Java 应用                               │
│                                                                              │
│  WebSocket /chat/{token}                                                      │
│       ↓                                                                       │
│  ChatHandler.processMessage()                                                 │
│       ├── 1. 获取对话历史（Redis）                                             │
│       ├── 2. 检索长期记忆（ES: conversation_long_term_memory）                  │
│       ├── 3. HybridSearchService.searchWithPermission()  ←── RAG 核心检索      │
│       │       ├── QueryRewriteService（查询改写）                              │
│       │       ├── EmbeddingClient（查询向量化）                                 │
│       │       ├── ES KNN 搜索 + BM25 搜索                                     │
│       │       ├── RRF 融合（两路排名合并）                                      │
│       │       └── RerankClient（Cross-Encoder 重排）                           │
│       ├── 4. buildContext()（组装 prompt）                                     │
│       ├── 5. LlmProviderRouter.streamResponse()（LLM 生成）                   │
│       ├── 6. 流式响应发送至前端                                                │
│       ├── 7. finalizeResponse()（存入 Redis 对话历史）                          │
│       │        ↓ 异步触发                                                       │
│       └── [新增] QueryLogService.saveAsync() ────────────────────────────────┼
│                                         │                                    │
└─────────────────────────────────────────│────────────────────────────────────┘
                                          │ HTTP POST /api/evaluation/evaluate
                                          │ (异步，不阻塞用户响应)
                                          ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Python RAGAS 评估服务（独立进程）                        │
│                                                                              │
│  FastAPI/Flask 服务                                                           │
│       ├── /api/evaluation/evaluate     ←── 接收 Java 侧的评估请求              │
│       │    POST body: { query_id, raw_query, rewritten_query,                │
│       │                  retrieved_chunks_json, response_text }              │
│       │                                                                         │
│       ├── RAGASEvaluator                                                         │
│       │    ├── faithfulnessJudge()    ←── 判断回答中每个陈述是否有证据支撑       │
│       │    ├── answerRelevancyJudge()  ←── LLM 判断回答对问题的相关度评分        │
│       │    ├── contextPrecisionJudge() ←── 逐 chunk 判断其对问题的贡献度        │
│       │    └── contextRecallApprox()   ←── LLM 自我评估缺失了哪些信息            │
│       │                                                                         │
│       └── MetricsWriter                                                       │
│            写入 MySQL rag_evaluation_result 表                                 │
│                                                                              │
│  定时任务（每日凌晨）                                                           │
│       └── BatchEvaluator                                                       │
│            批量评估昨日抽样数据（约 5-10%），降低 API 成本                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                          │
                                          │ 查询评估结果
                                          ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Grafana 监控看板                                   │
│                                                                              │
│  Prometheus Metrics（Python 服务暴露）                                         │
│       ├── rag_evaluation_faithfulness{org_tag="..."}                         │
│       ├── rag_evaluation_answer_relevancy{org_tag="..."}                     │
│       ├── rag_evaluation_context_precision{org_tag="..."}                     │
│       ├── rag_evaluation_context_recall_approx{org_tag="..."}                 │
│       └── rag_evaluation_query_total{org_tag="..."}                           │
│                                                                              │
│  看板面板：                                                                     │
│       ├── RAGAS 四项指标日/周趋势                                               │
│       ├── 检索召回率 = 0 的高频查询（知识库覆盖死角）                             │
│       ├── Faithfulness 低分率（幻觉风险）                                        │
│       └── 可追溯查询日志（输入 query_id 查看证据链）                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 数据流向总览

```
用户提问
    ↓
[Java] ChatHandler
    ↓
[Java] HybridSearchService
    │   查询改写 → query_vector → ES KNN ─┐
    │         └── ES BM25 ────────────────┼→ RRF 融合 ─→ Cross-Encoder 重排
    ↓                                        │
[Java] LlmProviderRouter                      │
    │   构建 prompt（含检索到的 chunk）        │
    ↓                                        │
[LLM API] DeepSeek/Ollama                      │
    ↓                                        │
[Java] 流式响应拼装 ───────────────────────────┘
    │
    ├──→ [Java] finalizeResponse() → Redis 对话历史
    │                                      │
    │    [新增] QueryLogService.saveAsync()   │
    │         生成 UUID 作为 query_id          │
    │         异步 HTTP POST 到 Python 服务     │
    │                                          ↓
    └──→ [Python] RAGASEvaluator.compute()     │
              四个 judge 方法并行/串行调用 LLM   │
              ↓                                  │
         [Python] 写入 MySQL rag_evaluation_result
```

---

## 二、现有 RAG 流程详解（Java 侧）

了解评估数据从哪里来，需要先清楚现有 RAG 流程每一步产出了什么。

### 2.1 ChatHandler.processMessage() — 主处理流程

```java
public void processMessage(String userId, String userMessage, WebSocketSession session) {
    // 1. 限流检查
    rateLimitService.checkChatByUser(userId);

    // 2. 获取或创建会话 ID（Redis key: user:{userId}:current_conversation）
    String conversationId = getOrCreateConversationId(userId);

    // 3. 获取对话历史（Redis List: conversation:{conversationId}，最多 10 条消息）
    List<Map<String, String>> history = getConversationHistory(conversationId);

    // 4. 检索长期记忆（ES: conversation_long_term_memory）
    List<LongTermMemory> longTermMemories = conversationMemoryService.retrieveMemories(...);
    String longTermMemoryContext = buildLongTermMemoryContext(longTermMemories);

    // 5. 混合搜索（核心检索）
    List<SearchResult> searchResults = searchService.searchWithPermission(userMessage, userId, 5);

    // 6. 构建上下文（将检索结果格式化为 prompt 中的引用块）
    String context = buildContext(searchResults, session.getId(), userMessage);

    // 7. 合并长期记忆
    if (!longTermMemoryContext.isEmpty()) {
        context = longTermMemoryContext + "\n\n" + context;
    }

    // 8. 调用 LLM 生成流式响应
    llmProviderRouter.streamResponse(userId, userMessage, context, history,
        chunkCallback, errorCallback);

    // 9. 后台监控响应完成 → finalizeResponse()
}
```

### 2.2 HybridSearchService.searchWithPermission() — 两阶段检索

这是 RAG 的检索核心，经历两个阶段：

#### Stage 1：RRF 融合（Recall 阶段）

**第一步：查询改写（QueryRewriteService）**

输入用户原始 query，经过四步处理得到 `rewrittenQuery`：
1. **全角转半角**：`！（）→ !()`
2. **去除特殊字符**：保留中文/英文/数字/空格
3. **去除 WWW 疑问词**：去除句首/句尾的"请问/能否/是否可以"等语气词
4. **同义词扩展**：查 `dict/synonym.txt` 同义词表，O(1) 哈希查找，最多扩展 5 个词

**第二步：查询向量化（EmbeddingClient）**

用 DashScope `text-embedding-v4` 将 `rewrittenQuery` 转为 2048 维向量。

**第三步：KNN 向量搜索**

```java
// ES knowledge_base 索引，knn 模式
knn: {
    field: "vector",          // 2048 维向量字段
    queryVector: [0.123, ...],
    k: 150,                   // recallK = topK * 30 = 5 * 30
    numCandidates: 150
}
// 加上权限过滤：userId = 当前用户 OR isPublic = true OR orgTag 匹配
```

返回 `docId → rank`（1-based rank，rank 越小说明越相关）。

**第四步：BM25 文本搜索**

```java
// ES match 查询，operator = AND（所有词都要命中）
query: { match: { textContent: rewrittenQuery, operator: AND } }
// 同样加上权限过滤
```

同样返回 `docId → rank`。

**第五步：RRF 融合**

```
RRF_score(doc) = knnWeight / (k + knnRank) + bm25Weight / (k + bm25Rank)
k = 60（RRF 平滑参数）
knnWeight = 0.5, bm25Weight = 0.5（默认配置）
```

对所有同时出现在 KNN 和 BM25 结果中的文档，计算 RRF 分数，按降序排列。RRF 的核心思想：**同时被两路召回的文档排名靠前，单路召回但排名很靠前的文档也有机会靠前**。

取 Top 20（`rerankTopN`），送入 Stage 2。

#### Stage 2：Cross-Encoder 重排（Rerank）

**调用 DashScope qwen3-rerank API**：

```
输入：query + 20 个候选 chunk 原文
输出：按相关性降序排列的 20 个 chunk + 得分
```

Cross-Encoder 的原理是将 query 和 document 一起输入到一个专门训练过的 Transformer 模型中，联合计算相关性分数，比 KNN + BM25 的双通道独立打分更精准。

最终取 Top 5 返回给 ChatHandler。

### 2.3 ChatHandler.buildContext() — Prompt 组装

将 SearchResult 列表格式化为注入 LLM 的上下文块：

```java
// 格式示例：
[1] (合同模板.pdf | 第3页) 第一条 甲方将其合法拥有的知识产权（包括但不限于专利、商标...
[2] (用户协议.docx | 第5页) 2.1 乙方保证其向甲方提供的信息真实、完整、有效...
```

`MAX_CONTEXT_SNIPPET_LEN = 300`，超过 300 字符的 chunk 被截断。

每个编号 `[N]` 对应一个 `ReferenceInfo`，包含：
- `fileMd5` / `fileName` / `pageNumber`：定位到具体文件的具体页
- `anchorText`：该 chunk 的前 120 字符（用于引用展示）
- `matchedChunkText`：完整原始 chunk 文本（最长 800 字符）
- `retrievalMode`：RERANK / TEXT_ONLY / HYBRID
- `score`：rerank 分数

### 2.4 LlmProviderRouter.streamResponse() — LLM 生成

构建最终 prompt 并调用 LLM：

```
System:
你是派聪明知识助手，须遵守：
1. 仅用简体中文作答。
2. 回答需先给结论，再给论据。
3. 如引用参考信息，请在句末加 (来源#编号: 文件名)。
4. 若知识库中无足够信息，就依据自己的知识来回答...
<<REF>>
[1] (合同模板.pdf | 第3页) 第一条 甲方将其合法拥有...
[2] (用户协议.docx | 第5页) 2.1 乙方保证其向甲方...
<<END>>

[对话历史...]

User: {当前问题}
```

流式响应通过 WebSocket 分块发送至前端。

### 2.5 finalizeResponse() — 流响应完成

```java
private void finalizeResponse(...) {
    String completeResponse = responseBuilder.toString();  // 完整响应字符串

    // 存入 Redis 对话历史（带时间戳）
    updateConversationHistory(conversationId, userId, userMessage, completeResponse, refMapping);

    // [新增] 异步触发评估流程
    queryLogService.saveAsync(...);  // 不阻塞，不等待
}
```

---

## 三、评估数据埋点（Java 新增）

### 3.1 为什么要埋点

现有流程中，**原始 query / 检索结果 / LLM 回答** 这三者只存在于内存和 Redis 中：
- 会话结束后，`sessionReferenceMappings`（内存 Map）即被清除
- Redis 中只存了对话消息 JSON，原始 SearchResult 列表未单独存储
- 无法做离线评估、趋势分析、证据追溯

因此需要新增 `query_log` 表，将评估所需的三元组持久化。

### 3.2 新增表结构

#### query_log — 每次 RAG 查询的完整记录

```sql
CREATE TABLE query_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    query_id VARCHAR(36) NOT NULL UNIQUE COMMENT 'UUID，每轮查询唯一标识',
    user_id VARCHAR(64) NOT NULL COMMENT '用户 ID',
    org_tag VARCHAR(50) COMMENT '用户主组织标签（用于多租户统计）',
    conversation_id VARCHAR(36) COMMENT '所属会话 ID',

    -- 查询阶段数据
    raw_query TEXT NOT NULL COMMENT '用户原始输入',
    rewritten_query TEXT COMMENT 'QueryRewriteService 改写后的查询',

    -- 检索阶段数据（完整 JSON，存原始 SearchResult，不是 prompt 里的截断版本）
    retrieved_chunks_json LONGTEXT NOT NULL COMMENT 'List<SearchResult> 完整 JSON',

    -- 送入 LLM 的上下文（记录 prompt 中实际使用的截断版本，便于对比分析）
    context_text TEXT COMMENT 'buildContext() 生成的上下文文本（含截断）',
    chunk_count INT COMMENT '检索到的 chunk 数量',

    -- 生成阶段数据
    response_text LONGTEXT COMMENT 'LLM 完整回答（流响应拼装后的完整字符串）',
    model_version VARCHAR(128) COMMENT '使用的 LLM 模型版本',

    -- 性能数据
    total_tokens INT COMMENT '总 token 消耗（prompt + completion）',
    latency_ms INT COMMENT '端到端延迟（从接收到响应完成）',
    retrieval_mode VARCHAR(20) COMMENT 'HYBRID / TEXT_ONLY / KNN_ONLY',
    rerank_applied BOOLEAN DEFAULT TRUE COMMENT '是否使用了 Cross-Encoder 重排',

    -- 时间戳
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_user_id (user_id),
    INDEX idx_org_tag (org_tag),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **存储说明**：`retrieved_chunks_json` 存的是完整的 `SearchResult` JSON（原始 chunk 文本，未被截断），而不是 prompt 中传给 LLM 的截断版本。这样无论评估时分析哪个版本，都能追溯到完整的证据来源。

#### rag_evaluation_result — RAGAS 评估结果

```sql
CREATE TABLE rag_evaluation_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    query_id VARCHAR(36) NOT NULL,

    -- RAGAS 四大核心指标
    faithfulness DECIMAL(5,4) COMMENT '答案忠实度：0.0000~1.0000',
    answer_relevancy DECIMAL(5,4) COMMENT '答案相关性：0.0000~1.0000',
    context_precision DECIMAL(5,4) COMMENT '检索精确度：0.0000~1.0000',
    context_recall_approx DECIMAL(5,4) COMMENT '检索召回率（近似）：0.0000~1.0000',

    -- 评估元数据
    evaluation_model VARCHAR(128) COMMENT '执行 judge 的模型（如 deepseek-chat）',
    evaluation_prompt_tokens INT COMMENT '评估 prompt 消耗 token 数',
    evaluation_completion_tokens INT COMMENT '评估 completion 消耗 token 数',

    -- 详细评估过程（JSON，便于人工复核和错误分析）
    evaluation_detail_json LONGTEXT COMMENT '详细评估过程 JSON，含每个 judge 的中间输出',

    computed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_query (query_id),
    INDEX idx_computed_at (computed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### retrieval_feedback — 用户显式反馈

```sql
CREATE TABLE retrieval_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    query_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(64),
    vote TINYINT COMMENT '1=正向反馈(thumbs up), -1=负向反馈(thumbs down)',
    feedback_type VARCHAR(20) COMMENT 'RETRIEVAL / ANSWER_QUALITY / RELEVANCE',
    comment TEXT COMMENT '用户可选的文本反馈',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_query (query_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.3 QueryLogService — 数据写入逻辑

```java
@Service
public class QueryLogService {

    private final RabbitTemplate rabbitTemplate;  // 或 WebClient / RestTemplate

    @Async  // 异步执行，不阻塞 finalizeResponse
    public void saveAsync(QueryLog queryLog) {
        // 1. 写入 MySQL query_log 表（同步）
        queryLogRepository.save(queryLog);

        // 2. 发送消息触发 RAGAS 评估（异步，不等待结果）
        rabbitTemplate.convertAndSend("evaluation.exchange", "evaluation.trigger",
                Map.of("queryId", queryLog.getQueryId(), "priority", 1));
    }
}
```

触发评估通过 RabbitMQ（项目中已有 Kafka/RabbitMQ 基础设施）解耦：
- 评估服务消费 `evaluation.trigger` 消息
- Java 侧不关心评估何时完成
- 评估服务可独立部署、扩缩容

---

## 四、Python RAGAS 评估服务

### 4.1 技术选型

- **框架**：FastAPI（比 Flask 更适合需要并发 judge 调用的场景）
- **RAGAS 版本**：>= 0.1.0（本文档基于 0.1.x API 设计）
- **LLM 调用**：RAGAS 内置的 DeepSeek / DashScope 集成，或直接用 LangChain / custom client
- **部署方式**：Docker 容器，与 Java 应用共用 `docker-compose.yaml`

### 4.2 项目结构

```
ragas_evaluator/
├── app/
│   ├── main.py                    # FastAPI 入口
│   ├── api/
│   │   └── routes/
│   │       └── evaluation.py      # /api/evaluation/evaluate 接口
│   ├── services/
│   │   ├── evaluator.py           # RAGASEvaluator 核心类
│   │   ├── judges/
│   │   │   ├── faithfulness.py    # Faithfulness judge
│   │   │   ├── answer_relevancy.py
│   │   │   ├── context_precision.py
│   │   │   └── context_recall.py
│   │   └── metrics_writer.py     # 写入 MySQL
│   ├── models/
│   │   └── schemas.py             # Pydantic 请求/响应模型
│   └── config.py                  # LLM API 配置
├── dict/
│   └── synonym.txt                # 复用 Java 侧同义词表（可选）
├── tests/
│   └── test_judges.py
├── Dockerfile
├── requirements.txt
└── docker-compose.yaml
```

### 4.3 FastAPI 接口设计

#### POST /api/evaluation/evaluate

**请求体**（由 Java 侧 QueryLogService 发送）：

```json
{
  "queryId": "550e8400-e29b-41d4-a716-446655440000",
  "rawQuery": "合同最长期限是多久？",
  "rewrittenQuery": "合同期限最长期限",
  "retrievedChunks": [
    {
      "chunkId": 3,
      "fileMd5": "a1b2c3d4e5f6...",
      "fileName": "劳动合同模板.docx",
      "pageNumber": 5,
      "textContent": "第十五条 劳动合同期限分为固定期限、无固定期限和以完成一定工作任务为期限三种。固定期限劳动合同最长不超过三年。",
      "anchorText": "第十五条 劳动合同期限分为...",
      "retrievalMode": "RERANK",
      "score": 0.92
    },
    {
      "chunkId": 7,
      "fileMd5": "b2c3d4e5f6a7...",
      "fileName": "民法典合同编.pdf",
      "pageNumber": 12,
      "textContent": "第四百六十四条 合同是民事主体之间设立、变更、终止民事法律关系的协议。",
      "anchorText": "第四百六十四条 合同是民事主体...",
      "retrievalMode": "RERANK",
      "score": 0.85
    }
  ],
  "responseText": "根据知识库中的《劳动合同模板》第十五条规定，劳动合同期限分为固定期限、无固定期限和以完成一定工作任务为期限三种，其中固定期限劳动合同最长不超过三年。（来源#1: 劳动合同模板.docx）",
  "modelVersion": "deepseek-chat"
}
```

**响应体**（立即返回 202 Accepted）：

```json
{
  "queryId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "queued",
  "estimatedCompletionSeconds": 30
}
```

**评估计算在后台异步完成**，不阻塞 HTTP 响应。

#### GET /api/evaluation/result/{queryId}

查看某次评估的完整结果：

```json
{
  "queryId": "550e8400-e29b-41d4-a716-446655440000",
  "faithfulness": 0.875,
  "answerRelevancy": 0.920,
  "contextPrecision": 0.950,
  "contextRecallApprox": 0.800,
  "evaluationDetail": {
    "faithfulness": {
      "claims": [
        {"text": "劳动合同期限分为固定期限、无固定期限和以完成一定工作任务为期限三种", "supported": true, "evidenceChunkIds": [3]},
        {"text": "固定期限劳动合同最长不超过三年", "supported": true, "evidenceChunkIds": [3]},
        {"text": "该规定来源于《劳动合同模板》第十五条", "supported": true, "evidenceChunkIds": [3]}
      ],
      "faithfulnessScore": 0.875
    },
    "answerRelevancy": {
      "score": 0.920,
      "reasoning": "回答直接回应了合同最长期限的问题，给出了具体期限数值并注明了来源"
    },
    "contextPrecision": {
      "chunkPrecisions": [
        {"chunkId": 3, "contributionScore": 0.95, "reasoning": "直接包含答案所需的核心法条"},
        {"chunkId": 7, "contributionScore": 0.30, "reasoning": "仅为合同定义，与答案相关性较低"}
      ],
      "avgPrecision": 0.625
    },
    "contextRecallApprox": {
      "score": 0.800,
      "missingInfo": ["未说明无固定期限劳动合同的适用条件"],
      "reasoning": "当前 chunk 基本覆盖了答案所需信息，但未涵盖无固定期限的触发条件"
    }
  }
}
```

### 4.4 四个 Judge 的实现详解

#### 4.4.1 Faithfulness Judge

**目标**：检测回答中是否存在幻觉（hallucination）—— 有多少陈述能被检索到的证据支撑。

**RAGAS 原生方式**：RAGAS 内置了 `FaithfulnessEvaluator`，背后用 LLM 判断。

**prompt 工程实现**（如果不用 RAGAS 内置）：

```
系统提示：
你是一个答案质量评估专家。你的任务是从【回答】中提取所有事实性陈述，
并判断每一个陈述是否能从【上下文】（检索到的文档片段）中找到证据支撑。

判断标准：
- SUPPORTED：陈述的内容可以从上下文中直接推断或验证
- NOT_SUPPORTED：陈述的内容与上下文矛盾，或上下文根本没有提供相关信息
- PARTIALLY_SUPPORTED：陈述的核心内容有支撑，但有部分细节无法从上下文验证

请以 JSON 格式输出：
{
  "claims": [
    {"statement": "陈述1原文", "verdict": "SUPPORTED|NOT_SUPPORTED|PARTIALLY_SUPPORTED", "evidence": "引用上下文中的相关句子，没有则填''"},
    {"statement": "陈述2原文", "verdict": "...", "evidence": "..."}
  ],
  "faithfulnessScore": 有支撑的陈述数 / 总陈述数（保留4位小数）
}

回答只需输出 JSON，不要有其他文字。
```

Python 调用示例（使用 RAGAS 内置）：

```python
from ragas import EvaluationResult
from ragas.metrics import Faithfulness

faithfulness = Faithfulness(llm=deepseek_chat_model)
result = await faithfulness.singleターン_turn_output(
    response=response_text,
    contexts=[chunk["textContent"] for chunk in retrieved_chunks]
)
```

#### 4.4.2 Answer Relevancy Judge

**目标**：判断回答是否真正回答了问题（而非答非所问或过于笼统）。

**Prompt**：

```
系统提示：
请评估【回答】对【问题】的解决程度，按以下标准打分 0.0~1.0：

评分标准：
- 1.0：回答完全切题，直接解决了问题，信息量适中
- 0.7-0.9：回答基本切题，但有少量无关信息或信息不完整
- 0.4-0.6：回答部分切题，存在答非所问或过于笼统的情况
- 0.1-0.3：回答大部分不切题，与问题关联很弱
- 0.0：回答完全不切题

判断依据：
1. 回答是否针对问题中所有关键点？
2. 回答是否包含问题范围之外的内容？
3. 回答的信息粒度是否与问题匹配？

请以 JSON 格式输出：
{
  "score": 分数（0.0~1.0，保留4位小数）,
  "reasoning": "判断理由，不超过100字",
  "keyPointsCovered": ["问题中的关键点1", "关键点2"],
  "keyPointsMissing": ["未覆盖的关键点（如有）"]
}

回答只需输出 JSON。
```

#### 4.4.3 Context Precision Judge

**目标**：检索回来的 chunk 中，有多少比例对回答问题真正有贡献。

**逐 chunk 判断 Prompt**：

```
系统提示：
【问题】：{user_query}

【待评估的 Chunk】：{chunk_text}

请判断该 chunk 对回答上述问题的贡献程度。

评分标准（0.0~1.0）：
- 1.0：该 chunk 包含直接可用于回答问题的关键信息
- 0.7：该 chunk 提供了重要背景信息，对理解答案有帮助
- 0.4：该 chunk 与问题相关，但信息过于间接或重复
- 0.1：该 chunk 几乎不包含对回答问题有用的信息
- 0.0：该 chunk 与问题完全无关

请以 JSON 输出：
{
  "chunkId": {i},
  "score": 分数（0.0~1.0）,
  "reasoning": "判断理由，不超过50字"
}
```

**最终 precision**：`所有 chunk 的贡献度分数之和 / chunk 数量`（等权平均）。也可以用 rerank score 做加权平均。

#### 4.4.4 Context Recall Approx Judge

**目标**：在没有 ground truth 的情况下，用 LLM 自我评估"回答所需信息是否都被检索到了"。

**Prompt**：

```
系统提示：
【问题】：{user_query}

【LLM 作答】：{response_text}

【已检索到的文档片段】：
{retrieved_chunks_text}

请分析：要完整回答上述问题，LLM 的作答是否涵盖了所有必要信息？
请列出：
1. 作答中已有的关键信息（这些信息在【已检索文档】中有对应来源）
2. 作答中缺失的重要信息（如果这些信息存在于【已检索文档】但未被使用，也算缺失）
3. 如果要更完整回答问题，还需要检索哪些信息？

请以 JSON 输出：
{
  "score": 缺失信息的重要程度修正后的召回率估计（0.0~1.0）,
  "coveredInfo": ["已覆盖的关键信息列表"],
  "missingInfo": ["缺失的重要信息列表"],
  "additionalQueries": ["如果要补全缺失信息，建议补充检索的查询词"]
}

注意：0.0 表示完全缺失必要信息；1.0 表示所有必要信息均已覆盖。
回答只需输出 JSON。
```

> **说明**：这是一个近似值（Approximate），因为 LLM 的自我评估本身不完全可靠。真正的 Context Recall 需要人工标注的 ground truth answer 才能精确计算。Approx 版本的价值在于**趋势追踪**——如果某类问题的 recall 持续偏低，说明知识库或检索策略存在系统性问题。

### 4.5 定时批量评估

实时评估每个 query 都有 4 次额外 LLM 调用，成本较高。更经济的做法：

```python
# app/scheduler/batch_evaluator.py

APPROXIMATE_CRON = "0 2 * * *"  # 每天凌晨 2 点

@celery_app.task
def batch_evaluate_yesterday():
    """
    1. 从 query_log 查出昨日所有记录
    2. 抽样 5-10%（优先抽：检索 chunk_count=0 / latency_ms > 30s / 用户负反馈）
    3. 并行触发 RAGASEvaluator.compute()
    4. 结果写入 rag_evaluation_result
    """
    pass
```

| 查询类型 | 抽样权重 | 原因 |
|---------|---------|------|
| `chunk_count = 0`（检索为空） | 100% | 知识库覆盖死角，高优先级 |
| `latency_ms > 30000` | 100% | 性能异常，高优先级 |
| 用户负反馈（vote = -1） | 100% | 直接反映质量问题 |
| 普通查询 | 5-10% 随机抽样 | 统计代表性 |

### 4.6 Dockerfile 和部署

```dockerfile
# ragas_evaluator/Dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY app/ ./app/

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

```yaml
# ragas_evaluator/docker-compose.yaml（在项目 docs/docker-compose.yaml 中新增服务）
services:
  ragas-evaluator:
    build: ../ragas_evaluator
    container_name: pai-ragas-evaluator
    ports:
      - "8001:8000"
    environment:
      - DATABASE_URL=mysql+pymysql://user:pass@mysql:3306/smartpai
      - DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}
      - DEEPSEEK_BASE_URL=${DEEPSEEK_BASE_URL}
    depends_on:
      mysql:
        condition: service_healthy
    networks:
      - pai-network
```

```text
# ragas_evaluator/requirements.txt
fastapi>=0.109.0
uvicorn[standard]>=0.27.0
ragas>=0.1.0
langchain-deepseek>=0.1.0
pymysql>=1.1.0
sqlalchemy>=2.0.0
pydantic>=2.0.0
celery>=5.3.0
redis>=5.0.0
httpx>=0.26.0
python-json-logger>=2.0.0
```

---

## 五、证据引用与可追溯分析

### 5.1 证据链的完整路径

```
用户提问
    ↓
[query_id = UUID]  ←── 作为整条证据链的根节点
    ↓
检索到的每个 chunk
    ├── fileMd5 ──────────────────→ file_upload 表（原始文件元数据）
    │                                 └── file_name, file_md5, user_id, org_tag
    ├── MinerU 解析记录
    │                                 └── mineru_parse_result 表
    │                                     ├── content_json（原始解析 JSON）
    │                                     └── layout_json（布局信息）
    └── ES knowledge_base 索引
                                      ├── textContent（完整 chunk 原文）
                                      ├── sectionPath（章节路径）
                                      ├── pageNumber（PDF 页码）
                                      └── anchorText（前 120 字符）
    ↓
LLM 回答中的每个引用 [N]
    ├── 引用编号 → sessionReferenceMappings（Redis 中已序列化）
    │                └── ReferenceInfo: {fileMd5, pageNumber, anchorText}
    ↓
RAGAS Faithfulness 评估
    ├── 逐陈述判断 SUPPORTED / NOT_SUPPORTED
    └── 关联到具体 chunkId（如 SUPPORTED → chunkId=3）
```

### 5.2 EvidenceTracerService — 追溯 API

Java 侧新增一个 `EvidenceTracerService`，提供证据链还原能力：

```java
@Service
public class EvidenceTracerService {

    @Autowired private QueryLogRepository queryLogRepository;
    @Autowired private MineruParseResultRepository mineruParseResultRepository;
    @Autowired private DocumentVectorRepository documentVectorRepository;

    /**
     * 给定 query_id 和引用编号，还原完整的证据链
     * 返回：该引用的 chunk 原文 + 对应的 MinerU 原始解析片段 + 文件信息
     */
    public EvidenceChain trace(String queryId, int citationRef) {
        QueryLog queryLog = queryLogRepository.findByQueryId(queryId)
            .orElseThrow(() -> new NotFoundException("query_id not found"));

        // 1. 从 query_log 的 reference_map 查出该引用对应的 chunk
        SearchResult chunk = findChunkInRetrieved(queryLog, citationRef);

        // 2. 追溯到文件上传记录
        FileUpload file = fileUploadRepository.findByFileMd5(chunk.getFileMd5())
            .orElse(null);

        // 3. 追溯到 MinerU 原始解析 JSON（完整的 content_list_v2.json）
        MineruParseResult mineru = mineruParseResultRepository.findByFileMd5(chunk.getFileMd5())
            .orElse(null);

        // 4. 定位该 chunk 在 MinerU JSON 中的原始位置（page index + block index）
        OriginalBlockLocation location = locateOriginalBlock(mineru, chunk);

        return new EvidenceChain(
            citationRef,
            chunk.getTextContent(),          // ES 中的 chunk 原文
            chunk.getPageNumber(),           // PDF 页码
            chunk.getAnchorText(),           // 锚点文本
            chunk.getScore(),               // rerank 分数
            chunk.getRetrievalMode(),       // 召回路径
            file != null ? file.getFileName() : null,
            mineru != null ? mineru.getFullMd() : null,  // 原始 markdown 文本
            location                         // MinerU JSON 中的精确位置
        );
    }

    /**
     * 生成一份完整的评估报告（供 Admin 人工复核）
     */
    public EvaluationReport generateReport(String queryId) {
        QueryLog queryLog = queryLogRepository.findByQueryId(queryId);
        RAGEvaluationResult eval = evaluationResultRepository.findByQueryId(queryId);
        List<EvidenceChain> evidenceChains = new ArrayList<>();
        for (int i = 1; i <= queryLog.getChunkCount(); i++) {
            evidenceChains.add(trace(queryId, i));
        }
        return new EvaluationReport(queryLog, eval, evidenceChains);
    }
}

@Data
public class EvidenceChain {
    private int citationRef;            // [1], [2]...
    private String chunkText;          // ES knowledge_base 中的完整 chunk 原文
    private Integer pdfPageNumber;      // PDF 页码
    private String anchorText;          // 前 120 字符锚点
    private Double rerankScore;         // rerank 分数
    private String retrievalMode;       // RERANK / TEXT_ONLY
    private String fileName;            // 原始文件名
    private String fullMarkdownText;    // MinerU 解析的完整 markdown 原文
    private OriginalBlockLocation originalBlockLocation;  // MinerU JSON 中的位置
}

@Data
public class OriginalBlockLocation {
    private int pageIndex;   // 在 content_list_v2.json 的 pages[] 数组中的下标
    private int blockIndex;  // 在 pages[pageIndex].blocks[] 数组中的下标
    private String blockType; // title / paragraph / table / list / equation_interline
    private String blockContent; // 该 block 原始的 content 字段
}
```

### 5.3 引用与 MinerU 原始解析的对应

以 MinerU `content_list_v2.json` 为例：

```json
{
  "pages": [
    {
      "page_index": 4,
      "blocks": [
        {
          "type": "paragraph",
          "id": "p-15",
          "content": {
            "text": "第十五条 劳动合同期限分为固定期限、无固定期限和以完成一定工作任务为期限三种。固定期限劳动合同最长不超过三年。"
          }
        }
      ]
    }
  ]
}
```

假设 ES `knowledge_base` 中某个 chunk 的：
- `pageNumber = 5`（Elasticsearch 1-based）
- `chunkId = 3`（文件内 0-based）

`EvidenceTracerService` 通过：
1. `pageNumber - 1 = 4` → 定位到 `pages[4]`（MinerU JSON 0-based）
2. 遍历该 page 的 blocks，找到与 ES chunk 的 `textContent` 完全匹配的 block
3. 还原为 MinerU 原始的 `type` + `content.text`

这样就能回答"**这个回答引用了 MinerU 解析结果中的哪个 block？**"

---

## 六、Grafana 看板接入

### 6.1 Prometheus Metrics 暴露

Python 服务通过 `prometheus_client` 暴露指标：

```python
from prometheus_client import Counter, Histogram, Gauge

# 指标定义
QUERY_TOTAL = Counter(
    "rag_evaluation_query_total",
    "Total RAG evaluation requests",
    ["org_tag", "retrieval_mode"]
)

FAITHFULNESS = Histogram(
    "rag_evaluation_faithfulness",
    "Faithfulness score distribution",
    ["org_tag"],
    buckets=[0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0]
)

ANSWER_RELEVANCY = Histogram(...)
CONTEXT_PRECISION = Histogram(...)
CONTEXT_RECALL_APPROX = Histogram(...)

LLM_LATENCY = Histogram(
    "rag_evaluation_llm_latency_seconds",
    "LLM judge call latency",
    ["judge_name"]
)
```

### 6.2 Grafana 看板 Panel 设计

| Panel 名称 | 类型 | 查询 | 用途 |
|-----------|------|------|------|
| RAGAS 四项指标日趋势 | Time series | `rag_evaluation_faithfulness{org_tag="..."}` | 追踪指标随时间的变化 |
| Faithfulness 低分率（< 0.5） | Stat | `sum(rag_evaluation_faithfulness_bucket{le="0.5"}) / sum(rag_evaluation_faithfulness_count)` | 幻觉风险告警 |
| 检索为空占比 | Stat | `sum(rag_evaluation_query_total{chunk_count="0"}) / sum(rag_evaluation_query_total)` | 知识库覆盖率 |
| 高延迟查询 TOP 10 | Table | `topk(10, rag_evaluation_llm_latency_seconds_bucket)` | 慢查询分析 |
| 用户负反馈趋势 | Time series | `sum(retrieval_feedback{vote="-1"}) by (date)` | 用户满意度趋势 |
| 引用可追溯查询 | Table | 直接查询 MySQL `query_log JOIN rag_evaluation_result` | 人工复核入口 |

---

## 七、工作流程完整演示

以一次具体对话为例，走完整个评估闭环：

```
用户（WebSocket）: "合同最长可以签多久？"
```

**Step 1：Java ChatHandler 接收处理**

- `conversationId = "conv-abc123"`
- `HybridSearchService.searchWithPermission("合同最长可以签多久？", userId, 5)`
  - `QueryRewriteService.rewrite()` → `"合同期限 最长"`
  - Embedding → query_vector (2048-dim)
  - ES KNN (k=150) → 150 个 docId + rank
  - ES BM25 (AND operator) → 150 个 docId + rank
  - RRF 融合（k=60, weight=0.5/0.5）→ Top 20 docIds
  - `RerankClient.rerank()` → Top 5 + rerank scores
- `buildContext()` → `<<REF>>\n[1] (劳动合同.docx | 第5页) 第十五条...\n[2] (民法典.pdf | 第12页) 第四百六十四条...\n<<END>>`
- `LlmProviderRouter.streamResponse()` → DeepSeek API
- 流式响应拼装：`"根据知识库《劳动合同》第十五条规定，固定期限劳动合同最长不超过三年。（来源#1: 劳动合同.docx）"`

**Step 2：Java finalizeResponse()**

- 存入 Redis `conversation:conv-abc123`（user message + assistant response）
- `queryLogService.saveAsync()`：
  1. 生成 `queryId = "uuid-550e8400"`
  2. 写入 MySQL `query_log`（包含 5 个完整 SearchResult JSON）
  3. 发送 RabbitMQ 消息 `evaluation.trigger`（含 queryId）

**Step 3：Python RAGAS 评估服务消费消息**

- Celery worker 接收 `evaluation.trigger`
- 读取 MySQL `query_log` 中的完整记录
- 并行执行 4 个 judge（各自调用 DeepSeek LLM API）：
  - `FaithfulnessJudge`：判断回答中 3 个陈述 → 2 个 SUPPORTED，1 个 PARTIALLY → `0.8333`
  - `AnswerRelevancyJudge`：直接回答了合同最长期限 → `0.9000`
  - `ContextPrecisionJudge`：Chunk1 贡献 0.95，Chunk2 贡献 0.30 → `0.6250`
  - `ContextRecallApproxJudge`：提到"最长三年"但未说明"无固定期限"触发条件 → `0.7500`
- 写入 MySQL `rag_evaluation_result`
- 更新 Prometheus 指标

**Step 4：EvidenceTracer 可追溯查询**

- 管理员在 Grafana 看到某次查询的 Faithfulness = 0.45（低分）
- 点击进入详情页，输入 `query_id`
- `EvidenceTracerService.trace(queryId, 2)`：
  - Chunk 2 = "第四百六十四条 合同是民事主体之间设立..."
  - 对应 MinerU `content_list_v2.json` pages[11] blocks[2]
  - 发现 LLM 引用了合同定义而非合同期限条款
  - 说明检索策略有问题：BM25 "合同期限" 没有命中 Chunk 1，但命中了 Chunk 2
  - **根因**：Chunk 1 的 `textContent` 以"第十五条"开头，BM25 AND 模式要求所有词都命中，"合同"+"期限"+"最长"三个词中，"最长"在 Chunk 1 中出现位置靠后，BM25 权重不够

**Step 5：优化动作**

- 根因分析指向 BM25 AND 模式过于严格，漏掉了 Chunk 1
- 调整 `HybridSearchService`：对查询改写后的词数 ≤ 3 的情况，改用 OR 模式
- 重新评估同一批 query，Context Precision 从 0.62 提升至 0.81

---

## 八、技术挑战与已知限制

| 挑战 | 影响 | 缓解方案 |
|------|------|---------|
| **评估 LLM 成本** | 每个 query 额外 ~4000-8000 token（L4 judge 调用） | 定时批量评估 + 5% 抽样；实时评估仅对高优先级 query（检索为空/用户负反馈） |
| **Ground Truth 缺失 → Context Recall 不精确** | Approx 版本存在 LLM 自我评估偏差 | 积累一批人工标注的 gold QA pairs 做校准；重点关注趋势而非单次绝对值 |
| **Chunk 原文截断导致引用失真** | prompt 中最多 300 字符，LLM 可能基于不完整信息判断 | `query_log` 存完整原始 chunk，不依赖 prompt 截断版本；`EvidenceTracerService` 追溯时用完整原文 |
| **Python 服务需要维护额外基础设施** | 独立进程 + Docker + Celery worker | 与 Java 服务共用同一 docker-compose network；Celery broker 用项目中已有的 Redis |
| **多租户数据隔离** | RAGAS 指标涉及用户查询内容 | Admin 看板按 orgTag 隔离；Python 服务对 query_log 只有只读权限 |
| **RAGAS 版本升级风险** | 0.1.x API 可能与未来版本不兼容 | pip freeze requirements.txt；升级前在测试环境验证 judge prompt 输出格式 |

---

## 九、文件变更清单

### 新增文件

| 文件路径 | 说明 |
|---------|------|
| `ragas_evaluator/` 目录 | Python RAGAS 评估服务根目录 |
| `ragas_evaluator/app/main.py` | FastAPI 入口 |
| `ragas_evaluator/app/api/routes/evaluation.py` | 评估接口路由 |
| `ragas_evaluator/app/services/evaluator.py` | 评估器核心 |
| `ragas_evaluator/app/services/judges/*.py` | 四个 judge 实现 |
| `ragas_evaluator/app/services/metrics_writer.py` | MySQL 结果写入 |
| `ragas_evaluator/app/models/schemas.py` | Pydantic 数据模型 |
| `ragas_evaluator/requirements.txt` | Python 依赖 |
| `ragas_evaluator/Dockerfile` | Docker 镜像构建 |
| `ragas_evaluator/docker-compose.yaml` | 服务编排 |

### Java 侧新增

| 文件路径 | 说明 |
|---------|------|
| `src/main/java/.../entity/QueryLog.java` | query_log 表实体 |
| `src/main/java/.../entity/RAGEvaluationResult.java` | rag_evaluation_result 表实体 |
| `src/main/java/.../entity/RetrievalFeedback.java` | retrieval_feedback 表实体 |
| `src/main/java/.../repository/QueryLogRepository.java` | query_log DAO |
| `src/main/java/.../repository/RAGEvaluationResultRepository.java` | 评估结果 DAO |
| `src/main/java/.../service/QueryLogService.java` | 数据埋点写入服务 |
| `src/main/java/.../service/EvidenceTracerService.java` | 证据链追溯服务 |
| `src/main/java/.../controller/EvaluationAdminController.java` | Admin API（评估看板数据接口） |

### Java 侧修改

| 文件 | 修改内容 |
|------|---------|
| `ChatHandler.java` | `finalizeResponse()` 中新增 `queryLogService.saveAsync()` 异步调用 |
| `application.yml` | 新增 `ragas.evaluator.base-url` 配置（Python 服务地址） |
| `docs/docker-compose.yaml` | 新增 `ragas-evaluator` 服务 + MySQL 健康检查依赖 |
| `docs/databases/ddl.sql` | 新增 3 张表（query_log / rag_evaluation_result / retrieval_feedback） |

### 配置文件

| 文件 | 修改内容 |
|------|---------|
| `application.yml` | 新增 `ragas.evaluator.enabled=true`、`ragas.evaluator.base-url=http://localhost:8001` |
| `application-dev.yml` | 开发环境指向本地 Python 服务 |
| `application-prod.yml` | 生产环境指向 Docker 容器内服务名 |
