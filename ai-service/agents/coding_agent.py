from agents.bug_catalog import match_categories
from config import MAX_FILES_TO_EDIT
from llm import get_chat_llm, invoke_chat
from tools.file_tools import read_file, write_file

SERVICE_FILE = "src/main/java/com/example/userservice/service/UserService.java"
CONTROLLER_FILE = "src/main/java/com/example/userservice/controller/UserController.java"
PRICING_FILE = "src/main/java/com/example/userservice/service/PricingService.java"

_FIXERS = {
    "npe": lambda repo_path: _fix_npe(repo_path),
    "http_status": lambda repo_path: _fix_http_status(repo_path),
    "validation": lambda repo_path: _fix_validation(repo_path),
    "calculation": lambda repo_path: _fix_calculation(repo_path),
    "exception_handling": lambda repo_path: _fix_exception_handling(repo_path),
}


def apply_fix(repo_path: str, issue_text: str, plan: str, relevant_files: list[str], feedback: str | None = None) -> None:
    llm = get_chat_llm()
    if llm is None:
        _apply_mock_fix(repo_path, issue_text)
    else:
        _apply_llm_fix(repo_path, plan, relevant_files, feedback, llm)


# --- mock mode: deterministic, known-good patches for the 5 seeded bugs -----------------

def _apply_mock_fix(repo_path: str, issue_text: str) -> None:
    for category in match_categories(issue_text):
        _FIXERS[category["id"]](repo_path)


def _fix_npe(repo_path: str) -> None:
    content = read_file(repo_path, SERVICE_FILE)
    old = (
        "    public String getEmailDomain(String email) {\n"
        "        User user = userRepository.findByEmail(email);\n"
        "        return user.getEmail().substring(user.getEmail().indexOf('@') + 1);\n"
        "    }"
    )
    new = (
        "    public String getEmailDomain(String email) {\n"
        "        User user = userRepository.findByEmail(email);\n"
        "        if (user == null) {\n"
        "            throw new java.util.NoSuchElementException(\"No user found for email: \" + email);\n"
        "        }\n"
        "        return user.getEmail().substring(user.getEmail().indexOf('@') + 1);\n"
        "    }"
    )
    if old in content:
        write_file(repo_path, SERVICE_FILE, content.replace(old, new))


def _fix_validation(repo_path: str) -> None:
    content = read_file(repo_path, SERVICE_FILE)
    old = (
        "    public User createUser(String name, String email) {\n"
        "        User user = new User(null, name, email);\n"
        "        return userRepository.save(user);\n"
        "    }"
    )
    new = (
        "    public User createUser(String name, String email) {\n"
        "        if (email == null || email.isBlank()) {\n"
        "            throw new IllegalArgumentException(\"email must not be null or blank\");\n"
        "        }\n"
        "        User user = new User(null, name, email);\n"
        "        return userRepository.save(user);\n"
        "    }"
    )
    if old in content:
        write_file(repo_path, SERVICE_FILE, content.replace(old, new))


def _fix_exception_handling(repo_path: str) -> None:
    content = read_file(repo_path, SERVICE_FILE)
    old = (
        "    public User updateUserEmail(Long id, String newEmail) {\n"
        "        User user = userRepository.findById(id).orElseThrow();\n"
        "        user.setEmail(newEmail);\n"
        "        return userRepository.save(user);\n"
        "    }"
    )
    new = (
        "    public User updateUserEmail(Long id, String newEmail) {\n"
        "        User user = userRepository.findById(id).orElseThrow();\n"
        "        user.setEmail(newEmail);\n"
        "        try {\n"
        "            return userRepository.save(user);\n"
        "        } catch (com.example.userservice.repository.DataAccessException e) {\n"
        "            throw new IllegalStateException(\"Failed to update user email: \" + e.getMessage(), e);\n"
        "        }\n"
        "    }"
    )
    if old in content:
        write_file(repo_path, SERVICE_FILE, content.replace(old, new))


def _fix_http_status(repo_path: str) -> None:
    content = read_file(repo_path, CONTROLLER_FILE)
    old = (
        "    public ResponseEntity<User> getUser(@PathVariable Long id) {\n"
        "        User user = userService.getUserById(id);\n"
        "        return ResponseEntity.ok(user);\n"
        "    }"
    )
    new = (
        "    public ResponseEntity<User> getUser(@PathVariable Long id) {\n"
        "        User user = userService.getUserById(id);\n"
        "        if (user == null) {\n"
        "            return ResponseEntity.notFound().build();\n"
        "        }\n"
        "        return ResponseEntity.ok(user);\n"
        "    }"
    )
    if old in content:
        write_file(repo_path, CONTROLLER_FILE, content.replace(old, new))


def _fix_calculation(repo_path: str) -> None:
    content = read_file(repo_path, PRICING_FILE)
    old = "        return price - discountPercent;"
    new = "        return price - (price * discountPercent / 100);"
    if old in content:
        write_file(repo_path, PRICING_FILE, content.replace(old, new))


# --- real LLM mode: ask the model to rewrite each relevant file in full -----------------

def _apply_llm_fix(repo_path: str, plan: str, relevant_files: list[str], feedback: str | None, llm) -> None:
    # One LLM call per file, so cap how many files a single fix attempt rewrites: bounds
    # both free-tier quota burn and the blast radius of an automated change. relevant_files
    # is ranked by retrieval relevance, so the cap keeps the best candidates.
    for file_path in relevant_files[:MAX_FILES_TO_EDIT]:
        try:
            original = read_file(repo_path, file_path)
        except FileNotFoundError:
            continue

        feedback_block = (
            f"\n\nThe previous attempt failed mvn test with this output — fix the root cause:\n{feedback}"
            if feedback
            else ""
        )
        prompt = (
            "You are a senior Java/Spring Boot engineer fixing a bug. Apply the plan below to "
            "the given file and return the COMPLETE corrected file content only — no markdown "
            "fences, no explanation.\n\n"
            f"Plan:\n{plan}{feedback_block}\n\n"
            f"File: {file_path}\n{original}"
        )
        fixed = invoke_chat(llm, prompt, f"rewriting {file_path}").strip()
        if fixed.startswith("```"):
            fixed = fixed.split("\n", 1)[1] if "\n" in fixed else fixed
            if fixed.endswith("```"):
                fixed = fixed.rsplit("```", 1)[0]
        write_file(repo_path, file_path, fixed)
