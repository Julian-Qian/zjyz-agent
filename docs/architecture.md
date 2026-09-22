# 架构与集成边界

## 执行链概览

```text
React Agent 工作台
  -> Agent V2 Controller / Workspace Controller
  -> 会话解释与 TaskFrame 构建
  -> Coordinator / Run Executor
  -> Tool Discovery + Runtime Tool Registry
  -> 确定性业务 Skill / 知识检索 / 附件分析
  -> Requirement Verifier + Answer Guard + Presentation
  -> 事件流、运行状态、用量与学习反馈
```

## 已包含的能力

- 经典 Agent Chat 与 Workspace/V2 两代执行路径；
- 意图分类、槽位提取、项目范围选择和任务框架；
- 工具目录、工具发现、运行时注册和多领域业务 Skill；
- OpenAI-compatible 模型客户端、模型路由、降级和用量计量；
- 内置/向量/混合知识检索与 Qdrant 适配；
- 附件存储、解析、OCR 和合同审查；
- 反馈学习、验证、知识沉淀和答案呈现；
- 运行事件、恢复、watchdog、CAS 完成和前端可靠性状态；
- 财务、库存、项目、合同、单据和风险分析的确定性能力。

## 未包含的宿主能力

后端 `com.zjyz.agent` 会引用下列宿主接口或类型，它们有意不在本仓库中：

- `com.zjyz.common.*`：认证上下文、统一异常和 Controller 约定；
- `com.zjyz.membership.*`：权益、成员与租户能力；
- `com.zjyz.service.*`：项目、合同、库存、报表、单据和帮助中心；
- `com.zjyz.dao.*` 与 `com.zjyz.pojo.*`：产品数据库访问和业务数据模型。

前端同样依赖宿主应用的请求封装、路由、Redux 装配、公共组件和样式基线。

## 推荐的解耦方向

1. 把宿主依赖收敛为 `agent-core` 定义的端口接口，例如 `ProjectPort`、`InventoryPort`、`LedgerPort`。
2. 将 Spring/MyBatis/OSS/Qdrant/HTTP 实现下沉到 `agent-adapters`。
3. 让工具输入输出使用 Agent 自有的稳定 DTO，避免直接泄漏产品 Entity。
4. 保持金额、数量、状态判断和权限范围为确定性路径，并把模型输出视为不可信输入。
5. 为每个工具声明权限、作用域、证据类型、成本和幂等属性，使运行时可以统一治理。

## 当前限制

由于宿主业务代码未开源，本仓库当前主要用于静态评审和局部单元测试参考。若要形成可独立构建的项目，应先完成上述端口抽取，并为端口提供 fake adapter 与合成数据 fixture。
