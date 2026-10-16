#!/usr/bin/env bash
# 工程图合成依赖自检（在仓库根目录执行）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ok=0
fail=0

MAC_ODA="$ROOT/data/eng-drawing/tools/ODAFileConverter.app/Contents/MacOS/ODAFileConverter"
LINUX_ODA="/opt/oda/ODAFileConverter"
APP_ODA="/Applications/ODAFileConverter.app/Contents/MacOS/ODAFileConverter"

if [[ -n "${AI_DRAWING_ODA:-}" && -x "${AI_DRAWING_ODA}" ]]; then
  echo "[OK] AI_DRAWING_ODA=${AI_DRAWING_ODA}"
  ok=$((ok + 1))
elif [[ -x "$MAC_ODA" ]]; then
  echo "[OK] bundled Mac ODA: $MAC_ODA"
  ok=$((ok + 1))
elif [[ -x "$LINUX_ODA" ]]; then
  echo "[OK] Linux ODA: $LINUX_ODA"
  ok=$((ok + 1))
elif [[ -x "$APP_ODA" ]]; then
  echo "[OK] Applications ODA: $APP_ODA"
  ok=$((ok + 1))
else
  echo "[FAIL] 未找到 ODA（data/eng-drawing/tools 或 /opt/oda 或 AI_DRAWING_ODA）"
  fail=$((fail + 1))
fi

if command -v python3 >/dev/null 2>&1; then
  echo "[OK] python3"
  ok=$((ok + 1))
else
  echo "[FAIL] python3"
  fail=$((fail + 1))
fi
if python3 -c "import ezdxf" >/dev/null 2>&1; then
  echo "[OK] ezdxf"
  ok=$((ok + 1))
else
  echo "[FAIL] ezdxf（pip install ezdxf）"
  fail=$((fail + 1))
fi

if command -v dwg2dxf >/dev/null 2>&1; then
  echo "[OK] dwg2dxf (可选)"
  ok=$((ok + 1))
else
  echo "[WARN] dwg2dxf 未安装（可选兜底）"
fi

echo "----"
echo "通过 $ok 项，失败 $fail 项"
exit "$fail"
