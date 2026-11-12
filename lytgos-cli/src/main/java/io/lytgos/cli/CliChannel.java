package io.lytgos.cli;

import io.lytgos.core.LytgosException;
import io.lytgos.core.agent.ReActLoop;
import io.lytgos.core.session.Session;
import io.lytgos.core.session.SessionStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 交互模式的读-跑-打循环。
 *
 * <p>异常在这里被吃掉而不是冒泡退出进程：交互模式的价值就是「错了还能接着问」。
 * 但每轮结束后必须落盘——包括出错的那一轮，因为失败原因本身也是历史的一部分。</p>
 */
public final class CliChannel {

    private final ReActLoop loop;
    private final SessionStore sessions;
    private final Session session;

    public CliChannel(ReActLoop loop, SessionStore sessions, Session session) {
        this.loop = loop;
        this.sessions = sessions;
        this.session = session;
    }

    public void runInteractive() {
        System.out.println("已进入交互模式，输入 exit 退出。");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        while (true) {
            System.out.print("> ");
            System.out.flush();

            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                System.err.println("读取输入失败: " + e.getMessage());
                return;
            }
            if (line == null) {
                break;
            }

            String input = line.strip();
            if (input.isEmpty()) {
                continue;
            }
            if ("exit".equalsIgnoreCase(input)) {
                break;
            }

            try {
                System.out.println(loop.run(session, input));
            } catch (LytgosException e) {
                System.err.println(e.getMessage());
            } catch (RuntimeException e) {
                System.err.println("对话失败: " + e.getMessage());
            } finally {
                sessions.save(session);
            }
        }
    }
}
