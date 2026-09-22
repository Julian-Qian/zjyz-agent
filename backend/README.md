# Backend snapshot

The Java source mirrors the Agent-specific Spring Boot packages from the host product. It is intentionally not supplied with a standalone `pom.xml`, because compiling it against fabricated host DTOs or services would hide the real integration boundary.

Use `../docs/architecture.md` to identify the missing host ports. The recommended first refactor is to replace direct `com.zjyz.dao`, `com.zjyz.pojo`, `com.zjyz.service`, `com.zjyz.common`, and `com.zjyz.membership` imports with explicit Agent-owned interfaces.

Configuration values in the source use Spring property placeholders. Supply secrets only through your own secret manager or environment; never commit them.
