#!/usr/bin/env bash
# SuperBizAgent CI 门禁脚本（Linux/macOS 与 GitHub Actions 使用）
# 本地 Windows 用法：在 Git Bash / WSL 中运行 `bash scripts/ci.sh`，
# 或使用 PowerShell 等价脚本（后续提供 scripts/ci.ps1）。
# 前提：已安装 JDK 17 并设置 JAVA_HOME（CI runner 已预置）。
set -euo pipefail

echo "==> [1/3] 构建 + 单元测试 + JaCoCo 覆盖率门禁"
mvn -B clean verify

echo "==> [2/3] 密钥明文 grep 门禁（禁 sk- 入库）"
# 阻断范围：src/ 与根目录配置文件
if grep -RInE --exclude-dir=target --exclude-dir=.git --exclude-dir=node_modules \
    'sk-[A-Za-z0-9]{8,}' src/ *.yml *.yaml *.xml *.properties 2>/dev/null; then
  echo "ERROR: 检测到疑似 DashScope API Key 明文，禁止入库"
  exit 1
fi
# 告警不阻断：docs/（历史文档可能残留示例片段）
if grep -RInE --exclude-dir=target --exclude-dir=.git \
    'sk-[A-Za-z0-9]{8,}' docs/ 2>/dev/null; then
  echo "WARNING: docs/ 下存在疑似示例密钥片段，请人工确认并清理"
fi

echo "==> [3/3] 轻量门禁：业务代码禁用 System.out.print"
if grep -RInE --include='*.java' 'System\.out\.print' src/main/java 2>/dev/null; then
  echo "ERROR: 业务代码存在 System.out.print，请改用日志"
  exit 1
fi

echo "CI pipeline passed"
