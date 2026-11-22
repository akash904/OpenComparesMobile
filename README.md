# Open Compares

Search once and see the results from many shopping apps side by side, on your own phone.

Open Compares is an Android app that opens several marketplaces at once, such as Blinkit, Zepto, Instamart, Amazon and Flipkart, each in its own pane. One search runs on all of them. Every pane is a real browser session on your phone, so you can log in to each site and see the prices, offers and delivery times you would get in that site's own app, for your own location.

## Features

- **One search, every marketplace.** Pick which sites to show and search them all together.
- **Live, personal prices.** Each pane keeps its own login, cart and location.
- **Flexible layout.** Use one or two columns, adjust pane height and zoom, or open any pane fullscreen.
- **Custom marketplaces.** Add any site that has a search URL.
- **Shopping chat (optional, uses ChatGPT).** The app opens with "What do you want to buy?". Type your list, adjust it by chatting, and the app searches every site for each item in its own pane. It then shows what the whole list costs on each site, which items a site is missing, and the cheapest way to split the order. Delivery and other charges will come from each site's real cart in a later version.
- **Site setup.** A "Your sites" card shows, for each marketplace, whether you're logged in and which address it delivers to, and warns when a site is only guessing your city or is set to another area. "Set up" opens the site with a checklist that ticks off as you log in and pick your address. Logging in is optional: skip any site and it's left out of searches.
- **One delivery location for every site.** Use your phone's location, or type an area or pincode once. When a site asks for your location, it gets that one.
- **Prices on screen.** Read the products, prices, pack sizes and delivery times from every pane into one list, with prices per 100 g, 100 ml or piece. This runs on your phone without AI.
- **Compare with ChatGPT (optional).** Sign in with ChatGPT to use your own ChatGPT plan to find the same product across sites. The app then shows which site has the best value per unit, using the prices it read itself. AI requests run from your phone, for your own searches, and Open Compares never sees your ChatGPT password.

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
| `.../prices/` | Reading prices from the panes, unit prices, and the ChatGPT comparison |
| `.../shopping/` | The shopping chat: list parsing, searching each site, matching and basket totals |
| `app/src/main/assets/price_extractor.js` | The script that finds product cards on any marketplace page |
| `.../chatgpt/` | Sign in with ChatGPT: OAuth, token storage, and model calls |

## Privacy

- Marketplace logins, cookies and carts stay inside the app's browser panes on your phone.
- Location is used only to show your area and is passed only to the marketplace pages you open, when they ask for it. If you type an area instead, Android's geocoder looks it up (on most phones this is a Google service).
- To show whether you're logged in, the app checks only whether a site has a login token; it never reads or stores the token.
- If you sign in with ChatGPT, the tokens are encrypted on your phone and are never sent anywhere except OpenAI. AI requests use your own ChatGPT plan and count toward its limits. A comparison sends only the product names, pack sizes, prices, delivery times and stock shown in the prices list; it never sends page addresses, your location or your marketplace accounts. The shopping chat sends what you type and the same product details for the listings it found. You can sign out at any time, which also ends the session at OpenAI.

## Contributing

Issues and pull requests are welcome. By contributing, you agree that your contribution is licensed under the GPL 3.0.

## License

Open Compares is free software, released under the [GNU General Public License v3.0](LICENSE). You may use, study, share and modify it. If you distribute a modified version, you must release its source under the same license.

Open Compares is an independent project. It is not affiliated with or endorsed by OpenAI or any of the marketplaces it displays. All trademarks belong to their owners.
