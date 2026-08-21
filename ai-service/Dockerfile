FROM python:3.11-slim

WORKDIR /app

# git is needed for GitPython operations against cloned repos
RUN apt-get update && apt-get install -y --no-install-recommends git maven default-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
