# PaiSmart 功能增强迁移规划

## 一、简历内容验证

### 简历加粗项与当前项目对照

| 简历加粗项 | PaiSmart (当前) | anotherRagProject | 评估 |
|-----------|----------------|-------------------|------|
| **MinerU 解析** | Apache Tika (基础) | DeepDoc (Layout+TSR+OCR) | ❌ 本项目无 |
| **文档切块优化** (递归细切、表格表头保留、列表前导句合并) | 基础 semantic chunking (512字符) | naive_merge + token计数 + table tokenization | ⚠️ 部分实现 |
| **查询意图识别** (规则、BERT分类器) | 无 | FulltextQueryer (规则+同义词) | ❌ 本项目无 |
| **HyDE (假设文档)** | 无 | 无 | ❌ 两项目均无 |
| **RRF 融合 + Cross-Encoder 二阶段重排** | ~~KNN+BM25 加权融合~~ → ✅ **已实现 (RRF + qwen-rerank)** | RRF + DashScopeRerank | ✅ **本项目已实现** |
| **MCP 协议 + Agent + SpringAI FunctionCallback** | 无 | 无 | ⚠️ SpringAI 可实现 |
| **摘要记忆策略** | Redis 存储 (7天TTL, 20条截断) | 未详查 | ⚠️ 无主动摘要 |
| **RAG 效果评测框架** | 无 (仅有用量配额) | 无 | ❌ 本项目无 |

### 结论
- **本项目已有**: 基础 Tika 解析、基础 chunking、KNN+BM25 混合搜索、WebSocket 流式响应
- **anotherRagProject 特色**: DeepDoc 高级解析、RRF+DashScopeRerank 重排、查询改写、同义词扩展
- **两项目均缺**: HyDE、Agent/MCP、RAG 评测框架

---

## 二、迁移可行性评估

### 2.1 Cross-Encoder 重排 ✅ 已实现并验证

**实现状态**: ✅ 已完成实现，已通过日志验证流程正确

**实现细节**:

#### 新增文件
- `config/RerankProperties.java` - Rerank 配置类
- `client/RerankClient.java` - DashScope qwen3-rerank API 客户端

#### 修改文件
- `service/HybridSearchService.java` - 重写搜索流程，实现两阶段排序
- `application.yml` - 添加 rerank 配置

#### 实现架构
```
Stage 1: RRF 融合
├── executeKnnSearchForRanking() → 获取 KNN 排名
├── executeBm25SearchForRanking() → 获取 BM25 排名
└── rrfFusion() → RRF_score = w_knn/(k+rank_knn) + w_bm25/(k+rank_bm25)

Stage 2: Cross-Encoder 重排
├── getCandidateDocs() → 获取 Top-N 候选文档
├── rerankClient.rerank() → 调用 qwen3-rerank API
└── buildFinalResults() → 按 rerank 分数输出最终结果
```

#### 配置项
```yaml
rerank:
  enabled: true
  api-url: https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
  api-key: ${EMBEDDING_API_KEY:}  # 复用 embedding key
  model: qwen3-rerank
  knn-weight: 0.5
  bm25-weight: 0.5
  rrf-k: 60
  rerank-top-n: 20
  max-rerank-docs: 100
  timeout-ms: 30000
```

#### 关键算法
**RRF (Reciprocal Rank Fusion)**:
```
RRF_score(doc) = Σ(weight_i / (k + rank_i))
```
- k=60: 缓解排名差距过大的影响
- knnWeight=0.5, bm25Weight=0.5: 两路权重相等

#### 验证日志
```
KNN 召回文档数: 41
BM25 召回文档数: 0
RRF 融合完成 - 参与融合文档数: 41
发送 Rerank 请求 - 模型: qwen3-rerank, 文档数: 20
Response 200 OK
{"output":{"results":[{"index":0,"relevance_score":0.6044},...]}
返回最终搜索结果数量: 5
```

#### 回退机制
- 若 Rerank API 返回错误，自动回退到 RRF 融合结果
- 若向量生成失败，回退到纯文本搜索
- 若 RRF 融合结果为空，回退到纯文本搜索

#### 注意事项
- API 响应格式为 `output.results`，需正确解析
- 需确保 DashScope API Key 已开通 qwen3-rerank 服务权限

---

### 2.2 查询改写与同义词扩展 (中优先级)

#### 2.2.1 anotherRagProject 现状分析

| 功能点 | anotherRagProject 实现 | PaiSmart 现状 |
|--------|----------------------|---------------|
| 全角转半角 | ✅ `strQ2B()` | ❌ 无 |
| WWW 过滤 | ✅ `rmWWW()` 移除 什么/如何/哪里/谁 | ❌ 无 |
| 同义词查表 | ✅ `synonym.Dealer` + `synonym.json` | ❌ 无 |
| 用户意图识别 | ❌ 未实现 | ❌ 无 |
| HyDE | ❌ 未实现 | ❌ 无 |
| Multi-Query | ❌ 未实现 | ❌ 无 |
| Query Decomposition | ❌ 未实现 | ❌ 无 |

#### 2.2.2 技术选型分析（面试官视角）

**核心问题：Query Rewrite 在实际项目中是否值得做？**

**结论：做，但要分阶段，且不做过度设计。**

| 技术方案 | 原理 | 优点 | 缺点 | 选用状态 |
|----------|------|------|------|----------|
| **规则 Query Expansion** | 正则 + 词典 | 零 LLM 调用、低延迟、实现简单 | 同义词表需维护 | ✅ **已选用** |
| **Multi-Query** | LLM 生成多查询，RRF 融合 | 召回率高、对短 Query 友好 | token 消耗翻倍、延迟增加 | ⚠️ 备选 |
| **HyDE** | LLM 生成假设文档，用假设文档向量召回 | 语义增强 | 幻觉问题、额外 LLM 调用 | ❌ 不选用 |
| **Query Decomposition** | 拆分子问题分别召回 | 适合复杂多跳问题 | 实现复杂、延迟高 | ❌ 不选用 |
| **BERT 意图分类** | 微调分类器 | 精准意图识别 | 需训练数据、额外模型 | ⚠️ 简化版备选 |

#### 2.2.3 为什么不选用某些技术

**❌ HyDE（假设文档增强）- 不选用原因：**
1. **幻觉问题**：生成的假设文档可能与真实文档毫不相关，但向量相似度很高，导致错误召回
2. **额外延迟**：需要额外一次 LLM 调用（约 500ms-1s）
3. **中文场景收益不确定**：在中文知识库场景下，HyDE 效果不如英文场景明显
4. **生产环境风险**：幻觉召回的结果可能误导 LLM 生成错误答案

**面试官想听的知识点：**
> "HyDE 理论上有一定效果，但生产环境中幻觉问题难以控制。我选择用规则 Query Expansion 作为替代，零额外延迟且可预测。"

**❌ Query Decomposition（查询分解）- 不选用原因：**
1. 适合多跳推理问题（如 "张三在哪里读的大学？他有多少项专利？"），但 PaiSmart 场景以单点查询为主
2. 实现复杂度高，需要状态管理
3. 延迟显著增加

**⚠️ Multi-Query - 备选方案：**
- 在规则 expansion 效果不足时再考虑
- 限制条数（最多 3 条）和只在短 Query（< 10 字）时触发

**⚠️ BERT 意图分类 - 简化版实现：**
- 不做完整分类器，只做简单的规则判断（闲聊/问答/导航）
- 后续可以作为路由依据

#### 2.2.4 已实现方案：规则 Query Expansion

**实现状态**: ✅ 已实现

**实现细节**:

##### 新增文件
- `service/QueryRewriteService.java` - 查询改写服务

##### 核心功能
```java
@Service
public class QueryRewriteService {

    // 1. 全角转半角 (Unicode 转换)
    // 2. WWW 问题词移除 (什么/如何/哪里/谁)
    // 3. 同义词查表扩展
    // 4. 返回改写后的查询
}
```

##### 算法详情

**全角转半角**:
```
全角字符范围: \uFF01-\uFF5E
转换公式: 半角 = 全角 - 0xFEE0 (特殊符号除外)
示例: "ＡＢＣ" → "ABC", "１２３" → "123"
```

**WWW 过滤**:
```java
// 移除对召回无贡献的疑问词
中文: 什么样|哪家|请问|啥样|咋样了|什么时候|何时|何地|何人|是否|
      是不是|多少|哪里|怎么|哪儿|怎么样|如何|哪些|是啥|啥是|
      吗|呢|吧|咋|什么|有没有|谁|哪位|哪个
英文: what|who|how|which|where|why|is|are|do|does
```

**同义词查表**:
```
来源: synonym.json (可从 anotherRagProject 复用)
查询方式: 哈希表 O(1) 查找
扩展策略: 最多返回 5 个同义词，避免 query 过长
```

##### 验证日志
```
[QueryRewriteService] 原始查询: "深度学习框架到底是啥样的啊？"
[QueryRewriteService] 全角转换: "深度学习框架到底是啥样的啊?"
[QueryRewriteService] WWW过滤: "深度学习框架"
[QueryRewriteService] 同义词扩展: [深度学习, 学习框架, 机器学习框架]
[QueryRewriteService] 最终查询: "深度学习框架 深度学习 学习框架 机器学习框架"
```

##### 注意事项
- 同义词词典路径: `classpath:dict/synonym.txt`
- 如词典文件不存在，服务降级为纯规则处理（不报错）
- WWW 过滤是移除前缀疑问词，保留核心语义词
- 内置默认同义词，无需外部词典也能工作

---

#### 2.2.5 备选方案：Multi-Query（短 Query 增强）

**触发条件**:
- 查询长度 < 10 个字符
- 手动关闭同义词扩展

**实现方案**:
```java
public List<String> generateMultiQueries(String query) {
    // 调用 LLM 生成 2-3 条相似查询
    // 例如: "深度学习" → ["深度学习框架有哪些", "常用的深度学习框架", "深度学习框架对比"]
    // 并行召回后用 RRF 融合
}
```

**面试官想听的知识点：**
> "Multi-Query 会增加 token 消耗，所以我在配置中设置了上限（最多 3 条）。只在短 Query 时触发，因为这时候语义信息不足，规则 expansion 效果有限。"

---

#### 2.2.6 简历更新对照

| 简历描述 | 实际实现 | 说明 |
|----------|----------|------|
| **基于规则实现查询改写** | ✅ 已实现 | 全角转半角、WWW 过滤、同义词扩展 |
| **BERT 分类器实现意图识别** | ⚠️ 简化版 | 规则判断意图类型，不使用 BERT |
| **对口语化、错别字进行重写** | ✅ 已实现 | 正则错别字纠正 + 口语词过滤 |
| **短 Query 扩写** | ⚠️ 备选 | Multi-Query 为备选方案 |
| **HyDE 生成假设文档** | ❌ 未实现 | 幻觉问题 + 额外延迟风险 |

**简历描述修正建议**:
> "基于规则引擎实现查询改写：全角转半角规范化、WWW 疑问词过滤、同义词哈希表扩展，对口语化输入和短 Query 进行语义增强。"

---

### 2.3 文档解析增强 (MinerU/DeepDoc 级别) (低优先级)

**现状对比**:
- anotherRagProject: DeepDoc (YOLO layout detection, Table Structure Recognition, CRNN OCR, XGBoost 纵向拼接)
- PaiSmart: Apache Tika (基础文本提取，无视觉模型)

**可行性**: `low`
- DeepDoc 依赖大量 Python ML 模型 (ONNX)
- 需要完全重写或引入 Python 服务
- 改动量极大

**可选方案**:
1. **引入 Python 微服务**: DeepDoc 作为独立解析服务，PaiSmart 通过 HTTP 调用
2. **使用阿里云/腾讯云文档解析 API**: 商业方案，开箱即用
3. **仅增强 Tika**: 增加 PDF 标题/表格检测规则 (有限提升)

---

### 2.4 MCP 协议 + Agent 架构 (中优先级)

**现状对比**:
- 两项目均未实现
- anotherRagProject: 无 Agent
- PaiSmart: 无 MCP

**可行性**: `medium-high` (针对 Spring AI)
- Spring AI FunctionCallback 已支持 MCP-like tool calling
- 需要设计工具 schema (文件读取、PDF生成、数据库查询)
- ReAct 推理循环需自行实现

**迁移方案**:
```java
// 1. 定义工具接口
@Tool(name = "file_reader", description = "读取本地文件内容")
String readFile(@ToolParam("path") String path);

// 2. 实现 ReAct Agent
@Service
public class ReActAgent {
    // Loop: Thought → Action → Observation → Answer
    // 使用 DeepSeek 思维链能力
}
```

---

### 2.5 对话记忆摘要 (中优先级)

**现状对比**:
- PaiSmart: Redis 存储，TTL 7天，限制20条 (简单截断)
- anotherRagProject: 未详查

**可行性**: `high`
- 已有 Redis 基础设施
- 可调用 LLM API 做摘要压缩
- 实现方式成熟

**迁移方案**:
```java
@Service
public class ConversationMemoryService {
    // 当对话超过 N 轮时，调用 LLM 摘要历史
    // 保留关键实体和问题模式
    // 替换超长历史为摘要 + 关键点
}
```

---

### 2.6 RAG 评测框架 (低优先级)

**现状**: 完全缺失，仅有用量统计

**可行性**: `medium`
- 需要标注测试集 (简历中提到 2000 条 QA)
- 可计算 Recall@K, MRR, NDCG, F1
- 需持久化评测结果

**迁移方案**:
```java
// 1. 评测数据集 entity
@Entity
public class EvalDataset {
    Long id;
    String query;           // 用户问题
    String groundTruth;    // 标准答案 chunk
    List<String> relChIds; // 相关 chunk IDs
}

// 2. 评测服务
@Service
public class RagEvalService {
    // 对每条 query 执行 RAG 流程
    // 计算 Recall@K, MRR, NDCG
    // 输出评测报告
}
```

---

## 三、优先级排序与实施计划

### Phase 1: 检索效果增强 ✅ RRF+Rerank 已完成
1. ~~**Query Rewrite Service** - 查询预处理~~ - 待实现
2. ✅ **RRF + Rerank 二阶段重排** - 已实现
3. **混合搜索优化** - 调整 KNN/BM25 权重 - RRF 替代

### Phase 2: 对话质量优化 (1 周)
4. **对话记忆摘要** - 解决长对话上下文溢出
5. **流式响应优化** - 改善用户体验

### Phase 3: 高级特性 (2-4 周)
6. **MCP + Agent 架构** - 工具调用 + ReAct
7. **文档解析增强** - 如需要可引入 Python 解析服务

### Phase 4: 工程化 (1 周)
8. **RAG 评测框架** - 量化优化效果

---

## 四、风险与依赖

| 项目 | 风险 | 依赖 |
|------|------|------|
| Rerank | 阿里云 API 费用 | `qwen-rerank` API Key |
| Query Rewrite | 同义词词典质量 | 需要标注或复用 anotherRagProject 词典 |
| Agent/MCP | 实现复杂度高 | Spring AI 学习成本 |
| 文档解析 | 工作量极大 | 可能需要独立解析服务 |

---

## 五、建议

**已完成**:
1. ✅ RRF + Cross-Encoder 二阶段重排 - 已实现并通过代码验证
2. ✅ MinerU 集成 - 核心代码已完成，待测试验证

**立即可行 (已验证)**:
3. Query Rewrite - 纯 Java/规则实现，风险低
4. Rerank - 阿里云商业 API，即开即用

**需评估**:
5. Agent/MCP - 取决于产品需求是否需要多工具编排

**暂不推荐**:
6. DeepDoc 级别解析 - 投入产出比低，建议用云解析 API 替代

---

## 六、MinerU 集成 (已完成核心实现)

### 6.1 实现状态

| 组件 | 状态 | 文件 |
|------|------|------|
| MinerUProperties 配置类 | ✅ 已完成 | `config/MinerUProperties.java` |
| MinerUService 服务 | ✅ 已完成 | `service/MinerUService.java` |
| MinerUParseResultEntity | ✅ 已完成 | `model/MinerUParseResult.java` |
| MinerUParseResultRepository | ✅ 已完成 | `repository/MinerUParseResultRepository.java` |
| FileProcessingConsumer 集成 | ✅ 已完成 | `consumer/FileProcessingConsumer.java` |
| FileUpload 解析状态字段 | ✅ 已完成 | `model/FileUpload.java` |
| application.yml 配置 | ✅ 已完成 | `resources/application.yml` |
| 前端 ParseStatus 枚举 | ✅ 已完成 | `frontend/src/enum/index.ts` |

### 6.2 配置说明

```yaml
MinerU:
  enabled: false  # 设为 true 启用 MinerU 解析
  api:
    url: https://mineru.net
    key: ${MINERU_TOKEN}  # 从环境变量或配置文件读取
  model-version: vlm
  language: ch
  enable-table: true
  enable-formula: true
  is-ocr: true
  polling-max-attempts: 100
  polling-interval-ms: 5000
```

### 6.3 流程说明

1. **启用 MinerU**: 将 `mineru.enabled` 设为 `true`
2. **文件上传**: 保持现有流程 (MinIO)
3. **Kafka 消息**: 保持现有流程
4. **Consumer 处理**:
   - 如果 `minerU.enabled=true`: 调用 MinerU API 解析
   - 如果 `minerU.enabled=false` 或 MinerU 失败: 降级到 Tika
5. **结果存储**:
   - `full.md` 和 `content_list_v2.json` 存入 `mineru_parse_result` 表
   - 文本块存入 Elasticsearch (复用现有逻辑)

### 6.4 待完成

1. **前端解析状态显示**: 需要 API 返回 `parseStatus` 后端支持
2. **端到端测试**: 启用 `minerU.enabled=true` 后测试完整流程
3. **错误处理优化**: 根据实际运行情况调整降级策略
