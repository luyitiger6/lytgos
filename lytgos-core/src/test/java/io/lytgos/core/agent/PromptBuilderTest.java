package io.lytgos.core.agent;

import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.Message;
import io.lytgos.core.chat.ToolCall;
import io.lytgos.core.session.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PromptBuilder：system prompt 组装与历史裁剪")
class PromptBuilderTest {

    @TempDir
    Path workspace;

    private static Profile profile(List<String> bootstrap) {
        return new Profile(
                "tester",
                "测试用 Agent",
                "你是一个测试助手。",
                new Profile.ProviderRef("deepseek", "deepseek-chat"),
                List.of(),
                List.of(),
                List.of("cli"),
                bootstrap,
                Map.of());
    }

    @Test
    @DisplayName("system prompt 含 identity、任务指令与 bootstrap 内容")
    void includesIdentityInstructionsAndBootstrap() throws IOException {
        Files.writeString(workspace.resolve("SOUL.md"), "语气克制，不堆形容词。", StandardCharsets.UTF_8);

        PromptBuilder builder = PromptBuilder.from(
                profile(List.of("SOUL.md", "MISSING.md")), workspace, "按用户要求回答。");
        List<Message> messages = builder.build(new Session("cli:local:tester"));

        assertEquals(1, messages.size(), "空历史时只应有一条 system 消息");
        Message system = messages.get(0);
        assertEquals(Message.Role.SYSTEM, system.role());
        assertTrue(system.content().contains("你是一个测试助手。"), "缺 identity");
        assertTrue(system.content().contains("按用户要求回答。"), "缺任务指令");
        assertTrue(system.content().contains("## SOUL.md"), "缺 bootstrap 标题");
        assertTrue(system.content().contains("语气克制，不堆形容词。"), "缺 bootstrap 正文");
        assertFalse(system.content().contains("MISSING.md"), "不存在的 bootstrap 文件应被静默跳过");
    }

    @Test
    @DisplayName("历史按原顺序追加在 system 之后")
    void appendsHistoryAfterSystem() {
        Session session = new Session("cli:local:tester");
        session.append(Message.user("你好"));
        session.append(Message.assistant("你好，有什么可以帮你？"));

        List<Message> messages = PromptBuilder.from(profile(List.of()), workspace, "").build(session);

        assertEquals(3, messages.size());
        assertEquals(Message.Role.SYSTEM, messages.get(0).role());
        assertEquals(Message.Role.USER, messages.get(1).role());
        assertEquals("你好", messages.get(1).content());
        assertEquals(Message.Role.ASSISTANT, messages.get(2).role());
    }

    @Test
    @DisplayName("历史超限时按用户消息边界裁剪，不切断工具调用与结果的配对")
    void trimsAtUserBoundary() {
        Session session = new Session("cli:local:tester");
        // 先垫 12 条，使 40 条窗口的起点恰好落在一轮中间的 TOOL 上，强制触发回退
        for (int i = 0; i < 12; i++) {
            session.append(Message.user("旧消息 " + i));
        }
        for (int i = 0; i < 20; i++) {
            session.append(Message.user("问题 " + i));
            session.append(Message.assistant("", List.of(new ToolCall("c" + i, "http_get", "{}"))));
            session.append(Message.tool("c" + i, "http_get", "结果 " + i));
        }

        List<Message> messages = PromptBuilder.from(profile(List.of()), workspace, "").build(session);
        List<Message> history = messages.subList(1, messages.size());

        assertEquals(Message.Role.SYSTEM, messages.get(0).role());
        assertEquals(Message.Role.USER, history.get(0).role(),
                "裁剪后的第一条历史必须是 user，否则工具配对会残缺");

        long assistants = history.stream().filter(m -> m.role() == Message.Role.ASSISTANT).count();
        long tools = history.stream().filter(m -> m.role() == Message.Role.TOOL).count();
        assertEquals(assistants, tools, "每个带工具调用的 assistant 都必须带上对应的 tool 结果");
    }

    @Test
    @DisplayName("bootstrap 文件名带路径分隔符时拒绝加载")
    void rejectsBootstrapFilePath() {
        LytgosException.InvalidAgentDefinition thrown = assertThrows(
                LytgosException.InvalidAgentDefinition.class,
                () -> PromptBuilder.from(profile(List.of("../secret.md")), workspace, "指令"));
        assertTrue(thrown.getMessage().contains("../secret.md"));
    }
}
