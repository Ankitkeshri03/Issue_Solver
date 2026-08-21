"""Known bug categories seeded in the dummy user-service repo (see workspace/user-service/issues/).

Used in mock mode to (a) decide which deterministic patch to apply and (b) scope the
mvn test run to just the test(s) covering that issue — mirroring how a real coding agent
should run only the tests relevant to the ticket, not the whole suite, so fixing issue #1
isn't blocked by unrelated pre-existing failures in issues #2-5.
"""

BUG_CATALOG = [
    {
        "id": "npe",
        "keywords": ["nullpointer", "npe"],
        "test_filter": "UserServiceTest#getEmailDomain_unknownEmail_throwsNoSuchElement+getEmailDomain_knownEmail_returnsDomain",
    },
    {
        "id": "http_status",
        "keywords": ["404", "http status", "status code"],
        "test_filter": "UserControllerTest",
    },
    {
        "id": "validation",
        "keywords": ["validation", "empty"],
        "test_filter": "UserServiceTest#createUser_blankEmail_isRejected+createUser_validEmail_isSaved",
    },
    {
        "id": "calculation",
        "keywords": ["calculation", "discount", "price"],
        "test_filter": "PricingServiceTest",
    },
    {
        "id": "exception_handling",
        "keywords": ["exception handling", "dataaccess", "db call"],
        "test_filter": "UserServiceTest#updateUserEmail_dbFailure_isHandledNotPropagatedRaw+updateUserEmail_dbFailure_doesNotLeakDataAccessException",
    },
]


def match_categories(issue_text: str) -> list[dict]:
    text = issue_text.lower()
    return [b for b in BUG_CATALOG if any(k in text for k in b["keywords"])]


def test_filter_for(issue_text: str) -> str | None:
    matched = match_categories(issue_text)
    if not matched:
        return None
    return ",".join(b["test_filter"] for b in matched)
