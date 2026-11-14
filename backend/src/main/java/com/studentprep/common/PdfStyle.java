package com.studentprep.common;

import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;

import java.awt.Color;

/**
 * Shared color palette and OpenPDF cell helpers used across printable StudentPrep PDF documents.
 */
public final class PdfStyle {

    public static final Color JAMB_GREEN = new Color(0, 135, 81);
    public static final Color SLATE_900 = new Color(15, 23, 42);
    public static final Color SLATE_700 = new Color(51, 65, 85);
    public static final Color SLATE_600 = new Color(71, 85, 105);
    public static final Color SLATE_500 = new Color(100, 116, 139);
    public static final Color SLATE_300 = new Color(203, 213, 225);
    public static final Color SLATE_50 = new Color(248, 250, 252);
    public static final Color RED_600 = new Color(220, 38, 38);

    private PdfStyle() {
    }

    /**
     * Builds a styled table header cell using the shared brand green background.
     */
    public static PdfPCell createHeaderCell(String text, Font font, int horizontalAlignment, float padding) {
        PdfPCell cell = new PdfPCell(new Phrase(safeText(text), font));
        cell.setBackgroundColor(JAMB_GREEN);
        cell.setBorderColor(JAMB_GREEN);
        cell.setHorizontalAlignment(horizontalAlignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(padding);
        return cell;
    }

    /**
     * Builds a styled body cell with explicit alignment, background, and border colors.
     */
    public static PdfPCell createBodyCell(
            String text,
            Font font,
            int horizontalAlignment,
            Color backgroundColor,
            Color borderColor,
            float padding) {
        PdfPCell cell = new PdfPCell(new Phrase(safeText(text), font));
        cell.setBackgroundColor(backgroundColor);
        cell.setBorderColor(borderColor);
        cell.setHorizontalAlignment(horizontalAlignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(padding);
        return cell;
    }

    public static String safeText(String value) {
        return value != null ? value : "";
    }
}
