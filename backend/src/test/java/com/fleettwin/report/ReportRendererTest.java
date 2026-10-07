package com.fleettwin.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import com.fleettwin.report.ReportData.Section;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ReportRendererTest {

    private final ReportData report = new ReportData("Fuel report", "2026-10-01 to 2026-10-07 (UTC).", List.of(
            new Section("Efficiency", List.of("The fleet drove 3,000 km."), List.of("Vehicle", "Distance km", "km per litre"),
                    List.of(Arrays.asList("KA01AB1234", 1634.8, 2.45), Arrays.asList("DL01JK7890", 0.0, null))),
            new Section("Fuel anomalies: theft/leak?", List.of("0 fuel anomalies in the period."), List.of("When", "Vehicle"), List.of())));

    @Test
    void pdfContainsTheTitleNotesAndRows() throws Exception {
        byte[] pdf = ReportRenderer.pdf(report);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        for (String expected : List.of("Fuel report", "The fleet drove 3,000 km.", "KA01AB1234", "1,634.80", "Nothing in this period.")) {
            assertTrue(text.contains(expected), expected + " missing from: " + text);
        }
    }

    @Test
    void workbookHasOneSheetPerSectionAndKeepsNumbersNumeric() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(ReportRenderer.xlsx(report)))) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals("Fuel anomalies  theft leak", workbook.getSheetName(1), "characters Excel forbids are replaced");
            Sheet sheet = workbook.getSheetAt(0);
            // title, subtitle, one note, blank, header, then the rows
            assertEquals("Vehicle", sheet.getRow(4).getCell(0).getStringCellValue());
            assertEquals("KA01AB1234", sheet.getRow(5).getCell(0).getStringCellValue());
            assertEquals(CellType.NUMERIC, sheet.getRow(5).getCell(1).getCellType());
            assertEquals(1634.8, sheet.getRow(5).getCell(1).getNumericCellValue());
            assertEquals(null, sheet.getRow(6).getCell(2), "null leaves the cell empty");
        }
    }
}
