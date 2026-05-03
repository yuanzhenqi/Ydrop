FROM python:3.12-slim

WORKDIR /app

# lxml 需要 libxml2/libxslt 的 dev 头文件才能编译 wheel；
# 用预构建 wheel 大多数时候足够，但国内镜像偶尔 fallback 到源码编译。
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libxml2 libxslt1.1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN pip install --no-cache-dir \
    --index-url https://pypi.tuna.tsinghua.edu.cn/simple \
    --timeout 120 \
    -r requirements.txt

COPY app ./app

EXPOSE 8787

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8787"]
