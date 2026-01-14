#!/usr/bin/env python3
"""
iCloud Calendar MCP Server - Python wrapper

This script wraps the Java JAR for easy uvx/pip usage.
Requires Java 17+ to be installed.
"""

import os
import subprocess
import sys
from pathlib import Path

from icloud_calendar_mcp.downloader import ensure_jar

JAR_NAME = "icloud-calendar-mcp-1.0.0-all.jar"
MIN_JAVA_VERSION = 17


def get_jar_path() -> Path:
    """Get the path where the JAR should be stored."""
    # Store in user's cache directory
    cache_dir = Path.home() / ".cache" / "icloud-calendar-mcp"
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir / JAR_NAME


def check_java() -> tuple[bool, str]:
    """Check if Java 17+ is available."""
    try:
        result = subprocess.run(
            ["java", "-version"],
            capture_output=True,
            text=True,
        )
        output = result.stderr + result.stdout

        # Parse version from output like 'openjdk version "17.0.1"' or 'java version "21"'
        import re
        match = re.search(r'version "(\d+)', output)
        if match:
            version = int(match.group(1))
            if version >= MIN_JAVA_VERSION:
                return True, f"Java {version}"
            return False, f"Java {MIN_JAVA_VERSION}+ required, found Java {version}"
        return False, "Could not parse Java version"
    except FileNotFoundError:
        return False, "Java not found. Please install Java 17 or higher."


def check_credentials() -> tuple[bool, str]:
    """Check if iCloud credentials are set."""
    username = os.environ.get("ICLOUD_USERNAME")
    password = os.environ.get("ICLOUD_PASSWORD")

    if not username or not password:
        return False, """Missing credentials. Set environment variables:
  export ICLOUD_USERNAME="your-apple-id@icloud.com"
  export ICLOUD_PASSWORD="your-app-specific-password"

Get an app-specific password at: https://appleid.apple.com"""
    return True, ""


def show_help():
    """Display help information."""
    print("""
\033[32miCloud Calendar MCP Server\033[0m

A security-first MCP server for iCloud Calendar access.

\033[33mUsage:\033[0m
  uvx icloud-calendar-mcp
  python -m icloud_calendar_mcp

\033[33mEnvironment Variables (required):\033[0m
  ICLOUD_USERNAME    Your Apple ID email
  ICLOUD_PASSWORD    App-specific password (NOT your Apple ID password)

\033[33mClaude Desktop Configuration:\033[0m
  Add to ~/Library/Application Support/Claude/claude_desktop_config.json:

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

\033[33mGet App-Specific Password:\033[0m
  https://appleid.apple.com -> Security -> App-Specific Passwords

\033[33mMore Info:\033[0m
  https://github.com/icloud-calendar-mcp/icloud-calendar-mcp
""")


def main():
    """Main entry point."""
    args = sys.argv[1:]

    # Handle --help and --version
    if "--help" in args or "-h" in args:
        show_help()
        sys.exit(0)

    if "--version" in args or "-v" in args:
        from icloud_calendar_mcp import __version__
        print(f"icloud-calendar-mcp v{__version__}")
        sys.exit(0)

    # Preflight checks
    java_ok, java_msg = check_java()
    if not java_ok:
        print(f"\033[31mError:\033[0m {java_msg}", file=sys.stderr)
        sys.exit(1)

    cred_ok, cred_msg = check_credentials()
    if not cred_ok:
        print(f"\033[31mError:\033[0m {cred_msg}", file=sys.stderr)
        sys.exit(1)

    # Ensure JAR is downloaded
    jar_path = get_jar_path()
    if not ensure_jar(jar_path):
        sys.exit(1)

    # Run the JAR
    try:
        process = subprocess.run(
            ["java", "-jar", str(jar_path)],
            env=os.environ,
        )
        sys.exit(process.returncode)
    except KeyboardInterrupt:
        sys.exit(0)
    except Exception as e:
        print(f"\033[31mFailed to start Java:\033[0m {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
