package dev.s7a.strata.runtime.web

import org.w3c.dom.HTMLElement

/**
 * Installs one host-owned stylesheet; callers remove the returned node when their host closes.
 */
internal fun installWebTheme(
    root: HTMLElement,
    theme: WebTheme,
): HTMLElement? {
    if (theme == WebTheme.Native) return null
    val document = checkNotNull(root.ownerDocument)
    val style = document.createElement("style") as HTMLElement
    style.setAttribute("data-strata-styles", theme.token)
    style.textContent = webThemeStyles(theme)
    checkNotNull(document.head).appendChild(style)
    return style
}

/**
 * Returns deterministic theme rules for inclusion in standalone initial documents.
 */
internal fun webThemeStyles(theme: WebTheme): String =
    when (theme) {
        WebTheme.Native -> {
            ""
        }

        WebTheme.Minecraft -> {
            """
            [data-strata-theme-root="minecraft"] {
             background-color: #292522;
             background-image: repeating-linear-gradient(0deg, #ffffff04 0 2px, transparent 2px 4px), repeating-linear-gradient(90deg, #00000018 0 2px, transparent 2px 4px);
             color: #fff;
            }
            [data-strata-theme="minecraft"] { font: ${theme.font}; color: #fff; text-shadow: 2px 2px #303030; }
            button[data-strata-theme="minecraft"] {
             appearance: none; border: 2px solid #111; border-radius: 0; padding: 0 6px;
             background: repeating-linear-gradient(0deg, #ffffff06 0 1px, #00000006 1px 2px), #737373;
             box-shadow: inset 2px 2px #aaa, inset -2px -2px #383838;
             cursor: pointer;
            }
            button[data-strata-theme="minecraft"]:hover:not(:disabled), button[data-strata-theme="minecraft"]:focus-visible {
             border-color: #fff; outline: none; background-color: #818181;
            }
            button[data-strata-theme="minecraft"]:active:not(:disabled) { background-color: #5c5c5c; box-shadow: inset 2px 2px #333, inset -2px -2px #999; }
            button[data-strata-theme="minecraft"]:disabled { cursor: default; background: #414141; color: #a0a0a0; text-shadow: 1px 1px #202020; box-shadow: inset 2px 2px #555, inset -2px -2px #292929; }
            progress[data-strata-theme="minecraft"] { appearance: none; border: 2px solid #111; border-radius: 0; background: #282828; box-shadow: inset 1px 1px #101010; }
            progress[data-strata-theme="minecraft"]::-webkit-progress-bar { background: #282828; }
            progress[data-strata-theme="minecraft"]::-webkit-progress-value { background: #80b543; box-shadow: inset 0 2px #b4e377, inset 0 -2px #446421; }
            progress[data-strata-theme="minecraft"]::-moz-progress-bar { background: #80b543; box-shadow: inset 0 2px #b4e377, inset 0 -2px #446421; }
            """.trimIndent()
        }
    }
