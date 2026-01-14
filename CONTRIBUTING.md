# Contributing to iCloud Calendar MCP Server

Thank you for your interest in contributing! This document provides guidelines for contributing to the project.

## Code of Conduct

Be respectful and constructive in all interactions. We welcome contributors of all backgrounds and experience levels.

## Getting Started

### Prerequisites

- Java 17 or higher
- Gradle (wrapper included)
- Git

### Development Setup

1. Fork and clone the repository:
   ```bash
   git clone https://github.com/icloud-calendar-mcp/icloud-calendar-mcp.git
   cd icloud-calendar-mcp
   ```

2. Build the project:
   ```bash
   ./gradlew build
   ```

3. Run tests:
   ```bash
   ./gradlew test
   ```

4. Set up test credentials (optional, for integration testing):
   ```bash
   cp .env.example .env
   # Edit .env with your iCloud test credentials
   ```

## Making Changes

### Branch Naming

- `feature/description` - New features
- `fix/description` - Bug fixes
- `security/description` - Security improvements
- `docs/description` - Documentation updates

### Code Style

We follow standard Kotlin conventions:

- Use 4-space indentation
- Maximum line length of 120 characters
- Use meaningful variable and function names
- Document public APIs with KDoc comments

### Testing Requirements

All contributions must include tests:

- **New features**: Add unit tests and integration tests
- **Bug fixes**: Add a test that reproduces the bug
- **Security fixes**: Add adversarial tests

Run the full test suite before submitting:
```bash
./gradlew test
```

### Security Considerations

This is a security-sensitive project. Please ensure:

1. **No credential exposure** - Never log or expose credentials
2. **Input validation** - Validate all user inputs
3. **Error handling** - Don't expose sensitive data in errors
4. **Injection prevention** - Properly escape all user data
5. **ReDoS prevention** - Test regex patterns for catastrophic backtracking
6. **SSRF prevention** - Validate URLs and block internal networks

See [SECURITY.md](SECURITY.md) for security guidelines.

## Test Categories

The project has 555 tests across these categories:

### Security Tests (239 tests)

| Category | Tests | Description |
|----------|-------|-------------|
| `AdversarialTest` | 53 | SQL injection, XSS, path traversal, command injection |
| `UnicodeSecurityTest` | 38 | Homoglyphs, normalization, RTL override |
| `McpLoggerSecurityTest` | 31 | Log injection, credential sanitization |
| `OwaspMcpSecurityTest` | 29 | OWASP MCP Top 10 attack vectors |
| `ProgressSecurityTest` | 27 | Token security, injection, state manipulation |
| `ReDoSSecurityTest` | 25 | Catastrophic backtracking, resource exhaustion |
| `CancellationSecurityTest` | 22 | Replay attacks, race conditions |
| `CredentialManagerTest` | 14 | Token masking, secure storage |

### Functional Tests (316 tests)

| Category | Tests | Description |
|----------|-------|-------------|
| CalDAV Protocol | 105 | XML parsing, HTTP client, models |
| ICS Parsing | 48 | RFC 5545 compliance, edge cases |
| Input Validation | 39 | All parameter validation rules |
| Error Handling | 32 | Secure error responses |
| Integration | 26 | End-to-end tool execution |
| Service Layer | 21 | Calendar operations, caching |
| Rate Limiting | 15 | Concurrent access, window reset |
| Cancellation | 12 | Operation cancellation, cleanup |
| Logging | 9 | MCP logging compliance |
| Progress | 9 | Progress reporting |

## Pull Request Process

### Before Submitting

1. Run all tests: `./gradlew test`
2. Ensure clean build: `./gradlew clean build`
3. Update documentation if needed
4. Add yourself to contributors (optional)

### PR Requirements

- Clear description of changes
- Reference any related issues
- All tests passing
- No decrease in test coverage
- Security review for security-related changes

### Review Process

1. Submit PR against `main` branch
2. Automated tests run via GitHub Actions
3. Maintainer review within 3-5 business days
4. Address feedback and update PR
5. Squash and merge when approved

## Types of Contributions

### Bug Reports

Open an issue with:
- Clear description
- Steps to reproduce
- Expected vs actual behavior
- Environment details (Java version, OS)

### Feature Requests

Open an issue with:
- Use case description
- Proposed solution (optional)
- Alternatives considered (optional)

### Security Vulnerabilities

**Do not open public issues for security vulnerabilities.**

See [SECURITY.md](SECURITY.md) for responsible disclosure process.

### Documentation

- Fix typos and improve clarity
- Add examples and use cases
- Translate documentation

## Development Tips

### Running Individual Tests

```bash
# Single test class
./gradlew test --tests "AdversarialTest"

# Single test method
./gradlew test --tests "AdversarialTest.MCP01*"

# Tests matching pattern
./gradlew test --tests "*CalDav*"

# All security tests
./gradlew test --tests "*SecurityTest*"

# OWASP MCP tests
./gradlew test --tests "OwaspMcpSecurityTest"

# Unicode security tests
./gradlew test --tests "UnicodeSecurityTest"

# ReDoS tests
./gradlew test --tests "ReDoSSecurityTest"
```

### Building Fat JAR

```bash
./gradlew fatJar
# Output: build/libs/icloud-calendar-mcp-1.0.0-all.jar
```

### Testing with MCP Inspector

```bash
ICLOUD_USERNAME="test@icloud.com" \
ICLOUD_PASSWORD="test-password" \
npx @mcp-use/inspector java -jar build/libs/icloud-calendar-mcp-1.0.0-all.jar
```

### Adding Security Tests

When adding security tests, follow this pattern:

```kotlin
@Test
@Timeout(value = 2, unit = TimeUnit.SECONDS)
fun `attack vector is mitigated`() {
    val maliciousInput = "..." // Attack payload

    val result = systemUnderTest.process(maliciousInput)

    // Assert attack was blocked or sanitized
    assertFalse(result.contains(sensitiveData))
}
```

Key considerations:
- Always add timeout to prevent DoS during testing
- Test both blocking and sanitization behaviors
- Document the attack vector being tested
- Include edge cases (unicode, encoding, etc.)

## Questions?

- Open a GitHub Discussion for general questions
- Open an Issue for bugs or feature requests
- See [SECURITY.md](SECURITY.md) for security concerns

Thank you for contributing!
