# eng-drawing 后端运行镜像：Temurin 17 JRE + venv(ezdxf) + 中文字体 + Linux ODA
# 构建前须将 ODAFileConverter_lnxX64.deb 放在本目录（随 eng-drawing-prod-backend.tar.gz 上传）
# 构建：cd /data01/eng-drawing/backend && sudo docker compose -f docker-compose-backend.yml build
# 基础镜像 DaoCloud 前缀；不改服务器 daemon、不 restart docker
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    MPLBACKEND=Agg \
    AI_DRAWING_PYTHON=/opt/aidrawing-py/bin/python3 \
    AI_DRAWING_ODA=/usr/local/bin/oda-file-converter \
    PATH="/opt/aidrawing-py/bin:${PATH}"

# apt 换阿里云；Python + 文泉驿 + ODA/Qt(xcb) 运行库 + 无头 xvfb
RUN set -eux; \
    if [ -f /etc/apt/sources.list ]; then \
      sed -i 's|http://archive.ubuntu.com|http://mirrors.aliyun.com|g; s|http://security.ubuntu.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list; \
    fi; \
    if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then \
      sed -i 's|http://archive.ubuntu.com|http://mirrors.aliyun.com|g; s|http://security.ubuntu.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        python3 \
        python3-venv \
        python3-pip \
        ca-certificates \
        fontconfig \
        fonts-wqy-microhei \
        xvfb \
        libgl1 \
        libglib2.0-0 \
        libxkbcommon0 \
        libxkbcommon-x11-0 \
        libdbus-1-3 \
        libfontconfig1 \
        libfreetype6 \
        libsm6 \
        libice6 \
        libxext6 \
        libxcb1 \
        libxcb-glx0 \
        libxcb-keysyms1 \
        libxcb-image0 \
        libxcb-shm0 \
        libxcb-icccm4 \
        libxcb-render0 \
        libxcb-render-util0 \
        libxcb-shape0 \
        libxcb-randr0 \
        libxcb-xfixes0 \
        libxcb-sync1 \
        libxcb-xinerama0 \
        libxcb-xkb1 \
        libx11-xcb1 \
        libxrender1 \
        libxi6 \
        libxcb-util1; \
    # 官方说明：部分发行版仅有 libxcb-util.so.1，需兼容 .so.0
    if [ ! -e /usr/lib/x86_64-linux-gnu/libxcb-util.so.0 ] \
       && [ -e /usr/lib/x86_64-linux-gnu/libxcb-util.so.1 ]; then \
      ln -s libxcb-util.so.1 /usr/lib/x86_64-linux-gnu/libxcb-util.so.0; \
    fi; \
    fc-cache -fv; \
    rm -rf /var/lib/apt/lists/*

COPY requirements-aidrawing.txt /tmp/requirements-aidrawing.txt

# pip 清华源
RUN python3 -m venv /opt/aidrawing-py \
    && /opt/aidrawing-py/bin/pip install --upgrade pip -i https://pypi.tuna.tsinghua.edu.cn/simple \
    && /opt/aidrawing-py/bin/pip install -i https://pypi.tuna.tsinghua.edu.cn/simple -r /tmp/requirements-aidrawing.txt \
    && rm -f /tmp/requirements-aidrawing.txt \
    && /opt/aidrawing-py/bin/python -c "import ezdxf; import matplotlib; print('ezdxf', ezdxf.__version__)" \
    && test -f /usr/share/fonts/truetype/wqy/wqy-microhei.ttc

# Linux ODA：构建上下文必须有 ODAFileConverter_lnxX64.deb（勿用 Mac .app）
# deb 内常有两份同名文件：① 67B 壳脚本（调 /usr/bin/ODAFileConverter_*）② 版本目录下真实二进制
# 必须按体积选后者；勿 find | head -1（会误拷壳脚本导致 DWG 转换失败）
COPY ODAFileConverter_lnxX64.deb /tmp/ODAFileConverter_lnxX64.deb
RUN set -eux; \
    test -s /tmp/ODAFileConverter_lnxX64.deb; \
    mkdir -p /tmp/oda-unpack /opt/oda; \
    dpkg-deb -x /tmp/ODAFileConverter_lnxX64.deb /tmp/oda-unpack; \
    # 真实二进制约数百 KB～数 MB；壳脚本仅数十字节。阈值用 64KB，勿用 1MB（会误杀 ~530KB 真文件）
    ODA_BIN="$(find /tmp/oda-unpack -type f -path '*/ODAFileConverter_*/ODAFileConverter' -size +64k | head -1)"; \
    if [ -z "$ODA_BIN" ]; then \
      ODA_BIN="$(find /tmp/oda-unpack -type f -name 'ODAFileConverter' -size +64k | head -1)"; \
    fi; \
    test -n "$ODA_BIN"; \
    test -s "$ODA_BIN"; \
    if head -c 2 "$ODA_BIN" | grep -q '#!'; then \
      echo "refusing ODA wrapper script: $ODA_BIN"; \
      exit 1; \
    fi; \
    cp -a "$(dirname "$ODA_BIN")/." /opt/oda/; \
    chmod +x /opt/oda/ODAFileConverter; \
    test -x /opt/oda/ODAFileConverter; \
    test "$(stat -c%s /opt/oda/ODAFileConverter)" -gt 65536; \
    # 无头容器无 DISPLAY：业务走包装脚本（xvfb-run），勿直接调真实二进制
    printf '%s\n' '#!/bin/sh' \
      'exec xvfb-run -a --server-args="-screen 0 1280x1024x24" /opt/oda/ODAFileConverter "$@"' \
      > /usr/local/bin/oda-file-converter; \
    chmod +x /usr/local/bin/oda-file-converter; \
    test -x /usr/local/bin/oda-file-converter; \
    command -v xvfb-run; \
    rm -rf /tmp/ODAFileConverter_lnxX64.deb /tmp/oda-unpack; \
    ls -la /opt/oda | head -20; \
    wc -c /opt/oda/ODAFileConverter; \
    ls -la /usr/local/bin/oda-file-converter

WORKDIR /app
