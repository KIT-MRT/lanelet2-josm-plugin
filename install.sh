#!/usr/bin/env bash
# Copy lanelet2.jar into JOSM's default plugins directory.
#
# Usage:
#   ./install.sh                         # build/dist/lanelet2.jar next to this script
#   ./install.sh /path/to/lanelet2.jar   # a downloaded release jar
#   ./install.sh --jar FILE [--dir DIR]
#
# Override the destination with --dir or JOSM_PLUGIN_DIR. The default path
# follows JOSM itself: ~/.josm/plugins if that legacy home exists, otherwise
# ${XDG_DATA_HOME:-~/.local/share}/JOSM/plugins on Linux, ~/Library/JOSM/plugins
# on macOS, %APPDATA%\JOSM\plugins on Windows (Git Bash / MSYS).

set -euo pipefail

PLUGIN_NAME="lanelet2.jar"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_DIST="$SCRIPT_DIR/build/dist/$PLUGIN_NAME"

jar=""
dest_dir=""

usage() {
  cat <<EOF
Install the Lanelet2 JOSM plugin into JOSM's plugins directory.

Usage:
  $0 [JAR] [--dir DIR]
  $0 --jar JAR [--dir DIR]

  JAR          Plugin jar to copy. Defaults to build/dist/lanelet2.jar
               (the Gradle dist output). Pass a downloaded GitHub release
               jar the same way.
  --dir DIR    Plugins directory (or set JOSM_PLUGIN_DIR). Default is
               JOSM's own user-data plugins folder on this machine.
  -h, --help   Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --jar)
      jar="${2:?--jar needs a path}"
      shift 2
      ;;
    --dir)
      dest_dir="${2:?--dir needs a path}"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "error: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      if [[ -n "$jar" ]]; then
        echo "error: extra argument: $1" >&2
        exit 2
      fi
      jar="$1"
      shift
      ;;
  esac
done

default_plugin_dir() {
  if [[ -n "${JOSM_PLUGIN_DIR:-}" ]]; then
    printf '%s\n' "$JOSM_PLUGIN_DIR"
    return
  fi
  local home="${HOME:-}"
  case "$(uname -s)" in
    Darwin)
      printf '%s\n' "$home/Library/JOSM/plugins"
      ;;
    CYGWIN*|MINGW*|MSYS*)
      local appdata="${APPDATA:-}"
      if [[ -z "$appdata" && -n "${USERPROFILE:-}" ]]; then
        appdata="$USERPROFILE/AppData/Roaming"
      fi
      printf '%s\n' "${appdata%/}/JOSM/plugins"
      ;;
    *)
      # Same rule as JOSM PlatformHookUnixoid: a leftover ~/.josm wins.
      if [[ -e "$home/.josm" ]]; then
        printf '%s\n' "$home/.josm/plugins"
      else
        local xdg="${XDG_DATA_HOME:-$home/.local/share}"
        printf '%s\n' "$xdg/JOSM/plugins"
      fi
      ;;
  esac
}

if [[ -z "$jar" ]]; then
  jar="$DEFAULT_DIST"
fi
if [[ -z "$dest_dir" ]]; then
  dest_dir="$(default_plugin_dir)"
fi

if [[ ! -f "$jar" ]]; then
  echo "error: plugin jar not found: $jar" >&2
  if [[ "$jar" == "$DEFAULT_DIST" ]]; then
    echo "Build it with:  ./gradlew dist" >&2
    echo "Or pass a downloaded release jar:  $0 /path/to/lanelet2.jar" >&2
  fi
  exit 1
fi

if ! unzip -tqq "$jar" >/dev/null 2>&1; then
  echo "error: $jar is not a readable zip/jar" >&2
  exit 1
fi

josm_home="$(dirname "$dest_dir")"
if [[ ! -d "$dest_dir" ]]; then
  echo "warning: JOSM plugins directory does not exist:" >&2
  echo "         $dest_dir" >&2
  if [[ ! -d "$josm_home" ]]; then
    echo "warning: JOSM user data ($josm_home) is missing too." >&2
    echo "         JOSM has probably never been started on this account." >&2
    echo "         The jar will still be copied; start JOSM afterwards and" >&2
    echo "         enable the plugin under Edit → Preferences → Plugins." >&2
  fi
  echo "Creating $dest_dir" >&2
  mkdir -p "$dest_dir"
fi

target="$dest_dir/$PLUGIN_NAME"
if [[ -e "$target" ]]; then
  bak="$target.bak"
  cp -f "$target" "$bak"
  echo "Replacing existing $target (backup: $bak)"
fi

cp -f "$jar" "$target"
echo "Installed $jar"
echo "      -> $target"
echo
echo "Restart JOSM, then enable \"lanelet2\" under Edit → Preferences → Plugins"
echo "if this is the first install."
