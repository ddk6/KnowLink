package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinerU API 配置属性
 * 用于配置 MinerU 标准精准 API
 */
@Component
@ConfigurationProperties(prefix = "mineru")
@Data
public class MinerUProperties {

    /** 是否启用 MinerU 解析 (false 则使用原有 Tika 解析) */
    private boolean enabled = false;

    /** API Base URL */
    private String apiUrl = "https://mineru.net";

    /** API Key (从 application.yml 的 MinerU.api.key 读取) */
    private String apiKey;

    /** MinerU 模型版本 */
    private String modelVersion = "vlm";

    /** 语言设置 */
    private String language = "ch";

    /** 是否启用表格识别 */
    private boolean enableTable = true;

    /** 是否启用公式识别 */
    private boolean enableFormula = true;

    /** 是否启用 OCR (图片文字识别) */
    private boolean isOcr = true;

    /** 轮询最大次数 */
    private int pollingMaxAttempts = 100;

    /** 轮询间隔 (毫秒) */
    private int pollingIntervalMs = 5000;

    /** 请求超时时间 (毫秒) */
    private int timeoutMs = 30000;

    /** 下载文件存放临时目录 */
    private String tempDownloadPath = "/tmp/mineru";
}
