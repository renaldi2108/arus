# Arus

A navigation and state layer for Kotlin, built on Jetpack Navigation 3 and
`ViewModel`. Works in plain Android projects, Compose Multiplatform projects,
and Kotlin Multiplatform projects — one artifact, one codebase.

You get two things:

- **`ArusController<S, E>`** — one immutable state per screen, one-shot effects.
- **Navigation** — typed routes, an injectable `Navigator`, and a host that
  renders them.

Arus ships **no dependency-injection framework**. Controllers are ordinary
classes with constructor parameters, so build them with `viewModel { }`, Koin,
Hilt, or by hand.

## Contents

- [Getting it](#getting-it) · [Requirements](#requirements)
- [Five-minute start](#five-minute-start)
- [Navigation](#navigation) — [results](#results),
  [messages](#talking-to-a-screen-further-back), [dialogs](#dialogs),
  [deep links](#deep-links), [guards](#guards),
  [transitions](#per-route-transitions), [bottom sheets](#bottom-sheets),
  [bottom navigation](#bottom-navigation)
- [Testing](#testing)
- [License](#license)

---

## Getting it

With a version catalog:

```toml
# gradle/libs.versions.toml
[versions]
arus = "0.1.0"

[libraries]
arus = { module = "io.github.renaldi2108:arus", version.ref = "arus" }
arus-testing = { module = "io.github.renaldi2108:arus-testing", version.ref = "arus" }
```

```kotlin
dependencies {
    implementation(libs.arus)
    testImplementation(libs.arus.testing)
}
```

Or straight in the build file:

```kotlin
dependencies {
    implementation("io.github.renaldi2108:arus:0.1.0")
    testImplementation("io.github.renaldi2108:arus-testing:0.1.0")
}
```

Routes are `@Serializable`, so every module that declares one needs the
serialization plugin:

```kotlin
plugins {
    kotlin("plugin.serialization") version "2.4.21"
}
```

That is all. Everything the examples below call is already on your compile
classpath, because `arus` exposes it transitively: the Compose runtime,
coroutines, `ViewModel` with its savedstate and Compose integrations,
Navigation 3 runtime and UI, `kotlinx-serialization-core`, and Material 3.

Two things it deliberately does not expose, in case your own code wants them:
`kotlinx-coroutines-android` for the main dispatcher, and
`kotlinx-serialization-json`. Declare those yourself if you use them directly.

### Requirements

| | |
|---|---|
| Kotlin | 2.4.21 |
| `minSdk` / `compileSdk` | 24 / 37 |
| JVM target | 17 |
| Jetpack Navigation 3 | 1.2.0 |
| Lifecycle | 2.11.0 |
| Compose Multiplatform | 1.12.2 (Material 3 on its own line, 1.9.0) |
| Android Gradle Plugin | 9.3.1 |
| Artifact size | 164 KB |

Targets built: Android, `iosArm64`, `iosSimulatorArm64`. **iOS compiles but has
never been run** — treat it as unverified.

---

## Five-minute start

### 1. Declare routes

```kotlin
@Serializable data object ListRoute : Route
@Serializable data class DetailRoute(val itemId: Int) : Route
```

Routes are types, not strings. Arguments are fields the compiler checks.

### 2. Write a controller

```kotlin
data class ListState(val items: List<Item> = emptyList(), val loading: Boolean = true)

class ListController(
    private val repository: ItemRepository,
    private val navigator: Navigator,
) : ArusController<ListState, ArusEffect>(ListState()) {

    init {
        launchCatching {
            repository.observeItems().collect { items ->
                setState { copy(items = items, loading = false) }
            }
        }
    }

    fun onItemClicked(item: Item) = navigator.push(DetailRoute(item.id))

    override fun onError(error: Throwable) {
        setState { copy(loading = false) }
        emit(ArusEffect.Snackbar(error.message ?: "Something went wrong"))
    }
}
```

No Context, no Activity, no Compose import — so it is unit testable on the JVM.

#### Values a controller can hold

```kotlin
class SearchController(
    handle: SavedStateHandle,
) : ArusController<SearchState, ArusEffect>(SearchState(), savedStateHandle = handle) {

    // 1. Survives process death. For what the user chose and nothing can
    //    reconstruct: a query, a selected tab, a scroll anchor.
    private var query: String by savedState("query", "")

    // 2. Lives as long as the screen. For a value nothing derives from and
    //    that changes on every keystroke — in the state it would allocate a
    //    new state object per character. Read it with `draft.watch()`.
    val draft: Ars<String> = ars("")        // or "".ars()
    var page: Int by 1.ars()                // as a delegate

    // 3. The screen's state. One immutable value, replaced atomically.
    fun onQueryChanged(text: String) {
        query = text
        draft.value = text
        setState { copy(query = text) }
    }
}
```

Supply the handle where the screen is declared, so it is the navigation
entry's own saved state rather than the Activity's:

```kotlin
screen<SearchRoute> {
    SearchScreen(viewModel { SearchController(createSavedStateHandle()) })
}
```

`derived(scope) { }` maps one `StateFlow` into another when a value is
computed from something outside the controller's state.

### 3. Declare screens and render them

```kotlin
ArusHost(start = ListRoute) {
    screen<ListRoute> {
        val controller = viewModel { ListController(repository, navigator) }
        ListScreen(controller)
    }
    screen<DetailRoute> { route ->
        val controller = viewModel { DetailController(route.itemId, repository, navigator) }
        DetailScreen(controller)
    }
}
```

That is the whole wiring. A `screen<R>` declaration binds the composable **and**
registers the route for saved state, so there is no second list of routes to
keep in step.

### 4. Observe state and effects

```kotlin
@Composable
fun ListScreen(controller: ListController, snackbarHostState: SnackbarHostState) {
    // `select` recomposes only when the slice it reads changes.
    // `controller.collectState()` observes the whole state object instead.
    val items by controller.select { it.items }

    CollectEffects(controller) { effect ->
        when (effect) {
            is ArusEffect.Snackbar -> snackbarHostState.showSnackbar(effect.message)
            is ArusEffect.Dialog -> Unit
        }
    }

    LazyColumn { items(items) { ItemRow(it) { controller.onItemClicked(it) } } }
}
```

---

## Navigation

| Call | Effect on the back stack |
|---|---|
| `push(route)` | grows by one |
| `pop()` | removes the top; `false` at the root, so the platform finishes the Activity |
| `popTo(route, inclusive)` | removes down to a destination |
| `popToRoot()` | removes everything above the first entry |
| `replace(route)` | swaps the top, leaving the rest |
| `resetTo(route)` | clears everything and starts again |
| `currentRoute`, `canPop` | plain `StateFlow`s, for app chrome |

### Results

```kotlin
when (val result = navigator.pushForResult(PickCountryRoute(current))) {
    is NavResult.Completed -> setState { copy(country = result.value) }
    NavResult.Cancelled -> Unit
}

// in the picker
fun onChosen(code: String) = navigator.popWithResult(code)
```

`ResultRoute<R>` makes the handoff checked. A request does not survive process
death — the coroutine awaiting it dies with the process. See the KDoc.

### Talking to a screen further back

`pushForResult` answers the screen that asked, and only that one. To tell a
screen several steps behind that something happened:

```kotlin
// five screens deep
navigator.publish(OrderPlaced(itemId))

// in a controller near the root
init {
    launchCatching {
        navigator.messages<OrderPlaced>().collect { setState { copy(orders = orders + 1) } }
    }
}
```

The listener is a controller, not a composable, and a controller stays alive
while its entry is on the stack — so the message arrives even though that
screen is nowhere near the display, and without the user navigating back.

No addressing, no replay, and not carried across process death. **Reach for a
repository both screens observe first**: if what you are sending is domain
data, a message is the wrong tool.

### Dialogs

```kotlin
dialog<ConfirmRoute> { route -> ConfirmDialog(viewModel { ... }) }
```

Floats over what is below but is otherwise an ordinary entry: its own scoped
controller, survives rotation and process death, `pop()` dismisses it.

### Deep links

```kotlin
val links = ArusDeepLinks {
    host("shop.example.com")
    path("catalog/item/{id}") { args -> listOf(ListRoute, DetailRoute(args.int("id"))) }
}

navigator.openStack(links.resolve(uri))
```

One set of patterns serves both `https://shop.example.com/catalog/item/3` and
`arus://catalog/item/3`: a **declared** host is recognised and dropped, and
anything else stays a path segment — so a link from a domain you did not
declare does not resolve at all.

Query parameters are readable by name, percent escapes are decoded, and
fragments are ignored. A link produces a **synthetic back stack**, so back from
a linked screen goes to its parent rather than out of the app.

### Guards

```kotlin
class CheckoutGuard(private val hasAddress: () -> Boolean) : NavGuard {
    override fun check(from: Route, to: Route): GuardDecision = when {
        to !is ConfirmRoute -> GuardDecision.Allow
        hasAddress()        -> GuardDecision.Allow
        else                -> GuardDecision.Redirect(AddressRoute(to.itemId))
    }
}

ArusHost(start = ListRoute, guards = listOf(CheckoutGuard { hasAddress() }))
```

Consulted before every forward navigation, and installed on the host rather
than written inside a controller — so the rule holds however the destination is
reached, including a deep link. Backward navigation is exempt.

### Per-route transitions

```kotlin
screen<SettingsRoute>(
    transition = { slideInVertically { it } + fadeIn() togetherWith fadeOut() },
) { SettingsScreen(...) }
```

### Bottom sheets

```kotlin
bottomSheetDialog<SortRoute>(
    BottomSheetProperties(skipPartiallyExpanded = true, showDragHandle = false),
) { route ->
    SortSheet(viewModel { SortController(route.current, navigator) })
}
```

Built in, like `dialog`. Every `BottomSheetProperties` field defaults to the
Material 3 default, so you override only what you name.

The destination is an ordinary back-stack entry: its own scoped controller,
restored after process death, `pop()` dismisses it, and it can hand a typed
value back. The screen underneath stays composed, so dismissing does not reset
it — including when the sheet is opened from inside a tab.

### Anything else

For a presentation Arus does not ship — a two-pane layout, a side sheet —
supply the strategy yourself:

```kotlin
ArusHost(start = ListRoute, sceneStrategies = listOf(TwoPaneSceneStrategy())) {
    scene<DetailRoute>(TwoPaneSceneStrategy.detailPane()) { route -> DetailPane(route) }
}
```

### When chrome outside the host needs the navigator

`ArusHost(start = …) { }` is the short form and hides the graph. Hoist it when
something *outside* the host observes navigation — a top bar showing the
current destination, or an inspector:

```kotlin
val graph = rememberArusGraph(start = ListRoute) {
    catalogScreens(repository)
    checkoutScreens()
}

val current by graph.navigator.currentRoute.collectAsStateWithLifecycle()
val canPop by graph.navigator.canPop.collectAsStateWithLifecycle()

Scaffold(topBar = { TopAppBar(title = { Text(current::class.simpleName.orEmpty()) }) }) {
    ArusHost(graph, Modifier.padding(it))
}
```

`currentRoute` and `canPop` are plain `StateFlow`s, so chrome observes
navigation without importing anything from Compose, and `canPop` is false
exactly when `pop()` would do nothing — the arrow is never a dead control.

### Debugging navigation

```kotlin
graph.inspector = NavInspector()
// ...
graph.inspector?.events?.forEach(::println)
// push(DetailRoute(itemId=7)) -> [ListRoute, DetailRoute]
```

Bounded, off by default, and free when nothing is attached.

### Bottom navigation

```kotlin
val tabs = rememberArusTabs(
    ArusTab(id = "browse", start = ListRoute),
    ArusTab(id = "settings", start = SettingsRoute),
) {
    browseScreens(repository)
    settingsScreens()
}

ArusTabHost(tabs)
```

Each tab keeps its own history **and its own screen state**, so switching away
and back returns the user exactly where they were — same scroll position, same
text in the search box, same controller. Screens are declared once and shared;
a tab owns a stack, not a set of destinations.

#### Destinations that should not have the bar

Two different things, so Arus has two answers:

```kotlin
// 1. Still in the tab, bar hidden. Back returns into Settings.
screen<ProfileEditorRoute>(hidesTabBar = true) { ProfileEditor(...) }

bottomBar = { if (tabs.tabBarVisible()) NavigationBar { ... } }
```

```kotlin
// 2. Out of the tabs entirely. Back returns past the whole dashboard.
ArusHost(start = DashboardRoute) {
    screen<DashboardRoute> {
        val outer = navigator
        val tabs = rememberArusTabs(browse, settings) {
            screen<SettingsRoute> { SettingsScreen(onLicences = { outer.push(LicencesRoute) }) }
        }
        Scaffold(bottomBar = { ... }) { ArusTabHost(tabs) }
    }
    screen<LicencesRoute> { Licences() }
}
```

Pick by where back should land. The second needs no extra API — the tab
declarations close over the outer navigator.

---

## Testing

```kotlin
@Test
fun openingAnItemNavigatesToDetail() = runTest {
    runControllerTest {
        val navigator = FakeNavigator(ListRoute)
        val controller = ListController(FakeRepository(), navigator)

        controller.onItemClicked(Item(id = 7))

        assertEquals(listOf(ListRoute, DetailRoute(7)), navigator.backStack)
    }
}
```

`FakeNavigator` is held to the real implementation by a contract test, so a test
that passes against the fake is not passing against a simplification.

### Waiting for state and effects

```kotlin
val effects = controller.awaitEffects(count = 2) { controller.load() }
val settled = controller.awaitState { !it.loading }
```

Both time out rather than hanging, so a test asserting on something that never
arrives fails in a second instead of blocking the build.

### One screen, composed

```kotlin
@Test
fun tappingTheRowOpensTheDetail() = runComposeUiTest {
    val navigator = FakeNavigator(ListRoute)

    setArusContent(navigator) { ListScreen(ListController(FakeRepository(), navigator)) }
    onNodeWithText("Item 7").performClick()

    assertEquals(listOf(ListRoute, DetailRoute(7)), navigator.backStack)
}
```

Publishes the navigator for the screen to find, without standing up a host.
There is no back stack and no entry scoping — that is the point; it isolates
one screen.

### Process death, without killing a process

```kotlin
@Test
fun theBackStackSurvivesProcessDeath() = runComposeUiTest {
    val host = setArusHost(start = ListRoute) {
        screen<ListRoute> { ListScreen() }
        screen<DetailRoute> { route -> DetailScreen(route.itemId) }
    }

    host.navigator.push(DetailRoute(7))
    waitForIdle()

    host.simulateProcessDeath()

    assertEquals(DetailRoute(7), host.navigator.currentRoute.value)
}
```

Genuine save and restore: routes go through the same serialization the platform
uses, so a destination with an argument the platform cannot write fails here
exactly as it would on a phone.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE). The published POM declares the
same license, so a dependency scanner reads it straight from Maven Central
without visiting this page.
