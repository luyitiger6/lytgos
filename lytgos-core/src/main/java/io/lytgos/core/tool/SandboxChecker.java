package io.lytgos.core.tool;

import io.lytgos.core.LytgosException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 沙箱校验器：文件路径、Shell 命令、HTTP 域名三类。
 *
 * <p>构造时传入的白名单全部做不可变拷贝，运行期无法被改写。三类校验的缺口策略一致：
 * <b>白名单为空时一律拒绝</b>——默认拒绝而不是默认放行，是这套治理的地基。</p>
 *
 * <p>路径校验不接受「字符串里有没有 {@code ..}」这种判断：先规范化再比前缀，
 * 否则 {@code work/../../etc} 这类绕过只需一个符号链接就能成立。</p>
 */
public final class SandboxChecker {

    private final Path workspaceRoot;
    private final Set<String> allowedCommands;
    private final Set<String> allowedDomains;

    /**
     * @param workspaceRoot   工作区根目录，路径校验的边界
     * @param allowedCommands 允许执行的命令名（不含路径与 {@code .exe} 后缀）
     * @param allowedDomains  允许访问的域名，精确匹配，忽略大小写
     */
    public SandboxChecker(Path workspaceRoot, Set<String> allowedCommands, Set<String> allowedDomains) {
        this.workspaceRoot = canonicalize(workspaceRoot);
        this.allowedCommands = Set.copyOf(allowedCommands);
        this.allowedDomains = allowedDomains.stream()
                .map(d -> d.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 校验路径落在工作区内，返回规范化后的绝对路径。 */
    public Path checkPath(Path candidate) {
        Path real = canonicalize(candidate);
        if (!real.startsWith(workspaceRoot)) {
            throw new LytgosException.SandboxViolation(
                    "路径越界: " + candidate + " 不在工作区 " + workspaceRoot + " 内");
        }
        return real;
    }

    /** 校验命令的可执行文件名在白名单内，返回该文件名。 */
    public String checkCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new LytgosException.SandboxViolation("命令为空");
        }
        String name = normalizeName(executableOf(command.trim()));
        if (allowedCommands.isEmpty()) {
            throw new LytgosException.SandboxViolation("命令白名单为空，全部拒绝: " + name);
        }
        if (!allowedCommands.contains(name)) {
            throw new LytgosException.SandboxViolation(
                    "命令 " + name + " 不在白名单 " + allowedCommands + " 内");
        }
        return name;
    }

    /**
     * 从命令声明里取出可执行文件那一段。
     *
     * <p>三种写法都要认：{@code java}、{@code "C:\Program Files\...\java.exe"}、
     * {@code java -Xmx1g}（内联参数）。不能一律按第一个空格切——Windows 路径
     * 常在 {@code Program Files} 这种带空格的目录下，切完只剩 {@code Program}，
     * 明明放行了 java 却报「Program 不在白名单」。判据是「按整串取出的文件名
     * 已在白名单里」：是就用整串，否则退回第一个空格前的部分。</p>
     */
    private String executableOf(String command) {
        if (command.startsWith("\"")) {
            int closing = command.indexOf('"', 1);
            return closing > 0 ? command.substring(1, closing) : command.substring(1);
        }
        if (allowedCommands.contains(normalizeName(command))) {
            return command;
        }
        return command.split("\\s+", 2)[0];
    }

    /** 取路径最后一段作为命令名，并去掉 Windows 的 {@code .exe} 后缀。 */
    private static String normalizeName(String executable) {
        int lastSeparator = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        String name = lastSeparator >= 0 ? executable.substring(lastSeparator + 1) : executable;
        if (name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    /**
     * 当前允许访问的域名（已统一小写）。供工具在 {@code spec()} 里声明给模型看——
     * 模型不知道白名单就只能猜域名，猜错一次就浪费一轮对话。
     * 告诉模型规则不等于放宽规则，真正的拦截仍然在 {@link #checkDomain(String)} 里。
     */
    public Set<String> allowedDomains() {
        return allowedDomains;
    }

    /** 校验 URL 的协议与域名，返回解析后的 URI。 */
    public URI checkDomain(String url) {
        if (url == null || url.isBlank()) {
            throw new LytgosException.InvalidToolArgument("URL 为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            // 模型自己拼 URL 时常把未转义的 % 或空格写进 query（如 wttr.in 的 ?format=%l:+%c）。
            // 这是输入格式错误，不是白名单拦截。混成同一个异常会让 ToolExecutor 把它当
            // 安全事件中断整轮对话，模型失去改正的机会——而它明明只需要把 % 写成 %25。
            throw new LytgosException.InvalidToolArgument(
                    "URL 格式非法（query 里的 % 要写成 %25，空格写成 %20）: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            // 协议限制是策略约束，不是格式错：file:///etc/passwd 是拿别的协议读本地文件，
            // 归到“只允许 http/https”这条安全边界里。
            throw new LytgosException.SandboxViolation("只允许 http/https 协议: " + url);
        }
        if (uri.getUserInfo() != null) {
            // http://api.open-meteo.com@evil.com/ 这类把白名单域名塞进 userinfo 的写法必须挡住
            throw new LytgosException.SandboxViolation("URL 不允许携带 userinfo: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new LytgosException.InvalidToolArgument("URL 缺少 host: " + url);
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (allowedDomains.isEmpty()) {
            throw new LytgosException.SandboxViolation("域名白名单为空，全部拒绝: " + normalized);
        }
        if (!allowedDomains.contains(normalized)) {
            throw new LytgosException.SandboxViolation(
                    "域名 " + normalized + " 不在白名单 " + allowedDomains + " 内");
        }
        return uri;
    }

    /**
     * 取得路径的真实形态：存在的前缀段解析符号链接，不存在的尾段按字面保留。
     *
     * <p>不能直接用 {@link Path#toRealPath()}——目标文件可能尚未创建；
     * 也不能只做 {@link Path#normalize()}——那不解符号链接。</p>
     */
    private static Path canonicalize(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        Deque<Path> missingTail = new ArrayDeque<>();
        while (existing != null && !Files.exists(existing)) {
            missingTail.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException e) {
            throw new LytgosException("无法解析真实路径: " + path, e);
        }
        for (Path segment : missingTail) {
            real = real.resolve(segment);
        }
        return real;
    }
}
