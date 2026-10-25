package com.lifedesk.finance;

import java.util.ArrayList;
import java.util.List;

/** Report of a CSV import run. */
public class CsvImportReport {
    public int created;
    public int skippedDuplicates;
    public int errors;
    public List<String> errorLines = new ArrayList<>();
    public List<String> messages = new ArrayList<>();

    public void addError(int lineNo, String message) {
        errors++;
        errorLines.add("Dòng " + lineNo + ": " + message);
    }
}
