package org.kissweb;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence-agnostic base class for importing a delimited (CSV) file.
 * <br><br>
 * This class knows nothing about databases, ORMs, logging frameworks, or web paths.  Everything of that sort is
 * done by a subclass or through the hook methods ({@link #commitBatch()}, {@link #onFinish(Result)},
 * {@link #onFailure(Throwable)}, {@link #log(int, String)}, {@link #deactivateMissing()}), which have no-op defaults.
 * <br><br>
 * <b>Lifecycle</b> (run by {@link #run()}):
 * <ol>
 *   <li>The header is read and validated: missing required columns, duplicate columns, and "no recognized
 *       column" are reported as errors.</li>
 *   <li><i>Check pass</i>: every data row is read and passed to {@link #checkRow(int)}, which reports problems
 *       with {@link #addError(String)}.  Nothing should be written in this pass.</li>
 *   <li><i>Parse pass</i>: only if there were no errors, the file is read again and every row is passed to
 *       {@link #parseRow(int)}, which does the actual work and increments {@link #recordsAdded},
 *       {@link #recordsUpdated}, and/or {@link #recordsDeactivated}.  {@link #commitBatch()} is called every
 *       {@link #setBatchSize(int) batch size} rows and at the end.</li>
 *   <li>In {@link #FULL_MODE}, and only if there were still no errors, {@link #deactivateMissing()} is called.</li>
 *   <li>{@link #onFinish(Result)} (or {@link #onFailure(Throwable)} on an unexpected exception) is called and
 *       the {@link Result} is returned.</li>
 * </ol>
 * <br>
 * Columns are declared by plain logical name with {@link #addColumn(String, boolean, String...)}; the other
 * titles a file may use for the column are given as aliases.  Matching ignores case, white space, hyphens,
 * underscores and non-printable characters (see {@link DelimitedFileReader#setNormalizeHeaders(boolean)}).
 * <pre>
 *   class PersonImport extends DelimitedFileImport {
 *       PersonImport(String file, int mode) throws FileNotFoundException {
 *           super("person", file, mode);
 *       }
 *       protected void initColumns() {
 *           addColumn("Last Name", true, "LNAME", "Surname");
 *           addColumn("Phone", false);
 *       }
 *       protected void checkRow(int rowNum) {
 *           if (getString("Last Name").isEmpty())
 *               addError("Last Name is missing");
 *       }
 *       protected void parseRow(int rowNum) {
 *           // ... write getString("Last Name") using your persistence layer ...
 *           recordsAdded++;
 *       }
 *       protected void commitBatch() { /* flush / commit your persistence layer *&#47; }
 *   }
 *   Result r = new PersonImport(path, DelimitedFileImport.UPDATE_MODE).run();
 * </pre>
 */
public abstract class DelimitedFileImport {

    /** Add new records; existing records are ignored and not updated. */
    public static final int APPEND_MODE = 1;
    /** Add new records and update existing ones. */
    public static final int UPDATE_MODE = 2;
    /** Add new records, update existing records, and deactivate records not in the import file. */
    public static final int FULL_MODE = 3;

    /** Log level for {@link #log(int, String)}. */
    public static final int LOG_INFO = 0;
    /** Log level for {@link #log(int, String)}. */
    public static final int LOG_WARNING = 1;
    /** Log level for {@link #log(int, String)}. */
    public static final int LOG_ERROR = 2;

    /** The reader, positioned on the current data row during {@link #checkRow(int)} and {@link #parseRow(int)}. */
    protected final DelimitedFileReader df;
    /** One of {@link #APPEND_MODE}, {@link #UPDATE_MODE}, {@link #FULL_MODE}. */
    protected final int mode;
    /** Rows read.  Maintained by this class (counted during the parse pass; during the check pass it is the check count). */
    protected int rowsRead;
    /** Incremented by the subclass. */
    protected int recordsAdded;
    /** Incremented by the subclass. */
    protected int recordsUpdated;
    /** Incremented by the subclass. */
    protected int recordsDeactivated;

    private final String typeOfImport;
    private final String importFileName;
    private final Map<String, Boolean> columns = new LinkedHashMap<>();  // logical name -> required
    private final List<String> fileColumns = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private int batchSize = 20;
    private String logPath;
    private int currentRow;

    /**
     * @param typeOfImport a word for what is being imported (e.g. "employee"), used in messages
     * @param filename     the file to import
     * @param mode         {@link #APPEND_MODE}, {@link #UPDATE_MODE}, or {@link #FULL_MODE}
     * @throws FileNotFoundException if the file cannot be opened
     */
    protected DelimitedFileImport(String typeOfImport, String filename, int mode) throws FileNotFoundException {
        this(typeOfImport, filename, mode, ',', '"');
    }

    /**
     * @param typeOfImport a word for what is being imported, used in messages
     * @param filename     the file to import
     * @param mode         {@link #APPEND_MODE}, {@link #UPDATE_MODE}, or {@link #FULL_MODE}
     * @param delimiter    the field delimiter
     * @param quote        the quote character
     * @throws FileNotFoundException if the file cannot be opened
     */
    protected DelimitedFileImport(String typeOfImport, String filename, int mode, char delimiter, char quote) throws FileNotFoundException {
        this.typeOfImport = typeOfImport;
        this.importFileName = filename;
        this.mode = mode;
        df = new DelimitedFileReader(filename, delimiter, quote);
        df.setNormalizeHeaders(true);
    }

    /** Declare the columns by calling {@link #addColumn(String, boolean, String...)}.  Called once by {@link #run()}. */
    protected abstract void initColumns();

    /**
     * Check the current row (available through {@code df} and the {@code get*} helpers).  Report problems with
     * {@link #addError(String)}.  Do not write data.
     *
     * @param rowNum the 1-based data row number (the header is not counted)
     */
    protected abstract void checkRow(int rowNum);

    /**
     * Process the current row.  Only called if the check pass found no errors.  Increment
     * {@link #recordsAdded}, {@link #recordsUpdated}, {@link #recordsDeactivated} as appropriate.
     *
     * @param rowNum the 1-based data row number (the header is not counted)
     */
    protected abstract void parseRow(int rowNum);

    /** Hook for {@link #FULL_MODE}: deactivate records not seen in the file.  Default does nothing. */
    protected void deactivateMissing() {
    }

    /** Hook called every {@link #setBatchSize(int)} rows during the parse pass and once at its end.  Default does nothing. */
    protected void commitBatch() {
    }

    /** Hook called at the end of every run, after the result is built.  Default does nothing. */
    protected void onFinish(Result result) {
    }

    /** Hook called if an unexpected exception aborts the run (the exception is also added as an error). Default does nothing. */
    protected void onFailure(Throwable t) {
    }

    /**
     * Hook for logging.  Default does nothing.
     *
     * @param level {@link #LOG_INFO}, {@link #LOG_WARNING}, or {@link #LOG_ERROR}
     * @param msg   the message
     */
    protected void log(int level, String msg) {
    }

    /**
     * Declare a column.
     *
     * @param name     the logical name (used in messages and by the {@code get*} helpers)
     * @param required true if the file must contain the column
     * @param aliases  other titles the file may use for this column
     */
    protected void addColumn(String name, boolean required, String... aliases) {
        columns.put(name, required);
        if (aliases.length > 0)
            df.addAlias(name, aliases);
    }

    /** @return true if the file has the column (valid after the header has been read) */
    protected boolean colExists(String name) {
        return df.getColumnIndex(name) > -1;
    }

    /** @return the declared columns that are present, in file order */
    protected List<String> getFileColumns() {
        return Collections.unmodifiableList(fileColumns);
    }

    /** @return true if the column exists and the current row has non-blank data in it */
    protected boolean columnHasData(String name) {
        int i = df.getColumnIndex(name);
        if (i < 0)
            return false;
        String s = df.getString(i);
        return s != null && !s.trim().isEmpty();
    }

    /** @return the trimmed value of the column in the current row, or "" if absent */
    protected String getString(String name) {
        int i = df.getColumnIndex(name);
        if (i < 0)
            return "";
        String s = df.getString(i);
        return s == null ? "" : s.trim();
    }

    /** Remove dollar signs and commas from a number so it can be parsed. */
    protected String fixNum(String num) {
        return num.replace("$", "").replace(",", "");
    }

    /**
     * Record an error.  During the row passes the current row number is prefixed.
     */
    protected void addError(String err) {
        errors.add(currentRow > 0 ? "Row " + currentRow + ": " + err : err);
    }

    /** Record a warning (does not stop the import). */
    protected void addWarning(String warn) {
        warnings.add(currentRow > 0 ? "Row " + currentRow + ": " + warn : warn);
    }

    /** @return the number of errors so far */
    protected int errorCount() {
        return errors.size();
    }

    /** Set how many rows are processed between {@link #commitBatch()} calls (default 20). */
    public void setBatchSize(int n) {
        batchSize = Math.max(1, n);
    }

    /** Ask this class to write the summary / error log to the given file with plain java.io.  Not written if never set. */
    public void setLogPath(String path) {
        logPath = path;
    }

    /**
     * Run the import.  Never throws; failures are reported in the result.
     *
     * @return the result
     */
    public Result run() {
        try {
            initColumns();
            readHeader();
            if (errors.isEmpty())
                passes();
        } catch (Throwable t) {
            currentRow = 0;
            addError("Unexpected error: " + t);
            log(LOG_ERROR, "Unexpected error: " + t);
            try {
                onFailure(t);
            } catch (Throwable ignored) {
            }
        } finally {
            df.close();
        }
        currentRow = 0;
        Result r = new Result(typeOfImport, new File(importFileName).getName(), new ArrayList<>(errors),
                new ArrayList<>(warnings), rowsRead, recordsAdded, recordsUpdated, recordsDeactivated);
        writeLog(r);
        try {
            onFinish(r);
        } catch (Throwable t) {
            log(LOG_ERROR, "onFinish failed: " + t);
        }
        return r;
    }

    private void readHeader() {
        df.readHeader();
        if (df.size() == 0) {
            addError("import file is missing the expected (first) column title line");
            return;
        }
        for (String dup : df.getDuplicateHeaders())
            addError("import file has duplicate column titles (" + dup + ")");
        for (Map.Entry<String, Boolean> e : columns.entrySet())
            if (e.getValue() && !colExists(e.getKey()))
                addError("import file is missing required column " + e.getKey());
        List<String> found = new ArrayList<>();
        for (String name : columns.keySet())
            if (colExists(name))
                found.add(name);
        if (found.isEmpty()) {
            addError("import file has no usable / recognizable columns");
            return;
        }
        found.sort((x, y) -> Integer.compare(df.getColumnIndex(x), df.getColumnIndex(y)));
        fileColumns.addAll(found);
    }

    private void passes() throws Exception {
        int n = readRows(false);
        if (!errors.isEmpty())
            return;
        df.moveToStart();
        df.readHeader();
        rowsRead = 0;
        recordsAdded = recordsUpdated = recordsDeactivated = 0;
        readRows(true);
        currentRow = 0;
        if (mode == FULL_MODE && errors.isEmpty())
            deactivateMissing();
        log(LOG_INFO, typeOfImport + " import processed " + n + " rows");
    }

    private int readRows(boolean parse) throws Exception {
        int n = 0;
        while (true) {
            try {
                if (!df.nextLine())
                    break;
            } catch (java.io.IOException ioe) {
                throw ioe;
            } catch (Exception e) {
                currentRow = ++n;
                addError(e.getMessage());
                continue;
            }
            currentRow = ++n;
            rowsRead++;
            if (parse) {
                parseRow(n);
                if (n % batchSize == 0)
                    commitBatch();
            } else
                checkRow(n);
        }
        currentRow = 0;
        if (parse)
            commitBatch();
        return n;
    }

    private void writeLog(Result r) {
        if (logPath == null)
            return;
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(logPath)))) {
            pw.print(r.getSummary());
        } catch (Exception e) {
            log(LOG_ERROR, "Unable to write log file: " + e);
        }
    }

    /** The outcome of a run. */
    public static class Result {
        private final String type;
        private final String fileName;
        private final List<String> errors;
        private final List<String> warnings;
        private final int rowsRead, added, updated, deactivated;

        Result(String type, String fileName, List<String> errors, List<String> warnings, int rowsRead, int added, int updated, int deactivated) {
            this.type = type;
            this.fileName = fileName;
            this.errors = Collections.unmodifiableList(errors);
            this.warnings = Collections.unmodifiableList(warnings);
            this.rowsRead = rowsRead;
            this.added = added;
            this.updated = updated;
            this.deactivated = deactivated;
        }

        /** @return the errors (each prefixed with its row number where applicable) */
        public List<String> getErrors() { return errors; }
        /** @return the warnings */
        public List<String> getWarnings() { return warnings; }
        /** @return true if there were no errors */
        public boolean isSuccess() { return errors.isEmpty(); }
        /** @return rows read */
        public int getRowsRead() { return rowsRead; }
        /** @return records added */
        public int getRecordsAdded() { return added; }
        /** @return records updated */
        public int getRecordsUpdated() { return updated; }
        /** @return records deactivated */
        public int getRecordsDeactivated() { return deactivated; }

        /** @return formatted text: file, errors, warnings and counts */
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(type).append(" import\nFile: ").append(fileName).append("\n\n");
            for (String e : errors)
                sb.append("ERROR: ").append(e).append('\n');
            for (String w : warnings)
                sb.append("WARNING: ").append(w).append('\n');
            sb.append('\n').append(rowsRead).append(" import records read\n");
            sb.append(errors.size()).append(" errors encountered\n");
            sb.append(warnings.size()).append(" warnings\n");
            sb.append(added).append(' ').append(type).append("s added\n");
            sb.append(updated).append(' ').append(type).append("s updated\n");
            sb.append(deactivated).append(' ').append(type).append("s deactivated\n");
            return sb.toString();
        }

        @Override
        public String toString() {
            return getSummary();
        }
    }
}
