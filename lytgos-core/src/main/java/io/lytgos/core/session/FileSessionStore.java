package io.lytgos.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话的文件实现：内存缓存 + JSON 落盘。
 *
 * <p>为什么不是纯内存：{@code --message} 单条模式发一条就退出进程，
 * 纯内存方案下两次单条调用物理上不可能共享上下文，「单条模式与交互模式共用同一 Session」
 * 这条口径就无法成立。</p>
 *
 * <p>文件名用 key 的 Base64URL 编码而不是直接拼 key：{@code cli:local:default} 里的冒号
 * 在 Windows 文件名里非法，而把冒号替换成下划线又会让 {@code a:b} 与 {@code a_b} 撞名。</p>
 *
 * <p>写入走「临时文件 + 原子改名」：直接覆盖原文件时若进程在写一半时退出，
 * 留下的是半截 JSON，下次启动读不出来。</p>
 */
public final class FileSessionStore implements SessionStore {

    private static final Logger LOG = LoggerFactory.getLogger(FileSessionStore.class);

    private final Path directory;
    private final ObjectMapper mapper;
    private final Map<String, Session> cache = new ConcurrentHashMap<>();

    public FileSessionStore(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new LytgosException("无法创建会话目录: " + directory, e);
        }
    }

    @Override
    public Session get(String key) {
        return cache.computeIfAbsent(key, this::load);
    }

    @Override
    public void save(Session session) {
        Path file = fileOf(session.key());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Snapshot snapshot = new Snapshot(session.key(), session.history());
            Files.writeString(temp, mapper.writeValueAsString(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new LytgosException("会话落盘失败: " + file, e);
        }
    }

    private Session load(String key) {
        Path file = fileOf(key);
        Session session = new Session(key);
        if (!Files.exists(file)) {
            return session;
        }
        try {
            Snapshot snapshot = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Snapshot.class);
            session.restore(snapshot.messages() == null ? List.of() : snapshot.messages());
        } catch (IOException e) {
            // 坏文件不静默丢弃：改名留证，再从空历史开始，否则每次启动都会踩同一颗雷
            Path broken = file.resolveSibling(file.getFileName() + ".broken");
            try {
                Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 改不了名就保留原文件，异常信息里已带路径，人工可查
            }
            LOG.warn("会话文件损坏，已备份为 {}: {}", broken.getFileName(), e.getMessage());
        }
        return session;
    }

    private Path fileOf(String key) {
        String name = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return directory.resolve(name + ".json");
    }

    /**
     * 落盘结构。
     *
     * <p>刻意与 {@link Session} 分开：内核类型不该为了序列化去挂 Jackson 注解，
     * 落盘格式变化也不该逼着内核类型跟着改。</p>
     */
    record Snapshot(String key, List<Message> messages) {
    }
}
