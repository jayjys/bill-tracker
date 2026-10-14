package com.billtracker.web;

import com.billtracker.analytics.anomaly.AlertRepository;
import com.billtracker.analytics.forecast.ForecastService;
import com.billtracker.analytics.forecast.HouseholdForecast;
import com.billtracker.bill.Bill;
import com.billtracker.bill.BillService;
import com.billtracker.bill.BillSource;
import com.billtracker.bill.BillStatus;
import com.billtracker.bill.UtilityType;
import com.billtracker.ingest.BillIngestionService;
import com.billtracker.trends.TrendReport;
import com.billtracker.trends.TrendService;
import com.billtracker.user.AppUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** JSON API used by the dashboard chart; also usable from scripts with HTTP Basic auth. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final BillService bills;
    private final TrendService trends;
    private final BillIngestionService ingestion;
    private final ForecastService forecasts;
    private final AlertRepository alerts;

    public ApiController(BillService bills, TrendService trends, BillIngestionService ingestion,
                         ForecastService forecasts, AlertRepository alerts) {
        this.bills = bills;
        this.trends = trends;
        this.ingestion = ingestion;
        this.forecasts = forecasts;
        this.alerts = alerts;
    }

    @GetMapping("/bills")
    public List<BillDto> bills(AppUser user) {
        return bills.list(user.getHousehold()).stream().map(BillDto::from).toList();
    }

    @GetMapping("/bills/{id}")
    public BillDto bill(AppUser user, @PathVariable long id) {
        return BillDto.from(bills.get(user, id));
    }

    @GetMapping("/trends")
    public TrendReport trends(AppUser user, @RequestParam(defaultValue = "12") int months) {
        return trends.monthly(user.getHousehold(), Math.clamp(months, 1, 120));
    }

    @GetMapping("/forecast")
    public HouseholdForecast forecast(AppUser user) {
        return forecasts.forecast(user.getHousehold());
    }

    @GetMapping("/alerts")
    public List<AlertDto> alerts(AppUser user) {
        return alerts.openAlerts(user.getHousehold().getId()).stream()
                .map(a -> new AlertDto(a.getId(), a.getBillId(), a.getKind().name(), a.getSeverity().name(),
                        a.getTitle(), a.getDetail(), a.getScore()))
                .toList();
    }

    public record AlertDto(Long id, Long billId, String kind, String severity, String title, String detail,
                           BigDecimal score) {
    }

    @PostMapping("/bills/upload")
    public List<UploadResult> upload(AppUser user, @RequestParam("file") MultipartFile file) throws IOException {
        return ingestion.ingestFile(user, file.getOriginalFilename(), file.getContentType(), file.getBytes(),
                        BillSource.UPLOAD).stream()
                .map(o -> new UploadResult(o.name(), o.result(), o.message(),
                        o.bill() == null ? null : BillDto.from(o.bill())))
                .toList();
    }

    public record UploadResult(String name, BillIngestionService.Result result, String message, BillDto bill) {
    }

    public record BillDto(
            Long id,
            String provider,
            UtilityType utilityType,
            BigDecimal amount,
            LocalDate statementDate,
            LocalDate dueDate,
            LocalDate periodStart,
            LocalDate periodEnd,
            BigDecimal usageQuantity,
            String usageUnit,
            BigDecimal unitCost,
            BigDecimal costPerDay,
            Integer serviceDays,
            BillSource source,
            BillStatus status,
            BigDecimal parseConfidence) {

        static BillDto from(Bill b) {
            return new BillDto(b.getId(), b.getProvider(), b.getUtilityType(), b.getAmount(), b.getStatementDate(),
                    b.getDueDate(), b.getPeriodStart(), b.getPeriodEnd(), b.getUsageQuantity(), b.getUsageUnit(),
                    b.getUnitCost(), b.getCostPerDay(), b.getServiceDays(), b.getSource(), b.getStatus(),
                    b.getParseConfidence());
        }
    }
}
