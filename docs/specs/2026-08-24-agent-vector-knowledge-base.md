# Spec: 智建云租 AI 向量知识库 V1

- 状态：已批准
- 批准日期：2026-08-24
- 范围：React 前端、Spring Boot 后端、MySQL、OSS、Qdrant、Embedding 服务
- 实施原则：兼容演进，不替换现有 `/agent` 协议，不把实时业务数据写入向量库

## Background

当前 Agent 使用内置结构化知识包和帮助中心标题、摘要、标签关键词检索，不能稳定识别同义表达，也不支持企业自行上传 PDF、DOCX、TXT、Markdown 资料。现有 `AgentKnowledgeRetriever` 已提供扩展点，本次在不重写 Agent 工作台的前提下增加可审计、可降级的向量检索。

## Goals

- 支持企业老板上传、预览、发布、下线、重新索引和归档知识文档。
- 使用 Embedding-3（1024 维）与 Qdrant 提供语义检索。
- 保留内置知识、帮助中心和关键词检索，形成混合检索。
- MySQL 作为文档权限、版本和发布状态的唯一事实源；Qdrant 仅作为可重建索引。
- 每次 Agent 回答记录知识引用，确保企业隔离和来源可追溯。
- Qdrant 或 Embedding 不可用时自动降级，不阻断核心业务工具。

## Non-Goals

- V1 不支持 OCR、图片/音视频、XLSX、PPTX、网页抓取和项目附件自动入库。
- V1 只开放平台公共知识和企业知识，项目级知识仅预留数据字段。
- 不自动审核或发布，不允许企业知识覆盖平台安全策略和实时数据规则。
- 不把项目数量、金额、库存、单据状态等实时事实写入向量库。
- 不进行模型微调，不开放任意 SQL、URL、Shell 或文件系统读取。

## Assumptions

- 企业知识仅 `OWNER` 可管理；`FINANCE` 可在 Agent 中使用已发布知识但不可维护。
- 系统公共知识由部署同步，不提供平台知识管理 UI。
- 文件类型为 PDF、DOCX、TXT、Markdown，单文件最大 20 MB。
- 原文件进入现有私有 OSS Bucket。
- Qdrant 部署于独立同地域受控实例，不与当前 2 核 1.8 GiB 应用机混部。
- Embedding 提供方默认智谱 `embedding-3`，固定 1024 维。

## Architecture

```text
Upload -> OSS -> MySQL source/job -> parse -> chunk -> Embedding -> Qdrant
                                                    |
User goal -> TaskFrame -> built-in + keyword + vector -> MySQL post-check -> Agent
```

- MySQL 保存来源、分段原文、版本、权限、状态、处理任务和引用审计。
- OSS 保存企业上传原文件。
- Qdrant 保存向量与最小检索 Payload，不保存权威权限状态。
- 查询 Qdrant 时由服务端注入 `cid` 过滤；命中后必须回查 MySQL，确认同企业、当前版本、`PUBLISHED`、`ACTIVE`。
- 结构化安全和业务语义保持最高优先级；企业知识只能补充企业操作资料。

## Data Model

迁移文件为 `V121_create_agent_vector_knowledge.sql`，以 `use zjyz;` 开头并兼容 MySQL 5.7。

### `agent_knowledge_source`

保存 `source_id`、`scope_type`、`cid`、`source_type`、标题/描述/领域/标签、权威级别、版本、生命周期状态、索引状态、OSS 信息、摘要、解析/Embedding 信息、分段数、错误摘要、创建/发布/归档审计字段。

生命周期：`DRAFT/PUBLISHED/UNPUBLISHED/ARCHIVED`。

索引状态：`PENDING/PROCESSING/READY/FAILED`。

### `agent_knowledge_chunk`

保存分段原文、标题路径、顺序、哈希、Token 估算、Qdrant Point ID、状态和元数据。向量数组不写入 MySQL。

### `agent_knowledge_ingest_job`

保存 `PARSE/EMBED/UPSERT/DELETE/SYNC` 异步任务、状态、重试、锁和错误摘要，提供幂等恢复。

### `agent_run_knowledge_ref`

保存 Run/消息与来源/分段之间的引用关系、检索方式、排名、分数和是否被最终回答引用。

### `agent_semantic_definition`

保存未来动态业务语义；V1 只同步现有批准规则，不开放企业编辑。

## Document Processing

- 同时校验扩展名、MIME、文件魔数、文件大小和 SHA-256。
- PDF 使用 PDFBox，DOCX 使用 Apache POI；TXT/Markdown 只接受可可靠解码文本。
- 不执行宏、链接或嵌入对象；扫描 PDF 返回 `AKB005/OCR_REQUIRED`。
- 优先按标题、段落、列表和表格切片，目标 500～800 个中文字符，硬上限约 1200 Tokens，重叠约 100 字符。
- 单文档最多 5000 个分段；相同哈希段落不重复向量化。
- Embedding 批量最多 32 条，失败最多重试 3 次并指数退避。

## Qdrant

- 逻辑别名：`zjyz_agent_platform_current`、`zjyz_agent_tenant_current`。
- 物理集合名称包含模型、维度和版本，模型升级时全量重建后切换别名。
- 1024 维 Cosine；Point ID 由来源、版本和分段顺序确定性生成。
- Payload 包含来源、版本、分段、范围、cid、领域、权威、发布状态和内容哈希。
- 为 `cid/scope_type/domain/authority/lifecycle_status/source_id/source_version` 创建 Payload 索引。
- Qdrant 只开放私网，启用 TLS 和 API Key，不经公网 nginx 暴露。

## Retrieval

每轮基于最新 `TaskFrame.userGoal`：

1. 检索内置结构化规则。
2. 执行帮助中心关键词检索。
3. 生成查询向量。
4. 查询平台和当前企业集合。
5. 对向量命中执行 MySQL 权限、版本和发布状态复核。
6. 按权威、关键词与向量相关度合并去重，最多向模型注入 5 条、6000 字符。
7. 写入 `knowledge.retrieved` 事件和 `agent_run_knowledge_ref`。

Qdrant/Embedding 异常时返回内置和关键词结果。文档内容视为不可信数据，不能覆盖系统指令、权限和工具规则。

## API Contract

全部复用 `/agent` 顶级前缀和现有响应 Envelope：

- `POST /agent/knowledge/sources`：上传，返回 202、source/job 状态。
- `GET /agent/knowledge/sources`：分页列表。
- `GET /agent/knowledge/sources/{sourceId}`：详情。
- `GET /agent/knowledge/sources/{sourceId}/chunks`：分页分段预览。
- `GET /agent/knowledge/sources/{sourceId}/download`：鉴权下载。
- `POST /agent/knowledge/sources/{sourceId}/publish`：发布，使用 `expectedVersion` 乐观校验。
- `POST /agent/knowledge/sources/{sourceId}/unpublish`：下线。
- `POST /agent/knowledge/sources/{sourceId}/reindex`：幂等重建。
- `DELETE /agent/knowledge/sources/{sourceId}`：可恢复归档。
- `POST /agent/knowledge/search-preview`：OWNER 发布前检索预览。
- `GET /agent/knowledge/jobs/{jobId}`：处理状态。
- `GET /agent/capabilities` 新增 `knowledgeMode=HYBRID_VECTOR`、管理权限和向量健康字段。

建议错误码：`AKB001` 文件类型、`AKB002` 大小、`AKB003` 重复、`AKB004` 解析、`AKB005` OCR、`AKB006` Embedding、`AKB007` Qdrant、`AKB008` 未就绪、`AKB009` 版本冲突、`AKB010` 无权或不存在、`AKB011` OWNER 权限、`AKB012` 配额、`AKB013` 重复任务、`AKB014` 空内容、`AKB015` 分段超限。

## Frontend

- 新增 `src/api/agentKnowledge.js`。
- 新增 Agent 工作台内“知识库”页签，仅 OWNER 显示管理操作。
- 提供统计、筛选、拖拽上传、列表、状态、分段预览、搜索预览、发布、下线、重建、下载和归档。
- 覆盖上传/处理 Loading、空状态、失败重试、只读会员、无权限和向量降级提示。
- Agent 回答继续展示知识标题，并兼容旧消息无引用元数据。

## Consistency And Security

- MySQL 先创建来源和任务；OSS、Embedding、Qdrant 通过异步状态机和补偿任务串联。
- 发布/下线以 MySQL 提交为准，Qdrant 更新可以延迟；查询后置校验保证立即生效。
- 所有写操作必须幂等，版本更新不覆盖当前已发布版本。
- 归档保留 30 天后再清理 OSS、MySQL 分段和向量。
- 不记录完整 Prompt、向量、API Key、OSS签名或第三方原始错误。
- 企业文档发送至 Embedding 提供方前，需要完成数据处理条款审阅并在上传页提示。

## Deployment

1. 独立部署并加固 Qdrant，创建集合、索引和 Alias。
2. 执行 V121。
3. 后端先以 `managementEnabled=false/vectorEnabled=false` 发布。
4. 同步 8 条内置知识与 8 篇帮助文章并运行召回评测。
5. 开启只读向量灰度，再按企业开启管理上传。

新增接口仍为 `/agent/knowledge/**`，生产 nginx 已有 `location /agent/`，不新增顶级代理前缀。回滚通过功能开关恢复 `BUILT_IN_CURATED` 模式，新增表和OSS原文件保留。

## Acceptance Criteria

- 现有 8 条内置知识和 8 篇帮助文章无回归。
- OWNER 可完成完整文档生命周期；FINANCE 所有管理写接口均被拒绝。
- 同义表达可命中文档；至少 100 条中文评测集 `Recall@5 >= 85%`。
- 草稿、失败、下线、归档和旧版本不会进入正常回答。
- 跨企业错误召回为 0；Qdrant 返回错误租户 Point 时 MySQL 必须丢弃。
- 文档 Prompt 注入不能改变系统规则或工具权限。
- Qdrant/Embedding 不可用时自动降级，Agent业务工具仍可用。
- 下线在 MySQL 提交后立即生效；失败任务可安全重试。
- 20 并发下知识检索 P95 目标不超过 2 秒，不含最终模型生成。
- 后端编译/测试、前端构建、API 前缀扫描和公网认证形态验证通过。

## Verification Plan

- 后端：`mvn -DskipTests compile`、`mvn test`，覆盖解析、切片、幂等、权限、状态机、降级和跨企业测试。
- 前端：`npm run build`，人工验证上传、状态轮询、发布/下线、失败重试和角色入口。
- API：核对前后端 `/agent/knowledge/**` 路径；ECS本机和公网无Token请求必须返回认证 JSON，不能返回 `index.html`。
- 运维：Qdrant快照、恢复、密钥、TLS、私网和故障切换演练。

## Implementation Order

1. V121 数据模型。
2. OSS、解析、切片、任务状态机。
3. Embedding 与 Qdrant 适配器。
4. 混合检索、MySQL 后置校验和引用审计。
5. 管理 API 和权限。
6. 前端管理页与 Agent 引用展示。
7. 同步、评测、构建、安全和部署验证。
