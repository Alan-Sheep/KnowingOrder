package com.ragqa.rag.ingest;

import com.ragqa.exception.BusinessException;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;

class OfficeTextExtractorTest {
    private final Utf8TextParser parser = new Utf8TextParser();

    @Test void pdfKeepsPageMarkersAndUsesExtractedTextCoordinates() throws Exception {
        var parts = parser.parse("policy.PDF", pdf(2, true));
        String text = parts.get(0).content();
        assertThat(text).contains("【PDF第1页】", "【PDF第2页】", "Meal allowance 30 per day");
        assertThat(parts.get(0).start()).isZero();
        assertThat(parts.get(0).end()).isEqualTo(text.length());
    }
    @Test void scannedEmptyAndTooManyPagesAreRejectedWithoutOcr() throws Exception {
        assertThatThrownBy(() -> parser.parse("scan.pdf", pdf(1, false))).isInstanceOf(BusinessException.class).hasMessageContaining("OCR");
        assertThatThrownBy(() -> parser.parse("big.pdf", pdf(101, false))).isInstanceOf(BusinessException.class);
    }
    @Test void docxParagraphsAndTablesBecomeStableTextWithoutExternalAccess() throws Exception {
        var parts = parser.parse("虚构制度.docx", docx("<w:p><w:r><w:t>餐补每日30元。</w:t></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>主管审批</w:t></w:r></w:p></w:tc></w:tr></w:tbl>", false));
        assertThat(parts.get(0).content()).contains("餐补每日30元。\n", "主管审批\n\t");
        assertThat(parts.get(0).end()).isEqualTo(parts.get(0).content().length());
    }
    @Test void docxRejectsDtdEmbeddedObjectsAndExpansionBombs() throws Exception {
        assertThatThrownBy(() -> parser.parse("xxe.docx", docx("<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///not-read'>]><w:p><w:r><w:t>&leak;</w:t></w:r></w:p>", false)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> parser.parse("embedded.docx", docx("<w:p><w:r><w:t>正文</w:t></w:r></w:p>", true)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> parser.parse("bomb.docx", docx("<w:p><w:r><w:t>" + "a".repeat(4 * 1024 * 1024) + "</w:t></w:r></w:p>", false)))
                .isInstanceOf(BusinessException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"file.pdf", "file.docx", "file.doc", "file.exe"})
    void suffixAloneDoesNotMakePlainTextAValidOfficeFile(String name) {
        assertThatThrownBy(() -> parser.parse(name, "普通文字".getBytes(StandardCharsets.UTF_8))).isInstanceOf(BusinessException.class);
    }
    private byte[] pdf(int pages, boolean text) throws Exception {
        try (var doc = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(); doc.addPage(page);
                if (text) try (var content = new PDPageContentStream(doc, page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(40, 700); content.showText("Meal allowance 30 per day"); content.endText();
                }
            }
            doc.save(bytes); return bytes.toByteArray();
        }
    }
    private byte[] docx(String body, boolean embedded) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            entry(zip, "[Content_Types].xml", "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'/>");
            entry(zip, "word/document.xml", "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body>" + body + "</w:body></w:document>");
            if (embedded) entry(zip, "word/embeddings/object.bin", "not executed");
        }
        return bytes.toByteArray();
    }
    private void entry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
