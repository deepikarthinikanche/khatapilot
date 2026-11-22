**Live Demo:** https://khatapilot.onrender.com
> Note: Render's free tier spins down after inactivity, so the first request after a period of idle time may take about 50 seconds to load.

# KhataPilot

A cash co-pilot for solo home tutors: see who has paid, who to chase, and whether next month's bills are covered.

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