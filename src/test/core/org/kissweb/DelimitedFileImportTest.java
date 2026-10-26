package org.kissweb;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DelimitedFileImport}.
 */
class DelimitedFileImportTest {

    private static String tmp(String content) throws Exception {
        File f = File.createTempFile("dfi", ".csv");
        f.deleteOnExit();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f.getPath();
    }

    static class Imp extends DelimitedFileImport {
        final List<String> parsed = new ArrayList<>();
        int commits, finishes, deactivations;
        Result finished;

        Imp(String file, int mode) throws Exception {
            super("person", file, mode);
        }

        @Override protected void initColumns() {
            addColumn("Last Name", true, "LNAME", "Surname");
            addColumn("Phone", false);
        }
        @Override protected void checkRow(int n) {
            if (getString("Last Name").isEmpty())
                addError("Last Name is missing");
        }
        @Override protected void parseRow(int n) {
            parsed.add(getString("Last Name") + "|" + columnHasData("Phone"));
            recordsAdded++;
        }
        @Override protected void commitBatch() { commits++; }
        @Override protected void deactivateMissing() { deactivations++; recordsDeactivated += 2; }
        @Override protected void onFinish(Result r) { finishes++; finished = r; }
    }

    @Test
    void goodFileTwoPasses() throws Exception {
        Imp i = new Imp(tmp("lname, Phone\nSmith,555\nJones,\n"), DelimitedFileImport.FULL_MODE);
        i.setBatchSize(1);
        DelimitedFileImport.Result r = i.run();
        assertTrue(r.isSuccess());
        assertEquals(2, r.getRowsRead());
        assertEquals(2, r.getRecordsAdded());
        assertEquals(2, r.getRecordsDeactivated());
        assertEquals(List.of("Smith|true", "Jones|false"), i.parsed);
        assertEquals(3, i.commits);   // every row plus the final one
        assertEquals(1, i.finishes);
        assertTrue(r.getSummary().contains("2 persons added"));
    }

    @Test
    void checkErrorsStopParse() throws Exception {
        Imp i = new Imp(tmp("Phone,Surname\n1,Smith\n2,\n3,Jones\n"), DelimitedFileImport.FULL_MODE);
        DelimitedFileImport.Result r = i.run();
        assertFalse(r.isSuccess());
        assertEquals(1, r.getErrors().size());
        assertTrue(r.getErrors().get(0).startsWith("Row 2:"));
        assertTrue(i.parsed.isEmpty());
        assertEquals(0, i.deactivations);
        assertEquals(1, i.finishes);
    }

    @Test
    void headerProblems() throws Exception {
        DelimitedFileImport.Result r = new Imp(tmp("Phone\n1\n"), DelimitedFileImport.APPEND_MODE).run();
        assertTrue(r.getErrors().get(0).contains("missing required column Last Name"));
        r = new Imp(tmp("Surname,LNAME\nA,B\n"), DelimitedFileImport.APPEND_MODE).run();
        assertTrue(r.getErrors().get(0).contains("duplicate"));
        r = new Imp(tmp("x,y\n1,2\n"), DelimitedFileImport.APPEND_MODE).run();
        assertFalse(r.isSuccess());
    }

    @Test
    void appendModeSkipsDeactivationAndWritesLog() throws Exception {
        Imp i = new Imp(tmp("Last Name\nA\n"), DelimitedFileImport.APPEND_MODE);
        File log = File.createTempFile("dfilog", ".txt");
        log.deleteOnExit();
        i.setLogPath(log.getPath());
        i.run();
        assertEquals(0, i.deactivations);
        assertTrue(new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).contains("1 import records read"));
    }
}
