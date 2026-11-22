# KhataPilot — Plan

## Goal
A back office and cash co-pilot for one solo home tutor: about 18 students, monthly fees, and a few monthly bills. Hackathon project.

## Constraints
- Free to run; no paid APIs; no live AI calls inside the app.
- Sample data only (no real names, phones or addresses).
- All amounts in rupees (INR).
- Work in small steps; run tests after every step.

## Stack
- Java 21, Spring Boot 3.3.5, Maven
- Spring Web, Spring Data JPA, Thymeleaf
- Embedded H2 in-memory database (sample data to be loaded on startup in a later step)
- JUnit 5 (via spring-boot-starter-test); Chart.js and small JavaScript for charts later

## Folder structure (current)
- pom.xml — Maven build
- src/main/java/com/khatapilot/KhataPilotApplication.java — Spring Boot entry point
- src/main/java/com/khatapilot/web/HealthController.java — serves GET /health
- src/main/resources/application.properties — app name, H2 settings, port 8080
- src/main/resources/templates/health.html — health-check page
- src/main/java/com/khatapilot/service/InvoiceService.java — invoice create, mark sent, record full/partial payment, payment history, OVERDUE via Invoice.effectiveStatus, idempotent monthly invoice generation
- src/main/java/com/khatapilot/service/RecurringBillService.java — create, list, delete recurring bills
- src/main/java/com/khatapilot/config/SampleDataLoader.java — startup CommandLineRunner, seeds only when the student table is empty (18 fictional students, 12 months of invoices/payments, recurring bills Rent/Internet/Electricity/Supplies)
- src/test/java/com/khatapilot/web/HealthControllerTest.java — MockMvc test for /health
- src/test/java/com/khatapilot/service/InvoiceServiceTest.java — JUnit 5 tests for status transitions, partial payments, recurring generation
- src/main/java/com/khatapilot/service/ReminderService.java — 3-tone payment reminder drafts (Gentle, Firm, Final) plus wa.me WhatsApp link builder
- src/main/java/com/khatapilot/service/ChaseRankingService.java — bad-day chase ranking (overdue balance × likelihood)
- src/test/java/com/khatapilot/service/ReminderServiceTest.java — JUnit 5 tests for tones, draft content, URL encoding
- src/test/java/com/khatapilot/service/ChaseRankingServiceTest.java — JUnit 5 tests for chase ranking
- src/main/resources/templates/home.html — dashboard urgency bar, Remind modal, chase alerts
- DashboardService.java and HomeController.java — extended for Prompt 3 (urgency buckets, chase ranking, reminder data)
- .gitignore

## Decisions
- Package root: com.khatapilot.
- H2 in-memory with create-drop: data resets on each start; H2 console disabled.
- Health page is the only page for now; no features built yet.
- Maven is not on PATH on the dev machine; a local copy lives at C:\Users\SIDDHARTH\tools\maven-local\apache-maven-3.9.9 (outside the project).

## Known environment issue
- The machine JAVA_HOME points to C:\Program Files\Java\jdk-25\bin; it must be C:\Program Files\Java\jdk-25 or Maven fails with "JAVA_HOME environment variable is not defined correctly". Fix it in system settings, and add Maven's bin folder to PATH.

## Progress log
- Step 1 — Maven skeleton (pom.xml, app class, application.properties, .gitignore). VERIFIED: `mvn -q -DskipTests package` exits 0; jar built to target/.
- Step 2 — Health-check page (HealthController + health.html). VERIFIED: app started; GET /health returned HTTP 200 and page shows KhataPilot and Status UP (raw HTML wraps UP in a strong tag).
- Step 3 — JUnit 5 test HealthControllerTest verified (`mvn test` passed: 1 run, 0 failures, 0 errors; Maven wrapper generated).
- Step 4 — PLAN.md created (this file).
- Step 5 — Service layer, sample data loader, JUnit tests (Prompt 1). VERIFIED: `.\mvnw.cmd test` (with JAVA_HOME set to C:\Program Files\Java\jdk-25 for that command) — Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS (InvoiceServiceTest 11, HealthControllerTest 1). Seeding observed once in a Spring test context (log: 'Seeded sample data: 18 students, 13 months of invoices, 236 payments, 4 recurring bills'); a standalone app start has not been observed yet.
- Step 6 — Final polish (current-month sample data, invoices layout, Copy Reminder, README). Current month seeded for all 18 students: 9 PAID, 4 late 5–10 days (2 SENT, 2 PARTLY_PAID), 3 late 15+ days (2 SENT, 1 PARTLY_PAID), 2 DRAFT; due dates are relative to today for the late groups, clamped to the 1st of the month. invoices/list.html: Create invoice and Generate monthly invoices forms side-by-side above the table, View / Pay button per row. invoices/list.html and invoices/detail.html: client-side Copy Reminder button for SENT, PARTLY_PAID and OVERDUE invoices. README.md created. VERIFIED: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'; .\mvnw.cmd test` — Tests run: 28, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS. NOT YET VERIFIED in a browser: the new layout, the Copy Reminder clipboard behaviour, and the current-month dashboard figures on a real app start.
- Step 7 — Prompt 3: 3-Tone Reminders, 1-Click WhatsApp Links & Bad-Day Ranking.
  - Tones: Gentle (friendly reminder), Firm (clear request with firm deadline), Final (last notice before escalation); each draft includes student name, billing-month batch note, balance in ₹, due date, days late.
  - Batch note = invoice billing month (Student has no batch field; no schema change).
  - WhatsApp link: https://wa.me/?text=<UTF-8 percent-encoded message, spaces as %20>; no API keys.
  - Bad-day ranking: score = overdue balance × likelihood, where likelihood falls as the student's average days late on paid invoices rises; top 2–3 shown as "Chase <Student> today: ₹<amount> overdue <N> days".
  - Dashboard: urgency bar for overdue dues in 1–7, 8–30, 31+ days; Remind button opens a modal with Gentle/Firm/Final selector, live preview, pure-JS Copy button, and Send on WhatsApp link.
  - VERIFIED: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'; .\mvnw.cmd test` — Tests run: 43, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS (RecurringBillTest 4, ChaseRankingServiceTest 6, InvoiceServiceTest 11, RecurringBillServiceTest 5, ReminderServiceTest 9, MainPagesControllerTest 7, HealthControllerTest 1).
  - NOT YET VERIFIED in a browser: the modal, the Copy button clipboard behaviour, the urgency bar rendering, and the WhatsApp link opening on a phone.
- Step 8 — Prompt 4: Cash forecast with grace days and Monte Carlo simulation (CashForecastService delegates to MonteCarloCashSimulator). Simulator reports P10/P50/P90 end balances (nearest-rank percentile) and shortfall probability (% of iterations ending below zero). Test fixes: CashForecastServiceTest.graceDaysPushOutflowIntoNextMonth expectations updated for 28 Dec bill + 5 grace days landing on 2 Jan (1 Feb 9000.00, 2 Feb 8000.00, 4 Mar 8000.00, 5 Mar 7000.00); MonteCarloCashSimulatorTest.sameSeedGivesIdenticalResultAndDifferentSeedChangesDays now uses a wider delay spread across four receivables so a different seed changes the daily result. VERIFIED: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'; .\mvnw.cmd test` — Tests run: 79, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.
- Step 9 — Prompt 5: Forecast chart and plain-language risk banner. HomeController runs CashForecastService forecasts for 30, 60 and 90 days from today (targeting the next upcoming recurring bill's due date plus grace days, seed 42) and passes daily worst (10th percentile), most-likely (50th) and best (90th) balances as the "forecasts" model attribute, plus riskBannerVisible, riskBannerMessage and shortfallPercent. RiskBanner (com.khatapilot.forecast) shows the banner only when shortfall probability is above 10%; message format "34% chance you can't cover Rent (₹12,000) on the 5th". home.html: banner at the top of the dashboard with a "See Rescue Plan" button linking to #rescue-plan; Rescue Plan placeholder card (id rescue-plan); Chart.js forecast chart with 30d/60d/90d tabs showing the most-likely line, a shaded worst-to-best band, a vertical big-bill marker, and a red danger zone where the worst case is below ₹0. Tests: RiskBannerTest (5 cases incl. threshold: 10.0 hidden, above 10 shown) and MainPagesControllerTest home-page test for the new model attributes. VERIFIED: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'; .\mvnw.cmd test` — Tests run: 85, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS. NOT YET VERIFIED in a browser: the Chart.js rendering, the tab switching, the danger-zone and bill-marker drawing, and the scroll to #rescue-plan.
- Step 10 — Prompt 6: Cloud deployment readiness. server.port changed to ${PORT:8080} in application.properties (H2 in-memory and startup SampleDataLoader seeding unchanged). Dockerfile added at project root: multi-stage, build stage eclipse-temurin:21-jdk-alpine (runs sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw package -DskipTests), runtime stage eclipse-temurin:21-jre-alpine copying target/*.jar to app.jar, EXPOSE 8080, ENTRYPOINT java -XX:+UseSerialGC -Xmx384m -Xms128m -Dserver.port=${PORT:8080} -jar app.jar (sized for Render's 512 MB free container). .gitignore now also excludes *.log and .env (target/ and .codebenders/ were already excluded). Secrets/personal-data scan: no real secrets or personal data found; only the empty H2 password (spring.datasource.password=) and sample fictional data. VERIFIED: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'; .\mvnw.cmd test` — Tests run: 85, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS. NOT VERIFIED: the Docker image has not been built or run (no Docker in this environment). Re-checked in a later run: Dockerfile, application.properties and .gitignore match the Prompt 6 spec; `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'; .\mvnw.cmd test` — Tests run: 85, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.
- Step 11 — Prompt 7 (What-If delay simulator) and Prompt 8 (Greedy rescue plan with preview toggles).
  - Dockerfile ENTRYPOINT no longer passes -Dserver.port (server.port=${PORT:8080} in application.properties is the single source).
  - What-If: CashForecastService.simulateDelay delays one student's receivables' due dates by N days (default 15); amounts unchanged. Overlaid on the forecast chart as dashed "After delay" series; all figures labelled as estimates.
  - RescuePlanService greedy ranking: zero-cost actions first, then expected cash recovered per ₹ of cost.
  - Candidates: CHASE (overdue balance × likelihood, ₹0 cost); DISCOUNT (5% off; 60% acceptance is an ESTIMATE; cost 0.6 × 5% × balance); SHIFT_BILL (zero impact under the existing model because bills are already modelled at end of grace; shown honestly).
  - Preview subsets (up to 64) are precomputed server-side; the home page toggles draw the chosen subset's p50 as a dashed "Preview" series.
  - NOT VERIFIED: tests and browser rendering (tests written by the orchestrator are pending the test run); Docker build not run.
- Step 12 — Prompts 12, 14, 17, 18: Backtest page (BacktestService, BacktestController, backtest.html, navbar Backtest link), mobile CSS in layout.html, README.md rewrite, BadDayDemoTest fix (demo payment history seeded in SampleDataLoader.loadBadDayDemo). Root cause of the 0.0 shortfall: the seeded history was present and visible to the forecast, but the delays were too short, so the 3000 student always paid before the Rent date and the on-time inflow could never fall below the 2800 gap. Fix: the SampleDataLoader.java delay table now makes students 1, 2 and 5 always pay after Rent, student 3 always before, and student 4 pay late one time in three, giving a shortfall of about 33% by design. Test status: NOT YET VERIFIED by a full run after this change unless you can confirm otherwise.




## Next steps
1. Start the app once and confirm the sample-data seeding log line appears once, and that a second start (or a non-empty DB) does not reseed.
2. Plan the next feature step before building it.

## Web UI & Cash Co-Pilot — status: done (tests pass: 28 run, 0 failures)
- Home dashboard (GET /): KPI cards (Expected Monthly Fees, Collected This Month, Pending/Overdue Dues, Upcoming Bills Total), Cash Co-Pilot panel (shortfall risk vs. recurring bills, late payers in 5–10 and 15+ day buckets), Chart.js charts for 12-month billed vs collected and invoice status breakdown. Served by HomeController, DashboardService.
- Invoices (GET /invoices, GET /invoices/{id}): filterable list by status and billing month; create invoice, generate monthly invoices for all students, mark draft as sent, record full or partial payments (UPI, Bank Transfer, Cash), payment history. Served by InvoiceController.
- Students (GET /students, POST /students) with payment reliability badge (On Time, 5–10 Days Late, 15+ Days Late). StudentController, StudentService, PaymentReliabilityService.
- Recurring bills (GET /bills, POST /bills, POST /bills/{id}/delete). BillController, RecurringBillService.
- Money shown in INR (₹) via InrFormatter.
- Tests: RecurringBillServiceTest, RecurringBillTest, MainPagesControllerTest (GET /, /invoices, /students, /bills; mark sent; partial and full payment).
- Assumptions: reliability badge uses the average days late of a student's last 3 paid invoices (<5 On Time, 5–14 "5–10 Days Late", 15+ "15+ Days Late"); Cash Co-Pilot shortfall flagged when collected this month, or collected plus pending dues, is below this month's recurring bills total. Generated invoices use issue date on the 1st and due date on the 10th of the billing month.
- Known gap: none of the reliability or cash rules have been checked against real payment data beyond the sample seed.


## Deployment (Render, free tier) — steps
1. Push the repo to GitHub (target/ and .env are git-ignored; do not commit secrets).
2. In Render: New > Web Service > connect the repo. Runtime: Docker. Dockerfile path: ./Dockerfile. Instance type: Free.
3. No environment variables are required. Render sets PORT automatically; the app reads it via server.port=${PORT:8080}.
4. Deploy. The app uses H2 in-memory, so data resets on every restart and redeploy; sample data is reseeded on startup.
5. Note: the free tier sleeps after inactivity; the first request after sleep is slow.

## Post-deploy checks
1. Open the Render URL + /health: expect HTTP 200 and the page showing KhataPilot, Status UP.
2. Open the Render URL + / (dashboard): expect KPI cards with figures, the cash co-pilot panel and charts rendered (not blank).
3. Open /invoices and /students: expect 18 students and invoices listed.
4. Check the Render logs: expect one line "Seeded sample data: 18 students, ..." at startup. Restarting the service should show the seed line again only because the in-memory DB is recreated, not duplicated.
5. Check the Render logs for OutOfMemoryError or repeated restarts; if memory is exceeded, lower -Xmx (e.g. 256m) in the Dockerfile ENTRYPOINT.
6. Confirm no sensitive values appear in the logs or pages.

## Deployment not yet verified
- Docker build and container run have not been tested locally; first verify with `docker build -t khatapilot .` and `docker run -e PORT=8080 -p 8080:8080 khatapilot`, then check /health, before relying on the Render deploy.

