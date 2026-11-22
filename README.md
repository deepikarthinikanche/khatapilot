# KhataPilot

**Live Full-Stack Demo:** https://khatapilot.onrender.com/index.html  
**Classic Server-Rendered View:** https://khatapilot.onrender.com/  

> **Note:** Render's free tier spins down after inactivity, so the first request after an idle period may take about 50 seconds to wake up.

A cash-flow co-pilot for solo home tutors in India: see who has paid, who to chase, and whether next month's bills are covered—powered by a deterministic Monte Carlo simulator and a greedy Rescue Plan optimizer.

---

## 1. Problem and Persona

**Meera** (a fictional persona) is a solo home tutor in India teaching **18 students** who each pay a monthly tuition fee, alongside four recurring monthly bills: **Rent**, **Internet**, **Electricity**, and **Study Supplies**.

Even when total monthly fees exceed total bills, staggered and late student payments make it hard to know whether cash on hand will cover upcoming fixed expenses. Chasing a late-paying parent or student feels awkward, so tutors often wait too long. KhataPilot keeps invoices, payments, and bills in one place, forecasts cash balances under payment-delay uncertainty, and recommends the highest-impact recovery steps.

*All names, phone numbers, amounts, and student histories in this repository are fictional sample data.*

---

## 2. What It Does

* **Home Dashboard (`/index.html`):**
  * **Shortfall Alert Banner:** Highlights liquidity risk whenever the chance of dipping below `₹0` before the next major bill exceeds **10%**, with a direct jump to the Rescue Plan.
  * **KPI Strip:** Live totals for **Expected monthly fees**, **Collected this month**, **Overdue**, and **Next big bill**.
  * **Cash Forecast & "What If..." Simulator:** Interactive Chart.js step-line chart (`30d` / `60d` horizons) showing the median balance (`P50`), `P10–P90` confidence band, dashed target rent line, and vertical bill due markers. Users can test hypothetical delays (`+7`, `+14`, `+30 days`, or `doesn't pay`) for any student and immediately compare before/after shortfall probabilities.
  * **Chase Today:** Visual aging bar (`1–7 days`, `8–14 days`, `15+ days`) and ranked overdue queue with one-click WhatsApp reminders.
  * **Rescue Plan:** Numbered, toggleable recovery actions that overlay a dashed green `Preview (P50)` trajectory onto the forecast chart.
  * **Value This Month:** Estimates recoverable overdue cash under **Low (30%)**, **Medium (50%)**, and **High (80%)** collection assumptions.
* **Invoices (`#/invoices`):** Create new invoices, filter by status (`DRAFT`, `SENT`, `PARTLY_PAID`, `OVERDUE`, `PAID`), and record full or partial payments via **UPI**, **Bank Transfer**, or **Cash**.
* **WhatsApp Reminders:** Opens a modal with **Gentle**, **Firm**, and **Final** message templates, one-click copy, and pre-filled `https://wa.me/?text=...` links that open in your own WhatsApp (nothing is sent automatically).
* **Students (`#/students`):** Manage the student roster, monthly fees, and payment notes.
* **Bills (`#/bills`):** Add new recurring bills or edit existing bill amounts, due days, and grace periods—feeding directly into the forecast engine.
* **Backtest (`#/backtest`):** Evaluates forecast calibration across 12 synthetic historical checkpoints.
* **Demo Controls:** One-click **`Load bad-day demo`** (simulates a liquidity crunch) and **`Reset`** (restores the healthy baseline dataset) in H2.

---

## 3. How the Forecast Works

The forecast answers one question: *"If I do nothing, will my balance go below ₹0 before my next major bill is due?"*

1. **Monte Carlo Simulation:** Runs **1,000** stochastic paths across the selected horizon (`30` or `60` days in the UI; up to `365` days via API). In each run, every open invoice (`SENT`, `PARTLY_PAID`, or `OVERDUE`) is paid on its due date plus a sampled delay drawn from that student's own historical paid invoices. Students with fewer than 3 past payments borrow from the payment-count-weighted pool of all students' delays.
2. **Bills:** Each recurring bill is modeled on its due day plus its grace days (assuming grace days are fully utilized).
3. **Balance Paths:** For each day, the engine sorts the 1,000 simulated balances using the nearest-rank method and reports:
   * **P10:** Worst-case decile path (only 1 in 10 runs ends lower).
   * **P50:** Most likely (median) path.
   * **P90:** Best-case decile path (only 1 in 10 runs ends higher).
4. **Shortfall Probability:** The share of the 1,000 runs whose balance dips below `₹0` before the next major bill's target date.
5. **Determinism:** The random number generator uses a fixed seed (`42`), so identical inputs always yield identical results.

---

## 4. How the Rescue Plan Works

The Rescue Plan generates recovery cards whenever shortfall probability exceeds **10%** (at `≤ 10%`, it confirms the cash buffer is healthy):

1. **Chase Overdue Invoices (₹0 Cost):** One card per overdue invoice. Expected recovery equals the overdue balance multiplied by payment likelihood derived from the student's average delay.
2. **Early-Payment Discount (5% Off):** Offered on overdue balances, modeled with an illustrative **60% acceptance rate** (`expectedCost = 0.60 × 0.05 × overdueBalance`).
3. **Shift Bill by Grace Period (Shown Honestly):** Evaluates deferring a bill within its grace window. Because bills are already modeled at the end of their grace periods, this has zero forecast impact (`Timing only · ₹0 cost`) and is displayed for transparency.

* **Ranking & Greedy Pass:** Free actions are ranked first by expected cash recovered, followed by paid discounts ranked by cash recovered per rupee spent (up to 6 cards). The engine applies cards in rank order, re-runs the simulation after each one, and keeps only cards that reduce shortfall probability until risk reaches `≤ 10%`.
* **Live Previews:** Toggling any combination of cards on the dashboard draws the adjusted `P50` trajectory as a dashed green preview line.

---

## 5. Sample Data

`SampleDataLoader` seeds the H2 database on startup whenever the student table is empty:

* **18 fictional students** with monthly fees and class notes.
* **12 past months of paid invoices** per student spanning on-time payers, 5–10 day late payers, 15–20 day late payers, and installment payers.
* **Current-month invoice mix:** 9 `PAID` on time, 4 late by 5–10 days (2 `SENT`, 2 `PARTLY_PAID`), 3 overdue by 15+ days (2 `SENT`, 1 `PARTLY_PAID`), and 2 `DRAFT`.
* **Recurring bills:**

| Bill | Amount | Due Day | Grace Days |
| :--- | ---: | :---: | :---: |
| Rent | ₹12,000 | 5th | 0 |
| Internet | ₹999 | 10th | 3 |
| Electricity | ₹1,800 | 15th | 5 |
| Supplies | ₹500 | 20th | 7 |

*Invoice statuses `DRAFT`, `SENT`, `PARTLY_PAID`, and `PAID` are persisted in H2; `OVERDUE` is computed dynamically for any `SENT` or `PARTLY_PAID` invoice past its due date with a remaining balance.*

---

## 6. REST API Endpoints

| Method & Path | Purpose |
| :--- | :--- |
| `GET /api/dashboard` | Returns `base` (`labels`, `p10`, `p50`, `p90`, `shortfallProb`), `baseline`, `preview` (when `whatIfStudentId`/`whatIfDelayDays` are passed), `rescueCards`, `chase`, `kpis`, and `monthly` trend data. |
| `POST /api/demo/bad-day` | Thread-safe (`DemoLock`) switch to the liquidity-crunch demo dataset. |
| `POST /api/demo/reset` | Thread-safe (`DemoLock`) restore of the default 18-student sample dataset. |
| `GET / POST /api/invoices` | List all invoices or create a new invoice in H2. |
| `POST /api/invoices/{id}/pay` | Record a full or partial payment (`UPI`, `BANK_TRANSFER`, or `CASH`). |
| `GET / POST /api/students` | List all students or add a new student in H2. |
| `GET / POST /api/bills` | List recurring bills or add a new recurring bill in H2. |
| `GET /api/backtest` | Run and return the 12 synthetic monthly checkpoints and 5 calibration buckets. |

---

## 7. Tests and Backtest

### Automated Test Suite
Run the full JUnit 5 and Spring MockMvc verification suite:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
.\mvnw.cmd test