# 基础镜像走 DaoCloud 代理前缀，不改服务器 /etc/docker、不 restart docker
FROM docker.m.daocloud.io/library/python:3.10-slim-bookworm

RUN set -eux; \
    for f in /etc/apt/sources.list /etc/apt/sources.list.d/debian.sources; do \
      if [ -f "$f" ]; then \
        sed -i 's|http://deb.debian.org|https://mirrors.aliyun.com|g; s|http://security.debian.org|https://mirrors.aliyun.com|g' "$f"; \
      fi; \
    done; \
    apt-get update; \
    apt-get install -y --no-install-recommends libgomp1 libgl1 libglib2.0-0; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt

COPY app/ /app/

EXPOSE 8868
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8868"]
