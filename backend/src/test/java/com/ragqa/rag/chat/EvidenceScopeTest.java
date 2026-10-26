package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.domain.dto.ChatRequest;
import com.ragqa.rag.retrieval.EvidenceSelector.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceScopeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EvidenceScope scope = EvidenceScope.from("2026-10-17 重庆市合川区天气", context("Asia/Shanghai"));

    @ParameterizedTest
    @CsvSource({"Asia/Shanghai,今天重庆合川的天气如何,2026-10-16",
            "Asia/Tokyo,今天重庆合川的天气如何,2026-10-17",
            "America/New_York,明天重庆合川的天气如何,2026-10-17",
            "Asia/Tokyo,北京时间今天重庆合川的天气如何,2026-10-16",
            "Asia/Tokyo,2026-10-13 重庆合川天气,2026-10-13",
            "Asia/Tokyo,2026年10月17日重庆市合川区天气,2026-10-17"})
    void anchorsOnlyTheRequestedDateUsingTheFixedZone(String zone, String question, String expected) {
        var context = context(zone);
        assertThat(EvidenceScope.from(question, context).date()).hasToString(expected);
        assertThat(EvidenceScope.anchor(question, question, context)).contains(expected);
        assertThat(EvidenceScope.from(question, context).location()).isEqualTo("合川");
    }

    @ParameterizedTest
    @CsvSource({"下周重庆合川天气", "2026-02-30 重庆合川天气", "2026-10-17和2026-10-18重庆合川天气"})
    void refusesToGuessUnsupportedOrInvalidDateRanges(String question) {
        assertThat(EvidenceScope.from(question, context("Asia/Shanghai")).date()).isNull();
    }

    @Test
    void requestDateCannotBeUsedAsEvidenceForAnUndatedDayNumber() {
        Entry entry = new Entry(1, "合川天气", "https://example.com", "17日（今天）小雨23℃。13日（今天）阴19℃。");
        assertThat(scope.accepts(entry, fact("17日", "合川"), "17日（今天）小雨23℃。")).isFalse();
        assertThat(scope.accepts(entry, fact("13日", "合川"), "13日（今天）阴19℃。")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "合川天气,2026-10-17 合川 小雨23℃。,2026-10-17,合川,true",
            "合川天气,2026-10-13 合川 阴19℃。,2026-10-13,合川,false",
            "沙坪坝天气,2026-10-17 沙坪坝 小雨23℃。,2026-10-17,沙坪坝,false",
            "重庆天气,2026-10-17 重庆 阴19℃。,2026-10-17,重庆,false",
            "合川天气2026-10-17,13日（今天）阴19℃。,2026-10-17,合川,false",
            "合川天气,2026-10-17 合川 小雨23℃。,2026-10-17,北京,false",
            "2026年10月合川天气,合川17日 小雨23℃。,2026年10月,合川,true",
            "合川天气2026-10-17,2026-10-17 沙坪坝小雨23℃。,2026-10-17,合川,false",
            "2026年10月合川天气,13日 阴19℃。,2026年10月,合川,false"})
    void validatesDateLocationAndSameSourceProof(String title, String content, String dateProof,
            String placeProof, boolean accepted) {
        Entry entry = new Entry(1, title, "https://example.com", content);
        assertThat(scope.accepts(entry, fact(dateProof, placeProof), content)).isEqualTo(accepted);
    }

    @Test
    void anEarlierPublicationCanContainAValidForecastForTheTargetDay() {
        Entry entry = new Entry(1, "合川天气预报 发布于2026-10-13", "https://example.com",
                "2026-10-17 合川小雨，最高23℃，最低17℃。");
        assertThat(scope.accepts(entry, fact("2026-10-17", "合川"), entry.content())).isTrue();
        assertThat(scope.accepts(entry, fact("2026-10-13", "合川"), entry.content())).isTrue();
        // 无论发布时间为何，实际用于回答的引文仍须含目标日。
        assertThat(scope.accepts(entry, fact("2026-10-17", "合川"), "最高23℃，最低17℃。")).isFalse();
    }

    @Test
    void unrelatedDatesAndPlacesCannotBeBorrowedFromAnotherSource() {
        Entry entry = new Entry(2, "合川天气", "https://example.com", "13日（今天）阴19℃。");
        assertThat(scope.accepts(entry, fact("2026-10-17", "合川"), entry.content())).isFalse();
        assertThat(scope.accepts(entry, json.valueToTree(Map.of()), entry.content())).isFalse();
    }

    @Test
    void neverInfersLocationFromTheTimeZoneOrTreatsEveryTopicAsWeather() {
        assertThat(EvidenceScope.from("今天天气如何", context("Asia/Shanghai")).location()).isEmpty();
        assertThat(EvidenceScope.from("Java17发布日期", context("Asia/Shanghai")).weather()).isFalse();
        assertThat(EvidenceScope.from("weather in Paris today", context("Europe/Paris")).location()).isEqualTo("paris");
    }

    private com.fasterxml.jackson.databind.JsonNode fact(String dateProof, String placeProof) {
        return json.valueToTree(Map.of("appliesOn", "2026-10-17", "dateQuote", dateProof, "locationQuote", placeProof));
    }

    private static RequestTimeContext context(String zone) {
        var deadline = TaskDeadline.start(Duration.ofSeconds(90),
                Clock.fixed(Instant.parse("2026-10-16T15:30:00Z"), ZoneOffset.UTC), () -> 0L);
        return RequestTimeContext.from(new ChatRequest("天气", Instant.MIN, zone), deadline);
    }
}
