package com.yizhaoqi.smartpai.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 长期记忆实体类
 * 存储在 Elasticsearch conversation_long_term_memory 索引中
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LongTermMemory {

    private String memoryId;          // 记忆唯一标识
    private String userId;            // 用户ID
    private String sessionId;         // 会话ID
    private String memoryType;        // 记忆类型: task/preference/fact/episode/constraint
    private String summary;           // 摘要（短，用于展示）
    private String details;           // 详细补充
    private List<String> entities;    // 实体列表
    private List<String> keywords;     // 关键词
    private Double importance;        // 重要性 0-1
    private Double confidence;        // 置信度 0-1
    private LocalDateTime createdAt;  // 创建时间
    private LocalDateTime updatedAt;  // 更新时间
    private LocalDateTime lastUsedAt; // 最后使用时间
    private List<String> sourceMessageIds;  // 源消息ID列表
    private Boolean isActive;          // 是否激活
    private Integer ttlDays;           // TTL天数
    private List<Float> summaryEmbedding;  // 摘要向量（用于相似度检索）

    /**
     * 记忆类型枚举
     */
    public static final String TYPE_TASK = "task";
    public static final String TYPE_PREFERENCE = "preference";
    public static final String TYPE_FACT = "fact";
    public static final String TYPE_EPISODE = "episode";
    public static final String TYPE_CONSTRAINT = "constraint";
}
