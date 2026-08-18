FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN groupadd --gid 10001 agenthub && useradd --uid 10001 --gid agenthub --create-home agenthub
WORKDIR /app
COPY python-engine/requirements.txt ./
RUN python -m pip install --no-cache-dir -r requirements.txt
COPY python-engine/agent_runtime ./agent_runtime
COPY python-engine/main.py ./main.py
USER 10001:10001
EXPOSE 8000 9091
CMD ["python", "main.py"]
