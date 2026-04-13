package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * MinerU 解析结果实体
 * 用于存储 MinerU API 解析文档后的结果
 */
@Data
@Entity
@Table(name = "mineru_parse_result", indexes = {
        @Index(name = "idx_file_md5", columnList = "file_md5")
})
public class MinerUParseResult {

    /** 自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联文件的 MD5 */
    @Column(name = "file_md5", length = 32, nullable = false)
    private String fileMd5;

    /** 完整 Markdown 内容 */
    @Column(name = "full_md", columnDefinition = "LONGTEXT")
    private String fullMd;

    /** content_list_v2.json 内容 */
    @Column(name = "content_json", columnDefinition = "LONGTEXT")
    private String contentJson;

    /** layout.json 内容（可选，用于调试） */
    @Column(name = "layout_json", columnDefinition = "LONGTEXT")
    private String layoutJson;

    /** MinerU batch_id，用于排查问题 */
    @Column(name = "mineru_batch_id", length = 64)
    private String mineruBatchId;

    /** 解析状态 SUCCESS/FAILED */
    @Column(name = "parse_status", length = 20)
    private String parseStatus;

    /** 错误信息 */
    @Column(name = "parse_error", columnDefinition = "TEXT")
    private String parseError;

    /** 创建时间 */
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
