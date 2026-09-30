# UI translations

The Compose UI reads `app/src/main/assets/strings.json` as the English fallback
and `app/src/main/assets/locales/<language-tag>/strings.json` for the selected
language. Each catalog uses a flat JSON object of string keys and UTF-8 values.
Keep the English catalog at `locales/en/strings.json` identical to the fallback.
Use numbered printf placeholders such as `%1$d` and preserve their types in
translations. Model names, image filenames and output folder paths retain their
original names.

The language picker is in Settings. Its selection is saved in DataStore and
applied immediately without recreating the Activity or interrupting processing.
System mode follows the current Android configuration; unsupported languages
fall back to English. Chinese script tags distinguish Simplified and Traditional
Chinese, and Portuguese resolves to Brazilian Portuguese. Arabic uses RTL.

Check catalog coverage and placeholders:

```sh
python3 tools/check_i18n.py
```

Check locale resolution:

```sh
./gradlew :app:testDebugUnitTest
```

Android XML resources remain available for platform resources. New Compose UI
text should use `t("key")` and be added to every JSON catalog.
