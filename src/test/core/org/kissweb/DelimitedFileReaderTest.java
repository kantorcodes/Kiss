package org.kissweb;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the header-name facilities of {@link DelimitedFileReader}.
 */
class DelimitedFileReaderTest {

    private static DelimitedFileReader open(String content) throws Exception {
        File f = File.createTempFile("dfr", ".csv");
        f.deleteOnExit();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return new DelimitedFileReader(f);
    }

    @Test
    void defaultBehaviorUnchanged() throws Exception {
        try (DelimitedFileReader r = open("Name,Age\nBob,42\n")) {
            r.readHeader();
            assertTrue(r.nextLine());
            assertEquals("Bob", r.getString("name"));
            assertEquals(42, r.getInt("AGE"));
            assertEquals("", r.getString("Na me"));
            assertFalse(r.hasHeader("Na-me"));
        }
    }

    @Test
    void normalizationAndAliases() throws Exception {
        try (DelimitedFileReader r = open("﻿Account_Name, Phone-Number ,Amt\nAcme,555,1.5\n")) {
            r.setNormalizeHeaders(true);
            r.addAlias("Company Name", "Company", "Account Name");
            r.readHeader();
            assertTrue(r.nextLine());
            assertEquals("Acme", r.getString("Company Name"));
            assertEquals("555", r.getString("phone number"));
            assertEquals(1.5, r.getDouble("amt"));
            assertEquals(555, r.getInt("Phone Number"));
        }
    }

    @Test
    void aliasesWithoutNormalization() throws Exception {
        try (DelimitedFileReader r = open("Company,Zip\nAcme,12345\n")) {
            r.addAlias("Company Name", "Company");
            r.readHeader();
            assertTrue(r.nextLine());
            assertEquals("Acme", r.getString("Company Name"));
            assertEquals(12345, r.getInt("zip"));
        }
    }

    @Test
    void requiredColumnsListAllMissing() throws Exception {
        try (DelimitedFileReader r = open("A,B\n1,2\n")) {
            r.readHeader();
            assertEquals(java.util.List.of("C", "D"), r.missingHeaders("A", "C", "B", "D"));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> r.requireHeaders("A", "C", "D"));
            assertTrue(e.getMessage().contains("C, D"));
            r.requireHeaders("A", "B");
        }
    }

    @Test
    void duplicateDetection() throws Exception {
        try (DelimitedFileReader r = open("Company,Account Name,Phone,phone\n")) {
            r.addAlias("Company Name", "Company", "Account Name");
            r.readHeader();
            assertEquals(2, r.getDuplicateHeaders().size());
            assertTrue(r.getDuplicateHeaders().contains("Company, Account Name"));
            assertTrue(r.getDuplicateHeaders().contains("Phone, phone"));
        }
    }
}
