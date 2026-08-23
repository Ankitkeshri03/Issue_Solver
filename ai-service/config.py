import os

from dotenv import load_dotenv

load_dotenv()

DATABASE_URL = os.getenv(
    "DATABASE_URL", "postgresql://gitissuesolver:gitissuesolver@localhost:5433/gitissuesolver"
)
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
GITHUB_TOKEN = os.getenv("GITHUB_TOKEN", "")

# Mock mode runs the full analyze -> plan -> code -> test -> PR pipeline with deterministic,
# rule-based logic instead of live Gemini calls, so the loop is provable without API keys.
# Set MOCK_LLM=false once GEMINI_API_KEY is provided to use the real model.
MOCK_LLM = os.getenv("MOCK_LLM", "true").lower() in ("1", "true", "yes") or not GEMINI_API_KEY

WORKSPACE_DIR = os.getenv("WORKSPACE_DIR", os.path.join(os.path.dirname(__file__), "..", "workspace"))
EMBEDDING_DIM = 768
MAX_TEST_RETRIES = 3

# Model names are env-configurable because Google retires/rotates them regularly --
# hardcoding these previously caused hard 404 failures when text-embedding-004 and
# gemini-1.5-flash were retired.
#
# Chat default balances two failure modes seen in practice:
#   - Dated model names get retired (gemini-1.5-flash 404'd; gemini-2.5-flash became
#     unavailable to new accounts), so a rolling "-latest" alias is safer.
#   - The plain gemini-flash-latest alias resolves to the newest flagship flash model,
#     which carries the tightest free-tier quota (5 req/min) and 429'd immediately.
# The lite alias is always current AND has a more generous free-tier limit.
# Override with a flagship model if you're on a paid plan and want higher quality.
GEMINI_CHAT_MODEL = os.getenv("GEMINI_CHAT_MODEL", "models/gemini-flash-lite-latest")
GEMINI_EMBED_MODEL = os.getenv("GEMINI_EMBED_MODEL", "models/gemini-embedding-001")

# Free-tier Gemini quotas are per-minute and small, so LLM calls are retried with backoff
# rather than failing the whole run on a transient 429.
LLM_MAX_RETRIES = int(os.getenv("LLM_MAX_RETRIES", "5"))
LLM_RETRY_BASE_DELAY = float(os.getenv("LLM_RETRY_BASE_DELAY", "5"))

# The coding agent makes one LLM call per file it rewrites. Capping this bounds both
# quota burn and blast radius of a single automated fix.
MAX_FILES_TO_EDIT = int(os.getenv("MAX_FILES_TO_EDIT", "3"))

# Embedding requests are batched; Gemini accepts up to 100 texts per call.
EMBED_BATCH_SIZE = int(os.getenv("EMBED_BATCH_SIZE", "50"))
