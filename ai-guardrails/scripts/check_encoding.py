#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_encoding.py — 编码完整性与文件体积突变检查

用途
    防止 AI 协作中最高发的两类事故：
      ① 中文乱码（编码不一致）
      ② 文件被覆盖（内容丢失）

检查项
    1. 是否为合法 UTF-8
    2. 是否含 UTF-8 BOM（会导致部分工具解析失败）
    3. 是否含乱码特征（UTF-8 被误读为 GBK / Latin-1）
    4. 是否含 NUL 字节（UTF-16 被误存为文本）
    5. 换行符是否混用（CRLF/LF 混合会让 diff 不可读）
    6. 【--check-shrink】文件是否异常缩小（疑似被 AI 覆盖）

用法
    python scripts/check_encoding.py .
    python scripts/check_encoding.py src/ docs/
    python scripts/check_encoding.py . --fix            # 自动修 BOM 与换行符
    python scripts/check_encoding.py . --check-shrink   # 额外检查体积突变

退出码
    0 = 未发现问题
    1 = 发现问题（CI / pre-commit 会因此阻止提交）

豁免
    文件内出现 `check-encoding:ignore-mojibake` 时，跳过"乱码特征串"检查。
    供专门讲解编码问题的文档使用。本脚本自身也带此标记。
"""

# check-encoding:ignore-mojibake
# ↑ 本脚本的定义里包含乱码特征串（MOJIBAKE_MARKERS），
#   属于"讲解编码问题"的合法场景，故自我豁免该检查。


from __future__ import annotations

import argparse
import codecs
import subprocess
import sys
from pathlib import Path
from typing import Iterable, List, NamedTuple

# ============ 控制台编码加固 ============
# 中文 Windows 的控制台默认是 GBK，直接 print 中文或 emoji 会抛
# UnicodeEncodeError 让脚本自己崩掉 —— 一个检查编码的脚本因为编码崩掉，
# 是最讽刺的 bug。所以这里先把标准输出强制成 UTF-8。
#
# 注意：这不改变任何被检查文件的内容，只是让本脚本的输出能正常显示。

def _harden_console() -> None:
    """把 stdout/stderr 切到 UTF-8；失败则降级为可丢字符，绝不抛异常。"""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue                      # 被重定向到不支持的对象，跳过
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (ValueError, OSError, AttributeError):
            # 极端环境下（如只读流）忽略，后面用 ASCII 符号兜底
            pass


_harden_console()

# emoji 在部分终端宽度异常，但既然已强制 UTF-8，可以安全使用
_ICON_ERROR = "❌"
_ICON_WARN = "⚠️ "
_ICON_FIX = "🔧"
_ICON_OK = "✅"

# ============ 配置 ============

# 需要检查的文本文件后缀
TEXT_EXTENSIONS = {
    ".py", ".pyi", ".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs",
    ".json", ".jsonl", ".yml", ".yaml", ".toml", ".ini", ".cfg", ".conf",
    ".md", ".markdown", ".txt", ".rst", ".log",
    ".html", ".htm", ".css", ".scss", ".less", ".sass",
    ".sql", ".sh", ".bash", ".zsh", ".ps1", ".bat", ".cmd",
    ".xml", ".csv", ".tsv", ".env", ".example", ".lock",
    ".dockerfile", ".editorconfig", ".gitattributes", ".gitignore",
}

# 明确跳过（二进制）
BINARY_EXTENSIONS = {
    ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp", ".tiff",
    ".pdf", ".zip", ".gz", ".tar", ".7z", ".rar", ".bz2", ".xz",
    ".woff", ".woff2", ".ttf", ".otf", ".eot",
    ".mp3", ".mp4", ".avi", ".mov", ".wav", ".flac",
    ".exe", ".dll", ".so", ".dylib", ".bin", ".o", ".a", ".class", ".pyc",
    ".xlsx", ".docx", ".pptx", ".db", ".sqlite", ".sqlite3",
}

# 跳过的目录
SKIP_DIRS = {
    ".git", ".hg", ".svn", "node_modules", ".venv", "venv", "env", "ENV",
    "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache",
    ".idea", ".vscode", "dist", "build", ".next", ".nuxt",
    "chroma", "qdrant_storage", ".tox", "htmlcov", ".coverage",
    "site-packages", ".eggs",

    # 外部参考项目：不是本项目的代码，不受本项目编码规范约束。
    # （例如从网上拉取、仅用于阅读分析的项目，其文件可能保持原始换行符。）
    "physics-agent",
}

# 单文件大小上限（超过则跳过，视为数据文件）
MAX_FILE_SIZE = 5 * 1024 * 1024  # 5 MB

# 乱码特征串（这些串出现在文本里几乎必然是编码事故）
#
# ⚠️ 判定方式说明：不能"出现即报错"，因为讲解编码问题的文档本身就会
# 引用这些字符串。真乱码的特征是大量重复，所以用"次数 + 占比"双阈值。
MOJIBAKE_MARKERS = [
    "\ufffd",      # U+FFFD 替换字符
    "锟斤拷",       # GBK/UTF-8 双重转换的经典产物
    "锘",          # UTF-8 BOM 被当作 GBK 解读
    "烫烫烫",       # MSVC Debug 未初始化内存
    "屯屯屯",
    "ï»¿",         # BOM 被当作 Latin-1 解读
    "Ã¯Â»Â¿",
]

# 触发告警的最少出现次数（低于此视为"文档里在举例"）
MOJIBAKE_MIN_HITS = 3

# 触发告警的最少字符占比
MOJIBAKE_MIN_RATIO = 0.002

# 文件级豁免标记：文件内出现此串则跳过乱码特征检查
# 给"专门讲解编码/乱码问题"的文档使用
IGNORE_MARKER = "check-encoding:ignore-mojibake"

# Latin-1 乱码区间（UTF-8 中文被当 Latin-1 读时，会落在这个区间）
LATIN1_RANGE = (0x00A0, 0x00FF)
CJK_RANGES = (
    (0x4E00, 0x9FFF),   # CJK 统一表意文字
    (0x3400, 0x4DBF),   # 扩展 A
    (0x3000, 0x303F),   # CJK 标点
    (0xFF00, 0xFFEF),   # 全角字符
)


# ============ 数据结构 ============


class Issue(NamedTuple):
    path: Path
    severity: str      # "error" | "warning"
    code: str
    message: str


# ============ 工具函数 ============


def is_text_candidate(path: Path) -> bool:
    """判断是否值得按文本检查"""
    ext = path.suffix.lower()
    if ext in BINARY_EXTENSIONS:
        return False
    if ext in TEXT_EXTENSIONS:
        return True
    # 无后缀文件（Makefile、Dockerfile 等）也检查
    if not ext:
        return True
    # 其他未知后缀，检查是否有已知文本名
    return path.name.lower() in {
        "makefile", "dockerfile", "license", "readme", "changelog",
    }


def iter_files(roots: List[str]) -> Iterable[Path]:
    """遍历待检查文件"""
    for root in roots:
        p = Path(root)
        if not p.exists():
            continue
        if p.is_file():
            if is_text_candidate(p):
                yield p
            continue
        for f in p.rglob("*"):
            if not f.is_file():
                continue
            if any(part in SKIP_DIRS for part in f.parts):
                continue
            try:
                if f.stat().st_size > MAX_FILE_SIZE:
                    continue
            except OSError:
                continue
            if is_text_candidate(f):
                yield f


def byte_offset_to_line(raw: bytes, offset: int) -> int:
    """字节偏移 → 行号（1-based）"""
    return raw[:offset].count(b"\n") + 1


def is_cjk(ch: str) -> bool:
    cp = ord(ch)
    return any(lo <= cp <= hi for lo, hi in CJK_RANGES)


def latin1_mojibake_ratio(text: str) -> float:
    """
    计算「疑似 Latin-1 乱码」的比例。

    原理：UTF-8 中文被当 Latin-1 读时，每个中文字会变成 3 个
    U+00A0–U+00FF 区间的字符，于是该区间占比极高，且几乎没有 CJK。
    """
    non_ascii = [ch for ch in text if ord(ch) > 0x7F]
    if len(non_ascii) < 20:          # 样本太小，不判定
        return 0.0
    if any(is_cjk(ch) for ch in non_ascii):
        return 0.0                    # 有正常中文，说明不是整体乱码
    latin1_count = sum(
        1 for ch in non_ascii if LATIN1_RANGE[0] <= ord(ch) <= LATIN1_RANGE[1]
    )
    return latin1_count / len(non_ascii)


# ============ 单文件检查 ============


def check_file(path: Path, fix: bool) -> tuple[List[Issue], bool]:
    """
    检查单个文件。

    返回：(问题列表, 是否被修改)
    """
    issues: List[Issue] = []
    modified = False

    try:
        raw = path.read_bytes()
    except OSError as e:
        issues.append(Issue(path, "error", "READ_FAIL", f"无法读取: {e}"))
        return issues, modified

    if not raw:
        return issues, modified

    # ---- 检查 1：NUL 字节（UTF-16 被误存为文本）----
    if b"\x00" in raw[:8192]:
        issues.append(Issue(
            path, "error", "NUL_BYTES",
            "含 NUL 字节，疑似 UTF-16/二进制被当作文本。"
            "若本应是文本，请用 UTF-8 重新保存。",
        ))
        return issues, modified

    # ---- 检查 2：UTF-8 BOM ----
    has_bom = raw.startswith(codecs.BOM_UTF8)
    if has_bom:
        if fix:
            raw = raw[len(codecs.BOM_UTF8):]
            modified = True
        else:
            issues.append(Issue(
                path, "warning", "BOM",
                "含 UTF-8 BOM。部分解析器（JSON、脚本、Linux 工具）会出错。",
            ))

    # ---- 检查 3：是否为合法 UTF-8 ----
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as e:
        line = byte_offset_to_line(raw, e.start)
        issues.append(Issue(
            path, "error", "INVALID_UTF8",
            f"第 {line} 行不是合法 UTF-8（字节 0x{raw[e.start]:02X}）。"
            f"文件很可能是 GBK/GB18030 编码。",
        ))
        # 尝试用 GBK 解码，给出修复提示
        try:
            raw.decode("gbk")
            issues.append(Issue(
                path, "warning", "LOOKS_LIKE_GBK",
                "该文件可以被 GBK 正常解码 —— 建议转成 UTF-8："
                "用编辑器「以 GBK 打开 → 另存为 UTF-8」。",
            ))
        except UnicodeDecodeError:
            pass
        return issues, modified

    # ---- 检查 4：乱码特征串 ----
    # ⚠️ 不能"出现即报错"：文档里举例说明乱码时，本来就会写到这些字符串。
    # 真乱码的特征是「大量重复」（一个中文被破坏就产生一个这样的串），
    # 所以用出现次数 + 占比双重阈值判定。
    #
    # 文件级豁免：文件内出现 IGNORE_MARKER 则跳过本项检查
    # （给"讲解编码问题"的文档用）。
    if IGNORE_MARKER not in text:
        total_marker_hits = 0
        worst_marker = ""
        for marker in MOJIBAKE_MARKERS:
            n = text.count(marker)
            if n > total_marker_hits:
                total_marker_hits, worst_marker = n, marker

        # 阈值：出现 >= 3 次，且占非空字符数的比例可观
        non_space = sum(1 for ch in text if not ch.isspace())
        ratio_marker = total_marker_hits / max(non_space, 1)

        if total_marker_hits >= MOJIBAKE_MIN_HITS and ratio_marker >= MOJIBAKE_MIN_RATIO:
            issues.append(Issue(
                path, "error", "MOJIBAKE_MARKER",
                f"乱码特征 {worst_marker!r} 出现 {total_marker_hits} 次"
                f"（占非空字符 {ratio_marker:.1%}）。该文件很可能已被错误转码。"
                f"若这是讲解编码问题的文档，可在文件内加入 {IGNORE_MARKER} 跳过本项检查。",
            ))

    # ---- 检查 5：Latin-1 乱码比例 ----
    ratio = latin1_mojibake_ratio(text)
    if ratio > 0.6:
        issues.append(Issue(
            path, "error", "MOJIBAKE_LATIN1",
            f"{ratio:.0%} 的非 ASCII 字符落在 Latin-1 区间且无中文 —— "
            f"疑似 UTF-8 被按 Latin-1/GBK 解读。",
        ))

    # ---- 检查 6：换行符 ----
    # 注意：--fix 必须能处理「纯 CRLF」和「混用」两种情况，
    # 早期版本只修混用，导致 --fix 对纯 CRLF 文件无效（已修）。
    has_crlf = "\r\n" in text
    has_lone_cr = "\r" in text.replace("\r\n", "")
    has_lone_lf = text.replace("\r\n", "").count("\n") > 0

    if has_crlf or has_lone_cr:
        if fix:
            # 统一为 LF
            text = text.replace("\r\n", "\n").replace("\r", "\n")
            modified = True
        elif has_crlf and has_lone_lf:
            issues.append(Issue(
                path, "warning", "MIXED_EOL",
                "CRLF 与 LF 混用，会让 git diff 显示整个文件都是改动。",
            ))
        else:
            issues.append(Issue(
                path, "warning", "CRLF",
                "使用 CRLF 换行。项目约定为 LF（.gitattributes 已声明）。"
                "可用 --fix 自动转换。",
            ))

    # ---- 应用 --fix 的写入 ----
    if fix and modified:
        try:
            path.write_bytes(text.encode("utf-8"))
        except OSError as e:
            issues.append(Issue(path, "error", "WRITE_FAIL", f"修复失败: {e}"))
            modified = False

    return issues, modified


# ============ 体积突变检查（防覆盖）============


def check_shrink(root: str, min_removed: int = 20, ratio: float = 3.0) -> List[Issue]:
    """
    用 git 对比工作区与 HEAD，找出「行数大幅减少」的文件。

    这是检测「文件被 AI 覆盖」最直接的手段。
    """
    issues: List[Issue] = []

    try:
        result = subprocess.run(
            ["git", "diff", "--numstat", "HEAD"],
            cwd=root,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return issues       # 不是 git 仓库 / git 不可用，静默跳过

    if result.returncode != 0:
        return issues

    for line in result.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        added_s, removed_s, rel = parts
        if added_s == "-" or removed_s == "-":
            continue        # 二进制文件
        try:
            added, removed = int(added_s), int(removed_s)
        except ValueError:
            continue

        # 判定：删除量显著大于新增量
        suspicious = (
            removed >= min_removed
            and (added == 0 or removed > added * ratio)
        )
        if not suspicious:
            continue

        # 取原始行数，算缩小比例
        orig_lines = None
        try:
            show = subprocess.run(
                ["git", "show", f"HEAD:{rel}"],
                cwd=root, capture_output=True, text=True,
                encoding="utf-8", errors="replace", timeout=20,
            )
            if show.returncode == 0:
                orig_lines = show.stdout.count("\n") + 1
        except (OSError, subprocess.SubprocessError):
            pass

        detail = f"删除 {removed} 行、新增 {added} 行"
        if orig_lines:
            pct = removed / max(orig_lines, 1)
            detail += f"（原 {orig_lines} 行，减少约 {pct:.0%}）"
        detail += "。⚠️ 疑似被整文件重写导致内容丢失，请用 git diff 确认。"

        issues.append(Issue(Path(rel), "error", "SHRINK", detail))

    return issues


# ============ 主流程 ============


def main() -> int:
    parser = argparse.ArgumentParser(
        description="编码完整性与文件体积突变检查",
    )
    parser.add_argument(
        "paths", nargs="*", default=["."],
        help="要检查的文件或目录（默认当前目录）",
    )
    parser.add_argument(
        "--fix", action="store_true",
        help="自动修复：移除 BOM、统一换行为 LF",
    )
    parser.add_argument(
        "--check-shrink", action="store_true",
        help="额外检查文件是否异常缩小（需在 git 仓库中运行）",
    )
    parser.add_argument(
        "--quiet", action="store_true",
        help="只输出问题，不输出正常文件",
    )
    args = parser.parse_args()

    roots = args.paths or ["."]

    all_issues: List[Issue] = []
    checked = 0
    fixed_count = 0

    print("=" * 68)
    print("编码与文件完整性检查")
    print("=" * 68)

    for path in iter_files(roots):
        checked += 1
        issues, modified = check_file(path, args.fix)
        if modified:
            fixed_count += 1
            print(f"  {_ICON_FIX} 已修复  {path}")
        all_issues.extend(issues)

    # 体积突变检查
    if args.check_shrink:
        for root in roots:
            all_issues.extend(check_shrink(root))

    # ---- 输出问题 ----
    errors = [i for i in all_issues if i.severity == "error"]
    warnings = [i for i in all_issues if i.severity == "warning"]

    if all_issues:
        print()
        for issue in sorted(all_issues, key=lambda i: (str(i.path), i.code)):
            icon = _ICON_ERROR if issue.severity == "error" else _ICON_WARN
            print(f"{icon} [{issue.code}] {issue.path}")
            print(f"      {issue.message}")

    # ---- 汇总 ----
    print()
    print("-" * 68)
    print(
        f"检查文件：{checked} 个    "
        f"{_ICON_ERROR} 错误：{len(errors)}    "
        f"{_ICON_WARN} 警告：{len(warnings)}    "
        f"{_ICON_FIX} 已修复：{fixed_count}"
    )

    if errors:
        print()
        print("存在错误，请修复后再提交。")
        print("提示：GBK 文件请用编辑器「以 GBK 打开 → 另存为 UTF-8」。")
        return 1

    if warnings:
        print()
        print("存在警告（不阻止提交，但建议处理）。")
        print("提示：可用 --fix 自动修复 BOM 与换行符。")

    print()
    print(f"{_ICON_OK} 编码检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
