from rag.indexer import index_repo, is_indexed
from rag.retriever import search


def find_relevant_files(repo_id: int, repo_path: str, query: str, top_k: int = 5) -> list[dict]:
    if not is_indexed(repo_id):
        index_repo(repo_id, repo_path)
    results = search(repo_id, query, top_k=top_k)

    # collapse to one best chunk per file, preserving rank order
    seen_files = set()
    deduped = []
    for r in results:
        if r["file_path"] in seen_files:
            continue
        seen_files.add(r["file_path"])
        deduped.append(r)
    return deduped
