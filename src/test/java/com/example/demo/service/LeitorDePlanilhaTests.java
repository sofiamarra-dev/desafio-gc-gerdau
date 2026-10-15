package com.example.demo.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LeitorDePlanilhaTests {
    @Test
    void leUploadComUnidadesAntesDaAbaDeServicos() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var units = workbook.createSheet("2. Unidades de Medida");
            units.createRow(0).createCell(0).setCellValue("Sigla");
            units.getRow(0).createCell(1).setCellValue("Descrição");
            units.createRow(1).createCell(0).setCellValue("H");
            units.getRow(1).createCell(1).setCellValue("Hora");
            var sheet = workbook.createSheet("Base");
            var header = sheet.createRow(0);
            String[] columns = {"Grupo de Serviço", "Código do Fornecedor", "Código do Serviço", "Descrição do Item", "Unidade de medida"};
            for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
            var row = sheet.createRow(1);
            String[] values = {"Elétrica", "F01", "00123", "Manutenção de motor", "h"};
            for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            var out = new ByteArrayOutputStream();
            workbook.write(out);
            var file = new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
            var parsed = new LeitorDePlanilha().parse(file);
            assertEquals("Base", parsed.sheet());
            assertEquals(1, parsed.items().size());
            assertEquals("00123", parsed.items().getFirst().get("codigo_servico"));
            assertEquals("Manutenção de motor", parsed.items().getFirst().get("descricao_item"));
            assertEquals("Hora", parsed.items().getFirst().get("unidade_descricao"));
        }
    }

    @Test
    void rejeitaPlanilhaSemCabecalhos() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            workbook.createSheet("Outra aba");
            var out = new ByteArrayOutputStream();
            workbook.write(out);
            var file = new MockMultipartFile("file", "upload.xlsx", null, out.toByteArray());
            assertThrows(IllegalArgumentException.class, () -> new LeitorDePlanilha().parse(file));
        }
    }
}
