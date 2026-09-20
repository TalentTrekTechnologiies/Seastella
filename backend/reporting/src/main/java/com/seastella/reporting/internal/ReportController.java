package com.seastella.reporting.internal;

import com.seastella.core.api.error.NotFoundException;
import com.seastella.reporting.api.ReportCatalogue;
import com.seastella.reporting.api.ReportTable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Reports (SoW s7). Each is read on screen or taken away as a PDF, and both
 * come from the same build - a printed report and the screen it was printed
 * from can never disagree.
 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

    private final ReportService reports;
    private final ReportPdfWriter pdf;

    ReportController(ReportService reports, ReportPdfWriter pdf) {
        this.reports = reports;
        this.pdf = pdf;
    }

    /** What this role may run. */
    @GetMapping
    ResponseEntity<List<ReportService.Available>> available() {
        return ResponseEntity.ok(reports.available());
    }

    @GetMapping("/{key}")
    ResponseEntity<ReportTable> report(@PathVariable String key) {
        return ResponseEntity.ok(reports.build(byKey(key)));
    }

    @GetMapping("/{key}/pdf")
    ResponseEntity<byte[]> asPdf(@PathVariable String key) {
        ReportTable table = reports.build(byKey(key));
        String fileName = "seastella-" + table.key() + "-" + LocalDate.now(ZoneOffset.UTC) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.write(table));
    }

    private static ReportCatalogue byKey(String key) {
        return ReportCatalogue.byKey(key).orElseThrow(() -> NotFoundException.ofResource("Report", null));
    }
}
