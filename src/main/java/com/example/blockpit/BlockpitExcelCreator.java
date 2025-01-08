package com.example.blockpit;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jsoup.Jsoup;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class BlockpitExcelCreator {

    private static final String INTEGRATION_NAME = "Robinhood";

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println(
                    "Usage: mvn exec:java -Dexec.mainClass=com.example.blockpit.BlockpitExcelCreator -Dexec.args=\"<input-folder> <output-file.xlsx>\"");
            return;
        }

        String inputFolder = args[0];
        String outputFile = args[1];

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Transactions");
            createHeaderRow(sheet);

            File folder = new File(inputFolder);
            File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".eml"));
            List<RowData> rowDataList = new ArrayList<>(); // List for sorting rows

            if (files != null) {
                for (File file : files) {
                    try (InputStream fileStream = new FileInputStream(file)) {
                        InputStreamReader reader = new InputStreamReader(fileStream, StandardCharsets.UTF_8);
                        StringWriter writer = new StringWriter();

                        // Kopiere den Inhalt in einen String
                        char[] buffer = new char[8192];
                        int len;
                        while ((len = reader.read(buffer)) != -1) {
                            writer.write(buffer, 0, len);
                        }

                        // Konvertiere den String zurück in einen ByteArrayInputStream für MimeMessage
                        InputStream emlStream = new ByteArrayInputStream(
                                writer.toString().getBytes(StandardCharsets.UTF_8));

                        // Erstelle MimeMessage mit UTF-8 kodiertem Stream
                        Properties props = new Properties();
                        Session session = Session.getDefaultInstance(props, null);
                        MimeMessage message = new MimeMessage(session, emlStream);

                        String subject = message.getSubject();
                        String content = htmlToPlainText(getTextFromMessage(message));
                        String sentDate = formatDateToUTC(message.getSentDate());

                        // Debugging-Logs
                        System.out.println("Processing File: " + file.getName());

                        String messageType = determineMessageType(subject, content);
                        if (messageType.equals("Skip")) {
                            System.out.println("Skipped processing for subject: " + subject);
                            continue; // Skip processing for this file
                        }

                        switch (messageType) {
                            case "Gift-Received":
                                parseReceivedContent(rowDataList, content, sentDate, messageType);
                                break;
                            case "Deposit":
                                parseDepositContent(rowDataList, content, sentDate, messageType);
                                break;
                            case "WithdrawalToBank":
                                parseWithdrawalToBankContent(rowDataList, content, sentDate, "Withdrawal");
                                break;
                            case "WithdrawalToWallet":
                                parseWithdrawalToWalletContent(rowDataList, content, sentDate, "Withdrawal");
                                break;
                            case "Trade":
                                parseTradeContent(rowDataList, content, sentDate, messageType);
                                break;
                            case "Staking":
                                parseStakingContent(rowDataList, content, sentDate, messageType);
                                break;
                            default:
                                System.out.println("Unrecognized message type for subject: " + subject);
                        }
                    } catch (Exception e) {
                        System.err.println("Error processing file: " + file.getName() + " - " + e.getMessage());
                    }
                }
            }

            // Sort rows by Date (UTC)
            rowDataList.sort((row1, row2) -> {
                SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");
                try {
                    Date date1 = dateFormat.parse(row1.getDate());
                    Date date2 = dateFormat.parse(row2.getDate());
                    return date1.compareTo(date2); // Sortiere aufsteigend
                } catch (Exception e) {
                    // Falls ein Datum nicht geparst werden kann, werden diese als gleich behandelt
                    System.err.println("Error parsing date: " + e.getMessage());
                    return 0;
                }
            });

            // Write sorted rows to the sheet
            int rowIndex = 1; // Start writing from the second row
            for (RowData rowData : rowDataList) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(rowData.getDate());
                row.createCell(1).setCellValue(rowData.getIntegrationName());
                row.createCell(2).setCellValue(rowData.getLabel());
                row.createCell(3).setCellValue(rowData.getOutgoingAsset());
                row.createCell(4).setCellValue(rowData.getOutgoingAmount());
                row.createCell(5).setCellValue(rowData.getIncomingAsset());
                row.createCell(6).setCellValue(rowData.getIncomingAmount());
                row.createCell(7).setCellValue(rowData.getFeeAsset());
                row.createCell(8).setCellValue(rowData.getFeeAmount());
                row.createCell(9).setCellValue(rowData.getComment());
                row.createCell(10).setCellValue(rowData.getTransactionId());
            }

            // Autofit columns
            for (int i = 0; i < 11; i++) { // Adjust the number of columns as needed
                sheet.autoSizeColumn(i);
            }

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                workbook.write(fos);
            }
            System.out.println("Excel file created: " + outputFile);

        } catch (IOException e) {
            System.err.println("Error writing Excel file: " + e.getMessage());
        }
    }

    private static void createHeaderRow(Sheet sheet) {
        String[] headers = {
                "Date (UTC)", "Integration Name", "Label", "Outgoing Asset", "Outgoing Amount",
                "Incoming Asset", "Incoming Amount", "Fee Asset (optional)", "Fee Amount (optional)",
                "Comment (optional)", "Trx. ID (optional)"
        };
        Row headerRow = sheet.createRow(0);
        CellStyle boldStyle = sheet.getWorkbook().createCellStyle();
        Font boldFont = sheet.getWorkbook().createFont();
        boldFont.setBold(true);
        boldStyle.setFont(boldFont);

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(boldStyle);
        }
    }

    private static String getTextFromMessage(MimeMessage message) throws Exception {
        Object content = message.getContent();

        if (content instanceof String) {
            return new String(((String) content).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        } else if (content instanceof Multipart) {
            Multipart multipart = (Multipart) content;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);

                if (bodyPart.isMimeType("text/plain")) {
                    return decodeQuotedPrintable(bodyPart.getContent().toString(), bodyPart.getContentType());
                } else if (bodyPart.isMimeType("text/html")) {
                    String htmlContent = decodeQuotedPrintable(bodyPart.getContent().toString(),
                            bodyPart.getContentType());
                    return htmlToPlainText(htmlContent);
                }
            }
        }
        return "";
    }

    private static String decodeQuotedPrintable(String content, String contentType) {
        if (contentType.contains("quoted-printable")) {
            try {
                ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
                        content.getBytes(StandardCharsets.ISO_8859_1));
                InputStream decodedStream = javax.mail.internet.MimeUtility.decode(byteArrayInputStream,
                        "quoted-printable");

                StringBuilder decodedContent = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(decodedStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        decodedContent.append(line).append("\n");
                    }
                }
                return decodedContent.toString().trim();
            } catch (Exception e) {
                System.err.println("Error decoding quoted-printable content: " + e.getMessage());
            }
        }
        return content;
    }

    private static String formatDateToUTC(Date date) {
        if (date == null)
            return "";
        SimpleDateFormat utcFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        return utcFormat.format(date);
    }

    private static String htmlToPlainText(String html) {
        return Jsoup.parse(new String(html.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).text().trim();
    }

    private static String determineMessageType(String subject, String content) {
        if (content.contains("You received") && content.contains("signing up")) {
            return "Gift";
        }
        if (content.contains("You received") && content.contains("for holding")) {
            return "Staking";
        }
        if (subject.matches("Your .* order was placed")) {
            return "Skip";
        }
        if (subject.matches("Your .* order was filled")) {
            return "Trade";
        }
        if (content.contains("Your deposit has completed")) {
            return "Deposit";
        }
        if (content.contains("Your withdrawal is in progress")) {
            return "WithdrawalToBank";
        }
        if (subject.matches("Your [A-Za-z]* transfer is on its way")) {
            return "WithdrawalToWallet";
        }
        return "Unknown";
    }

    private static void parseReceivedContent(
            List<RowData> rowDataList, String content, String sentDate, String messageType) {
        // Extrahiere Betrag und Asset
        String[] extractedData = extractDataFromReceivedType(content);
        String amount = extractedData[0];
        String asset = extractedData[1];

        // Erstelle den Kommentar
        String comment = "You received " + amount + " in " + asset + " for signing up";

        // Erstelle Zeilendaten
        RowData rowData = new RowData(sentDate, INTEGRATION_NAME, messageType, "", "", asset, "???", "", "", comment,
                "");
        rowDataList.add(rowData);
    }

    private static String[] extractDataFromReceivedType(String content) {
        // Regex für Betrag und Asset
        String regex = "You (just )?received\\s*(€\\s?[\\d.,]+|[\\d.,]+\\s?€|\\$\\s?[\\d.,]+|[\\d.,]+\\s?\\$)\\s*in\\s*([A-Za-z0-9]+)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String amount = matcher.group(2).trim(); // Betrag inkl. Währung
            String asset = matcher.group(3).trim(); // Kryptowährung
            return new String[] { amount, asset };
        }

        // Fallback, falls kein Treffer
        System.out.println("No relevant content found in: " + content);
        return new String[] { "Unknown", "Unknown" };
    }

    private static void parseStakingContent(
            List<RowData> rowDataList, String content, String sentDate, String messageType) {
        String[] extractedData = extractDataFromStakingType(content); // Extrahiere relevanten Inhalt
        String amount = extractedData[0];
        String asset = extractedData[1];
        String period = extractedData[2];

        String comment = "You received " + amount + " in " + asset + " for holding " + asset + " in " + period;

        RowData rowData = new RowData(sentDate, INTEGRATION_NAME, messageType, "", "", "USDC", "???", "", "",
                comment, "");
        rowDataList.add(rowData);
    }

    private static String[] extractDataFromStakingType(String content) {
        // Regex für beide Währungspositionen
        String regex = "You (just )?received\\s*(€\\s?[\\d.,]+|[\\d.,]+\\s?€|\\$\\s?[\\d.,]+|[\\d.,]+\\s?\\$)\\s*in\\s*([A-Za-z0-9]+)\\s*for holding\\s*\\3\\s*in\\s*(\\w+)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String amount = matcher.group(2).trim(); // Betrag (inkl. Währung)
            String asset = matcher.group(3).trim(); // Kryptowährung
            String period = matcher.group(4).trim(); // Zeitraum (z.B. April)
            return new String[] { amount, asset, period };
        }

        // Fallback, falls kein Treffer
        System.out.println("No relevant staking content found in: " + content);
        return new String[] { "Unknown", "Unknown", "Unknown" };
    }

    private static void parseDepositContent(List<RowData> rowDataList, String content, String sentDate,
            String messageType) {
        // Extrahiere Betrag, Asset und Quelle
        String[] extractedData = extractDataFromDepositType(content);
        String amount = extractedData[0];
        String asset = extractedData[1];
        String source = extractedData[2];

        String incomingAmount = amount; // Betrag bleibt unverändert
        String incomingAsset;

        // Bestimme, ob das Asset Fiat oder Krypto ist
        if (asset.equals("€")) {
            incomingAsset = "EUR";
        } else if (asset.equals("$")) {
            incomingAsset = "USD";
        } else {
            incomingAsset = asset; // Krypto-Währung direkt übernehmen
        }

        // Erstelle den Kommentar
        String comment = "Transfer of " + amount + " " + asset + " from " + source;

        // Erstelle Zeilendaten
        RowData rowData = new RowData(sentDate, INTEGRATION_NAME, messageType, "", "", incomingAsset, incomingAmount,
                "", "", comment, "");
        rowDataList.add(rowData);
    }

    private static String[] extractDataFromDepositType(String content) {
        // Regex für Betrag und Asset (Fiat oder Krypto), unabhängig von der Position
        // der Währung
        String regex = "Amount:\\s*(€\\s?[\\d.,]+|[\\d.,]+\\s?€|\\$\\s?[\\d.,]+|[\\d.,]+\\s?\\$|[\\d.,]+\\s?[A-Za-z]+|[A-Za-z]+\\s?[\\d.,]+)\\s*From:\\s*([\\w\\s\\d]+)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            // Extrahiere Rohbetrag und Asset
            String rawAmount = matcher.group(1).trim();
            String source = matcher.group(2).trim();

            // Verarbeite Betrag und Asset
            String[] amountAndAsset = processRawAmountAndAsset(rawAmount);
            return new String[] { amountAndAsset[0], amountAndAsset[1], source }; // amount, asset, source
        }

        // Fallback, falls kein Treffer
        System.out.println("No relevant deposit content found in: " + content);
        return new String[] { "Unknown", "Unknown", "Unknown" };
    }

    private static String[] processRawAmountAndAsset(String rawAmount) {
        // Entferne Leerzeichen
        rawAmount = rawAmount.replaceAll("\\s", "");

        // Bestimme die Währung oder das Asset
        String asset;
        if (rawAmount.startsWith("€")) {
            asset = "EUR";
            rawAmount = rawAmount.substring(1); // Entferne das Symbol
        } else if (rawAmount.endsWith("€")) {
            asset = "EUR";
            rawAmount = rawAmount.substring(0, rawAmount.length() - 1); // Entferne das Symbol
        } else if (rawAmount.startsWith("$")) {
            asset = "USD";
            rawAmount = rawAmount.substring(1); // Entferne das Symbol
        } else if (rawAmount.endsWith("$")) {
            asset = "USD";
            rawAmount = rawAmount.substring(0, rawAmount.length() - 1); // Entferne das Symbol
        } else {
            // Wenn keine Fiat-Währung erkannt wird, ist es ein Krypto-Asset
            asset = rawAmount.replaceAll("[^A-Za-z]", ""); // Extrahiere nur Buchstaben
            rawAmount = rawAmount.replaceAll("[A-Za-z]", ""); // Entferne Buchstaben aus dem Betrag
        }

        // Normalisiere den Betrag
        rawAmount = rawAmount.replace(",", "."); // Ersetze Kommas durch Punkte
        rawAmount = replaceAllExceptLast(rawAmount, "."); // Entferne alle Punkte, außer den letzten

        return new String[] { rawAmount, asset };
    }

    private static String replaceAllExceptLast(String text, String target) {
        int lastIndex = text.lastIndexOf(target);
        if (lastIndex == -1) {
            return text; // Kein Ziel gefunden, keine Änderung
        }
        return text.substring(0, lastIndex).replace(target, "") + text.substring(lastIndex);
    }

    private static void parseWithdrawalToBankContent(
            List<RowData> rowDataList, String content, String sentDate, String label) {
        // Extrahiere Daten aus der Nachricht
        String[] extractedData = extractDataFromWithdrawalToBankType(content);
        String rawAmount = extractedData[0];
        String toAddress = extractedData[1];

        // Verarbeite den Betrag
        rawAmount = rawAmount.replace(",", "."); // Ersetze "," durch "."
        rawAmount = replaceAllExceptLast(rawAmount, "."); // Entferne alle Punkte, außer den letzten

        String outgoingAmount = rawAmount.replaceAll("[^\\d.]", "").trim(); // Entferne alles außer Zahlen und "."
        String outgoingAsset;

        if (rawAmount.contains("€")) {
            outgoingAsset = "EUR";
        } else if (rawAmount.contains("$")) {
            outgoingAsset = "USD";
        } else {
            outgoingAsset = rawAmount.replaceAll("[^a-zA-Z]", "").trim(); // Dynamische Kryptowährung
        }

        // Erstelle Kommentar
        String comment = "Transfer " + rawAmount + " to " + toAddress;

        // Füge Daten zur Liste hinzu
        RowData rowData = new RowData(
                sentDate, INTEGRATION_NAME, label,
                outgoingAsset, outgoingAmount, "", "", "", "", comment, "");
        rowDataList.add(rowData);
    }

    private static String[] extractDataFromWithdrawalToBankType(String content) {
        String regex = "Amount:\\s*([\\d.,]+\\s?[€$a-zA-Z]+|[€$a-zA-Z]+\\s?[\\d.,]+)\\s*To:\\s*(.+)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String amount = matcher.group(1).trim(); // Betrag inkl. Währung
            String to = matcher.group(2).trim(); // Alles, was nach "To:" kommt (IBAN oder Wallet-Adresse)
            return new String[] { amount, to };
        }

        // Fallback, falls kein Treffer
        System.out.println("No relevant withdrawal content found in: " + content);
        return new String[] { "Unknown", "Unknown" };
    }

    private static void parseWithdrawalToWalletContent(
            List<RowData> rowDataList, String content, String sentDate, String label) {
        String[] extractedData = extractDataFromWithdrawalToWalletType(content);

        String rawDate = extractedData[0];
        String fee = extractedData[1];
        String feeAsset = extractedData[2];
        String walletAddress = extractedData[3];
        String receivedAmount = extractedData[4];
        String receivedAsset = extractedData[5];
        String transactionId = extractedData[6];

        // Verarbeite Datum in UTC
        String dateUTC = convertToUTC(rawDate);

        // Erstelle Kommentar
        String comment = walletAddress + " will receive " + receivedAmount + " " + receivedAsset
                + ", see transaction details of " + transactionId;

        fee = fee.replace(",", "."); // Ersetze "," durch "."
        fee = replaceAllExceptLast(fee, "."); // Entferne alle Punkte, außer den letzten
        receivedAmount = receivedAmount.replace(",", "."); // Ersetze "," durch "."
        receivedAmount = replaceAllExceptLast(receivedAmount, "."); // Entferne alle Punkte, außer den letzten

        // Füge Daten zur Liste hinzu
        RowData rowData = new RowData(
                dateUTC, INTEGRATION_NAME, label, receivedAsset,
                receivedAmount,
                "", "",
                feeAsset, fee, comment, transactionId);
        rowDataList.add(rowData);
    }

    private static String[] extractDataFromWithdrawalToWalletType(String content) {
        // Regex für die Hauptteile nach "on"
        String mainRegex = "on\\s*(\\d{1,2}\\s[A-Za-z]{3,9},?\\s\\d{4}\\sat\\s\\d{2}:\\d{2}\\s[A-Z]+),\\s*and paid a network fee of\\s*([\\d.,]+)\\s*([A-Za-z0-9]+)\\.\\s*The wallet address\\s*([A-Za-z0-9]+)\\s*will receive\\s*([\\d.,]+)\\s*([A-Za-z0-9]+)";
        String transactionIdRegex = "transaction ID is\\s*([A-Za-z0-9]+)";

        // Matcher für Hauptteile
        java.util.regex.Pattern mainPattern = java.util.regex.Pattern.compile(mainRegex,
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher mainMatcher = mainPattern.matcher(content);

        if (mainMatcher.find()) {
            String rawDatePart = mainMatcher.group(1).trim();
            String fee = mainMatcher.group(2).trim();
            String feeAsset = mainMatcher.group(3).trim();
            String walletAddress = mainMatcher.group(4).trim();
            String receivedAmount = mainMatcher.group(5).trim();
            String receivedAsset = mainMatcher.group(6).trim();

            // Matcher für Transaction ID
            java.util.regex.Pattern transactionIdPattern = java.util.regex.Pattern.compile(transactionIdRegex);
            java.util.regex.Matcher transactionIdMatcher = transactionIdPattern.matcher(content);
            String transactionId = "Unknown";

            if (transactionIdMatcher.find()) {
                transactionId = transactionIdMatcher.group(1).trim();
            } else {
                System.err.println("No Transaction ID found in: " + content);
            }

            // Parse rawDatePart into structured components
            String[] parsedDateComponents = parseDateComponents(rawDatePart);
            if (parsedDateComponents != null) {
                String formattedDate = parsedDateComponents[0]; // Raw Date in normalisiertem Format
                return new String[] { formattedDate, fee, feeAsset, walletAddress, receivedAmount, receivedAsset,
                        transactionId };
            } else {
                System.err.println("Failed to parse date components for: " + rawDatePart);
            }
        }

        System.out.println("No relevant wallet withdrawal content found in: " + content);
        return new String[] { "Unknown", "0", "Unknown", "Unknown", "0", "Unknown", "Unknown" };
    }

    // Zweiter Schritt: Zerlege und normalisiere den Datumsteil
    private static String[] parseDateComponents(String rawDatePart) {
        // Regex für die Datumsbestandteile
        String dateRegex = "(\\d{1,2})\\s([A-Za-z]{3,9}),?\\s(\\d{4})\\sat\\s(\\d{2}:\\d{2})\\s([A-Z]+)";
        java.util.regex.Pattern datePattern = java.util.regex.Pattern.compile(dateRegex);
        java.util.regex.Matcher dateMatcher = datePattern.matcher(rawDatePart);

        if (dateMatcher.find()) {
            String day = dateMatcher.group(1).trim();
            String month = dateMatcher.group(2).trim();
            month = normalizeMonth(month); // Monat normalisieren
            String year = dateMatcher.group(3).trim();
            String time = dateMatcher.group(4).trim();
            String timezone = dateMatcher.group(5).trim();

            // Formatiere das rohe Datum
            String rawDate = day + " " + month + " " + year + " " + time + " " + timezone;

            return new String[] { rawDate, day, month, year, time, timezone };
        }

        // Fallback, falls keine gültigen Datumsteile gefunden wurden
        System.err.println("Error parsing date components: " + rawDatePart);
        return null;
    }

    // Monat normalisieren
    private static String normalizeMonth(String month) {
        Map<String, String> monthMap = new HashMap<>();
        monthMap.put("Jan", "January");
        monthMap.put("Feb", "February");
        monthMap.put("Mar", "March");
        monthMap.put("Apr", "April");
        monthMap.put("May", "May");
        monthMap.put("Jun", "June");
        monthMap.put("Jul", "July");
        monthMap.put("Aug", "August");
        monthMap.put("Sep", "September");
        monthMap.put("Oct", "October");
        monthMap.put("Nov", "November");
        monthMap.put("Dec", "December");
        return monthMap.getOrDefault(month, month);
    }

    private static String convertToUTC(String rawDate) {
        try {
            SimpleDateFormat inputFormat;
            if (rawDate.matches(".*\\b[A-Za-z]{3}\\b.*")) {
                inputFormat = new SimpleDateFormat("d MMM yyyy HH:mm z", Locale.ENGLISH); // Kurzform
            } else {
                inputFormat = new SimpleDateFormat("d MMMM yyyy HH:mm z", Locale.ENGLISH); // Langform
            }
            if (rawDate.contains("CEST")) {
                inputFormat.setTimeZone(TimeZone.getTimeZone("CEST"));
            } else {
                inputFormat.setTimeZone(TimeZone.getTimeZone("CET"));
            }

            Date parsedDate = inputFormat.parse(rawDate);

            SimpleDateFormat outputFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");
            outputFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

            return outputFormat.format(parsedDate);
        } catch (Exception e) {
            System.err.println("Error parsing or converting date: " + rawDate + " - " + e.getMessage());
            return "Unknown";
        }
    }

    private static void parseTradeContent(
            List<RowData> rowDataList, String content, String sentDate, String messageType) {
        String[] extractedData = extractDataFromTradeType(content);

        String rawDate = extractedData[0];
        String incomingAmount = extractedData[1];
        String incomingAsset = extractedData[2];
        String finalCost = extractedData[3];
        String costCurrency = extractedData[4];

        // Verarbeite Datum in UTC
        String dateUTC = convertToUTC(rawDate);

        finalCost = finalCost.replace(",", "."); // Ersetze "," durch "."
        finalCost = replaceAllExceptLast(finalCost, "."); // Entferne alle Punkte, außer den letzten

        incomingAmount = incomingAmount.replace(",", "."); // Ersetze "," durch "."
        incomingAmount = replaceAllExceptLast(incomingAmount, "."); // Entferne alle Punkte, außer den letzten

        // Kommentar
        String comment = "Trade executed: Purchased " + incomingAmount + " " + incomingAsset +
                " for " + finalCost + " " + costCurrency;

        // Füge die Daten zur Liste hinzu
        RowData rowData = new RowData(
                dateUTC, INTEGRATION_NAME, messageType, costCurrency,
                finalCost, incomingAsset, incomingAmount, "", "", comment, "");
        rowDataList.add(rowData);
    }

    private static String[] extractDataFromTradeType(String content) {
        String amountRegex = "Amount purchased:\\s*([\\d.,]+)\\s*([A-Za-z0-9]+)";
        String costRegex = "Final cost:\\s*(€\\s?[\\d.,]+|[\\d.,]+\\s?€|\\$\\s?[\\d.,]+|[\\d.,]+\\s?\\$)";
        String dateRegex = "Date completed:\\s*(\\d{1,2}\\s[A-Za-z]{3,9},?\\s\\d{4}\\sat\\s\\d{2}:\\d{2}\\s[A-Z]+)";

        // Matcher für Bestandteile
        java.util.regex.Pattern amountPattern = java.util.regex.Pattern.compile(amountRegex);
        java.util.regex.Matcher amountMatcher = amountPattern.matcher(content);

        java.util.regex.Pattern costPattern = java.util.regex.Pattern.compile(costRegex);
        java.util.regex.Matcher costMatcher = costPattern.matcher(content);

        java.util.regex.Pattern datePattern = java.util.regex.Pattern.compile(dateRegex);
        java.util.regex.Matcher dateMatcher = datePattern.matcher(content);

        String incomingAmount = "0";
        String incomingAsset = "Unknown";
        if (amountMatcher.find()) {
            incomingAmount = amountMatcher.group(1).trim();
            incomingAsset = amountMatcher.group(2).trim();
        } else {
            System.err.println("No amount purchased data found in: " + content);
        }

        String finalCost = "0";
        String costCurrency = "Unknown";
        if (costMatcher.find()) {
            String costRaw = costMatcher.group(1).trim();
            if (costRaw.startsWith("€")) {
                costCurrency = "EUR";
                finalCost = costRaw.substring(1).trim();
            } else if (costRaw.endsWith("€")) {
                costCurrency = "EUR";
                finalCost = costRaw.substring(0, costRaw.length() - 1).trim();
            } else if (costRaw.startsWith("$")) {
                costCurrency = "USD";
                finalCost = costRaw.substring(1).trim();
            } else if (costRaw.endsWith("$")) {
                costCurrency = "USD";
                finalCost = costRaw.substring(0, costRaw.length() - 1).trim();
            } else {
                System.err.println("Unknown cost format: " + costRaw);
            }
        } else {
            System.err.println("No final cost data found in: " + content);
        }

        String rawDate = "Unknown";
        if (dateMatcher.find()) {
            rawDate = dateMatcher.group(1).trim();

            // Datum normalisieren
            String[] parsedDateComponents = parseDateComponents(rawDate);
            if (parsedDateComponents != null) {
                rawDate = parsedDateComponents[0]; // Normalisiertes Datum
            } else {
                System.err.println("Failed to parse date components for: " + rawDate);
            }
        } else {
            System.err.println("No date completed data found in: " + content);
        }

        return new String[] { rawDate, incomingAmount, incomingAsset, finalCost, costCurrency };
    }

    // Helper class to store row data
    static class RowData {
        private final String date;
        private final String integrationName;
        private final String label;
        private final String outgoingAsset;
        private final String outgoingAmount;
        private final String incomingAsset;
        private final String incomingAmount;
        private final String feeAsset;
        private final String feeAmount;
        private final String comment;
        private final String transactionId;

        public RowData(String date, String integrationName, String label, String outgoingAsset,
                String outgoingAmount, String incomingAsset, String incomingAmount, String feeAsset, String feeAmount,
                String comment,
                String transactionId) {
            this.date = date;
            this.integrationName = integrationName;
            this.label = label;
            this.outgoingAsset = outgoingAsset;
            this.outgoingAmount = outgoingAmount;
            this.incomingAsset = incomingAsset;
            this.incomingAmount = incomingAmount;
            this.feeAsset = feeAsset;
            this.feeAmount = feeAmount;
            this.comment = comment;
            this.transactionId = transactionId;
        }

        public String getDate() {
            return date;
        }

        public String getIntegrationName() {
            return integrationName;
        }

        public String getLabel() {
            return label;
        }

        public String getOutgoingAsset() {
            return outgoingAsset;
        }

        public String getOutgoingAmount() {
            return outgoingAmount;
        }

        public String getIncomingAsset() {
            return incomingAsset;
        }

        public String getIncomingAmount() {
            return incomingAmount;
        }

        public String getFeeAsset() {
            return feeAsset;
        }

        public String getFeeAmount() {
            return feeAmount;
        }

        public String getComment() {
            return comment;
        }

        public String getTransactionId() {
            return transactionId;
        }
    }
}
