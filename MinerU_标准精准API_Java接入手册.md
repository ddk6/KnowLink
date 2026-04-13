
# MinerU 标准精准 API（Token 模式）Java 接入手册

> 版本定位：基于 MinerU 官方公开文档整理，面向 **Java / Spring Boot / 普通 Java SE** 接入场景。  
> 目标：让阅读这份手册的人，能够独立完成 **单文件 URL 解析**、**本地文件上传解析**、**结果轮询与下载**、**错误排查**。  
> 安全说明：你提供的真实 Token **不会写入手册**。文中统一使用 `<YOUR_MINERU_TOKEN>` 占位，实际使用时在本地配置替换。

---

## 1. 先说结论

MinerU 的 **标准精准 API** 适合这些场景：

- 需要 **Token 鉴权**
- 需要处理 **复杂 PDF / Word / PPT / 图片 / HTML**
- 需要 **高质量结构化结果**
- 需要导出 **Zip 结果包**，其中通常包含 Markdown、JSON，以及可选的 docx / html / latex 导出结果
- 需要支持 **批量处理** 或 **本地文件上传**

标准精准 API 的核心不是“同步上传然后立刻返回结果”，而是：

1. **提交任务**
2. **拿到 task_id 或 batch_id**
3. **轮询查询状态**
4. **在 done 时下载 full_zip_url**

---

## 2. 标准精准 API 和轻量 API 的区别

本手册只讲 **标准精准 API**。你需要知道它和轻量 API 的差异，避免接错接口。

| 维度 | 标准精准 API | Agent 轻量 API |
|---|---|---|
| 是否需要 Token | 是 | 否 |
| 接口地址 | `/api/v4/extract/task`、`/api/v4/file-urls/batch` | `/api/v1/agent/parse/url`、`/api/v1/agent/parse/file` |
| 单文件大小限制 | 200MB | 10MB |
| 页数限制 | 600 页 | 20 页 |
| 批量 | 支持，最多 200 个 | 不支持 |
| 输出 | Zip 结果包 | Markdown 链接 |
| 适合 | 正式接入、复杂文档、批处理 | 快速试玩、轻量场景 |

如果你是 Java 项目正式落地，优先接 **标准精准 API**。

---

## 3. 官方约束与限制

### 3.1 文件能力范围

标准精准 API 支持：

- PDF
- 图片：png / jpg / jpeg / jp2 / webp / gif / bmp
- Word：doc / docx
- PPT：ppt / pptx
- HTML（但必须把 `model_version` 指定为 `MinerU-HTML`）

### 3.2 文件限制

- 单文件最大：**200MB**
- 单文件页数上限：**600 页**
- 批量单次最多：**200 个文件**

### 3.3 调用与频控

官方公开的限流策略里写明：

- **提交任务接口** 共用频控：**300 次/分钟**
- **查询任务结果接口** 共用频控：**1000 次/分钟**
- 单用户每天最多上传 **1 万个文件**
- 其中 HTML 文件每天最多 **100 个**

### 3.4 额外注意事项

- URL 解析接口 **不支持直接上传文件**
- 如果用远程 URL，官方提示 **GitHub、AWS 等国外 URL 可能超时**
- 如果你要传本地文件，应走 **先申请上传链接，再 PUT 上传文件** 的流程
- 上传链接有效期为 **24 小时**
- 上传文件时 **不要手动设置 Content-Type**
- 本地文件上传完成后，**系统会自动提交解析任务**，不需要你再次调用“提交解析任务”

---

## 4. 认证方式

每次调用标准精准 API，都要在请求头加：

```http
Authorization: Bearer <YOUR_MINERU_TOKEN>
Content-Type: application/json
```

建议不要把 Token 写死在源码里，推荐放在：

- 环境变量
- `application.yml`
- 外部配置中心
- CI/CD Secret

推荐配置示例：

```yaml
mineru:
  base-url: https://mineru.net
  token: ${MINERU_TOKEN}
```

---

## 5. 标准精准 API 的两条主流程

标准精准 API 实际上有两种主要接法：

### 流程 A：远程 URL 解析（最简单）

适合：

- 文件已经能通过公网 URL 下载
- 不想处理本地上传链路
- 单文件或外部对象存储文件

流程：

1. `POST /api/v4/extract/task`
2. 服务返回 `task_id`
3. 轮询 `GET /api/v4/extract/task/{task_id}`
4. 状态变成 `done` 后获取 `full_zip_url`
5. 下载 zip 并解压结果

### 流程 B：本地文件上传解析（更常见）

适合：

- 文件在本机、NAS、内部系统里
- 文件没有公网 URL
- 需要批量上传

流程：

1. `POST /api/v4/file-urls/batch` 申请上传地址
2. 拿到 `batch_id` 和一组 `file_urls`
3. 对每个 `file_url` 执行 `PUT` 上传原文件
4. 系统自动提交解析任务
5. 轮询批量结果接口
6. 成功后拿每个文件的 `full_zip_url`

---

## 6. URL 解析：接口说明

### 6.1 创建任务

**接口**

```http
POST https://mineru.net/api/v4/extract/task
```

**最小请求体**

```json
{
  "url": "https://your-domain.com/demo.pdf",
  "model_version": "vlm"
}
```

### 6.2 常用参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `url` | string | 是 | 文件公网 URL |
| `model_version` | string | 否 | `pipeline` / `vlm` / `MinerU-HTML`，默认 `pipeline` |
| `is_ocr` | bool | 否 | 是否启用 OCR，默认 false，仅 `pipeline`/`vlm` 有效 |
| `enable_formula` | bool | 否 | 是否开启公式识别，默认 true |
| `enable_table` | bool | 否 | 是否开启表格识别，默认 true |
| `language` | string | 否 | 文档语言，默认 `ch` |
| `data_id` | string | 否 | 你的业务数据 ID，建议传，方便关联 |
| `callback` | string | 否 | 任务完成后的回调 URL |
| `seed` | string | 否 | 与 callback 搭配做签名校验 |
| `extra_formats` | string[] | 否 | 可额外导出 `docx` / `html` / `latex` |
| `page_ranges` | string | 否 | 页码范围，如 `"1-5,8"` |
| `no_cache` | bool | 否 | 是否绕过缓存 |
| `cache_tolerance` | int | 否 | 缓存容忍时间，单位秒 |

### 6.3 模型选择建议

- **`vlm`**：官方文档中明确给出“推荐”，优先使用
- **`pipeline`**：默认模型，适合保持兼容
- **`MinerU-HTML`**：仅在解析 HTML 文件时使用；如果源文件不是 HTML，不要乱设这个值

---

## 7. Java 示例：远程 URL 单文件解析

下面给一个 **纯 Java 11+ `HttpClient`** 版本，便于你在任何项目里直接迁移。

```java
package demo.mineru;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class MinerUUrlClient {
    private static final String BASE_URL = "https://mineru.net";
    private static final String TOKEN = System.getenv("MINERU_TOKEN");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String createTask(String fileUrl) throws IOException, InterruptedException {
        String body = """
                {
                  "url": "%s",
                  "model_version": "vlm",
                  "language": "ch",
                  "enable_table": true,
                  "enable_formula": true,
                  "is_ocr": false
                }
                """.formatted(fileUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v4/extract/task"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        JsonNode root = objectMapper.readTree(response.body());
        ensureSuccess(response.statusCode(), root, "创建解析任务失败");
        return root.path("data").path("task_id").asText();
    }

    public JsonNode queryTask(String taskId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v4/extract/task/" + taskId))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        JsonNode root = objectMapper.readTree(response.body());
        ensureSuccess(response.statusCode(), root, "查询任务状态失败");
        return root.path("data");
    }

    public String waitUntilDone(String taskId, long intervalMillis, int maxAttempts)
            throws IOException, InterruptedException {
        for (int i = 0; i < maxAttempts; i++) {
            JsonNode data = queryTask(taskId);
            String state = data.path("state").asText();

            if ("done".equals(state)) {
                return data.path("full_zip_url").asText();
            }
            if ("failed".equals(state)) {
                throw new RuntimeException("解析失败: " + data.path("err_msg").asText());
            }

            Thread.sleep(intervalMillis);
        }
        throw new RuntimeException("轮询超时，taskId=" + taskId);
    }

    private void ensureSuccess(int httpCode, JsonNode root, String message) {
        int code = root.path("code").asInt(-1);
        if (httpCode != 200 || code != 0) {
            throw new RuntimeException(message + "，HTTP=" + httpCode + "，body=" + root.toPrettyString());
        }
    }
}
```

### 调用示例

```java
public class App {
    public static void main(String[] args) throws Exception {
        MinerUUrlClient client = new MinerUUrlClient();
        String taskId = client.createTask("https://cdn-mineru.openxlab.org.cn/demo/example.pdf");
        System.out.println("taskId = " + taskId);

        String zipUrl = client.waitUntilDone(taskId, 3000, 120);
        System.out.println("zipUrl = " + zipUrl);
    }
}
```

---

## 8. 任务查询：你会看到哪些状态

查询接口返回的 `state` 常见值有：

- `pending`：排队中
- `running`：解析中
- `converting`：格式转换中
- `done`：完成
- `failed`：失败

### running 时的进度字段

当状态为 `running` 时，返回里通常会有：

- `extract_progress.extracted_pages`
- `extract_progress.total_pages`
- `extract_progress.start_time`

### done 时的结果字段

当状态为 `done` 时，重点看：

- `full_zip_url`

这个 zip 是最终成果物。通常你真正使用的内容都在里面。

---

## 9. 结果包里通常有什么

官方文档说明：

- `full.md`：Markdown 解析结果
- `content_list.json`：内容列表
- `model.json` / `*_model.json`：模型推理结果
- `middle.json` / `layout.json`：中间处理结果
- 如果额外导出格式启用，还可能带：
  - `docx`
  - `html`
  - `latex`

如果你做 RAG 或知识抽取，通常第一优先级是：

1. `full.md`
2. `content_list.json`

如果你做精细版面分析、二次处理、表格回溯，则继续看 `layout.json` / `model.json`。

---

## 10. 本地文件上传解析：正确流程

很多人会误以为标准精准 API 能直接 multipart 上传。**不是。**

标准精准 API 的本地文件上传方式是：

1. 调 `POST /api/v4/file-urls/batch`
2. 服务返回：
   - `batch_id`
   - `file_urls`
3. 对每个 `file_url` 执行 `PUT`
4. 上传完成后系统自动解析
5. 再去查批量结果

---

## 11. Java 示例：申请上传链接并上传本地文件

```java
package demo.mineru;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class MinerUBatchUploadClient {
    private static final String BASE_URL = "https://mineru.net";
    private static final String TOKEN = System.getenv("MINERU_TOKEN");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public BatchApplyResult applyUploadUrl(Path file) throws IOException, InterruptedException {
        String body = """
                {
                  "files": [
                    {
                      "name": "%s",
                      "data_id": "demo-data-001",
                      "is_ocr": false
                    }
                  ],
                  "model_version": "vlm",
                  "language": "ch",
                  "enable_table": true,
                  "enable_formula": true
                }
                """.formatted(file.getFileName());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v4/file-urls/batch"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        JsonNode root = objectMapper.readTree(response.body());
        ensureSuccess(response.statusCode(), root, "申请上传链接失败");

        String batchId = root.path("data").path("batch_id").asText();
        String uploadUrl = root.path("data").path("file_urls").get(0).asText();
        return new BatchApplyResult(batchId, uploadUrl);
    }

    public void uploadFile(String uploadUrl, Path file) throws IOException, InterruptedException {
        byte[] bytes = Files.readAllBytes(file);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .timeout(Duration.ofMinutes(10))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new RuntimeException("上传失败，HTTP=" + response.statusCode() + "，body=" + response.body());
        }
    }

    public JsonNode queryBatchResult(String batchId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v4/extract-results/batch/" + batchId))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        JsonNode root = objectMapper.readTree(response.body());
        ensureSuccess(response.statusCode(), root, "查询批量结果失败");
        return root.path("data");
    }

    private void ensureSuccess(int httpCode, JsonNode root, String message) {
        int code = root.path("code").asInt(-1);
        if (httpCode != 200 || code != 0) {
            throw new RuntimeException(message + "，HTTP=" + httpCode + "，body=" + root.toPrettyString());
        }
    }

    public record BatchApplyResult(String batchId, String uploadUrl) {}
}
```

### 调用示例

```java
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

public class UploadApp {
    public static void main(String[] args) throws Exception {
        MinerUBatchUploadClient client = new MinerUBatchUploadClient();

        Path file = Path.of("demo.pdf");
        MinerUBatchUploadClient.BatchApplyResult result = client.applyUploadUrl(file);

        System.out.println("batchId = " + result.batchId());
        System.out.println("uploadUrl = " + result.uploadUrl());

        client.uploadFile(result.uploadUrl(), file);
        System.out.println("上传完成，等待系统自动提交解析任务...");

        for (int i = 0; i < 120; i++) {
            JsonNode data = client.queryBatchResult(result.batchId());
            JsonNode array = data.path("extract_result");

            if (array.isArray() && array.size() > 0) {
                JsonNode item = array.get(0);
                String state = item.path("state").asText();

                if ("done".equals(state)) {
                    System.out.println("zipUrl = " + item.path("full_zip_url").asText());
                    return;
                }
                if ("failed".equals(state)) {
                    throw new RuntimeException("解析失败: " + item.path("err_msg").asText());
                }
                System.out.println("当前状态: " + state);
            }

            Thread.sleep(3000);
        }

        throw new RuntimeException("批量结果轮询超时");
    }
}
```

---

## 12. 批量结果接口怎么理解

本地文件上传解析是“批量体系”，哪怕你只传一个文件，也会返回 `batch_id`。

批量查询返回里，关键字段通常在：

```json
data.extract_result[]
```

每个元素至少重点看：

- `file_name`
- `state`
- `err_msg`
- `full_zip_url`
- `data_id`
- `extract_progress`

如果你上传了多个文件，应该按 `file_name` 或 `data_id` 建立映射，而不是假设返回顺序永远稳定。

---

## 13. 回调模式怎么用

如果你不想轮询，可以在创建任务时传：

- `callback`
- `seed`

官方说明 callback 接口需要：

- 支持 `POST`
- UTF-8 编码
- `Content-Type: application/json`

同时会带：

- `checksum`
- `content`

你需要按官方规则用 **uid + seed + content 做 SHA256** 校验，确认回调没有被篡改。

### 什么时候适合回调

适合：

- 任务量大
- 不想写轮询调度器
- 有稳定的服务端接收接口

### 什么时候不建议回调

不适合：

- 本地开发
- 内网服务没有公网回调地址
- 你只是想先验证能不能跑通

对于首次接入，优先建议 **先写轮询版**，跑通后再升级为回调版。

---

## 14. 常见错误与排查建议

### 14.1 401 / 403 相关

优先检查：

- Token 是否过期
- 请求头是否写成 `Authorization: Bearer <token>`
- 是否多了空格、换行、引号
- 是否把轻量 API 的用法错拿来调标准 API

### 14.2 提交成功但一直失败

优先检查：

- 远程 URL 是否真的可下载
- 文件是否超出 200MB / 600 页
- 文件扩展名和真实内容是否一致
- 是否错误地用 `MinerU-HTML` 去解析非 HTML 文件
- 是否使用了国外 URL 导致拉取超时

### 14.3 本地上传后没有结果

优先检查：

- 上传链接是否过期（24 小时）
- PUT 上传是否真的返回 200 / 201
- 上传时是否乱加了 `Content-Type`
- 查询的是不是正确的 `batch_id`

### 14.4 轮询太猛被限流

建议：

- 单任务轮询间隔从 **2~5 秒** 起步
- 批量任务可统一调度，避免每个文件一个线程疯狂轮询
- 对 `pending/running/converting` 做指数退避更稳

---

## 15. 推荐的 Java 项目封装方式

建议你在项目中分 4 层：

### 15.1 配置层

```java
public record MinerUProperties(String baseUrl, String token) {}
```

### 15.2 API 客户端层

职责：

- 组装 HTTP 请求
- 解析 JSON 响应
- 把 `code != 0` 统一转成异常

建议拆成：

- `MinerUTaskClient`：URL 创建任务 + 查单任务
- `MinerUBatchClient`：申请上传链接 + 查批量结果
- `MinerUDownloadClient`：下载 zip

### 15.3 服务层

职责：

- 封装完整业务流程
- 比如 `parseByUrl(url)` 直接返回 zip 下载链接
- 比如 `uploadAndParse(file)` 直接返回最终结果

### 15.4 任务调度层

职责：

- 轮询
- 超时控制
- 重试
- 回调消费
- 限流

---

## 16. 推荐依赖

如果你走纯 Java：

- Java 11+ 内置 `HttpClient`
- `jackson-databind`

Maven 示例：

```xml
<dependencies>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.17.2</version>
    </dependency>
</dependencies>
```

如果你走 Spring Boot，也可以换成：

- `WebClient`
- `RestClient`
- `RestTemplate`（旧项目兼容）

但从示例的可移植性看，`HttpClient + Jackson` 最容易给别的模型继续理解和改造。

---

## 17. 最小可行接入方案（推荐）

如果你现在的目标是“先接入、能跑、能拿结果”，建议按下面的最小方案做：

### 方案一：URL 文件解析 MVP

1. 写 `createTask(url)`
2. 写 `queryTask(taskId)`
3. 写 `waitUntilDone(taskId)`
4. 打印 `full_zip_url`
5. 手工下载 zip 验证

### 方案二：本地文件解析 MVP

1. 写 `applyUploadUrl(file)`
2. 写 `uploadFile(uploadUrl, file)`
3. 写 `queryBatchResult(batchId)`
4. 在 `extract_result[]` 里找到 `done`
5. 拿 `full_zip_url`

不要一上来就做：

- 回调验签
- 多线程批量调度
- 全量结果解压入库
- 分布式限流

先把主链路跑通，再逐步升级。

---

## 18. 生产化建议

### 必做

- Token 放配置，不写死
- 请求和响应打印 `trace_id`
- 对 `code != 0`、HTTP 非 200 做统一异常处理
- 轮询设置总超时
- 对 zip 下载做重试与大小校验

### 建议做

- `data_id` 始终传业务主键
- 任务状态入库
- 轮询器统一调度
- 下载后的 zip 立即解压并归档
- 对 `full.md`、`content_list.json` 做二次封装

### 能力升级

- 支持 callback
- 支持批量多文件并发上传
- 支持失败重试与死信
- 支持解析结果自动入向量库 / 知识库

---

## 19. 容易踩坑的点总结

1. **标准 API 不是 multipart 直传**
2. **本地文件要先申请上传链接**
3. **上传后系统自动提交解析**
4. **HTML 文件要显式设置 `model_version = MinerU-HTML`**
5. **国外 URL 可能超时**
6. **上传 PUT 时不要乱加 Content-Type**
7. **不要把真实 Token 写进代码仓库**
8. **批量结果要按 `data_id` 或 `file_name` 对齐，不要迷信数组顺序**

---

## 20. 给其他模型继续工作的提示词

如果你准备把这份手册继续交给 Claude Code 或其他模型改代码，可以直接附上这段需求：

> 请基于这份 MinerU 标准精准 API Java 接入手册，帮我生成一个可直接用于 Spring Boot 的完整客户端实现。要求：
> 1. 使用 Java 17；
> 2. 封装 URL 单文件解析、本地文件上传解析、任务轮询、Zip 下载；
> 3. Token 从配置文件读取；
> 4. 对响应中的 `code`、`msg`、`trace_id` 做统一异常处理；
> 5. 输出结构化 DTO；
> 6. 给出单元测试和调用示例；
> 7. 禁止在源码中硬编码真实 Token。

---

## 21. 官方参考要点（供核对）

这份手册的关键事实来自官方公开文档，包括：

- 标准精准 API 需要 Token
- 标准精准 API 的核心接口是 `/api/v4/extract/task` 和 `/api/v4/file-urls/batch`
- 标准 API 支持 `pipeline` / `vlm` / `MinerU-HTML`
- 单文件最大 200MB、最大 600 页
- 单个账号每天享有 2000 页高优先级额度，超过后优先级降低
- 本地文件上传链接有效期 24 小时
- 上传文件时无需设置 Content-Type
- 上传完成后系统自动提交解析任务
- 提交任务接口 300 次/分钟，查询结果接口 1000 次/分钟
- 超额业务量可邮件联系官方运营团队

如果后续 MinerU 官方变更接口，你应以官方文档为准更新这份手册。

---

## 22. 最后的接入建议

如果你当前只是“先把 Java 调通”，最实用的路径是：

- **第一步**：先做 URL 解析版本
- **第二步**：再补本地文件上传版本
- **第三步**：再做 Zip 下载与解压
- **第四步**：最后做回调、重试、限流和生产化封装

这样最稳，不容易一开始就把链路做复杂。
