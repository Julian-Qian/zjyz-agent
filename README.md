# ZJYZ Agent

智建云租 Agent 子系统的开源代码快照，用于架构评审、能力审计和优化实验。

本仓库**不是**智建云租完整业务系统，也不包含原产品仓库的 Git 历史。它只保留与 Agent 能力直接相关的代码：任务编排、模型路由、工具发现与调用、知识库检索、反馈学习、附件解析、运行可靠性、答案校验、前端工作台及对应测试。

## 为什么开源

当前实现仍处于持续演进阶段。我们希望借助开发者和其他 Agent 的审查，重点发现以下问题：

- 任务规划、意图识别和多工具编排是否过于复杂或脆弱；
- 确定性业务计算与 LLM 推理的边界是否清晰；
- 项目范围、租户隔离、证据链和答案完整性保护是否充分；
- 运行恢复、并发控制、幂等、配额和成本治理是否合理；
- 知识检索、学习纠错和附件处理是否存在安全或质量风险；
- 前端运行状态和业务结果表达是否可以更简单、可靠。

## 仓库结构

```text
backend/
  src/main/java/com/zjyz/agent/   Spring Boot Agent 子系统
  src/main/resources/agent/       内置知识与意图-工具绑定
  src/test/                        单元测试和契约测试
  migration/                       Agent 相关 MySQL 迁移
  scripts/agent/                   Qdrant 初始化脚本
frontend/
  src/components/SubPage/          Agent 工作台与知识库 UI
  src/api/                         Agent API 客户端
  src/store/modules/               Redux 状态与可靠性逻辑
docs/specs/                        关键设计说明
docs/evaluation/                   评测方法与测试用例
qa/                                本地冒烟和 holdout 评测工具
```

## 重要边界

这是从现有产品中抽取的**可评审源码快照**，不是开箱即用的独立应用。

- 后端代码依赖宿主系统的认证上下文、会员权益、项目/合同/库存/结算 Service、DAO、Entity、DTO 和统一异常/响应约定。
- 前端代码依赖宿主 React 应用的路由、请求封装、Redux、Ant Design 和公共组件。
- 这些产品业务实现未被复制，以避免变相开源整个业务仓库。
- 本仓库不包含生产配置、凭证、客户数据、原始运行结果或账号白名单特例。

如需运行，请为 `com.zjyz.*` 的宿主依赖实现适配层，或逐步把 Agent 核心重构为端口/适配器结构。参见 [架构与集成边界](docs/architecture.md)。

## 建议的评审入口

1. 从 `AgentV2CoordinatorService`、`AgentV2RunExecutor` 和 `AgentRuntimeToolRegistry` 阅读主执行链。
2. 查看 `AgentTaskFrameBuilder`、`AgentAnswerGuard`、`AgentRequirementVerifier` 如何限制范围和校验完成度。
3. 查看 `AgentBusinessQueryService` 以及各业务 Skill，判断确定性查询是否与编排层解耦。
4. 查看 `AgentV2ModelRouter` 和 `AgentV2OpenAiCompatibleClient` 的模型降级、响应约束与用量记录。
5. 结合 `backend/src/test`、`docs/evaluation` 和 `qa/agent-evals` 给出可复现的改进建议。

## 数据库兼容性

迁移脚本以 MySQL 5.7 兼容为目标，并以 `use zjyz;` 开头。部署前请在隔离环境审阅和验证，勿直接对生产库执行。

## 安全

请不要在 Issue、PR 或评测结果中提交真实密钥、生产数据或客户信息。安全问题请遵循 [SECURITY.md](SECURITY.md)。

## License

[MIT](LICENSE)
