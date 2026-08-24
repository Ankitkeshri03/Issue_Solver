import os
import re

from agents.bug_catalog import match_categories
from config import MAX_FILES_TO_EDIT
from llm import get_chat_llm, invoke_chat
from tools.file_tools import read_file, write_file

SERVICE_FILE = "src/main/java/com/example/userservice/service/UserService.java"
CONTROLLER_FILE = "src/main/java/com/example/userservice/controller/UserController.java"
PRICING_FILE = "src/main/java/com/example/userservice/service/PricingService.java"

# Sentinel the model returns when a retrieved file needs no change.
_UNCHANGED = "UNCHANGED"

_FIXERS = {
    "npe": lambda repo_path: _fix_npe(repo_path),
    "http_status": lambda repo_path: _fix_http_status(repo_path),
    "validation": lambda repo_path: _fix_validation(repo_path),
    "calculation": lambda repo_path: _fix_calculation(repo_path),
    "exception_handling": lambda repo_path: _fix_exception_handling(repo_path),
}


def apply_fix(repo_path: str, issue_text: str, plan: str, relevant_files: list[str], feedback: str | None = None) -> list[str]:
    """Applies the fix and returns the repo-relative paths actually written.

    The caller commits exactly these paths -- committing with `git add -A` instead swept
    in build output (target/**) for repos that don't gitignore it, producing PRs with
    dozens of .class files alongside the three real source changes.
    """
    llm = get_chat_llm()
    if llm is None:
        return _apply_mock_fix(repo_path, issue_text)
    return _apply_llm_fix(repo_path, plan, relevant_files, feedback, llm)


# --- mock mode: deterministic, known-good patches for the 5 seeded bugs -----------------

def _apply_mock_fix(repo_path: str, issue_text: str) -> list[str]:
    edited: list[str] = []
    for category in match_categories(issue_text):
        edited.extend(_FIXERS[category["id"]](repo_path) or [])
    return edited


def _fix_npe(repo_path: str) -> list[str]:
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
    if old not in content:
        return []
    write_file(repo_path, SERVICE_FILE, content.replace(old, new))
    return [SERVICE_FILE]


def _fix_validation(repo_path: str) -> list[str]:
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
    if old not in content:
        return []
    write_file(repo_path, SERVICE_FILE, content.replace(old, new))
    return [SERVICE_FILE]


def _fix_exception_handling(repo_path: str) -> list[str]:
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
    if old not in content:
        return []
    write_file(repo_path, SERVICE_FILE, content.replace(old, new))
    return [SERVICE_FILE]


def _fix_http_status(repo_path: str) -> list[str]:
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
    if old not in content:
        return []
    write_file(repo_path, CONTROLLER_FILE, content.replace(old, new))
    return [CONTROLLER_FILE]


def _fix_calculation(repo_path: str) -> list[str]:
    content = read_file(repo_path, PRICING_FILE)
    old = "        return price - discountPercent;"
    new = "        return price - (price * discountPercent / 100);"
    if old not in content:
        return []
    write_file(repo_path, PRICING_FILE, content.replace(old, new))
    return [PRICING_FILE]


# --- real LLM mode: ask the model to rewrite each relevant file in full -----------------

def _apply_llm_fix(repo_path: str, plan: str, relevant_files: list[str], feedback: str | None, llm) -> list[str]:
    # One LLM call per file, so cap how many files a single fix attempt rewrites: bounds
    # both free-tier quota burn and the blast radius of an automated change. relevant_files
    # is ranked by retrieval relevance, so the cap keeps the best candidates.
    edited: list[str] = []
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
            "Retrieval is fuzzy, so this file may have nothing to do with the plan. Decide first "
            "whether THIS file needs to change. If the plan targets a different class or file, "
            f"reply with exactly {_UNCHANGED} and nothing else. Never move a class into this file, "
            "never rename its top-level type, and never replace unrelated code — a Java file must "
            "keep declaring the same public type it declares now.\n\n"
            f"Plan:\n{plan}{feedback_block}\n\n"
            f"File: {file_path}\n{original}"
        )
        fixed = invoke_chat(llm, prompt, f"rewriting {file_path}").strip()
        if fixed.startswith("```"):
            fixed = fixed.split("\n", 1)[1] if "\n" in fixed else fixed
            if fixed.endswith("```"):
                fixed = fixed.rsplit("```", 1)[0]
        fixed = fixed.strip()

        if not fixed or fixed == _UNCHANGED:
            continue
        if fixed == original.strip():
            continue
        if not _declares_same_public_type(file_path, original, fixed):
            # The model rewrote this file into some other class (the failure mode that
            # clobbered AuthController.java with a second GlobalExceptionHandler). Dropping
            # the write is always safer than committing a file that cannot compile.
            print(f"[coding_agent] skipped {file_path}: rewrite changed the public type")
            continue

        write_file(repo_path, file_path, fixed if fixed.endswith("\n") else fixed + "\n")
        edited.append(file_path)

    return edited


def _declares_same_public_type(file_path: str, original: str, fixed: str) -> bool:
    """A Java rewrite must still declare the same public type the file declared before.

    javac requires the public type to match the filename, so a rewrite that renames it
    can never compile -- which is exactly how a plan aimed at one class ended up
    overwriting another file with a copy of that class.
    """
    if not file_path.endswith(".java"):
        return True
    return _public_type_name(fixed) == _public_type_name(original)


def _public_type_name(source: str) -> str | None:
    match = re.search(
        r"^\s*public\s+(?:final\s+|abstract\s+|sealed\s+|non-sealed\s+|static\s+)*"
        r"(?:class|interface|enum|record)\s+(\w+)",
        source,
        re.MULTILINE,
    )
    return match.group(1) if match else None
