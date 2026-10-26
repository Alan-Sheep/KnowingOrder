package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 只测离线契约，不把手写备选句当作模型改写质量或权限链验收。 */
class QueryRewriteContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void returnsOneAlternativeWithoutReplacingTheOriginalQuestion() {
        String original = "夜里多干活的伙食津贴一天最多能领几块钱？";
        assertThat(QueryRewriteContract.alternativeQuery(original, output("  加班餐补每人每天最多能领取多少？  ")))
                .isEqualTo("加班餐补每人每天最多能领取多少？");
        assertThat(original).isEqualTo("夜里多干活的伙食津贴一天最多能领几块钱？");
    }

    @Test
    void acceptsExistingMultilineQuestionButReturnsASingleLineQuery() {
        assertThat(QueryRewriteContract.alternativeQuery("餐补怎么申请？\r\n\t流程是什么？", output("餐补申请流程")))
                .isEqualTo("餐补申请流程");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "[]", "\"餐补\"", "{}", "{\"query\":\"餐补\"}",
            "{\"alternativeQuery\":null}", "{\"alternativeQuery\":12}", "{\"alternativeQuery\":false}",
            "{\"alternativeQuery\":[\"餐补\",\"报销\"]}", "{\"alternativeQuery\":{\"query\":\"餐补\"}}"})
    void rejectsMissingOrMistypedFields(String raw) throws Exception {
        JsonNode node = raw == null ? null : json.readTree(raw);
        assertRejected("餐补怎么申请？", node);
    }

    @ParameterizedTest
    @ValueSource(strings = {"enterpriseId", "spaceId", "memberId", "userId", "sql", "url", "tool_calls", "queries"})
    void rejectsEveryAdditionalControlField(String field) {
        var node = output("餐补申请流程").put(field, "fake-control-value");
        assertRejected("餐补怎么申请？", node);
    }

    @ParameterizedTest
    @MethodSource("invalidAlternatives")
    void rejectsBlankOversizedOrInvisibleOutput(String alternative) {
        assertRejected("餐补怎么申请？", output(alternative));
    }

    static Stream<Arguments> invalidAlternatives() {
        return Stream.of("", " \t", "\u00a0", "知".repeat(501), "😀".repeat(501),
                "餐补\n申请", "餐补\r申请", "餐补\t申请", "餐补\u0000申请", "餐补\u0085申请",
                "餐补\u200b申请", "餐补\u202e申请", "餐补\u2028申请", "餐补\u2029申请",
                "餐补\ud800申请", "餐补\udc00申请").map(Arguments::of);
    }

    @ParameterizedTest
    @ValueSource(strings = {"知", "😀"})
    void acceptsExactlyFiveHundredCodePoints(String character) {
        String alternative = character.repeat(500);
        assertThat(QueryRewriteContract.alternativeQuery("餐补怎么申请？", output(alternative)))
                .isEqualTo(alternative);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\u00a0", "原文\u0000", "原文\u202e"})
    void rejectsInvalidOriginalWithoutEchoingIt(String original) {
        assertRejected(original, output("餐补申请流程"));
    }

    @Test
    void respectsExistingTwelveHundredUtf16UnitInputLimit() {
        assertThat(QueryRewriteContract.alternativeQuery("知".repeat(1200), output("知识检索")))
                .isEqualTo("知识检索");
        assertRejected("知".repeat(1201), output("知识检索"));
    }

    @ParameterizedTest
    @MethodSource("constraintDrift")
    void rejectsAddedRemovedOrChangedNumericAndLexicalConstraints(String original, String alternative) {
        assertRejected(original, output(alternative));
    }

    static Stream<Arguments> constraintDrift() {
        return Stream.of(
                Arguments.of("额度30元怎么申请？", "额度300元申请"),
                Arguments.of("额度30元怎么申请？", "额度申请"),
                Arguments.of("餐补怎么申请？", "餐补30元申请"),
                Arguments.of("30元和30元分别适用什么？", "30元适用范围"),
                Arguments.of("比例30%怎么计算？", "比例30计算方式"),
                Arguments.of("金额30.5怎么申请？", "金额30.6申请"),
                Arguments.of("额度-30怎么处理？", "额度30处理"),
                Arguments.of("2026-10-18的制度？", "2026-10-19的制度"),
                Arguments.of("未审批能报销吗？", "审批报销条件"),
                Arguments.of("不得报销交通费用？", "报销交通费用"),
                Arguments.of("补贴不能超过额度？", "补贴不得超过额度"),
                Arguments.of("最多领多少补贴？", "补贴金额"),
                Arguments.of("至少工作多久？", "最多工作多久"),
                Arguments.of("餐补能申请吗？", "餐补不允许申请"),
                Arguments.of("Do not submit twice", "submit twice"),
                Arguments.of("at most 2 requests", "at least 2 requests")
        );
    }

    @Test
    void preservesNumberMultiplicityAndConstraintsEvenWhenWordOrderChanges() {
        assertThat(QueryRewriteContract.alternativeQuery("最多30元和20元各适用谁？",
                output("20元与30元最多额度的适用人员")))
                .isEqualTo("20元与30元最多额度的适用人员");
        assertThat(QueryRewriteContract.alternativeQuery("DO NOT submit 2 requests",
                output("do not send 2 requests"))).isEqualTo("do not send 2 requests");
    }

    @Test
    void identicalQueryIsValidButDoesNotPromiseAdditionalRecall() {
        assertThat(QueryRewriteContract.alternativeQuery("餐补怎么申请？", output("餐补怎么申请？")))
                .isEqualTo("餐补怎么申请？");
    }

    @Test
    void lexicalCheckCannotProveLocationTimeOrChineseNumeralEquivalence() {
        // 显式保留能力边界：这些反例通过格式检查，仍须后续语义评测阻止上线误用。
        assertThat(QueryRewriteContract.alternativeQuery("今天北京的政策？", output("明天上海的政策")))
                .isEqualTo("明天上海的政策");
        assertThat(QueryRewriteContract.alternativeQuery("三天内申请？", output("五天内申请")))
                .isEqualTo("五天内申请");
    }

    private com.fasterxml.jackson.databind.node.ObjectNode output(String alternative) {
        return json.createObjectNode().put("alternativeQuery", alternative);
    }

    private void assertRejected(String original, JsonNode node) {
        assertThatThrownBy(() -> QueryRewriteContract.alternativeQuery(original, node))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ResultCode.BIZ_ERROR.getCode());
                    assertThat(error.getMessage()).isEqualTo("检索扩展数据校验未通过");
                    assertThat(error.getCause()).isNull();
                });
    }
}
