package com.ragqa.rag.retrieval;

import com.ragqa.domain.dto.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSelectorTest {
    private final EvidenceSelector selector = new EvidenceSelector();

    @Test
    void deduplicatesFragmentsWithoutDiscardingMeaningfulQueryParametersOrPathCase() {
        List<EvidenceSelector.Entry> selected = selector.select(List.of(
                result("https://example.com/Doc?id=1#one", 0.9),
                result("https://EXAMPLE.com/Doc?id=1#two", 0.8),
                result("https://example.com/Doc?id=2", 0.7),
                result("https://example.com/doc?id=1", null)));
        assertThat(selected).hasSize(3);
        assertThat(selected).extracting(EvidenceSelector.Entry::id).containsExactly(1, 2, 3);
        assertThat(selected.get(0).modelData()).doesNotContainKey("url");
    }

    @Test
    void skipsUnsafeLinksAndBoundsPerDocumentAndTotalEvidenceWithoutSplittingSurrogates() {
        List<SearchResult> raw = new ArrayList<>();
        raw.add(result("javascript:alert(1)", 1.0));
        raw.add(result("https://user:password@example.com/private", 1.0));
        for (int index = 0; index < 20; index++) {
            raw.add(new SearchResult("😀".repeat(200), "https://example.com/" + index,
                    "😀".repeat(1000), null));
        }
        List<EvidenceSelector.Entry> selected = selector.select(raw);
        assertThat(selected).hasSize(5).allSatisfy(entry -> {
            assertThat(entry.title().codePointCount(0, entry.title().length())).isEqualTo(160);
            assertThat(entry.content().codePointCount(0, entry.content().length())).isEqualTo(800);
            assertThat(entry.content()).endsWith("😀");
        });
        assertThat(selected.stream().mapToInt(entry -> entry.content().codePointCount(0, entry.content().length())).sum())
                .isEqualTo(4000);
        assertThat(selector.select(List.of())).isEmpty();
    }

    private static SearchResult result(String url, Double score) {
        return new SearchResult("标题", url, "有效资料正文", score);
    }
}
