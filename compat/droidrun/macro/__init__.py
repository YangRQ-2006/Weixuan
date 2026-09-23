"""Forward droidrun.macro to mobilerun.macro.

NOT empty — must re-export the real module's API so `from droidrun.macro
import MacroPlayer` works. The lazy importer in __init__.py won't handle
this because this file takes precedence as a physical package.
"""

import sys

import mobilerun.macro as _real

# Re-export everything from the real module
from mobilerun.macro import *  # noqa: F401,F403

# Also alias this module object in sys.modules so the lazy importer
# won't create a duplicate when droidrun.macro is imported elsewhere
sys.modules[__name__] = _real
