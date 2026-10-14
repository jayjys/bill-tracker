package com.billtracker.web;

import com.billtracker.bill.Bill;
import com.billtracker.bill.BillForm;
import com.billtracker.bill.BillService;
import com.billtracker.bill.BillSource;
import com.billtracker.bill.UtilityType;
import com.billtracker.ingest.BillIngestionService;
import com.billtracker.user.AppUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Controller
public class BillController {

    private final BillService bills;
    private final BillIngestionService ingestion;

    public BillController(BillService bills, BillIngestionService ingestion) {
        this.bills = bills;
        this.ingestion = ingestion;
    }

    @ModelAttribute("utilityTypes")
    UtilityType[] utilityTypes() {
        return UtilityType.values();
    }

    @GetMapping("/bills")
    public String list(AppUser user, @RequestParam(required = false) UtilityType type, Model model) {
        List<Bill> all = bills.list(user.getHousehold());
        model.addAttribute("bills", type == null ? all : all.stream().filter(b -> b.getUtilityType() == type).toList());
        model.addAttribute("selectedType", type);
        return "bills/list";
    }

    @GetMapping("/bills/new")
    public String newBill(@ModelAttribute("form") BillForm form) {
        return "bills/new";
    }

    @PostMapping("/bills")
    public String create(AppUser user, @Valid @ModelAttribute("form") BillForm form, BindingResult result,
                         RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return "bills/new";
        }
        bills.create(user, form);
        redirect.addFlashAttribute("flash", "Bill added.");
        return "redirect:/bills";
    }

    @GetMapping("/bills/{id}")
    public String edit(AppUser user, @PathVariable long id, Model model) {
        Bill bill = bills.get(user, id);
        model.addAttribute("bill", bill);
        model.addAttribute("form", BillForm.from(bill));
        return "bills/edit";
    }

    @PostMapping("/bills/{id}")
    public String update(AppUser user, @PathVariable long id, @Valid @ModelAttribute("form") BillForm form,
                         BindingResult result, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            model.addAttribute("bill", bills.get(user, id));
            return "bills/edit";
        }
        bills.update(user, id, form);
        redirect.addFlashAttribute("flash", "Bill saved.");
        return "redirect:/bills";
    }

    @PostMapping("/bills/{id}/delete")
    public String delete(AppUser user, @PathVariable long id, RedirectAttributes redirect) {
        bills.delete(user, id);
        redirect.addFlashAttribute("flash", "Bill deleted.");
        return "redirect:/bills";
    }

    @GetMapping("/upload")
    public String uploadForm() {
        return "upload";
    }

    @PostMapping("/upload")
    public String upload(AppUser user, @RequestParam("files") List<MultipartFile> files, Model model)
            throws IOException {
        List<BillIngestionService.Outcome> outcomes = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            outcomes.addAll(ingestion.ingestFile(user, file.getOriginalFilename(), file.getContentType(),
                    file.getBytes(), BillSource.UPLOAD));
        }
        model.addAttribute("outcomes", outcomes);
        return "upload";
    }

    @GetMapping("/bills/export.csv")
    public ResponseEntity<String> exportCsv(AppUser user) {
        StringBuilder csv = new StringBuilder(
                "statement_date,provider,utility_type,amount,due_date,period_start,period_end,usage,unit,status\n");
        for (Bill b : bills.list(user.getHousehold())) {
            csv.append(String.join(",",
                    str(b.getStatementDate()), csvField(b.getProvider()), b.getUtilityType().name(),
                    str(b.getAmount()), str(b.getDueDate()), str(b.getPeriodStart()), str(b.getPeriodEnd()),
                    str(b.getUsageQuantity()), csvField(b.getUsageUnit()), b.getStatus().name())).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bills.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(csv.toString());
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** Quote when needed, and neutralise leading =+-@ so spreadsheets don't run it as a formula. */
    static String csvField(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
