#!/bin/sh
# Installs the Storrito CLI on Linux and macOS, without root:
#
#     curl -fsSL https://storrito.com/install.sh | sh
#
# Puts the `storrito` executable into ~/.local/bin (or $STORRITO_INSTALL_DIR).
# Environment variables: STORRITO_CLI_VERSION pins a version,
# STORRITO_INSTALL_DIR chooses the directory, STORRITO_DOWNLOADS_URL points
# to another download host (tests). Windows: irm https://storrito.com/install.ps1 | iex
#
# The executable is the babashka runtime with the CLI's jar appended. The
# runtime's own ad-hoc code signature stays valid on Apple Silicon (the
# appended jar lies outside the signed pages), and `codesign` refuses to
# re-sign such a file, so nothing is signed here.
# Docs: https://storrito.com/documentation/cli/
set -eu

BASE="${STORRITO_DOWNLOADS_URL:-https://storrito.com/downloads/cli}"
VERSION="${STORRITO_CLI_VERSION:-}"
INSTALL_DIR="${STORRITO_INSTALL_DIR:-$HOME/.local/bin}"

say() { printf '%s\n' "$*" >&2; }
fail() { say "install.sh: $*"; exit 1; }

if [ "$(id -u)" = "0" ] && [ -z "${STORRITO_INSTALL_ALLOW_ROOT:-}" ]; then
  fail "run this as your own user, the CLI needs no root (STORRITO_INSTALL_ALLOW_ROOT=1 overrides)"
fi

command -v curl >/dev/null 2>&1 || fail "curl is required"

os="$(uname -s)"
arch="$(uname -m)"
case "$os" in
  Linux) platform_os="linux" ;;
  Darwin) platform_os="macos" ;;
  *) fail "unsupported operating system: $os (on Windows run: irm https://storrito.com/install.ps1 | iex)" ;;
esac
case "$arch" in
  x86_64|amd64) platform_arch="amd64" ;;
  aarch64|arm64) platform_arch="aarch64" ;;
  *) fail "unsupported architecture: $arch" ;;
esac
# A shell under Rosetta reports x86_64 on Apple Silicon:
if [ "$platform_os" = "macos" ] && [ "$platform_arch" = "amd64" ]; then
  if [ "$(sysctl -n sysctl.proc_translated 2>/dev/null || echo 0)" = "1" ]; then
    platform_arch="aarch64"
  fi
fi
platform="$platform_os-$platform_arch"
file="storrito-$platform"

if [ -z "$VERSION" ]; then
  VERSION="$(curl -fsSL "$BASE/latest.txt" | tr -d '[:space:]')" || fail "could not read $BASE/latest.txt"
fi
[ -n "$VERSION" ] || fail "no version found at $BASE/latest.txt"

url="$BASE/$VERSION/$file"
tmp="$(mktemp -d 2>/dev/null || mktemp -d -t storrito)"
trap 'rm -rf "$tmp"' EXIT

say "Downloading storrito $VERSION for $platform"
curl -fsSL "$url" -o "$tmp/storrito" || fail "download failed: $url"
curl -fsSL "$url.sha256" -o "$tmp/storrito.sha256" || fail "download failed: $url.sha256"

expected="$(cut -d' ' -f1 "$tmp/storrito.sha256" | tr -d '[:space:]')"
if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$tmp/storrito" | cut -d' ' -f1)"
elif command -v shasum >/dev/null 2>&1; then
  actual="$(shasum -a 256 "$tmp/storrito" | cut -d' ' -f1)"
else
  fail "neither sha256sum nor shasum found to verify the download"
fi
[ "$expected" = "$actual" ] || fail "checksum mismatch for $url (expected $expected, got $actual)"

chmod +x "$tmp/storrito"

mkdir -p "$INSTALL_DIR"
mv -f "$tmp/storrito" "$INSTALL_DIR/storrito"
say "Installed $INSTALL_DIR/storrito"

"$INSTALL_DIR/storrito" version >/dev/null || fail "the installed executable does not run"

case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *)
    say ""
    say "Add $INSTALL_DIR to your PATH, e.g. in ~/.bashrc or ~/.zshrc:"
    say "  export PATH=\"$INSTALL_DIR:\$PATH\""
    ;;
esac
say ""
say "Next: storrito login"
