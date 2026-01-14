#!/usr/bin/env node

/**
 * iCloud Calendar MCP Server - npm wrapper
 *
 * This script wraps the Java JAR for easy npm/npx usage.
 * Requires Java 17+ to be installed.
 */

const { spawn, execSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const pkg = require('../package.json');
const JAR_NAME = `icloud-calendar-mcp-${pkg.version}-all.jar`;
const JAR_PATH = path.join(__dirname, '..', 'lib', JAR_NAME);
const MIN_JAVA_VERSION = 17;

// ANSI colors
const RED = '\x1b[31m';
const GREEN = '\x1b[32m';
const YELLOW = '\x1b[33m';
const RESET = '\x1b[0m';

function log(msg) {
  console.error(msg);
}

function checkJava() {
  try {
    const output = execSync('java -version 2>&1', { encoding: 'utf8' });
    const match = output.match(/version "(\d+)/);
    if (match) {
      const version = parseInt(match[1], 10);
      if (version >= MIN_JAVA_VERSION) {
        return { ok: true, version };
      }
      return { ok: false, version, error: `Java ${MIN_JAVA_VERSION}+ required, found ${version}` };
    }
    return { ok: false, error: 'Could not parse Java version' };
  } catch (e) {
    return { ok: false, error: 'Java not found. Please install Java 17 or higher.' };
  }
}

function checkCredentials() {
  const username = process.env.ICLOUD_USERNAME;
  const password = process.env.ICLOUD_PASSWORD;

  if (!username || !password) {
    return {
      ok: false,
      error: `Missing credentials. Set environment variables:
  export ICLOUD_USERNAME="your-apple-id@icloud.com"
  export ICLOUD_PASSWORD="your-app-specific-password"

Get an app-specific password at: https://appleid.apple.com`
    };
  }
  return { ok: true };
}

function checkJar() {
  if (!fs.existsSync(JAR_PATH)) {
    return {
      ok: false,
      error: `JAR not found at ${JAR_PATH}
Try reinstalling: npm install @icloud-calendar-mcp/server`
    };
  }
  return { ok: true };
}

function showHelp() {
  console.log(`
${GREEN}iCloud Calendar MCP Server${RESET}

A security-first MCP server for iCloud Calendar access.

${YELLOW}Usage:${RESET}
  npx @icloud-calendar-mcp/server

${YELLOW}Environment Variables (required):${RESET}
  ICLOUD_USERNAME    Your Apple ID email
  ICLOUD_PASSWORD    App-specific password (NOT your Apple ID password)

${YELLOW}Claude Desktop Configuration:${RESET}
  Add to ~/Library/Application Support/Claude/claude_desktop_config.json:

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

${YELLOW}Get App-Specific Password:${RESET}
  https://appleid.apple.com → Security → App-Specific Passwords

${YELLOW}More Info:${RESET}
  https://github.com/icloud-calendar-mcp/icloud-calendar-mcp
`);
}

function main() {
  const args = process.argv.slice(2);

  // Handle --help and --version
  if (args.includes('--help') || args.includes('-h')) {
    showHelp();
    process.exit(0);
  }

  if (args.includes('--version') || args.includes('-v')) {
    console.log(`icloud-calendar-mcp v${pkg.version}`);
    process.exit(0);
  }

  // Preflight checks
  const javaCheck = checkJava();
  if (!javaCheck.ok) {
    log(`${RED}Error:${RESET} ${javaCheck.error}`);
    process.exit(1);
  }

  const jarCheck = checkJar();
  if (!jarCheck.ok) {
    log(`${RED}Error:${RESET} ${jarCheck.error}`);
    process.exit(1);
  }

  const credCheck = checkCredentials();
  if (!credCheck.ok) {
    log(`${RED}Error:${RESET} ${credCheck.error}`);
    process.exit(1);
  }

  // Run the JAR
  const java = spawn('java', ['-jar', JAR_PATH], {
    stdio: 'inherit',
    env: process.env
  });

  java.on('error', (err) => {
    log(`${RED}Failed to start Java:${RESET} ${err.message}`);
    process.exit(1);
  });

  java.on('exit', (code) => {
    process.exit(code || 0);
  });

  // Forward signals
  process.on('SIGINT', () => java.kill('SIGINT'));
  process.on('SIGTERM', () => java.kill('SIGTERM'));
}

main();
