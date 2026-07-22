#!/usr/bin/env python3
from apply_next_release_fixes import apply as apply_next_release
from apply_release_text_fixes import apply as apply_release_texts
from apply_router_ui_fixes import patch_main, patch_router_ui
from apply_wol_navigation_fix import apply as apply_wol_navigation


if __name__ == "__main__":
    patch_main()
    patch_router_ui()
    apply_wol_navigation()
    apply_release_texts()
    apply_next_release()
    print("Android source fixes prepared")
