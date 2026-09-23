import sys


def main():
    # Use stderr print instead of DeprecationWarning — Python hides
    # DeprecationWarning by default, so most users would never see it.
    print(
        "\033[33m\u26a0 The 'droidrun' CLI has been renamed to 'mobilerun'. "
        "Please update your scripts.\033[0m",
        file=sys.stderr,
    )
    from mobilerun.cli.main import cli

    cli()
