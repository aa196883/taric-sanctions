package com.semsoft.taric.ingestion;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Reviewed, deterministic rules. Changes require rebuilding the consolidated state. */
public final class Rules {
    public static final String VERSION = "1";
    private static final Map<String, String> ACTIONS = new HashMap<>();
    private static final Set<String> IGNORED = Set.of(("103 105 106 107 109 110 112 115 117 119 122 123 141 142 143 145 146 147 "
        + "488 489 490 551 552 553 554 555 561 564 565 566 570 651 652 653 654 695 696").split(" "));
    private static final Set<String> SANCTIONS = Set.of(("0833/14 0765/06 0267/12 0264/12 1509/17 0036/12 0697/13 "
        + "0401/13 0284/17 0044/16 2063/17 1485/24 2309/22 0224/14 0813/25").split(" "));
    static {
        actions("prohibition", "277 278 705 706");
        actions("authorization", "473 478");
        actions("restriction", "465 467 474 475 476 708 722 738");
        actions("declaration", "464 481 482 483 484 485 495 496");
        actions("control", "410 415 479 707 709 710 711 712 713 714 715 716 717 718 719 724 725 726 728 730 731 732 734 735 736 737 740 745 746 747 748 749 750 751 755 760 761 762 763 765 766 767 768 769 770 775 780 781");
    }
    private static void actions(String action, String codes) {
        for (String code : codes.split(" ")) ACTIONS.put(code, action);
    }
    static String action(String type) {
        if (IGNORED.contains(type)) return null;
        if (!ACTIONS.containsKey(type)) throw new IllegalArgumentException("Unmapped measure type: " + type);
        return ACTIONS.get(type);
    }
    static Map<String, String> categories(String type, String label, String legal, Collection<String> texts) {
        var result = new TreeMap<String, String>();
        switch (type) {
            case "478" -> result.put("dual_use", "measure_type");
            case "717", "711" -> result.put("restricted_goods_or_technology", "measure_type");
            case "738" -> result.put("military_list", "measure_type");
            case "479", "761" -> result.put("chemical_control", "measure_type");
            case "705", "706", "708" -> result.put("torture_repression", "measure_type");
            default -> { }
        }
        if (Set.of("Regulation 0111/05", "Regulation 0273/04").contains(legal)) result.put("drug_precursor", "legal_reference");
        if (legal.equals("Regulation 0258/12")) result.put("firearms", "legal_reference");
        if (legal.equals("Regulation 0821/21") || legal.equals("Regulation 0428/09")) result.put("dual_use", "legal_reference");
        if (legal.startsWith("Regulation ") && SANCTIONS.contains(legal.substring(11))) result.put("sanction", "legal_reference");
        for (String text : texts) {
            String lower = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (lower.contains("common military list")) result.putIfAbsent("military_list", "footnote");
            if (lower.contains("drug precursor") || lower.matches(".*regulation.{0,25}\\b0?111/(?:05|2005)\\b.*"))
                result.putIfAbsent("drug_precursor", "footnote");
            if (lower.matches(".*regulation.{0,30}(?:2021/821|821/2021|428/2009).*")) result.putIfAbsent("dual_use", "footnote");
            if (lower.contains("restrictive measures") || lower.contains("sanctions")) result.putIfAbsent("sanction", "footnote");
        }
        return result;
    }
    private static final Pattern LEGAL = Pattern.compile("(Regulation|Decision|Directive) (\\d{1,4})/(\\d{2}|\\d{4})");
    static String legalUrl(String reference, LocalDate publication, Set<String> warnings) {
        var match = LEGAL.matcher(reference);
        if (match.matches()) {
            int year = Integer.parseInt(match.group(3));
            // A two-digit year is only unambiguous with the Legal basis publication date.
            if (year < 100 && publication != null) {
                int century = publication.getYear() / 100 * 100;
                year += century;
                if (year - publication.getYear() > 50) year -= 100;
                if (publication.getYear() - year > 50) year += 100;
            }
            if (year >= 1900 && publication != null && Math.abs(year - publication.getYear()) <= 1) {
                String kind = switch (match.group(1)) { case "Regulation" -> "R"; case "Decision" -> "D"; default -> "L"; };
                return "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:3" + year + kind + "%04d".formatted(Integer.parseInt(match.group(2)));
            }
        }
        warnings.add("EURLEX_UNRESOLVED: " + reference);
        return null;
    }
}
