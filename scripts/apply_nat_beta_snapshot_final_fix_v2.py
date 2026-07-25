#!/usr/bin/env python3
"""Run the final NAT/Beta patch with a safe API-function edit order."""

import apply_nat_beta_snapshot_final_fix as base


_original_patch_native_api = base.patch_native_api
_original_remove_function = base.remove_function


def _patch_native_api_in_safe_order(text: str) -> str:
    # The v1 remover intentionally consumed trailing whitespace, including the
    # indentation before betaInfo(). Let the original function patch Beta first,
    # then remove cancelNat after no more indented API functions need locating.
    saved_remove = base.remove_function
    base.remove_function = lambda current, signature: current
    try:
        updated = _original_patch_native_api(text)
    finally:
        base.remove_function = saved_remove
    return _original_remove_function(updated, "    suspend fun cancelNat(")


def apply() -> None:
    saved = base.patch_native_api
    base.patch_native_api = _patch_native_api_in_safe_order
    try:
        base.apply()
    finally:
        base.patch_native_api = saved


if __name__ == "__main__":
    apply()
