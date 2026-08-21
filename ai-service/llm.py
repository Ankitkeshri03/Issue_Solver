import hashlib

import numpy as np

from config import EMBEDDING_DIM, GEMINI_API_KEY, MOCK_LLM


def embed_text(text: str) -> list[float]:
    if MOCK_LLM:
        return _mock_embedding(text)
    from langchain_google_genai import GoogleGenerativeAIEmbeddings

    embedder = GoogleGenerativeAIEmbeddings(model="models/text-embedding-004", google_api_key=GEMINI_API_KEY)
    return embedder.embed_query(text)


def _mock_embedding(text: str) -> list[float]:
    """Deterministic bag-of-words feature-hashing embedding.

    Not semantically trained, but lexically meaningful: chunks that share more
    vocabulary with the query land closer in cosine space, which is enough to
    exercise the real pgvector retrieval path end-to-end without a live API key.
    """
    vec = np.zeros(EMBEDDING_DIM, dtype=np.float64)
    for token in text.lower().split():
        token = "".join(ch for ch in token if ch.isalnum())
        if not token:
            continue
        digest = hashlib.sha256(token.encode()).hexdigest()
        idx = int(digest, 16) % EMBEDDING_DIM
        vec[idx] += 1.0
    norm = np.linalg.norm(vec)
    if norm > 0:
        vec = vec / norm
    return vec.tolist()


def get_chat_llm():
    if MOCK_LLM:
        return None
    from langchain_google_genai import ChatGoogleGenerativeAI

    return ChatGoogleGenerativeAI(model="gemini-1.5-flash", google_api_key=GEMINI_API_KEY, temperature=0.2)
