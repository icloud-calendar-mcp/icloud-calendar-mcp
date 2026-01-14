# Security Policy

## Security-First Design

iCloud Calendar MCP Server is designed with security as a primary concern. This document describes our security properties, controls, and vulnerability disclosure process.

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Security Controls

### Credential Management

| Control | Description |
|---------|-------------|
| **Environment Variables Only** | Credentials loaded exclusively from `ICLOUD_USERNAME` and `ICLOUD_PASSWORD` environment variables |
| **No File Storage** | Credentials are never written to disk or config files |
| **Masked Logging** | Credentials are masked in all log output (shows only first 3 characters) |
| **Memory Protection** | Credentials are not held longer than necessary |

### Input Validation

All user inputs are validated before processing:

| Input | Validation |
|-------|------------|
| Calendar IDs | Pattern matching, length limits, SSRF protection |
| Event Titles | Length limits (500 chars), character filtering |
| Dates | Format validation (YYYY-MM-DD) |
| Times | ISO 8601 format validation |
| Descriptions | Length limits (5000 chars) |
| Locations | Length limits (500 chars) |

### SSRF Protection

The server blocks potentially dangerous inputs:

| Category | Blocked Patterns |
|----------|-----------------|
| **Dangerous URI Schemes** | `file://`, `javascript:`, `data:`, `vbscript:`, `ftp://` |
| **Localhost** | `localhost`, `127.0.0.1`, `::1`, `0.0.0.0` |
| **Private Networks** | `10.x.x.x`, `192.168.x.x`, `172.16-31.x.x` |
| **Metadata Endpoints** | `169.254.x.x` (link-local addresses) |
| **Command Injection** | `;`, `|`, backticks, `$(...)`, `${...}` |
| **Path Traversal** | `..`, glob patterns (`*`, `?`, `[`, `{`) |

### Rate Limiting

Protection against abuse and denial of service:

| Operation | Limit |
|-----------|-------|
| Read operations | 60 per minute |
| Write operations | 20 per minute |
| Window | Sliding 60-second window |

### Error Handling

Secure error responses that prevent information leakage:

- Passwords, tokens, and secrets are redacted from error messages
- File paths are sanitized to remove usernames
- Email addresses are partially masked
- Stack traces are not exposed to clients
- Generic error messages for internal failures
- Message length limits prevent DoS (max 2000 chars)

### ReDoS Protection

All regex patterns are tested against catastrophic backtracking:

- Precompiled regex patterns for performance
- Timeout protection on all tests
- Input size limits before regex processing
- No nested quantifiers or overlapping alternations

### Unicode Security

Protection against encoding-based attacks:

| Attack Type | Protection |
|-------------|------------|
| **Homoglyphs** | Documented behavior, pattern matching limitations |
| **Zero-width Characters** | Pass-through (data integrity preserved) |
| **RTL Override** | Pass-through (no interpretation) |
| **Normalization** | NFC normalization for comparisons |
| **Encoding Attacks** | UTF-8 validation, URL encoding handling |

## OWASP MCP Top 10 Compliance

This server is tested against the [OWASP MCP Top 10](https://owasp.org/www-project-mcp-top-10/) security risks:

### MCP01: Token Mismanagement & Secret Exposure
**Status: Mitigated** | **Tests: 14**
- Credentials loaded from environment variables only
- All logging masks sensitive values
- Error messages sanitize passwords, tokens, API keys, paths, and emails
- `toString()` methods mask credentials

### MCP02: Privilege Escalation via Scope Creep
**Status: Mitigated** | **Tests: 5**
- Fixed set of 5 tools with defined capabilities
- No dynamic tool registration
- Rate limiting prevents resource exhaustion
- Tool capabilities documented and enforced

### MCP03: Tool Argument Injection
**Status: Mitigated** | **Tests: 8**
- Input validation on all tool parameters
- Parameterized operations (no string concatenation)
- SSRF protection on calendar IDs
- Path traversal prevention

### MCP04: Sensitive Data Exposure in Tool Outputs
**Status: Mitigated** | **Tests: 10**
- Error sanitization removes credentials
- Path sanitization removes usernames
- Email masking in responses
- No stack traces in client responses

### MCP05: Command Injection & Execution
**Status: Mitigated** | **Tests: 3**
- No shell command execution
- User input treated as data, never interpreted
- Tested with `$(...)`, backtick, and shell metacharacter payloads

### MCP06: Prompt Injection via Contextual Payloads
**Status: Mitigated** | **Tests: 3**
- All user-provided text stored as data
- ICS content properly escaped per RFC 5545
- No text interpretation or evaluation

### MCP07: Insufficient Authentication & Authorization
**Status: Mitigated**
- Credentials validated at startup
- All CalDAV requests authenticated
- No anonymous access

### MCP08: Insecure Logging Practices
**Status: Mitigated** | **Tests: 31**
- Log injection prevention
- Credential sanitization in logs
- Rate limiting on log messages (10/sec, 50 burst)
- CRLF injection protection
- Control character handling

### MCP09: Resource Exhaustion & DoS
**Status: Mitigated** | **Tests: 25**
- Rate limiting (60 reads, 20 writes per minute)
- Input size limits
- Message truncation for large inputs
- ReDoS-safe regex patterns
- Timeout protection on all operations
- Memory-efficient parsing

### MCP10: Context Injection & Over-Sharing
**Status: Mitigated** | **Tests: 3**
- Request isolation (no shared state between requests)
- Error messages don't leak data from previous requests
- Rate limiter state is per-instance

## Setting Up iCloud Credentials

### Step 1: Generate App-Specific Password

1. Go to [appleid.apple.com](https://appleid.apple.com)
2. Sign in with your Apple ID
3. Navigate to **Security** > **App-Specific Passwords**
4. Click **Generate Password**
5. Name it "Calendar MCP Server"
6. Copy the generated password

### Step 2: Configure Environment

```bash
export ICLOUD_USERNAME="your-apple-id@icloud.com"
export ICLOUD_PASSWORD="xxxx-xxxx-xxxx-xxxx"  # App-specific password
```

### Security Best Practices

- **Never use your main Apple ID password** - Always use app-specific passwords
- **Rotate passwords periodically** - Regenerate app-specific passwords every 90 days
- **Revoke unused passwords** - Remove app-specific passwords you no longer need
- **Monitor account activity** - Check your Apple ID for unauthorized access

## Reporting a Vulnerability

### Responsible Disclosure

We take security vulnerabilities seriously. Please report them responsibly.

**DO NOT open public GitHub issues for security vulnerabilities.**

### How to Report

1. **GitHub Security Advisory**: Use [GitHub's private vulnerability reporting](https://github.com/icloud-calendar-mcp/icloud-calendar-mcp/security/advisories/new)
2. **Include**:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

### What to Expect

| Timeline | Action |
|----------|--------|
| 24 hours | Acknowledgment of report |
| 72 hours | Initial assessment |
| 7 days | Status update |
| 30 days | Fix development (for valid issues) |
| 90 days | Public disclosure (coordinated) |

### Recognition

We acknowledge security researchers who report valid vulnerabilities (with permission). Options:
- Name in CHANGELOG
- Name in GitHub Security Advisory
- Anonymous credit

## Security Testing

### Running Security Tests

```bash
# All security tests (239 tests)
./gradlew test --tests "*SecurityTest*"
./gradlew test --tests "AdversarialTest"

# OWASP MCP specific tests
./gradlew test --tests "OwaspMcpSecurityTest"

# Unicode security tests
./gradlew test --tests "UnicodeSecurityTest"

# ReDoS protection tests
./gradlew test --tests "ReDoSSecurityTest"

# Logger security tests
./gradlew test --tests "McpLoggerSecurityTest"

# Progress security tests
./gradlew test --tests "ProgressSecurityTest"

# Cancellation security tests
./gradlew test --tests "CancellationSecurityTest"

# Input validation tests
./gradlew test --tests "InputValidatorTest"

# Error handler tests
./gradlew test --tests "SecureErrorHandlerTest"
```

### Test Coverage

| Category | Tests | Description |
|----------|-------|-------------|
| **Adversarial Inputs** | 53 | SQL/NoSQL injection, XSS, path traversal, command injection |
| **Unicode Security** | 38 | Homoglyphs, normalization, RTL override, zero-width chars |
| **Logger Security** | 31 | Log injection, CRLF, credential sanitization bypass |
| **OWASP MCP Risks** | 29 | MCP01-10 specific attack vectors |
| **Progress Security** | 27 | Token enumeration, injection, state manipulation |
| **ReDoS Protection** | 25 | Catastrophic backtracking, resource exhaustion |
| **Cancellation Security** | 22 | Replay attacks, race conditions, resource cleanup |
| **Credential Security** | 14 | Token masking, secure storage, toString safety |
| **Total Security Tests** | **239** | |

### Adversarial Test Coverage

| Attack Vector | Tests | Status |
|--------------|-------|--------|
| SQL/NoSQL Injection | 3 | Mitigated |
| XSS Payloads | 3 | Mitigated |
| Path Traversal | 3 | Mitigated |
| Command Injection | 3 | Mitigated |
| Prompt Injection | 3 | Mitigated |
| ICS Format Injection | 3 | Mitigated |
| CRLF Injection | 3 | Mitigated |
| Null Byte Injection | 2 | Mitigated |
| Oversized Inputs | 5 | Mitigated |
| Unicode Edge Cases | 38 | Documented |
| ReDoS Patterns | 25 | Mitigated |
| Resource Exhaustion | 20 | Mitigated |

## Security Updates

Security updates are released as patch versions (e.g., 1.0.1) and announced via:
- GitHub Security Advisories
- CHANGELOG.md
- Release notes

Subscribe to releases to receive security notifications.

## Security Architecture

```
+------------------------------------------------------------------+
|                         Security Layer                             |
|                                                                    |
|  +-------------------+  +-------------------+  +----------------+  |
|  | Input Validator   |  | Rate Limiter      |  | Error Handler  |  |
|  | - SSRF protection |  | - 60 reads/min    |  | - Sanitization |  |
|  | - Injection block |  | - 20 writes/min   |  | - Truncation   |  |
|  | - Size limits     |  | - Sliding window  |  | - No stacktrace|  |
|  +-------------------+  +-------------------+  +----------------+  |
|                                                                    |
|  +-------------------+  +-------------------+  +----------------+  |
|  | Credential Mgr    |  | MCP Logger        |  | Cancellation   |  |
|  | - Env vars only   |  | - Rate limited    |  | - Cleanup      |  |
|  | - Masked logging  |  | - Sanitized       |  | - Isolation    |  |
|  | - toString safe   |  | - No injection    |  | - No replay    |  |
|  +-------------------+  +-------------------+  +----------------+  |
+------------------------------------------------------------------+
```
