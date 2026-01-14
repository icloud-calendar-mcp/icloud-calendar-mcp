#!/usr/bin/env python3
"""
JAR downloader - downloads the JAR from GitHub Releases on first run.
"""

import sys
from pathlib import Path

import httpx

VERSION = "1.0.0"
JAR_NAME = f"icloud-calendar-mcp-{VERSION}-all.jar"
DOWNLOAD_URL = f"https://github.com/icloud-calendar-mcp/icloud-calendar-mcp/releases/download/v{VERSION}/{JAR_NAME}"
MIN_JAR_SIZE = 1_000_000  # 1MB minimum


def download_jar(dest: Path) -> bool:
    """Download the JAR from GitHub Releases."""
    print(f"\033[33mDownloading iCloud Calendar MCP Server...\033[0m", file=sys.stderr)
    print(f"URL: {DOWNLOAD_URL}", file=sys.stderr)

    try:
        with httpx.Client(follow_redirects=True, timeout=300.0) as client:
            with client.stream("GET", DOWNLOAD_URL) as response:
                if response.status_code != 200:
                    print(f"\033[31mDownload failed:\033[0m HTTP {response.status_code}", file=sys.stderr)
                    return False

                total = int(response.headers.get("content-length", 0))
                downloaded = 0

                with open(dest, "wb") as f:
                    for chunk in response.iter_bytes(chunk_size=8192):
                        f.write(chunk)
                        downloaded += len(chunk)
                        if total:
                            percent = int((downloaded / total) * 100)
                            mb = downloaded / 1024 / 1024
                            print(f"\rDownloading: {percent}% ({mb:.1f} MB)", end="", file=sys.stderr)

                print("", file=sys.stderr)  # newline after progress
                print(f"\033[32mDownload complete!\033[0m", file=sys.stderr)
                return True

    except httpx.HTTPError as e:
        print(f"\033[31mDownload error:\033[0m {e}", file=sys.stderr)
        if dest.exists():
            dest.unlink()
        return False
    except Exception as e:
        print(f"\033[31mUnexpected error:\033[0m {e}", file=sys.stderr)
        if dest.exists():
            dest.unlink()
        return False


def ensure_jar(jar_path: Path) -> bool:
    """Ensure the JAR exists, downloading if necessary."""
    # Check if JAR already exists and is valid
    if jar_path.exists():
        if jar_path.stat().st_size > MIN_JAR_SIZE:
            return True
        # JAR exists but is too small (corrupted), remove it
        jar_path.unlink()

    # Download the JAR
    if not download_jar(jar_path):
        print(f"\nYou can manually download from:", file=sys.stderr)
        print(f"  {DOWNLOAD_URL}", file=sys.stderr)
        print(f"And place it at:", file=sys.stderr)
        print(f"  {jar_path}", file=sys.stderr)
        return False

    # Verify the download
    if jar_path.stat().st_size < MIN_JAR_SIZE:
        print("\033[31mDownloaded file is too small, may be corrupted\033[0m", file=sys.stderr)
        jar_path.unlink()
        return False

    return True
