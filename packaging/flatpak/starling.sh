#!/bin/sh
set -eu

export STARLING_PYTHON=/usr/bin/python3
export STARLING_PYTHON_PACKAGED=1
export PYTHONPATH="/app/lib/starling-python${PYTHONPATH:+:$PYTHONPATH}"
exec /app/opt/starling/bin/Starling "$@"
