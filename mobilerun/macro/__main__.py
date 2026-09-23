"""
Entry point for running Mobilerun macro CLI as a module.

Usage: python -m mobilerun.macro <command>
"""

from mobilerun.macro.cli import macro_cli

if __name__ == "__main__":
    macro_cli()
