# syntax=docker/dockerfile:1
FROM python:3.11-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHON_DATA_DIR=/data \
    HF_HOME=/models \
    TOKENIZERS_PARALLELISM=false
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY ai-python/requirements-docker.txt ./requirements-docker.txt
ARG TORCH_VERSION=2.14.1
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --index-url https://download.pytorch.org/whl/cpu "torch==${TORCH_VERSION}" \
    && pip install -r requirements-docker.txt
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app \
    && mkdir /data /models && chown app:app /data /models
COPY ai-python ./ai-python
USER app
EXPOSE 8001
CMD ["python", "-m", "uvicorn", "ai-python.main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "1"]
