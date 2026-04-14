package com.yizhaoqi.smartpai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结构化财务行记录
 * 用于存储从财务表格中抽取的规范化行级数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredFinancialRow {
    /**
     * 来源表格 ID
     */
    private String tableId;

    /**
     * 所在页码
     */
    private int page;

    /**
     * 报表类型: 利润表 / 现金流量表 / 资产负债表 / 财务比率表
     */
    private String statementType;

    /**
     * 年份: 2022 / 2023E / 2024E / 2025E
     */
    private String year;

    /**
     * 财务指标名称: 营业收入 / 营业成本 / 净利润 等
     */
    private String item;

    /**
     * 数值（字符串形式，保留原始格式如 "4432" / "-32" / "0.82"）
     */
    private String value;

    /**
     * 单位: 百万元 / 元 / % 等
     */
    private String unit;

    /**
     * 置信度 0.0 - 1.0
     */
    private double confidence;

    /**
     * 来源信息
     */
    private String sourceCaption;

    /**
     * 原始行标签（用于调试）
     */
    private String rawRowLabel;
}
