from db import get_conn, vector_literal
from llm import embed_text


def search(repo_id: int, query: str, top_k: int = 5) -> list[dict]:
    embedding = embed_text(query)
    with get_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT file_path, code_chunk, embedding <-> %s::vector AS distance
            FROM code_embeddings
            WHERE repo_id = %s
            ORDER BY embedding <-> %s::vector
            LIMIT %s
            """,
            (vector_literal(embedding), repo_id, vector_literal(embedding), top_k),
        )
        rows = cur.fetchall()
    return [{"file_path": r[0], "code_chunk": r[1], "distance": float(r[2])} for r in rows]
