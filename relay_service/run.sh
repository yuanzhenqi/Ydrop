#!/usr/bin/env bash
# Ydrop 一键启动脚本：检查依赖、构建前端、启动后端

set -e
cd "$(dirname "$0")"

echo "━━━ Ydrop relay_service 启动器 ━━━"

# 1. 读取 .env
if [[ ! -f .env ]]; then
  echo "⚠ 未找到 .env，复制 .env.example 并填入真实配置后重试"
  [[ -f .env.example ]] && cp .env.example .env && echo "  已复制 .env.example → .env，请编辑后重跑"
  exit 1
fi

set -a; source .env; set +a

if [[ "$RELAY_TOKEN" == "change-me" || -z "$RELAY_TOKEN" ]]; then
  echo "✗ RELAY_TOKEN 未配置或为默认值，请修改 .env"
  exit 1
fi

# 2. Python 3.11+ 检查
PY=""
for candidate in python3.11 python3.12 python3.13 ~/.local/bin/python3.11; do
  if command -v "$candidate" >/dev/null 2>&1; then
    PY=$(command -v "$candidate")
    break
  fi
done
if [[ -z "$PY" ]]; then
  echo "✗ 找不到 Python 3.11+，请先安装（brew install python@3.11 / apt install python3.11）"
  exit 1
fi
echo "✓ Python: $PY ($($PY --version))"

# 3. 虚拟环境 + 依赖
if [[ ! -d .venv ]]; then
  echo "→ 创建 venv..."
  $PY -m venv .venv
fi
if [[ ! -f .venv/.deps_installed ]] || [[ requirements.txt -nt .venv/.deps_installed ]]; then
  echo "→ 安装 Python 依赖..."
  .venv/bin/pip install --quiet --upgrade pip
  .venv/bin/pip install --quiet -r requirements.txt
  touch .venv/.deps_installed
fi
echo "✓ Python 依赖就绪"

# 4. Node.js 检查 + 前端构建（如需）
if [[ -d web ]]; then
  if ! command -v node >/dev/null 2>&1; then
    echo "⚠ 找不到 node，跳过前端构建（API 仍可用）"
  else
    NODE_VER=$(node --version | grep -oE '[0-9]+' | head -1)
    if [[ $NODE_VER -lt 18 ]]; then
      echo "⚠ Node.js $NODE_VER < 18，跳过前端构建"
    else
      if [[ ! -d web/node_modules ]]; then
        echo "→ 安装前端依赖（使用国内镜像）..."
        (cd web && npm install --registry=https://registry.npmmirror.com)
      fi
      if [[ ! -d static ]] || [[ web/src -nt static ]]; then
        echo "→ 构建 Next.js..."
        (cd web && npm run build)
        rm -rf static
        cp -r web/out static
      fi
      echo "✓ 前端静态文件就绪（static/）"
    fi
  fi
fi

# 5. 启动
HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8787}"
echo ""
echo "━━━ 启动 uvicorn ━━━"
echo "  监听:    http://$HOST:$PORT"
echo "  Web UI:  http://localhost:$PORT/"
echo "  API:     http://localhost:$PORT/api/notes"
echo "  Token:   ${RELAY_TOKEN:0:6}... (设置页输入)"
echo "  WebDAV:  ${WEBDAV_BASE_URL:-（未配置，仅本地模式）}"
echo ""
exec .venv/bin/uvicorn app.main:app --host "$HOST" --port "$PORT"
