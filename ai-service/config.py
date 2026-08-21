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
