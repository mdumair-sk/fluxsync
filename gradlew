#!/usr/bin/env bash
set -euo pipefail

if [[ -x "/root/.local/share/mise/installs/java/17.0.2/bin/java" ]]; then
  export JAVA_HOME="/root/.local/share/mise/installs/java/17.0.2"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

exec gradle "$@"
