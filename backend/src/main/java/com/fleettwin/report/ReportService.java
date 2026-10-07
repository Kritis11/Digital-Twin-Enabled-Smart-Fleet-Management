package com.fleettwin.report;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.fleettwin.config.FleetProperties;
import com.fleettwin.report.ReportBuilder.Type;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Generates reports, keeps the files in MinIO and the list of them in the reports table. */
@Service
@Slf4j
public class ReportService {

    public enum Format {
        PDF("application/pdf"), XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        public final String contentType;

        Format(String contentType) {
            this.contentType = contentType;
        }
    }

    public record Report(long id, Type type, Format format, LocalDate periodFrom, LocalDate periodTo, String createdBy,
                         OffsetDateTime createdAt, String objectKey, long sizeBytes) {

        public String fileName() {
            return String.format("%s_%s_%s.%s", type.name().toLowerCase(), periodFrom, periodTo, format.name().toLowerCase());
        }
    }

    private final FleetProperties.Reports cfg;
    private final ReportBuilder builder;
    private final JdbcTemplate jdbc;
    private final MinioClient minio;
    private final ObjectProvider<JavaMailSender> mail;

    public ReportService(FleetProperties props, ReportBuilder builder, JdbcTemplate jdbc, ObjectProvider<JavaMailSender> mail,
                         @Value("${fleet.minio.endpoint}") String endpoint,
                         @Value("${fleet.minio.access-key}") String accessKey,
                         @Value("${fleet.minio.secret-key}") String secretKey) {
        this.cfg = props.reports();
        this.builder = builder;
        this.jdbc = jdbc;
        this.mail = mail;
        this.minio = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    public Report generate(Type type, Format format, LocalDate from, LocalDate to, String createdBy) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= cfg.maxPeriodDays()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "from must not be after to, and the period at most " + cfg.maxPeriodDays() + " days");
        }
        ReportData data = builder.build(type, from, to);
        byte[] file = format == Format.PDF ? ReportRenderer.pdf(data) : ReportRenderer.xlsx(data);
        String key = String.format("%s/%s_%s_%s_%d.%s", LocalDate.now(ZoneOffset.UTC).getYear(), type.name().toLowerCase(),
                from, to, System.currentTimeMillis(), format.name().toLowerCase());
        try {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(cfg.bucket()).build())) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(cfg.bucket()).build());
            }
            minio.putObject(PutObjectArgs.builder().bucket(cfg.bucket()).object(key)
                    .stream(new ByteArrayInputStream(file), file.length, -1).contentType(format.contentType).build());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not store the report in MinIO: " + e.getMessage());
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO reports (type, format, period_from, period_to, created_by, object_key, size_bytes)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, type.name(), format.name(), from, to, createdBy, key, file.length);
        return get(id);
    }

    /** Newest first. */
    public List<Report> list() {
        return jdbc.query("SELECT * FROM reports ORDER BY created_at DESC LIMIT ?", new DataClassRowMapper<>(Report.class),
                cfg.maxResults());
    }

    public Report get(long id) {
        return jdbc.query("SELECT * FROM reports WHERE id = ?", new DataClassRowMapper<>(Report.class), id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public InputStream open(Report report) {
        try {
            return minio.getObject(GetObjectArgs.builder().bucket(cfg.bucket()).object(report.objectKey()).build());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not read the report from MinIO: " + e.getMessage());
        }
    }

    /** Emails last week's four reports as PDFs. Off unless fleet.reports.email.enabled; the files are also kept like any other. */
    @Scheduled(cron = "${fleet.reports.email.cron}")
    public void emailWeeklyReports() {
        if (!cfg.email().enabled()) {
            return;
        }
        try {
            if (cfg.email().to() == null || cfg.email().to().isEmpty()) {
                throw new IllegalStateException("REPORTS_EMAIL_TO is empty");
            }
            LocalDate to = LocalDate.now(ZoneOffset.UTC).minusDays(1);
            LocalDate from = to.minusDays(6);
            List<Report> reports = new ArrayList<>();
            for (Type type : Type.values()) {
                reports.add(generate(type, Format.PDF, from, to, "weekly email"));
            }
            JavaMailSender sender = mail.getObject();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(cfg.email().from());
            helper.setTo(cfg.email().to().toArray(String[]::new));
            helper.setSubject("Fleet Twin weekly reports, " + from + " to " + to);
            helper.setText("Attached: fleet health, maintenance, driver behaviour and fuel reports for " + from + " to " + to
                    + ".\nThey are also listed on the Reports page of the dashboard.");
            for (Report report : reports) {
                try (InputStream in = open(report)) {
                    helper.addAttachment(report.fileName(), new ByteArrayResource(in.readAllBytes()), report.format().contentType);
                }
            }
            sender.send(message);
            log.info("Emailed the weekly reports to {}", cfg.email().to());
        } catch (Exception e) {
            log.warn("Weekly report email failed: {}", e.toString());
        }
    }
}
