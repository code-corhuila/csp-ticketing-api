package com.cinesync.ticketing.adapter.out.pdf;

import com.cinesync.ticketing.core.model.Ticket;
import com.cinesync.ticketing.core.port.out.PdfGeneratorPort;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.layout.element.Image;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.List;

@Component
@Slf4j
public class ITextPdfGenerator implements PdfGeneratorPort {

    @Override
    public byte[] generateTicketPdf(Ticket ticket) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdfDoc = new PdfDocument(writer);
            Document document = new Document(pdfDoc, PageSize.A6.rotate());

            // Title
            document.add(new Paragraph("CineSync Platform")
                    .setFontSize(20)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(10));

            document.add(new Paragraph("Digital Ticket")
                    .setFontSize(16)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(20));

            // Details table
            Table table = new Table(UnitValue.createPercentArray(new float[]{30, 70}))
                    .useAllAvailableWidth();

            addTableRow(table, "Movie", ticket.getMovieTitleSnapshot());
            addTableRow(table, "Room", ticket.getRoomNameSnapshot());
            addTableRow(table, "Showtime", ticket.getShowtimeStartsAt().toString());
            addTableRow(table, "Seats", String.join(", ", ticket.getSeatLabelsSnapshot()));
            addTableRow(table, "Reservation ID", ticket.getReservationId().toString());
            addTableRow(table, "QR Payload", ticket.getQrPayload());

            if (ticket.getConcessionsSnapshot() != null && !ticket.getConcessionsSnapshot().isEmpty()) {
                String concessionsStr = ticket.getConcessionsSnapshot().stream()
                        .map(c -> c.getName() + " x" + c.getQuantity())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                addTableRow(table, "Concessions", concessionsStr);
            }

            document.add(table);

            // QR Code
            document.add(new Paragraph("\n"));
            byte[] qrBytes = generateQrCode(ticket.getQrPayload());
            Image qrImage = new Image(ImageDataFactory.create(qrBytes))
                    .setWidth(100)
                    .setHeight(100)
                    .setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER);
            document.add(qrImage);

            document.add(new Paragraph(ticket.getQrPayload())
                    .setFontSize(8)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(5));

            document.close();
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("Failed to generate PDF for ticket {}: {}", ticket.getId(), e.getMessage(), e);
            throw new RuntimeException("PDF generation failed", e);
        }
    }

    private void addTableRow(Table table, String label, String value) {
        table.addCell(new Cell().add(new Paragraph(label).setBold()).setBorder(null));
        table.addCell(new Cell().add(new Paragraph(value)).setBorder(null));
    }

    private byte[] generateQrCode(String payload) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(payload, BarcodeFormat.QR_CODE, 300, 300);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(bitMatrix);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("QR code generation failed", e);
        }
    }
}