#!/bin/bash
set -e

test "$THIRDPARTY_LIBRARIES" || (echo "\$THIRDPARTY_LIBRARIES is not set!" && exit 1)

apply_patch() {
  local dir="$1"
  local input="$2"

  if patch \
      --directory="$dir" \
      --strip=1 \
      --forward \
      --dry-run \
      --input="$input" >/dev/null 2>&1; then
    echo "Applying $(basename "$input")"
    patch \
        --directory="$dir" \
        --strip=1 \
        --forward \
        --input="$input"
  else
    echo "Skipping $(basename "$input") (already applied or not applicable)"
  fi
}

apply_patch \
    "${THIRDPARTY_LIBRARIES}/rlottie" \
    "${THIRDPARTY_LIBRARIES}/rlottie-patches/0001-Rewrite-pixman-NEON-asm-for-clang-s-integrated-assembler.patch"

apply_patch \
    "${THIRDPARTY_LIBRARIES}/opusfile" \
    "${THIRDPARTY_LIBRARIES}/opusfile-patches/0001-Add-fseeko-ftello-fallback-for-bionic-below-API-level-24.patch"

echo "rlottie and opusfile patches applied successfully!"
