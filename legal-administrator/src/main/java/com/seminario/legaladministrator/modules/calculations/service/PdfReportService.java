package com.seminario.legaladministrator.modules.calculations.service;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import com.lowagie.text.Image;
import com.seminario.legaladministrator.modules.calculations.AreaCalculationEntity;
import com.seminario.legaladministrator.modules.calculations.dto.AreaCalculationResponseDto;
import com.seminario.legaladministrator.modules.calculations.mapper.AreaCalculationMapper;
import com.seminario.legaladministrator.modules.calculations.repository.AreaCalculationRepository;
import com.seminario.legaladministrator.modules.users.service.ClientUserService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Service
public class PdfReportService {
    private final AreaCalculationRepository calculationRepository;
    private final AreaCalculationMapper areaCalculationMapper;
    private final ClientUserService clientUserService;

    private static @NonNull PdfPTable createFooterTable(Font warningFont) {
        PdfPTable footerTable = new PdfPTable(1);
        footerTable.setTotalWidth(523);
        footerTable.setLockedWidth(true);

        PdfPCell legalCell = new PdfPCell(new Phrase("AVISO LEGAL:\nSub-área fraccionada de referencia técnica. Sujeta a validación notarial.", warningFont));
        legalCell.setBorder(com.lowagie.text.Rectangle.TOP);
        legalCell.setBorderColor(new Color(150, 150, 150));
        legalCell.setPaddingTop(6);
        footerTable.addCell(legalCell);
        return footerTable;
    }

    @Transactional(readOnly = true)
    public byte[] generatePreliminaryReportPdf(Long calculationId) {
        AreaCalculationEntity calculation = calculationRepository.findById(calculationId)
                .orElseThrow(() -> new RuntimeException("Cálculo no encontrado con ID: " + calculationId));

        AreaCalculationResponseDto dto = areaCalculationMapper.toResponseDto(calculation);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Font.BOLD);
            Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.ITALIC);
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Font.NORMAL);
            Font headerTableFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Font.BOLD);
            Font warningFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.BOLD);

            // 1. Encabezado y Metadatos
            addHeaderAndMetadata(document, dto, titleFont, subtitleFont, bodyFont);

            // 2. Tabla de Datos de Colindancias y Medidas
            List<Double> segmentLengths = new ArrayList<>();
            List<String> vertexLabels = new ArrayList<>();
            PdfPTable table = createBoundariesTable(calculation, headerTableFont, bodyFont, segmentLengths, vertexLabels);
            document.add(table);
            document.add(Chunk.NEWLINE);

            // 3. Esquema Geométrico de Referencia o Imagen del Plano Guardada
            document.add(new Paragraph("Esquema Geométrico de Referencia / Plano:", subtitleFont));
            document.add(Chunk.NEWLINE);

            float currentY = writer.getVerticalPosition(true);
            PdfContentByte canvas = writer.getDirectContent();

            float boxWidth = 320f;
            float boxHeight = 180f;
            float startX = (PageSize.A4.getWidth() - boxWidth) / 2;
            float startY = currentY - boxHeight - 10;

            if (startY < 60) {
                document.newPage();
                startY = PageSize.A4.getHeight() - 200;
            }

            // Marco del contenedor del plano
            drawPlanFrame(canvas, startX, startY, boxWidth, boxHeight);

            // Si la entidad tiene una imagen de plano cargada (en bytes), la incrustamos; de lo contrario, dibujamos el polígono dinámico por defecto
            if (calculation.getPlanImage() != null && calculation.getPlanImage().length > 0) {
                drawStoredPlanImage(document, canvas, calculation.getPlanImage(), startX, startY, boxWidth, boxHeight);
            } else {
                drawGeometricPolygon(canvas, startX, startY, boxWidth, boxHeight, segmentLengths, vertexLabels);
            }

            // Dibujar la Rosa de los Vientos encima de la imagen o esquema
            drawCompassRose(canvas, startX, startY, boxWidth, boxHeight);

            // 4. Pie de página legal
            PdfPTable footerTable = createFooterTable(warningFont);
            footerTable.writeSelectedRows(0, -1, 36, 45, writer.getDirectContent());

            document.close();
        } catch (DocumentException | java.io.IOException e) {
            throw new RuntimeException("Error al generar el documento PDF preliminar", e);
        }

        return out.toByteArray();
    }

    private void addHeaderAndMetadata(Document document, AreaCalculationResponseDto dto, Font titleFont, Font subtitleFont, Font bodyFont) throws DocumentException {
        Paragraph headerApp = new Paragraph("LEGAL-ADMINISTRATOR", titleFont);
        headerApp.setAlignment(Element.ALIGN_CENTER);
        document.add(headerApp);

        Paragraph headerType = new Paragraph("REPORTE PRELIMINAR DE ÁREA - PLANO DE FINCA NUEVA", subtitleFont);
        headerType.setAlignment(Element.ALIGN_CENTER);
        document.add(headerType);
        document.add(Chunk.NEWLINE);

        String terrainName = dto.getTerrainName() != null ? dto.getTerrainName() : "N/A";
        String propertyType = dto.getPropertyType() != null ? dto.getPropertyType() : "N/A";
        String clientDpi = (dto.getClientUser() != null && dto.getClientUser().getDpi() != null) ? dto.getClientUser().getDpi() : "N/A";

        String clientName = "N/A";
        try {
            if (dto.getClientUser() != null && dto.getClientUser().getDpi() != null) {
                var client = clientUserService.getClientByDpi(dto.getClientUser().getDpi());
                clientName = client.getFirstName() + " " + client.getLastName();
            }
        } catch (Exception ignored) {
        }

        String location = dto.getLocation() != null ? dto.getLocation() : "N/A";
        String createdAt = dto.getCreatedAt() != null ? dto.getCreatedAt().toString() : "N/A";
        String observations = dto.getGeneralDescription() != null ? dto.getGeneralDescription() : "N/A";

        Double totalArea = dto.getTotalAreaSquareMeters();
        String areaStr = (totalArea != null && totalArea > 0) ? String.format("%.2f", totalArea) : "0.00";

        document.add(new Paragraph("Nombre Finca: " + terrainName, bodyFont));
        document.add(new Paragraph("Tipo de Propiedad: " + propertyType, bodyFont));
        document.add(new Paragraph("Propietario (DPI): " + clientDpi, bodyFont));
        document.add(new Paragraph("Nombre: " + clientName, bodyFont));
        document.add(new Paragraph("Ubicación: " + location, bodyFont));
        document.add(new Paragraph("Fecha de Emisión: " + createdAt, bodyFont));
        document.add(new Paragraph("Área Total Estimada: " + areaStr + " m²", titleFont));
        document.add(new Paragraph("Observaciones: " + observations, bodyFont));

        Paragraph marginNote = new Paragraph("Nota: Área calculada con base en descripciones de escrituras (Sujeto a variación por levantamiento topográfico)", subtitleFont);
        marginNote.setSpacingAfter(5);
        document.add(marginNote);
        document.add(Chunk.NEWLINE);

        document.add(new Paragraph("Desglose de Colindancias y Medidas:", subtitleFont));
        document.add(Chunk.NEWLINE);
    }

    private PdfPTable createBoundariesTable(AreaCalculationEntity calculation, Font headerTableFont, Font bodyFont, List<Double> segmentLengths, List<String> vertexLabels) {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[]{2f, 2f, 2.5f, 4.5f});
        } catch (DocumentException e) {
            throw new RuntimeException(e);
        }

        table.addCell(new PdfPCell(new Phrase("Punto Inicial", headerTableFont)));
        table.addCell(new PdfPCell(new Phrase("Punto Final", headerTableFont)));
        table.addCell(new PdfPCell(new Phrase("Distancia (m)", headerTableFont)));
        table.addCell(new PdfPCell(new Phrase("Colindancia", headerTableFont)));

        if (calculation.getBoundaries() != null && !calculation.getBoundaries().isEmpty()) {
            int totalBoundaries = calculation.getBoundaries().size();
            for (int i = 0; i < totalBoundaries; i++) {
                var boundary = calculation.getBoundaries().get(i);

                double totalMeters = 0.0;
                if (boundary.getMeasurements() != null) {
                    for (var m : boundary.getMeasurements()) {
                        if (m.getValueConvertedMeters() != null) {
                            totalMeters += m.getValueConvertedMeters();
                        }
                    }
                }
                segmentLengths.add(totalMeters);

                String startPoint = String.valueOf((char) ('A' + i));
                String endPoint = String.valueOf((char) ('A' + ((i + 1) % totalBoundaries)));
                vertexLabels.add(startPoint);

                String refPointStr = boundary.getReferencePoint() != null ? boundary.getReferencePoint() : "N/A";

                table.addCell(new PdfPCell(new Phrase(startPoint, bodyFont)));
                table.addCell(new PdfPCell(new Phrase(endPoint, bodyFont)));
                table.addCell(new PdfPCell(new Phrase(String.format("%.2f m", totalMeters), bodyFont)));
                table.addCell(new PdfPCell(new Phrase(refPointStr, bodyFont)));
            }
        } else {
            PdfPCell emptyCell = new PdfPCell(new Phrase("No hay colindancias registradas", bodyFont));
            emptyCell.setColspan(4);
            emptyCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(emptyCell);
        }

        return table;
    }

    private void drawPlanFrame(PdfContentByte canvas, float startX, float startY, float boxWidth, float boxHeight) {
        canvas.setColorStroke(new Color(200, 200, 200));
        canvas.setLineWidth(0.8f);
        canvas.rectangle(startX, startY, boxWidth, boxHeight);
        canvas.stroke();
    }

    private void drawStoredPlanImage(Document document, PdfContentByte canvas, byte[] imageBytes, float startX, float startY, float boxWidth, float boxHeight) throws java.io.IOException, DocumentException {
        Image planImage = Image.getInstance(imageBytes);
        // Ajustar imagen para que encaje de manera proporcional dentro del cuadro delimitador dejando un pequeño margen interno
        planImage.scaleToFit(boxWidth - 10, boxHeight - 10);

        // Centrar la imagen dentro del cuadro
        float imgWidth = planImage.getScaledWidth();
        float imgHeight = planImage.getScaledHeight();
        float posX = startX + (boxWidth - imgWidth) / 2f;
        float posY = startY + (boxHeight - imgHeight) / 2f;

        planImage.setAbsolutePosition(posX, posY);
        document.add(planImage);
    }

    private void drawCompassRose(PdfContentByte canvas, float startX, float startY, float boxWidth, float boxHeight) {
        // Rosa de los vientos estilizada (esquina superior derecha del plano)
        float compassX = startX + boxWidth - 25;
        float compassY = startY + boxHeight - 25;
        float r = 16f;

        canvas.setColorStroke(new Color(80, 80, 80));
        canvas.setLineWidth(0.8f);
        canvas.circle(compassX, compassY, r);
        canvas.stroke();

        // Punta Norte (Rosa con división bicolor clásica)
        canvas.setColorFill(new Color(180, 40, 40)); // Rojo oscuro institucional
        canvas.moveTo(compassX, compassY + r);
        canvas.lineTo(compassX - 3.5f, compassY);
        canvas.lineTo(compassX, compassY);
        canvas.fillStroke();

        canvas.setColorFill(new Color(220, 220, 220));
        canvas.moveTo(compassX, compassY + r);
        canvas.lineTo(compassX + 3.5f, compassY);
        canvas.lineTo(compassX, compassY);
        canvas.fillStroke();

        ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER,
                new Phrase("N", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK)),
                compassX, compassY + r + 5, 0);
    }

    private void drawGeometricPolygon(PdfContentByte canvas, float startX, float startY, float boxWidth, float boxHeight, List<Double> segmentLengths, List<String> vertexLabels) {
        int n = segmentLengths.size();
        float[] polyX = new float[n];
        float[] polyY = new float[n];

        if (n > 2) {
            float currentAngle = 0;
            float posX = startX + (boxWidth / 2f);
            float posY = startY + (boxHeight / 2f);

            double maxLen = segmentLengths.stream().mapToDouble(v -> v).max().orElse(1.0);
            float scaleFactor = (maxLen > 0) ? (50f / (float) maxLen) : 10f;

            polyX[0] = posX;
            polyY[0] = posY;

            for (int i = 0; i < n - 1; i++) {
                float len = (float) (segmentLengths.get(i) * scaleFactor);
                currentAngle += (float) ((2.0 * Math.PI) / n);
                posX += (float) (len * Math.cos(currentAngle));
                posY += (float) (len * Math.sin(currentAngle));

                polyX[i + 1] = posX;
                polyY[i + 1] = posY;
            }
        } else {
            polyX = new float[]{startX + 50, startX + 150, startX + 150, startX + 50};
            polyY = new float[]{startY + 30, startY + 30, startY + 130, startY + 130};
        }

        // Relleno y contorno del polígono simulado
        canvas.setColorFill(new Color(235, 245, 245));
        canvas.setColorStroke(new Color(40, 110, 110));
        canvas.setLineWidth(1.5f);

        canvas.moveTo(polyX[0], polyY[0]);
        for (int i = 1; i < polyX.length; i++) {
            canvas.lineTo(polyX[i], polyY[i]);
        }
        canvas.closePath();
        canvas.fillStroke();

        // Nodos y etiquetas de vértices
        canvas.setColorFill(new Color(20, 50, 50));
        for (int i = 0; i < polyX.length; i++) {
            canvas.circle(polyX[i], polyY[i], 3f);
            canvas.fill();

            String label = i < vertexLabels.size() ? vertexLabels.get(i) : String.valueOf((char) ('A' + i));
            ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER,
                    new Phrase(label, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.DARK_GRAY)),
                    polyX[i] + 8f, polyY[i] + 6f, 0);
        }
    }
}