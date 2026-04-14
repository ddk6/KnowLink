package com.yizhaoqi.smartpai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * MinerU 表格对象 - 第一阶段扫描结果
 * 用于存储从 content_list_v2.json 中提取的表格元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableObject {
    /**
     * 表格唯一标识，格式: p{page}_t{blockIndex}
     */
    private String tableId;

    /**
     * 所在页码
     */
    private int page;

    /**
     * 在页面中的块索引
     */
    private int blockIndex;

    /**
     * 原始 caption 数组（可能包含多个元素）
     */
    @Builder.Default
    private List<String> rawCaption = new ArrayList<>();

    /**
     * 原始 footnote 数组
     */
    @Builder.Default
    private List<String> rawFootnote = new ArrayList<>();

    /**
     * 表格前的邻近文本（paragraph/title）
     */
    @Builder.Default
    private List<String> prevTexts = new ArrayList<>();

    /**
     * 表格后的邻近文本
     */
    @Builder.Default
    private List<String> nextTexts = new ArrayList<>();

    /**
     * 从 full.md 中获取的邻近提示
     */
    @Builder.Default
    private List<String> mdNeighborHints = new ArrayList<>();

    /**
     * 候选表头行（第一行，通常是"会计年度 | 2022 | 2023E..."）
     */
    @Builder.Default
    private List<String> headerCandidates = new ArrayList<>();

    /**
     * 所有行标签（第一列的行名）
     */
    @Builder.Default
    private List<String> rowLabels = new ArrayList<>();

    /**
     * 年份列标识（如 ["2022", "2023E", "2024E", "2025E"]）
     */
    @Builder.Default
    private List<String> yearColumns = new ArrayList<>();

    /**
     * 原始 HTML 内容
     */
    private String rawHtml;

    /**
     * 行数
     */
    private int rowCount;

    /**
     * 列数
     */
    private int columnCount;

    /**
     * 边界框 [x1, y1, x2, y2]
     */
    @Builder.Default
    private List<Integer> bbox = new ArrayList<>();

    /**
     * 完整表格矩阵（用于第二阶段结构化抽取）
     * 格式: [[cell00, cell01, cell02...], [cell10, cell11...], ...]
     */
    @Builder.Default
    private List<List<String>> tableMatrix = new ArrayList<>();
}
