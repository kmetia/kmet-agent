"""A small EDN reader for kmet session files.

kmet writes one EDN map per line. The benchmark adapter needs only the
subset those maps use: maps, vectors, lists, sets, tagged values, strings,
keywords, symbols, integers, floats and nil/booleans. The reader returns
keywords as ``Keyword`` (a ``str`` subclass) and symbols as ``Symbol`` so
callers can tell ``:role`` from ``"role"``.
"""

from __future__ import annotations

from typing import Any


class EdnError(ValueError):
    """Raised when a value cannot be read as EDN."""


class Keyword(str):
    """An EDN keyword, e.g. ``:role``; ``str(keyword)`` is ``":role"``."""


class Symbol(str):
    """An EDN symbol, e.g. ``foo/bar``."""


_WHITESPACE = set(" \t\r\n,")
_DELIMITERS = set(" \t\r\n,()[]{}\";")


class _Reader:
    def __init__(self, text: str) -> None:
        self.text = text
        self.index = 0

    def _peek(self) -> str | None:
        return self.text[self.index] if self.index < len(self.text) else None

    def _next(self) -> str:
        char = self._peek()
        if char is None:
            raise EdnError("unexpected end of input")
        self.index += 1
        return char

    def _skip_ignored(self) -> None:
        while True:
            while self._peek() in _WHITESPACE:
                self.index += 1
            if self._peek() != ";":
                return
            while self._peek() not in (None, "\n"):
                self.index += 1

    def read(self) -> Any:
        self._skip_ignored()
        char = self._peek()
        if char is None:
            raise EdnError("unexpected end of input")
        if char == "#":
            self._next()
            return self._read_dispatch()
        if char == '"':
            return self._read_string()
        if char == ":":
            return self._read_keyword()
        if char == "[":
            self.index += 1
            return self._read_sequence("]")
        if char == "(":
            self.index += 1
            return self._read_sequence(")")
        if char == "{":
            self.index += 1
            return self._read_map()
        if char == "\\":
            self.index += 1
            return self._read_character()
        if char == "^":
            # Metadata is not used by session entries; skip the metadata form
            # and return the value it annotates.
            self.index += 1
            self.read()
            return self.read()
        if char in "]} )":
            raise EdnError(f"unexpected {char!r} at {self.index}")
        return self._read_atom()

    def _read_dispatch(self) -> Any:
        char = self._peek()
        if char == "{":
            self.index += 1
            return self._read_sequence("}")
        if char == "_":
            self.index += 1
            self.read()
            return self.read()
        tag = self._read_atom()
        value = self.read()
        if tag in ("inst", "uuid"):
            return value
        return value

    def _read_sequence(self, closing: str) -> list[Any]:
        values: list[Any] = []
        while True:
            self._skip_ignored()
            if self._peek() == closing:
                self.index += 1
                return values
            values.append(self.read())

    def _read_map(self) -> dict[Any, Any]:
        values = self._read_sequence("}")
        if len(values) % 2:
            raise EdnError("map has an odd number of forms")
        return {values[i]: values[i + 1] for i in range(0, len(values), 2)}

    def _read_string(self) -> str:
        self._next()  # opening quote
        chars: list[str] = []
        escapes = {
            '"': '"',
            "\\": "\\",
            "n": "\n",
            "t": "\t",
            "r": "\r",
            "b": "\b",
            "f": "\f",
            "/": "/",
        }
        while True:
            char = self._next()
            if char == '"':
                return "".join(chars)
            if char != "\\":
                chars.append(char)
                continue
            escaped = self._next()
            if escaped == "u":
                hex_digits = self.text[self.index : self.index + 4]
                if len(hex_digits) != 4:
                    raise EdnError("truncated unicode escape")
                self.index += 4
                chars.append(chr(int(hex_digits, 16)))
            else:
                chars.append(escapes.get(escaped, escaped))

    def _read_keyword(self) -> Keyword:
        self._next()  # colon
        start = self.index
        while (char := self._peek()) is not None and char not in _DELIMITERS:
            self.index += 1
        name = self.text[start : self.index]
        if not name:
            raise EdnError("empty keyword")
        return Keyword(f":{name}")

    def _read_character(self) -> str:
        start = self.index
        while (char := self._peek()) is not None and char not in _DELIMITERS:
            self.index += 1
        name = self.text[start : self.index]
        names = {
            "newline": "\n",
            "space": " ",
            "tab": "\t",
            "return": "\r",
            "backspace": "\b",
            "formfeed": "\f",
        }
        if name in names:
            return names[name]
        if len(name) == 1:
            return name
        raise EdnError(f"unknown character literal: \\{name}")

    def _read_atom(self) -> Any:
        start = self.index
        while (char := self._peek()) is not None and char not in _DELIMITERS:
            self.index += 1
        token = self.text[start : self.index]
        if not token:
            raise EdnError(f"unexpected character at {start}")
        if token == "nil":
            return None
        if token == "true":
            return True
        if token == "false":
            return False
        if token.startswith("#") and len(token) > 1:
            return token
        number = _parse_number(token)
        return number if number is not None else Symbol(token)


def _parse_number(token: str) -> int | float | None:
    try:
        if any(marker in token for marker in (".", "e", "E")):
            return float(token)
        return int(token)
    except ValueError:
        return None


def loads(text: str) -> Any:
    """Read the first EDN value in TEXT."""
    reader = _Reader(text)
    value = reader.read()
    reader._skip_ignored()
    if reader.index != len(reader.text):
        raise EdnError(f"trailing data at {reader.index}")
    return value


def load_line(line: str) -> Any:
    """Read one session line, ignoring blank lines and comments."""
    return loads(line)


def text_of(value: Any) -> str:
    """Recursively join text blocks in a kmet content vector."""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(
            part.get(":text", "")
            for part in value
            if isinstance(part, dict) and isinstance(part.get(":text"), str)
        )
    if isinstance(value, dict):
        return value.get(":text", "")
    return ""
