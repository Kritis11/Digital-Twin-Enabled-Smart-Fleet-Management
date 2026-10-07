# Demo data only: real vehicles publish to MQTT themselves. In production it runs under the "demo" profile.
FROM python:3.11.16-slim
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/* && useradd --system --create-home app
WORKDIR /app
COPY requirements.txt .
# afterwards pip and its tooling go: nothing is installed at run time
RUN pip install --no-cache-dir -r requirements.txt && pip uninstall -y setuptools wheel pip
COPY simulator.py .
# The wear state carried from a fast-forward into live mode; mount a volume here to keep it.
RUN mkdir /app/state && chown app /app/state
USER app
ENV PYTHONUNBUFFERED=1 SIM_STATE_FILE=/app/state/state.json
ENTRYPOINT ["python", "simulator.py"]
