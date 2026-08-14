# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.2.0] - 2026-08-14

### Added
- Durable event handles. `get_events`, `create_event`, and `update_event` return
  an opaque handle for each event (base64url of the event's normalized href plus
  its last-seen ETag). Pass it to `update_event` / `delete_event` to act on an
  event from a cold process, with no prior `get_events` to warm the cache. The
  decode carries an SSRF guard: a handle's href must be a relative path or an
  iCloud host.
- Per-occurrence editing of recurring events. `get_events` expands a recurring
  series into per-occurrence entries, each carrying its own `RECURRENCE-ID`, an
  occurrence handle, and the series `RRULE`. `update_event` / `delete_event` take
  an `event_scope` (`this_occurrence`, `this_and_future`, `all_events`), enforced
  before any network write, so a single occurrence can be changed or cancelled
  without rewriting the whole series.
- Three user-initiated prompt templates: `schedule_meeting`, `reschedule`,
  `find_conflicts`.
- `create_event` accepts `summary` as an alias for `title` when `title` is omitted.

### Fixed
- `get_events` reports each recurring occurrence at its own date, not the master's
  `DTSTART`. (#9)
- `get_events` returns occurrences that overlap the queried window even when they
  began before it (RFC 4791 §9.9: `DTSTART < end AND DTEND > start`). A daily
  overnight shift (22:00-06:00) queried for the morning it runs into now appears;
  an occurrence entirely before the window does not.
- `get_events` drops events iCloud returns outside the requested range, including
  the all-day case where an exclusive `DTEND` only touches the query start. (#11)
- `create_event` / `update_event` accept `Z` and `+HH:MM` / `-HH:MM` offset ISO
  8601 datetimes, not only naive local wall-clock. An explicit offset denotes an
  absolute instant and takes precedence over the `timezone` parameter, which now
  applies only to naive datetimes. The schema had advertised a `Z` form the
  validator rejected. (#14, #16)
- UTC-origin datetimes resolve in UTC regardless of the host time zone. A value
  stored as UTC no longer shifts by the host offset on an off-UTC machine, which
  had moved both the wall-clock time and the calendar date. The recurrence-expansion
  seed is aligned the same way. (#15)
- Stale-ETag recovery on writes. A write that hits `412 Precondition Failed`
  refetches the current ETag and retries once. The first PUT sends the handle's
  ETag as `If-Match`, so a concurrent out-of-band edit reliably trips `412` and
  reconciles.
- `RECURRENCE-ID` overrides survive a parse/generate round-trip: `RANGE=THISANDFUTURE`
  is preserved, and value-type / time-zone normalization makes an override match
  the correct occurrence regardless of the host zone.
- kotlin-logging's startup banner no longer reaches the STDIO JSON-RPC stream,
  where it could corrupt the first client exchange. (#13)
- The npm and PyPI installers now require Java 21, matching the JAR's compile
  target. They previously accepted Java 17, which passed the preflight check and
  then failed at runtime with an unsupported-class-version error.

### Changed
- Java 21 is now the baseline (was 17): toolchain, source/target level, and CI.
- `get_events` response-size caps, three fixed bounds with no pagination: reject a
  requested range wider than 366 days before the fetch; abort expansion of a
  single series past 10,000 occurrences; return a structured `PAYLOAD_TOO_LARGE`
  error when the assembled result exceeds 1,000 events, telling the caller to
  narrow the range. Prevents a silently truncated, invalid-JSON response.
- `get_events` day boundaries are UTC, now stated in the tool description:
  `start_date` and `end_date` select whole UTC calendar days, and timed events
  are returned as UTC instants. The description gives the recipe for resolving a
  user's local day (request one extra day on each side, filter by the converted
  `startTime`). No `timezone` parameter is added.
- Regional iCloud partition hosts (`pNN-*`, `:443`) are normalized consistently.
- MCP Kotlin SDK 0.14.0 → 0.15.0; ical4j 4.2.2 → 4.3.0 (in `icaldav-core`).

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
