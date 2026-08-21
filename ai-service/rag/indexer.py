from db import get_conn, vector_literal
from llm import embed_text
from tools.file_tools import chunk_file, list_java_files, read_file


def index_repo(repo_id: int, repo_path: str) -> int:
    """Chunks every Java file in the repo, embeds each chunk, and stores it in
    pgvector under this repo_id. Re-indexing replaces prior chunks for the repo.
    Returns the number of chunks stored.
    """
    java_files = list_java_files(repo_path)
    rows = []
    for file_path in java_files:
        content = read_file(repo_path, file_path)
        for chunk in chunk_file(content):
            embedding = embed_text(f"{file_path}\n{chunk}")
            rows.append((file_path, chunk, embedding))

    with get_conn() as conn, conn.cursor() as cur:
        cur.execute("DELETE FROM code_embeddings WHERE repo_id = %s", (repo_id,))
        for file_path, chunk, embedding in rows:
            cur.execute(
                """
                INSERT INTO code_embeddings (repo_id, file_path, code_chunk, embedding)
                VALUES (%s, %s, %s, %s::vector)
                """,
                (repo_id, file_path, chunk, vector_literal(embedding)),
            )
        cur.execute("UPDATE repos SET indexed_at = now() WHERE id = %s", (repo_id,))
    return len(rows)


def is_indexed(repo_id: int) -> bool:
    with get_conn() as conn, conn.cursor() as cur:
        cur.execute("SELECT 1 FROM code_embeddings WHERE repo_id = %s LIMIT 1", (repo_id,))
        return cur.fetchone() is not None
