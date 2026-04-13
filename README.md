# PaiSmart 派聪明

企业级 AI 知识库管理系统，基于 RAG (Retrieval-Augmented Generation) 技术，提供智能文档处理和检索能力。

![架构概览](https://cdn.tobebetterjavaer.com/stutymore/README-20250730101618.png)

## 核心特性

### RAG Pipeline

```
文档上传 → MinIO存储 → Kafka消息 → 异步解析 → 向量化 → Elasticsearch索引
                                        ↓
                                   MinerU API
                                   (Markdown输出)
                                        ↓
用户查询 → QueryRewrite → RRF融合召回 → Cross-Encoder重排 → LLM生成 → 响应
```

### 技术亮点

| 特性 | 实现方案 | 效果 |
|------|---------|------|
| **文档解析** | MinerU (Markdown + JSON) | 表格结构保留、OCR识别 |
| **查询预处理** | 规则 Query Rewrite | 全角转半角、WWW过滤、同义词扩展 |
| **向量召回** | RRF 融合 (KNN + BM25) | 语义 + 关键词互补召回 |
| **重排序** | Cross-Encoder (qwen-rerank) | 精准相关性重排 |
| **多租户** | 组织标签 + JWT | 数据隔离与权限控制 |
| **实时通信** | WebSocket | 流式响应体验 |

---

## 技术栈

### 后端

| 组件 | 技术 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.4.2 (Java 17) | |
| 数据库 | MySQL 8.0 | 元数据存储 |
| 缓存 | Redis 7.0 | 会话 + 预览缓存 |
| 搜索引擎 | Elasticsearch 8.10 | 向量 + 全文检索 |
| 消息队列 | Apache Kafka 3.2 | 异步文档处理 |
| 文件存储 | MinIO 8.5 | S3 兼容对象存储 |
| 文档解析 | MinerU API | Markdown 输出 |
| AI 集成 | DeepSeek API | LLM 生成 |
| 向量化 | DashScope Embedding | text-embedding-v4 |
| 重排序 | DashScope Rerank | qwen3-rerank |
| 安全 | Spring Security + JWT | |
| 实时通信 | WebSocket | |

### 前端

| 组件 | 技术 |
|------|------|
| 框架 | Vue 3 + TypeScript |
| 构建 | Vite |
| UI 组件 | Naive UI |
| 状态管理 | Pinia |
| 路由 | Vue Router |

---

## 快速启动

### 1. 环境要求

- Java 17+
- Maven 3.8.6+
- Node.js 18+
- pnpm 8+
- Docker (用于基础设施)

### 2. 启动基础设施

```bash
# 使用 docker-compose 启动所有依赖服务
cd docs
docker-compose up -d

# 或使用 infra.sh 脚本 (推荐)
./infra.sh start
```

**服务端口**:

| 服务 | 端口 |
|------|------|
| MySQL | 3306 |
| Redis | 6379 |
| Kafka | 9092 |
| Elasticsearch | 9200 |
| MinIO API | 19000 |
| MinIO Console | 19001 |

### 3. 配置环境变量

```bash
cp .env.example .env
```

编辑 `.env` 确认以下关键配置:

```bash
SPRING_PROFILES_ACTIVE=dev
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/PaiSmart
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=PaiSmart2025

# AI 服务
DEEPSEEK_API_KEY=your-deepseek-key
EMBEDDING_API_KEY=your-dashscope-key
MINERU_TOKEN=your-mineru-token
```

### 4. 启动后端

```bash
mvn spring-boot:run
```

或直接在 IDE 中运行 `SmartPaiApplication.java`。

### 5. 启动前端

```bash
cd frontend
pnpm install
pnpm run dev
```

前端访问: http://localhost:9527

---

## 项目结构

### 后端

```
src/main/java/com/yizhaoqi/smartpai/
├── SmartPaiApplication.java          # 应用入口
├── client/                          # 外部 API 客户端
│   ├── RerankClient.java           # DashScope Rerank API
│   └── EmbeddingClient.java        # 通义千问 Embedding API
├── config/                          # 配置类
│   ├── EsConfig.java               # Elasticsearch 配置
│   ├── KafkaConfig.java            # Kafka 配置
│   ├── MinerUProperties.java       # MinerU 配置
│   ├── RerankProperties.java       # Rerank 配置
│   └── WebSocketConfig.java        # WebSocket 配置
├── consumer/                        # Kafka 消费者
│   └── FileProcessingConsumer.java # 文档异步处理
├── controller/                       # REST API
├── entity/                           # JPA 实体
├── handler/                          # WebSocket 处理器
│   └── ChatHandler.java            # 聊天处理器
├── model/                            # 领域模型
├── repository/                       # 数据访问层
├── service/                          # 业务逻辑
│   ├── ChatService.java            # 对话服务
│   ├── DocumentService.java         # 文档服务
│   ├── ElasticsearchService.java    # ES 操作
│   ├── HybridSearchService.java      # RRF + Rerank 搜索
│   ├── MinerUService.java           # MinerU API 调用
│   ├── QueryRewriteService.java      # 查询改写
│   ├── VectorizationService.java    # 向量化服务
│   └── ParseService.java            # 文档解析
└── utils/                            # 工具类
```

### 前端

```
frontend/
├── src/
│   ├── api/                        # API 调用
│   ├── components/                 # 公共组件
│   ├── handler/websocket/          # WebSocket 处理
│   ├── service/                    # 业务服务
│   ├── store/                      # Pinia 状态
│   ├── views/                      # 页面视图
│   │   ├── chat/                   # 聊天页面
│   │   └── knowledge-base/         # 知识库页面
│   └── router/                     # 路由配置
```

---

## 核心模块

### 1. 文档处理流程

```
客户端上传 → MinIO (分片) → 合并 → Kafka消息 → Consumer
                                                   ↓
                                    ┌─────────────┴─────────────┐
                                    ↓                           ↓
                              MinerU API                    Apache Tika
                              (启用时)                      (降级)
                                    ↓                           ↓
                              full.md                      纯文本
                              + JSON                       ↓
                                    ↓                           ↓
                              文本分块                      文本分块
                                    ↓                           ↓
                              向量化 → ES                    向量化 → ES
```

### 2. 搜索流程 (RRF + Rerank)

```
用户查询
    │
    ▼
QueryRewrite (全角转半角 / WWW过滤 / 同义词扩展)
    │
    ▼
┌─────────────────┐    ┌─────────────────┐
│   KNN 召回       │    │   BM25 召回      │
│  (向量相似度)     │    │  (关键词匹配)    │
└────────┬────────┘    └────────┬────────┘
         │                      │
         └──────────┬───────────┘
                    ▼
            RRF 融合排序
            (k=60 权重平滑)
                    │
                    ▼
         Cross-Encoder 重排
         (qwen3-rerank)
                    │
                    ▼
              返回 Top-5
```

### 3. 聊天流程

```
用户消息 → WebSocket → ChatHandler
                              │
                              ▼
                    HybridSearchService
                              │
                              ▼
                    DeepSeek API (流式)
                              │
                              ▼
                    WebSocket 流式响应
```

---

## API 文档

### 文档管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/upload/chunk` | 分片上传 |
| POST | `/api/v1/upload/merge` | 合并文件 |
| GET | `/api/v1/documents/accessible` | 获取可访问文档 |
| DELETE | `/api/v1/documents/{fileMd5}` | 删除文档 |

### 聊天

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/chat/{token}` | WebSocket 连接 |
| POST | `/api/v1/chat/stream` | HTTP 流式聊天 (备选) |

---

## 配置参考

### RRF + Rerank

```yaml
rerank:
  enabled: true
  api-url: https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
  api-key: ${EMBEDDING_API_KEY:}
  model: qwen3-rerank
  knn-weight: 0.5
  bm25-weight: 0.5
  rrf-k: 60
  rerank-top-n: 20
```

### Query Rewrite

```yaml
query-rewrite:
  enabled: true
  synonym-path: classpath:dict/synonym.txt
  synonym-enabled: true
  synonym-max: 5
  www-filter-enabled: true
  fullwidth-normalization-enabled: true
```

### MinerU

```yaml
MinerU:
  enabled: true
  api-url: https://mineru.net
  api-key: ${MINERU_TOKEN}
  model-version: vlm
  language: ch
  enable-table: true
  enable-formula: true
  is-ocr: true
  polling-max-attempts: 100
  polling-interval-ms: 5000
  temp-download-path: D:/tmp/mineru
```

---

## 关键文件

| 文件 | 说明 |
|------|------|
| `MIGRATION_PLAN.md` | 功能优化详细文档 |
| `CLAUDE.md` | Claude Code 开发指南 |
| `RRF_RERANK_PLAN.md` | RRF + Rerank 设计文档 |
| `docs/paismart.md` | 系统设计文档 |

---

## 常见问题

### Q: MinerU 解析失败会怎样？

A: 自动降级到 Apache Tika，无需人工干预。

### Q: Rerank API 失败怎么办？

A: 自动回退到 RRF 融合结果，保证服务可用。

### Q: 如何清空 ES 索引？

```bash
curl -X POST -u elastic:PaiSmart2025 \
  "http://localhost:9200/knowledge_base/_delete_by_query" \
  -H "Content-Type: application/json" \
  -d '{"query": {"match_all": {}}}'
```

### Q: 如何清空 Redis？

```bash
redis-cli -a PaiSmart2025 FLUSHALL
```

---

## 许可证

MIT License
