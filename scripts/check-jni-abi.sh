#!/usr/bin/env bash
#
# check-jni-abi.sh: verify that the Kotlin `external fun` declarations and the
# Rust `Java_*` exports agree on name, arity, and parameter types.
#
# Why this exists
# ---------------
# JNI resolves a native method by its SHORT name (`Java_pkg_Cls_method`), which
# encodes no argument types at all. Long, type-mangled names are consulted only
# for overloaded natives. That produces two very different failure modes:
#
#   * Wrong symbol NAME       -> UnsatisfiedLinkError. Loud, safe, obvious.
#   * Right name, wrong TYPES -> the VM links it and calls it. No error.
#
# In the second case arguments are marshalled according to the Kotlin
# declaration and read according to the Rust signature. On arm64 a dropped
# parameter shifts every later argument by one register, and a Kotlin `Int` read
# as `jlong` picks up garbage in the high 32 bits. For a function taking a
# `jlong` pointer handle that means `Box::from_raw` on a corrupted pointer:
# silent memory corruption rather than an exception.
#
# Nothing else in the toolchain catches this. Android Studio checks signatures
# only for implicitly-bound methods in an IDE session, and clippy cannot see the
# Kotlin side at all.
#
# What it checks
# --------------
#   1. Every Kotlin `external fun` has a matching Rust export, and vice versa.
#   2. Arity agrees, after dropping the Rust `JNIEnv` + `JClass` prefix.
#   3. Each parameter's Kotlin type maps to the Rust type in that position.
#
# What it does NOT check: return types, and object types beyond "is an object"
# (a `JniAccountUsk` passed where a `JniBlockMeta` is expected is not caught,
# because both are `JObject` at the JNI level).
#
# Usage:
#   ./scripts/check-jni-abi.sh          # report and exit non-zero on mismatch
#   ./scripts/check-jni-abi.sh --list   # print the parsed table, then check

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

RUST_SRC="backend-lib/src/main/rust"
KOTLIN_SRC="backend-lib/src/main/java"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

RUST_TABLE="${WORK_DIR}/rust.tsv"
KOTLIN_TABLE="${WORK_DIR}/kotlin.tsv"

# Shared awk helpers: accumulate a signature across lines by tracking paren
# depth, then split the parameter list on top-level commas. Depth tracking on
# angle brackets means a future generic type containing a comma will not be
# mis-split.
read -r -d '' AWK_COMMON <<'AWK' || true
function split_params(s, out,   i, c, depth, cur, n) {
    n = 0; depth = 0; cur = ""
    for (i = 1; i <= length(s); i++) {
        c = substr(s, i, 1)
        if (c == "<" || c == "(" || c == "[") depth++
        else if (c == ">" || c == ")" || c == "]") depth--
        if (c == "," && depth == 0) { out[++n] = trim(cur); cur = ""; continue }
        cur = cur c
    }
    if (trim(cur) != "") out[++n] = trim(cur)
    return n
}
function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
# Take the type from a "name: Type" parameter. Everything before the first
# colon is the binding (which may be `_`, or `mut env`), so it is discarded.
function param_type(p,   i) {
    i = index(p, ":")
    if (i == 0) return trim(p)
    return trim(substr(p, i + 1))
}
AWK

# --- Rust side -------------------------------------------------------------
# Emits: <symbol> TAB <type,type,...>   with JNIEnv and JClass dropped.
extract_rust() {
    # shellcheck disable=SC2016
    # The awk program is intentionally single-quoted; $0/$1 are awk fields, not
    # shell parameters.
    find "${RUST_SRC}" -name "*.rs" -print0 \
        | xargs -0 awk "${AWK_COMMON}"'
        /pub extern "C" fn Java_/ {
            if (match($0, /Java_[A-Za-z0-9_]+/)) {
                sym = substr($0, RSTART, RLENGTH)
                buf = ""; depth = 0; started = 0
            }
        }
        sym != "" {
            for (i = 1; i <= length($0); i++) {
                c = substr($0, i, 1)
                if (c == "(") {
                    depth++
                    if (depth == 1) { started = 1; continue }
                } else if (c == ")") {
                    depth--
                    if (depth == 0 && started) {
                        n = split_params(buf, parts)
                        types = ""
                        # Drop parts[1] (JNIEnv) and parts[2] (JClass).
                        for (k = 3; k <= n; k++) {
                            t = param_type(parts[k])
                            gsub(/<.*>/, "", t)
                            types = types (types == "" ? "" : ",") trim(t)
                        }
                        printf "%s\t%s\n", sym, types
                        sym = ""
                        break
                    }
                }
                if (started && depth >= 1) buf = buf c
            }
            if (sym != "") buf = buf " "
        }
    ' | sort
}

# --- Kotlin side -----------------------------------------------------------
# Emits: <mangled symbol> TAB <expected rust type,...>
# The JNI short name is Java_ + package with dots replaced by underscores +
# class + method. An underscore in a Kotlin identifier would mangle to _1 and a
# nested class to _00024; neither occurs today, and both are rejected loudly
# below rather than silently mis-mangled.
extract_kotlin() {
    local file pkg cls
    while IFS= read -r file; do
        pkg="$(sed -n 's/^package //p' "${file}" | head -1 | tr -d '\r' | tr '.' '_')"
        cls="$(basename "${file}" .kt)"
        awk "${AWK_COMMON}"'
        function map_type(t) {
            sub(/\?$/, "", t)
            if (t == "Int")       return "jint"
            if (t == "Long")      return "jlong"
            if (t == "Boolean")   return "jboolean"
            if (t == "Byte")      return "jbyte"
            if (t == "Short")     return "jshort"
            if (t == "Float")     return "jfloat"
            if (t == "Double")    return "jdouble"
            if (t == "String")    return "JString"
            if (t == "ByteArray") return "JByteArray"
            if (t ~ /^Array</)    return "JObjectArray"
            return "JObject"
        }
        /external fun / {
            if (match($0, /external fun [a-zA-Z0-9_]+/)) {
                sym = substr($0, RSTART, RLENGTH)
                sub(/external fun /, "", sym)
                buf = ""; depth = 0; started = 0
            }
        }
        sym != "" {
            for (i = 1; i <= length($0); i++) {
                c = substr($0, i, 1)
                if (c == "(") {
                    depth++
                    if (depth == 1) { started = 1; continue }
                } else if (c == ")") {
                    depth--
                    if (depth == 0 && started) {
                        n = split_params(buf, parts)
                        types = ""
                        for (k = 1; k <= n; k++) {
                            t = map_type(param_type(parts[k]))
                            types = types (types == "" ? "" : ",") t
                        }
                        printf "Java_%s_%s_%s\t%s\n", PKG, CLS, sym, types
                        sym = ""
                        break
                    }
                }
                if (started && depth >= 1) buf = buf c
            }
            if (sym != "") buf = buf " "
        }
        ' PKG="${pkg}" CLS="${cls}" "${file}"
    done < <(grep -rl "external fun" "${KOTLIN_SRC}") | sort
}

extract_rust > "${RUST_TABLE}"
extract_kotlin > "${KOTLIN_TABLE}"

if [ "${1:-}" = "--list" ]; then
    echo "== Rust exports =="
    cat "${RUST_TABLE}"
    echo
    echo "== Kotlin declarations (types mapped to their Rust equivalents) =="
    cat "${KOTLIN_TABLE}"
    echo
fi

rust_count=$(wc -l < "${RUST_TABLE}" | tr -d ' ')
kotlin_count=$(wc -l < "${KOTLIN_TABLE}" | tr -d ' ')
errors=0

# A parse that finds nothing is a broken checker, not a passing repo.
if [ "${rust_count}" -eq 0 ] || [ "${kotlin_count}" -eq 0 ]; then
    echo "error: parsed ${rust_count} Rust exports and ${kotlin_count} Kotlin" >&2
    echo "       declarations. Expected both to be non-zero; the parser is" >&2
    echo "       broken or the source layout moved." >&2
    exit 1
fi

# Mangling forms this script does not implement. Fail loudly rather than
# emitting a name that silently does not match.
if cut -f1 "${KOTLIN_TABLE}" | grep -q '_00024'; then
    echo "error: a nested-class declaration needs _00024 mangling, which this" >&2
    echo "       script does not implement." >&2
    errors=$((errors + 1))
fi

# --- 1. symbol sets --------------------------------------------------------
missing_in_rust=$(comm -23 <(cut -f1 "${KOTLIN_TABLE}") <(cut -f1 "${RUST_TABLE}"))
missing_in_kotlin=$(comm -13 <(cut -f1 "${KOTLIN_TABLE}") <(cut -f1 "${RUST_TABLE}"))

if [ -n "${missing_in_rust}" ]; then
    echo "error: declared in Kotlin with no matching Rust export." >&2
    echo "       Calling one of these raises UnsatisfiedLinkError at runtime:" >&2
    printf '%s\n' "${missing_in_rust}" | awk '{ print "         " $0 }' >&2
    errors=$((errors + 1))
fi

if [ -n "${missing_in_kotlin}" ]; then
    echo "error: exported from Rust with no matching Kotlin declaration." >&2
    echo "       Dead code, or a renamed declaration that lost its export:" >&2
    printf '%s\n' "${missing_in_kotlin}" | awk '{ print "         " $0 }' >&2
    errors=$((errors + 1))
fi

# --- 2 and 3. arity and types for the symbols present on both sides --------
while IFS=$'\t' read -r sym kt_types; do
    rs_types=$(awk -F'\t' -v s="${sym}" '$1 == s { print $2; exit }' "${RUST_TABLE}")
    # Absent from the Rust table: already reported above.
    grep -q "^${sym}	" "${RUST_TABLE}" || continue

    if [ "${kt_types}" != "${rs_types}" ]; then
        kt_n=$(awk -v s="${kt_types}" 'BEGIN { print (s == "") ? 0 : split(s, a, ",") }')
        rs_n=$(awk -v s="${rs_types}" 'BEGIN { print (s == "") ? 0 : split(s, a, ",") }')
        if [ "${kt_n}" != "${rs_n}" ]; then
            echo "error: ${sym}: arity mismatch (Kotlin ${kt_n}, Rust ${rs_n})." >&2
            echo "       JNI does NOT validate this. The VM links the symbol and" >&2
            echo "       calls it, marshalling arguments per the Kotlin signature" >&2
            echo "       into a Rust frame expecting a different one." >&2
        else
            echo "error: ${sym}: parameter type mismatch." >&2
        fi
        echo "         Kotlin (mapped): ${kt_types:-<none>}" >&2
        echo "         Rust           : ${rs_types:-<none>}" >&2
        errors=$((errors + 1))
    fi
done < "${KOTLIN_TABLE}"

if [ "${errors}" -gt 0 ]; then
    echo >&2
    echo "check-jni-abi.sh: ${errors} error(s)." >&2
    exit 1
fi

echo "check-jni-abi.sh: ${rust_count} JNI exports agree on name, arity, and types"
