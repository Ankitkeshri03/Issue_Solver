import hashlib
import logging
import re
import time

import numpy as np

from config import (
    EMBED_BATCH_SIZE,
    EMBEDDING_DIM,
    GEMINI_API_KEY,
    GEMINI_CHAT_MODEL,
    GEMINI_EMBED_MODEL,
    LLM_MAX_RETRIES,
    LLM_RETRY_BASE_DELAY,
    MOCK_LLM,
)

logger = logging.getLogger(__name__)


class LlmQuotaError(RuntimeError):
    """Raised when Gemini's rate limit is still exhausted after all retries.

    Distinct from other failures so the API layer can report it as a 429 with an
    actionable message instead of an opaque 500.
    """


def _is_quota_error(exc: Exception) -> bool:
    return "429" in str(exc) or "ResourceExhausted" in type(exc).__name__ or "quota" in str(exc).lower()


def _suggested_delay(exc: Exception, attempt: int) -> float:
    """Gemini's 429 body carries a retry_delay; honor it when present, else back off."""
    match = re.search(r"retry_delay\s*{\s*seconds:\s*(\d+)", str(exc))
    if match:
        return float(match.group(1)) + 1.0
    return LLM_RETRY_BASE_DELAY * (2 ** (attempt - 1))


def _with_retry(operation, description: str):
    """Runs `operation`, retrying on Gemini quota (429) errors with backoff."""
    last_exc = None
    for attempt in range(1, LLM_MAX_RETRIES + 1):
        try:
            return operation()
        except Exception as exc:  # noqa: BLE001 - provider raises several unrelated types
            last_exc = exc
            if not _is_quota_error(exc):
                raise
            if attempt == LLM_MAX_RETRIES:
                break
            delay = _suggested_delay(exc, attempt)
            logger.warning(
                "Gemini quota hit during %s (attempt %d/%d); retrying in %.1fs",
                description, attempt, LLM_MAX_RETRIES, delay,
            )
            time.sleep(delay)

    raise LlmQuotaError(
        f"Gemini rate limit still exhausted after {LLM_MAX_RETRIES} attempts during {description}. "
        f"Free-tier quotas are per-minute and small -- wait a minute and retry, set "
        f"GEMINI_CHAT_MODEL to a higher-quota model, or upgrade the API plan. "
        f"Original error: {last_exc}"
    ) from last_exc


def _embedder():
    from langchain_google_genai import GoogleGenerativeAIEmbeddings

    return GoogleGenerativeAIEmbeddings(model=GEMINI_EMBED_MODEL, google_api_key=GEMINI_API_KEY)


def embed_text(text: str) -> list[float]:
    if MOCK_LLM:
        return _mock_embedding(text)
    return embed_texts([text])[0]


def embed_texts(texts: list[str]) -> list[list[float]]:
    """Batch variant -- one API call per EMBED_BATCH_SIZE texts instead of per text.

    Indexing a real repo produces hundreds of chunks; embedding them one-by-one burned
    hundreds of requests against a per-minute quota and took ~a minute per repo.
    """
    if MOCK_LLM:
        return [_mock_embedding(t) for t in texts]

    embedder = _embedder()
    vectors: list[list[float]] = []
    for start in range(0, len(texts), EMBED_BATCH_SIZE):
        batch = texts[start : start + EMBED_BATCH_SIZE]
        # gemini-embedding-001 returns 3072 dims by default; output_dimensionality pins it
        # to the pgvector column width (Matryoshka truncation, natively supported).
        vectors.extend(
            _with_retry(
                lambda b=batch: embedder.embed_documents(b, output_dimensionality=EMBEDDING_DIM),
                f"embedding batch of {len(batch)}",
            )
        )
    return vectors


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

    return ChatGoogleGenerativeAI(model=GEMINI_CHAT_MODEL, google_api_key=GEMINI_API_KEY, temperature=0.2)


def invoke_chat(llm, prompt: str, description: str = "chat completion") -> str:
    """Single entry point for chat calls so every one of them gets quota retry."""
    response = _with_retry(lambda: llm.invoke(prompt), description)
    return response.content
