# Open-source extraction manifest

## Included

- `com.zjyz.agent` production source and its Agent-focused tests;
- Agent resource bundles, selected migrations, and Qdrant bootstrap script;
- Agent workspace and knowledge-management frontend source and tests;
- Agent API/Redux modules;
- selected architecture specs, evaluation rubric, test cases, and QA scripts.

## Deliberately excluded

- full frontend/backend repositories and their Git history;
- host business services, DAOs, entities, DTOs, authentication, membership, billing, and deployment code;
- application properties and production environment configuration;
- raw evaluation outputs, runtime logs, uploaded attachments, generated reports, and customer data;
- migrations containing account-specific quota exceptions;
- unrelated documents, assets, and product modules.

## Provenance

The files are copied as a source snapshot from the 智建云租 product repositories and published under the root MIT license. Future syncs should repeat secret/PII scanning before publication.

## 2026-09-26 incremental sync

- Synced release-bundled product overview knowledge and onboarding guidance, including fallback when the help catalog is unavailable.
- Preserved specific guidance failure reasons in task answers and included the corresponding regression tests.
- Agent frontend source already matches the host snapshot; no frontend changes were needed.
- Retained the extraction boundaries and existing SQL compatibility adjustments. Source-only changes were reviewed for secrets, production endpoints, customer information, and raw traces before publication.

## 2026-10-10 incremental sync

- Aligned Agent source with host backend 1.0.47 and frontend 1.0.46; provenance and file hashes are in `docs/sync-2026-10-10.json`.
- Added structured guidance retrieval, verified source passage selection, deduplicated guidance presentation, and user-facing capability examples.
- Added field-specific interpretation validation diagnostics and targeted retry instructions, valid JSON examples, and regression tests. Authorization and date validation remain enforced.
- Agent frontend source already matches this snapshot. Product release notes, host manual publishing records, deployment scripts, credentials, and runtime traces remain excluded.
- Preserved the existing standalone documentation and MySQL compatibility adjustments.
- Host tests passed (692 passed, 5 skipped). This snapshot is not independently runnable, and live incident replay has not been completed; deployment success is not proof of incident resolution.
