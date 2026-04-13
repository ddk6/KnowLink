package com.yizhaoqi.smartpai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * MinerU API 简单验证 Demo
 * 用于测试 API 是否可用，不涉及项目代码修改
 */
public class MinerUApiDemo {

    private static final String BASE_URL = "https://mineru.net";
    private static final String TOKEN;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        // 从 application.yml 读取 MinerU token
        TOKEN = loadTokenFromConfig();
    }

    private static String loadTokenFromConfig() {
        try {
            Yaml yaml = new Yaml();
            // 读取项目根目录的 application.yml
            Path configPath = Path.of("d:/Project/PaiSmart-main/src/main/resources/application.yml");
            if (Files.exists(configPath)) {
                try (InputStream in = new FileInputStream(configPath.toFile())) {
                    Map<String, Object> config = yaml.load(in);
                    Map<String, Object> minerU = (Map<String, Object>) config.get("MinerU");
                    if (minerU != null) {
                        Map<String, Object> api = (Map<String, Object>) minerU.get("api");
                        if (api != null) {
                            String key = (String) api.get("key");
                            if (key != null && !key.isBlank()) {
                                return key;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("读取配置文件失败: " + e.getMessage());
        }
        // fallback to 环境变量
        String envToken = System.getenv("MINERU_TOKEN");
        if (envToken != null && !envToken.isBlank()) {
            return envToken;
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        if (TOKEN == null || TOKEN.isBlank()) {
            System.err.println("请在 application.yml 中设置 MinerU.api.key");
            System.err.println("或在环境变量中设置 MINERU_TOKEN");
            return;
        }

        System.out.println("=== MinerU API 验证 Demo ===");
        System.out.println("Token: " + TOKEN.substring(0, 20) + "...");

        MinerUApiDemo demo = new MinerUApiDemo();

        // ========== 测试 1: 上传本地文件 ==========
        System.out.println("\n========== 测试 1: 本地文件上传解析 ==========");
        Path pdfFile = Path.of("d:/Project/PaiSmart-main/anotherRagProject/RAG简历.pdf");

        if (!Files.exists(pdfFile)) {
            System.err.println("PDF 文件不存在: " + pdfFile);
            return;
        }

        System.out.println("PDF 文件: " + pdfFile);
        System.out.println("文件大小: " + Files.size(pdfFile) + " bytes");

        try {
            // Step 1: 申请上传链接
            System.out.println("\n[Step 1] 申请上传链接...");
            BatchApplyResult batchResult = demo.applyUploadUrl(pdfFile);
            System.out.println("batch_id: " + batchResult.batchId());
            System.out.println("upload_url: " + batchResult.uploadUrl());

            // Step 2: 上传文件
            System.out.println("\n[Step 2] 上传文件...");
            demo.uploadFile(batchResult.uploadUrl(), pdfFile);
            System.out.println("文件上传成功!");

            // Step 3: 等待解析完成
            System.out.println("\n[Step 3] 等待解析完成 (轮询中...)");
            String zipUrl = demo.waitForBatchDone(batchResult.batchId());

            if (zipUrl != null) {
                System.out.println("\n解析成功!");
                System.out.println("ZIP 下载链接: " + zipUrl);

                // Step 4: 下载 ZIP (可选)
                System.out.println("\n[Step 4] 下载 ZIP 结果...");
                Path zipPath = demo.downloadZip(zipUrl, Path.of("d:/Project/PaiSmart-main/download"));
                System.out.println("ZIP 已下载到: " + zipPath);
            }

        } catch (Exception e) {
            System.err.println("\n错误: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\n=== Demo 结束 ===");
    }

    /**
     * 申请上传链接
     */
    public BatchApplyResult applyUploadUrl(Path file) throws IOException, InterruptedException {
        String body = "{" +
                "\"files\": [{" +
                "\"name\": \"" + file.getFileName().toString() + "\"," +
                "\"data_id\": \"test-resume-001\"," +
                "\"is_ocr\": true" +
                "}]," +
                "\"model_version\": \"vlm\"," +
                "\"language\": \"ch\"," +
                "\"enable_table\": true," +
                "\"enable_formula\": true" +
                "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v4/file-urls/batch"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        System.out.println("申请上传链接响应状态: " + response.statusCode());
        System.out.println("响应内容: " + response.body());

        JsonNode root = objectMapper.readTree(response.body());
        int code = root.path("code").asInt(-1);
        if (response.statusCode() != 200 || code != 0) {
            throw new RuntimeException("申请上传链接失败: " + response.body());
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

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        System.out.println("上传文件响应状态: " + response.statusCode());
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            System.out.println("上传文件响应内容: " + response.body());
            throw new RuntimeException("上传文件失败，HTTP=" + response.statusCode());
        }
    }

    /**
     * 轮询等待批量任务完成
     */
    public String waitForBatchDone(String batchId) throws IOException, InterruptedException {
        int maxAttempts = 100;  // 最多轮询 100 次
        int intervalMs = 5000;  // 每 5 秒轮询一次

        for (int i = 0; i < maxAttempts; i++) {
            System.out.println("轮询第 " + (i + 1) + " 次...");

            JsonNode root = queryBatchResult(batchId);
            JsonNode array = root.path("data").path("extract_result");

            if (!array.isArray()) {
                throw new RuntimeException("响应结构异常，extract_result 不是数组: " + root);
            }

            if (array.isEmpty()) {
                System.out.println("  extract_result 为空，继续等待...");
                Thread.sleep(intervalMs);
                continue;
            }

            JsonNode item = array.get(0);
            String state = item.path("state").asText("");
            System.out.println("  状态: " + state);

            if ("done".equalsIgnoreCase(state)) {
                String zipUrl = item.path("full_zip_url").asText("");
                if (zipUrl.isBlank()) {
                    throw new RuntimeException("状态为 done，但 full_zip_url 为空: " + item);
                }
                return zipUrl;
            }

            if ("failed".equalsIgnoreCase(state)) {
                String errMsg = item.path("err_msg").asText("未知错误");
                throw new RuntimeException("解析失败: " + errMsg + ", detail=" + item);
            }

            Thread.sleep(intervalMs);
        }

        throw new RuntimeException("轮询超时，batchId=" + batchId);
    }

    /**
     * 查询批量任务结果
     */
    public JsonNode queryBatchResult(String batchId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v4/extract-results/batch/" + batchId))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        System.out.println("查询批量结果响应状态: " + response.statusCode());
        System.out.println("查询批量结果响应内容: " + response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException("查询批量结果失败，HTTP=" + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new RuntimeException("查询批量结果业务失败: " + response.body());
        }

        return root;
    }

    /**
     * 下载 ZIP 文件
     */
    public Path downloadZip(String zipUrl, Path targetDir) throws IOException, InterruptedException {
        // 创建目标目录
        Files.createDirectories(targetDir);

        String fileName = "mineru_result_" + System.currentTimeMillis() + ".zip";
        Path zipPath = targetDir.resolve(fileName);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(zipUrl))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<java.nio.file.Path> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofFile(zipPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE));

        System.out.println("下载响应状态: " + response.statusCode());
        System.out.println("文件大小: " + Files.size(zipPath) + " bytes");

        return zipPath;
    }

    /**
     * 单任务查询 (用于 URL 解析模式)
     */
    public JsonNode queryTask(String taskId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v4/extract/task/" + taskId))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("查询任务失败，HTTP=" + response.statusCode());
        }

        return objectMapper.readTree(response.body());
    }

    /**
     * 轮询等待单任务完成 (用于 URL 解析模式)
     */
    public String waitForTaskDone(String taskId) throws IOException, InterruptedException {
        int maxAttempts = 100;
        int intervalMs = 5000;

        for (int i = 0; i < maxAttempts; i++) {
            System.out.println("轮询任务第 " + (i + 1) + " 次...");

            JsonNode data = queryTask(taskId);
            String state = data.path("data").path("state").asText();
            System.out.println("  状态: " + state);

            if ("done".equals(state)) {
                return data.path("data").path("full_zip_url").asText();
            }
            if ("failed".equals(state)) {
                throw new RuntimeException("解析失败: " + data.path("data").path("err_msg").asText());
            }

            Thread.sleep(intervalMs);
        }

        throw new RuntimeException("轮询超时，taskId=" + taskId);
    }

    /**
     * 创建 URL 解析任务
     */
    public String createUrlTask(String fileUrl) throws IOException, InterruptedException {
        String body = "{" +
                "\"url\": \"" + fileUrl + "\"," +
                "\"model_version\": \"vlm\"," +
                "\"language\": \"ch\"," +
                "\"enable_table\": true," +
                "\"enable_formula\": true," +
                "\"is_ocr\": false" +
                "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v4/extract/task"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        System.out.println("创建任务响应状态: " + response.statusCode());
        System.out.println("响应内容: " + response.body());

        JsonNode root = objectMapper.readTree(response.body());
        int code = root.path("code").asInt(-1);
        if (response.statusCode() != 200 || code != 0) {
            throw new RuntimeException("创建任务失败: " + response.body());
        }

        return root.path("data").path("task_id").asText();
    }

    public record BatchApplyResult(String batchId, String uploadUrl) {}
}