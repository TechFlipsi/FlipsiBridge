# FIX-WELLE-2: gemeinsame Test-Imports (je Bug eine eigene Test-Datei).
import asyncio
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.modules.setdefault("gateway", None)

from test_adapter import (  # noqa: E402,F401
    _Msg, _establish_session, _make_crypto_adapter, _phone_frame)
