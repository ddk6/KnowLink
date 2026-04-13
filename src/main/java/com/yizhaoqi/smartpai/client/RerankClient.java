package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.RerankProperties;
import com.yizhaoqi.smartpai.exception.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;
import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 阿里云 DashScope qwen-rerank-2 模型客户端
 * 用于对候选文档进行 Cross-Encoder 重排
 */
@Component
public class RerankClient {

    private static final Logger logger = LoggerFactory.getLogger(RerankClient.class);

    private final RerankProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    /** Rerank 限流: 每分钟最大请求次数 */
    private static final long RERANK_MINUTE_MAX = 30;
    /** Rerank 限流: 分钟级窗口秒数 */
    private static final long RERANK_MINUTE_WINDOW = 60;

    public RerankClient(
            RerankProperties properties,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${embedding.api.key:}") String embeddingApiKey
    ) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = (properties.getApiKey() != null && !properties.getApiKey().isBlank())
                ? properties.getApiKey()
                : embeddingApiKey;
    }

    @PostConstruct
    public void init() {
        logger.info("RerankClient 初始化 - 模型: {}, API地址: {}, 启用状态: {}, TopN: {}, RRF权重(KNN={}, BM25={})",
                properties.getModel(),
                properties.getApiUrl(),
                properties.isEnabled(),
                properties.getRerankTopN(),
                properties.getKnnWeight(),
                properties.getBm25Weight());
    }

    /** 获取 WebClient 实例 */
    private WebClient getWebClient() {
        return WebClient.builder()
                .baseUrl(properties.getApiUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * 调用 Rerank API 对文档进行重排
     *
     * @param query     查询文本
     * @param documents 候选文档内容列表
     * @return 重排结果列表，按相关性分数降序排列
     */
    public List<RerankResult> rerank(String query, List<String> documents) {
        if (!properties.isEnabled()) {
            logger.debug("Rerank 已禁用，直接返回原始顺序");
            return createOriginalOrderResults(documents);
        }

        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        // 限流检查
        checkRateLimit();

        // 截断过长文档以节省 token
        List<String> processedDocs = documents.stream()
                .map(doc -> doc.length() > 4000 ? doc.substring(0, 4000) : doc)
                .collect(Collectors.toList());

        try {
            return doRerank(query, processedDocs);
        } catch (Exception e) {
            logger.error("Rerank API 调用失败: {}", e.getMessage());
            // 失败时返回原始顺序
            return createOriginalOrderResults(documents);
        }
    }

    private List<RerankResult> doRerank(String query, List<String> documents) {
        // DashScope rerank API 请求格式
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", properties.getModel());

        // 按 DashScope 格式封装 input
        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        requestBody.put("input", input);

        // 可选参数
        requestBody.put("top_n", Math.min(properties.getRerankTopN(), documents.size()));

        logger.debug("发送 Rerank 请求 - 模型: {}, 文档数: {}, 查询长度: {}",
                properties.getModel(), documents.size(), query.length());
        logger.debug("Rerank 请求体: {}", requestBody);

        String response;
        try {
            response = getWebClient().post()
                    .uri(properties.getApiUrl())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.fixedDelay(2, Duration.ofSeconds(1))
                            .filter(e -> e instanceof WebClientResponseException)
                            .doBeforeRetry(signal -> logger.warn("Rerank API 重试 - 尝试: {}, 错误: {}",
                                    signal.totalRetries() + 1, signal.failure().getMessage())))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .block();
        } catch (WebClientResponseException e) {
            logger.error("Rerank API 返回错误 - 状态码: {}, 响应: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }

        if (response == null || response.isBlank()) {
            logger.warn("Rerank API 返回空响应");
            return createOriginalOrderResults(documents);
        }

        logger.debug("Rerank 响应: {}", response);
        return parseRerankResponse(response, documents);
    }

    private List<RerankResult> parseRerankResponse(String response, List<String> originalDocs) {
        try {
            JsonNode root = objectMapper.readTree(response);
            // 正确的路径: output.results
            JsonNode output = root.get("output");
            JsonNode results = null;

            if (output != null && output.has("results")) {
                results = output.get("results");
            }

            if (results == null || !results.isArray()) {
                logger.warn("Rerank 响应格式错误，output.results 字段不存在或不是数组");
                logger.debug("Rerank 原始响应: {}", response);
                return createOriginalOrderResults(originalDocs);
            }

            List<RerankResult> rerankResults = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.get("index").asInt();
                double score = item.get("relevance_score").asDouble();

                String docContent = "";
                if (index < originalDocs.size()) {
                    docContent = originalDocs.get(index);
                }

                rerankResults.add(new RerankResult(index, score, docContent));
            }

            // 按分数降序排列
            rerankResults.sort((a, b) -> Double.compare(b.score(), a.score()));

            logger.debug("Rerank 完成 - 有效结果数: {}", rerankResults.size());
            return rerankResults;

        } catch (Exception e) {
            logger.error("解析 Rerank 响应失败: {}", e.getMessage());
            return createOriginalOrderResults(originalDocs);
        }
    }

    /**
     * 创建原始顺序的结果 (当 rerank 禁用或失败时使用)
     */
    private List<RerankResult> createOriginalOrderResults(List<String> documents) {
        List<RerankResult> results = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            // 原始顺序时，所有文档分数相同 (1.0 / (i + 1)) 保持相对顺序
            double score = 1.0 / (i + 1);
            results.add(new RerankResult(i, score, documents.get(i)));
        }
        return results;
    }

    /**
     * 简单的 Redis 限流检查
     */
    private void checkRateLimit() {
        String key = "rerank:minute:" + (Instant.now().getEpochSecond() / RERANK_MINUTE_WINDOW);
        Long current = redisTemplate.opsForValue().increment(key);

        if (current != null && current == 1) {
            redisTemplate.expire(key, RERANK_MINUTE_WINDOW, TimeUnit.SECONDS);
        }

        if (current != null && current > RERANK_MINUTE_MAX) {
            logger.warn("Rerank 请求触发限流，当前半分钟请求数: {}", current);
            throw new CustomException("Rerank 请求过于频繁，请稍后重试", org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    /**
     * Rerank 结果记录
     */
    public record RerankResult(
            /** 原始文档索引 */
            int originalIndex,
            /** 相关性分数 */
            double score,
            /** 文档内容 */
            String documentContent
    ) {
    }
}
