<p align="center">
  <img src="images/logo.png" alt="iCloud Calendar MCP Server" width="200"/>
</p>

<h1 align="center">iCloud Calendar MCP Server</h1>

[![Build](https://github.com/icloud-calendar-mcp/icloud-calendar-mcp/actions/workflows/test.yml/badge.svg)](https://github.com/icloud-calendar-mcp/icloud-calendar-mcp/actions/workflows/test.yml)
[![npm](https://img.shields.io/npm/v/@icloud-calendar-mcp/server.svg)](https://www.npmjs.com/package/@icloud-calendar-mcp/server)
[![PyPI](https://img.shields.io/pypi/v/icloud-calendar-mcp.svg)](https://pypi.org/project/icloud-calendar-mcp/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![MCP Registry](https://img.shields.io/badge/MCP-Registry-green.svg)](https://registry.modelcontextprotocol.io/?search=org.onekash)
[![Tests](https://img.shields.io/badge/Tests-768%20passing-brightgreen.svg)](#testing)
[![Security](https://img.shields.io/badge/Security-OWASP%20MCP%20Top%2010-blue.svg)](#security)

A **security-first** MCP (Model Context Protocol) server that provides AI assistants with secure access to iCloud Calendar via CalDAV. Built with comprehensive security controls aligned with the [OWASP MCP Top 10](https://owasp.org/www-project-mcp-top-10/).

> [!CAUTION]
> **Never use your main Apple ID password.** This server requires an [app-specific password](https://support.apple.com/en-us/HT204397) which can be revoked independently without affecting your Apple ID.

## Features

### MCP Tools

| Tool | Description | Read-Only | Destructive |
|------|-------------|:---------:|:-----------:|
| `list_calendars` | List all calendars from iCloud account | Yes | No |
| `get_events` | Get events within a date range from a calendar | Yes | No |
| `create_event` | Create a new calendar event | No | No |
| `update_event` | Update an existing event | No | No |
| `delete_event` | Delete an event by ID | No | Yes |

### MCP Resources

| Resource | Description |
|----------|-------------|
| `calendar://calendars` | Browse available calendars |

### Security Features

- **Credential Protection** - Environment variables only, never in code or config
- **Input Validation** - All parameters validated with SSRF protection
- **Rate Limiting** - 60 reads/min, 20 writes/min per MCP specification
- **Secure Error Handling** - No sensitive data leakage in error messages
- **OWASP MCP Top 10 Compliance** - 282 security tests covering all major risks
- **ReDoS Protection** - All regex patterns tested against catastrophic backtracking
- **Unicode Security** - Protection against homoglyph and encoding attacks

---

## Quick Start

### Prerequisites

- **Java 17+** (for all installation methods)
- iCloud account with [app-specific password](https://support.apple.com/en-us/HT204397)

### Installation

Choose your preferred installation method:

#### Option 1: npm (Recommended)

```bash
npx @icloud-calendar-mcp/server
```

#### Option 2: Python (uvx)

```bash
uvx icloud-calendar-mcp
```

#### Option 3: Direct JAR

```bash
# Download from GitHub Releases
curl -LO https://github.com/icloud-calendar-mcp/icloud-calendar-mcp/releases/latest/download/icloud-calendar-mcp-3.0.0-all.jar

# Run
java -jar icloud-calendar-mcp-3.0.0-all.jar
```

#### Option 4: Build from Source

```bash
git clone https://github.com/icloud-calendar-mcp/icloud-calendar-mcp.git
cd icloud-calendar-mcp
./gradlew fatJar
java -jar build/libs/icloud-calendar-mcp-3.0.0-all.jar
```

### Configuration

Set your iCloud credentials as environment variables:

```bash
export ICLOUD_USERNAME="your-apple-id@icloud.com"
export ICLOUD_PASSWORD="your-app-specific-password"
```

> **Security Note**: Use an [app-specific password](https://support.apple.com/en-us/HT204397), not your main Apple ID password.

---

## Claude Desktop Integration

Add to your Claude Desktop configuration:

| Platform | Config Path |
|----------|-------------|
| macOS | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Linux | `~/.config/claude/claude_desktop_config.json` |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |

<details open>
<summary><strong>Using npm (Recommended)</strong></summary>

```json
{
  "mcpServers": {
    "icloud-calendar": {
      "command": "npx",
      "args": ["@icloud-calendar-mcp/server"],
      "env": {
        "ICLOUD_USERNAME": "your-apple-id@icloud.com",
        "ICLOUD_PASSWORD": "your-app-specific-password"
      }
    }
  }
}
```
</details>

<details>
<summary><strong>Using uvx (Python)</strong></summary>

```json
{
  "mcpServers": {
    "icloud-calendar": {
      "command": "uvx",
      "args": ["icloud-calendar-mcp"],
      "env": {
        "ICLOUD_USERNAME": "your-apple-id@icloud.com",
        "ICLOUD_PASSWORD": "your-app-specific-password"
      }
    }
  }
}
```
</details>

<details>
<summary><strong>Using JAR directly</strong></summary>

```json
{
  "mcpServers": {
    "icloud-calendar": {
      "command": "java",
      "args": ["-jar", "/path/to/icloud-calendar-mcp-3.0.0-all.jar"],
      "env": {
        "ICLOUD_USERNAME": "your-apple-id@icloud.com",
        "ICLOUD_PASSWORD": "your-app-specific-password"
      }
    }
  }
}
```
</details>

---

## Usage Examples

Once configured, you can ask Claude:

- *"What's on my calendar this week?"*
- *"Create a meeting with John tomorrow at 2pm"*
- *"Show me all my calendars"*
- *"Delete the dentist appointment on Friday"*
- *"Move my 3pm meeting to 4pm"*

### Tool Parameters

#### list_calendars
No parameters required.

#### get_events
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `calendar_id` | string | Yes | Calendar identifier |
| `start_date` | string | Yes | Start date (YYYY-MM-DD) |
| `end_date` | string | Yes | End date (YYYY-MM-DD) |

#### create_event
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `calendar_id` | string | Yes | Target calendar |
| `title` | string | Yes | Event title |
| `start_time` | string | Cond. | ISO 8601 datetime (required for timed events) |
| `end_time` | string | Cond. | ISO 8601 datetime (required for timed events) |
| `start_date` | string | Cond. | Start date YYYY-MM-DD (required for all-day events) |
| `end_date` | string | Cond. | End date YYYY-MM-DD, inclusive (required for all-day events) |
| `is_all_day` | boolean | No | All-day event flag |
| `description` | string | No | Event description |
| `location` | string | No | Event location |
| `timezone` | string | No | IANA timezone (e.g., `America/New_York`) |
| `rrule` | string | No | Recurrence rule (e.g., `FREQ=WEEKLY;BYDAY=MO`) |

#### update_event
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `event_id` | string | Yes | Event UID to update |
| `title` | string | No | New title |
| `start_time` | string | No | New start time (ISO 8601) |
| `end_time` | string | No | New end time (ISO 8601) |
| `start_date` | string | No | New start date for all-day events (YYYY-MM-DD) |
| `end_date` | string | No | New end date for all-day events (YYYY-MM-DD) |
| `is_all_day` | boolean | No | Change to all-day event |
| `description` | string | No | New description |
| `location` | string | No | New location |
| `timezone` | string | No | IANA timezone (e.g., `America/New_York`) |
| `rrule` | string | No | Recurrence rule (e.g., `FREQ=WEEKLY;BYDAY=MO`) |

#### delete_event
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `event_id` | string | Yes | Event to delete |

---

## Security

This server is designed with security as a primary concern, following the [OWASP MCP Top 10](https://owasp.org/www-project-mcp-top-10/) guidelines.

### Security Controls

| Control | Implementation |
|---------|----------------|
| **Credential Storage** | Environment variables only, never logged or exposed |
| **Input Validation** | All inputs validated (calendar IDs, dates, times, text fields) |
| **SSRF Protection** | Blocks internal IPs, localhost, and dangerous URI schemes |
| **Rate Limiting** | Sliding window: 60 reads/min, 20 writes/min |
| **Error Handling** | Passwords, tokens, paths, emails sanitized from errors |
| **Injection Prevention** | ICS content properly escaped, command injection tested |
| **ETag Normalization** | RFC 7232 compliant, strips quotes/W/ prefix/XML entities |
| **Content-Length Guard** | Early rejection of oversized responses before buffering |
| **Circuit Breaker** | Prevents cascading failures with automatic recovery |
| **Audit Logging** | CUD operations logged via MCP logging protocol (MCP08) |
| **ReDoS Protection** | All regex patterns tested for catastrophic backtracking |
| **Unicode Security** | Homoglyph, normalization, and encoding bypass protection |

### OWASP MCP Top 10 Coverage

| Risk | Mitigation | Tests |
|------|------------|-------|
| **MCP01: Token Mismanagement** | Credentials masked in logs/errors, secure storage | 14 |
| **MCP02: Privilege Escalation** | Fixed tool set, no dynamic registration | 5 |
| **MCP03: Tool Argument Injection** | Input validation, parameterized operations | 8 |
| **MCP04: Sensitive Data Exposure** | Error sanitization, credential masking | 10 |
| **MCP05: Command Injection** | Input treated as data, not executed | 3 |
| **MCP06: Prompt Injection** | Malicious text stored as data, not interpreted | 3 |
| **MCP08: Insecure Logging** | Rate limiting, sensitive data sanitization | 31 |
| **MCP09: Resource Exhaustion** | Rate limiting, input size limits, DoS protection | 25 |
| **MCP10: Context Over-sharing** | Isolated state, no cross-request data leakage | 3 |

See [SECURITY.md](SECURITY.md) for full security documentation and vulnerability disclosure process.

---

## Testing

The server includes **768 comprehensive tests** across 30 test suites:

```bash
./gradlew test
```

### Test Coverage

| Category | Tests | Description |
|----------|-------|-------------|
| **Security** | 282 | Adversarial inputs, OWASP MCP Top 10, ReDoS, Unicode |
| **CalDAV Protocol** | 177 | XML parsing, HTTP client, models, ETag normalization |
| **ICS Format** | 98 | RFC 5545 parsing, building, patching |
| **Error Handling** | 56 | Secure error responses, credential sanitization |
| **Integration** | 40 | End-to-end tools, MCP spec compliance, annotations |
| **Input Validation** | 39 | All parameter validation rules |
| **Service Layer** | 26 | Calendar operations, caching |
| **Rate Limiting** | 15 | Concurrent access, window reset |
| **Cancellation** | 12 | Operation cancellation, cleanup |
| **Logging** | 9 | MCP logging compliance |
| **Progress** | 9 | Progress reporting |
| **E2E** | 5 | Live CalDAV + end-to-end integration |

### Security Test Categories

| Category | Tests | Coverage |
|----------|-------|----------|
| **Adversarial Inputs** | 53 | SQL/NoSQL injection, XSS, path traversal |
| **ICS Patcher Security** | 43 | CRLF injection, property injection, encoding attacks |
| **Unicode Security** | 38 | Homoglyphs, normalization, RTL override |
| **Logger Security** | 31 | Log injection, credential sanitization |
| **OWASP MCP Risks** | 29 | MCP01-10 specific attack vectors |
| **Progress Security** | 27 | Token enumeration, injection |
| **ReDoS Protection** | 25 | Catastrophic backtracking, resource exhaustion |
| **Cancellation Security** | 22 | Replay attacks, race conditions |
| **Credential Security** | 14 | Token masking, secure storage |

### Running Specific Tests

```bash
# All tests
./gradlew test

# Security tests only
./gradlew test --tests "*SecurityTest*"
./gradlew test --tests "AdversarialTest"

# OWASP MCP specific tests
./gradlew test --tests "OwaspMcpSecurityTest"

# Unicode security tests
./gradlew test --tests "UnicodeSecurityTest"

# ReDoS protection tests
./gradlew test --tests "ReDoSSecurityTest"

# CalDAV tests
./gradlew test --tests "*CalDav*"

# ICS tests
./gradlew test --tests "*Ics*"
```

---

## Architecture

```
+------------------------------------------------------------------+
|                    MCP Server (STDIO Transport)                    |
|                                                                    |
|  +----------------+  +----------------+  +----------------------+  |
|  | Rate Limiter   |  |   Input        |  |  Secure Error        |  |
|  | 60r/20w/min    |  |  Validator     |  |  Handler             |  |
|  +----------------+  +----------------+  +----------------------+  |
|                                                                    |
|  +----------------+  +----------------+  +----------------------+  |
|  | MCP Logger     |  | Cancellation   |  |  Progress            |  |
|  | (RFC 5424)     |  | Manager        |  |  Reporter            |  |
|  +----------------+  +----------------+  +----------------------+  |
|                                                                    |
|  Tools: list_calendars | get_events | create_event |               |
|         update_event | delete_event                                |
|                                                                    |
|  Resources: calendar://calendars                                   |
+------------------------------------------------------------------+
                              |
                              v
+------------------------------------------------------------------+
|                      CalendarService                               |
|  Orchestrates CalDAV operations, caches calendar metadata          |
+------------------------------------------------------------------+
                              |
                              v
+------------------------------------------------------------------+
|                      CalDAV Client Layer                           |
|                                                                    |
|  +-------------------+  +-------------------+  +----------------+  |
|  | OkHttpCalDav      |  |  IcsParser        |  |  IcsBuilder    |  |
|  | Client            |  |  (ical4j)         |  |  (RFC 5545)    |  |
|  +-------------------+  +-------------------+  +----------------+  |
|                                                                    |
|  +-------------------+  +-------------------+  +----------------+  |
|  | ICloudXml         |  |  IcsPatcher       |  |  EtagUtils     |  |
|  | Parser            |  |  (event edits)    |  |  (RFC 7232)    |  |
|  +-------------------+  +-------------------+  +----------------+  |
|                                                                    |
|  +-------------------+                                             |
|  | Credential        |                                             |
|  | Manager           |                                             |
|  +-------------------+                                             |
+------------------------------------------------------------------+
                              |
                              v
+------------------------------------------------------------------+
|                    iCloud CalDAV API                               |
|                    caldav.icloud.com                               |
+------------------------------------------------------------------+
```

---

## Development

### Build

```bash
# Build
./gradlew build

# Build fat JAR
./gradlew fatJar

# Run tests
./gradlew test

# Clean build
./gradlew clean build
```

### Project Structure

```
src/main/kotlin/org/onekash/mcp/calendar/
├── Main.kt                 # MCP server entry point
├── caldav/                 # CalDAV protocol implementation
│   ├── CalDavClient.kt     # Client interface
│   ├── CalDavModels.kt     # Domain models
│   ├── OkHttpCalDavClient.kt
│   ├── ICloudXmlParser.kt
│   └── EtagUtils.kt        # RFC 7232 ETag normalization
├── ics/                    # ICS format handling
│   ├── IcsParser.kt        # Parse iCalendar data
│   ├── IcsBuilder.kt       # Generate iCalendar data
│   └── IcsPatcher.kt       # Patch existing events (CRLF-safe)
├── service/                # Business logic
│   ├── CalendarService.kt
│   └── EventCache.kt
├── security/               # Security controls
│   └── CredentialManager.kt
├── validation/             # Input validation
│   └── InputValidator.kt
├── error/                  # Error handling
│   └── SecureErrorHandler.kt
├── ratelimit/              # Rate limiting
│   └── RateLimiter.kt
├── logging/                # MCP logging
│   └── McpLogger.kt
├── progress/               # Progress reporting
│   └── ProgressReporter.kt
└── cancellation/           # Operation cancellation
    └── CancellationManager.kt
```

### Testing with MCP Inspector

```bash
ICLOUD_USERNAME="test@icloud.com" \
ICLOUD_PASSWORD="test-app-password" \
npx @mcp-use/inspector java -jar build/libs/icloud-calendar-mcp-3.0.0-all.jar
```

---

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Security Issues

For security vulnerabilities, please see [SECURITY.md](SECURITY.md) for our responsible disclosure process. **Do not open public issues for security vulnerabilities.**

---

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---

## Acknowledgments

- [Model Context Protocol](https://modelcontextprotocol.io) by Anthropic
- [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) by Anthropic & JetBrains
- [ical4j](https://www.ical4j.org/) for ICS parsing
- [OkHttp](https://square.github.io/okhttp/) for HTTP client
- [OWASP MCP Top 10](https://owasp.org/www-project-mcp-top-10/) for security guidance
