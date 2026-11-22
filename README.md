# Open Compares

Search once and see the results from many shopping apps side by side, on your own phone.

Open Compares is an Android app that opens several marketplaces at once, such as Blinkit, Zepto, Instamart, Amazon and Flipkart, each in its own pane. One search runs on all of them. Every pane is a real browser session on your phone, so you can log in to each site and see the prices, offers and delivery times you would get in that site's own app, for your own location.

## Features

- **One search, every marketplace.** Pick which sites to show and search them all together.
- **Live, personal prices.** Each pane keeps its own login, cart and location.
- **Flexible layout.** Use one or two columns, adjust pane height and zoom, or open any pane fullscreen.
- **Custom marketplaces.** Add any site that has a search URL.
- **Optional ChatGPT sign-in (in development).** Sign in with ChatGPT to use your own ChatGPT plan for AI features. AI requests run from your phone, for your own searches, and Open Compares never sees your ChatGPT password.

### Built-in marketplaces

Quick commerce: Instamart, Blinkit, Zepto, Amazon Now, Flipkart Minutes, JioMart.

Marketplaces and more: Amazon.in, Flipkart, Meesho, Myntra, Nykaa, Swiggy, Zomato, Croma, Reliance Digital, Ajio, BigBasket, Shoppers Stop, Google Shopping, eBay.

## Building

Requirements:

- Android Studio, or the Android SDK with platform 36
- JDK 17 or 21. Gradle 8.13 does not run on Java 25, which newer Android Studio versions bundle. Set Android Studio's Gradle JDK to 17 or 21, or set `JAVA_HOME` when building from the command line.

Build and install a debug build:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds install as **OC Debug** (`com.opencompares.android.debug`), next to any release copy.

Run the unit tests:

```
./gradlew testDebugUnitTest
```

## Project layout

| Path | What it holds |
|---|---|
| `app/src/main/java/com/opencompares/android/` | Screen state, marketplace list, and WebView pane handling |
| `.../ui/` | Compose screens: the compare grid, panes, dialogs |
| `.../data/` | Saved settings, stored with DataStore |
| `.../chatgpt/` | Sign in with ChatGPT: OAuth, token storage, and model calls |

## Privacy

- Marketplace logins, cookies and carts stay inside the app's browser panes on your phone.
- Location is used only to show your area and is passed only to the marketplace pages you open.
- If you sign in with ChatGPT, the tokens are encrypted on your phone and are never sent anywhere except OpenAI. AI requests use your own ChatGPT plan and count toward its limits. You can sign out at any time, which also ends the session at OpenAI.

## Contributing

Issues and pull requests are welcome. By contributing, you agree that your contribution is licensed under the GPL 3.0.

## License

Open Compares is free software, released under the [GNU General Public License v3.0](LICENSE). You may use, study, share and modify it. If you distribute a modified version, you must release its source under the same license.

Open Compares is an independent project. It is not affiliated with or endorsed by OpenAI or any of the marketplaces it displays. All trademarks belong to their owners.
