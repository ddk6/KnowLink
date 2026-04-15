package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.MinerUProperties;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU API 服务
 * 用于调用 MinerU 标准精准 API 进行文档解析
 */
//这是MinerU API服务类
    //这里的解析是将文件上传到MinerU服务器，等待解析完成，下载解析结果，解析结果是一个ZIP文件，包含解析后的JSON文件
    //解析后的JSON文件包含了文档的元数据和内容
    //元数据包含了文档的标题、作者、创建时间等信息
    //内容包含了文档的文本内容

@Slf4j
@Service
public class MinerUService {

    private final MinerUProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final com.yizhaoqi.smartpai.client.DeepSeekClient deepSeekClient;

    public MinerUService(MinerUProperties properties,
                         ObjectMapper objectMapper,
                         com.yizhaoqi.smartpai.client.DeepSeekClient deepSeekClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.deepSeekClient = deepSeekClient;
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
        MinerUParseResult result = downloadAndParseZip(zipUrl, applyResult.getBatchId());

        return result;
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

            // V3 语义感知切分 - 利用 content_list_v2.json 实现结构化切分
            List<TextChunk> chunks = parseMarkdownWithStructure(contentJson, fullMd);

            MinerUParseResult result = new MinerUParseResult();
            result.setFullMd(fullMd);
            result.setContentJson(contentJson);
            result.setLayoutJson(layoutJson);
            result.setMineruBatchId(batchId);
            result.setChunks(chunks);
            result.setParseStatus("SUCCESS");

            log.info("[MinerU] V3 解析完成，共 {} 个文本块", chunks.size());
            return result;

        } finally {
            // 临时文件保留在 D:\tmp\mineru\ 目录下，不删除，方便调试
            log.info("[MinerU] 临时文件保留在: {}", tempDir);
            // try {
            //     Files.walk(tempDir)
            //             .sorted((a, b) -> -a.compareTo(b))
            //             .forEach(p -> {
            //         try {
            //             Files.delete(p);
            //         } catch (IOException ignored) {
            //         }
            //     });
            // } catch (IOException e) {
            //     log.warn("[MinerU] 清理临时目录失败: {}", tempDir);
            // }
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
     * @deprecated 使用 parseMarkdownWithStructure() 利用 content_list_v2.json 实现 V3 切分
     */
    @Deprecated
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

    // ==================== V3 语义感知切分 ====================

    /** 关键条款关键词 */
    private static final List<String> KEY_CLAUSE_KEYWORDS = Arrays.asList(
            "保险责任", "责任免除", "费率", "赔付", "赔偿", "免责", "承保范围"
    );

    /** 普通 section 最大 token 数 */
    private static final int MAX_TOKEN_NORMAL = 1024;

    /** 关键条款最大 token 数 */
    private static final int MAX_TOKEN_KEY_CLAUSE = 1536;

    /** 表格最大 token 数 */
    private static final int MAX_TOKEN_TABLE = 300;


    /**
     * V3 语义感知切分 - 利用 content_list_v2.json 实现结构化切分
     */
    public List<TextChunk> parseMarkdownWithStructure(String contentJson, String fullMd) {
        List<TextChunk> chunks = new ArrayList<>();

        if ((contentJson == null || contentJson.isBlank()) && (fullMd == null || fullMd.isBlank())) {
            log.warn("[MinerU] contentJson 和 fullMd 都为空，尝试使用备用解析");
            return chunks;
        }

        try {
            // 尝试解析 content_list_v2.json
            if (contentJson != null && !contentJson.isBlank()) {
                JsonNode root = objectMapper.readTree(contentJson);
                log.debug("[MinerU] JSON 根节点类型: {}, 是数组: {}", root.getNodeType(), root.isArray());

                // V2: 顶层是页面数组 [[page0_blocks], [page1_blocks], ...]
                if (root.isArray() && root.size() > 0) {
                    JsonNode firstElement = root.get(0);
                    if (firstElement.isArray()) {
                        log.debug("[MinerU] 检测到 V2 页面结构，共 {} 页", root.size());
                        return parseContentListV2(root);
                    } else {
                        log.debug("[MinerU] V1 content_list 扁平数组，降级到 parseMarkdownFallback");
                        return parseMarkdownFallback(fullMd);
                    }
                }

                // 非数组，尝试获取 content_list_v2 字段
                JsonNode contentList = root.get("content_list_v2");
                if (contentList != null && contentList.isArray()) {
                    log.debug("[MinerU] content_list_v2 数组大小: {}", contentList.size());
                    return parseContentListV2(contentList);
                }

                log.warn("[MinerU] 无法识别 JSON 结构，根节点: {}", root.getNodeType());
            }
        } catch (Exception e) {
            log.warn("[MinerU] 解析 content_list_v2.json 失败: {}, 降级到 Markdown 解析", e.getMessage());
        }

        // 降级：使用 fullMd 解析
        return parseMarkdownFallback(fullMd);
    }

    /**
     * 解析 content_list_v2.json 结构
     * V2 结构：顶层是页面数组 [[page0_blocks], [page1_blocks], ...]
     * 每个 block 有 type + content (content 是对象，不是字符串)
     */
    private List<TextChunk> parseContentListV2(JsonNode contentList) {
        List<TextChunk> chunks = new ArrayList<>();
        int chunkId = 0;
        StringBuilder currentSectionContent = new StringBuilder();
        String currentSectionPath = "";
        int currentPage = 1;
        boolean isKeyClause = false;

        // V2 顶层是页面数组
        if (!contentList.isArray()) {
            log.warn("[MinerU] V2 contentList 不是数组，跳过");
            return chunks;
        }

        log.debug("[MinerU] V2 解析：共 {} 页", contentList.size());

        // 遍历每一页
        for (int pageIdx = 0; pageIdx < contentList.size(); pageIdx++) {
            JsonNode pageBlocks = contentList.get(pageIdx);
            if (!pageBlocks.isArray()) continue;

            log.debug("[MinerU] V2 解析第 {} 页，共 {} 个块", pageIdx, pageBlocks.size());

            // 遍历当前页的每个块
            for (JsonNode block : pageBlocks) {
                String type = block.path("type").asText("");
                JsonNode contentObj = block.path("content");

                // 提取文本内容
                String text = extractTextFromV2Block(type, contentObj);
                // 注意：table 类型返回空字符串，但需要进入 switch 处理表格，不能跳过
                if (text.isBlank() && !"table".equals(type)) {
                    continue;
                }

                // 计算页码 (pageIdx 从 0 开始，转为 1 开始)
                // 注意：block 内的 page_idx 是块在页面内的索引，不是 PDF 页码
                // PDF 真实页码应该用 pageIdx（数组索引）+ 1
                int pageNum = pageIdx + 1;

                // 根据 type 处理
                switch (type) {
                    case "title", "paragraph" -> {
                        // 保存上一个 section
                        if (currentSectionContent.length() > 0) {
                            List<TextChunk> sectionChunks = splitSectionText(
                                    currentSectionContent.toString(),
                                    currentSectionPath,
                                    isKeyClause ? MAX_TOKEN_KEY_CLAUSE : MAX_TOKEN_NORMAL,
                                    chunkId,
                                    currentPage
                            );
                            chunks.addAll(sectionChunks);
                            chunkId = sectionChunks.isEmpty() ? chunkId : sectionChunks.get(sectionChunks.size() - 1).getChunkId() + 1;
                            if (!sectionChunks.isEmpty()) {
                                currentPage = sectionChunks.get(sectionChunks.size() - 1).getPageNumber() + 1;
                            }
                            currentSectionContent = new StringBuilder();
                        }

                        // 处理新标题
                        if ("title".equals(type)) {
                            int level = contentObj.path("level").asInt(1);
                            currentSectionPath = buildSectionPath(currentSectionPath, text, level);
                            isKeyClause = isKeyClauseTitle(text);
                        }

                        // paragraph 添加到当前 section
                        if ("paragraph".equals(type)) {
                            if (currentSectionContent.length() > 0) {
                                currentSectionContent.append("\n\n");
                            }
                            currentSectionContent.append(text);
                        }
                    }
                    case "table" -> {
                        // 保存当前 section
                        if (currentSectionContent.length() > 0) {
                            List<TextChunk> sectionChunks = splitSectionText(
                                    currentSectionContent.toString(),
                                    currentSectionPath,
                                    isKeyClause ? MAX_TOKEN_KEY_CLAUSE : MAX_TOKEN_NORMAL,
                                    chunkId,
                                    currentPage
                            );
                            chunks.addAll(sectionChunks);
                            chunkId = sectionChunks.isEmpty() ? chunkId : sectionChunks.get(sectionChunks.size() - 1).getChunkId() + 1;
                            if (!sectionChunks.isEmpty()) {
                                currentPage = sectionChunks.get(sectionChunks.size() - 1).getPageNumber() + 1;
                            }
                            currentSectionContent = new StringBuilder();
                        }

                        // 处理表格
                        List<TextChunk> tableChunks = parseTableChunkV2(contentObj, currentSectionPath, chunkId, pageNum);
                        chunks.addAll(tableChunks);
                        if (!tableChunks.isEmpty()) {
                            chunkId = tableChunks.get(tableChunks.size() - 1).getChunkId() + 1;
                        }
                    }
                    case "list" -> {
                        // 保存当前 section
                        if (currentSectionContent.length() > 0) {
                            List<TextChunk> sectionChunks = splitSectionText(
                                    currentSectionContent.toString(),
                                    currentSectionPath,
                                    isKeyClause ? MAX_TOKEN_KEY_CLAUSE : MAX_TOKEN_NORMAL,
                                    chunkId,
                                    currentPage
                            );
                            chunks.addAll(sectionChunks);
                            chunkId = sectionChunks.isEmpty() ? chunkId : sectionChunks.get(sectionChunks.size() - 1).getChunkId() + 1;
                            if (!sectionChunks.isEmpty()) {
                                currentPage = sectionChunks.get(sectionChunks.size() - 1).getPageNumber() + 1;
                            }
                            currentSectionContent = new StringBuilder();
                        }

                        // 处理列表
                        List<TextChunk> listChunks = parseListChunkV2(block, contentObj, currentSectionPath, chunkId, pageNum);
                        chunks.addAll(listChunks);
                        if (!listChunks.isEmpty()) {
                            chunkId = listChunks.get(listChunks.size() - 1).getChunkId() + 1;
                        }
                    }
                    default -> {
                        // 其他类型（如 equation_interline, code 等），添加到当前 section
                        if (currentSectionContent.length() > 0) {
                            currentSectionContent.append("\n\n");
                        }
                        currentSectionContent.append(text);
                    }
                }
            }
        }

        // 处理最后一个 section
        if (currentSectionContent.length() > 0) {
            List<TextChunk> sectionChunks = splitSectionText(
                    currentSectionContent.toString(),
                    currentSectionPath,
                    isKeyClause ? MAX_TOKEN_KEY_CLAUSE : MAX_TOKEN_NORMAL,
                    chunkId,
                    currentPage
            );
            chunks.addAll(sectionChunks);
        }

        // 串联 prev/next
        linkNeighboringChunks(chunks);

        log.info("[MinerU] V3 切分完成，共 {} 个 chunks", chunks.size());
        return chunks;
    }

    /**
     * 从 V2 块中提取文本内容
     * V2 content 是对象，格式如：{"title_content": [...], "level": 1}
     */
    private String extractTextFromV2Block(String type, JsonNode contentObj) {
        if (contentObj.isMissingNode() || contentObj.isNull()) {
            return "";
        }

        switch (type) {
            case "title" -> {
                // {"title_content": [{"type": "text", "content": "..."}], "level": 1}
                JsonNode titleContent = contentObj.path("title_content");
                if (titleContent.isArray() && titleContent.size() > 0) {
                    return titleContent.get(0).path("content").asText("");
                }
            }
            case "paragraph" -> {
                // {"paragraph_content": [{"type": "text", "content": "..."}]}
                JsonNode paraContent = contentObj.path("paragraph_content");
                if (paraContent.isArray() && paraContent.size() > 0) {
                    return paraContent.get(0).path("content").asText("");
                }
            }
            case "equation_interline" -> {
                // {"equation_interline_content": [{"type": "text", "content": "..."}]}
                JsonNode eqContent = contentObj.path("equation_interline_content");
                if (eqContent.isArray() && eqContent.size() > 0) {
                    return "[公式] " + eqContent.get(0).path("content").asText("");
                }
            }
            case "list" -> {
                // {"list_items": [{"type": "text", "content": "..."}]}
                StringBuilder sb = new StringBuilder();
                JsonNode listItems = contentObj.path("list_items");
                if (listItems.isArray()) {
                    for (JsonNode item : listItems) {
                        String itemText = item.path("content").asText("");
                        if (!itemText.isBlank()) {
                            sb.append(itemText).append("\n");
                        }
                    }
                }
                return sb.toString().trim();
            }
            case "table" -> {
                // table 不在这里处理，返回空，调用方会调用 parseTableChunkV2
                return "";
            }
            case "image", "chart" -> {
                // 图片/图表说明
                JsonNode caption = contentObj.path("image_caption");
                if (caption.isMissingNode()) caption = contentObj.path("chart_caption");
                if (caption.isArray() && caption.size() > 0) {
                    return "[图片] " + caption.get(0).asText("");
                }
            }
            case "code", "algorithm" -> {
                JsonNode codeBody = contentObj.path("code_body");
                if (codeBody.isMissingNode()) codeBody = contentObj.path("algorithm_body");
                if (!codeBody.isMissingNode() && !codeBody.isNull()) {
                    return "[代码] " + codeBody.asText("");
                }
            }
        }
        return "";
    }

    /**
     * V2 表格解析
     * V3: 小表格(≤300 token)整体为1个chunk，大表格按行切分每行带表头
     *
     * 修复记录:
     * 1. HTML 表格转为结构化文本，避免 embedding 噪声
     * 2. caption 取所有元素拼接，而非只取第一个
     * 3. 跳过 <table> 垃圾元素，正确识别表头行
     * 4. 缺失 caption 时使用 sectionPath 作为语义锚点
     */
    private List<TextChunk> parseTableChunkV2(JsonNode contentObj, String sectionPath, int startChunkId, int startPage) {
        List<TextChunk> chunks = new ArrayList<>();
        try {
            // 表格内容在 html 字段 (HTML 格式)
            // V2 JSON 结构: {"type": "table", "content": {"html": "<table>...</table>", "table_caption": [...], ...}}
            String tableBody = contentObj.path("html").asText("");
            if (tableBody.isBlank()) {
                log.debug("[MinerU] V2 表格 html 为空，跳过");
                return chunks;
            }

            // table_caption 是数组，格式: [{"type": "text", "content": "..."}]
            // 修复: 取所有 caption 元素拼接，而非只取第一个
            JsonNode captionNode = contentObj.path("table_caption");
            StringBuilder captionBuilder = new StringBuilder();
            if (captionNode.isArray()) {
                for (JsonNode cn : captionNode) {
                    String text = cn.path("content").asText("");
                    if (!text.isBlank()) {
                        if (captionBuilder.length() > 0) {
                            captionBuilder.append(" / ");
                        }
                        captionBuilder.append(text);
                    }
                }
            }
            String tableCaption = captionBuilder.toString();
            log.info("[MinerU] V2 表格解析: caption=\"{}\", html 长度={}", tableCaption, tableBody.length());

            // 估算 token 数
            int totalTokens = estimateTokens(tableBody);
            log.debug("[MinerU] V2 表格总 token 数: {}", totalTokens);

            // 将 HTML 表格转为结构化文本
            // 格式: "行名 | 2022 | 2023E | 2024E | 2025E"
            String structuredTable = convertHtmlTableToText(tableBody, tableCaption);
            if (structuredTable == null || structuredTable.isBlank()) {
                log.warn("[MinerU] V2 表格结构化失败，使用原始 HTML");
                structuredTable = tableBody;
            }

            // 小表格整体作为一个 chunk
            if (totalTokens <= MAX_TOKEN_TABLE) {
                // 修复: 缺失 caption 时使用 sectionPath 作为语义锚点
                String anchorText = !tableCaption.isBlank() ? tableCaption
                    : (!sectionPath.isBlank() ? sectionPath : "数据表");
                String chunkText = anchorText + "\n" + structuredTable;
                int chunkTokens = estimateTokens(chunkText);
                TextChunk chunk = new TextChunk(startChunkId, chunkText, startPage, sectionPath, "table", false, chunkTokens);
                chunks.add(chunk);
                log.debug("[MinerU] V2 小表格(结构化)整体作为1个chunk, anchor=\"{}\"", anchorText);
                return chunks;
            }

            // 大表格按行切分
            String[] rows = structuredTable.split("\n");
            log.debug("[MinerU] V2 大表格结构化后共 {} 行", rows.length);

            if (rows.length <= 2) {
                String anchorText = !tableCaption.isBlank() ? tableCaption : (!sectionPath.isBlank() ? sectionPath : "数据表");
                String chunkText = anchorText + "\n" + structuredTable;
                int chunkTokens = estimateTokens(chunkText);
                TextChunk chunk = new TextChunk(startChunkId, chunkText, startPage, sectionPath, "table", false, chunkTokens);
                chunks.add(chunk);
                return chunks;
            }

            // 取前2行作为表头
            String header = rows[0] + "\n" + rows[1];
            int headerTokens = estimateTokens(header);
            log.debug("[MinerU] V2 表格表头 token 数: {}, 表头内容: {}", headerTokens, header);

            List<String> dataRows = new ArrayList<>();
            for (int i = 2; i < rows.length; i++) {
                dataRows.add(rows[i]);
            }
            log.debug("[MinerU] V2 表格数据行数: {}", dataRows.size());

            List<String> currentRows = new ArrayList<>();
            int currentTokens = headerTokens;
            int page = startPage;
            int chunkId = startChunkId;

            for (String row : dataRows) {
                int rowTokens = estimateTokens(row);
                if (currentTokens + rowTokens > MAX_TOKEN_TABLE && !currentRows.isEmpty()) {
                    String chunkText = header + "\n" + String.join("\n", currentRows);
                    int chunkTokens = estimateTokens(chunkText);
                    TextChunk chunk = new TextChunk(chunkId++, chunkText, page, sectionPath, "table", false, chunkTokens);
                    chunks.add(chunk);
                    page++;
                    currentRows.clear();
                    currentTokens = headerTokens;
                }
                currentRows.add(row);
                currentTokens += rowTokens;
            }

            if (!currentRows.isEmpty()) {
                String chunkText = header + "\n" + String.join("\n", currentRows);
                int chunkTokens = estimateTokens(chunkText);
                TextChunk chunk = new TextChunk(chunkId, chunkText, page, sectionPath, "table", false, chunkTokens);
                chunks.add(chunk);
            }

            // 记录表格 chunk 详情
            log.info("[MinerU] V2 表格切分完成，共 {} 个 chunks", chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                TextChunk c = chunks.get(i);
                String preview = c.getContent().length() > 100 ? c.getContent().substring(0, 100) + "..." : c.getContent();
                log.info("[MinerU]   chunk[{}]: page={}, tokens={}, preview=\"{}\"",
                        i, c.getPageNumber(), c.getTokenCount(), preview.replace("\n", "\\n"));
            }

        } catch (Exception e) {
            log.warn("[MinerU] V2 表格解析失败: {}", e.getMessage());
        }
        return chunks;
    }

    /**
     * 将 HTML 表格转换为结构化文本
     * 格式: "行名 | 2022 | 2023E | 2024E | 2025E"
     *
     * 修复 HTML 噪声问题:
     * - 原始 HTML <table><tr><td>...</td>...</tr></table> 包含大量标签噪声
     * - embedding 模型会把 <td> 和 </td> 当成语义内容
     * - 生成模型容易在长 HTML 中间串行
     *
     * 使用正则表达式解析，不引入额外依赖
     */
    private String convertHtmlTableToText(String html, String caption) {
        if (html == null || html.isBlank()) {
            return null;
        }

        try {
            StringBuilder sb = new StringBuilder();
            // 如果有 caption，作为表名注释
            if (!caption.isBlank()) {
                sb.append("【").append(caption).append("】\n");
            }

            // 提取所有 <tr>...</tr> 行
            // 处理 <tr>xxx</tr> 或嵌套的 <tr>xxx<tr>xxx</tr>yyy</tr> 情况
            Pattern rowPattern = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher rowMatcher = rowPattern.matcher(html);

            while (rowMatcher.find()) {
                String rowContent = rowMatcher.group(1);

                // 提取单元格内容 <td>xxx</td> 或 <th>xxx</th>
                Pattern cellPattern = Pattern.compile("<t[hd][^>]*>(.*?)</t[hd]>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
                Matcher cellMatcher = cellPattern.matcher(rowContent);

                List<String> cells = new ArrayList<>();
                while (cellMatcher.find()) {
                    // 清理单元格文本：移除 HTML 标签、多余空白
                    String cellText = cellMatcher.group(1)
                            .replaceAll("<[^>]+>", "")  // 移除所有 HTML 标签
                            .replaceAll("\\s+", " ")     // 合并多余空白
                            .trim();
                    cells.add(cellText);
                }

                if (!cells.isEmpty()) {
                    // 使用 | 分隔，格式: "指标名 | 值1 | 值2 | ..."
                    sb.append(String.join(" | ", cells));
                    sb.append("\n");
                }
            }

            String result = sb.toString().trim();
            if (result.isBlank()) {
                log.warn("[MinerU] HTML 表格解析后为空，caption={}", caption);
                return null;
            }

            log.debug("[MinerU] HTML 表格结构化成功，caption={}, 行数={}", caption,
                    result.split("\n").length);
            return result;

        } catch (Exception e) {
            log.warn("[MinerU] HTML 表格结构化失败: {}, 原始前200字符: {}",
                    e.getMessage(),
                    html.length() > 200 ? html.substring(0, 200) : html);
            return null;
        }
    }

    /**
     * V2 列表解析
     * V3: 前导句 + 列表项合并，过长则保留前导句拆分
     * @param block 完整 block 节点，用于提取 lead 字段
     * @param contentObj block.content 节点，包含 list_items
     */
    private List<TextChunk> parseListChunkV2(JsonNode block, JsonNode contentObj, String sectionPath, int startChunkId, int startPage) {
        List<TextChunk> chunks = new ArrayList<>();

        // V2 列表结构: {"list_items": [...], "lead": "前导句"}，lead 在 block 级别，不在 content 中
        JsonNode listItems = contentObj.path("list_items");
        if (!listItems.isArray() || listItems.isEmpty()) {
            return chunks;
        }

        // 提取前导句 - lead 在 block 级别
        String lead = block.path("lead").asText("");
        if (lead.isBlank()) {
            // 尝试从 content 字段获取
            lead = contentObj.path("content").asText("");
            // 如果 content 看起来不像前导句，清空它
            if (lead.length() > 100 || !lead.contains("：") && !lead.contains(":")) {
                lead = "";
            }
        }

        // 构建完整列表文本 (前导句 + 列表项)
        StringBuilder fullList = new StringBuilder();
        if (!lead.isBlank()) {
            fullList.append(lead).append("\n");
        }
        for (JsonNode item : listItems) {
            String itemText = item.path("content").asText("");
            if (!itemText.isBlank()) {
                fullList.append(itemText).append("\n");
            }
        }

        String listText = fullList.toString().trim();
        if (listText.isBlank()) {
            return chunks;
        }

        int totalTokens = estimateTokens(listText);
        int leadTokens = lead.isBlank() ? 0 : estimateTokens(lead);

        // 小列表整体作为一个 chunk
        if (totalTokens <= MAX_TOKEN_NORMAL) {
            TextChunk chunk = new TextChunk(startChunkId, listText, startPage, sectionPath, "list", false, totalTokens);
            chunks.add(chunk);
            return chunks;
        }

        // 大列表按项目拆分，每个 chunk 必须保留前导句
        int chunkId = startChunkId;
        int page = startPage;
        List<String> currentItems = new ArrayList<>();
        int currentTokens = leadTokens; // 从前导句的 token 数开始

        for (JsonNode item : listItems) {
            String itemText = item.path("content").asText("");
            if (itemText.isBlank()) continue;

            int itemTokens = estimateTokens(itemText);

            // 如果加上这个 item 会超过限制
            if (currentTokens + itemTokens > MAX_TOKEN_NORMAL && !currentItems.isEmpty()) {
                // 保存当前 chunk，包含前导句
                String chunkText = buildListChunkText(lead, currentItems);
                int chunkTokens = leadTokens + currentItems.stream().mapToInt(this::estimateTokens).sum();
                TextChunk chunk = new TextChunk(chunkId++, chunkText, page, sectionPath, "list", false, chunkTokens);
                chunks.add(chunk);
                page++;
                currentItems.clear();
                currentTokens = leadTokens; // 重置时也要包含前导句 token
            }

            currentItems.add(itemText);
            currentTokens += itemTokens;
        }

        // 保存最后一个 chunk
        if (!currentItems.isEmpty()) {
            String chunkText = buildListChunkText(lead, currentItems);
            int chunkTokens = leadTokens + currentItems.stream().mapToInt(this::estimateTokens).sum();
            TextChunk chunk = new TextChunk(chunkId, chunkText, page, sectionPath, "list", false, chunkTokens);
            chunks.add(chunk);
        }

        return chunks;
    }

    /**
     * 解析表格 chunk
     * V3: 小表格(≤300 token)整体为1个chunk，大表格按行切分每行带表头
     */
    private List<TextChunk> parseTableChunk(JsonNode item, String sectionPath, int startChunkId, int startPage) {
        List<TextChunk> chunks = new ArrayList<>();

        try {
            List<String> rows = new ArrayList<>();
            // 尝试多个可能的字段名
            JsonNode rowsNode = item.path("rows");
            if (!rowsNode.isArray()) rowsNode = item.path("data");
            if (!rowsNode.isArray()) rowsNode = item.path("content");
            if (rowsNode.isArray()) {
                for (JsonNode row : rowsNode) {
                    rows.add(row.asText());
                }
            }

            if (rows.isEmpty()) {
                log.debug("[MinerU] 表格解析失败: rows 为空");
                return chunks;
            }

            // 前2行作为表头
            int headerRowCount = Math.min(2, rows.size());
            List<String> headerRows = rows.subList(0, headerRowCount);
            String headerText = String.join("\n", headerRows);
            int headerTokens = estimateTokens(headerText);

            // 如果表格很小，整体作为一个 chunk
            int totalTokens = estimateTokens(String.join("\n", rows));
            if (totalTokens <= MAX_TOKEN_TABLE) {
                String chunkText = String.join("\n", rows);
                TextChunk chunk = new TextChunk(
                        startChunkId,
                        chunkText,
                        startPage,
                        sectionPath,
                        "table",
                        false,
                        totalTokens
                );
                chunks.add(chunk);
                return chunks;
            }

            // 大表格按行切分，每行带表头
            List<String> dataRows = rows.subList(headerRowCount, rows.size());
            List<String> currentRows = new ArrayList<>();
            int currentTokens = headerTokens;
            int page = startPage;
            int chunkId = startChunkId;

            for (String row : dataRows) {
                int rowTokens = estimateTokens(row);
                if (currentTokens + rowTokens > MAX_TOKEN_TABLE && !currentRows.isEmpty()) {
                    // 保存当前 chunk，包含表头
                    String chunkText = headerText + "\n" + String.join("\n", currentRows);
                    TextChunk chunk = new TextChunk(chunkId++, chunkText, page, sectionPath, "table", false, currentTokens);
                    chunks.add(chunk);
                    page++;
                    currentRows.clear();
                    currentTokens = headerTokens;
                }
                currentRows.add(row);
                currentTokens += rowTokens;
            }

            // 保存最后一个 chunk
            if (!currentRows.isEmpty()) {
                String chunkText = headerText + "\n" + String.join("\n", currentRows);
                TextChunk chunk = new TextChunk(chunkId, chunkText, page, sectionPath, "table", false, currentTokens);
                chunks.add(chunk);
            }

        } catch (Exception e) {
            log.warn("[MinerU] 解析表格失败: {}", e.getMessage());
        }

        return chunks;
    }

    /**
     * 解析列表 chunk
     * V3: 前导句 + 列表项合并，过长则保留前导句拆分
     */
    private List<TextChunk> parseListChunk(JsonNode item, String sectionPath, int startChunkId, int startPage) {
        List<TextChunk> chunks = new ArrayList<>();

        // 尝试多个可能的字段名
        String lead = item.path("lead").asText("");
        if (lead.isBlank()) lead = item.path("heading").asText("");
        if (lead.isBlank()) lead = item.path("content").asText("");

        JsonNode itemsNode = item.path("items");
        if (!itemsNode.isArray()) itemsNode = item.path("list");
        if (!itemsNode.isArray()) itemsNode = item.path("content");
        List<String> items = new ArrayList<>();
        if (itemsNode.isArray()) {
            for (JsonNode itemNode : itemsNode) {
                items.add(itemNode.asText());
            }
        }

        // 如果 items 也为空，可能是纯文本内容
        if (items.isEmpty()) {
            String textContent = item.path("content").asText("");
            if (textContent.isBlank()) textContent = item.path("text").asText("");
            if (!textContent.isBlank()) {
                // 整个 item 作为 text 类型处理
                TextChunk chunk = new TextChunk(startChunkId, textContent, startPage, sectionPath, "text", false, estimateTokens(textContent));
                chunks.add(chunk);
                return chunks;
            }
            return chunks;
        }

        // 构建完整列表文本
        StringBuilder fullList = new StringBuilder();
        if (!lead.isBlank()) {
            fullList.append(lead).append("\n");
        }
        for (String listItem : items) {
            fullList.append(listItem).append("\n");
        }

        String listText = fullList.toString().trim();
        int totalTokens = estimateTokens(listText);

        // 如果列表很小，整体作为一个 chunk
        if (totalTokens <= MAX_TOKEN_NORMAL) {
            TextChunk chunk = new TextChunk(startChunkId, listText, startPage, sectionPath, "list", false, totalTokens);
            chunks.add(chunk);
            return chunks;
        }

        // 大列表：前导句 + 部分列表项，过长则拆分但保留前导句
        int chunkId = startChunkId;
        int page = startPage;
        List<String> currentItems = new ArrayList<>();
        int currentTokens = estimateTokens(lead);
        boolean leadAdded = false;

        for (String listItem : items) {
            int itemTokens = estimateTokens(listItem);

            // 如果加上这个item会超过限制
            if (currentTokens + itemTokens > MAX_TOKEN_NORMAL && !currentItems.isEmpty()) {
                // 保存当前 chunk
                String chunkText = buildListChunkText(lead, currentItems);
                TextChunk chunk = new TextChunk(chunkId++, chunkText, page, sectionPath, "list", false, currentTokens);
                chunks.add(chunk);
                page++;
                currentItems.clear();
                currentTokens = estimateTokens(lead);
                leadAdded = false;
            }

            currentItems.add(listItem);
            currentTokens += itemTokens;
        }

        // 保存最后一个 chunk
        if (!currentItems.isEmpty()) {
            String chunkText = buildListChunkText(lead, currentItems);
            TextChunk chunk = new TextChunk(chunkId, chunkText, page, sectionPath, "list", false, currentTokens);
            chunks.add(chunk);
        }

        return chunks;
    }

    /**
     * 构建列表 chunk 文本
     */
    private String buildListChunkText(String lead, List<String> items) {
        StringBuilder sb = new StringBuilder();
        if (!lead.isBlank()) {
            sb.append(lead).append("\n");
        }
        for (String item : items) {
            sb.append(item).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 按 section 切分文本，超长则递归细切
     */
    private List<TextChunk> splitSectionText(String text, String sectionPath, int maxTokens, int startChunkId, int startPage) {
        List<TextChunk> chunks = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return chunks;
        }

        int totalTokens = estimateTokens(text);

        // 如果在限制内，直接返回
        if (totalTokens <= maxTokens) {
            TextChunk chunk = new TextChunk(startChunkId, text.trim(), startPage, sectionPath, "text", isKeyClauseText(text), totalTokens);
            chunks.add(chunk);
            return chunks;
        }

        // 超长 section，尝试按段落/子标题细分
        String[] parts = text.split("\n(?=#)");
        if (parts.length > 1) {
            // 有子标题，递归处理每个子部分
            int chunkId = startChunkId;
            int page = startPage;
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) continue;

                List<TextChunk> subChunks = splitSectionText(part, sectionPath, maxTokens, chunkId, page);
                chunks.addAll(subChunks);
                if (!subChunks.isEmpty()) {
                    chunkId = subChunks.get(subChunks.size() - 1).getChunkId() + 1;
                    page = subChunks.get(subChunks.size() - 1).getPageNumber() + 1;
                }
            }
            return chunks;
        }

        // 没有子标题，按句子级切分
        return sentenceAwareSplit(text, sectionPath, maxTokens, startChunkId, startPage);
    }

    /**
     * 句子级切分，避免在句子中间截断
     */
    private List<TextChunk> sentenceAwareSplit(String text, String sectionPath, int maxTokens, int startChunkId, int startPage) {
        List<TextChunk> chunks = new ArrayList<>();

        // 按句子分割
        String[] sentences = text.split("(?<=[。！？；])|(?<=[.!?;])\\s+");

        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;
        int chunkId = startChunkId;
        int page = startPage;

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;

            int sentenceTokens = estimateTokens(sentence);

            // 如果单个句子就超过限制，按词切
            if (sentenceTokens > maxTokens) {
                if (currentChunk.length() > 0) {
                    chunks.add(new TextChunk(chunkId++, currentChunk.toString().trim(), page, sectionPath, "text", isKeyClauseText(currentChunk.toString()), currentTokens));
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                    page++;
                }
                // 按词分割超长句子
                chunks.addAll(splitLongSentence(sentence, sectionPath, maxTokens, chunkId, page));
                if (!chunks.isEmpty()) {
                    chunkId = chunks.get(chunks.size() - 1).getChunkId() + 1;
                    page = chunks.get(chunks.size() - 1).getPageNumber() + 1;
                }
                continue;
            }

            // 如果添加这个句子会超过限制
            if (currentTokens + sentenceTokens > maxTokens && currentChunk.length() > 0) {
                chunks.add(new TextChunk(chunkId++, currentChunk.toString().trim(), page, sectionPath, "text", isKeyClauseText(currentChunk.toString()), currentTokens));
                currentChunk = new StringBuilder();
                currentTokens = 0;
                page++;
            }

            if (currentChunk.length() > 0) {
                currentChunk.append(" ");
            }
            currentChunk.append(sentence);
            currentTokens += sentenceTokens;
        }

        // 保存最后一个 chunk
        if (currentChunk.length() > 0) {
            chunks.add(new TextChunk(chunkId, currentChunk.toString().trim(), page, sectionPath, "text", isKeyClauseText(currentChunk.toString()), currentTokens));
        }

        return chunks;
    }

    /**
     * 分割超长句子
     */
    private List<TextChunk> splitLongSentence(String sentence, String sectionPath, int maxTokens, int startChunkId, int startPage) {
        List<TextChunk> chunks = new ArrayList<>();

        // 按标点或常见分隔符切分
        String[] parts = sentence.split("(?<=[,，、:：;；])");
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;
        int chunkId = startChunkId;
        int page = startPage;

        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            int partTokens = estimateTokens(part);

            if (currentTokens + partTokens > maxTokens && currentChunk.length() > 0) {
                chunks.add(new TextChunk(chunkId++, currentChunk.toString().trim(), page, sectionPath, "text", false, currentTokens));
                currentChunk = new StringBuilder();
                currentTokens = 0;
                page++;
            }

            if (currentChunk.length() > 0) {
                currentChunk.append(" ");
            }
            currentChunk.append(part);
            currentTokens += partTokens;
        }

        if (currentChunk.length() > 0) {
            chunks.add(new TextChunk(chunkId, currentChunk.toString().trim(), page, sectionPath, "text", false, currentTokens));
        }

        return chunks;
    }

    /**
     * 构建层级路径
     */
    private String buildSectionPath(String parentPath, String currentTitle, int level) {
        if (currentTitle == null || currentTitle.isBlank()) {
            return parentPath;
        }
        if (parentPath.isEmpty()) {
            return currentTitle;
        }
        // 根据 level 决定是替换同级别还是追加
        return parentPath + " > " + currentTitle;
    }

    /**
     * 根据标题判断是否关键条款
     */
    private boolean isKeyClauseTitle(String title) {
        if (title == null) return false;
        for (String keyword : KEY_CLAUSE_KEYWORDS) {
            if (title.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据文本内容判断是否关键条款
     */
    private boolean isKeyClauseText(String text) {
        if (text == null) return false;
        int matchCount = 0;
        for (String keyword : KEY_CLAUSE_KEYWORDS) {
            if (text.contains(keyword)) {
                matchCount++;
            }
        }
        // 文本中出现多个关键词才认为是关键条款
        return matchCount >= 2;
    }

    /**
     * 估算 token 数（中文按字符估算，英文按单词估算）
     */
    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        // 简单估算：中文每个字符约1个token，英文每个单词约1.5个token
        int chineseChars = (int) text.chars().filter(c -> c >= 0x4E00 && c <= 0x9FA5).count();
        int englishWords = text.split("[\\u4E00-\\u9FA5]").length;
        return chineseChars + (int) (englishWords * 1.5);
    }

    /**
     * 串联相邻 chunks 的 prev/next
     */
    private void linkNeighboringChunks(List<TextChunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            if (i > 0) {
                chunk.setPrevChunkId(String.valueOf(chunks.get(i - 1).getChunkId()));
            }
            if (i < chunks.size() - 1) {
                chunk.setNextChunkId(String.valueOf(chunks.get(i + 1).getChunkId()));
            }
        }
    }

    /**
     * 使用 fullMd 降级解析（当 content_list_v2.json 不可用时）
     */
    private List<TextChunk> parseMarkdownFallback(String fullMd) {
        if (fullMd == null || fullMd.isBlank()) {
            log.warn("[MinerU] fullMd 为空，无法降级解析");
            return new ArrayList<>();
        }
        log.debug("[MinerU] 使用 fullMd 降级解析，fullMd 长度: {}", fullMd.length());
        List<TextChunk> result = parseMarkdownToChunks(fullMd);
        log.debug("[MinerU] fullMd 降级解析完成，生成 {} 个 chunks", result.size());
        return result;
    }

    /**
     * 申请上传链接结果
     */
    public static class BatchApplyResult {
        private final String batchId;
        private final String uploadUrl;

        public BatchApplyResult(String batchId, String uploadUrl) {
            this.batchId = batchId;
            this.uploadUrl = uploadUrl;
        }

        public String getBatchId() { return batchId; }
        public String getUploadUrl() { return uploadUrl; }
    }

    /**
     * MinerU 解析结果
     */
    public static class MinerUParseResult {
        private String fullMd;
        private String contentJson;
        private String layoutJson;
        private String mineruBatchId;
        private String parseStatus;
        private String parseError;
        private List<TextChunk> chunks;

        // Getters
        public String getFullMd() { return fullMd; }
        public String getContentJson() { return contentJson; }
        public String getLayoutJson() { return layoutJson; }
        public String getMineruBatchId() { return mineruBatchId; }
        public String getParseStatus() { return parseStatus; }
        public String getParseError() { return parseError; }
        public List<TextChunk> getChunks() { return chunks; }

        // Setters
        public void setFullMd(String fullMd) { this.fullMd = fullMd; }
        public void setContentJson(String contentJson) { this.contentJson = contentJson; }
        public void setLayoutJson(String layoutJson) { this.layoutJson = layoutJson; }
        public void setMineruBatchId(String mineruBatchId) { this.mineruBatchId = mineruBatchId; }
        public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
        public void setParseError(String parseError) { this.parseError = parseError; }
        public void setChunks(List<TextChunk> chunks) { this.chunks = chunks; }
    }

    /**
     * V3 文本块，包含丰富的 metadata
     */
    public static class TextChunk {
        private int chunkId;
        private String content;
        private int pageNumber;
        private String heading;
        private String sectionPath;
        private String chunkType;
        private boolean isKeyClause;
        private int tokenCount;
        private String prevChunkId;
        private String nextChunkId;

        public TextChunk(int chunkId, String content, int pageNumber, String heading) {
            this.chunkId = chunkId;
            this.content = content;
            this.pageNumber = pageNumber;
            this.heading = heading;
            this.sectionPath = null;
            this.chunkType = "text";
            this.isKeyClause = false;
            this.tokenCount = estimateTokensStatic(content);
        }

        public TextChunk(int chunkId, String content, int pageNumber, String sectionPath,
                         String chunkType, boolean isKeyClause, int tokenCount) {
            this.chunkId = chunkId;
            this.content = content;
            this.pageNumber = pageNumber;
            this.heading = sectionPath;
            this.sectionPath = sectionPath;
            this.chunkType = chunkType != null ? chunkType : "text";
            this.isKeyClause = isKeyClause;
            this.tokenCount = tokenCount;
        }

        // Getters
        public int getChunkId() { return chunkId; }
        public String getContent() { return content; }
        public int getPageNumber() { return pageNumber; }
        public String getHeading() { return heading; }
        public String getSectionPath() { return sectionPath; }
        public String getChunkType() { return chunkType; }
        public boolean isKeyClause() { return isKeyClause; }
        public int getTokenCount() { return tokenCount; }
        public String getPrevChunkId() { return prevChunkId; }
        public String getNextChunkId() { return nextChunkId; }

        // Setters
        public void setChunkId(int chunkId) { this.chunkId = chunkId; }
        public void setContent(String content) { this.content = content; }
        public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
        public void setHeading(String heading) { this.heading = heading; }
        public void setSectionPath(String sectionPath) { this.sectionPath = sectionPath; }
        public void setChunkType(String chunkType) { this.chunkType = chunkType; }
        public void setKeyClause(boolean keyClause) { isKeyClause = keyClause; }
        public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }
        public void setPrevChunkId(String prevChunkId) { this.prevChunkId = prevChunkId; }
        public void setNextChunkId(String nextChunkId) { this.nextChunkId = nextChunkId; }

        public String getAnchorText() {
            if (content == null) return "";
            String text = content.replace("\n", " ").trim();
            if (text.length() <= 120) return text;
            return text.substring(0, 120);
        }

        private static int estimateTokensStatic(String text) {
            if (text == null || text.isBlank()) return 0;
            int chineseChars = (int) text.chars().filter(c -> c >= 0x4E00 && c <= 0x9FA5).count();
            int englishWords = text.split("[\u4E00-\u9FA5]").length;
            return chineseChars + (int) (englishWords * 1.5);
        }
    }

}
