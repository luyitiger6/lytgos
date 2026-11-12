package io.lytgos.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.chat.Message;
import io.lytgos.core.chat.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FileSessionStore：同键复用、跨进程恢复、键隔离与坏文件处理")
class FileSessionStoreTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("同一个键两次取到同一个 Session 实例")
    void sameKeyReturnsSameInstance() {
        FileSessionStore store = new FileSessionStore(dir, new ObjectMapper());
        assertSame(store.get("cli:local:default"), store.get("cli:local:default"));
    }

    @Test
    @DisplayName("进程重启后读回同一历史（含工具调用与工具结果）")
    void survivesRestart() {
        String key = "cli:local:default";
        FileSessionStore first = new FileSessionStore(dir, new ObjectMapper());
        Session session = first.get(key);
        session.append(Message.user("北京今天天气怎么样"));
        session.append(Message.assistant("", List.of(new ToolCall("c1", "http_get", "{\"url\":\"x\"}"))));
        session.append(Message.tool("c1", "http_get", "{\"temperature\":25}"));
        session.append(Message.assistant("北京今天晴，25 度"));
        first.save(session);

        // 新的 store 实例等价于进程重启：内存缓存为空，只能从磁盘恢复
        Session reloaded = new FileSessionStore(dir, new ObjectMapper()).get(key);

        assertEquals(4, reloaded.size());
        assertEquals(Message.Role.USER, reloaded.history().get(0).role());
        assertEquals("北京今天天气怎么样", reloaded.history().get(0).content());
        assertEquals(1, reloaded.history().get(1).toolCalls().size(), "assistant 的工具调用必须落盘");
        assertEquals("c1", reloaded.history().get(2).toolCallId());
        assertEquals("http_get", reloaded.history().get(2).toolName());
        assertEquals("北京今天晴，25 度", reloaded.history().get(3).content());
    }

    @Test
    @DisplayName("不同键互不影响")
    void differentKeysAreIsolated() {
        FileSessionStore store = new FileSessionStore(dir, new ObjectMapper());
        Session a = store.get("cli:local:a");
        Session b = store.get("cli:local:b");
        a.append(Message.user("只属于 a"));
        b.append(Message.user("只属于 b"));
        store.save(a);
        store.save(b);

        Session reloadedA = new FileSessionStore(dir, new ObjectMapper()).get("cli:local:a");
        Session reloadedB = new FileSessionStore(dir, new ObjectMapper()).get("cli:local:b");

        assertEquals(1, reloadedA.size());
        assertEquals("只属于 a", reloadedA.history().get(0).content());
        assertEquals(1, reloadedB.size());
        assertEquals("只属于 b", reloadedB.history().get(0).content());
    }

    @Test
    @DisplayName("键里的冒号等文件名非法字符不会导致落盘失败")
    void encodesIllegalFileNameChars() throws IOException {
        FileSessionStore store = new FileSessionStore(dir, new ObjectMapper());
        Session session = store.get("cli:local:a/b\\c");
        session.append(Message.user("hi"));
        store.save(session);

        try (var files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(1, names.size(), "实际落盘文件: " + names);
            assertTrue(names.get(0).endsWith(".json"));
            assertTrue(names.get(0).indexOf(':') < 0, "文件名里不能出现冒号: " + names.get(0));
        }
    }

    @Test
    @DisplayName("坏文件改名留证，并从空历史开始")
    void backsUpBrokenFile() throws IOException {
        String key = "cli:local:default";
        FileSessionStore probe = new FileSessionStore(dir, new ObjectMapper());
        probe.get(key);
        // 先用一次保存确定文件名，再把它写坏
        Session seed = probe.get(key);
        seed.append(Message.user("x"));
        probe.save(seed);
        Path saved;
        try (var files = Files.list(dir)) {
            saved = files.findFirst().orElseThrow();
        }
        Files.writeString(saved, "{ 这不是 JSON", StandardCharsets.UTF_8);

        Session recovered = new FileSessionStore(dir, new ObjectMapper()).get(key);

        assertEquals(0, recovered.size(), "坏文件不能半信半疑地解析，应放弃并从空历史开始");
        assertTrue(Files.exists(saved.resolveSibling(saved.getFileName() + ".broken")),
                "坏文件应改名保留，供人工排查");
    }
}
