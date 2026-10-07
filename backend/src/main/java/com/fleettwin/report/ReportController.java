package com.fleettwin.report;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

import com.fleettwin.auth.AuditService;
import com.fleettwin.report.ReportBuilder.Type;
import com.fleettwin.report.ReportService.Format;
import com.fleettwin.report.ReportService.Report;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    /** from and to are dates (UTC); to is inclusive. */
    public record ReportRequest(@NotNull Type type, @NotNull Format format, @NotNull LocalDate from, @NotNull LocalDate to) {
    }

    private final ReportService service;
    private final AuditService audit;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Report generate(@Valid @RequestBody ReportRequest req, Principal principal) {
        Report report = service.generate(req.type(), req.format(), req.from(), req.to(), principal.getName());
        audit.record("GENERATE", "report", report.id(), report.fileName());
        return report;
    }

    /** Past reports, newest first. */
    @GetMapping
    public List<Report> list() {
        return service.list();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable long id) {
        Report report = service.get(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(report.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(report.format().contentType))
                .contentLength(report.sizeBytes())
                .body(new InputStreamResource(service.open(report)));
    }
}
