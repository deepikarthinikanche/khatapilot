# KhataPilot

A cash co-pilot for solo home tutors: see who has paid, who to chase, and whether next month's bills are covered.

**Live Deployed URL:** [PASTE RENDER URL HERE once deployed]

## 1. Problem and persona

**Meera** (a fictional persona) is a solo home tutor in India. She has about 18 students who each pay a monthly fee, and a few monthly bills: rent, internet, electricity and study supplies.

Her pain is that late fee payments make it hard to know whether she can cover the bills this month. Chasing a late payer is awkward, so she often waits too long. KhataPilot keeps the invoices and payments in one place, forecasts the cash, and suggests who to chase and what to do about a shortfall.

All names, amounts and students in this repository are fictional sample data.

## 2. What it does

- **Dashboard (`/`):** KPI cards, a Cash Co-Pilot panel, a plain-language risk banner, a cash forecast chart, and a Rescue Plan.
- **Invoices (`/invoices`):** create, generate monthly invoices, mark as sent, record full or partial payments (UPI, Bank Transfer, Cash).
- **Reminders:** a Remind dialog with Gentle, Firm and Final message drafts and a **WhatsApp link** for each. The link opens your own WhatsApp with the text filled in. Nothing is sent automatically.
- **Students (`/students`):** each student has a payment reliability badge (On Time, 5–10 Days Late, 15+ Days Late).
- **Bills (`/bills`):** recurring monthly bills with an amount, due day and grace days. These feed the forecast.
- **Backtest (`/backtest`):** a calibration check of the forecast on synthetic history (see section 6).

## 3. How the forecast works

The forecast answers one question: **"If I do nothing, will my balance go below ₹0 before a bill is due?"**

It works by simulation:

1. **Monte Carlo simulation.** The app runs 1,000 random versions of the next 30, 60 or 90 days. In each version, every open invoice is paid on its due date plus a random delay. The delay is drawn from that student's own payment history. A student with fewer than 3 past payments borrows from the pool of all students' delays, weighted by how many payments they have.
2. **Bills.** Each recurring bill is paid on its due day plus its grace days. Grace days are assumed to be fully used.
3. **Balance paths.** For each day, the app sorts the 1,000 balances and reports:
   - **P10:** the worst path (only 1 in 10 runs ends lower).
   - **P50:** the most likely path.
   - **P90:** the best path (only 1 in 10 runs ends higher).
4. **Shortfall probability.** The share of the 1,000 runs whose balance dips below ₹0 before the target date (the next big bill). The risk banner uses this number.

The random generator uses a fixed seed (42), so the same data always gives the same result. Percentiles use the nearest-rank method.

**Risk banner.** The banner appears only when the shortfall probability is above 10%. Example text: "34% chance you can't cover Rent (₹12,000) on the 5th."

**Forecast chart.** The chart shows the P50 line, a shaded band from P10 to P90, a marker on the big bill, and a red zone where the P10 path is below ₹0.

## 4. How the Rescue Plan works

The Rescue Plan appears only when the shortfall probability is above 10%. Otherwise it says the cash buffer is healthy.

It builds a list of possible actions and ranks them greedily:

1. **Chase (free).** One card per overdue invoice. Expected recovery is the overdue balance times the likelihood the student pays, which is based on their average delay. It costs ₹0.
2. **Early-payment discount (costs money).** One card per student with overdue balances: 5% off if they pay now. The **60% acceptance rate is an estimate, not a measured value.** The expected cost is 60% × 5% × the overdue balance.
3. **Shift bill (shown honestly).** Pay a bill later by its grace period. In the current model this has **zero impact**, because the bill is already modelled at the end of its grace period. It is shown so you can see it was considered.

**Ranking.** Free actions come first, ordered by expected cash recovered. Paid actions come next, ordered by expected cash recovered per ₹ of cost. At most 6 cards are shown.

**Greedy pass.** The app applies the cards in rank order, re-runs the simulation after each one, and keeps a card only if it changes the shortfall probability. It stops once the risk is at or below 10%. If no combination gets there, the plan says so and shows the best result.

**Previews.** Every on/off combination of the cards is simulated in advance. Toggling a card on the dashboard draws the P50 path with that card applied, as a dashed "Preview" line. All figures are estimates.

## 5. Sample data

`SampleDataLoader` seeds the data on startup, only when the student table is empty.

- **18 fictional students**, each with a monthly fee and class notes.
- **12 past months** of invoices, all paid. Some pay on time, some 5–10 days late, some 15–20 days late, and some in two instalments.
- **Current month:** 9 PAID on time; 4 late by 5–10 days (2 SENT, 2 PARTLY_PAID); 3 overdue by 15+ days (2 SENT, 1 PARTLY_PAID); 2 DRAFT.
- **Recurring bills:**

| Bill | Amount | Due day | Grace days |
|---|---|---|---|
| Rent | ₹12,000 | 5th | 0 |
| Internet | ₹999 | 10th | 3 |
| Electricity | ₹1,800 | 15th | 5 |
| Supplies | ₹500 | 20th | 7 |

Invoice statuses: DRAFT, SENT, PARTLY_PAID and PAID are stored. OVERDUE is calculated: a SENT or PARTLY_PAID invoice past its due date with a balance left.

## 6. Tests and backtest

### Tests

Run the full suite with:

```powershell
.\mvnw.cmd test
```

Run `.\mvnw.cmd test` for the current count. The suites cover:

- **Forecast:** `CashForecastService` inputs, delay scenarios and grace-day timing.
- **Simulator:** `MonteCarloCashSimulator` percentiles, shortfall probability, and same-seed repeatability. Also `PaymentDelayModel`.
- **Risk banner:** `RiskBanner`, including the 10% threshold and message format.
- **Rescue:** `RescuePlanService` ranking, greedy application, previews and the healthy case.
- **Reminders and chase ranking:** message tones, WhatsApp link encoding, and chase order.
- **Invoices and bills:** status changes, partial payments, recurring invoice generation.
- **Web pages:** MockMvc tests for the dashboard, invoices, students, bills, backtest and health check.

### Backtest

`GET /backtest` evaluates the forecast on **12 synthetic monthly checkpoints** (1 January to 1 December 2025). At each checkpoint it uses only the data dated on or before that checkpoint:

- The forecast uses invoices issued and not yet paid by that date, the payment-delay history of invoices already paid, and the bills due in the next 30 days.
- The **predicted** value is the shortfall probability from the simulation (0–100%).
- The **actual** outcome is a shortfall if cash collected in the next 30 days minus bills due in those 30 days is below zero.

Checkpoints are grouped into five buckets by predicted probability: 0–10%, 10–30%, 30–50%, 50–70%, and 70–100%. Each bucket shows its checkpoint count, the mean predicted percentage, and the observed shortfall rate.

**This uses synthetic history only.** It is a check that the numbers hang together, not a measure of real-world accuracy. The predicted value includes the opening balance, but the actual outcome does not, so the two are not a like-for-like comparison.

## 7. Business viability (assumptions, not measured results)

All numbers in this section are assumptions for planning. None are measured.

- **Price:** ₹399 per month per tutor (assumed SaaS price).
- **Running cost:** close to zero. The app makes no LLM or paid API calls at runtime. It runs on free-tier hosting (Render) with an in-memory H2 database.
- **First 100 tutors:**
  - Join tutor groups on WhatsApp and demo the Bad-Day scenario.
  - Get referrals from local coaching centres.
  - Offer a free 30-day trial.
  - Onboard each tutor personally, with the founder helping set up their students and bills.
- **Expansion:** clinics and salons have the same pattern: recurring fees from regular clients, and fixed monthly bills.

## 8. 60-second Bad-Day demo

Start from the Bad-Day demo data. It has opening cash of ₹9,200, five overdue students, and Rent of ₹12,000 due in 4 days.

1. **0–10s:** Open the dashboard. Point at the risk banner.
2. **10–25s:** Show the forecast chart. Point at the red danger zone where the P10 path goes below ₹0.
3. **25–40s:** Open the Rescue Plan. Toggle one card on to show the preview line change.
4. **40–50s:** Open Remind for the top chase student. Show the WhatsApp link.
5. **50–60s:** Open `/backtest`. Point at the synthetic-history notice.

## 9. Assumptions and disclaimers

- All data is fictional sample data. Names, amounts and students are made up.
- The in-memory H2 database resets on every restart and every redeploy.
- The 60% discount acceptance rate and the payment-delay distributions are estimates.
- The backtest uses synthetic history, not real payments.
- This is not financial advice. Use your own judgement.
- WhatsApp links open your own WhatsApp app with the message filled in. No messages are sent automatically.
- No real payment processing happens. Payments are recorded by hand.

## 10. Run locally and deploy

### Requirements

- Windows with PowerShell.
- A JDK 25 install, with `JAVA_HOME` set to the JDK folder itself, not its `bin` folder.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
```

Setting `JAVA_HOME` in a session lasts only for that session. If it points to `...\jdk-25\bin`, Maven fails with "JAVA_HOME environment variable is not defined correctly".

The project targets Java 21 (`java.version` in `pom.xml`). The Maven wrapper (`mvnw.cmd`) downloads its own Maven, so Maven does not need to be on PATH.

### Run the app

```powershell
.\mvnw.cmd spring-boot:run
```

Open **http://localhost:8080**. The port comes from `server.port=${PORT:8080}` in `src/main/resources/application.properties`.

### Other commands

```powershell
# Run the tests
.\mvnw.cmd test

# Build the jar without tests (written to target\)
.\mvnw.cmd -DskipTests package
```

The jar is `target\khatapilot-0.1.0-SNAPSHOT.jar`.

### Deploy on Render (free tier, Docker)

1. Push the repo to GitHub. `target/` and `.env` are git-ignored; do not commit secrets.
2. In Render: **New > Web Service**, connect the repo. Runtime: **Docker**. Dockerfile path: `./Dockerfile`. Instance type: **Free**.
3. No environment variables are needed. Render sets `PORT`, and the app reads it.
4. Deploy. Data resets on every restart. Sample data is reseeded at startup.
5. The free tier sleeps after inactivity, so the first request after sleep is slow.

The Dockerfile uses a multi-stage build (Temurin 21) and runs the jar with a 384 MB heap, sized for Render's 512 MB free container.

**Not yet verified:** the Docker image has not been built or run in this environment. Before relying on the Render deploy, run `docker build -t khatapilot .` and `docker run -e PORT=8080 -p 8080:8080 khatapilot`, then open `/health`.

### Post-deploy checks

1. `<your-url>/health` returns HTTP 200 and shows Status UP.
2. `<your-url>/` shows KPI cards, the forecast chart and the Co-Pilot panel.
3. `/invoices` and `/students` list 18 students.
4. The logs show one "Seeded sample data" line at startup.
