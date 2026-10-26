package com.ragqa.rag.llm;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 从固定白名单加载带版本的模板；不接受用户传入路径，启动时发现缺失资源。 */
@Component
public final class PromptRegistry {

    private static final Map<String, String> ACTIVE = Map.ofEntries(
            Map.entry("plan", "v3"), Map.entry("grade", "v2"), Map.entry("generate", "v2"),
            Map.entry("knowledge", "v1"), Map.entry("rewrite", "v1"), Map.entry("follow-up", "v1"),
            Map.entry("follow-up-review", "v1"), Map.entry("knowledge-follow-up", "v1"),
            Map.entry("knowledge-scope", "v1"), Map.entry("knowledge-scoped", "v1"),
            Map.entry("knowledge-support", "v1"), Map.entry("rewrite-review", "v1"));
    private final Map<String, Prompt> prompts;

    public PromptRegistry() {
        prompts = ACTIVE.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> load(entry.getKey(), entry.getValue())));
    }

    public Prompt get(String name) {
        Prompt prompt = prompts.get(name);
        if (prompt == null) {
            throw new IllegalArgumentException("未知的服务端模板名称");
        }
        return prompt;
    }

    private static Prompt load(String name, String version) {
        return new Prompt(name + ":" + version, read(name, version, "system"), read(name, version, "user"));
    }

    private static String read(String name, String version, String role) {
        String path = "prompts/" + name + "/" + version + "_" + role + ".txt";
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            byte[] bytes = stream.readNBytes(16385);
            if (bytes.length > 16384) {
                throw new IllegalStateException("服务端模板超过长度限制");
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.isBlank() || text.charAt(0) == '\uFEFF') {
                throw new IllegalStateException("服务端模板为空或含 BOM");
            }
            return text;
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("服务端模板必须使用 UTF-8 编码");
        } catch (IOException exception) {
            throw new IllegalStateException("服务端模板读取失败：" + name + ":" + version);
        }
    }

    /** 只替换一次固定占位符，输入中的占位符不递归执行。资料用 JSON 表达数据边界。 */
    public record Prompt(String id, String system, String userTemplate) {
        public Prompt {
            if (userTemplate.indexOf("{{payload}}") < 0
                    || userTemplate.indexOf("{{payload}}") != userTemplate.lastIndexOf("{{payload}}")) {
                throw new IllegalStateException("服务端模板必须包含一个 payload 占位符");
            }
        }

        public String render(String json) {
            return userTemplate.replace("{{payload}}", json);
        }
    }
}
