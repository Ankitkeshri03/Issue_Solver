import os


def list_java_files(repo_path: str) -> list[str]:
    java_files = []
    for root, _dirs, files in os.walk(repo_path):
        if "/target/" in root or root.endswith("/target"):
            continue
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.relpath(os.path.join(root, f), repo_path))
    return sorted(java_files)


def read_file(repo_path: str, relative_path: str) -> str:
    with open(os.path.join(repo_path, relative_path), "r", encoding="utf-8") as fh:
        return fh.read()


def write_file(repo_path: str, relative_path: str, content: str) -> None:
    full_path = os.path.join(repo_path, relative_path)
    os.makedirs(os.path.dirname(full_path), exist_ok=True)
    with open(full_path, "w", encoding="utf-8") as fh:
        fh.write(content)


def chunk_file(content: str, chunk_size: int = 120, overlap: int = 20) -> list[str]:
    """Splits file content into overlapping line-based chunks (simple, language-agnostic)."""
    lines = content.splitlines()
    if not lines:
        return []
    chunks = []
    step = max(chunk_size - overlap, 1)
    for start in range(0, len(lines), step):
        chunk = "\n".join(lines[start : start + chunk_size])
        if chunk.strip():
            chunks.append(chunk)
        if start + chunk_size >= len(lines):
            break
    return chunks
