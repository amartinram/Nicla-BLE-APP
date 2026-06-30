This repository contains the code for building an android app that receives steps from an Arduino Nicla Sense Me and posts to a web server.

Pre-requisites

  1. An Android phone with the app installed.
  2. An Arduino Nicla Sense Me with the code in this repository (https://github.com/amartinram/Nicla-Step-Counter/tree/testLowLevel) flashed.


Functionality

The app can be installed by building the code in this repository or by downloading the app-debug.apk that can be found also in this repository.
Note: Check if the last commit of the app-debug.apk matches the last commit of code to be on pair with the latest fixes.

Once the app is installed, you will have to accept the permits that the app asks you and disable battery optimization and kill in background, this
will prevent Android from killing the app. After that the app, will ask you to choose the URL of the server where you will see the data and the MAC
address of the Arduino you are receiving the data from.
Note: A MAC address scanner will be added later in order to centralize everything and not having to install another app to check the address.

If you dont have a web server that parses the bytes received from the Arduino you can test the App and the Arduino with the google script below.

Once the google server is running you can click connect in the App and two spreadsheets will appear, one will show you the battery remaining in the Arduino (it dies at 70%)
and the other will show you a csv with the steps taken every minute.

GOOGLE SCRIPT

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
  
