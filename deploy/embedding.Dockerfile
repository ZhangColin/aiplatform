# embedding 服务镜像（部署形态）：fastembed ＋ bge-small-zh-v1.5 模型离线预置
# （hf-mirror 预下载五文件＋HF_HUB_OFFLINE=1——大陆连不上 HuggingFace 是既知事实，
# 不预置首启挂死）。原脚本 scripts/embedding_server.py 不动：其 bind 127.0.0.1 在
# 容器内够不着 webnet，构建期注入 EMBED_HOST 支持（默认仍 127.0.0.1，本地开发不变）。
# 构建上下文＝aiplatform-server/scripts/。
FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple fastembed
ENV FASTEMBED_CACHE_DIR=/cache/fastembed HF_HUB_OFFLINE=1
RUN mkdir -p /cache/fastembed/fast-bge-small-zh-v1.5 \
    && cd /cache/fastembed/fast-bge-small-zh-v1.5 \
    && for f in config.json tokenizer.json tokenizer_config.json special_tokens_map.json model_optimized.onnx; do \
        curl -fsSL -O "https://hf-mirror.com/Qdrant/bge-small-zh-v1.5/resolve/main/$f"; \
    done
COPY embedding_server.py .
RUN sed -i 's/"127.0.0.1", PORT)/EMBED_HOST, PORT)/' embedding_server.py \
    && sed -i 's/^PORT = /EMBED_HOST = os.environ.get("EMBED_HOST", "127.0.0.1")\nPORT = /' embedding_server.py \
    && grep -n "EMBED_HOST" embedding_server.py \
    && python -m py_compile embedding_server.py
ENV EMBED_HOST=0.0.0.0
EXPOSE 9091
CMD ["python", "embedding_server.py"]
