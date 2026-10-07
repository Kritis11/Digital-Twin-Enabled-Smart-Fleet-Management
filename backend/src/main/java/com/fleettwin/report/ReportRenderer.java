package com.fleettwin.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import com.fleettwin.report.ReportData.Section;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Turns a {@link ReportData} into a PDF (OpenPDF) or an Excel workbook (Apache POI). */
public final class ReportRenderer {

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final Font HEADER = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
    private static final Color HEADER_BACKGROUND = new Color(0x16, 0x20, 0x2c);

    private ReportRenderer() {
    }

    public static byte[] pdf(ReportData report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph(report.title(), TITLE));
        doc.add(new Paragraph(report.subtitle(), BODY));
        for (Section section : report.sections()) {
            Paragraph heading = new Paragraph(section.title(), HEADING);
            heading.setSpacingBefore(14);
            heading.setSpacingAfter(4);
            doc.add(heading);
            for (String note : section.notes()) {
                doc.add(new Paragraph(note, BODY));
            }
            if (section.headers().isEmpty()) {
                continue;
            }
            if (section.rows().isEmpty()) {
                doc.add(new Paragraph("Nothing in this period.", BODY));
                continue;
            }
            PdfPTable table = new PdfPTable(section.headers().size());
            table.setWidthPercentage(100);
            table.setSpacingBefore(6);
            table.setHeaderRows(1);
            for (String header : section.headers()) {
                PdfPCell cell = new PdfPCell(new Phrase(header, HEADER));
                cell.setBackgroundColor(HEADER_BACKGROUND);
                cell.setPadding(4);
                table.addCell(cell);
            }
            for (List<Object> row : section.rows()) {
                for (Object value : row) {
                    PdfPCell cell = new PdfPCell(new Phrase(text(value), BODY));
                    cell.setPadding(3);
                    cell.setHorizontalAlignment(value instanceof Number ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                    table.addCell(cell);
                }
            }
            doc.add(table);
        }
        doc.close();
        return out.toByteArray();
    }

    /** One sheet per section; numbers stay numbers so they can be summed and charted. */
    public static byte[] xlsx(ReportData report) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFFont boldFont = workbook.createFont();
            boldFont.setBold(true);
            CellStyle bold = workbook.createCellStyle();
            bold.setFont(boldFont);
            for (Section section : report.sections()) {
                // Excel: at most 31 characters and none of []:*?/\
                String name = section.title().replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
                Sheet sheet = workbook.createSheet(name.length() > 31 ? name.substring(0, 31) : name);
                int r = 0;
                Row title = sheet.createRow(r++);
                title.createCell(0).setCellValue(report.title() + ": " + section.title());
                title.getCell(0).setCellStyle(bold);
                sheet.createRow(r++).createCell(0).setCellValue(report.subtitle());
                for (String note : section.notes()) {
                    sheet.createRow(r++).createCell(0).setCellValue(note);
                }
                if (section.headers().isEmpty()) {
                    continue;
                }
                r++;
                Row header = sheet.createRow(r++);
                for (int c = 0; c < section.headers().size(); c++) {
                    header.createCell(c).setCellValue(section.headers().get(c));
                    header.getCell(c).setCellStyle(bold);
                }
                for (List<Object> values : section.rows()) {
                    Row row = sheet.createRow(r++);
                    for (int c = 0; c < values.size(); c++) {
                        Object value = values.get(c);
                        if (value instanceof Number n) {
                            row.createCell(c).setCellValue(n.doubleValue());
                        } else if (value != null) {
                            row.createCell(c).setCellValue(value.toString());
                        }
                    }
                }
                // Fixed widths: autoSizeColumn needs AWT fonts, which a headless server image may not have.
                for (int c = 0; c < section.headers().size(); c++) {
                    sheet.setColumnWidth(c, 20 * 256);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not write the workbook", e);
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return "–";
        }
        if (value instanceof Double || value instanceof Float || value instanceof java.math.BigDecimal) {
            return String.format(Locale.ROOT, "%,.2f", ((Number) value).doubleValue());
        }
        return value.toString();
    }
}
