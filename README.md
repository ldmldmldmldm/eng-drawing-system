# 工程图纸生成系统

前后端分离。钉钉工号登录 → 任务驱动五步向导（抽取 / 层次 / 材料 / 翻译 / 生成）→ 合成公司图框工程图 → 预览/下载 DXF。

- 环境安装：[`docs/环境搭建.md`](docs/环境搭建.md)
- 代码结构：[`docs/代码说明.md`](docs/代码说明.md)

## 端口

| 端 | 端口 |
|----|------|
| 后端 | **6053** |
| 前端 | **8893** |

结构：`eng-backend/{eng-admin,eng-biz,eng-common}`、`eng-frontend/`。

## 本地启动

详细环境安装见 [`docs/环境搭建.md`](docs/环境搭建.md)。下列命令均在**仓库根目录**执行（先 `cd` 到本仓库）。

前置：已安装 **JDK 17+**、**Maven**、**Node.js 18+（含 npm）**、**MySQL**、**Python + ezdxf/matplotlib**。  
仅「能登录 / 进任务列表」可暂时没有 ODA、OCR；完整①～⑤ 合成必须有 **ODA** + **Python** + **OCR**。

库名：`eng_drawing`。默认连接示例：`127.0.0.1:23306` / `root` / `root`。本机若是 **3306**，启动前设 `MYSQL_PORT=3306`（或改 `application-dev.yml`）。

默认管理员：`admin` / `admin@123`（上线后请改密）  
普通用户：钉钉工号 + 密码；无账号则自动创建；首次须补全姓名。

建议固定业务数据目录（绝对路径，避免相对路径随进程 cwd 变化）：

```bash
# macOS / Linux 示例
export AI_DRAWING_DIR="$(pwd)/data/eng-drawing"
```

```bat
REM Windows CMD 示例（改成你的仓库绝对路径）
set AI_DRAWING_DIR=D:\work\eng-drawing-system\data\eng-drawing
set AI_DRAWING_PYTHON=python
```

### macOS / Linux

1. 初始化 MySQL（空库；端口按本机改 `-P`）：

```bash
mysql --default-character-set=utf8mb4 \
  -uroot -proot -h127.0.0.1 -P23306 < docs/sql/init.sql
```

2. 依赖自检（ODA、Python、ezdxf）：

```bash
bash scripts/check-eng-deps.sh
```

3. 后端（先起）：

```bash
cd eng-backend && mvn -pl eng-admin -am spring-boot:run
```

就绪可自检：`curl -sS http://127.0.0.1:6053/health`

4. 前端（另开终端，仍从仓库根目录）：

```bash
cd eng-frontend && npm install && npm run dev
```

### Windows

在 **PowerShell** 或 **CMD** 中操作。先 `cd` 到仓库根目录。若 `mysql` / `mvn` / `npm` 不在 PATH，请用完整路径或先配环境变量。

1. 初始化 MySQL（空库；端口按本机改 `-P`）：

```bat
mysql --default-character-set=utf8mb4 -uroot -proot -h127.0.0.1 -P23306 < docs\sql\init.sql
```

PowerShell 若重定向异常，可用：

```powershell
Get-Content -Raw docs\sql\init.sql | mysql --default-character-set=utf8mb4 -uroot -proot -h127.0.0.1 -P23306
```

2. 依赖检查（Windows 无 bash 脚本时手工确认）：
   - `pip install "ezdxf>=1.1.0" "matplotlib>=3.7.0"`
   - 后端默认调 `python3`；Windows 一般需：`set AI_DRAWING_PYTHON=python`
   - ODA：`set AI_DRAWING_ODA=C:\ODA\ODAFileConverter.exe`  
     （PowerShell：`$env:AI_DRAWING_ODA="C:\ODA\ODAFileConverter.exe"`）

3. 后端（先起）：

```bat
cd eng-backend
mvn -pl eng-admin -am spring-boot:run
```

浏览器或另开窗口访问：`http://127.0.0.1:6053/health` 应有响应。

4. 前端（另开终端，回到仓库根目录再进前端）：

```bat
cd eng-frontend
npm install
npm run dev
```

浏览器访问：`http://127.0.0.1:8893`（前端经 `/api` 代理到后端 `6053`，无需改前端配置）。

## 外部依赖

| 依赖 | 说明 |
|------|------|
| MySQL | 业务库 `eng_drawing`；建表脚本 `docs/sql/init.sql` |
| ODA File Converter | DWG→DXF；Mac 可放 `data/eng-drawing/tools/ODAFileConverter.app`；Windows 安装后设环境变量 `AI_DRAWING_ODA` 指向可执行文件 |
| Python3 + ezdxf | 版式合成 / 层次 DXF / 预览渲染 |
| PaddleOCR | 开发默认 `http://127.0.0.1:8868`；可选编排 `docs/deploy/docker/prod/paddle-ocr/` |
| LLM | 默认两条见「模型配置」（`qwen3.5-397b` 启用、`lyauto` 备用）；空库由 `init.sql` 写入；无可用模型时仅用语料翻译 |

## 生产编排

编排与配置在 `docs/deploy/docker/prod/{backend,frontend}/`（Dockerfile、compose、`application-prod.yml`、nginx）。按该目录部署即可。

## 数据落盘

| 数据 | 位置 |
|------|------|
| 用户 / 任务元数据 | MySQL `eng_drawing` |
| 任务文件（STP/DWG/annot/产物） | `data/eng-drawing/users/{uid}/tasks/{id}/` |
| 层次资产 | `data/eng-drawing/stack/{uuid}.{json,dxf,png}` |
