# open-escpos-print-service

This is an Android app that provides a [PrintService](https://developer.android.com/reference/android/printservice/PrintService) for label and receipt printers.

It supports ESC/POS printers like the Netum G5 or MTP-II.
It also supports Citizen printers using the CPCL protocol.

You can connect printers through Bluetooth or a TCP socket.

## How
 * Install the app;
 * enable Bluetooth;
 * pair your Bluetooth printers with your phone;
 * open the app;
 * the Bluetooth printers should be there, configure the paper size;
 * for TCP printers, just add an address and a port separated by a ":" and configure the paper size;
 * you should now be able to print from any Android app (Chromium, image gallery, etc...).

## Using through an intent
If you need to print from an app that does not support printing, or without going through the Android printer selection screen,
you can send an intent to this app with the following format:

 * `scheme`: `print-intent`
 * `S.content`: a base64 encoded gzipped JSON array of strings, each string is an HTML document.

It looks like this:
`intent://#Intent;scheme=print-intent;S.content=H4sIAAAAAAAA...XXXXX;end`

Sample code for generating the intent url:
```typescript
import * as base64 from "js-base64";
import * as pako from "pako";

const pages = [
  "<html><body>page 1</body></html>",
  "<html><body>page 2</body></html>",
];

function buildIntentUrl(data: Record<string, string>) {
  const content = Object.entries({
    scheme: "print-intent",
    ...data,
  }).map(([key, value]) => [key, encodeURIComponent(value)].join("="));
  return `intent://${["#Intent", ...content, "end"].join(";")}`;
}

const intentUrl = buildIntentUrl({
  "S.content": base64.fromUint8Array(pako.gzip(JSON.stringify(pages))),
});
```

You might want to add some inline css in your html to reset the margins:

```css
@page {
  margin: 0mm 0mm 0mm 0mm;
}

@media all {
  body {
    width: 100%;
    margin: 0;
  }
}
```

## Using via `ACTION_SEND` or `ACTION_SEND_MULTIPLE`

You can share images to this app, they will be printed on the default printer.

## Details
If you have an ESC/POS __label__ printer, enable the `Cut after each page` switch, this will make the printer go to the start of the next label (at least on the Netum ones).
There are speed limits and delays that you can set in each printer settings; if your printer works well, leave these at 0.

## TODO
Some things are not implemented yet:
 * a document print queue;
 * discovery of network printers through mDNS.

## Linter
Please lint with `./gradlew lintKotlin` and format code with `./gradlew formatKotlin` before commiting.
