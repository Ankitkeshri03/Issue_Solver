def build_query(issue_title: str, issue_description: str) -> str:
    """Normalizes an issue into a single text blob used as the RAG query."""
    return f"{issue_title}\n{issue_description or ''}".strip()
