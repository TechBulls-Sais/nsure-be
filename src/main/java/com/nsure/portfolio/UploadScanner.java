package com.nsure.portfolio;

/**
 * The hook for a malware scanner on uploaded files (NS-SH09A-004). No scanner ships with Nsure, so by default nothing
 * implements this and every upload waits in quarantine as {@link #NOT_SCANNED} until a person with RELEASE_DOCUMENT
 * releases or rejects it. An installation that connects a scanner registers one bean implementing this interface:
 * a {@link #CLEAN} verdict then releases the upload at once and {@link #INFECTED} rejects it.
 */
public interface UploadScanner {

    String CLEAN = "CLEAN";
    String INFECTED = "INFECTED";
    String NOT_SCANNED = "NOT_SCANNED";

    /** The scanner's own name, recorded with each verdict. */
    String name();

    /** Scans the bytes; must not throw for an infected file (return INFECTED with the finding instead). */
    Verdict scan(String fileName, byte[] bytes);

    /** {@code result} is CLEAN, INFECTED or NOT_SCANNED; {@code detail} says what the scanner found or why it did not. */
    record Verdict(String result, String detail) {
    }

    /** The verdict when no scanner is configured: the file was not scanned, so it is never called clean. */
    Verdict NO_SCANNER = new Verdict(NOT_SCANNED, "No malware scanner is configured, so the file was not scanned.");
}
