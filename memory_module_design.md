# RAG 记忆模块设计（Redis 短期记忆 + ES 长期记忆）

---

## A. 项目现状与可行性分析

### A.1 当前实现状态

通过代码审查，当前项目记忆模块实现如下（`ChatHandler.java`）：

| 维度 | 当前实现 | 设计文档要求 | 差距 |
|------|---------|------------|------|
| **存储位置** | Redis String (JSON) | Redis List/Stream | 较小 |
| **窗口控制** | 固定 20 条消息截断 | 按轮次或 token 控制 | **较大** |
| **短期记忆内容** | 原始消息 (role/content/timestamp) | 原始消息 + token_estimate | 需补充字段 |
| **长期记忆** | ❌ 无 | ES 独立索引 + hybrid search | **完全缺失** |
| **记忆类型分类** | ❌ 无 | memory_type 枚举 (task/preference/fact 等) | **完全缺失** |
| **重要性/置信度** | ❌ 无 | importance + confidence 字段 | **完全缺失** |
| **检索时 query 扩展** | ❌ 无 (只用 userMessage) | 短期记忆 + query 构造 retrieval query | **完全缺失** |
| **记忆筛选/过滤** | ❌ 无 (全量截断) | 候选抽取 + 价值判断 | **完全缺失** |
| **记忆合并/更新** | ❌ 无 | 相似记忆合并 + 版本更新 | **完全缺失** |
| **记忆过期/衰减** | ❌ 无 | TTL + is_active + importance 衰减 | **完全缺失** |

**当前架构简图**：

```
用户 query → ChatHandler
                ↓
         getConversationHistory()  ← Redis: conversation:{id}, 20条截断
                ↓
         buildMessages() 直接拼入 history
                ↓
         LlmProviderRouter.streamResponse()
```

### A.2 可行性分析

| 方面 | 评估 | 说明 |
|------|------|------|
| **Redis 基础** | ✅ 可行 | 项目已有 RedisTemplate，复用 `conversation:{id}` 结构即可 |
| **ES 基础设施** | ✅ 可行 | 项目已有 ElasticsearchService，SSS 索引存在 |
| **代码侵入性** | ⚠️ 中等 | 需修改 `ChatHandler.getConversationHistory()` 和 `LlmProviderRouter.buildMessages()` |
| **新增 Service** | ✅ 可行 | 可新建 `ConversationMemoryService` 隔离复杂度 |
| **LLM 调用成本** | ⚠️ 需控制 | 记忆摘要需额外 LLM 调用，需设计触发条件避免频繁调用 |
| **Embedding 服务** | ✅ 可行 | 项目已有 DashScope embedding 集成，长期记忆向量存储可行 |

### A.3 关键风险

1. **Token 成本**：摘要阶段需要额外 LLM 调用，需严格控制触发频率
2. **检索质量**：短期记忆扩展 query 的效果依赖扩展质量，需要 prompt tuning
3. **去重合并**：相似记忆合并需要额外 LLM 调用或向量相似度计算
4. **向后兼容**：当前 20 条截断策略需要平滑迁移

---

## B. 分阶段实施计划

### Phase 0: 短期记忆增强（1 周）

**目标**：在不改长期记忆架构的前提下，提升短期记忆质量。

#### B.0.1 改用 Redis List 替代 String

**当前**：`conversation:{id}` → String (JSON 数组)

**改为**：`conversation:{id}` → Redis List，每条消息单独 RPUSH

**好处**：
- `LRANGE` 取最近 N 条天然支持
- `LTRIM` 窗口控制 O(1)
- 后续扩展到 Stream 更平滑

#### B.0.2 窗口控制从”消息数”改为”轮次数”

**当前**：20 条消息截断

**改为**：保留最近 **5 轮** user-assistant 对（10 条消息）

**优势**：
- 轮次语义比消息数更稳定
- 5 轮足以覆盖”继续刚才那个”类场景
- 比按 token 控制更简单（第一阶段）

#### B.0.3 增加 token_estimate 字段

每条消息增加 `token_estimate` 字段，为后续按 token 控制打基础：

```json
{
  “role”: “user”,
  “content”: “...”,
  “timestamp”: “2026-04-14T10:00:00”,
  “token_estimate”: 45
}
```

#### B.0.4 阶段性验收标准

- [ ] Redis List 迁移完成，原 String 数据兼容读取
- [ ] 窗口固定为 5 轮（10 条消息）
- [ ] 每条消息带 token_estimate
- [ ] 单元测试覆盖

---

### Phase 1: 长期记忆基础设施（1 周）

**目标**：建立 ES 长期记忆索引，不开启写入。

#### B.1.1 新建 ES 索引 `conversation_long_term_memory`

Mapping 设计：

```json
{
  “mappings”: {
    “properties”: {
      “memory_id”: { “type”: “keyword” },
      “user_id”: { “type”: “keyword” },
      “session_id”: { “type”: “keyword” },
      “memory_type”: { “type”: “keyword” },
      “summary”: { “type”: “text”, “analyzer”: “ik_max_word” },
      “details”: { “type”: “text”, “analyzer”: “ik_max_word” },
      “entities”: { “type”: “keyword” },
      “keywords”: { “type”: “keyword” },
      “importance”: { “type”: “float” },
      “confidence”: { “type”: “float” },
      “created_at”: { “type”: “date” },
      “updated_at”: { “type”: “date” },
      “last_used_at”: { “type”: “date” },
      “source_message_ids”: { “type”: “keyword” },
      “is_active”: { “type”: “boolean” },
      “ttl_days”: { “type”: “integer” },
      “summary_embedding”: { “type”: “dense_vector”, “dims”: 1536, “index”: true, “similarity”: “cosine” }
    }
  }
}
```

#### B.1.2 新建 `ConversationMemoryService`

```java
@Service
public class ConversationMemoryService {

    // 写入相关
    void consolidateMemory(String userId, String conversationId, List<Message> overflowMessages);

    // 检索相关
    List<LongTermMemory> retrieveMemories(String userId, String query, List<Message> shortTermMessages);

    // 辅助
    String buildRetrievalQuery(String query, List<Message> shortTermMessages);
    String summarizeForMemory(List<Message> messages);
}
```

#### B.1.3 阶段性验收标准

- [ ] ES 索引创建成功，mapping 正确
- [ ] `ConversationMemoryService` 骨架代码完成
- [ ] 可以调用 LLM 做摘要测试
- [ ] `summary_embedding` 写入/检索流程跑通

---

### Phase 2: 记忆写入流程（1 周）

**目标**：实现触发式记忆 consolidation，不启用检索。

#### B.2.1 触发条件

当 Redis 窗口从 5 轮扩展到 6 轮时（即即将截断最旧 1 轮时），触发 consolidation：

```java
// 每次 updateConversationHistory 后检查
if (history.size() > 10) {  // 5轮 x 2
    // 触发 consolidation，把最旧的 2 条消息（1轮）做摘要尝试
}
```

#### B.2.2 候选抽取 Prompt

```text
你是记忆分析专家。从以下对话片段中抽取值得长期保留的信息。

对话片段：
{ messages }

请判断：
1. 这段对话包含什么类型的信息？（task/preference/fact/episode/constraint）
2. 有什么值得未来复用的事实或结论？
3. 重要性打分（0-1）和置信度打分（0-1）
4. 如果不值得保存，返回空。

输出JSON格式：
{
  “should_store”: true/false,
  “memory_type”: “task|preference|fact|episode|constraint”,
  “summary”: “一句话总结”,
  “details”: “详细补充（可选）”,
  “entities”: [“实体1”, “实体2”],
  “keywords”: [“关键词1”, “关键词2”],
  “importance”: 0.85,
  “confidence”: 0.90
}
```

#### B.2.3 写入 ES

- 生成 `memory_id` (UUID)
- 写入 `summary_embedding`（调用 embedding API）
- `is_active = true`，`ttl_days = 180`

#### B.2.4 阶段性验收标准

- [ ] 溢出触发 consolidation
- [ ] LLM 摘要质量可接受
- [ ] ES 写入成功
- [ ] 日志记录 memory_id 和 summary

---

### Phase 3: 记忆检索流程（1 周）

**目标**：在 ChatHandler 查询时，携带长期记忆。

#### B.3.1 修改 `ChatHandler.processMessage()`

```java
// 1. 读取 Redis 短期记忆（现有逻辑）
List<Map<String, Object>> shortTermMessages = getConversationHistoryRecords(conversationId);

// 2. 检索长期记忆（新增）
List<LongTermMemory> longTermMemories = memoryService.retrieveMemories(
    userId,
    userMessage,
    shortTermMessages
);

// 3. 构造 retrieval query（新增）
String retrievalQuery = memoryService.buildRetrievalQuery(userMessage, shortTermMessages);
logger.info(“长期记忆检索 query: {}”, retrievalQuery);

// 4. 把长期记忆加入 context
String memoryContext = buildMemoryContext(longTermMemories);
```

#### B.3.2 修改 `LlmProviderRouter.buildMessages()`

在 system prompt 中增加长期记忆 section：

```
[长期记忆]
{memoryContext}

请基于以上信息回答当前问题。如果长期记忆与当前上下文冲突，以当前上下文和用户最新指令为准。
```

#### B.3.3 Hybrid 检索实现

复用现有 `HybridSearchService` 的 RRF 逻辑，但针对 long_term_memory 索引：

```java
// 1. BM25 召回
bm25Results = esClient.search()
    .index(“conversation_long_term_memory”)
    .query(QueryBuilders.multiMatch())
    .filter(f -> f.term(“user_id”, userId))
    .filter(f -> f.term(“is_active”, true));

// 2. Vector 召回
vectorResults = esClient.search()
    .index(“conversation_long_term_memory”)
    .scriptScore(s -> s.query(q -> q.term(“user_id”, userId)))
    .source(“summary_embedding”, embeddingService.getEmbedding(retrievalQuery));

// 3. RRF 融合 + filter by memory_type
// 4. Top-5 返回
```

#### B.3.4 阶段性验收标准

- [ ] 检索 query 构造日志正确
- [ ] ES 召回结果数量合理
- [ ] 长期记忆正确拼入 system prompt
- [ ] 对”继续刚才那个”类短 query，召回结果有帮助

---

### Phase 4: 去重与生命周期（0.5 周）

**目标**：防止重复记忆积累，支持过期清理。

#### B.4.1 写入前去重检查

```java
// 在 consolidation 写入前，先查相似记忆
List<LongTermMemory> similar = esClient.search()
    .index(“conversation_long_term_memory”)
    .query(q -> q.scriptScore(s -> s
        .query(qq -> qq.bool(b -> b
            .must(m -> m.term(“user_id”, userId))
            .must(m -> m.term(“memory_type”, newMemory.getMemoryType()))
        ))
        .script(s -> s
            .source(“cosineSimilarity(params.queryVector, 'summary_embedding') + 0.01”)
            .param(“queryVector”, embedding)
        )
    ))
    .filter(f -> f.range(r -> r.field(“importance”).gte(0.8)));

if (similar.size() > 0 && cosineSimilarity > 0.9) {
    // 更新已有记忆，追加 details
    updateMemory(similar.get(0).getMemoryId(), newMemory);
} else {
    // 新写入
    insertMemory(newMemory);
}
```

#### B.4.2 定时清理任务

使用 `@Scheduled` 每天凌晨清理：

- `last_used_at` 超过 180 天且 `is_active = true` → `is_active = false`
- `updated_at` 超过 365 天 → 删除

#### B.4.3 阶段性验收标准

- [ ] 相似记忆不重复写入
- [ ] 定时任务日志正确
- [ ] 过期记忆可被过滤掉

---

## C. 实施检查清单

| 阶段 | 任务 | 状态 | 验收标准 |
|------|------|------|---------|
| Phase 0 | Redis List 迁移 | ⏳ 待实施 | 5 轮窗口，token_estimate |
| Phase 0 | 轮次窗口控制 | ⏳ 待实施 | 固定 10 条消息 |
| Phase 1 | ES 索引 + Service 骨架 | ⏳ 待实施 | mapping 正确，可 CRUD |
| Phase 1 | Embedding 集成 | ⏳ 待实施 | 向量写入/检索 |
| Phase 2 | Consolidation 触发 | ⏳ 待实施 | 溢出时调用 LLM |
| Phase 2 | 候选抽取 Prompt | ⏳ 待实施 | 质量可接受 |
| Phase 3 | 检索流程接入 | ⏳ 待实施 | 召回有帮助 |
| Phase 3 | Hybrid 检索 | ⏳ 待实施 | RRF 融合 |
| Phase 4 | 去重合并 | ⏳ 待实施 | 无重复 |
| Phase 4 | 定时清理 | ⏳ 待实施 | TTL 生效 |

---

## D. 与现有文档的兼容性说明

本文档在以下方面**强化或细化**了原设计文档：

1. **Redis Key 保持兼容**：继续使用 `conversation:{id}`，但从 String 迁移到 List
2. **当前 20 条截断改为 5 轮**：减少信息量但提升语义完整性
3. **Trigger 方式**：原文档建议按 token溢出，本项目改为”轮次即将扩展时”，更易实现
4. **长期记忆暂时不做 Rerank**：复用 `HybridSearchService` 的 RRF 即可，不重复造轮子
5. **简化 memory_type**：暂时只支持 `task / preference / fact / episode` 四类，减少 LLM 分类复杂度

---

## E. 原始设计文档（保持不变）

以下为原始 memory_module_design.md 内容，描述通用设计原则。

### 1. 目标

设计一个适用于对话式 RAG 系统的记忆模块，将用户历史对话拆分为：

- **短期记忆（Short-term Memory）**：保存最近若干轮原始对话，保证当前上下文连续性
- **长期记忆（Long-term Memory）**：对较早历史进行筛选、摘要、结构化后入库，支持按需动态唤醒

核心目标：

1. 避免每次都把全部历史对话塞给 LLM，降低上下文成本
2. 保证连续追问、任务延续、多轮约束场景下的上下文稳定性
3. 让长期有效的信息可以通过检索动态唤醒
4. 降低长期记忆噪声，避免把所有历史都机械入库

---

### 2. 总体设计

系统采用两级记忆：

#### 2.1 短期记忆：Redis

短期记忆存放在 **Redis** 中，保存最近若干轮原始消息，供当前对话直接拼接给 LLM。

建议：

- 不要只保存”最近 5 条 message”
- 推荐保存”最近 3~5 轮对话”或”最近固定 token 预算内的消息”
- Redis 中保存原始消息，不能只保存摘要

短期记忆作用：

- 承接当前会话上下文
- 支持”继续””还是按刚才那个来””你刚才说的第二点”这种依赖近端上下文的提问
- 参与长期记忆检索 query 的构造

---

#### 2.2 长期记忆：ES（Elasticsearch）

长期记忆存放在 **Elasticsearch** 中，用于动态唤醒。

长期记忆不是原始对话全文，而是：

- 经过筛选后的重要信息
- 结构化后的记忆项
- 可检索、可打分、可过期、可更新

长期记忆作用：

- 召回用户长期偏好
- 召回持续任务状态
- 召回历史事实与经验
- 在用户 query 较短时，通过短期上下文辅助检索出真正相关的旧信息

---

### 3. 记忆分层原则

#### 3.1 短期记忆存什么

短期记忆保留最近几轮原始对话，建议包括：

- 用户消息
- Assistant 回复
- 时间戳
- 可选：工具调用摘要 / 检索结果摘要

短期记忆不需要压缩得太狠，它的核心是保真。

适合放入短期记忆的信息：

- 当前话题
- 最近的约束条件
- 当前任务的中间状态
- 最近几轮中的指代关系

例如：

- “按刚才那个 loss 改”
- “还是不要用伪标签过滤”
- “实验部分继续写”

这些都高度依赖最近上下文。

---

#### 3.2 长期记忆存什么

长期记忆只保留未来高概率还会用到的信息，不是所有旧历史都入库。

适合进入长期记忆的信息：

1. **用户画像 / 长期偏好**
   - 用户喜欢的回答风格
   - 常用语言
   - 习惯的输出格式
   - 长期稳定的偏好

2. **持续任务记忆**
   - 正在做的项目/论文/系统
   - 当前项目阶段
   - 上次中断位置
   - 长期约束条件

3. **事实记忆**
   - 用户明确给出的稳定事实
   - 固定的数据结构/接口约定
   - 明确确认过的系统配置

4. **经验记忆 / 事件记忆（episodic memory）**
   - 某次问题如何解决
   - 某次实验失败原因
   - 某个方案尝试后的结论

不建议进入长期记忆的信息：

- 一次性寒暄
- 临时闲聊
- 无复用价值的短问短答
- 已过期状态
- 重复表达的同类信息
- 未确认且不稳定的中间推测

---

### 4. 核心流程

#### 4.1 写入流程

##### Step 1：用户发起新消息

系统接收到：

- `user_id`
- `session_id`
- `query`

##### Step 2：写入 Redis 短期记忆

将本轮消息追加到 Redis 中。

建议使用 Redis List / Stream 保存消息序列。

保存内容示例：

```json
{
  “role”: “user”,
  “content”: “继续帮我改实验部分”,
  “timestamp”: 1710000000,
  “message_id”: “msg_xxx”
}
```

Assistant 回复后，也要追加一条 assistant message。

##### Step 3：检查短期窗口是否溢出

如果 Redis 中短期记忆超过阈值，则触发”记忆整理（consolidation）”。

阈值建议：

- 按轮次：超过最近 5 轮
- 或按 token：超过 2000~4000 tokens

推荐优先按 token 控制，更贴近实际 LLM 上下文成本。

##### Step 4：从溢出的旧对话中抽取候选长期记忆

不是把所有旧消息直接摘要后入库，而是先做筛选。

可让一个轻量 LLM / 规则模块从旧对话中抽取：

- 是否包含可长期复用的信息
- 该信息属于哪类记忆
- 重要性如何
- 置信度如何
- 是否已存在相似记忆

输出格式建议：

```json
{
  “should_store”: true,
  “memory_type”: “task”,
  “summary”: “用户正在撰写通用域适应论文，当前关注 Office31 上 common acc 偏低问题”,
  “entities”: [“通用域适应”, “Office31”, “common acc”],
  “importance”: 0.89,
  “confidence”: 0.93,
  “source_span”: [“msg_101”, “msg_108”]
}
```

##### Step 5：写入 ES 长期记忆

将筛选后的记忆项写入 ES。

写入前最好做：

- 相似去重
- 同类记忆合并
- 老版本记忆失效/降权

例如同一个持续任务不要每天生成 20 条高度相似记忆。

---

#### 4.2 检索流程

##### Step 1：读取 Redis 短期记忆

获取当前 session 最近几轮原始对话。

##### Step 2：构造长期记忆检索 query

不要只用用户当前 query 去 ES 检索。

应使用：

- 当前 query
- 短期记忆摘要
- 当前任务标签 / 实体

组合出一个更完整的 retrieval query。

例如用户只说：

> “继续改一下实验部分”

直接检索几乎无效。

应扩展成：

> 用户正在撰写通用域适应论文，当前在修改实验部分，最近讨论过 Office31、OfficeHome、common acc、伪标签一致性。

然后再去 ES 检索长期记忆。

##### Step 3：ES 检索长期记忆

建议使用 **混合检索**：

- 关键词检索（BM25）
- 向量检索（dense vector）
- metadata filter（按 user_id / type / 时间 / session / importance）

##### Step 4：Rerank

对召回的长期记忆进行 rerank。

rerank 可考虑因素：

- query 相关性
- 短期上下文相关性
- importance
- confidence
- recency
- memory_type 优先级

##### Step 5：拼装最终上下文给 LLM

最终发给 LLM 的上下文建议包括四部分：

1. **系统提示词**
2. **短期记忆（Redis 原始对话）**
3. **长期记忆（ES 检索 Top-K）**
4. **外部知识检索结果（RAG 文档）**
5. **当前用户 query**

推荐拼装顺序：

```text
[System Prompt]
[Short-term Memory]
[Relevant Long-term Memory]
[Retrieved Knowledge]
[Current User Query]
```

注意：

- 长期记忆是”辅助信息”，不能无上限拼接
- 建议 Top-K 控制在 3~8 条
- 长期记忆每条尽量短、结构化、可解释

---

### 5. Redis 短期记忆设计

#### 5.1 Redis Key 设计

```text
conversation:{conversation_id}
```

#### 5.2 Redis Value 设计

推荐 Redis List：

- 每条消息 append 到 list 尾部
- 用 `LRANGE` 取最近 N 条
- 用 `LTRIM` 控制窗口大小

消息内容建议为 JSON 字符串：

```json
{
  “message_id”: “msg_001”,
  “role”: “user”,
  “content”: “帮我继续改实验部分”,
  “timestamp”: 1710000000,
  “token_estimate”: 23
}
```

---

### 6. ES 长期记忆设计

#### 6.1 Index 建议

```text
conversation_long_term_memory
```

#### 6.2 Document 结构建议

```json
{
  “memory_id”: “mem_001”,
  “user_id”: “u123”,
  “session_id”: “s456”,
  “memory_type”: “task”,
  “summary”: “用户正在撰写通用域适应论文，当前关注 Office31 上 common acc 偏低问题。”,
  “details”: “之前讨论过伪标签一致性、common/private class 区分，以及 OfficeHome 与 Office31 表现差异。”,
  “entities”: [“通用域适应”, “Office31”, “OfficeHome”, “common acc”],
  “keywords”: [“实验分析”, “伪标签”, “分类准确率”],
  “importance”: 0.89,
  “confidence”: 0.93,
  “created_at”: “2026-04-14T10:00:00Z”,
  “updated_at”: “2026-04-14T10:00:00Z”,
  “last_used_at”: “2026-04-14T10:20:00Z”,
  “source_message_ids”: [“msg_101”, “msg_108”],
  “is_active”: true,
  “ttl_days”: 180,
  “summary_embedding”: [0.123, 0.456, 0.789]
}
```

#### 6.3 memory_type 枚举建议

```text
preference   # 长期偏好
profile      # 用户稳定背景
task         # 持续任务
fact         # 稳定事实
episode      # 历史事件 / 经验总结
constraint   # 长期约束
```

---

### 7. 给实现者的明确约束

1. **短期记忆必须使用 Redis**，不能省略 Redis
2. **不能只保存最近 5 条 message**，应按轮次或 token 控制窗口
3. **长期记忆不能把所有旧对话直接摘要入 ES**，必须先筛选
4. **长期记忆检索不能只依赖当前 query**，必须结合短期上下文扩展检索 query
5. **长期记忆必须带 metadata**，至少包含：user_id, memory_type, importance, confidence, created_at, last_used_at, source_message_ids
6. **最终发给 LLM 的上下文必须包含短期记忆 + 长期记忆 + 当前 query**
7. **若长期记忆与当前上下文冲突，应优先采用当前上下文和用户最新指令**

---

### 8. 最终结论

这个记忆模块的正确方向是：

- **Redis 保存短期原始对话记忆**
- **ES 保存筛选后的长期结构化记忆**
- **回答问题时，使用”短期记忆 + 当前 query + 长期记忆检索结果 + RAG 检索结果”一起发给 LLM**

推荐实现原则：

> 短期记忆保真，长期记忆筛选；
> 检索时结合近端上下文做动态唤醒；
> Redis 管会话态，ES 管长期知识化记忆。
