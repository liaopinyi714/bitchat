"""Publish a new output directory only after its complete validation succeeds."""

from contextlib import contextmanager
from pathlib import Path
import tempfile


@contextmanager
def atomic_output_directory(destination: Path):
    destination = destination.absolute()
    if destination.exists() or destination.is_symlink():
        raise ValueError("Choose a new output directory; existing artifacts are never overwritten")
    destination.parent.mkdir(parents=True, exist_ok=True)
    parent = destination.parent.resolve()
    destination = parent / destination.name
    with tempfile.TemporaryDirectory(prefix="." + destination.name + ".incomplete-", dir=parent) as temporary:
        staging = Path(temporary)
        if staging.resolve().parent != parent:
            raise ValueError("Invalid temporary release directory")
        yield staging
        if destination.exists() or destination.is_symlink():
            raise ValueError("Release destination appeared during preparation")
        staging.rename(destination)
