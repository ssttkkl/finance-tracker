#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'build-wasm-web: %s\n' "$*" >&2
  exit 1
}

for command_name in python3 curl tar find awk; do
  command -v "$command_name" >/dev/null 2>&1 || fail "需要预装命令：$command_name"
done

if command -v sha256sum >/dev/null 2>&1; then
  sha256_file() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
  sha256_file() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  fail '需要 sha256sum 或 shasum 来校验 JDK 下载文件'
fi

[[ "$(uname -s)" == Linux ]] || fail '此脚本仅支持 Linux 构建环境'
case "$(uname -m)" in
  x86_64|amd64) architecture=x64 ;;
  aarch64|arm64) architecture=aarch64 ;;
  *) fail "不支持的 Linux 架构：$(uname -m)" ;;
esac

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cache_root="${XDG_CACHE_HOME:-${HOME:?HOME 未设置}/.cache}/finance-tracker/jdks"
mkdir -p "$cache_root"

# Query metadata rather than pinning a patch version so new GA security patches
# are used automatically. Keep JSON parsing in Python's standard library.
package_info="$(python3 - "$architecture" <<'PY'
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

base = "https://api.foojay.io/disco/v3.0"
architecture = sys.argv[1]
query = urllib.parse.urlencode({
    "version": "21",
    "distribution": "zulu",
    "architecture": architecture,
    "operating_system": "linux",
    "package_type": "jdk",
    "archive_type": "tar.gz",
    "latest": "available",
    "release_status": "ga",
    "javafx_bundled": "false",
})

def get_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": "finance-tracker-render-build/1.0"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.load(response)

try:
    packages = get_json(f"{base}/packages?{query}").get("result", [])
    packages = [package for package in packages if package.get("lib_c_type") == "glibc"]
    if len(packages) != 1:
        raise ValueError(f"预期 1 个 glibc 包，实际找到 {len(packages)} 个")

    package = packages[0]
    package_id = package.get("id", "")
    if not re.fullmatch(r"[a-f0-9]{32}", package_id):
        raise ValueError("Foojay 返回的包 ID 无效")
    detail = get_json(f"{base}/ids/{package_id}").get("result", [])
    if len(detail) != 1:
        raise ValueError("Foojay 未返回唯一的包详情")
    detail = detail[0]
    checksum = detail.get("checksum", "")
    if detail.get("checksum_type", "").lower() != "sha256" or not re.fullmatch(r"[a-fA-F0-9]{64}", checksum):
        raise ValueError("Foojay 未提供有效的 SHA-256 校验和")
    download_url = detail.get("direct_download_uri", "")
    if not download_url.startswith("https://"):
        raise ValueError("Foojay 未提供 HTTPS 下载地址")
    java_version = package.get("java_version", "")
    if not java_version.startswith("21."):
        raise ValueError(f"Foojay 返回了非 Java 21 包：{java_version}")
    print("\t".join((package_id, checksum.lower(), download_url, java_version)))
except (OSError, ValueError, KeyError, urllib.error.URLError, json.JSONDecodeError) as error:
    print(f"无法获取 Azul Zulu JDK 21 GA 信息：{error}", file=sys.stderr)
    sys.exit(1)
PY
)" || fail '获取 Azul Zulu JDK 21 信息失败'

IFS=$'\t' read -r package_id expected_sha256 download_url java_version <<<"$package_info"
[[ -n "$package_id" && -n "$expected_sha256" && -n "$download_url" ]] || fail 'Foojay 返回了不完整的包信息'
cache_dir="$cache_root/$package_id"

cache_is_valid=false
if [[ -x "$cache_dir/jdk/bin/java" && -f "$cache_dir/sha256" ]] && \
  [[ "$(<"$cache_dir/sha256")" == "$expected_sha256" ]]; then
  cache_is_valid=true
fi

if [[ "$cache_is_valid" != true ]]; then
  temporary_dir="$(mktemp -d "$cache_root/.jdk-${package_id}.XXXXXX")"
  cleanup() { rm -rf -- "$temporary_dir"; }
  trap cleanup EXIT

  archive="$temporary_dir/jdk.tar.gz"
  printf 'Downloading Azul Zulu JDK %s for Linux/%s...\n' "$java_version" "$architecture"
  curl --fail --location --silent --show-error --retry 3 --retry-delay 2 \
    --connect-timeout 20 --max-time 900 \
    "$download_url" --output "$archive" || fail 'JDK 下载失败'

  actual_sha256="$(sha256_file "$archive")"
  [[ "$actual_sha256" == "$expected_sha256" ]] || fail 'JDK SHA-256 校验失败，未安装或缓存该文件'

  # Reject archive paths and links that could escape the extraction directory.
  python3 - "$archive" <<'PY'
import posixpath
import sys
import tarfile

archive = sys.argv[1]
try:
    with tarfile.open(archive, "r:gz") as package:
        for member in package.getmembers():
            name = member.name
            normalized = posixpath.normpath(name)
            if name.startswith("/") or normalized == ".." or normalized.startswith("../"):
                raise ValueError(f"归档包含越界路径：{name}")
            if member.issym() or member.islnk():
                target = member.linkname
                resolved = posixpath.normpath(
                    target if member.islnk() else posixpath.join(posixpath.dirname(name), target)
                )
                if target.startswith("/") or resolved == ".." or resolved.startswith("../"):
                    raise ValueError(f"归档包含越界链接：{name} -> {target}")
except (OSError, tarfile.TarError, ValueError) as error:
    print(f"拒绝不安全或无效的 JDK 归档：{error}", file=sys.stderr)
    sys.exit(1)
PY

  extraction_dir="$temporary_dir/extracted"
  mkdir "$extraction_dir"
  tar -xzf "$archive" --no-same-owner --no-same-permissions -C "$extraction_dir" || fail 'JDK 解压失败'
  java_binary="$(find "$extraction_dir" -type f -path '*/bin/java' -print -quit)"
  [[ -n "$java_binary" && -x "$java_binary" ]] || fail 'JDK 归档中未找到可执行的 bin/java'
  jdk_root="${java_binary%/bin/java}"

  mkdir "$temporary_dir/cache-entry"
  mv "$jdk_root" "$temporary_dir/cache-entry/jdk"
  printf '%s\n' "$expected_sha256" >"$temporary_dir/cache-entry/sha256"
  rm -rf -- "$cache_dir"
  mv "$temporary_dir/cache-entry" "$cache_dir"
fi

export JAVA_HOME="$cache_dir/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
java_version_output="$("$JAVA_HOME/bin/java" -version 2>&1)" || fail '缓存中的 Java 无法启动'
printf '%s\n' "$java_version_output" | grep -Eq 'version "21([.]|[+])' || fail 'JDK 版本不是 Java 21'
printf 'Using %s\n' "$(printf '%s\n' "$java_version_output" | head -n 1)"

if [[ -n "${temporary_dir:-}" ]]; then
  cleanup
  trap - EXIT
fi

cd "$script_dir"
exec ./gradlew :webApp:wasmJsBrowserDistribution "$@"
