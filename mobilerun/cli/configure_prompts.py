from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import click


@dataclass(frozen=True)
class SelectChoice:
    value: str
    label: str
    hint: str | None = None


def _import_inquirer_select():
    try:
        from InquirerPy import inquirer

        return inquirer
    except Exception:
        return None


def select_prompt(
    message: str,
    choices: Sequence[SelectChoice],
    *,
    default: str | None = None,
) -> str:
    """Interactive select prompt with arrow-key UX when available."""

    inquirer = _import_inquirer_select()
    if inquirer is not None:
        rendered = []
        for choice in choices:
            name = f"• {choice.label}"
            if choice.hint:
                name = f"{name} ({choice.hint})"
            rendered.append({"name": name, "value": choice.value})
        return str(
            inquirer.select(
                message=message,
                choices=rendered,
                default=default,
                vi_mode=False,
                cycle=False,
            ).execute()
        )

    click.echo(message)
    for index, choice in enumerate(choices, start=1):
        suffix = f" - {choice.hint}" if choice.hint else ""
        click.echo(f"  {index}. {choice.label}{suffix}")

    default_index = next(
        (
            index
            for index, choice in enumerate(choices, start=1)
            if default is not None and choice.value.casefold() == default.casefold()
        ),
        1,
    )
    while True:
        response = click.prompt(
            "Select option",
            type=str,
            default=str(default_index),
            show_choices=False,
        ).strip()
        if response.isdecimal():
            selected_index = int(response)
            if 1 <= selected_index <= len(choices):
                return choices[selected_index - 1].value

        normalized_response = response.casefold()
        for choice in choices:
            if normalized_response in {
                choice.label.casefold(),
                choice.value.casefold(),
            }:
                return choice.value

        click.echo(
            f"Please enter a number from 1 to {len(choices)} or a listed label.",
            err=True,
        )


def text_prompt(
    message: str,
    *,
    default: str | None = None,
    secret: bool = False,
) -> str:
    inquirer = _import_inquirer_select()
    normalized_default = default if default is not None else ""
    if inquirer is not None:
        prompt = inquirer.secret if secret else inquirer.text
        return str(
            prompt(message=message, default=normalized_default).execute()
        ).strip()
    return click.prompt(message, default=normalized_default, hide_input=secret).strip()
