# Build stage: install the Python packages into a virtualenv that the runtime stage copies.
FROM python:3.11.16-slim AS build
RUN python -m venv /venv
COPY requirements.txt .
# xgboost's Linux wheel drags in nvidia-nccl (470 MB), which only GPU training uses. Models here run on the CPU.
RUN /venv/bin/pip install --no-cache-dir -r requirements.txt \
    && /venv/bin/pip uninstall -y nvidia-nccl-cu12 setuptools wheel

FROM python:3.11.16-slim
# XGBoost needs the OpenMP runtime, which the slim image lacks.
# Also: pending OS security updates, and the base image's own pip tooling removed (the service runs
# from /venv and never installs anything).
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/* && pip uninstall -y setuptools wheel pip \
    && useradd --system --create-home app
COPY --from=build /venv /venv
ENV PATH=/venv/bin:$PATH PYTHONUNBUFFERED=1

# Same layout as the repository (ml-service/ next to docs/), so the training scripts work unchanged:
#   docker compose exec ml-service python -m training.train_rul
WORKDIR /srv/ml-service
COPY app ./app
COPY training ./training
RUN mkdir -p /srv/docs training/data && chown -R app /srv/docs training/data
USER app
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health', timeout=3)" || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
