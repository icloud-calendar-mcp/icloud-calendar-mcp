# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
- Recurring event support
- Event reminders
- Calendar sharing
- Attendee management
