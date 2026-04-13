# RRF 融合与 Cross-Encoder 二阶段重排优化计划

## 一、需求分析

### 1.1 当前实现
```java
// HybridSearchService.searchWithPermission() 第 86-135 行
// 当前流程：
// 1. KNN 向量搜索 (k=topK*30)
// 2. 必须包含关键词 + 权限过滤
// 3. BM25 Rescore (queryWeight=0.2, rescoreQueryWeight=1.0)
// 4. 直接返回 topK 结果
```
**问题**: KNN 和 BM25 分数直接加权融合，非真正的排序融合；无二阶段重排。

### 1.2 目标实现
```
Stage 1: RRF 融合
├── KNN 向量搜索 → 获取排名
├── BM25 关键词搜索 → 获取排名
└── RRF score = Σ(1/(k+rank_i)) → 融合两个排名

Stage 2: Cross-Encoder 重排
├── 取 RRF Top-N 候选 (如 20 条)
├── 调用 qwen-rerank API 重排
└── 返回最终 topK 结果
```

---

## 二、所需 API

### 2.1 阿里云 DashScope Rerank API

**API 详情**:
- **服务**: qwen-rerank (或 dashscope-rerank)
- **Endpoint**: `https://dashscope.aliyuncs.com/api/v1/services/rerank/rerank`
- **Method**: POST
- **API Key**: 与 embedding 共用 `EMBEDDING_API_KEY` (DashScope 通义千问密钥)

**请求格式**:
```json
{
  "model": "qwen-rerank-2",
  "query": "用户查询",
  "documents": ["文档1内容", "文档2内容", ...],
  "return_documents": true
}
```

**响应格式**:
```json
{
  "results": [
    {"index": 0, "document": {...}, "relevance_score": 0.95},
    {"index": 2, "document": {...}, "relevance_score": 0.88},
    ...
  ]
}
```

**费用**: 按 token 计费，约 $0.02/1K tokens (以阿里云官网为准)

---

## 三、需要修改的文件

### 3.1 新增文件

| 文件 | 作用 |
|------|------|
| `config/RerankProperties.java` | Rerank API 配置类 |
| `client/RerankClient.java` | Rerank API HTTP 客户端 |

### 3.2 修改文件

| 文件 | 修改内容 |
|------|----------|
| `application.yml` | 添加 rerank 配置项 |
| `service/HybridSearchService.java` | 实现 RRF + 二阶段重排 |
| `service/RateLimitService.java` | 添加 rerank 限流 |

---

## 四、详细修改设计

### 4.1 配置类 (新增)

**文件**: `src/main/java/com/yizhaoqi/smartpai/config/RerankProperties.java`

```java
@Component
@ConfigurationProperties(prefix = "rerank")
@Data
public class RerankProperties {
    /** 是否启用 rerank */
    private boolean enabled = true;
    /** API URL */
    private String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/rerank";
    /** API Key (复用 embedding 的 key) */
    private String apiKey;
    /** Rerank 模型 */
    private String model = "qwen-rerank-2";
    /** RRF 融合时 BM25 的权重 */
    private double bm25Weight = 0.5;
    /** RRF 融合时 KNN 的权重 */
    private double knnWeight = 0.5;
    /** RRF K 参数 (通常 60) */
    private int rrfK = 60;
    /** 重排候选数量 */
    private int rerankTopN = 20;
    /** 最大单次重排文档数 */
    private int maxRerankDocs = 100;
}
```

### 4.2 Rerank 客户端 (新增)

**文件**: `src/main/java/com/yizhaoqi/smartpai/client/RerankClient.java`

```java
@Service
@Slf4j
public class RerankClient {
    private final WebClient webClient;
    private final String apiKey;
    private final String model;

    public RerankClient(RerankProperties properties,
                        @Value("${embedding.api.key}") String embeddingApiKey) {
        // apiKey 优先用 rerank 配置，否则复用 embedding key
        this.apiKey = properties.getApiKey() != null ? properties.getApiKey() : embeddingApiKey;
        this.model = properties.getModel();
        this.webClient = WebClient.builder()
                .baseUrl(properties.getApiUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    /**
     * 调用 Rerank API 重排文档
     * @param query 查询文本
     * @param documents 候选文档列表
     * @return 重排后的结果 (index, relevance_score)
     */
    public List<RerankResult> rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        // 批量处理，单次最多 100 条
        // ...
    }
}
```

### 4.3 HybridSearchService 修改

**修改点 1**: 添加 RRF 融合方法

```java
/**
 * RRF (Reciprocal Rank Fusion) 融合 KNN 和 BM25 的排名
 * RRF_score = Σ(w_i / (k + rank_i))
 *
 * @param knnResults KNN 搜索结果 (id -> score)
 * @param bm25Results BM25 搜索结果 (id -> rank)
 * @param w1 KNN 权重
 * @param w2 BM25 权重
 * @param k RRF k 参数 (通常 60)
 * @return 融合后的文档 ID 列表 (按 RRF score 降序)
 */
private List<String> rrfFusion(
        Map<String, Integer> knnRankMap,    // docId -> knn_rank (1-based)
        Map<String, Integer> bm25RankMap,   // docId -> bm25_rank (1-based)
        double w1, double w2, int k) {

    Map<String, Double> rrfScores = new HashMap<>();

    // 所有文档 ID 集合
    Set<String> allDocs = new HashSet<>();
    allDocs.addAll(knnRankMap.keySet());
    allDocs.addAll(bm25RankMap.keySet());

    for (String docId : allDocs) {
        int knnRank = knnRankMap.getOrDefault(docId, Integer.MAX_VALUE);
        int bm25Rank = bm25RankMap.getOrDefault(docId, Integer.MAX_VALUE);

        double knnRrf = knnRank == Integer.MAX_VALUE ? 0 : w1 / (k + knnRank);
        double bm25Rrf = bm25Rank == Integer.MAX_VALUE ? 0 : w2 / (k + bm25Rank);

        rrfScores.put(docId, knnRrf + bm25Rrf);
    }

    // 按 RRF score 降序排序
    return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .toList();
}
```

**修改点 2**: 修改 searchWithPermission 实现两阶段重排

```java
public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
    // ... 权限过滤部分不变 ...

    // === Stage 1: 分别执行 KNN 和 BM25，获取各自排名 ===
    // KNN 搜索 (不执行 rescore，只获取排名)
    Map<String, Integer> knnRankMap = executeKnnSearchForRanking(...);

    // BM25 搜索 (不执行 rescore，只获取排名)
    Map<String, Integer> bm25RankMap = executeBm25SearchForRanking(...);

    // RRF 融合
    List<String> fusedDocIds = rrfFusion(knnRankMap, bm25RankMap,
            rerankProperties.getKnnWeight(),
            rerankProperties.getBm25Weight(),
            rerankProperties.getRrfK());

    // === Stage 2: Cross-Encoder 重排 ===
    // 取 Top-N 候选
    int rerankTopN = rerankProperties.getRerankTopN();
    List<String> candidateDocIds = fusedDocIds.subList(0,
            Math.min(rerankTopN, fusedDocIds.size()));

    // 获取候选文档内容
    List<String> candidateContents = getDocContents(candidateDocIds);

    // 调用 Rerank API
    List<RerankResult> reranked = rerankClient.rerank(query, candidateContents);

    // 按 rerank 分数重新排序，结合 RRF 分数
    List<SearchResult> finalResults = buildFinalResults(reranked, candidateDocIds, ...);

    return finalResults.subList(0, Math.min(topK, finalResults.size()));
}
```

---

## 五、配置项 (application.yml)

```yaml
rerank:
  enabled: true
  api-url: https://dashscope.aliyuncs.com/api/v1/services/rerank/rerank
  api-key: ${EMBEDDING_API_KEY:}  # 复用 embedding key
  model: qwen-rerank-2
  # RRF 权重配置
  knn-weight: 0.5
  bm25-weight: 0.5
  rrf-k: 60
  # 重排配置
  rerank-top-n: 20
  max-rerank-docs: 100
```

---

## 六、限流配置

在 `RateLimitService` 中添加 rerank 限流:

```yaml
rate-limit:
  rerank:
    minute-max: 30          # 每分钟最多 30 次
    minute-window-seconds: 60
    day-max: 1000           # 每天最多 1000 次
    day-window-seconds: 86400
```

---

## 七、影响范围分析

### 7.1 调用链

```
ChatHandler
  └── HybridSearchService.searchWithPermission()  [修改]
        ├── executeKnnSearchForRanking()         [新增]
        ├── executeBm25SearchForRanking()        [新增]
        ├── rrfFusion()                          [新增]
        ├── RerankClient.rerank()               [新增]
        └── buildFinalResults()                  [修改]
```

### 7.2 受影响组件

| 组件 | 影响 | 原因 |
|------|------|------|
| `ChatHandler` | **无** | 接口签名不变 |
| `ElasticsearchService` | **无** | 底层 ES 操作不变 |
| `EmbeddingClient` | **无** | 仅新增 client |
| `RateLimitService` | **轻微** | 新增 rerank 限流逻辑 |
| 其他 Service | **无** | 均通过 ChatHandler 间接调用 |

### 7.3 向后兼容性

- `HybridSearchService.search()` (无权限版本) 保持不变，作为 fallback
- 若 Rerank API 调用失败，应回退到纯 RRF 结果
- 需设置 timeout，避免 rerank 超时阻塞搜索

### 7.4 性能影响

| 指标 | 影响 |
|------|------|
| 搜索延迟 | +100-300ms (RRF 多一次 ES 查询 + rerank API 调用) |
| API 费用 | rerank 按 token 计费，需监控 |
| ES 负载 | RRF 需要分别执行 KNN/BM25，轻微增加 |

---

## 八、风险与回退方案

### 8.1 风险

1. **Rerank API 不可用**: 网络问题或 API 限流
   - **缓解**: 设置 timeout，超时后回退到纯 RRF 结果

2. **API 费用超支**: 重排调用频繁
   - **缓解**: 限流 + 监控 + topN 限制

3. **LLM Token 费用**: rerank 文档内容计入 token
   - **缓解**: 截断过长文档，控制 maxRerankDocs

### 8.2 回退方案

```java
try {
    List<RerankResult> reranked = rerankClient.rerank(query, contents);
} catch (Exception e) {
    logger.warn("Rerank API 调用失败，回退到 RRF 结果: {}", e.getMessage());
    // 直接返回 RRF 排序结果
    return buildResultsFromRrfOrder(candidateDocIds, topK);
}
```

---

## 九、测试计划

### 9.1 单元测试
- `RRF_fusion_should_fuse_rankings_correctly`
- `RRF_fusion_should_handle_missing_documents`
- `RerankClient_should_parse_response_correctly`

### 9.2 集成测试
- 使用真实 ES 和 Mock Rerank API 测试完整流程
- 验证权限过滤在 RRF 阶段正常工作

### 9.3 性能测试
- 测量 RRF + Rerank 额外延迟
- 验证 100 并发下 ES 负载

---

## 十、部署 Checklist

1. 配置 `rerank.api-key` (DashScope API Key)
2. 配置 `rerank.enabled=true`
3. 观察日志确认 RRF + Rerank 流程正常
4. 监控 Rerank API 调用量和费用
5. 如有异常，设置 `rerank.enabled=false` 回退
