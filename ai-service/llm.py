import hashlib

import numpy as np

from config import EMBEDDING_DIM, GEMINI_API_KEY, MOCK_LLM


def embed_text(text: str) -> list[float]:
    if MOCK_LLM:
        return _mock_embedding(text)
    from langchain_google_genai import GoogleGenerativeAIEmbeddings

    # text-embedding-004 was retired by Google; gemini-embedding-001 is the current model.
    # It defaults to 3072-dim output, so output_dimensionality is pinned to EMBEDDING_DIM
    # (768) to match the pgvector column -- Gemini's embeddings support this truncation
    # natively (Matryoshka representation learning), it's not a lossy hack.
    embedder = GoogleGenerativeAIEmbeddings(model="models/gemini-embedding-001", google_api_key=GEMINI_API_KEY)
    return embedder.embed_documents([text], output_dimensionality=EMBEDDING_DIM)[0]


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

    # gemini-1.5-flash was retired by Google. gemini-flash-latest is a rolling alias
    # (rather than a dated model name) specifically to avoid this class of breakage
    # recurring every time Google rotates their model lineup.
    return ChatGoogleGenerativeAI(model="models/gemini-flash-latest", google_api_key=GEMINI_API_KEY, temperature=0.2)
