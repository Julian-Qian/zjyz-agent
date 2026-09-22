# Agent Review Instructions

This repository is an extracted review snapshot of an Agent subsystem, not the complete host application.

When reviewing or modifying it:

- Focus on orchestration, model routing, tool contracts, scope enforcement, evidence, learning, knowledge retrieval, reliability, and answer presentation.
- Treat missing `com.zjyz` business services, DAOs, entities, DTOs, authentication, and membership classes as host application ports. Do not invent their behavior; document assumptions.
- Prefer deterministic computation for money, inventory quantities, document state, and cross-project aggregation. Use the model for interpretation and presentation, not as the source of business truth.
- Preserve tenant and project scoping in every query and tool execution path.
- Add or update tests for any proposed behavior change.
- Never add real credentials, production endpoints, customer information, or raw production traces.
- Keep recommendations incremental and identify which changes belong in this extracted module versus the host application.
