# Demo data only: real vehicles publish to MQTT themselves. In production it runs under the "demo" profile.
FROM python:3.11.16-slim
RUN useradd --system --create-home app
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY simulator.py .
# simulator.py keeps its wear state (state.json) next to itself
RUN chown app /app
USER app
ENV PYTHONUNBUFFERED=1
ENTRYPOINT ["python", "simulator.py"]
