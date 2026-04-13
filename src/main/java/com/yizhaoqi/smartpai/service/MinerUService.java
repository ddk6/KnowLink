package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.MinerUProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU API 服务
 * 用于调用 MinerU 标准精准 API 进行文档解析
 */
@Slf4j
@Service
public class MinerUService {

    private final MinerUProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MinerUService(MinerUProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
    }

    /**
     * 上传文件并等待解析完成
     *
     * @param file 文件路径
     * @param fileName 文件名
     * @param dataId 数据ID（用于追踪）
     * @return 解析结果
     */
    public MinerUParseResult uploadAndParse(Path file, String fileName, String dataId) throws Exception {
        log.info("[MinerU] 开始解析文件: {}, 大小: {} bytes", fileName, Files.size(file));

        // 1. 申请上传链接
        BatchApplyResult applyResult = applyUploadUrl(fileName, dataId);
        log.info("[MinerU] 获得上传链接, batchId: {}", applyResult.getBatchId());

        // 2. 上传文件到 MinerU OSS
        uploadFile(applyResult.getUploadUrl(), file);
        log.info("[MinerU] 文件上传成功");

        // 3. 轮询等待解析完成
        String zipUrl = waitForBatchDone(applyResult.getBatchId());
        log.info("[MinerU] 解析完成, ZIP URL: {}", zipUrl);

        // 4. 下载并解析 ZIP
        return downloadAndParseZip(zipUrl, applyResult.getBatchId());
    }

    /**
     * 申请上传链接
     */
    public BatchApplyResult applyUploadUrl(String fileName, String dataId) throws IOException, InterruptedException {
        String body = "{"
                + "\"files\": [{"
                + "\"name\": \"" + fileName + "\","
                + "\"data_id\": \"" + dataId + "\","
                + "\"is_ocr\": " + properties.isOcr() + ""
                + "}],"
                + "\"model_version\": \"" + properties.getModelVersion() + "\","
                + "\"language\": \"" + properties.getLanguage() + "\","
                + "\"enable_table\": " + properties.isEnableTable() + ","
                + "\"enable_formula\": " + properties.isEnableFormula() + ""
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getApiUrl() + "/api/v4/file-urls/batch"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        log.info("[MinerU] 申请上传链接响应状态: {}", response.statusCode());
        log.debug("[MinerU] 申请上传链接响应内容: {}", response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException("申请上传链接失败，HTTP=" + response.statusCode() + ", body=" + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new RuntimeException("申请上传链接业务失败: " + response.body());
        }

        String batchId = root.path("data").path("batch_id").asText();
        String uploadUrl = root.path("data").path("file_urls").get(0).asText();

        return new BatchApplyResult(batchId, uploadUrl);
    }

    /**
     * 上传文件到指定 URL
     */
    public void uploadFile(String uploadUrl, Path file) throws IOException, InterruptedException {
        byte[] bytes = Files.readAllBytes(file);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .timeout(Duration.ofMinutes(10))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        log.info("[MinerU] 上传文件响应状态: {}", response.statusCode());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            log.error("[MinerU] 上传文件响应内容: {}", response.body());
            throw new RuntimeException("上传文件失败，HTTP=" + response.statusCode());
        }
    }

    /**
     * 轮询等待批量任务完成
     */
    public String waitForBatchDone(String batchId) throws IOException, InterruptedException {
        int maxAttempts = properties.getPollingMaxAttempts();
        int intervalMs = properties.getPollingIntervalMs();

        for (int i = 0; i < maxAttempts; i++) {
            log.debug("[MinerU] 轮询第 {} 次...", i + 1);

            JsonNode root = queryBatchResult(batchId);
            JsonNode array = root.path("data").path("extract_result");

            if (!array.isArray()) {
                log.warn("[MinerU] 响应结构异常，extract_result 不是数组: {}", root);
                Thread.sleep(intervalMs);
                continue;
            }

            if (array.isEmpty()) {
                log.debug("[MinerU] extract_result 为空，继续等待...");
                Thread.sleep(intervalMs);
                continue;
            }

            JsonNode item = array.get(0);
            String state = item.path("state").asText("");
            log.debug("[MinerU] 状态: {}", state);

            if ("done".equalsIgnoreCase(state)) {
                String zipUrl = item.path("full_zip_url").asText("");
                if (zipUrl.isBlank()) {
                    throw new RuntimeException("状态为 done，但 full_zip_url 为空: " + item);
                }
                return zipUrl;
            }

            if ("failed".equalsIgnoreCase(state)) {
                String errMsg = item.path("err_msg").asText("未知错误");
                throw new RuntimeException("MinerU 解析失败: " + errMsg);
            }

            Thread.sleep(intervalMs);
        }

        throw new RuntimeException("MinerU 轮询超时，batchId=" + batchId);
    }

    /**
     * 查询批量任务结果
     */
    public JsonNode queryBatchResult(String batchId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getApiUrl() + "/api/v4/extract-results/batch/" + batchId))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("查询批量结果失败，HTTP=" + response.statusCode());
        }

        return objectMapper.readTree(response.body());
    }

    /**
     * 下载并解析 ZIP 文件
     */
    public MinerUParseResult downloadAndParseZip(String zipUrl, String batchId) throws Exception {
        // 创建临时目录
        Path tempDir = Paths.get(properties.getTempDownloadPath(), UUID.randomUUID().toString());
        Files.createDirectories(tempDir);

        try {
            // 下载 ZIP
            Path zipPath = tempDir.resolve("result.zip");
            downloadFile(zipUrl, zipPath);
            log.info("[MinerU] ZIP 下载完成: {}", zipPath);

            // 解压并解析
            String fullMd = null;
            String contentJson = null;
            String layoutJson = null;

            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String entryName = entry.getName().toLowerCase();

                    if (entryName.endsWith("full.md")) {
                        fullMd = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                        log.info("[MinerU] 提取到 full.md, 大小: {} bytes", fullMd.length());
                    } else if (entryName.endsWith("content_list_v2.json")) {
                        contentJson = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                        log.info("[MinerU] 提取到 content_list_v2.json, 大小: {} bytes", contentJson.length());
                    } else if (entryName.endsWith("layout.json")) {
                        layoutJson = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                        log.info("[MinerU] 提取到 layout.json, 大小: {} bytes", layoutJson.length());
                    } else if (entryName.endsWith("_origin.pdf")) {
                        // 原始 PDF 不需要，已存在于 MinIO
                        log.debug("[MinerU] 跳过原始 PDF: {}", entryName);
                    } else if (entryName.endsWith("_model.json")) {
                        // 模型元数据不需要
                        log.debug("[MinerU] 跳过模型元数据: {}", entryName);
                    }

                    zis.closeEntry();
                }
            }

            // 解析 full.md 为文本块
            List<TextChunk> chunks = parseMarkdownToChunks(fullMd);

            MinerUParseResult result = new MinerUParseResult();
            result.setFullMd(fullMd);
            result.setContentJson(contentJson);
            result.setLayoutJson(layoutJson);
            result.setMineruBatchId(batchId);
            result.setChunks(chunks);
            result.setParseStatus("SUCCESS");

            log.info("[MinerU] 解析完成，共 {} 个文本块", chunks.size());
            return result;

        } finally {
            // 清理临时文件
            try {
                Files.walk(tempDir)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
            } catch (IOException e) {
                log.warn("[MinerU] 清理临时目录失败: {}", tempDir);
            }
        }
    }

    /**
     * 下载文件
     */
    private void downloadFile(String url, Path targetPath) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<Path> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofFile(targetPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE));

        log.info("[MinerU] 下载响应状态: {}, 文件大小: {} bytes",
                response.statusCode(), Files.size(targetPath));
    }

    /**
     * 将 Markdown 文本切分为文本块
     */
    public List<TextChunk> parseMarkdownToChunks(String markdown) {
        List<TextChunk> chunks = new ArrayList<>();

        if (markdown == null || markdown.isBlank()) {
            return chunks;
        }

        // 按标题分割，每个标题下作为一个块
        String[] sections = markdown.split("\n(?=#)");

        int chunkId = 0;
        String currentSection = null;
        int currentPage = 1;

        for (String section : sections) {
            section = section.trim();
            if (section.isEmpty()) {
                continue;
            }

            // 判断是否是标题
            boolean isHeading = section.startsWith("#");
            String heading = null;
            int headingLevel = 0;

            if (isHeading) {
                int idx = section.indexOf(' ');
                if (idx > 0) {
                    headingLevel = idx - 1;
                    // 统计 # 数量
                    heading = section.substring(idx + 1).trim();
                }
            }

            // 超过 512 字符的块需要再分割
            if (section.length() > 512) {
                String[] paragraphs = section.split("\n\n+");
                StringBuilder currentChunk = new StringBuilder();

                for (String para : paragraphs) {
                    para = para.trim();
                    if (para.isEmpty()) {
                        continue;
                    }

                    if (currentChunk.length() + para.length() + 2 <= 512) {
                        if (currentChunk.length() > 0) {
                            currentChunk.append("\n\n");
                        }
                        currentChunk.append(para);
                    } else {
                        // 保存当前块
                        if (currentChunk.length() > 0) {
                            chunks.add(new TextChunk(chunkId++, currentChunk.toString(), currentPage, heading));
                            currentPage++;
                        }
                        // 开始新块
                        currentChunk = new StringBuilder(para);
                    }
                }

                // 保存最后一个块
                if (currentChunk.length() > 0) {
                    chunks.add(new TextChunk(chunkId++, currentChunk.toString(), currentPage, heading));
                    currentPage++;
                }
            } else {
                chunks.add(new TextChunk(chunkId++, section, currentPage, heading));
                currentPage++;
            }
        }

        return chunks;
    }

    /**
     * 申请上传链接结果
     */
    @Data
    public static class BatchApplyResult {
        private final String batchId;
        private final String uploadUrl;

        public BatchApplyResult(String batchId, String uploadUrl) {
            this.batchId = batchId;
            this.uploadUrl = uploadUrl;
        }
    }

    /**
     * MinerU 解析结果
     */
    @Data
    public static class MinerUParseResult {
        /** 完整 Markdown 内容 */
        private String fullMd;

        /** content_list_v2.json 内容 */
        private String contentJson;

        /** layout.json 内容 */
        private String layoutJson;

        /** MinerU batch_id */
        private String mineruBatchId;

        /** 解析状态 SUCCESS/FAILED */
        private String parseStatus;

        /** 解析错误信息 */
        private String parseError;

        /** 文本块列表 */
        private List<TextChunk> chunks;
    }

    /**
     * 文本块
     */
    @Data
    public static class TextChunk {
        /** 块序号 */
        private int chunkId;

        /** 文本内容 */
        private String content;

        /** 页码（从 Markdown 中估算） */
        private int pageNumber;

        /** 所属标题（用于引用来源） */
        private String heading;

        public TextChunk(int chunkId, String content, int pageNumber, String heading) {
            this.chunkId = chunkId;
            this.content = content;
            this.pageNumber = pageNumber;
            this.heading = heading;
        }

        /**
         * 生成锚文本（前 120 字符）
         */
        public String getAnchorText() {
            if (content == null) {
                return "";
            }
            String text = content.replace("\n", " ").trim();
            if (text.length() <= 120) {
                return text;
            }
            return text.substring(0, 120);
        }
    }
}
