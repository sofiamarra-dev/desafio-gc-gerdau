FROM python:3.11-slim-bookworm
WORKDIR /app
RUN pip install --no-cache-dir Flask==3.1.2 waitress==3.0.2
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
COPY app.py index.html ./
USER app
EXPOSE 5000
CMD ["waitress-serve", "--listen=0.0.0.0:5000", "--threads=1", "app:app"]
