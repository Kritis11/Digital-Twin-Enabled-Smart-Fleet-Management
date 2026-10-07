package com.fleettwin.report;

import java.util.List;

/**
 * A report before it is given a file format: a title and sections, each with a few sentences and
 * optionally one table. Both renderers (PDF and Excel) work from this, so the two files always agree.
 */
public record ReportData(String title, String subtitle, List<Section> sections) {

    /** rows hold String, Number or null; headers is empty for a section without a table. */
    public record Section(String title, List<String> notes, List<String> headers, List<List<Object>> rows) {
    }
}
