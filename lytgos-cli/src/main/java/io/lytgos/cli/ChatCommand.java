package io.lytgos.cli;

import io.lytgos.core.agent.ReActLoop;
import io.lytgos.core.session.Session;
import io.lytgos.core.session.SessionStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code lytgos chat}：交互模式与 {@code --message} 单条模式。
 *
 * <p>两种模式共用同一个 Session（键为 {@code cli:local:<agent>}），
 * 所以「先 --message 报名字，再交互问我是谁」能接上——这不是便利功能，
 * 是验证会话确实落盘的唯一用户可见证据。</p>
 */
@Command(name = "chat", description = "与 Agent 对话（交互模式或单条模式）")
public final class ChatCommand implements Runnable {

    @Option(names = {"-m", "--message"}, description = "单条模式：发送一条消息后退出")
    private String message;

    @Option(names = {"-a", "--agent"}, defaultValue = "default", description = "Agent 名称（默认 default）")
    private String agent;

    private final LazyContext context;

    private final CliContext cliContext = CliContext.instance();

    public ChatCommand(LazyContext context) {
        this.context = context;
    }

    @Override
    public void run() {
        // 必须在 try 里：runtimeFor 可能已经拉起了 MCP 子进程，
        // 不关就会在用户机器上留个孤儿
        try (AgentRuntime runtime = cliContext.runtimeFor(agent)) {
            ReActLoop loop = runtime.loop();
            SessionStore store = cliContext.sessions();
            Session session = store.get(cliContext.sessionKey(agent));

            if (message != null) {
                String reply;
                try {
                    reply = loop.run(session, message);
                } finally {
                    // 即便对话抛异常也要落盘：历史里可能已经写入了工具调用与失败原因，
                    // 丢了它下一轮会因「有调用无结果」被上游拒绝
                    store.save(session);
                }
                System.out.println(reply);
                return;
            }

            new CliChannel(loop, store, session).runInteractive();
        }
    }
}
