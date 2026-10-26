package com.ragqa.config;

import com.ragqa.mapper.KnowledgeRetrievalMapper;
import com.ragqa.mapper.KnowledgeVectorPublicationMapper;
import com.ragqa.rag.retrieval.HybridKnowledgeRetriever;
import com.ragqa.rag.retrieval.KeywordKnowledgeRetriever;
import com.ragqa.rag.retrieval.KnowledgeRetriever;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import com.ragqa.service.EnterpriseResourceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * 企业问答检索选择；本类覆盖混合实现初始化时“尚未接正式问答”的历史说明。
 *
 * <p>一、设计及替代：在装配层选择已有KnowledgeRetriever策略，默认复用原关键词Bean。
 * 不按dev/prod自动开启，也不在控制器接收选择参数，避免环境切换或用户输入扩大数据处理范围。
 * Primary消除两个接口Bean的注入歧义，Qualifier固定原关键词实例，避免自引用。</p>
 * <p>二、配置和调用：knowingorder.retrieval.hybrid.enabled默认false；启用时必须同时开启
 * knowingorder.embedding.enabled、knowingorder.qdrant.enabled，填写本机embedding.model，
 * 并显式提供hybrid.similarity-threshold（有限数[-1,1]）及hybrid.rrf-smoothing（整数1至1000）。
 * 这里hybrid均指knowingorder.retrieval.hybrid前缀，不填写未经评测的生产默认阈值。
 * 可通过JVM -D属性或Spring配置注入，不需要新增yml、更改模型密钥或数据库迁移。
 * 本工厂方法只做配置与对象装配，不访问数据库、模型或Qdrant；无运行时自动回退。
 * KnowledgeAnswerService按接口获得选中实现，引用仍复核当前业务资料权限。
 * Answer.mode仍只表达LOCAL_EVIDENCE/SYNTHETIC_MODEL外发许可，不用它混淆召回方式。</p>
 * <p>三、边界：开启但漏参数、关客户端或非法阈值时明确启动失败，异常不回显配置值。
 * 配置正确不证明服务已启动、索引READY或语义质量合格；运行故障沿用业务异常向前端反馈。
 * 双槽/15秒阶段预算及当前索引复核沿用混合实现；不增加重试、缓存或企业资料外发许可。
 * 四、优化：先测真实召回、延迟和容量再校准参数；热切换需处理在途任务和配置快照，
 * 本轮只采用启动时固定策略，避免为尚无需求的热更新增加状态复杂度。
 * 学习点：Spring依赖注入、策略装配、Primary/Qualifier、失败即停止和独立数据外发边界；未用MCP。</p>
 * <p>本批还精确修改回答服务的检索文案，并由KnowledgeRetrievalConfigTest验证装配、服务和MVC契约；
 * 本轮按三文件上限，四方面记录暂存本注释，待后续文档单元归档至开发待办。
 * 开工GitHub检索Spring官方配置方案未取得可用正文，复用项目已有Spring依赖，不新增SDK。</p>
 * <p>2026-10-24定向134项通过，5份XML与testcase数量一致，失败/错误/跳过均0，43.658秒；
 * 新装配/MVC35项、原回答14项、混合编排52项、追问18项、安全HTTP15项。本轮首轮即通过。
 * 样例：开启混合且未设外发白名单，提问“餐补多少”仍返回LOCAL_EVIDENCE/EVIDENCE_ONLY及原文；
 * 缺发布索引则HTTP200、Result.code=503，不返回NO_EVIDENCE，不回退关键词；沿用原异常协议。
 * 在backend沿用E盘TEMP/TMP/java.io.tmpdir运行mvn -o
 * -Dtest=KnowledgeRetrievalConfigTest,KnowledgeAnswerServiceTest,HybridKnowledgeRetrieverTest,KnowledgeFollowUpModuleTest,EnterpriseSecurityHttpTest test。
 * 日志E:/Codex/test-artifacts/hybrid-wiring-first-20261024-maven.log；XML后缀hybrid-wiring-first-20261024。
 * 测试使用隔离H2或方法替身，未连接开发MySQL/真实模型/Qdrant、修改YAML、启用本机开关或下载模型。
 * 终端未继承PYTHONUTF8=1，本次命令在进程内设置，未改全局变量；源码检查UTF-8无BOM、LF。
 * 下一步归档文档、补独立检索方式说明，再准备真实本机服务验收；7条改写语义漂移未由本轮修复。</p>
 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeRetrievalConfig {
    private static final String PREFIX = "knowingorder.retrieval.hybrid.";

    @Bean
    @Primary
    public KnowledgeRetriever selectedKnowledgeRetriever(
            @Qualifier("keywordKnowledgeRetriever") KeywordKnowledgeRetriever keyword,
            KnowledgeRetrievalMapper evidence, KnowledgeVectorPublicationMapper publications,
            EnterpriseResourceService resources, LocalEmbeddingClient embedding, QdrantVectorClient vectors,
            Environment environment) {
        if (!flag(environment, PREFIX + "enabled")) return keyword;
        if (!flag(environment, "knowingorder.embedding.enabled") || !flag(environment, "knowingorder.qdrant.enabled"))
            throw invalid("启用混合检索须同时启用本机Embedding与Qdrant客户端");
        String model = environment.getProperty("knowingorder.embedding.model", "");
        if (model.isBlank()) throw invalid("启用混合检索须配置本机Embedding模型名，不会自动下载模型");
        double threshold;
        int smoothing;
        try {
            threshold = Double.parseDouble(environment.getProperty(PREFIX + "similarity-threshold", "").strip());
            smoothing = Integer.parseInt(environment.getProperty(PREFIX + "rrf-smoothing", "").strip());
        } catch (IllegalArgumentException ex) {
            throw invalid("启用混合检索须显式配置有限相似度阈值[-1,1]和RRF整数常数[1,1000]");
        }
        if (!Double.isFinite(threshold) || threshold < -1 || threshold > 1 || smoothing < 1 || smoothing > 1000)
            throw invalid("混合检索相似度阈值须为有限数[-1,1]，RRF整数常数须为[1,1000]");
        return new HybridKnowledgeRetriever(evidence, publications, resources, embedding, vectors, threshold, smoothing);
    }

    private static boolean flag(Environment environment, String name) {
        return switch (environment.getProperty(name, "false").strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid("检索相关开关仅允许true或false，请核对配置项名称及取值");
        };
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
