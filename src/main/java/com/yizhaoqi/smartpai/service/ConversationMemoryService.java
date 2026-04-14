package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.LongTermMemory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 对话记忆服务
 * 负责短期记忆溢出时的长期记忆写入，以及长期记忆的检索
 */
@Service
public class ConversationMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationMemoryService.class);
    private static final String INDEX_NAME = "conversation_long_term_memory";

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private ModelProviderConfigService modelProviderConfigService;

    @Autowired
    private UsageQuotaService usageQuotaService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> VALID_MEMORY_TYPES = Set.of(
            LongTermMemory.TYPE_TASK,
            LongTermMemory.TYPE_PREFERENCE,
            LongTermMemory.TYPE_FACT,
            LongTermMemory.TYPE_EPISODE,
            LongTermMemory.TYPE_CONSTRAINT
    );

    /**
     * 初始化 ES 索引
     */
    @PostConstruct
    public void initIndex() {
        try {
            boolean exists = esClient.indices().exists(
                    ExistsRequest.of(e -> e.index(INDEX_NAME))
            ).value();

            if (!exists) {
                createIndex();
                logger.info("创建长期记忆索引: {}", INDEX_NAME);
            } else {
                logger.info("长期记忆索引已存在: {}", INDEX_NAME);
            }
        } catch (Exception e) {
            logger.error("初始化长期记忆索引失败: {}", e.getMessage(), e);
        }
    }

    private void createIndex() {
        try {
            esClient.indices().create(CreateIndexRequest.of(c -> c
                    .index(INDEX_NAME)
                    .mappings(m -> m
                            .properties("memoryId", p -> p.keyword(k -> k))
                            .properties("userId", p -> p.keyword(k -> k))
                            .properties("sessionId", p -> p.keyword(k -> k))
                            .properties("memoryType", p -> p.keyword(k -> k))
                            .properties("summary", p -> p.text(t -> t.analyzer("ik_max_word")))
                            .properties("details", p -> p.text(t -> t.analyzer("ik_max_word")))
                            .properties("entities", p -> p.keyword(k -> k))
                            .properties("keywords", p -> p.keyword(k -> k))
                            .properties("importance", p -> p.float_(f -> f))
                            .properties("confidence", p -> p.float_(f -> f))
                            .properties("createdAt", p -> p.date(d -> d))
                            .properties("updatedAt", p -> p.date(d -> d))
                            .properties("lastUsedAt", p -> p.date(d -> d))
                            .properties("sourceMessageIds", p -> p.keyword(k -> k))
                            .properties("isActive", p -> p.boolean_(b -> b))
                            .properties("ttlDays", p -> p.integer(i -> i))
                            .properties("summaryEmbedding", p -> p.denseVector(dv -> dv
                                    .dims(1536)
                                    .index(true)
                                    .similarity("cosine")
                            ))
                    )
            ));
        } catch (Exception e) {
            logger.error("创建索引失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 写入长期记忆
     */
    public void saveMemory(LongTermMemory memory) {
        try {
            if (memory.getMemoryId() == null) {
                memory.setMemoryId(UUID.randomUUID().toString());
            }
            if (memory.getCreatedAt() == null) {
                memory.setCreatedAt(LocalDateTime.now());
            }
            memory.setUpdatedAt(LocalDateTime.now());
            if (memory.getIsActive() == null) {
                memory.setIsActive(true);
            }

            IndexResponse response = esClient.index(IndexRequest.of(i -> i
                    .index(INDEX_NAME)
                    .id(memory.getMemoryId())
                    .document(memory)
            ));

            logger.info("长期记忆写入成功: memoryId={}, result={}", memory.getMemoryId(), response.result());
        } catch (Exception e) {
            logger.error("写入长期记忆失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 根据 userId 检索长期记忆
     */
    public List<LongTermMemory> retrieveMemories(String userId, String query, int topK) {
        try {
            SearchResponse<LongTermMemory> response = esClient.search(SearchRequest.of(s -> s
                    .index(INDEX_NAME)
                    .query(Query.of(q -> q.bool(BoolQuery.of(b -> b
                            .must(m -> m.term(t -> t.field("userId").value(userId)))
                            .must(m -> m.term(t -> t.field("isActive").value(true)))
                            .should(sh -> sh.match(mt -> mt.field("summary").query(query)))
                            .should(sh -> sh.match(mt -> mt.field("details").query(query)))
                            .should(sh -> sh.match(mt -> mt.field("entities").query(query)))
                    ))))
                    .size(topK)
            ), LongTermMemory.class);

            List<LongTermMemory> results = new ArrayList<>();
            for (Hit<LongTermMemory> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    results.add(hit.source());
                }
            }

            logger.debug("检索长期记忆: userId={}, query={}, 结果数={}", userId, query, results.size());
            return results;
        } catch (Exception e) {
            logger.error("检索长期记忆失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 标记记忆为已使用（更新 lastUsedAt）
     */
    public void markAsUsed(String memoryId) {
        try {
            esClient.update(UpdateRequest.of(u -> u
                    .index(INDEX_NAME)
                    .id(memoryId)
                    .doc(Map.of("lastUsedAt", LocalDateTime.now()))
            ), LongTermMemory.class);
        } catch (Exception e) {
            logger.warn("更新记忆使用时间失败: memoryId={}, error={}", memoryId, e.getMessage());
        }
    }

    /**
     * 软删除记忆（设置 isActive = false）
     */
    public void deactivateMemory(String memoryId) {
        try {
            esClient.update(UpdateRequest.of(u -> u
                    .index(INDEX_NAME)
                    .id(memoryId)
                    .doc(Map.of("isActive", false, "updatedAt", LocalDateTime.now()))
            ), LongTermMemory.class);
            logger.info("记忆已停用: memoryId={}", memoryId);
        } catch (Exception e) {
            logger.error("停用记忆失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 检查是否有相似记忆存在
     */
    public Optional<LongTermMemory> findSimilarMemory(String userId, String summary, String memoryType) {
        try {
            SearchResponse<LongTermMemory> response = esClient.search(SearchRequest.of(s -> s
                    .index(INDEX_NAME)
                    .query(Query.of(q -> q.bool(BoolQuery.of(b -> b
                            .must(m -> m.term(t -> t.field("userId").value(userId)))
                            .must(m -> m.term(t -> t.field("memoryType").value(memoryType)))
                            .must(m -> m.term(t -> t.field("isActive").value(true)))
                            .must(m -> m.match(mt -> mt.field("summary").query(summary)))
                    ))))
                    .size(1)
            ), LongTermMemory.class);

            if (response.hits().hits().size() > 0 && response.hits().hits().get(0).source() != null) {
                return Optional.of(response.hits().hits().get(0).source());
            }
            return Optional.empty();
        } catch (Exception e) {
            logger.error("查找相似记忆失败: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 构造检索 query（结合短期记忆扩展）
     * 目前是简单版本，后续可增强为 LLM 生成
     */
    public String buildRetrievalQuery(String userQuery, List<Map<String, Object>> shortTermMessages) {
        StringBuilder sb = new StringBuilder(userQuery);
        logger.debug("构造检索 query: {}", sb);
        return sb.toString();
    }

    /**
     * 判断 memoryType 是否有效
     */
    public boolean isValidMemoryType(String type) {
        return VALID_MEMORY_TYPES.contains(type);
    }

    /**
     * 触发记忆整合
     * 当短期记忆窗口溢出时，调用此方法提取候选长期记忆
     */
    public void consolidateMemory(String userId, String conversationId,
                                  List<Map<String, Object>> overflowMessages) {
        if (overflowMessages == null || overflowMessages.isEmpty()) {
            return;
        }

        try {
            logger.info("开始记忆整合: userId={}, conversationId={}, 消息数={}",
                    userId, conversationId, overflowMessages.size());

            // 调用 LLM 抽取候选记忆
            CandidateMemory candidate = extractCandidateMemory(overflowMessages);
            if (candidate == null || !candidate.shouldStore()) {
                logger.debug("没有值得存储的记忆");
                return;
            }

            // 检查相似记忆
            Optional<LongTermMemory> existing = findSimilarMemory(
                    userId, candidate.summary(), candidate.memoryType());
            if (existing.isPresent()) {
                // 合并到已有记忆
                logger.info("发现相似记忆，合并: existingId={}", existing.get().getMemoryId());
                mergeMemory(existing.get(), candidate);
            } else {
                // 创建新记忆
                LongTermMemory newMemory = new LongTermMemory();
                newMemory.setMemoryId(UUID.randomUUID().toString());
                newMemory.setUserId(userId);
                newMemory.setSessionId(conversationId);
                newMemory.setMemoryType(candidate.memoryType());
                newMemory.setSummary(candidate.summary());
                newMemory.setDetails(candidate.details());
                newMemory.setEntities(candidate.entities());
                newMemory.setKeywords(candidate.keywords());
                newMemory.setImportance(candidate.importance());
                newMemory.setConfidence(candidate.confidence());
                newMemory.setCreatedAt(LocalDateTime.now());
                newMemory.setUpdatedAt(LocalDateTime.now());
                newMemory.setLastUsedAt(LocalDateTime.now());
                newMemory.setSourceMessageIds(candidate.sourceMessageIds());
                newMemory.setIsActive(true);
                newMemory.setTtlDays(180);
                saveMemory(newMemory);
            }
        } catch (Exception e) {
            logger.error("记忆整合失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 调用 LLM 抽取候选记忆
     */
    private CandidateMemory extractCandidateMemory(List<Map<String, Object>> messages) {
        try {
            ModelProviderConfigService.ActiveProviderView provider =
                    modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);

            // 构建消息内容
            StringBuilder content = new StringBuilder("对话片段：\n");
            for (Map<String, Object> msg : messages) {
                String role = String.valueOf(msg.getOrDefault("role", ""));
                String text = String.valueOf(msg.getOrDefault("content", ""));
                String timestamp = String.valueOf(msg.getOrDefault("timestamp", ""));
                content.append(String.format("[%s] %s: %s\n", timestamp, role, text));
            }

            String prompt = String.format("""
                    你是记忆分析专家。从以下对话片段中抽取值得长期保留的信息。

                    %s

                    请判断：
                    1. 这段对话包含什么类型的信息？（task/preference/fact/episode/constraint）
                    2. 有什么值得未来复用的事实或结论？
                    3. 重要性打分（0-1）和置信度打分（0-1）
                    4. 如果不值得保存，返回空。

                    输出JSON格式（只返回JSON，不要其他文字）：
                    {
                      "should_store": true/false,
                      "memory_type": "task|preference|fact|episode|constraint",
                      "summary": "一句话总结",
                      "details": "详细补充（可选）",
                      "entities": ["实体1", "实体2"],
                      "keywords": ["关键词1", "关键词2"],
                      "importance": 0.85,
                      "confidence": 0.90,
                      "source_message_ids": ["msg_1", "msg_2"]
                    }
                    """, content);

            String response = callLlm(provider, prompt);
            return parseCandidateMemory(response);
        } catch (Exception e) {
            logger.error("抽取候选记忆失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private String callLlm(ModelProviderConfigService.ActiveProviderView provider, String prompt) {
        try {
            WebClient client = WebClient.builder()
                    .baseUrl(provider.apiBaseUrl())
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build();

            Map<String, Object> request = Map.of(
                    "model", provider.model(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "stream", false
            );

            Map<String, Object> response = client.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response != null && response.containsKey("choices")) {
                List<?> choices = (List<?>) response.get("choices");
                if (!choices.isEmpty()) {
                    Map<?, ?> choice = (Map<?, ?>) choices.get(0);
                    Map<?, ?> message = (Map<?, ?>) choice.get("message");
                    return String.valueOf(message.get("content"));
                }
            }
            return null;
        } catch (Exception e) {
            logger.error("LLM 调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private CandidateMemory parseCandidateMemory(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isBlank()) {
            return null;
        }
        try {
            // 清理可能的 markdown 代码块
            jsonResponse = jsonResponse.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();

            JsonNode node = objectMapper.readTree(jsonResponse);
            if (!node.has("should_store") || !node.get("should_store").asBoolean()) {
                return new CandidateMemory(false, null, null, null, null, null, 0.0, 0.0, Collections.emptyList());
            }

            List<String> entities = new ArrayList<>();
            if (node.has("entities") && node.get("entities").isArray()) {
                node.get("entities").forEach(e -> entities.add(e.asText()));
            }

            List<String> keywords = new ArrayList<>();
            if (node.has("keywords") && node.get("keywords").isArray()) {
                node.get("keywords").forEach(k -> keywords.add(k.asText()));
            }

            List<String> sourceMessageIds = new ArrayList<>();
            if (node.has("source_message_ids") && node.get("source_message_ids").isArray()) {
                node.get("source_message_ids").forEach(m -> sourceMessageIds.add(m.asText()));
            }

            return new CandidateMemory(
                    true,
                    node.has("memory_type") ? node.get("memory_type").asText() : "task",
                    node.has("summary") ? node.get("summary").asText() : "",
                    node.has("details") ? node.get("details").asText() : "",
                    entities,
                    keywords,
                    node.has("importance") ? node.get("importance").asDouble() : 0.5,
                    node.has("confidence") ? node.get("confidence").asDouble() : 0.5,
                    sourceMessageIds
            );
        } catch (JsonProcessingException e) {
            logger.error("解析候选记忆失败: {}, response={}", e.getMessage(), jsonResponse);
            return null;
        }
    }

    private void mergeMemory(LongTermMemory existing, CandidateMemory candidate) {
        try {
            // 合并 details
            String newDetails = existing.getDetails() != null
                    ? existing.getDetails() + "\n" + candidate.details()
                    : candidate.details();

            esClient.update(UpdateRequest.of(u -> u
                    .index(INDEX_NAME)
                    .id(existing.getMemoryId())
                    .doc(Map.of(
                            "details", newDetails,
                            "updatedAt", LocalDateTime.now(),
                            "lastUsedAt", LocalDateTime.now(),
                            "confidence", Math.min(1.0, existing.getConfidence() + 0.1)
                    ))
            ), LongTermMemory.class);

            logger.info("记忆合并完成: memoryId={}", existing.getMemoryId());
        } catch (Exception e) {
            logger.error("合并记忆失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 候选记忆记录
     */
    private record CandidateMemory(
            boolean shouldStore,
            String memoryType,
            String summary,
            String details,
            List<String> entities,
            List<String> keywords,
            double importance,
            double confidence,
            List<String> sourceMessageIds
    ) {}
}