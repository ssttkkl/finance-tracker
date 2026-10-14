#!/usr/bin/env python3
"""Bootstrap a verified Azul Zulu JDK and build the Compose Wasm distribution."""

from __future__ import annotations

import hashlib
import json
import os
import platform
import posixpath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

FOOJAY_API = "https://api.foojay.io/disco/v3.0"
USER_AGENT = "finance-tracker-render-build/2.0"
SHA256_PATTERN = re.compile(r"(?<![A-Fa-f0-9])[A-Fa-f0-9]{64}(?![A-Fa-f0-9])")
LOCAL_API_ORIGIN = "http://127.0.0.1:8000"
API_ORIGIN_PATTERN = re.compile(
    r"^https://(?:\[[0-9A-Fa-f:]+\]|[A-Za-z0-9.-]+)(?::([0-9]{1,5}))?$"
)


class BuildError(RuntimeError):
    """A build bootstrap step failed with an actionable diagnostic."""


def get_bytes(url: str, *, timeout: int = 45) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.read()
        except (OSError, urllib.error.URLError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(2 * (attempt + 1))
    raise BuildError(f"请求失败：{urlparse_host(url)}（{last_error}）") from last_error


def urlparse_host(url: str) -> str:
    return urllib.parse.urlsplit(url).hostname or "未知主机"


def get_json(url: str) -> dict[str, Any]:
    try:
        payload = json.loads(get_bytes(url))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise BuildError(f"Foojay 返回的 JSON 无法解析：{error}") from error
    if not isinstance(payload, dict):
        raise BuildError("Foojay 返回的 JSON 结构无效")
    return payload


def get_text(url: str) -> str:
    try:
        return get_bytes(url).decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise BuildError(f"SHA-256 校验文件不是 UTF-8 文本：{urlparse_host(url)}") from error


def download_file(url: str, destination: Path, *, timeout: int = 900) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                with destination.open("wb") as output:
                    shutil.copyfileobj(response, output, length=1024 * 1024)
            return
        except (OSError, urllib.error.URLError) as error:
            last_error = error
            destination.unlink(missing_ok=True)
            if attempt < 2:
                time.sleep(2 * (attempt + 1))
    raise BuildError(f"JDK 下载失败：{urlparse_host(url)}（{last_error}）") from last_error


def version_key(value: str) -> tuple[int, ...]:
    release = value.split("+", 1)[0]
    try:
        return tuple(int(component) for component in release.split("."))
    except ValueError:
        return ()


def select_packages(packages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    valid = []
    for package in packages:
        package_id = package.get("id")
        version = package.get("java_version")
        if (
            package.get("lib_c_type") == "glibc"
            and isinstance(package_id, str)
            and re.fullmatch(r"[a-fA-F0-9]{32}", package_id)
            and isinstance(version, str)
            and version.startswith("21.")
            and version_key(version)
        ):
            valid.append(package)
    return sorted(
        valid,
        key=lambda package: (
            version_key(package["java_version"]),
            package["id"],
        ),
        reverse=True,
    )


def valid_https_url(value: Any, field: str) -> str:
    if not isinstance(value, str) or any(ord(char) < 32 for char in value):
        raise BuildError(f"Foojay 包详情缺少有效字段：{field}")
    try:
        parsed = urllib.parse.urlsplit(value)
    except ValueError as error:
        raise BuildError(f"Foojay 字段 {field} 不是有效的 HTTPS 地址") from error
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
    ):
        raise BuildError(f"Foojay 字段 {field} 必须是有效的 HTTPS 地址")
    return value


def checksum_from_details(details: dict[str, Any], package_id: str) -> str:
    checksum = details.get("checksum")
    checksum_type = details.get("checksum_type")
    if isinstance(checksum, str) and SHA256_PATTERN.fullmatch(checksum.strip()):
        if str(checksum_type).lower() in ("sha256", "sha-256"):
            return checksum.strip().lower()

    checksum_uri = details.get("checksum_uri")
    if not checksum_uri:
        raise BuildError(
            f"Foojay 包 {package_id} 缺少有效 SHA-256（checksum/checksum_uri）"
        )
    checksum_uri = valid_https_url(checksum_uri, "checksum_uri")
    if checksum_type and str(checksum_type).lower() not in ("sha256", "sha-256"):
        raise BuildError(f"Foojay 包 {package_id} 的 checksum_type 不是 SHA-256")
    matches = SHA256_PATTERN.findall(get_text(checksum_uri))
    if not matches:
        raise BuildError(f"Foojay 校验文件未包含 SHA-256：{urlparse_host(checksum_uri)}")
    return matches[0].lower()


def package_details(package_id: str) -> dict[str, Any]:
    url = f"{FOOJAY_API}/ids/{urllib.parse.quote(package_id, safe='')}"
    payload = get_json(url)
    result = payload.get("result")
    if not isinstance(result, list) or len(result) != 1 or not isinstance(result[0], dict):
        raise BuildError(f"Foojay 未返回包 {package_id} 的唯一详情记录")
    return result[0]


def query_package(architecture: str) -> dict[str, str]:
    query = urllib.parse.urlencode(
        {
            "version": "21",
            "distribution": "zulu",
            "architecture": architecture,
            "operating_system": "linux",
            "package_type": "jdk",
            "archive_type": "tar.gz",
            "latest": "available",
            "release_status": "ga",
            "javafx_bundled": "false",
        }
    )
    payload = get_json(f"{FOOJAY_API}/packages?{query}")
    result = payload.get("result")
    if not isinstance(result, list):
        raise BuildError("Foojay 包查询响应缺少 result 列表")

    candidates = select_packages([item for item in result if isinstance(item, dict)])
    if not candidates:
        raise BuildError(
            f"Foojay 没有返回 Linux/glibc {architecture} Azul Zulu JDK 21 GA 包"
        )

    failures = []
    for package in candidates:
        package_id = package["id"]
        try:
            details = package_details(package_id)
            download_url = valid_https_url(
                details.get("direct_download_uri"), "direct_download_uri"
            )
            checksum = checksum_from_details(details, package_id)
            return {
                "id": package_id,
                "sha256": checksum,
                "download_url": download_url,
                "java_version": package["java_version"],
            }
        except BuildError as error:
            failures.append(f"{package_id}: {error}")
    summary = "; ".join(failures[:3])
    raise BuildError(f"Foojay 返回的候选包信息不完整：{summary}")


def verify_archive_paths(archive_path: Path) -> None:
    try:
        with tarfile.open(archive_path, "r:gz") as archive:
            for member in archive.getmembers():
                name = member.name
                normalized = posixpath.normpath(name)
                if name.startswith("/") or normalized in ("..", ".") or normalized.startswith("../"):
                    raise BuildError(f"JDK 归档包含越界路径：{name}")
                if member.issym() or member.islnk():
                    target = member.linkname
                    resolved = posixpath.normpath(
                        target
                        if member.islnk()
                        else posixpath.join(posixpath.dirname(name), target)
                    )
                    if target.startswith("/") or resolved in ("..", ".") or resolved.startswith("../"):
                        raise BuildError(f"JDK 归档包含越界链接：{name} -> {target}")
    except (OSError, tarfile.TarError) as error:
        raise BuildError(f"JDK 下载文件不是有效的 gzip tar 归档：{error}") from error


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def cached_jdk(cache_dir: Path, expected_sha256: str) -> bool:
    java = cache_dir / "jdk" / "bin" / "java"
    marker = cache_dir / "sha256"
    try:
        return java.is_file() and os.access(java, os.X_OK) and marker.read_text().strip() == expected_sha256
    except OSError:
        return False


def install_jdk(package: dict[str, str], architecture: str, cache_root: Path) -> Path:
    cache_dir = cache_root / package["id"]
    if cached_jdk(cache_dir, package["sha256"]):
        print(f"Using cached Azul Zulu JDK {package['java_version']} ({package['id']})")
        return cache_dir / "jdk"

    with tempfile.TemporaryDirectory(prefix=f".jdk-{package['id']}.", dir=cache_root) as temp_name:
        temporary_dir = Path(temp_name)
        archive_path = temporary_dir / "jdk.tar.gz"
        print(f"Downloading Azul Zulu JDK {package['java_version']} for Linux/{architecture}...")
        download_file(package["download_url"], archive_path)
        actual = sha256_file(archive_path)
        if actual != package["sha256"]:
            raise BuildError("JDK SHA-256 校验失败，未安装或缓存该文件")

        verify_archive_paths(archive_path)
        extracted = temporary_dir / "extracted"
        extracted.mkdir()
        try:
            subprocess.run(
                [
                    "tar",
                    "-xzf",
                    str(archive_path),
                    "--no-same-owner",
                    "--no-same-permissions",
                    "-C",
                    str(extracted),
                ],
                check=True,
                stdout=subprocess.DEVNULL,
            )
        except (OSError, subprocess.CalledProcessError) as error:
            raise BuildError(f"JDK 解压失败：{error}") from error

        java_binaries = list(extracted.glob("**/bin/java"))
        java_binary = next(
            (candidate for candidate in java_binaries if candidate.is_file() and os.access(candidate, os.X_OK)),
            None,
        )
        if java_binary is None:
            raise BuildError("Foojay JDK 归档中未找到可执行的 bin/java")
        jdk_root = java_binary.parent.parent

        entry = temporary_dir / "cache-entry"
        entry.mkdir()
        shutil.move(str(jdk_root), str(entry / "jdk"))
        (entry / "sha256").write_text(package["sha256"] + "\n", encoding="ascii")
        if cache_dir.is_symlink():
            raise BuildError("JDK 缓存目录不能是符号链接")
        backup = temporary_dir / "previous-cache"
        had_previous_cache = cache_dir.exists()
        try:
            if had_previous_cache:
                os.replace(cache_dir, backup)
            os.replace(entry, cache_dir)
        except OSError as error:
            if had_previous_cache and backup.exists() and not cache_dir.exists():
                os.replace(backup, cache_dir)
            if not cached_jdk(cache_dir, package["sha256"]):
                raise BuildError(f"无法安装 JDK 缓存：{error}") from error
    return cache_dir / "jdk"


def normalize_web_api_origin(api_origin: str) -> str:
    candidate = api_origin.strip().removesuffix("/")
    if not candidate:
        return LOCAL_API_ORIGIN
    match = API_ORIGIN_PATTERN.fullmatch(candidate)
    if match is None:
        raise BuildError(
            "FT_API_ORIGIN 必须是 HTTPS origin，不能包含路径、查询参数或凭据"
        )
    port = match.group(1)
    if port is not None and int(port) not in range(1, 65536):
        raise BuildError("FT_API_ORIGIN 的端口必须在 1 到 65535 之间")
    return candidate


def configure_web_api_origin(config_path: Path, api_origin: str) -> None:
    candidate = normalize_web_api_origin(api_origin)
    if not config_path.is_file():
        raise BuildError(f"Wasm 产物中未找到 config.js：{config_path}")
    config = f"window.FT_API_ORIGIN = {json.dumps(candidate)};\n"
    config_path.write_text(config, encoding="utf-8")
    print("Configured Compose Web API origin from FT_API_ORIGIN.")


def detect_architecture() -> str:
    if platform.system() != "Linux":
        raise BuildError("此脚本仅支持 Linux 构建环境")
    machine = platform.machine().lower()
    if machine in ("x86_64", "amd64"):
        return "x64"
    if machine in ("aarch64", "arm64"):
        return "aarch64"
    raise BuildError(f"不支持的 Linux 架构：{platform.machine()}")


def main() -> int:
    try:
        architecture = detect_architecture()
        script_dir = Path(__file__).resolve().parent
        cache_base = os.environ.get("XDG_CACHE_HOME")
        if not cache_base:
            home = os.environ.get("HOME")
            if not home:
                raise BuildError("XDG_CACHE_HOME 与 HOME 均未设置，无法定位 JDK 缓存")
            cache_base = str(Path(home) / ".cache")
        cache_root = Path(cache_base) / "finance-tracker" / "jdks"
        cache_root.mkdir(parents=True, exist_ok=True)
        environment = os.environ.copy()
        api_origin = normalize_web_api_origin(environment.get("FT_API_ORIGIN", ""))

        package = query_package(architecture)
        jdk_home = install_jdk(package, architecture, cache_root)
        environment["JAVA_HOME"] = str(jdk_home)
        environment["PATH"] = str(jdk_home / "bin") + os.pathsep + environment.get("PATH", "")
        try:
            version_output = subprocess.check_output(
                [str(jdk_home / "bin" / "java"), "-version"],
                stderr=subprocess.STDOUT,
                text=True,
                env=environment,
            )
        except (OSError, subprocess.CalledProcessError) as error:
            raise BuildError(f"缓存中的 Java 无法启动：{error}") from error
        if not re.search(r'version "21(?:[.]|[+])', version_output):
            raise BuildError("JDK 版本不是 Java 21")
        print(f"Using {version_output.splitlines()[0]}")
        gradle = script_dir / "gradlew"
        result = subprocess.run(
            [str(gradle), ":webApp:wasmJsBrowserDistribution", *sys.argv[1:]],
            cwd=script_dir,
            env=environment,
            check=False,
        )
        if result.returncode != 0:
            return result.returncode
        config_path = (
            script_dir
            / "webApp/build/dist/wasmJs/productionExecutable/config.js"
        )
        configure_web_api_origin(config_path, api_origin)
        return 0
    except BuildError as error:
        print(f"build-wasm-web: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
