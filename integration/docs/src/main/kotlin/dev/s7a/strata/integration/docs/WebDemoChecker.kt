package dev.s7a.strata.integration.docs

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies generated production pages before deployment at both current and nested release URLs.
 * Fresh browser contexts isolate each demo; servers, browsers, and contexts are closed on all paths.
 */
internal object WebDemoChecker {
    /**
     * Accepts the generated demo directory and a fresh screenshot evidence directory.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Web demo checking requires generated documents and evidence paths." }
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        val evidence = Path.of(args[1]).toAbsolutePath().normalize()
        Files.createDirectories(evidence)
        Playwright.create().use { playwright ->
            playwright.chromium().launch().use { browser ->
                listOf("/strata/", "/strata/releases/0.0.0/").forEachIndexed { index, prefix ->
                    WebDemoServer(root, prefix).use { server ->
                        checkIndex(browser, server.baseUrl, evidence.resolve("index-$index.png"))
                        checkDemo(browser, server.baseUrl, "counter", evidence.resolve("counter-$index.png"), WebDemoChecks::counter)
                        checkDemo(browser, server.baseUrl, "progress", evidence.resolve("progress-$index.png"), WebDemoChecks::progress)
                        checkDemo(browser, server.baseUrl, "keyed-list", evidence.resolve("keyed-list-$index.png"), WebDemoChecks::keyedList)
                        checkStartupFailure(browser, server.baseUrl)
                    }
                }
            }
        }
    }

    private fun checkIndex(
        browser: Browser,
        base: String,
        screenshot: Path,
    ) {
        browser.newContext().use { context ->
            val page = context.newPage()
            val errors = recordErrors(page)
            page.navigate("${base}demos/")
            assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Open demo"))).hasCount(3)
            page.screenshot(Page.ScreenshotOptions().setPath(screenshot).setFullPage(true))
            page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Open demo")).first().click()
            assertThat(page.locator("#strata-root")).hasAttribute("data-demo-ready", "true")
            check(errors.isEmpty()) { "Demo index errors: $errors" }
        }
    }

    private fun checkDemo(
        browser: Browser,
        base: String,
        slug: String,
        screenshot: Path,
        actions: (Page) -> Unit,
    ) {
        val url = "${base}demos/$slug/"
        browser.newContext(Browser.NewContextOptions().setJavaScriptEnabled(false)).use { context ->
            val page = context.newPage()
            page.navigate(url)
            check(0 < page.locator("#strata-root > *").count()) { "Initial HTML is empty: $url" }
            assertThat(page.locator("#strata-root")).isVisible()
        }
        browser.newContext(Browser.NewContextOptions().setViewportSize(390, 844)).use { context ->
            captureInitialElements(context)
            val page = context.newPage()
            val errors = recordErrors(page)
            page.navigate(url)
            assertThat(page.locator("#strata-root")).hasAttribute("data-demo-ready", "true")
            check(page.evaluate("window.initialDemoElements.length > 0 && window.initialDemoElements.every((node, i) => node === document.querySelector('#strata-root').children[i])") == true) {
                "Startup replaced initial DOM: $url"
            }
            check(page.evaluate("document.documentElement.scrollWidth <= innerWidth") == true) { "Page overflows narrow viewport: $url" }
            page.screenshot(Page.ScreenshotOptions().setPath(screenshot).setFullPage(true))
            val initialText = requireNotNull(page.locator("#strata-root").textContent())
            actions(page)
            page.locator("#strata-root button:enabled").first().click()
            page.reload()
            assertThat(page.locator("#strata-root")).hasAttribute("data-demo-ready", "true")
            assertThat(page.locator("#strata-root")).hasText(initialText)
            assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("API reference"))).hasAttribute("href", "../../index.html")
            page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("All demos")).click()
            assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Open demo"))).hasCount(3)
            check(errors.isEmpty()) { "Demo errors at $url: $errors" }
        }
    }

    private fun checkStartupFailure(
        browser: Browser,
        base: String,
    ) {
        browser.newContext().use { context ->
            context.route("**/counter/") { route ->
                val response = route.fetch()
                route.fulfill(Route.FulfillOptions().setResponse(response).setBody(response.text().replace("Count: 0", "Count: invalid")))
            }
            val page = context.newPage()
            val errors = recordErrors(page)
            page.navigate("${base}demos/counter/")
            assertThat(page.locator("#demo-status")).hasText("Unable to start this demo. Reload to try again.")
            check(page.locator("#strata-root").getAttribute("data-demo-ready") == null)
            assertThat(page.getByText("Count: invalid", Page.GetByTextOptions().setExact(true))).isVisible()
            check(errors.isNotEmpty()) { "A failed startup was not reported to the browser." }
        }
    }

    private fun captureInitialElements(context: BrowserContext) {
        context.route("**/app.js") { route ->
            val response = route.fetch()
            route.fulfill(
                Route.FulfillOptions().setResponse(response).setBody(
                    "window.initialDemoElements = Array.from(document.querySelector('#strata-root').children);\n" + response.text(),
                ),
            )
        }
    }

    private fun recordErrors(page: Page): List<String> {
        val errors = ArrayList<String>()
        page.onPageError { value -> errors.add(value) }
        page.onConsoleMessage { message -> if (WebDemoConsoleLevel.decode(message.type()) == WebDemoConsoleLevel.Error) errors.add(message.text()) }
        page.onRequestFailed { request -> errors.add("${request.url()}: ${request.failure()}") }
        page.onResponse { response -> if (400 <= response.status()) errors.add("${response.status()}: ${response.url()}") }
        return errors
    }
}
