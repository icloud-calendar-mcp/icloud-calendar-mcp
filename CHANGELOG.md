# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.1.0] - 2026-07-22

### Added
- Bundled `icaldav-core` as a Gradle subproject: an iCalendar (RFC 5545)
  parser/generator used by the MCP server's ICS layer. ical4j 4.2.2 is confined
  to this subproject; the server's own source never depends on ical4j directly.
- Gated live integration test suite (`@Tag("integration")`), opted in with
  `./gradlew test -Pintegration`. It runs against a real iCloud account and is
  excluded from the default `./gradlew test`, which stays hermetic. Covers
  create→read round-trips and adversarial write/read workflows.

### Fixed
- Text with RFC 5545 special characters (`,` `;` `\`) is no longer double-escaped
  on `create_event` / `update_event`. Input sanitization now only strips CRLF for
  injection defense; the generator owns escaping and applies it exactly once.

### Changed
- Upgraded dependencies: MCP Kotlin SDK 0.8.1 → 0.14.0, Ktor 3.0.3 → 3.5.1,
  OkHttp 4.12.0 → 5.4.0, Kotlin 2.1.0 → 2.4.10, Gradle → 9.4.1.
- 843 MCP server tests passing; the bundled icaldav-core adds 1714 more.

## [3.0.1] - 2026-07-21

### Fixed
- All five tools now return `structuredContent` on success, satisfying MCP tools
  that declare an `outputSchema` (fixes JSON-RPC -32600). Error responses remain
  exempt (they carry `isError=true` and no `structuredContent`) (#6)

### Changed
- Server version 3.0.0 → 3.0.1
- 843 tests passing (up from 768): added negative-path tests asserting error
  responses omit `structuredContent` and set `isError`

### Known limitations
- `update_event` / `delete_event` resolve the target event from the current
  session's in-memory cache, which is populated by `get_events` (or by the
  `create_event` that made the event). To act on an event in a fresh session,
  call `get_events` first so the event is cached. There is no server-side
  find-by-UID fallback: iCloud rejects a CalDAV `calendar-query` UID
  `prop-filter` with HTTP 412, and an unfiltered query returns the full
  calendar (multiple MB), so a stateless per-request UID lookup is not viable.

## [3.0.0] - 2026-03-25

### Added
- IcsPatcher for editing existing events (CRLF injection sanitization)
- EtagUtils with RFC 7232 normalization at all entry points
- IcsBuilder VTIMEZONE support and UTF-8 octet line folding
- Extended event properties: status, url, categories, priority, organizer, attendees
- CalDavResult factory methods: badRequest, payloadTooLarge, rateLimit, sslError
- Content-Length early rejection in readBodyWithLimit
- Retry with exponential backoff, circuit breaker, rate limit handling
- Multi-homeset calendar discovery (RFC 4791)
- fetchEtags for efficient sync (ETag-only REPORT)
- McpLogger for CUD operation audit logging
- 43 adversarial security tests (ICS injection, prompt injection, OWASP MCP Top 10)
- E2E integration test suite
- Version catalog (gradle/libs.versions.toml)

### Fixed
- MCP server exiting immediately (connect + awaitCancellation)
- isConflict now checks both HTTP 409 and 412
- ETag normalization applied consistently across XML parser and HTTP headers

### Changed
- Bumped all package versions to 3.0.0 (npm, PyPI, JAR)
- 768 tests passing (up from 555)

## [2.0.0] - 2026-01-14

### Added

#### MCP Tools
- `list_calendars` - List all calendars from iCloud account
- `get_events` - Get events within a date range from a calendar
- `create_event` - Create a new calendar event with title, time, location, description
- `update_event` - Update an existing event's properties
- `delete_event` - Delete an event by ID

#### MCP Resources
- `calendar://calendars` - Browse available calendars

#### Security Features
- Credential management via environment variables only
- Input validation for all tool parameters
- Rate limiting (60 reads/min, 20 writes/min)
- Secure error handling with sensitive data sanitization
- SSRF protection blocking dangerous URI schemes and internal IPs
- ReDoS-safe regex patterns throughout
- Unicode attack prevention (homoglyphs, RTL override, zero-width)
- OWASP MCP Top 10 full compliance

#### CalDAV Implementation
- Full iCloud CalDAV protocol support
- Calendar discovery via PROPFIND
- Event CRUD operations via CalDAV
- ICS parsing with ical4j
- ICS generation per RFC 5545
- Support for all-day and timed events
- Timezone handling

#### Testing
- 555 comprehensive tests
- 239 security tests
- OWASP MCP Top 10 coverage
- ReDoS vulnerability tests
- Unicode security tests
- MockWebServer integration tests

#### Documentation
- Comprehensive README with usage examples
- Security documentation (SECURITY.md)
- Contributing guidelines (CONTRIBUTING.md)
- API documentation for all tools

### Security
- Full OWASP MCP Top 10 compliance (MCP01-10)
- Credentials masked in all logging
- Error messages sanitize passwords, tokens, paths, emails
- Input validation prevents injection attacks
- Rate limiting prevents abuse

## [Unreleased]

### Planned
- Calendar sharing
- Attendee management
