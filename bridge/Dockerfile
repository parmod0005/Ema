FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /srv/vardhani
COPY bridge/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY bridge/app ./app

RUN useradd --create-home --uid 10001 vardhani && \
    mkdir -p /srv/vardhani/bridge-data && \
    chown -R vardhani:vardhani /srv/vardhani
USER vardhani

EXPOSE 8080
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--proxy-headers"]
