package com.billtracker.web;

import com.billtracker.TestFixtures;
import com.billtracker.bill.Bill;
import com.billtracker.bill.BillRepository;
import com.billtracker.analytics.anomaly.Alert;
import com.billtracker.analytics.anomaly.AlertKind;
import com.billtracker.analytics.anomaly.AlertRepository;
import com.billtracker.bill.BillStatus;
import com.billtracker.bill.UtilityAccountRepository;
import com.billtracker.bill.UtilityType;
import com.billtracker.household.HouseholdRepository;
import com.billtracker.household.HouseholdService;
import com.billtracker.insights.InsightQueries;
import com.billtracker.jobs.JobRun;
import com.billtracker.jobs.JobRunRepository;
import com.billtracker.jobs.JobRunner;
import com.billtracker.user.AppUser;
import com.billtracker.user.UserRepository;
import com.billtracker.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillTrackerWebTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService userService;
    @Autowired
    UserRepository users;
    @Autowired
    BillRepository bills;
    @Autowired
    UtilityAccountRepository accounts;
    @Autowired
    HouseholdService households;
    @Autowired
    HouseholdRepository householdRepo;
    @Autowired
    AlertRepository alerts;
    @Autowired
    InsightQueries insightQueries;
    @Autowired
    JobRunner jobRunner;
    @Autowired
    JobRunRepository jobRuns;

    private AppUser alice;
    private AppUser bob;

    @BeforeEach
    void setUp() {
        alice = userService.register(unique("alice"), PASSWORD, "Alice");
        bob = userService.register(unique("bob"), PASSWORD, "Bob");
    }

    // ---------- auth ----------

    @Test
    void pagesRequireLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/bills")).andExpect(status().is3xxRedirection());
    }

    @Test
    void apiReturns401InsteadOfRedirect() throws Exception {
        mvc.perform(get("/api/bills")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithCorrectAndWrongPassword() throws Exception {
        mvc.perform(formLogin("/login").userParameter("email").user(alice.getEmail()).password(PASSWORD))
                .andExpect(authenticated().withUsername(alice.getEmail()));
        mvc.perform(formLogin("/login").userParameter("email").user(alice.getEmail()).password("nope-nope"))
                .andExpect(unauthenticated());
    }

    @Test
    void registrationCreatesAccountAndSignsIn() throws Exception {
        String email = unique("new");
        mvc.perform(post("/register").with(csrf())
                        .param("email", email)
                        .param("password", "a-long-password")
                        .param("confirmPassword", "a-long-password"))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(email));
        assertThat(users.findByEmailIgnoreCase(email)).isPresent();
    }

    @Test
    void registrationValidatesInput() throws Exception {
        mvc.perform(post("/register").with(csrf())
                        .param("email", "not-an-email")
                        .param("password", "short")
                        .param("confirmPassword", "different"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "email", "password", "passwordConfirmed"));
    }

    @Test
    void registrationRejectsDuplicateEmailCaseInsensitively() throws Exception {
        mvc.perform(post("/register").with(csrf())
                        .param("email", alice.getEmail().toUpperCase())
                        .param("password", "a-long-password")
                        .param("confirmPassword", "a-long-password"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrorCode("form", "email", "taken"));
    }

    @Test
    void passwordsAreStoredHashed() {
        assertThat(users.findById(alice.getId()).orElseThrow().getPasswordHash())
                .isNotEqualTo(PASSWORD)
                .startsWith("$2");
    }

    @Test
    void formPostsRequireCsrfToken() throws Exception {
        mvc.perform(post("/bills").with(user(alice.getEmail())).param("amount", "10"))
                .andExpect(status().isForbidden());
    }

    // ---------- upload & bills ----------

    @Test
    void uploadingABillParsesAndStoresIt() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "pge.txt", "text/plain",
                TestFixtures.bytes("pge-electric.txt"));

        mvc.perform(multipart("/upload").file(file).with(csrf()).with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Parsed")));

        List<Bill> saved = billsOf(alice);
        assertThat(saved).singleElement().satisfies(b -> {
            assertThat(b.getProvider()).isEqualTo("PG&E");
            assertThat(b.getAmount()).isEqualByComparingTo("98.62");
            assertThat(b.getStatus()).isEqualTo(BillStatus.PARSED);
        });
    }

    @Test
    void uploadingTheSameFileTwiceIsDetectedAsDuplicate() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "gas.txt", "text/plain",
                TestFixtures.bytes("socalgas.txt"));
        mvc.perform(multipart("/upload").file(file).with(csrf()).with(user(alice.getEmail())));
        mvc.perform(multipart("/upload").file(file).with(csrf()).with(user(alice.getEmail())))
                .andExpect(content().string(containsString("Already imported")));

        assertThat(billsOf(alice)).hasSize(1);
    }

    @Test
    void unreadableUploadIsKeptButFlaggedForReview() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "notice.txt", "text/plain",
                TestFixtures.bytes("unreadable.txt"));
        mvc.perform(multipart("/upload").file(file).with(csrf()).with(user(alice.getEmail())))
                .andExpect(content().string(containsString("Needs review")));
        assertThat(billsOf(alice))
                .singleElement().extracting(Bill::getStatus).isEqualTo(BillStatus.NEEDS_REVIEW);
    }

    @Test
    void manualEntryValidatesAndSaves() throws Exception {
        mvc.perform(post("/bills").with(csrf()).with(user(alice.getEmail()))
                        .param("utilityType", "INTERNET"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "amount", "dated"));

        mvc.perform(post("/bills").with(csrf()).with(user(alice.getEmail()))
                        .param("provider", "Sonic")
                        .param("utilityType", "INTERNET")
                        .param("amount", "50.00")
                        .param("dueDate", "2026-09-15"))
                .andExpect(redirectedUrl("/bills"));
        assertThat(billsOf(alice))
                .singleElement().extracting(Bill::getStatus).isEqualTo(BillStatus.CONFIRMED);
    }

    @Test
    void usersCannotSeeOrModifyEachOthersBills() throws Exception {
        long id = createBillFor(alice);

        mvc.perform(get("/bills/" + id).with(user(bob.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(get("/api/bills/" + id).with(user(bob.getEmail()))).andExpect(status().isNotFound());
        mvc.perform(post("/bills/" + id + "/delete").with(csrf()).with(user(bob.getEmail())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/bills").with(user(bob.getEmail()))).andExpect(jsonPath("$", hasSize(0)));

        assertThat(bills.findById(id)).isPresent();
        mvc.perform(get("/bills/" + id).with(user(alice.getEmail()))).andExpect(status().isOk());
    }

    @Test
    void editingABillMarksItConfirmed() throws Exception {
        long id = createBillFor(alice);
        mvc.perform(post("/bills/" + id).with(csrf()).with(user(alice.getEmail()))
                        .param("provider", "PG&E")
                        .param("utilityType", "ELECTRIC")
                        .param("amount", "101.50")
                        .param("periodEnd", "2026-08-29"))
                .andExpect(redirectedUrl("/bills"));
        Bill bill = bills.findById(id).orElseThrow();
        assertThat(bill.getAmount()).isEqualByComparingTo("101.50");
        assertThat(bill.getStatus()).isEqualTo(BillStatus.CONFIRMED);
    }

    @Test
    void dashboardAndListRender() throws Exception {
        createBillFor(alice);
        mvc.perform(get("/").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Monthly cost by utility")))
                .andExpect(content().string(containsString("$98.62")));
        mvc.perform(get("/bills").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PG&amp;E")));
        mvc.perform(get("/").with(user(bob.getEmail())))
                .andExpect(content().string(containsString("No bills yet")));
    }

    @Test
    void csvExportNeutralisesFormulaInjection() throws Exception {
        mvc.perform(post("/bills").with(csrf()).with(user(alice.getEmail()))
                .param("provider", "=HYPERLINK(\"http://evil\")")
                .param("utilityType", "OTHER")
                .param("amount", "1.00")
                .param("dueDate", "2026-01-01"));
        mvc.perform(get("/bills/export.csv").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"'=HYPERLINK(\"\"http://evil\"\")\"")));
    }

    // ---------- JSON API ----------

    @Test
    void apiSupportsHttpBasicAndReturnsTrends() throws Exception {
        createBillFor(alice);
        mvc.perform(get("/api/trends").with(httpBasic(alice.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months", hasSize(12)))
                .andExpect(jsonPath("$.months[11]").value("2026-08"))
                .andExpect(jsonPath("$.series[0].type").value("ELECTRIC"))
                .andExpect(jsonPath("$.summary.latestMonthTotal").value(98.62));
    }

    @Test
    void apiUploadReturnsParsedBill() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "water.html", "text/html",
                TestFixtures.bytes("city-water.html"));
        mvc.perform(multipart("/api/bills/upload").file(file).with(httpBasic(alice.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].result").value("CREATED"))
                .andExpect(jsonPath("$[0].bill.utilityType").value("WATER"))
                .andExpect(jsonPath("$[0].bill.amount").value(68.05));
    }

    // ---------- inbound email webhook ----------

    @Test
    void inboundWebhookRejectsWrongSecret() throws Exception {
        mvc.perform(multipart("/api/inbound/email")
                        .param("to", "bills+" + alice.getInboundToken() + "@in.test")
                        .param("text", "Amount due $10.00")
                        .header("X-Inbound-Secret", "wrong"))
                .andExpect(status().isUnauthorized());
        assertThat(billsOf(alice)).isEmpty();
    }

    @Test
    void inboundWebhookRejectsUnknownRecipient() throws Exception {
        mvc.perform(multipart("/api/inbound/email")
                        .param("to", "bills+0000000000000000@in.test")
                        .param("text", "Amount due $10.00")
                        .header("X-Inbound-Secret", "test-secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void inboundWebhookWithMailgunStyleFieldsAndAttachment() throws Exception {
        MockMultipartFile pdfLike = new MockMultipartFile("attachment-1", "bill.txt", "text/plain",
                TestFixtures.bytes("coned-past-due.txt"));
        mvc.perform(multipart("/api/inbound/email").file(pdfLike)
                        .param("recipient", "Bills <bills+" + alice.getInboundToken() + "@in.test>")
                        .param("from", "Con Edison")
                        .param("subject", "Your bill")
                        .param("body-plain", "See attached")
                        .param("secret", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].result").value("CREATED"));

        assertThat(billsOf(alice))
                .singleElement().satisfies(b -> assertThat(b.getAmount()).isEqualByComparingTo("121.45"));
        assertThat(billsOf(bob)).isEmpty();
    }

    @Test
    void inboundWebhookWithPostmarkJsonUsesBodyWhenNoAttachments() throws Exception {
        String json = """
                {"To": "bills+%s@in.test", "FromName": "Xfinity", "Subject": "Your Xfinity bill",
                 "TextBody": "Your internet bill\\nAmount due: $80.00\\nDue date: 10/12/2026", "Attachments": []}
                """.formatted(alice.getInboundToken());
        mvc.perform(post("/api/inbound/email").contentType(MediaType.APPLICATION_JSON).content(json)
                        .header("X-Inbound-Secret", "test-secret"))
                .andExpect(status().isOk());

        assertThat(billsOf(alice)).singleElement().satisfies(b -> {
            assertThat(b.getProvider()).isEqualTo("Xfinity");
            assertThat(b.getAmount()).isEqualByComparingTo("80.00");
        });
    }

    @Test
    void rotatingTheForwardingAddressInvalidatesTheOldOne() throws Exception {
        String oldToken = alice.getInboundToken();
        mvc.perform(post("/settings/rotate-address").with(csrf()).with(user(alice.getEmail())))
                .andExpect(redirectedUrl("/settings"));
        mvc.perform(multipart("/api/inbound/email")
                        .param("to", "bills+" + oldToken + "@in.test")
                        .param("text", "Amount due $10.00")
                        .param("secret", "test-secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ---------- statements, line items, layout changes ----------

    @Test
    void combinedStatementBecomesTwoBillsWithLineItems() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "pge.txt", "text/plain",
                TestFixtures.bytes("pge-combined.txt"));
        mvc.perform(multipart("/upload").file(file).with(csrf()).with(user(alice.getEmail())))
                .andExpect(content().string(containsString("Split into 2 bills")));

        List<Bill> saved = billsOf(alice);
        assertThat(saved).extracting(Bill::getUtilityType)
                .containsExactlyInAnyOrder(UtilityType.ELECTRIC, UtilityType.GAS);
        assertThat(accounts.findByHouseholdId(alice.getHousehold().getId())).hasSize(2);

        long gasId = saved.stream().filter(b -> b.getUtilityType() == UtilityType.GAS).findFirst().orElseThrow().getId();
        mvc.perform(get("/bills/" + gasId).with(user(alice.getEmail())))
                .andExpect(content().string(containsString("Gas Procurement Charges")))
                .andExpect(content().string(containsString("Line items")));
    }

    @Test
    void providerLayoutChangeRaisesAnAlert() throws Exception {
        upload(alice, "pge-electric.txt");
        upload(alice, "pge-combined.txt");
        MockMultipartFile changed = new MockMultipartFile("files", "pge-new.txt", "text/plain", """
                PG&E
                Account No: 1234567890-5
                Balance to pay: $98.62
                Total Usage 412 kWh
                """.getBytes());
        mvc.perform(multipart("/upload").file(changed).with(csrf()).with(user(alice.getEmail())));

        assertThat(alerts.findByHouseholdIdAndDismissedAtIsNullOrderByCreatedAtDesc(alice.getHousehold().getId()))
                .extracting(Alert::getKind).contains(AlertKind.PARSER_FORMAT_CHANGE);
    }

    // ---------- households / roommates ----------

    @Test
    void roommateWhoSignsUpWithInviteSeesTheSameBills() throws Exception {
        long id = createBillFor(alice);
        String code = households.ensureInvite(alice).getInviteCode();

        mvc.perform(get("/register").param("invite", code))
                .andExpect(content().string(containsString("invited to join")));
        String carol = unique("carol");
        mvc.perform(post("/register").with(csrf())
                        .param("email", carol).param("password", "a-long-password")
                        .param("confirmPassword", "a-long-password").param("invite", code))
                .andExpect(redirectedUrl("/"));

        mvc.perform(get("/bills/" + id).with(user(carol))).andExpect(status().isOk());
        mvc.perform(get("/api/bills").with(user(carol))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/bills/" + id).with(user(bob.getEmail()))).andExpect(status().isNotFound());
        assertThat(householdRepo.findById(alice.getHousehold().getId()).orElseThrow().getOccupants()).isEqualTo(2);
    }

    @Test
    void joiningBringsASoleMembersBillsAndMergesDuplicates() throws Exception {
        upload(alice, "pge-electric.txt");
        upload(bob, "pge-electric.txt");   // the same bill, uploaded by both
        upload(bob, "socalgas.txt");
        String code = households.ensureInvite(alice).getInviteCode();
        long bobsOldHousehold = bob.getHousehold().getId();

        mvc.perform(get("/join/" + code).with(user(bob.getEmail())))
                .andExpect(content().string(containsString("Join")));
        mvc.perform(post("/join/" + code).with(csrf()).with(user(bob.getEmail())))
                .andExpect(redirectedUrl("/household"));

        assertThat(billsOf(alice)).hasSize(2)
                .extracting(Bill::getProvider).containsExactlyInAnyOrder("PG&E", "SoCalGas");
        assertThat(householdRepo.findById(bobsOldHousehold)).isEmpty();
        mvc.perform(get("/household").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("Bob")));
    }

    @Test
    void invalidInviteIsRejected() throws Exception {
        mvc.perform(get("/join/not-a-code").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("doesn't work")));
        mvc.perform(get("/join/not-a-code")).andExpect(status().isOk());
    }

    @Test
    void signedOutInviteLinkGoesToRegistration() throws Exception {
        String code = households.ensureInvite(alice).getInviteCode();
        mvc.perform(get("/join/" + code)).andExpect(redirectedUrl("/register?invite=" + code));
    }

    @Test
    void leavingStartsAFreshHouseholdAndKeepsBillsWithTheOldOne() throws Exception {
        createBillFor(alice);
        households.join(bob, households.ensureInvite(alice).getInviteCode());

        mvc.perform(post("/household/leave").with(csrf()).with(user(bob.getEmail())))
                .andExpect(redirectedUrl("/household"));

        assertThat(billsOf(bob)).isEmpty();
        assertThat(billsOf(alice)).hasSize(1);
    }

    @Test
    void householdSettingsGeocodeTheCity() throws Exception {
        mvc.perform(post("/household").with(csrf()).with(user(alice.getEmail()))
                        .param("name", "Flat 2").param("occupants", "3").param("city", "Chicago"))
                .andExpect(redirectedUrl("/household"));
        var h = householdRepo.findById(alice.getHousehold().getId()).orElseThrow();
        assertThat(h.getOccupants()).isEqualTo(3);
        assertThat(h.getCity()).isEqualTo("Chicago, Testland");
        assertThat(h.hasLocation()).isTrue();

        mvc.perform(post("/household").with(csrf()).with(user(alice.getEmail()))
                        .param("name", "Flat 2").param("occupants", "3").param("city", "Nowhere"))
                .andExpect(flash().attribute("error", containsString("Couldn't find")));
    }

    // ---------- insights, alerts, jobs ----------

    @Test
    void everyInsightQueryRunsOnTheSchema() throws Exception {
        upload(alice, "pge-electric.txt");
        upload(alice, "pge-combined.txt");
        upload(alice, "socalgas.txt");

        List<InsightQueries.Result> results = insightQueries.run(
                householdRepo.findById(alice.getHousehold().getId()).orElseThrow(), LocalDate.of(2026, 9, 15));

        assertThat(results).hasSize(8).allSatisfy(r -> assertThat(r.error()).as(r.query().id()).isNull());
        assertThat(results).filteredOn(r -> r.query().id().equals("charges_by_category")).singleElement()
                .satisfies(r -> assertThat(r.rows()).isNotEmpty());
        mvc.perform(get("/insights").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Where the money goes")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Query failed"))));
    }

    @Test
    void alertsCanBeDismissedOnlyByTheirHousehold() throws Exception {
        upload(alice, "pge-electric.txt");
        Bill bill = billsOf(alice).getFirst();
        Alert alert = alerts.save(new Alert(bill, AlertKind.USAGE_SPIKE, Alert.Severity.WARNING, "Spike", "test"));

        mvc.perform(post("/alerts/" + alert.getId() + "/dismiss").with(csrf()).with(user(bob.getEmail())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/alerts").with(user(alice.getEmail()))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(post("/alerts/" + alert.getId() + "/dismiss").with(csrf()).with(user(alice.getEmail())))
                .andExpect(redirectedUrl("/insights#alerts"));
        mvc.perform(get("/api/alerts").with(user(alice.getEmail()))).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void forecastApiAndSystemPageWork() throws Exception {
        upload(alice, "pge-electric.txt");
        mvc.perform(get("/api/forecast").with(user(alice.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped", hasSize(1)));
        mvc.perform(post("/system/refresh").with(csrf()).with(user(alice.getEmail())))
                .andExpect(redirectedUrl("/system"));
        mvc.perform(get("/system").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("manual-refresh")));
    }

    @Test
    void jobRunnerIsolatesFailuresAndRecordsTheRun() {
        JobRun run = jobRunner.run("test-job", List.of(1, 2, 3), i -> {
            if (i == 2) {
                throw new IllegalStateException("boom");
            }
            return 10;
        });
        assertThat(run.getStatus()).isEqualTo(JobRun.Status.PARTIAL);
        assertThat(run.getItems()).isEqualTo(20);
        assertThat(run.getMessage()).contains("3 processed").contains("1 failed").contains("boom");
        assertThat(jobRuns.findById(run.getId())).isPresent();
    }

    // ---------- helpers ----------

    private long createBillFor(AppUser owner) throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "pge.txt", "text/plain",
                TestFixtures.bytes("pge-electric.txt"));
        mvc.perform(multipart("/upload").file(file).with(csrf()).with(user(owner.getEmail())));
        return billsOf(owner).getFirst().getId();
    }

    private List<Bill> billsOf(AppUser user) {
        return bills.findByHouseholdIdOrderByCreatedAtDesc(
                users.findById(user.getId()).orElseThrow().getHousehold().getId());
    }

    private void upload(AppUser user, String fixture) throws Exception {
        mvc.perform(multipart("/upload").file(new MockMultipartFile("files", fixture, "text/plain",
                TestFixtures.bytes(fixture))).with(csrf()).with(user(user.getEmail())));
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }
}
