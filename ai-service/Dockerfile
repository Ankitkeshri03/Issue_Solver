FROM python:3.11-slim

WORKDIR /app

# Maven is available globally; Gradle projects use their committed gradlew wrapper.
# curl/unzip also support wrapper scripts that bootstrap their own distributions.
RUN apt-get update && apt-get install -y --no-install-recommends git maven default-jdk-headless ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
