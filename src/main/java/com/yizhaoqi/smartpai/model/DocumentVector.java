package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Blob;

/**
 * 文档向量实体类
 * 用于存储文本分块和相关元数据
 */
@Data
@Entity
@Table(name = "document_vectors")
public class DocumentVector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;

    @Column(nullable = false, length = 32)
    private String fileMd5;

    @Column(nullable = false)
    private Integer chunkId;

    @Lob
    private String textContent;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    @Column(length = 32)
    private String modelVersion;
    
    /**
     * 上传用户ID
     */
    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;
    
    /**
     * 文件所属组织标签
     */
    @Column(name = "org_tag", length = 50)
    private String orgTag;
    
    /**
     * 文件是否公开
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    /**
     * 层级路径，如 "第3条 > 3.2 > (1)"
     */
    @Column(name = "section_path", length = 500)
    private String sectionPath;

    /**
     * chunk 类型: text/table/list
     */
    @Column(name = "chunk_type", length = 20)
    private String chunkType;

    /**
     * 是否关键条款
     */
    @Column(name = "is_key_clause")
    private boolean isKeyClause = false;

    /**
     * chunk token 数
     */
    @Column(name = "token_count")
    private Integer tokenCount;
}
