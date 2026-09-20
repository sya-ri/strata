package dev.s7a.strata.integration.docs

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole

/**
 * Exercises visible production demo behavior through real browser pointer events.
 * Checks deliberately assert outcomes and native identity instead of repeating declaration implementation.
 */
internal object WebDemoChecks {
    /**
     * Checks counter bounds and reset after both endpoints have been reached.
     */
    fun counter(page: Page) {
        assertThat(button(page, "Decrease")).isDisabled()
        repeat(10) { button(page, "Increase").click() }
        assertThat(page.getByText("Count: 10", Page.GetByTextOptions().setExact(true))).isVisible()
        assertThat(button(page, "Increase")).isDisabled()
        repeat(10) { button(page, "Decrease").click() }
        assertThat(button(page, "Decrease")).isDisabled()
        assertThat(page.getByText("Count: 0", Page.GetByTextOptions().setExact(true))).isVisible()
        button(page, "Increase").click()
        button(page, "Reset").click()
        assertThat(page.getByText("Count: 0", Page.GetByTextOptions().setExact(true))).isVisible()
    }

    /**
     * Checks every progress step, boundary, and reset against the native progress value.
     */
    fun progress(page: Page) {
        assertThat(button(page, "Decrease")).isDisabled()
        repeat(10) { index ->
            button(page, "Increase").click()
            assertThat(page.locator("progress")).hasAttribute("value", ((index + 1) / 10.0).toString().removeSuffix(".0"))
            assertThat(page.getByText("Progress: ${(index + 1) * 10}%", Page.GetByTextOptions().setExact(true))).isVisible()
        }
        assertThat(button(page, "Increase")).isDisabled()
        for (step in 9 downTo 0) {
            button(page, "Decrease").click()
            assertThat(page.locator("progress")).hasAttribute("value", (step / 10.0).toString().removeSuffix(".0"))
            assertThat(page.getByText("Progress: ${step * 10}%", Page.GetByTextOptions().setExact(true))).isVisible()
        }
        assertThat(button(page, "Decrease")).isDisabled()
        button(page, "Increase").click()
        button(page, "Reset").click()
        assertThat(page.locator("progress")).hasAttribute("value", "0")
        assertThat(page.getByText("Progress: 0%", Page.GetByTextOptions().setExact(true))).isVisible()
    }

    /**
     * Checks keyed reordering, bounded additions, empty content, and replacement after removal.
     */
    fun keyedList(page: Page) {
        page.evaluate("window.savedItems = [...document.querySelectorAll('#strata-root span')].filter(node => node.textContent.startsWith('Item '))")
        button(page, "Reverse").click()
        check(page.evaluate("window.savedItems.every(saved => saved === [...document.querySelectorAll('#strata-root span')].find(node => node.textContent === saved.textContent))") == true)
        val labels = page.locator("#strata-root span").allTextContents().filter { it.startsWith("Item ") }
        check(labels == listOf("Item 3", "Item 2", "Item 1")) { "List did not reverse: $labels" }
        repeat(2) { button(page, "Add").click() }
        assertThat(button(page, "Add")).isDisabled()
        button(page, "Remove 1").click()
        check(page.evaluate("window.savedItems.slice(1).every(saved => saved === [...document.querySelectorAll('#strata-root span')].find(node => node.textContent === saved.textContent))") == true)
        for (item in 2..5) button(page, "Remove $item").click()
        assertThat(page.getByText("No items. Add one to begin.", Page.GetByTextOptions().setExact(true))).isVisible()
        assertThat(button(page, "Reverse")).isDisabled()
        button(page, "Add").click()
        assertThat(button(page, "Remove 6")).isVisible()
        button(page, "Reset").click()
        assertThat(button(page, "Remove 1")).isVisible()
        check(page.evaluate("window.savedItems.every(node => node.isConnected === false)") == true)
        assertThat(page.getByText("Items: 3 / 5", Page.GetByTextOptions().setExact(true))).isVisible()
    }

    private fun button(
        page: Page,
        name: String,
    ) = page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(name).setExact(true))
}
