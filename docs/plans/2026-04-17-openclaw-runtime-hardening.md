# OpenClaw Runtime Hardening

## Scope

This document captures the runtime hardening that closes the remaining Docker/OpenClaw portion of the visual office plan.

## Applied changes

1. `docker-compose.yml`
- OpenClaw runtime mode flag added
- tool timeout env added
- browser capability env added
- egress allowlist env added
- dedicated `runtime-artifacts` mount added
- container marked `read_only: true`
- `tmpfs` added for `/tmp`
- internal network attached through `crab-agent-net`

2. `openclaw/config/openclaw.json`
- sandbox moved from `off` to `workspace-write`
- explicit network/filesystem permissions added
- runtime audit log enabled
- artifacts directory configured
- tool timeout configured

3. `openclaw/config/.env.example`
- runtime egress/tool/browser envs documented

## Runtime expectations

- OpenClaw writes only into workspace and runtime artifacts mounts
- container root filesystem stays read-only
- network access can be narrowed by env allowlist
- runtime audit logs are persisted under `openclaw/config/logs/runtime-audit.jsonl`
- generated files/artifacts are persisted under `openclaw/runtime-artifacts`

## Remaining operational note

The exact enforcement semantics of OpenClaw network allowlists depend on the upstream gateway/runtime build. This repo now contains the required config surface and safer Docker defaults so runtime hardening is no longer only conceptual.
