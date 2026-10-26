package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.rag.retrieval.EvidenceSelector.Entry;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 天气事实的保守适用性边界：请求时间不是天气证据，网页的“今天”也不是绝对日期。
 * 只接受可从原文复核的完整日期与具体地点；不认识的日期范围、地名表达宁可报告不足。
 * 其他领域的时效性仍由评估模板判断，本类不冒充通用地理库或网页语义证明器。
 */
public record EvidenceScope(boolean weather, LocalDate date, String location) {

    private static final Pattern DATE = Pattern.compile("(?<!\\d)(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})(?:日)?(?!\\d)");
    private static final Pattern MONTH_DAY = Pattern.compile("(?<!\\d)(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern DAY = Pattern.compile("(?<!\\d)(\\d{1,2})日");
    private static final Pattern PLACE = Pattern.compile("([\\p{IsHan}]{2,32})(?:天气|气温)");
    private static final Pattern ENGLISH_PLACE = Pattern.compile("(?i)weather\\s+(?:in|for|at)\\s+([a-z]+(?:[ -][a-z]+){0,2})");
    private static final Map<String, Integer> RELATIVE = Map.of("今天", 0, "明天", 1, "昨天", -1,
            "后天", 2, "前天", -2, "today", 0, "tomorrow", 1, "yesterday", -1);

    /** 只解析问题，不用网页里的时间反向改变用户的目标日期。 */
    public static EvidenceScope from(String question, RequestTimeContext context) {
        boolean weather = question.toLowerCase(Locale.ROOT).matches("(?s).*(天气|气温|weather|天気|météo|wetter|clima).*");
        if (!weather) return new EvidenceScope(false, null, "");
        LocalDate reference = question.contains("北京时间")
                ? context.receivedAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate() : context.referenceDate();
        Set<LocalDate> dates = new LinkedHashSet<>();
        Matcher matcher = DATE.matcher(question);
        boolean invalid = false;
        while (matcher.find()) {
            try { dates.add(LocalDate.of(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)))); }
            catch (DateTimeException exception) { invalid = true; }
        }
        if (dates.isEmpty() && !invalid) {
            Matcher shortDate = MONTH_DAY.matcher(question);
            while (shortDate.find()) {
                try { dates.add(LocalDate.of(reference.getYear(), Integer.parseInt(shortDate.group(1)),
                        Integer.parseInt(shortDate.group(2)))); }
                catch (DateTimeException exception) { invalid = true; }
            }
            for (var relative : RELATIVE.entrySet()) {
                if (containsRelative(question, relative.getKey())) dates.add(reference.plusDays(relative.getValue()));
            }
        }
        if (dates.isEmpty() && !invalid && !question.matches("(?s).*(下周|上周|本周|下月|上月|周末|未来|过去|\\d+天|next|last|week).*$")) {
            dates.add(reference);
        }
        return new EvidenceScope(true, !invalid && dates.size() == 1 ? dates.iterator().next() : null, place(question));
    }

    /** 保留原文供展示；只给检索问题补上可复核的绝对日期。 */
    public static String anchor(String query, String original, RequestTimeContext context) {
        EvidenceScope scope = from(original, context);
        if (!scope.weather() || scope.date() == null) return query;
        String resolved = query;
        for (String relative : RELATIVE.keySet()) {
            String expression = relative.matches("[a-z]+") ? "(?i)\\b" + relative + "\\b" : relative;
            resolved = resolved.replaceAll(expression, scope.date().toString());
        }
        // 绝对日期已有明确含义；模型改写不能把用户明确日期换成另一天。
        resolved = DATE.matcher(resolved).replaceAll(scope.date().toString());
        if (!resolved.contains(scope.date().toString())) resolved += " 日期 " + scope.date();
        return resolved;
    }

    public Map<String, Object> modelData() {
        return Map.of("required", weather, "targetDate", date == null ? "" : date.toString(),
                "targetLocation", location, "dateProof", "完整年月日；或原文明示年月且引文含对应日，不能借用请求年份");
    }

    /** 三个附加字段均须来自当前同一来源，禁止仅凭模型声明“匹配”。 */
    public boolean accepts(Entry source, JsonNode fact, String quote) {
        if (!weather) return true;
        if (date == null || location.isBlank()) return false;
        String appliesOn = fact.path("appliesOn").asText("");
        String dateQuote = fact.path("dateQuote").asText("");
        String locationQuote = fact.path("locationQuote").asText("");
        if (!appliesOn.equals(date.toString()) || dateQuote.isBlank() || dateQuote.length() > 160
                || locationQuote.isBlank() || locationQuote.length() > 80) return false;
        String sourceText = source.title() + "\n" + source.content();
        if (!sourceText.contains(dateQuote) || !sourceText.contains(locationQuote)
                || !canonical(locationQuote).contains(canonical(location))
                || !canonical(quote).contains(canonical(location))) return false;
        // 日期证据必须和本条引文绑定；页面其他位置的日期不能为旧天气背书。
        boolean dateBound = hasDate(quote, date) || (source.title().contains(dateQuote) && hasDate(dateQuote, date));
        if (!dateBound) {
            String yearMonth = date.getYear() + "年" + date.getMonthValue() + "月";
            String isoMonth = date.toString().substring(0, 7);
            dateBound = (dateQuote.contains(yearMonth) || dateQuote.contains(isoMonth))
                    && (source.title().contains(dateQuote) || quote.contains(dateQuote))
                    && DAY.matcher(quote).results().anyMatch(match -> Integer.parseInt(match.group(1)) == date.getDayOfMonth());
        }
        return dateBound && !conflictingDay(quote);
    }

    public boolean conflictingDay(String text) {
        if (!weather || date == null) return false;
        Matcher full = DATE.matcher(text);
        while (full.find()) {
            if (!hasDate(full.group(), date)) return true;
        }
        return DAY.matcher(DATE.matcher(text).replaceAll("")).results()
                .anyMatch(match -> Integer.parseInt(match.group(1)) != date.getDayOfMonth());
    }

    public String gap() {
        return "缺少" + (date == null ? "可确定日期" : date.toString()) + "、"
                + (location.isBlank() ? "具体地点" : location) + "相匹配的天气证据，未采用旧日期或其他地区的天气";
    }

    private static boolean hasDate(String text, LocalDate expected) {
        return DATE.matcher(text).results().anyMatch(match -> Integer.parseInt(match.group(1)) == expected.getYear()
                && Integer.parseInt(match.group(2)) == expected.getMonthValue()
                && Integer.parseInt(match.group(3)) == expected.getDayOfMonth());
    }

    private static boolean containsRelative(String value, String word) {
        return word.matches("[a-z]+") ? Pattern.compile("(?i)\\b" + word + "\\b").matcher(value).find() : value.contains(word);
    }

    private static String place(String question) {
        String withoutDate = MONTH_DAY.matcher(DATE.matcher(question).replaceAll(" ")).replaceAll(" ");
        Matcher matcher = PLACE.matcher(withoutDate);
        if (matcher.find()) {
            String value = matcher.group(1).replaceFirst("^(请|帮我|查询|查一下|查查|看看|想知道|告诉我|一下)+", "")
                    .replaceAll("北京时间|今天|明天|昨天|后天|前天|当天|今日|日期", "")
                    .replaceAll("的$", "");
            // 直辖市前缀只用于区县级匹配，不把主城区天气冒充其他区县天气。
            value = value.replaceFirst("^(重庆|北京|上海|天津)市?(?=.{2,})", "");
            return canonical(value);
        }
        matcher = ENGLISH_PLACE.matcher(question);
        return matcher.find() ? canonical(matcher.group(1).replaceAll("(?i)\\s+(today|tomorrow|yesterday)$", "")) : "";
    }

    private static String canonical(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[省市区县\\s，,。.-]", "");
    }
}
