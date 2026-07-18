"""Secure helpers for password-protected statement PDFs."""
from __future__ import annotations

import os
from pathlib import Path
import selectors
import subprocess
import tempfile
import time


def decrypt_pdf(input_path, output_path, password: str | None, *, timeout: int = 30) -> None:
    """Decrypt a PDF without placing the password in process arguments."""
    output = Path(output_path)
    password_path = None
    argv = ["qpdf"]
    try:
        if password is not None:
            handle = tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", dir=output.parent,
                prefix=".password-", delete=False,
            )
            password_path = Path(handle.name)
            try:
                os.chmod(password_path, 0o600)
                handle.write(password)
                handle.write("\n")
            finally:
                handle.close()
            argv.append(f"--password-file={password_path}")
        argv.extend(["--decrypt", str(input_path), str(output)])
        result = subprocess.run(
            argv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=timeout,
        )
        if result.returncode != 0:
            raise ValueError("PDF decryption failed")
    finally:
        if password_path is not None:
            password_path.unlink(missing_ok=True)


def extract_pdf_text(
    input_path, *, timeout: int = 60, max_bytes: int = 25 * 1024 * 1024,
) -> str:
    """Extract mutool text while enforcing hard time and output-memory limits."""
    process = subprocess.Popen(
        ["mutool", "draw", "-F", "text", str(input_path)],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    )
    if process.stdout is None:
        process.kill()
        raise ValueError("PDF text extraction failed")
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    deadline = time.monotonic() + timeout
    chunks: list[bytes] = []
    total = 0
    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("PDF text extraction timed out")
            for key, _ in selector.select(min(0.1, remaining)):
                data = os.read(key.fd, 64 * 1024)
                if not data:
                    selector.unregister(key.fileobj)
                    continue
                total += len(data)
                if total > max_bytes:
                    raise ValueError("extracted statement text exceeds 25 MiB limit")
                chunks.append(data)
        if process.wait(timeout=max(0.1, deadline - time.monotonic())) != 0:
            raise ValueError("PDF text extraction failed")
        return b"".join(chunks).decode("utf-8", errors="replace")
    finally:
        selector.close()
        process.stdout.close()
        if process.poll() is None:
            process.kill()
            process.wait()
