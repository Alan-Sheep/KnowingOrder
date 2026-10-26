package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 只检查人工标注和已知词法盲区；参考句不作为模型输出，不执行检索或真实外发。 */
class QueryRewriteDatasetTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE = "/eval/knowledge-rewrite-v1.json";

    record Dataset(String version, String description, String evaluationMode,
            String corpusResource, String corpusSha256, List<Sample> cases) { }
    record Sample(String id, String group, String risk, String question, String referenceAlternative,
            List<String> preservedAnchors, long spaceId, String expectedStatus,
            List<Long> expectedEvidenceIds, String gap, String unsafeAlternative,
            String unsafeReason, String unsafeContractOutcome) {
        @Override
        public String toString() { return id; }
    }

    @Test
    void freezesSixteenAnnotationsWithEightSynonymsAndEightBoundaryCases() throws Exception {
        Dataset dataset = dataset();
        assertThat(dataset.version()).isEqualTo("knowledge-rewrite-synthetic-v1");
        assertThat(dataset.evaluationMode()).isEqualTo("ANNOTATION_ONLY");
        assertThat(dataset.description()).contains("人工标注", "不是模型输出");
        assertThat(dataset.cases()).hasSize(16);
        assertThat(dataset.cases()).extracting(Sample::id).doesNotHaveDuplicates();
        assertThat(dataset.cases()).filteredOn(s -> s.group().equals("SYNONYM")).hasSize(8);
        assertThat(dataset.cases()).filteredOn(s -> s.group().equals("BOUNDARY")).hasSize(8);
        assertThat(dataset.cases()).extracting(Sample::group).containsOnly("SYNONYM", "BOUNDARY");
        assertThat(dataset.cases()).extracting(Sample::question).doesNotHaveDuplicates();
    }

    @Test
    void pinsOriginalCorpusBytesWithoutReplacingItsTwentyFourCases() throws Exception {
        Dataset dataset = dataset();
        assertThat(dataset.corpusResource()).isEqualTo("/eval/knowledge-v1.json");
        byte[] original = resource(dataset.corpusResource());
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)))
                .isEqualTo(dataset.corpusSha256())
                .isEqualTo("a953418cde8b982c72f0a8ff22190091c16d03bc7767b4cac91b16217afd8fe5");
        JsonNode corpus = JSON.readTree(original);
        assertThat(corpus.path("documents").size()).isEqualTo(20);
        assertThat(corpus.path("cases").size()).isEqualTo(24);
    }

    @Test
    void keepsAllEightRiskAxesAndBothDetectedAndUndetectedCounterexamples() throws Exception {
        List<Sample> boundaries = dataset().cases().stream()
                .filter(s -> s.group().equals("BOUNDARY")).toList();
        assertThat(boundaries).extracting(Sample::risk).containsExactlyInAnyOrder(
                "ENTITY", "AMBIGUITY", "TIME_LOCATION", "CHINESE_QUANTITY",
                "NEGATION_DROP", "UNIT", "NUMBER_BINDING", "NEGATION_SCOPE");
        assertThat(boundaries).extracting(Sample::unsafeContractOutcome)
                .containsOnly("ACCEPTED", "REJECTED").contains("ACCEPTED", "REJECTED");
        assertThat(boundaries).extracting(Sample::expectedStatus)
                .contains("NO_EVIDENCE", "PARTIAL", "CONFLICT", "ANSWERED");
    }

    @ParameterizedTest(name = "{0}：人工参考句及证据标注检查，非问答质量成绩")
    @MethodSource("samples")
    void checksReferenceEligibilityAndTraceableAnnotations(Sample sample) throws Exception {
        assertThat(sample.id()).matches("R(?:0[1-9]|1[0-6])");
        assertThat(sample.question()).isNotBlank();
        assertThat(sample.referenceAlternative()).isNotBlank();
        assertThat(sample.spaceId()).isEqualTo(401L);
        // 这里只检查参考句的可用格式；包含锚点仍不能证明数量绑定、否定作用域或语义等价。
        assertThat(QueryRewriteContract.alternativeQuery(sample.question(), output(sample.referenceAlternative())))
                .isEqualTo(sample.referenceAlternative());
        assertThat(sample.preservedAnchors()).doesNotHaveDuplicates();
        for (String anchor : sample.preservedAnchors()) {
            assertThat(anchor).isNotBlank();
            assertThat(sample.question()).contains(anchor);
            assertThat(sample.referenceAlternative()).contains(anchor);
        }
        assertThat(sample.expectedStatus()).isIn("ANSWERED", "PARTIAL", "NO_EVIDENCE", "CONFLICT");
        assertThat(sample.expectedEvidenceIds()).doesNotHaveDuplicates();
        if (sample.expectedStatus().equals("NO_EVIDENCE")) {
            assertThat(sample.expectedEvidenceIds()).isEmpty();
        } else {
            assertThat(sample.expectedEvidenceIds()).isNotEmpty();
        }
        if (sample.expectedStatus().equals("ANSWERED")) {
            assertThat(sample.gap()).isEmpty();
        } else {
            assertThat(sample.gap()).isNotBlank();
        }
        // 部分依据可只支撑一部分条件，不将有候选误标为完整回答。
        JsonNode documents = JSON.readTree(resource(dataset().corpusResource())).path("documents");
        Set<Long> accessible = new HashSet<>();
        documents.forEach(doc -> {
            if (doc.path("spaceId").asLong() == sample.spaceId() && doc.path("state").asText().equals("LIVE")) {
                accessible.add(doc.path("id").asLong());
            }
        });
        assertThat(accessible).containsAll(sample.expectedEvidenceIds());
        if (sample.group().equals("SYNONYM")) {
            assertThat(sample.risk()).isEqualTo("NONE");
            assertThat(sample.expectedStatus()).isEqualTo("ANSWERED");
            assertThat(sample.unsafeAlternative()).isEmpty();
            assertThat(sample.unsafeReason()).isEmpty();
            assertThat(sample.unsafeContractOutcome()).isEqualTo("NOT_APPLICABLE");
        } else {
            assertThat(sample.unsafeAlternative()).isNotBlank().isNotEqualTo(sample.referenceAlternative());
            assertThat(sample.unsafeReason()).isNotBlank();
            assertThat(sample.preservedAnchors()).isNotEmpty();
        }
    }

    @ParameterizedTest(name = "{0}：手写错误改写的词法检测边界，非模型输出")
    @MethodSource("boundaries")
    void recordsKnownLexicalBlindSpotsWithoutCallingThemSemanticSuccess(Sample sample) {
        JsonNode unsafe = output(sample.unsafeAlternative());
        if (sample.unsafeContractOutcome().equals("REJECTED")) {
            assertThatThrownBy(() -> QueryRewriteContract.alternativeQuery(sample.question(), unsafe))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.getCode()).isEqualTo(1000);
                        assertThat(error.getMessage()).isEqualTo("检索扩展数据校验未通过");
                        assertThat(error.getCause()).isNull();
                    });
        } else {
            assertThat(sample.unsafeContractOutcome()).isEqualTo("ACCEPTED");
            // 测试通过表示已知漏检仍可复现，绝不表示这条改写可以投入生产。
            assertThat(QueryRewriteContract.alternativeQuery(sample.question(), unsafe))
                    .isEqualTo(sample.unsafeAlternative());
        }
    }

    private static Stream<Sample> samples() throws IOException {
        return dataset().cases().stream();
    }

    private static Stream<Sample> boundaries() throws IOException {
        return samples().filter(sample -> sample.group().equals("BOUNDARY"));
    }

    private static JsonNode output(String alternative) {
        return JSON.createObjectNode().put("alternativeQuery", alternative);
    }

    private static Dataset dataset() throws IOException {
        return JSON.readValue(resource(RESOURCE), Dataset.class);
    }

    private static byte[] resource(String path) throws IOException {
        try (var input = QueryRewriteDatasetTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("缺少评估资源：" + path);
            return input.readAllBytes();
        }
    }
}
