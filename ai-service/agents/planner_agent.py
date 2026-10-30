from llm import get_chat_llm, invoke_chat


def make_plan(
    issue_title: str,
    issue_description: str,
    retrieved: list[dict],
    test_command: str = "the project's Maven or Gradle test command",
) -> str:
    llm = get_chat_llm()
    if llm is None:
        return _mock_plan(issue_title, issue_description or "", test_command)

    context = "\n\n".join(f"# {r['file_path']}\n{r['code_chunk']}" for r in retrieved)
    prompt = (
        "You are a senior Java/Spring Boot engineer. Given the GitHub issue below and the "
        "relevant code retrieved from the repository, write a short, concrete, numbered "
        "step-by-step plan to fix it. Do not write code, just the plan.\n\n"
        f"Verify changes using this project's detected test command: {test_command}.\n\n"
        f"Issue title: {issue_title}\nIssue description: {issue_description}\n\n"
        f"Relevant code:\n{context}"
    )
    return invoke_chat(llm, prompt, "planning")


def _mock_plan(title: str, description: str, test_command: str) -> str:
    text = f"{title} {description}".lower()

    if "nullpointer" in text or "npe" in text:
        return (
            "1. In UserService.getEmailDomain, check the result of userRepository.findByEmail "
            "for null before dereferencing it.\n"
            "2. Throw java.util.NoSuchElementException with a clear message when no user matches.\n"
            f"3. Re-run {test_command} to confirm getEmailDomain_unknownEmail_throwsNoSuchElement passes."
        )
    if "404" in text or "http status" in text or "status code" in text:
        return (
            "1. In UserController.getUser, check if UserService.getUserById returns null.\n"
            "2. Return ResponseEntity.notFound().build() when the user doesn't exist, "
            "otherwise ResponseEntity.ok(user).\n"
            f"3. Re-run {test_command} to confirm getUser_unknownId_returns404 passes."
        )
    if "validation" in text or "empty" in text:
        return (
            "1. In UserService.createUser, validate that email is non-null and non-blank "
            "before constructing/saving the User.\n"
            "2. Throw IllegalArgumentException when validation fails.\n"
            f"3. Re-run {test_command} to confirm createUser_blankEmail_isRejected passes."
        )
    if "calculation" in text or "discount" in text or "price" in text:
        return (
            "1. In PricingService.calculateDiscountedPrice, change the formula from "
            "'price - discountPercent' to 'price - (price * discountPercent / 100)'.\n"
            f"2. Re-run {test_command} to confirm PricingServiceTest passes."
        )
    if "exception handling" in text or "dataaccess" in text or "db call" in text:
        return (
            "1. In UserService.updateUserEmail, wrap the userRepository.save(...) call in a "
            "try/catch for DataAccessException.\n"
            "2. On catch, rethrow as IllegalStateException with a clear message instead of "
            "letting the raw DataAccessException propagate.\n"
            f"3. Re-run {test_command} to confirm updateUserEmail_dbFailure_* tests pass."
        )
    return (
        "1. Read the referenced file(s) and locate the code path described in the issue.\n"
        "2. Apply the minimal fix that satisfies the issue's expected behavior.\n"
        f"3. Re-run {test_command} to confirm no regressions."
    )
