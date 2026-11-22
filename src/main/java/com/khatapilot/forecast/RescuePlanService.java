package com.khatapilot.forecast;

import com.khatapilot.forecast.CashForecastService.ForecastInputs;
import com.khatapilot.service.ChaseRankingService;
import com.khatapilot.service.InrFormatter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Suggests actions that reduce the probability of a cash shortfall before the target date.
 *
 * <p>Candidate actions are built from the forecast inputs: chasing overdue invoices, offering an
 * early-payment discount per student, and shifting recurring bills. Candidates are ranked (free actions
 * first), capped at {@link #MAX_CANDIDATES} cards, and applied greedily while re-running the seeded
 * simulation. Every subset of the cards is also simulated so the UI can preview each combination.
 *
 * <p>Likelihoods and the discount acceptance rate are estimates, not measured values.
 */
@Service
public class RescuePlanService {

    public static final long SEED = 42L;
    public static final double SHORTFALL_THRESHOLD = RiskBanner.THRESHOLD_PERCENT;
    public static final int MAX_CANDIDATES = 6;
    public static final double DISCOUNT_RATE = 0.05;
    /** ESTIMATE: assumed share of students who accept the early-payment discount. Not measured. */
    public static final double DISCOUNT_ACCEPTANCE = 0.60;

    public static final String TYPE_CHASE = "CHASE";
    public static final String TYPE_DISCOUNT = "DISCOUNT";
    public static final String TYPE_SHIFT_BILL = "SHIFT_BILL";

    private static final String SHIFT_REASON_PREFIX = "Pay this bill later, by its grace period (";
    private static final String SHIFT_REASON_SUFFIX = " days) so the outflow lands after the dip; total owed is unchanged.";

    private static final InrFormatter INR = new InrFormatter();
    private static final BigDecimal ACCEPT = BigDecimal.valueOf(DISCOUNT_ACCEPTANCE);
    private static final BigDecimal REJECT = BigDecimal.ONE.subtract(ACCEPT);
    private static final BigDecimal DISCOUNT_FRACTION = BigDecimal.ONE.subtract(BigDecimal.valueOf(DISCOUNT_RATE));
    /** Share of a balance that the accepting part of a discounted receivable represents: 0.60 x 0.95. */
    private static final BigDecimal ACCEPT_SHARE = ACCEPT.multiply(DISCOUNT_FRACTION);

    /**
     * Free actions first (by expected recovery, descending), then paid actions (by recovery per unit of cost,
     * descending). Ties are broken by type, then id.
     */
    private static final Comparator<Action> RANK_ORDER = Comparator
            .comparing((Action a) -> a.cost().signum() > 0)
            .thenComparingDouble(RescuePlanService::rankKey)
            .thenComparing(Action::type)
            .thenComparing(Action::id);

    /** One suggested action. {@code expectedCashRecovered} and {@code cost} are rounded to 2dp. */
    public record Action(String id, String type, String title, long studentId, long invoiceId,
                         BigDecimal expectedCashRecovered, BigDecimal cost, String reason, boolean estimate) {
    }

    /** Simulated result for one combination of cards. {@code mask} bit i means card i is applied. */
    public record Preview(int mask, double shortfallPercent, List<Double> p50) {
        public Preview {
            p50 = List.copyOf(p50);
        }
    }

    /**
     * Result of the rescue planner.
     *
     * @param cards                ranked, capped list of actions; index i is bit i in {@code previews}
     * @param appliedActionIds     ids of the cards the greedy pass kept, in application order
     * @param previews             every subset of {@code cards}, keyed by bitmask
     * @param riskAfterAloneByCard shortfall percent when only card i is applied, aligned with {@code cards}
     * @param baselineP50          most-likely daily balances before any action
     */
    public record RescuePlan(boolean healthy, double baselineShortfallPercent, String healthyMessage,
                             List<Action> cards, List<String> appliedActionIds, double finalShortfallPercent,
                             boolean fullyResolved, String notice, Map<Integer, Preview> previews,
                             List<Double> riskAfterAloneByCard, List<Double> baselineP50) {
        public RescuePlan {
            cards = List.copyOf(cards);
            appliedActionIds = List.copyOf(appliedActionIds);
            previews = Collections.unmodifiableMap(new LinkedHashMap<>(previews));
            riskAfterAloneByCard = List.copyOf(riskAfterAloneByCard);
            baselineP50 = List.copyOf(baselineP50);
        }
    }

    private final CashForecastService cashForecastService;

    public RescuePlanService(CashForecastService cashForecastService) {
        this.cashForecastService = cashForecastService;
    }

    /** Builds the plan from live forecast inputs for the window starting at {@code today}. */
    public RescuePlan plan(LocalDate today, LocalDate targetDate, int horizonDays) {
        return plan(cashForecastService.inputs(today, horizonDays), today, targetDate, horizonDays);
    }

    /** Pure core: builds the plan from prebuilt inputs. Same inputs always give the same plan. */
    public RescuePlan plan(ForecastInputs in, LocalDate today, LocalDate targetDate, int horizonDays) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(today, "today");

        CashForecastResult baseline = simulate(in, today, targetDate, horizonDays);
        double baselineRisk = baseline.probabilityBelowZeroBeforeTarget();
        List<Double> baselineP50 = p50(baseline);

        if (baselineRisk <= SHORTFALL_THRESHOLD) {
            String message = String.format(Locale.ROOT,
                    "Cash buffer is healthy: shortfall risk is %.1f%% (at or below %.0f%%).",
                    baselineRisk, SHORTFALL_THRESHOLD);
            return new RescuePlan(true, baselineRisk, message, List.of(), List.of(), baselineRisk,
                    true, "", Map.of(), List.of(), baselineP50);
        }

        List<Action> candidates = new ArrayList<>();
        candidates.addAll(chaseCandidates(in, today));
        candidates.addAll(discountCandidates(in, today));
        candidates.addAll(shiftCandidates(in, today, targetDate));
        List<Action> cards = rank(candidates).stream().limit(MAX_CANDIDATES).toList();

        // Greedy: apply each card in ranked order, keep it only if it changes the simulated risk.
        ForecastInputs current = in;
        double risk = baselineRisk;
        List<String> applied = new ArrayList<>();
        for (Action card : cards) {
            if (risk <= SHORTFALL_THRESHOLD) {
                break;
            }
            ForecastInputs next = applyAction(current, card, today);
            double nextRisk = simulate(next, today, targetDate, horizonDays).probabilityBelowZeroBeforeTarget();
            if (Double.compare(nextRisk, risk) != 0) {
                current = next;
                risk = nextRisk;
                applied.add(card.id());
            }
        }
        boolean fullyResolved = risk <= SHORTFALL_THRESHOLD;
        String notice = fullyResolved
                ? ""
                : String.format(Locale.ROOT,
                "No combination of these actions can fully eliminate the shortfall risk; the best achievable is %.1f%%.",
                risk);

        // Preview every subset of the displayed cards.
        int n = cards.size();
        Map<Integer, Preview> previews = new LinkedHashMap<>();
        for (int mask = 0; mask < (1 << n); mask++) {
            CashForecastResult result = simulate(applyMask(in, cards, mask, today), today, targetDate, horizonDays);
            previews.put(mask, new Preview(mask, result.probabilityBelowZeroBeforeTarget(), p50(result)));
        }
        List<Double> riskAlone = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            riskAlone.add(previews.get(1 << i).shortfallPercent());
        }

        return new RescuePlan(false, baselineRisk, "", cards, applied, risk, fullyResolved, notice,
                previews, riskAlone, baselineP50);
    }

    /** Ranks actions: free actions first, then paid ones, with deterministic tie-breaks. */
    public static List<Action> rank(List<Action> actions) {
        return actions.stream().sorted(RANK_ORDER).toList();
    }

    private static double rankKey(Action a) {
        if (a.cost().signum() > 0) {
            return -a.expectedCashRecovered().doubleValue() / a.cost().doubleValue();
        }
        return -a.expectedCashRecovered().doubleValue();
    }

    private CashForecastResult simulate(ForecastInputs inputs, LocalDate today, LocalDate targetDate, int horizonDays) {
        return cashForecastService.run(inputs, today, horizonDays, targetDate,
                CashForecastService.DEFAULT_ITERATIONS, SEED);
    }

    private static List<Double> p50(CashForecastResult result) {
        return result.days().stream().map(d -> d.mostLikely().doubleValue()).toList();
    }

    // ---- candidate builders -------------------------------------------------------------------

    private static List<Action> chaseCandidates(ForecastInputs in, LocalDate today) {
        List<Action> out = new ArrayList<>();
        for (Receivable r : in.receivables()) {
            if (!isOverdue(r, today)) {
                continue;
            }
            double likelihood = ChaseRankingService.likelihoodForAverageDelay(
                    in.delayModel().meanDelay(r.studentId()));
            BigDecimal recovered = r.balance().multiply(BigDecimal.valueOf(likelihood))
                    .setScale(2, RoundingMode.HALF_UP);
            String reason = "Overdue " + INR.format(r.balance()) + "; student's history suggests ~"
                    + Math.round(likelihood * 100) + "% likely to pay (estimate).";
            out.add(new Action(TYPE_CHASE + ":" + r.invoiceId(), TYPE_CHASE,
                    "Chase invoice #" + r.invoiceId(), r.studentId(), r.invoiceId(),
                    recovered, zero(), reason, true));
        }
        return out;
    }

    private static List<Action> discountCandidates(ForecastInputs in, LocalDate today) {
        Map<Long, BigDecimal> overdueByStudent = new LinkedHashMap<>();
        for (Receivable r : in.receivables()) {
            if (isOverdue(r, today)) {
                overdueByStudent.merge(r.studentId(), r.balance(), BigDecimal::add);
            }
        }
        List<Action> out = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : overdueByStudent.entrySet()) {
            BigDecimal overdue = entry.getValue();
            BigDecimal recovered = overdue.multiply(ACCEPT).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cost = overdue.multiply(ACCEPT).multiply(BigDecimal.valueOf(DISCOUNT_RATE))
                    .setScale(2, RoundingMode.HALF_UP);
            String reason = "Offer " + Math.round(DISCOUNT_RATE * 100) + "% off if paid now; "
                    + Math.round(DISCOUNT_ACCEPTANCE * 100) + "% acceptance is an estimate; costs ~"
                    + INR.format(cost) + " in discount.";
            out.add(new Action(TYPE_DISCOUNT + ":" + entry.getKey(), TYPE_DISCOUNT,
                    "Offer early-payment discount to student #" + entry.getKey(),
                    entry.getKey(), 0L, recovered, cost, reason, true));
        }
        return out;
    }

    private static List<Action> shiftCandidates(ForecastInputs in, LocalDate today, LocalDate targetDate) {
        List<Action> out = new ArrayList<>();
        if (targetDate == null) {
            return out;
        }
        List<BillOutflow> bills = in.bills();
        for (int i = 0; i < bills.size(); i++) {
            BillOutflow bill = bills.get(i);
            // A shift moves the outflow by its grace days, so a grace-0 bill would get a card with no effect.
            if (bill.graceDays() <= 0) {
                continue;
            }
            if (bill.date() == null || bill.date().isBefore(today) || bill.date().isAfter(targetDate)) {
                continue;
            }
            String reason = SHIFT_REASON_PREFIX + bill.graceDays() + SHIFT_REASON_SUFFIX;
            out.add(new Action(TYPE_SHIFT_BILL + ":" + i, TYPE_SHIFT_BILL,
                    "Shift bill due " + bill.date(), 0L, 0L,
                    bill.amount().setScale(2, RoundingMode.HALF_UP), zero(), reason, false));
        }
        return out;
    }

    // ---- effects ------------------------------------------------------------------------------

    private static ForecastInputs applyMask(ForecastInputs in, List<Action> cards, int mask, LocalDate today) {
        ForecastInputs current = in;
        for (int i = 0; i < cards.size(); i++) {
            if (((mask >> i) & 1) == 1) {
                current = applyAction(current, cards.get(i), today);
            }
        }
        return current;
    }

    private static ForecastInputs applyAction(ForecastInputs in, Action action, LocalDate today) {
        return switch (action.type()) {
            case TYPE_CHASE -> applyChase(in, action, today);
            case TYPE_DISCOUNT -> applyDiscount(in, action, today);
            case TYPE_SHIFT_BILL -> applyShift(in, action);
            default -> in;
        };
    }

    /**
     * Moves one bill outflow later by its grace days. The bill's amount is unchanged; only its date moves.
     * The index is parsed from the action id, which is built as {@code TYPE_SHIFT_BILL + ":" + index}.
     */
    private static ForecastInputs applyShift(ForecastInputs in, Action action) {
        int index = Integer.parseInt(action.id().substring(TYPE_SHIFT_BILL.length() + 1));
        List<BillOutflow> bills = new ArrayList<>(in.bills());
        BillOutflow bill = bills.get(index);
        bills.set(index, new BillOutflow(bill.date().plusDays(bill.graceDays()), bill.amount(), bill.graceDays()));
        return new ForecastInputs(in.opening(), in.receivables(), bills, in.delayModel());
    }

    /** Moves the overdue receivable(s) of the invoice to be due today. */
    private static ForecastInputs applyChase(ForecastInputs in, Action action, LocalDate today) {
        List<Receivable> out = new ArrayList<>();
        for (Receivable r : in.receivables()) {
            if (r.invoiceId() == action.invoiceId() && isOverdue(r, today)) {
                out.add(new Receivable(r.invoiceId(), r.studentId(), r.balance(), today));
            } else {
                out.add(r);
            }
        }
        return withReceivables(in, out);
    }

    /**
     * Splits each overdue receivable of the student into an accepting part (due today, balance b x 0.6 x 0.95)
     * and a remaining part (original due date, balance b x 0.4).
     */
    private static ForecastInputs applyDiscount(ForecastInputs in, Action action, LocalDate today) {
        List<Receivable> out = new ArrayList<>();
        for (Receivable r : in.receivables()) {
            if (r.studentId() == action.studentId() && isOverdue(r, today)) {
                out.add(new Receivable(r.invoiceId(), r.studentId(),
                        r.balance().multiply(ACCEPT_SHARE).setScale(2, RoundingMode.HALF_UP), today));
                out.add(new Receivable(r.invoiceId(), r.studentId(),
                        r.balance().multiply(REJECT).setScale(2, RoundingMode.HALF_UP), r.dueDate()));
            } else {
                out.add(r);
            }
        }
        return withReceivables(in, out);
    }

    private static ForecastInputs withReceivables(ForecastInputs in, List<Receivable> receivables) {
        return new ForecastInputs(in.opening(), receivables, in.bills(), in.delayModel());
    }

    private static boolean isOverdue(Receivable r, LocalDate today) {
        return r.dueDate() != null && r.dueDate().isBefore(today) && r.balance().signum() > 0;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
}
