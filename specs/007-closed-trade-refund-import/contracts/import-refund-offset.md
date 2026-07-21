# Contract: Platform Refund Relations (Scan Phase A)

**Supersedes import-time write.**

- Created during `relations check` Phase A only (for new path).
- Alipay: order prefix match unique → accepted refund_offset
- WeChat: dual-row rules unique/residual → accepted refund_offset
- Multi-candidate → pending/open-leg
- rule_id: `scan.alipay.*` / `scan.wechat.*` (compat: treat existing `import.*` as already linked)
