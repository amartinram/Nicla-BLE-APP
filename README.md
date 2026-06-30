# Nicla BLE App

An Android app that receives step data from an **Arduino Nicla Sense ME** over Bluetooth Low Energy and posts it to a web server.

> This is the Android half of a two-part project. The Nicla firmware lives here: **[Nicla-Step-Counter](https://github.com/amartinram/Nicla-Step-Counter/tree/testLowLevel)**

## Prerequisites

- An Android phone with this app installed.
- An Arduino Nicla Sense ME flashed with the [Nicla-Step-Counter firmware](https://github.com/amartinram/Nicla-Step-Counter/tree/testLowLevel).

## Installation

You can get the app in one of two ways:

- **Build from source** using the code in this repository, or
- **Download the prebuilt APK** (`app-debug.apk`) included in the repo.

> If you use the prebuilt APK, check that its last commit matches the last commit of the source code, so you're running the latest fixes.

## Setup

1. Install the app and grant all requested permissions.
2. Disable **battery optimization** and **kill in background** for the app — this stops Android from terminating it.
3. When prompted, enter:
   - The **URL** of the server where the data will be sent.
   - The **MAC address** of the Arduino you're receiving data from.

> 📡 A built-in MAC address scanner is planned, so you won't need a separate app to find the device address.

## Testing without your own server

If you don't have a web server set up to parse the bytes from the Arduino, you can test the full app + Arduino flow using the Google Apps Script below.

Once the script is deployed and running, tap **Connect** in the app. Two sheets will be created:

- **Battery** — the Arduino's remaining battery (note: it dies at 70%).
- **Steps** — a CSV of the steps taken each minute.

### Setting up the Google Apps Script

1. Create a Google Sheet and copy its **Spreadsheet ID** (the long string in the sheet's URL).
2. In the sheet, open **Extensions → Apps Script** and paste the code below.
3. Set `TARGET_SPREADSHEET_ID` to the ID you copied.
4. Deploy via **Deploy → New deployment → Web app**, then use the resulting URL as the server URL in the app.

```javascript
// Paste your Spreadsheet ID right here inside the quotes
// This ID can be obtained by creating a google sheet and getting the ID of the sheet
var TARGET_SPREADSHEET_ID = "";

function doPost(e) {
  try {
    var doc = SpreadsheetApp.openById(TARGET_SPREADSHEET_ID);

    var incomingSheetName = e.parameter.sheetName;
    var totalSteps = e.parameter.steps;
    var csvLog = "'" + e.parameter.logData;
    var captureTime = e.parameter.captureTime;
    var timestamp = captureTime ? new Date(Number(captureTime)) : new Date();

    if (!incomingSheetName) {
        incomingSheetName = "Unknown_Device";
    }

    var sheet = doc.getSheetByName(incomingSheetName);

    if (!sheet) {
      sheet = doc.insertSheet(incomingSheetName);
      sheet.appendRow(["Timestamp", "Total Steps", "Minute Log (CSV)"]);
      sheet.getRange("A1:C1").setFontWeight("bold");
    }

    sheet.appendRow([timestamp, totalSteps, csvLog]);

    return ContentService.createTextOutput("Success: Written to " + incomingSheetName);

  } catch(error) {
    return ContentService.createTextOutput("Error: " + error.toString());
  }
}
```
