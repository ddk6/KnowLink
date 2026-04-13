# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PaiSmart (派聪明) is an enterprise-grade AI knowledge management system built with RAG (Retrieval-Augmented Generation) technology. It provides intelligent document processing and retrieval capabilities using a modern tech stack including Spring Boot, Vue 3, Elasticsearch, and AI services.

## Tech Stack

- **Backend**: Spring Boot 3.4.2, Java 17, JPA/Hibernate, Spring Security (JWT)
- **Frontend**: Vue 3 + TypeScript, Vite, Pinia, Vue Router
- **Data**: MySQL 8.0, Elasticsearch 8.10.0 (vector + keyword search), Redis 7.0
- **Async**: Apache Kafka 3.2.1 for file processing pipeline
- **Storage**: MinIO 8.5.12 (S3-compatible object storage)
- **AI**: DeepSeek API (LLM), DashScope text-embedding-v4 (vectorization)

## RAG Pipeline Architecture

### Document Ingestion (Async)
```
File Upload → MinIO → Kafka (file-processing topic) → FileProcessingConsumer
                                                              ↓
                                                    ParseService (Apache Tika)
                                                              ↓
                                                    VectorizationService (Embedding API)
                                                              ↓
                                                    Elasticsearch (knowledge_base index)
```

### Chat Flow
```
WebSocket /chat/{token} → ChatHandler → HybridSearchService
                                                ↓
                                    Elasticsearch (KNN + keyword hybrid)
                                                ↓
                                    DeepSeek API → Streamed response
```

### Multi-tenant Security
- Organization tags (`orgTag`) on all tenant-scoped entities
- `OrganizationTagAuthorizationFilter` enforces data isolation
- Documents have `is_public` flag for org-wide visibility
- WebSocket tokens embed `primaryOrg` and `orgTags`

## Key Files

### Backend
- `src/main/java/com/yizhaoqi/smartpai/SmartPaiApplication.java` - Entry point
- `src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java` - Kafka consumer for async file parsing/vectorization
- `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java` - WebSocket chat handler
- `src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java` - Elasticsearch hybrid search (vector + keyword)
- `src/main/java/com/yizhaoqi/smartpai/config/WebSocketConfig.java` - WebSocket CORS allowed-origins configuration
- `src/main/java/com/yizhaoqi/smartpai/config/KafkaConfig.java` - Kafka topic names and consumer group config

### Frontend
- `frontend/src/handler/websocket/` - WebSocket client implementation
- `frontend/src/service/` - API client functions (one file per domain)
- `frontend/src/store/` - Pinia stores

## Configuration

### Profile Hierarchy
- `application.yml` - Base config, most settings have environment variable overrides
- `application-dev.yml` - Local dev overrides
- `application-docker.yml` - Docker deployment settings
- `application-prod.yml` - Production settings
- `.env.example` in project root - Copy to `.env` for IDE running (Spring reads it automatically)

### Key Service Ports (from docker-compose.yaml)
| Service | Port |
|---------|------|
| MySQL | 3306 |
| Redis | 6379 |
| Kafka | 9092 |
| Elasticsearch | 9200 |
| MinIO (API) | 19000 |
| MinIO (Console) | 19001 |

### Important Config Notes
- `security.allowed-origins` in `application.yml` controls WebSocket CORS. Must include frontend origin (e.g., `http://localhost:9527`) for WebSocket handshake to succeed
- `elasticsearch.scheme` defaults to `http` (not `https`) when running locally
- Kafka topics (`file-processing`, `vectorization`) are auto-created on startup with 1 partition/replica

## Common Commands

### Backend
```bash
# Run with dev profile (reads .env automatically)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Run specific test
mvn test -Dtest=SomeServiceTest

# Package
mvn clean package
```

### Frontend
```bash
cd frontend && pnpm install
pnpm dev          # Dev server on port 9527
pnpm build        # Production build
pnpm typecheck    # TypeScript check
pnpm lint         # Lint check
```

### Docker Services
```bash
cd docs && docker-compose up -d   # Start all infra services
docker compose -f docs/docker-compose.yaml ps  # Check status
```

## Development Workflow

### Adding a New Feature (Backend)
1. Create entity in `entity/` with JPA annotations
2. Create repository in `repository/`
3. Create service in `service/`
4. Create controller in `controller/`
5. JPA auto-generates DDL on restart (ddl-auto: update)

### Adding a New API Endpoint
- Follow RESTful conventions
- JWT authentication via `SecurityConfig` filter chain
- Organization-tag-based authorization via `OrgTagAuthorizationFilter`

### WebSocket Debugging
- WebSocket endpoint: `/chat/{token}` where token is JWT
- Check `ChatWebSocketHandler` for message handling logic
- Ensure `security.allowed-origins` includes frontend URL before handshake