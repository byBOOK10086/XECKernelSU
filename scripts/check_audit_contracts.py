#!/usr/bin/env python3
"""Cheap structural regression guards for the audited privilege/build contracts.

These deliberately constrain security-sensitive source shapes. If a contract is
refactored, update its guard with equivalent negative regression probes; do not
replace a guard with a token-presence check. Run directly or via check_quality.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KOTLIN = "manager/app/src/main/java/me/weishu/kernelsu/ui/util/KsuCli.kt"
CPP = "manager/app/src/main/cpp"
# Preserve literals while removing comments: comments must never satisfy a guard.
LEXEMES = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/', re.S)


def code(text):
    return LEXEMES.sub(lambda m: " " if m[0].startswith(("//", "/*")) else m[0], text)


def block(text, marker):
    """Return a brace-balanced body, ignoring braces inside quoted literals."""
    match = re.search(marker, text)
    if not match:
        raise ValueError("missing declaration: " + marker)
    start = text.find("{", match.end())
    if start < 0:
        raise ValueError("missing body: " + marker)
    depth = 0
    # Mask strings without changing offsets (Kotlin string templates included).
    masked = LEXEMES.sub(lambda m: " " * len(m[0]), text)
    for i in range(start, len(masked)):
        if masked[i] == "{":
            depth += 1
        elif masked[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    raise ValueError("unclosed body: " + marker)


def check_contracts(root=ROOT):
    problems = []

    def read(name):
        return code((root / name).read_text(encoding="utf-8"))

    def require(ok, message):
        if not ok:
            problems.append(message)

    def matches(pattern, text):
        return re.search(pattern, text, re.S) is not None

    try:
        cli = read(KOTLIN)
        install = block(cli, r"\bfun installBoot\s*\(")
        # The flash flag belongs only to the null-URI branch. A selected image
        # must use -b, and invalid input must leave through the failure result.
        command = r'var\s+cmd\s*=\s*if\s*\(bootUri\s*==\s*null\)\s*"boot-patch -f"\s*else\s*"boot-patch -b \$\{shQuote\(requireNotNull\(image\)\.path\)\}"'
        require(matches(command, install), "installBoot: only bootUri == null may select boot-patch -f")
        literals = re.findall(r'"(?:\\.|[^"\\])*"', install)
        flash_literals = [s for s in literals if re.search(r'(?:^|\s)(?:-f|--flash)(?:\s|"|$)', s)]
        require(flash_literals == ['"boot-patch -f"'], "installBoot: unexpected additional flash flag")
        require(matches(r'require\(looksLikeFlashableImage\(it\)\)', install),
                "installBoot: selected images must pass the magic guard")
        require(matches(r'catch\s*\([^)]*Exception\)\s*\{[^}]*FlashResult\(-1,\s*message,\s*false\)', install),
                "installBoot: invalid input must fail without flashing/reboot")

        magic = block(cli, r"\bfun looksLikeFlashableImage\s*\(")
        returns = re.findall(r'\breturn\s+([^\n]+)', magic)
        require(returns == ['false', 'false', 'magic == "ANDROID!" || magic == "VNDRBOOT"'],
                "image magic: accept only ANDROID! or VNDRBOOT, with no OTA/archive bypass")
        require(matches(r'ByteArray\(8\)', magic) and matches(r'if\s*\(read\s*<\s*8\)\s*return false', magic),
                "image magic: require a complete eight-byte header")

        getter = block(cli, r"\bfun getRootShell\s*\(")
        require(matches(r'^\s*return if \(globalMnt\) KsuCli\.GLOBAL_MNT_SHELL else\s*\{\s*KsuCli\.SHELL\s*\}\s*$', getter),
                "getRootShell: must return only the privileged getters")
        for name, arg in (("SHELL", "false"), ("GLOBAL_MNT_SHELL", "true")):
            require(matches(r'val\s+' + name + r':\s*Shell\s+get\(\)\s*=\s*requireRoot\(' + arg + r'\)', cli),
                    name + ": must use requireRoot, never plainShell")
        root_body = block(cli, r"\bfun requireRoot\s*\(")
        require(not matches(r'plainShell|Shell\.Builder|\.build\(', root_body),
                "requireRoot: no unprivileged shell fallback")
        require(matches(r'isAlive\s*&&\s*it\.isRoot', root_body) and
                matches(r'return probeOnce\(slot, globalMnt\)\s*\?:\s*throw NoRootException\(\)', root_body),
                "requireRoot: verify cached root and throw when acquisition fails")

        creator = block(cli, r"\bfun createRootShell\s*\(")
        require(matches(r'probe\.code\s*==\s*0\s*&&\s*uid\s*==\s*"0"', creator)
                and matches(r'throw NoRootException\(', creator)
                and not matches(r'plainShell|build\("sh"\)|return\s+Shell\.', creator),
                "createRootShell: require uid 0 and fail without a plain-shell fallback")

        cmake = re.sub(r'(?m)#.*$', '', read(CPP + "/CMakeLists.txt"))
        require(matches(r'get_filename_component\(KSU_REPO_ROOT\s+"\$\{CMAKE_CURRENT_SOURCE_DIR\}/\.\./\.\./\.\./\.\./\.\."\s+ABSOLUTE\)', cmake)
                and matches(r'target_include_directories\(xcnative\s+PRIVATE\s+\.\s+"\$\{KSU_REPO_ROOT\}"\)', cmake),
                "JNI CMake: include canonical repository-root uapi headers")
        private_uapi = root / CPP / "uapi"
        require(not private_uapi.exists() or not any(private_uapi.rglob("*")),
                "JNI: private cpp/uapi copies must not return")
        require((root / "uapi/ksu.h").is_file() and '#include "uapi/ksu.h"' in read(CPP + "/ksu.h"),
                "JNI: use the canonical root uapi/ksu.h")
        native = read(CPP + "/ksu.cc")
        scan = block(native, r'\bscan_driver_fd\s*\(\s*\)')
        require(re.findall(r'"anon_inode:[^"\n]*"', native) == ['"anon_inode:[ksu_driver]"']
                and matches(r'strcmp\(target,\s*kName\)\s*==\s*0', scan),
                "JNI driver scan: match exactly anon_inode:[ksu_driver], no scoped descriptor")
        require(not matches(r'strstr|strncmp|strcasestr', scan),
                "JNI driver scan: no prefix/substring descriptor matching")

        kbuild = re.sub(r'(?m)#.*$', '', read("kernel/Kbuild"))
        require(not matches(r'\b(?:curl|wget)\b|\bgit\s+(?:fetch|pull|clone)\b|https?://', kbuild),
                "Kbuild: no network version fetch")
        require(matches(r'KSU_VERSION_FILE\s*:=\s*\$\(KSU_KERNEL_DIR\)/\.\./version\.properties', kbuild)
                and matches(r'KSU_VERSION_CODE\s*:=\s*\$\(shell\s+sed\b[^\n]*\$\(KSU_VERSION_FILE\)', kbuild)
                and matches(r'ccflags-y\s*\+=\s*-DKSU_VERSION=\$\(KSU_VERSION_CODE\)', kbuild),
                "Kbuild: derive kernel version from shared version.properties")
        assignments = re.findall(r'^\s*KSU_VERSION(?:_CODE)?\s*[:?+]?=\s*(.*)$', kbuild, re.M)
        require(len(assignments) == 1 and not matches(r'\b16\b', '\n'.join(assignments)),
                "Kbuild: no version override or fallback 16")
        require(matches(r'ifeq\s*\(\$\(strip \$\(KSU_VERSION_CODE\)\),\)\s*\$\(error', kbuild)
                and matches(r'ifneq\s*\(\$\(words \$\(KSU_VERSION_CODE\)\),1\)\s*\$\(error', kbuild),
                "Kbuild: missing/ambiguous shared version must fail loudly")

        dispatch = read("kernel/supercall/dispatch.c")
        umount = block(dispatch, r'\badd_try_umount\s*\(')
        for mode in ("ADD", "DEL"):
            case = block(umount, r'case\s+KSU_UMOUNT_' + mode + r'\s*:')
            require(matches(r'long len\s*=\s*strncpy_from_user\(buf,\s*\(const char __user \*\)cmd.arg,\s*sizeof\(buf\)\)', case)
                    and matches(r'if\s*\(len\s*>=\s*\(long\)sizeof\(buf\)\)\s*return -ENAMETOOLONG;', case),
                    "umount " + mode + ": reject truncated user paths with ENAMETOOLONG")
            require(case.find("return -ENAMETOOLONG;") < case.find("down_write("),
                    "umount " + mode + ": validate path before mutating the list")
    except (OSError, ValueError) as exc:
        problems.append("audit source unavailable or structurally changed: " + str(exc))
    return problems


def main():
    problems = check_contracts()
    if problems:
        print("audit contracts failed (%d):" % len(problems))
        for problem in problems:
            print("  - " + problem)
        return 1
    print("audit contracts: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
