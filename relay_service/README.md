# Ydrop 中转服务

这是给 Ydrop 语音转写使用的临时音频中转服务。

## 功能说明

- 接收带认证的音频上传请求
- 为第三方语音转写服务提供可直接访问的文件 URL
- 支持按请求删除临时文件
- 自动清理过期文件

## 接口列表

- `GET /health`
- `POST /upload`（使用 `multipart/form-data`）
- `GET /files/{file_id}`
- `DELETE /files/{file_id}`

## 鉴权方式

上传和删除请求需要带认证头：

```text
Authorization: Bearer <RELAY_TOKEN>
```

下载接口保持匿名访问，方便外部语音识别服务直接拉取文件。

## 本地运行

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --host 0.0.0.0 --port 8787
```

## Docker 运行

```bash
copy .env.example .env
docker compose up --build -d
```

## 部署说明

- 建议放在反向代理后面，例如：`https://relay.yourdomain.com`
- `PUBLIC_BASE_URL` 要设置为这个对外可访问的域名
- `/app/storage` 应挂载持久卷，避免容器重启丢文件
- `RELAY_TOKEN` 不要提交进 git

## Ydrop 的预期调用链路

1. Ydrop 在手机本地完成录音
2. Ydrop 把音频上传到中转服务
3. 中转服务返回一个公开的音频 URL
4. Ydrop 把这个 URL 提交给 Volcengine / 豆包 ASR
5. 转写成功后，Ydrop 删除中转中的临时文件
