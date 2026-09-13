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
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayOutputStream;

@RequiredArgsConstructor
@Service
public class PdfReportService {
    private final AreaCalculationRepository calculationRepository;
    private final AreaCalculationMapper areaCalculationMapper;

    public byte[] generatePreliminaryReportPdf(Long calculationId) {
        log.info("Iniciando generación de PDF para calculationId: {}", calculationId);

        AreaCalculationEntity calculation = calculationRepository.findById(calculationId)
                .orElseThrow(() -> new RuntimeException("Cálculo no encontrado con ID: " + calculationId));

        AreaCalculationResponseDto dto = areaCalculationMapper.toResponseDto(calculation);

        // LOG 1: Verificar si el DTO y la lista de colindancias vienen vacíos o nulos
        if (dto.getBoundaries() == null) {
            log.warn("¡ATENCIÓN! dto.getBoundaries() es NULL para el ID: {}", calculationId);
        } else {
            log.info("Cantidad de colindancias encontradas en el DTO: {}", dto.getBoundaries().size());
        }

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

            // Metadatos
            document.add(new Paragraph("Nombre Finca: " + dto.getTerrainName(), bodyFont));
            document.add(new Paragraph("Tipo de Propiedad: " + dto.getPropertyType(), bodyFont));
            document.add(new Paragraph("Propietario (Cliente DPI): " + (dto.getClientUser() != null ? dto.getClientUser().getDpi() : "N/A"), bodyFont));
            document.add(new Paragraph("Fecha de Emisión: " + dto.getCreatedAt(), bodyFont));
            document.add(new Paragraph("Área Total Estimada: " + dto.getTotalAreaSquareMeters() + " m²", titleFont));
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

            if (dto.getBoundaries() != null) {
                for (AreaCalculationResponseDto.BoundaryDto boundaryDto : dto.getBoundaries()) {
                    log.info("Procesando colindancia - Lado: {}, Orientación: {}", boundaryDto.getSideNumber(), boundaryDto.getOrientation());

                    double totalMeters = 0.0;
                    if (boundaryDto.getMeasurements() != null) {
                        for (AreaCalculationResponseDto.MeasurementDto m : boundaryDto.getMeasurements()) {
                            if (m.getValueConvertedMeters() != null) {
                                totalMeters += m.getValueConvertedMeters();
                            }
                        }
                    } else {
                        log.warn("La colindancia {} no tiene mediciones asociadas (measurements es null).", boundaryDto.getSideNumber());
                    }

                    String orientationStr = boundaryDto.getOrientation() != null ? boundaryDto.getOrientation() : "N/A";

                    table.addCell(new PdfPCell(new Phrase(String.valueOf(boundaryDto.getSideNumber()), bodyFont)));
                    table.addCell(new PdfPCell(new Phrase(orientationStr, bodyFont)));
                    table.addCell(new PdfPCell(new Phrase(String.format("%.2f m", totalMeters), bodyFont)));
                    table.addCell(new PdfPCell(new Phrase(boundaryDto.getReferencePoint() != null ? boundaryDto.getReferencePoint() : "N/A", bodyFont)));
                }
            }
            document.add(table);
            document.add(Chunk.NEWLINE);

            // Esquema Gráfico Ampliado
            document.add(new Paragraph("Esquema Geométrico de Referencia:", subtitleFont));
            document.add(Chunk.NEWLINE);

            PdfContentByte canvas = writer.getDirectContent();
            float startX = 160;
            float startY = 410;
            float width = 230;
            float height = 120;

            canvas.setColorStroke(new Color(40, 40, 40));
            canvas.setLineWidth(1.5f);
            canvas.moveTo(startX, startY);
            canvas.lineTo(startX + width, startY);
            canvas.lineTo(startX + width - 30, startY + height);
            canvas.lineTo(startX, startY + height - 20);
            canvas.closePath();
            canvas.stroke();

            canvas.setColorStroke(new Color(100, 100, 100));
            canvas.setLineWidth(0.5f);

            ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER, new Phrase("150.00m", smallFont), startX + (width/2), startY - 12, 0);
            ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER, new Phrase("95.50m", smallFont), startX - 18, startY + (height/2), 90);
            ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER, new Phrase("105.20m", smallFont), startX + width + 18, startY + (height/2) + 5, 65);
            ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER, new Phrase("142.10m", smallFont), startX + (width/2) - 15, startY + height + 8, 0);

            canvas.rectangle(startX, startY - 35, 75, 16);
            canvas.stroke();
            ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER, new Phrase("Escala 1:200", smallFont), startX + 37.5f, startY - 28, 0);

            float compassX = startX + width + 45;
            float compassY = startY + (height / 2);
            canvas.circle(compassX, compassY, 16);
            canvas.stroke();
            ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER, new Phrase("N", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD)), compassX, compassY + 4, 0);

            // Aviso Legal en el pie de página absoluto
            PdfPTable footerTable = new PdfPTable(1);
            footerTable.setTotalWidth(523);
            footerTable.setLockedWidth(true);

            PdfPCell legalCell = new PdfPCell(new Phrase("AVISO LEGAL OBLIGATORIO:\nSub-área fraccionada de referencia técnica. Sujeta a validación notarial.", warningFont));
            legalCell.setBorder(com.lowagie.text.Rectangle.TOP);
            legalCell.setBorderColor(new Color(150, 150, 150));
            legalCell.setPaddingTop(6);
            footerTable.addCell(legalCell);

            footerTable.writeSelectedRows(0, -1, 36, 50, writer.getDirectContent());

            document.close();
            log.info("PDF generado exitosamente para calculationId: {}", calculationId);
        } catch (DocumentException e) {
            log.error("Error al generar el PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Error al generar el documento PDF preliminar", e);
        }

        return out.toByteArray();
    }
}