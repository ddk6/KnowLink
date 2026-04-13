package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rerank API 配置属性
 * 用于配置阿里云 DashScope qwen-rerank-2 模型
 */
@Component
@ConfigurationProperties(prefix = "rerank")
@Data
public class RerankProperties {

    /** 是否启用 rerank 功能 */
    private boolean enabled = true;

    /** API URL */
    private String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/rerank";

    /** API Key (默认复用 embedding 的 key) */
    private String apiKey;

    /** Rerank 模型名称 */
    private String model = "qwen-rerank-2";

    /** RRF 融合时 KNN 的权重 */
    private double knnWeight = 0.5;

    /** RRF 融合时 BM25 的权重 */
    private double bm25Weight = 0.5;

    /** RRF K 参数 (通常设为 60)，用于缓解排名差距过大的问题 */
    private int rrfK = 60;

    /** 重排候选数量上限 */
    private int rerankTopN = 20;

    /** 最大单次重排文档数 */
    private int maxRerankDocs = 100;

    /** 请求超时时间 (毫秒) */
    private int timeoutMs = 30000;
}
