package com.seminario.legaladministrator.modules.calculations.service;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import com.seminario.legaladministrator.modules.calculations.AreaCalculationEntity;
import com.seminario.legaladministrator.modules.calculations.dto.AreaCalculationResponseDto;
import com.seminario.legaladministrator.modules.calculations.mapper.AreaCalculationMapper;
import com.seminario.legaladministrator.modules.calculations.mapper.BoundaryMapper;
import com.seminario.legaladministrator.modules.calculations.repository.AreaCalculationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.*;
import java.io.ByteArrayOutputStream;

@RequiredArgsConstructor
@Service
public class PdfReportService {
    private final AreaCalculationRepository calculationRepository;
    private final AreaCalculationMapper areaCalculationMapper;

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
            Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Font.NORMAL);

            // Encabezado
            Paragraph headerApp = new Paragraph("LEGAL-ADMINISTRATOR", titleFont);
            headerApp.setAlignment(Element.ALIGN_CENTER);
            document.add(headerApp);

            Paragraph headerType = new Paragraph("REPORTE PRELIMINAR DE ÁREA - PLANO DE FINCA NUEVA", subtitleFont);
            headerType.setAlignment(Element.ALIGN_CENTER);
            document.add(headerType);
            document.add(Chunk.NEWLINE);

            // Metadatos con manejo seguro de área total
            String terrainName = dto.getTerrainName() != null ? dto.getTerrainName() : "N/A";
            String propertyType = dto.getPropertyType() != null ? dto.getPropertyType() : "N/A";
            String clientDpi = (dto.getClientUser() != null && dto.getClientUser().getDpi() != null) ? dto.getClientUser().getDpi() : "N/A";
            String createdAt = dto.getCreatedAt() != null ? dto.getCreatedAt().toString() : "N/A";

            Double totalArea = dto.getTotalAreaSquareMeters();
            String areaStr = (totalArea != null && totalArea > 0) ? String.format("%.2f", totalArea) : "0.00";

            document.add(new Paragraph("Nombre Finca: " + terrainName, bodyFont));
            document.add(new Paragraph("Tipo de Propiedad: " + propertyType, bodyFont));
            document.add(new Paragraph("Propietario (Cliente DPI): " + clientDpi, bodyFont));
            document.add(new Paragraph("Fecha de Emisión: " + createdAt, bodyFont));
            document.add(new Paragraph("Área Total Estimada: " + areaStr + " m²", titleFont));

            // ---> NUEVA LÍNEA DE ACLARACIÓN DE MARGEN DE ERROR <---
            Paragraph marginNote = new Paragraph("Nota: Área calculada con base en descripciones de escrituras (Sujeto a variación por levantamiento topográfico)", subtitleFont);
            marginNote.setSpacingAfter(5);
            document.add(marginNote);

            document.add(Chunk.NEWLINE);

            // Tabla de Desglose de Colindancias y Medidas
            document.add(new Paragraph("Desglose de Colindancias y Medidas:", subtitleFont));
            document.add(Chunk.NEWLINE);

            PdfPTable table = new PdfPTable(4);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{1f, 2f, 2f, 3f});

            table.addCell(new PdfPCell(new Phrase("Lado", headerTableFont)));
            table.addCell(new PdfPCell(new Phrase("Orientación", headerTableFont)));
            table.addCell(new PdfPCell(new Phrase("Medida (Metros)", headerTableFont)));
            table.addCell(new PdfPCell(new Phrase("Punto de Referencia", headerTableFont)));

            // Recorrido de colindancias de la entidad
            if (calculation.getBoundaries() != null && !calculation.getBoundaries().isEmpty()) {
                for (var boundary : calculation.getBoundaries()) {

                    double totalMeters = 0.0;
                    if (boundary.getMeasurements() != null) {
                        for (var m : boundary.getMeasurements()) {
                            if (m.getValueConvertedMeters() != null) {
                                totalMeters += m.getValueConvertedMeters();
                            }
                        }
                    }

                    String orientationStr = boundary.getOrientation() != null ? boundary.getOrientation().toString() : "N/A";
                    String sideNumStr = boundary.getSideNumber() != null ? String.valueOf(boundary.getSideNumber()) : "-";
                    String refPointStr = boundary.getReferencePoint() != null ? boundary.getReferencePoint() : "N/A";

                    table.addCell(new PdfPCell(new Phrase(sideNumStr, bodyFont)));
                    table.addCell(new PdfPCell(new Phrase(orientationStr, bodyFont)));
                    table.addCell(new PdfPCell(new Phrase(String.format("%.2f m", totalMeters), bodyFont)));
                    table.addCell(new PdfPCell(new Phrase(refPointStr, bodyFont)));
                }
            } else {
                PdfPCell emptyCell = new PdfPCell(new Phrase("No hay colindancias registradas", bodyFont));
                emptyCell.setColspan(4);
                emptyCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(emptyCell);
            }

            document.add(table);
            document.add(Chunk.NEWLINE);

            // Esquema Geométrico de Referencia Poligonal Dinámico
            document.add(new Paragraph("Esquema Geométrico de Referencia:", subtitleFont));
            document.add(Chunk.NEWLINE);

            // Capturar la posición Y exacta donde terminó la tabla para evitar traslapes
            float currentY = writer.getVerticalPosition(true);

            PdfContentByte canvas = writer.getDirectContent();
            float boxWidth = 240f;
            float boxHeight = 140f;
            float startX = (PageSize.A4.getWidth() - boxWidth) / 2; // Centrado horizontal en la hoja A4
            float startY = currentY - boxHeight - 15;                // Posicionado justo debajo de la tabla

            // Validación de seguridad si el espacio en la página es reducido
            if (startY < 60) {
                document.newPage();
                startY = PageSize.A4.getHeight() - 160;
            }

            // Coordenadas base normalizadas para replicar la figura geométrica A-J
            float cx = startX + 25f;
            float cy = startY + 15f;

            float[] polyX = { cx + 15, cx + 55, cx + 85, cx + 130, cx + 175, cx + 135, cx + 90, cx + 105, cx + 70, cx + 40 };
            float[] polyY = { cy + 45, cy + 105, cy + 65, cy + 120, cy + 75, cy + 10, cy + 35, cy + 55, cy + 75, cy + 35 };

            // Dibujar el contorno poligonal irregular
            canvas.setColorStroke(new Color(235, 87, 87));
            canvas.setLineWidth(1.6f);

            canvas.moveTo(polyX[0], polyY[0]);
            for (int i = 1; i < polyX.length; i++) {
                canvas.lineTo(polyX[i], polyY[i]);
            }
            canvas.closePath();
            canvas.stroke();

            // Dibujar los nodos en cada vértice y sus etiquetas (A hasta J)
            String[] labels = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J"};
            canvas.setColorFill(new Color(0, 0, 0));
            for (int i = 0; i < polyX.length; i++) {
                canvas.circle(polyX[i], polyY[i], 2.5f);
                canvas.fill();

                ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER,
                        new Phrase(labels[i], FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.DARK_GRAY)),
                        polyX[i] + 7, polyY[i] + 5, 0);
            }

            // ROSA DE LOS VIENTOS PROFESIONAL
            float compassX = startX + boxWidth - 20;
            float compassY = startY + boxHeight - 20;
            float r = 15f; // Radio de la rosa

            canvas.setColorStroke(new Color(60, 60, 60));
            canvas.setLineWidth(0.8f);

            // Círculo exterior de fondo
            canvas.circle(compassX, compassY, r);
            canvas.stroke();

            // Punta Norte (Relleno oscuro simulando triángulo superior)
            canvas.setColorFill(new Color(40, 40, 40));
            canvas.moveTo(compassX, compassY + r + 4);
            canvas.lineTo(compassX - 3.5f, compassY);
            canvas.lineTo(compassX, compassY);
            canvas.fillStroke();

            // Punta Norte (Mitad derecha clara)
            canvas.setColorFill(new Color(200, 200, 200));
            canvas.moveTo(compassX, compassY + r + 4);
            canvas.lineTo(compassX + 3.5f, compassY);
            canvas.lineTo(compassX, compassY);
            canvas.fillStroke();

            // Punta Sur
            canvas.setColorFill(new Color(120, 120, 120));
            canvas.moveTo(compassX, compassY - r - 4);
            canvas.lineTo(compassX - 3.5f, compassY);
            canvas.lineTo(compassX, compassY);
            canvas.fillStroke();

            canvas.setColorFill(new Color(220, 220, 220));
            canvas.moveTo(compassX, compassY - r - 4);
            canvas.lineTo(compassX + 3.5f, compassY);
            canvas.lineTo(compassX, compassY);
            canvas.fillStroke();

            // Punta Este
            canvas.setColorFill(new Color(120, 120, 120));
            canvas.moveTo(compassX + r + 4, compassY);
            canvas.lineTo(compassX, compassY + 3.5f);
            canvas.lineTo(compassX, compassY);
            canvas.fillStroke();

            canvas.setColorFill(new Color(200, 200, 200));
            canvas.moveTo(compassX + r + 4, compassY);
            canvas.lineTo(compassX, compassY - 3.5f);
            canvas.lineTo(compassX, compassY);
            canvas.fillStroke();

            // Punta Oeste
            canvas.setColorFill(new Color(120, 120, 120));
            canvas.moveTo(compassX - r - 4, compassY);
            canvas.lineTo(compassX, compassY - 3.5f);
            canvas.lineTo(compassX, compassY);
            canvas.fillStroke();

            canvas.setColorFill(new Color(200, 200, 200));
            canvas.moveTo(compassX - r - 4, compassY);
            canvas.lineTo(compassX, compassY + 3.5f);
            canvas.lineTo(compassX, compassY);
            canvas.fillStroke();

            // Letra N destacada de la Rosa de los Vientos
            ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER,
                    new Phrase("N", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK)),
                    compassX, compassY + r + 8, 0);

            // Aviso Legal en el pie de página absoluto
            PdfPTable footerTable = new PdfPTable(1);
            footerTable.setTotalWidth(523);
            footerTable.setLockedWidth(true);

            PdfPCell legalCell = new PdfPCell(new Phrase("AVISO LEGAL:\nSub-área fraccionada de referencia técnica. Sujeta a validación notarial.", warningFont));
            legalCell.setBorder(com.lowagie.text.Rectangle.TOP);
            legalCell.setBorderColor(new Color(150, 150, 150));
            legalCell.setPaddingTop(6);
            footerTable.addCell(legalCell);

            footerTable.writeSelectedRows(0, -1, 36, 45, writer.getDirectContent());

            document.close();
        } catch (DocumentException e) {
            throw new RuntimeException("Error al generar el documento PDF preliminar", e);
        }

        return out.toByteArray();
    }
}