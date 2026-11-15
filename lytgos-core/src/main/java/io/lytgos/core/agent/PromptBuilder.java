package io.lytgos.core.agent;

import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.Message;
import io.lytgos.core.session.Session;
import io.lytgos.core.util.LogSafe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 Profile、技能、Bootstrap 文件与历史拼成一次请求的消息序列。
 *
 * <p>拼的是结构化消息而不是一整段字符串文本：工具声明要走 {@code ChatRequest.tools} 独立字段，历史要走 role/content 结构，塞进一个字符串会丢掉 role
 * 语义， 上游模型也就无从区分谁说的话。
 *
 * <p><b>每次 {@link #build(Session)} 都重新读盘</b>：启动时一次性拼好的话，用户改完 AGENT.md / MEMORY.md 必须重启进程才生效。多读几个小于
 * 64KB 的文件，换「改文件即生效」，不值得引入缓存失效逻辑。
 *
 * <p>顺序固定：身份 → 任务指令 → 技能 → Bootstrap → 长期记忆 → 当前日期。记忆放在身份之后，是因为 AGENT.md / SOUL.md / USER.md
 * 定义「我是谁」，MEMORY.md 记录「我记得什么」——记忆不该覆盖身份设定。
 */
public final class PromptBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(PromptBuilder.class);

  /** 历史消息上限。超过后从最早的消息开始丢，但不切断 tool 调用与结果的配对。 */
  private static final int MAX_HISTORY_MESSAGES = 40;

  /** 记忆注入上限（字符）。与 MemoryStore 的写入上限一致，超出截断并标注。 */
  public static final int MAX_MEMORY_CHARS = 8000;

  /** 技能清单文件在技能目录下的固定文件名。 */
  public static final String SKILL_FILE = "SKILL.md";

  private final Profile profile;
  private final Path workspaceRoot;
  private final String instructions;
  private final Clock clock;

  private PromptBuilder(Profile profile, Path workspaceRoot, String instructions, Clock clock) {
    this.profile = profile;
    this.workspaceRoot = workspaceRoot;
    this.instructions = instructions;
    this.clock = clock;
  }

  /**
   * 从 Profile 构造。
   *
   * @param instructions {@code AGENT.md} 中 {@code ---} 之后的正文，作为任务指令
   */
  public static PromptBuilder from(Profile profile, Path workspaceRoot, String instructions) {
    return from(profile, workspaceRoot, instructions, Clock.systemDefaultZone());
  }

  /** 可注入时钟的构造器：测试日期注入时不必依赖机器当天时间。 */
  public static PromptBuilder from(
      Profile profile, Path workspaceRoot, String instructions, Clock clock) {
    return new PromptBuilder(profile, workspaceRoot, instructions, clock);
  }

  /**
   * 组装一次请求的消息序列：system + 历史。
   *
   * <p>本轮输入由调用方先写入 Session 再调用本方法——ReAct 循环要跑多轮， 只有第一轮有「本轮输入」，后续轮次都是重放历史，把输入当作参数传会让多轮时重复拼接。
   *
   * <p>历史裁剪按「用户消息」边界对齐。若从中间直接切，可能留下一条没有对应 assistant 调用的 tool 结果，上游会以 400 拒绝整个请求——裁断本身就成了故障源。
   */
  public List<Message> build(Session session) {
    List<Message> messages = new ArrayList<>();
    String system = systemPrompt();
    if (!system.isBlank()) {
      messages.add(Message.system(system));
    }
    messages.addAll(trim(session.history()));
    return messages;
  }

  private String systemPrompt() {
    StringBuilder prompt = new StringBuilder();

    if (profile.identity() != null && !profile.identity().isBlank()) {
      prompt.append(profile.identity().strip()).append("\n\n");
    }
    if (instructions != null && !instructions.isBlank()) {
      prompt.append(instructions.strip()).append("\n\n");
    }
    appendSkills(prompt);
    appendBootstrap(prompt);
    appendMemory(prompt);
    appendDate(prompt);

    return prompt.toString().strip();
  }

  /**
   * 注入技能摘要。
   *
   * <p>只注入 name / description 与文件路径，不注入全文：技能正文可能很长，全量注入会挤掉 真正与当前任务相关的上下文。模型需要细节时按路径自行读取（渐进式披露）。
   */
  private void appendSkills(StringBuilder prompt) {
    if (profile.skills().isEmpty()) {
      return;
    }
    prompt.append("## 可用技能\n\n");
    for (String skill : profile.skills()) {
      requirePlainName(skill, "skills");
      Path file = skillFile(skill);
      if (!Files.isRegularFile(file)) {
        throw new LytgosException.InvalidAgentDefinitionException(
            "Agent " + profile.name() + " 声明的技能 " + skill + " 缺少 " + SKILL_FILE + "，期望路径: " + file);
      }
      Map<String, String> front = frontmatter(file);
      String name = front.get("name");
      String description = front.get("description");
      if (name == null || name.isBlank() || description == null || description.isBlank()) {
        throw new LytgosException.InvalidAgentDefinitionException(
            "技能定义缺少 frontmatter 的 name 或 description: " + file);
      }
      prompt.append("### ").append(name.strip()).append("\n\n");
      prompt.append(description.strip()).append("\n\n");
      prompt.append("完整内容见：").append(relativeSkillPath(skill)).append("\n\n");
    }
  }

  private Path skillFile(String skill) {
    return workspaceRoot
        .resolve("agents")
        .resolve(profile.name())
        .resolve("skills")
        .resolve(skill)
        .resolve(SKILL_FILE);
  }

  private String relativeSkillPath(String skill) {
    return "agents/" + profile.name() + "/skills/" + skill + "/" + SKILL_FILE;
  }

  /**
   * 读取 SKILL.md 的 frontmatter。
   *
   * <p>这里不引 YAML 依赖：技能 frontmatter 只需要 {@code name} 与 {@code description} 两个单行
   * 字段，手写解析比为一个字段拉一个解析器更可控；多行/嵌套结构不在此处支持，遇到就按缺失处理。
   */
  private static Map<String, String> frontmatter(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new LytgosException.InvalidAgentDefinitionException("技能文件读取失败: " + file);
    }
    Map<String, String> result = new LinkedHashMap<>();
    int index = 0;
    while (index < lines.size() && lines.get(index).isBlank()) {
      index++;
    }
    if (index >= lines.size() || !"---".equals(lines.get(index).strip())) {
      return result;
    }
    index++;
    for (; index < lines.size(); index++) {
      String line = lines.get(index);
      if ("---".equals(line.strip())) {
        break;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String key = line.substring(0, colon).strip();
      String value = line.substring(colon + 1).strip();
      if (isQuoted(value)) {
        value = value.substring(1, value.length() - 1);
      }
      result.put(key, value);
    }
    return result;
  }

  /** 首尾是同一类引号（单引号或双引号）的字符串才算驱动引号。 */
  private static boolean isQuoted(String value) {
    if (value.length() < 2) {
      return false;
    }
    return (value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'"));
  }

  /**
   * 注入 Bootstrap 文件。
   *
   * <p>缺失时记 WARN 后跳过而不是静默 continue：文件是 init 建的、用户可能误删或写错名字， 静默跳过会让「我明明写了 AGENTS.md」变成一次无解的排查。
   */
  private void appendBootstrap(StringBuilder prompt) {
    for (String fileName : profile.bootstrap()) {
      requirePlainName(fileName, "bootstrap");
      Path file = workspaceRoot.resolve(fileName);
      if (!Files.isRegularFile(file)) {
        LOG.warn("Bootstrap 文件缺失，已跳过: {}", LogSafe.sanitize(file));
        continue;
      }
      try {
        String content = Files.readString(file, StandardCharsets.UTF_8).strip();
        if (!content.isEmpty()) {
          prompt.append("## ").append(fileName).append("\n\n").append(content).append("\n\n");
        }
      } catch (IOException e) {
        LOG.warn("Bootstrap 文件读取失败，跳过: {}", LogSafe.sanitize(file));
      }
    }
  }

  /**
   * 注入长期记忆。
   *
   * <p>文件缺失或为空直接跳过——新工作区还没记忆是常态，不是故障。
   */
  private void appendMemory(StringBuilder prompt) {
    Path file = workspaceRoot.resolve("memory").resolve("MEMORY.md");
    if (!Files.isRegularFile(file)) {
      return;
    }
    String content;
    try {
      content = Files.readString(file, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      LOG.warn("记忆文件读取失败，跳过: {}", LogSafe.sanitize(file));
      return;
    }
    if (content.isEmpty()) {
      return;
    }
    prompt.append("## 长期记忆（MEMORY.md）\n\n");
    if (content.length() <= MAX_MEMORY_CHARS) {
      prompt.append(content).append("\n\n");
      return;
    }
    prompt
        .append(content, 0, MAX_MEMORY_CHARS)
        .append("\n\n[记忆已截断，仅注入前 ")
        .append(MAX_MEMORY_CHARS)
        .append(" 字符，原始长度 ")
        .append(content.length())
        .append(" 字符]\n\n");
  }

  /** 日期放在末尾：它是每次请求都会变的唯一字段，放最后便于人眼核对新鲜度。 */
  private void appendDate(StringBuilder prompt) {
    prompt.append("## 当前日期\n\n").append(LocalDate.now(clock)).append("\n");
  }

  private static List<Message> trim(List<Message> history) {
    if (history.size() <= MAX_HISTORY_MESSAGES) {
      return history;
    }
    int start = history.size() - MAX_HISTORY_MESSAGES;
    while (start > 0 && history.get(start).role() != Message.Role.USER) {
      start--;
    }
    return List.copyOf(history.subList(start, history.size()));
  }

  /**
   * Bootstrap 文件名与技能目录名只允许单层名称。
   *
   * <p>这些值来自 AGENT.md，属于用户可编辑内容；放行 {@code ../} 就等于让一份 AGENT.md 读到工作区外任意文件。
   */
  private static void requirePlainName(String name, String field) {
    if (name == null
        || name.isBlank()
        || name.contains("/")
        || name.contains("\\")
        || name.contains("..")) {
      throw new LytgosException.InvalidAgentDefinitionException(
          field + " 只允许单层名称（不含路径分隔符与 ..），当前为: " + name);
    }
  }
}
